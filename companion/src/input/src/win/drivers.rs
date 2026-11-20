//! The drivers the outputs go through: ViGEmBus (Xbox 360 and DualShock 4 pads), the optional GyroPhone pad
//! driver (DualSense) and the optional GyroPhone input driver (a virtual mouse and keyboard). Ports of
//! ViGEmPadSink.cs, DualSensePadSink.cs, PadSinks.cs and HidInputSink.cs; ViGEmBus is talked to directly, with
//! the control codes of its public client library.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use windows_sys::core::GUID;
use windows_sys::Win32::Devices::DeviceAndDriverInstallation::{CM_Get_Device_Interface_ListW, CM_Get_Device_Interface_List_SizeW};
use windows_sys::Win32::Foundation::{ERROR_ACCESS_DENIED, ERROR_INVALID_PARAMETER, ERROR_IO_PENDING, GENERIC_READ, GENERIC_WRITE, HANDLE, WAIT_OBJECT_0};
use windows_sys::Win32::Storage::FileSystem as fs;
use windows_sys::Win32::System::Threading as th;
use windows_sys::Win32::System::IO::{CancelIoEx, DeviceIoControl, GetOverlappedResult, OVERLAPPED};

use super::{last_error, Handle};
use crate::host::{DriverSink, InputDriver};
use crate::mapping::{Ds4Frame, InjectEvent, X360Frame};
use crate::ports::{KeyMouseSink, NullPadSink, PadSink, SinkError, SLOT_UNKNOWN};
use crate::reports::{ds4_report, dualsense_report, Ds4Motion, HidInputReports, HID_MAX_BYTES};
use crate::udp::Log;
use crate::wire::output_mode;

type RumbleHandler = Arc<dyn Fn(u8, u8) + Send + Sync>;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

// Win32 errors that mean the pad or the driver's device is gone, not a passing failure. Access denied on a
// handle that worked is a device being removed (delete pending), and how ViGEmBus refuses a target it lost.
fn is_gone(error: u32) -> bool {
    matches!(error, 2 | 5 | 6 | 22 | 55 | 433 | 1167 | 1617)
}

/// The paths of the present devices with this interface.
fn device_paths(interface: &GUID) -> Vec<Vec<u16>> {
    let mut len = 0u32;
    // SAFETY: valid pointers; no device id filter; present devices only (flags 0).
    if unsafe { CM_Get_Device_Interface_List_SizeW(&mut len, interface, std::ptr::null(), 0) } != 0 || len <= 1 {
        return Vec::new();
    }
    let mut buf = vec![0u16; len as usize];
    // SAFETY: the buffer is `len` characters.
    if unsafe { CM_Get_Device_Interface_ListW(interface, std::ptr::null(), buf.as_mut_ptr(), len, 0) } != 0 {
        return Vec::new();
    }
    // NUL-ended strings, the list ended by an empty one.
    buf.split(|c| *c == 0).filter(|s| !s.is_empty()).map(|s| s.iter().copied().chain(std::iter::once(0)).collect()).collect()
}

fn open_device(path: &[u16], flags: u32) -> Result<Handle, u32> {
    // SAFETY: a NUL-ended path; no security attributes, no template.
    let h = unsafe {
        fs::CreateFileW(path.as_ptr(), GENERIC_READ | GENERIC_WRITE, fs::FILE_SHARE_READ | fs::FILE_SHARE_WRITE, std::ptr::null(), fs::OPEN_EXISTING, flags, std::ptr::null_mut())
    };
    Handle::new(h).ok_or_else(last_error)
}

/// One control call on a handle opened without FILE_FLAG_OVERLAPPED; the bytes written to `output`.
fn control(h: &Handle, code: u32, input: &[u8], output: &mut [u8]) -> Result<u32, u32> {
    let mut returned = 0u32;
    // SAFETY: the buffers are of the lengths given and outlive the (synchronous) call.
    let ok = unsafe {
        DeviceIoControl(h.raw(), code, input.as_ptr().cast(), input.len() as u32, output.as_mut_ptr().cast(), output.len() as u32, &mut returned, std::ptr::null_mut())
    };
    if ok == 0 {
        Err(last_error())
    } else {
        Ok(returned)
    }
}

fn manual_event() -> Option<Handle> {
    // SAFETY: no attributes, manual reset, not set, no name.
    Handle::new(unsafe { th::CreateEventW(std::ptr::null(), 1, 0, std::ptr::null()) })
}

// =====================================================================================
// The GyroPhone pad driver: DualSense
// =====================================================================================

const DUALSENSE_INTERFACE: GUID = GUID { data1: 0x9B7491BC, data2: 0x0FE9, data3: 0x49AA, data4: [0xBA, 0x4A, 0xD5, 0xC8, 0x04, 0x0B, 0xE2, 0x26] };
const PAD_PROTOCOL: u32 = 1;
const PAD_VERSION: u32 = 0x222000;
const PAD_PLUG: u32 = 0x222004;
const PAD_UNPLUG: u32 = 0x222008;
const PAD_SUBMIT: u32 = 0x22200C;
const PAD_DUALSENSE: u32 = 1;

struct DualSenseState {
    handle: Option<Handle>,
    report: [u8; dualsense_report::SIZE],
}

/// A virtual DualSense through the optional GyroPhone pad driver (driver/ in the repo). The driver's device takes
/// a PLUG, then each 64-byte input report, then an UNPLUG; closing the handle unplugs too, so a crashed input
/// process leaves no pad behind. The control codes and protocol version match driver/src/gyropad_ioctl.h.
struct DualSense {
    log: Log,
    state: Mutex<DualSenseState>,
    /// A pad is plugged and hasn't gone away underneath.
    plugged: AtomicBool,
}

impl DualSense {
    /// The sink, or None when the driver isn't installed (its device interface isn't there).
    fn create(log: &Log) -> Option<DualSense> {
        if device_paths(&DUALSENSE_INTERFACE).is_empty() {
            return None;
        }
        log("GyroPhone pad driver found: DualSense output available");
        Some(DualSense { log: log.clone(), state: Mutex::new(DualSenseState { handle: None, report: [0; dualsense_report::SIZE] }), plugged: AtomicBool::new(false) })
    }

    fn plugged(&self) -> bool {
        self.plugged.load(Ordering::Acquire)
    }

    fn open_plugged() -> Result<Handle, String> {
        let paths = device_paths(&DUALSENSE_INTERFACE);
        let path = paths.first().ok_or("the GyroPhone pad driver is not installed")?;
        // Dropped (closed) on every failure below, so a refused plug leaves no handle holding the device.
        let h = open_device(path, 0).map_err(|e| format!("error {e}"))?;
        let mut version = [0u8; 8];
        match control(&h, PAD_VERSION, &[], &mut version) {
            Ok(4) if u32::from_le_bytes([version[0], version[1], version[2], version[3]]) == PAD_PROTOCOL => {}
            Ok(_) => return Err("the GyroPhone pad driver is a different version; run its install.cmd again".to_string()),
            Err(e) => return Err(format!("error {e}")),
        }
        let mut plug = [0u8; 8];
        plug[..4].copy_from_slice(&PAD_PROTOCOL.to_le_bytes());
        plug[4..].copy_from_slice(&PAD_DUALSENSE.to_le_bytes());
        control(&h, PAD_PLUG, &plug, &mut []).map_err(|e| format!("error {e}"))?;
        Ok(h)
    }

    fn plug(&self) -> bool {
        if self.plugged() {
            return true;
        }
        self.unplug(); // closes a pad lost underneath
        match Self::open_plugged() {
            Ok(h) => {
                lock(&self.state).handle = Some(h);
                self.plugged.store(true, Ordering::Release);
                true
            }
            Err(e) => {
                (self.log)(&format!("plug DualSense failed: {e}"));
                false
            }
        }
    }

    fn unplug(&self) {
        self.plugged.store(false, Ordering::Release);
        let h = lock(&self.state).handle.take();
        if let Some(h) = h {
            if let Err(e) = control(&h, PAD_UNPLUG, &[], &mut []) {
                (self.log)(&format!("unplug DualSense failed: error {e}"));
            }
        }
    }

    fn submit(&self, f: &Ds4Frame, m: &Ds4Motion) -> Result<(), SinkError> {
        if !self.plugged() {
            return Ok(());
        }
        // Never waits behind a plug or unplug (the pad thread's): that report is simply not sent.
        let Ok(mut s) = self.state.try_lock() else { return Ok(()) };
        let s = &mut *s;
        let Some(h) = s.handle.as_ref() else { return Ok(()) };
        dualsense_report::build(&mut s.report, f, m);
        match control(h, PAD_SUBMIT, &s.report, &mut []) {
            Err(e) if is_gone(e) => {
                // Plugging a new one blocks in the driver, so it is left to the engine's pad thread; the handle
                // is kept for its plug or unplug to close.
                if self.plugged.swap(false, Ordering::AcqRel) {
                    (self.log)("DualSense pad was unplugged underneath");
                }
                Err("the DualSense pad is gone")
            }
            _ => Ok(()),
        }
    }
}

impl Drop for DualSense {
    fn drop(&mut self) {
        self.unplug();
    }
}

// =====================================================================================
// ViGEmBus: Xbox 360 and DualShock 4
// =====================================================================================

const VIGEM_INTERFACE: GUID = GUID { data1: 0x96E42B22, data2: 0xF5E9, data3: 0x42F8, data4: [0xB0, 0x43, 0xED, 0x0F, 0x93, 0x2F, 0x01, 0x4F] };
const VIGEM_COMMON_VERSION: u32 = 1;
const VIGEM_PLUGIN_TARGET: u32 = 0x2AA004;
const VIGEM_UNPLUG_TARGET: u32 = 0x2AA008;
const VIGEM_CHECK_VERSION: u32 = 0x2AA00C;
const VIGEM_WAIT_DEVICE_READY: u32 = 0x2AA010;
const VIGEM_XUSB_REQUEST_NOTIFICATION: u32 = 0x2AE804;
const VIGEM_XUSB_SUBMIT_REPORT: u32 = 0x2AA808;
const VIGEM_DS4_SUBMIT_REPORT: u32 = 0x2AA80C;
const VIGEM_DS4_REQUEST_NOTIFICATION: u32 = 0x2AE810;
const VIGEM_XUSB_GET_USER_INDEX: u32 = 0x2AE81C;
const TARGET_XBOX360_WIRED: u32 = 0;
const TARGET_DUALSHOCK4_WIRED: u32 = 2;
/// Serial numbers tried for a new pad: other programs' pads on the bus hold the first ones.
const MAX_SERIAL: u32 = 256;

#[repr(C)]
struct PluginTarget {
    size: u32,
    serial: u32,
    target_type: u32,
    vendor_id: u16,
    product_id: u16,
}

#[repr(C)]
struct SerialOnly {
    size: u32,
    serial: u32,
}

#[repr(C)]
struct XusbSubmit {
    size: u32,
    serial: u32,
    buttons: u16,
    left_trigger: u8,
    right_trigger: u8,
    lx: i16,
    ly: i16,
    rx: i16,
    ry: i16,
}

#[repr(C)]
struct XusbNotification {
    size: u32,
    serial: u32,
    large_motor: u8,
    small_motor: u8,
    led_number: u8,
}

#[repr(C)]
struct XusbUserIndex {
    size: u32,
    serial: u32,
    user_index: u32,
}

/// DS4_SUBMIT_REPORT: the simple report (sticks, buttons, special, triggers), 20 bytes with its padding.
#[repr(C)]
struct Ds4Submit {
    size: u32,
    serial: u32,
    report: [u8; 9],
    _pad: [u8; 3],
}

/// DS4_SUBMIT_REPORT_EX: the full 63-byte report, packed (71 bytes).
#[repr(C, packed)]
struct Ds4SubmitEx {
    size: u32,
    serial: u32,
    report: [u8; ds4_report::SIZE],
}

#[repr(C)]
struct Ds4Notification {
    size: u32,
    serial: u32,
    small_motor: u8,
    large_motor: u8,
    lightbar: [u8; 3],
}

/// The open bus device. Opened for overlapped calls, as its client library does: each caller brings its own event.
struct Bus(Handle);

impl Bus {
    /// One control call, waited for. `data` goes in; with `read_back` the driver's answer is written over it.
    fn call<T>(&self, code: u32, data: &mut T, read_back: bool, event: &Handle) -> Result<(), u32> {
        let size = std::mem::size_of::<T>() as u32;
        let p: *mut T = data;
        // SAFETY: OVERLAPPED is plain data. `data` and `ov` stay in place until GetOverlappedResult (waiting)
        // says the call is over. T is one of the plain structs above.
        unsafe {
            let mut ov: OVERLAPPED = std::mem::zeroed();
            ov.hEvent = event.raw();
            let mut n = 0u32;
            let (out, out_len) = if read_back { (p.cast(), size) } else { (std::ptr::null_mut(), 0) };
            if DeviceIoControl(self.0.raw(), code, p.cast_const().cast(), size, out, out_len, &mut n, &mut ov) == 0 {
                let e = last_error();
                if e != ERROR_IO_PENDING {
                    return Err(e);
                }
            }
            if GetOverlappedResult(self.0.raw(), &ov, &mut n, 1) == 0 {
                return Err(last_error());
            }
        }
        Ok(())
    }

    /// As `call` with the answer read back, but a set `stop` event cancels the wait (None).
    fn call_until<T>(&self, code: u32, data: &mut T, event: &Handle, stop: &Handle) -> Option<Result<(), u32>> {
        let size = std::mem::size_of::<T>() as u32;
        let p: *mut T = data;
        // SAFETY: as in `call`; a cancelled call is waited for too before `data` and `ov` go.
        unsafe {
            let mut ov: OVERLAPPED = std::mem::zeroed();
            ov.hEvent = event.raw();
            let mut n = 0u32;
            let mut stopped = false;
            if DeviceIoControl(self.0.raw(), code, p.cast_const().cast(), size, p.cast(), size, &mut n, &mut ov) == 0 {
                let e = last_error();
                if e != ERROR_IO_PENDING {
                    return Some(Err(e));
                }
                let handles: [HANDLE; 2] = [event.raw(), stop.raw()];
                if th::WaitForMultipleObjects(2, handles.as_ptr(), 0, u32::MAX) != WAIT_OBJECT_0 {
                    stopped = true;
                    CancelIoEx(self.0.raw(), &ov);
                }
            }
            let ok = GetOverlappedResult(self.0.raw(), &ov, &mut n, 1) != 0;
            if stopped {
                None
            } else if ok {
                Some(Ok(()))
            } else {
                Some(Err(last_error()))
            }
        }
    }
}

/// Listens for the game's rumble on one plugged pad, on its own thread, until stopped.
struct Notifier {
    stop: Arc<Handle>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Notifier {
    fn start(bus: Arc<Bus>, mode: u8, serial: u32, rumble: Arc<RwLock<Option<RumbleHandler>>>) -> Option<Notifier> {
        let stop = Arc::new(manual_event()?);
        let event = manual_event()?;
        let stop2 = stop.clone();
        let thread = std::thread::Builder::new()
            .name("gp-rumble".to_string())
            .spawn(move || loop {
                // Each call stays pending in the driver until the game changes the motors (or the pad's light).
                let motors = if mode == output_mode::XBOX360 {
                    let mut n = XusbNotification { size: std::mem::size_of::<XusbNotification>() as u32, serial, large_motor: 0, small_motor: 0, led_number: 0 };
                    bus.call_until(VIGEM_XUSB_REQUEST_NOTIFICATION, &mut n, &event, &stop2).map(|r| r.map(|()| (n.large_motor, n.small_motor)))
                } else {
                    let mut n = Ds4Notification { size: std::mem::size_of::<Ds4Notification>() as u32, serial, small_motor: 0, large_motor: 0, lightbar: [0; 3] };
                    bus.call_until(VIGEM_DS4_REQUEST_NOTIFICATION, &mut n, &event, &stop2).map(|r| r.map(|()| (n.large_motor, n.small_motor)))
                };
                match motors {
                    Some(Ok((large, small))) => {
                        let handler = rumble.read().unwrap_or_else(|e| e.into_inner()).clone();
                        if let Some(h) = handler {
                            h(large, small);
                        }
                    }
                    // Stopped, or the pad is gone (the driver ends the call): rumble is only shown, never needed.
                    _ => return,
                }
            })
            .ok()?;
        Some(Notifier { stop, thread: Some(thread) })
    }
}

impl Drop for Notifier {
    fn drop(&mut self) {
        // SAFETY: a live event handle.
        unsafe {
            th::SetEvent(self.stop.raw());
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

struct Submit {
    event: Handle,
    /// The bus is older than 1.17 and lacks DS4_REPORT_EX: the DualShock 4 gets the simple report, no motion.
    ds4_simple: bool,
    ds4_simple_last: Option<[u8; 9]>,
    ds4_full: [u8; ds4_report::SIZE],
}

struct Control {
    event: Handle,
    /// The serial of the pad on the bus (0: none), kept when it was lost underneath so the next plug frees it.
    serial: u32,
    notifier: Option<Notifier>,
}

/// Virtual pads through the ViGEmBus driver: Xbox 360 (XInput) or DualShock 4. Each report is one control call
/// with the whole state. Submit runs under the engine lock, plug and unplug on its pad thread; rumble arrives on
/// its own thread and is only forwarded. A pad removed underneath (driver reset, disabled) makes submit fail and
/// `plugged` None; the engine has the pad thread plug a new one.
struct ViGEm {
    bus: Arc<Bus>,
    log: Log,
    plugged: AtomicU8,
    /// The plugged pad's serial for submit; 0 while none is plugged.
    serial: AtomicU32,
    submit: Mutex<Submit>,
    control: Mutex<Control>,
    query: Mutex<Handle>,
    rumble: Arc<RwLock<Option<RumbleHandler>>>,
}

impl ViGEm {
    /// Connects to the bus driver; on failure a reason for the user (keyboard and mouse keep working).
    fn connect(log: &Log) -> Result<ViGEm, String> {
        let (Some(submit_event), Some(control_event), Some(query_event)) = (manual_event(), manual_event(), manual_event()) else {
            return Err("ViGEmBus unavailable (no event)".to_string());
        };
        let mut error = "ViGEmBus driver is not installed";
        for path in device_paths(&VIGEM_INTERFACE) {
            let flags = fs::FILE_ATTRIBUTE_NORMAL | fs::FILE_FLAG_NO_BUFFERING | fs::FILE_FLAG_WRITE_THROUGH | fs::FILE_FLAG_OVERLAPPED;
            let Ok(h) = open_device(&path, flags) else {
                error = "ViGEmBus driver could not be opened";
                continue;
            };
            let bus = Bus(h);
            let mut version = SerialOnly { size: 8, serial: VIGEM_COMMON_VERSION };
            if bus.call(VIGEM_CHECK_VERSION, &mut version, false, &control_event).is_err() {
                error = "ViGEmBus driver version is not supported; reinstall it";
                continue;
            }
            return Ok(ViGEm {
                bus: Arc::new(bus),
                log: log.clone(),
                plugged: AtomicU8::new(output_mode::NONE),
                serial: AtomicU32::new(0),
                submit: Mutex::new(Submit { event: submit_event, ds4_simple: false, ds4_simple_last: None, ds4_full: [0; ds4_report::SIZE] }),
                control: Mutex::new(Control { event: control_event, serial: 0, notifier: None }),
                query: Mutex::new(query_event),
                rumble: Arc::new(RwLock::new(None)),
            });
        }
        Err(error.to_string())
    }

    fn plugged(&self) -> u8 {
        self.plugged.load(Ordering::Acquire)
    }

    fn mode_name(mode: u8) -> &'static str {
        match mode {
            output_mode::XBOX360 => "Xbox360",
            output_mode::DUALSHOCK4 => "DualShock4",
            _ => "None",
        }
    }

    fn plug(&self, mode: u8) -> bool {
        let mut c = lock(&self.control);
        if mode == self.plugged() {
            return mode != output_mode::NONE;
        }
        self.unplug_locked(&mut c); // also frees a pad lost underneath
        let (target_type, vendor_id, product_id) = match mode {
            output_mode::XBOX360 => (TARGET_XBOX360_WIRED, 0x045E, 0x028E),
            output_mode::DUALSHOCK4 => (TARGET_DUALSHOCK4_WIRED, 0x054C, 0x05C4),
            _ => return false,
        };
        // The bus has no "next free" call: its client library tries the serials in turn too.
        let mut last = 0u32;
        let mut serial = 0u32;
        for candidate in 1..=MAX_SERIAL {
            let mut plug = PluginTarget { size: std::mem::size_of::<PluginTarget>() as u32, serial: candidate, target_type, vendor_id, product_id };
            match self.bus.call(VIGEM_PLUGIN_TARGET, &mut plug, false, &c.event) {
                Ok(()) => {
                    serial = candidate;
                    break;
                }
                Err(e) => last = e,
            }
        }
        if serial == 0 {
            (self.log)(&format!("plug {} failed: error {last}", Self::mode_name(mode)));
            return false;
        }
        c.serial = serial;
        // Until Windows has started the new pad its reports would be dropped. A bus before 1.17 has no such call
        // (invalid parameter): go on as its client library does.
        let mut ready = SerialOnly { size: 8, serial };
        match self.bus.call(VIGEM_WAIT_DEVICE_READY, &mut ready, false, &c.event) {
            Ok(()) => {}
            Err(ERROR_INVALID_PARAMETER) => {}
            Err(e) => {
                (self.log)(&format!("plug {} failed: not ready, error {e}", Self::mode_name(mode)));
                self.unplug_locked(&mut c);
                return false;
            }
        }
        lock(&self.submit).ds4_simple_last = None;
        c.notifier = Notifier::start(self.bus.clone(), mode, serial, self.rumble.clone());
        self.serial.store(serial, Ordering::Release);
        self.plugged.store(mode, Ordering::Release);
        true
    }

    fn unplug(&self) {
        let mut c = lock(&self.control);
        self.unplug_locked(&mut c);
    }

    fn unplug_locked(&self, c: &mut Control) {
        self.plugged.store(output_mode::NONE, Ordering::Release);
        self.serial.store(0, Ordering::Release);
        c.notifier = None; // stops and joins its thread
        let serial = std::mem::take(&mut c.serial);
        if serial == 0 {
            return;
        }
        let mut unplug = SerialOnly { size: 8, serial };
        if let Err(e) = self.bus.call(VIGEM_UNPLUG_TARGET, &mut unplug, false, &c.event) {
            (self.log)(&format!("unplug failed: error {e}"));
        }
    }

    /// The pad was removed underneath (disabled in Device Manager, driver reset): the bus refuses its reports.
    /// Plugging a new one blocks in the driver, so it is left to the engine's pad thread: `plugged` goes None,
    /// and its next plug frees the old one.
    fn lost(&self) {
        let was = self.plugged.swap(output_mode::NONE, Ordering::AcqRel);
        if was != output_mode::NONE {
            self.serial.store(0, Ordering::Release);
            (self.log)(&format!("{} pad was unplugged underneath", Self::mode_name(was)));
        }
    }

    fn submitted(&self, r: Result<(), u32>) -> Result<(), SinkError> {
        match r {
            Err(e) if is_gone(e) => {
                self.lost();
                Err("the pad is gone")
            }
            // Any other failure is that one report's; the next carries the whole state again.
            _ => Ok(()),
        }
    }

    fn submit_x360(&self, f: &X360Frame) -> Result<(), SinkError> {
        if self.plugged() != output_mode::XBOX360 {
            return Ok(());
        }
        let serial = self.serial.load(Ordering::Acquire);
        if serial == 0 {
            return Ok(());
        }
        let s = lock(&self.submit);
        let mut report = XusbSubmit {
            size: std::mem::size_of::<XusbSubmit>() as u32,
            serial,
            buttons: f.buttons,
            left_trigger: f.lt,
            right_trigger: f.rt,
            lx: f.lx,
            ly: f.ly,
            rx: f.rx,
            ry: f.ry,
        };
        self.submitted(self.bus.call(VIGEM_XUSB_SUBMIT_REPORT, &mut report, false, &s.event))
    }

    fn submit_ds4(&self, f: &Ds4Frame, m: &Ds4Motion) -> Result<(), SinkError> {
        if self.plugged() != output_mode::DUALSHOCK4 {
            return Ok(());
        }
        let serial = self.serial.load(Ordering::Acquire);
        if serial == 0 {
            return Ok(());
        }
        let mut s = lock(&self.submit);
        let s = &mut *s;
        ds4_report::build(&mut s.ds4_full, f, m);
        if !s.ds4_simple {
            // The full report (DS4_REPORT_EX) carries the gyro and accelerometer the simple one lacks.
            let mut report = Ds4SubmitEx { size: std::mem::size_of::<Ds4SubmitEx>() as u32, serial, report: s.ds4_full };
            match self.bus.call(VIGEM_DS4_SUBMIT_REPORT, &mut report, false, &s.event) {
                Err(ERROR_INVALID_PARAMETER) => {
                    // ViGEmBus before 1.17 has no DS4_REPORT_EX: buttons, sticks and triggers still go, without motion.
                    s.ds4_simple = true;
                    (self.log)("ViGEmBus is older than 1.17: the DualShock 4 gets no gyro; update ViGEmBus for it");
                }
                r => return self.submitted(r),
            }
        }
        // The simple report is the full one's first 9 bytes; only when it changed, since each motion sample
        // would repeat it.
        let mut simple = [0u8; 9];
        simple.copy_from_slice(&s.ds4_full[..9]);
        if s.ds4_simple_last == Some(simple) {
            return Ok(());
        }
        let mut report = Ds4Submit { size: std::mem::size_of::<Ds4Submit>() as u32, serial, report: simple, _pad: [0; 3] };
        let r = self.bus.call(VIGEM_DS4_SUBMIT_REPORT, &mut report, false, &s.event);
        if r.is_ok() {
            s.ds4_simple_last = Some(simple);
        }
        self.submitted(r)
    }

    /// The XInput slot the plugged Xbox 360 pad took (the driver reports it once Windows assigned one),
    /// `SLOT_UNKNOWN` until then, -1 when none is plugged.
    fn xinput_slot(&self) -> i32 {
        if self.plugged() != output_mode::XBOX360 {
            return -1;
        }
        let serial = self.serial.load(Ordering::Acquire);
        if serial == 0 {
            return -1;
        }
        let event = lock(&self.query);
        let mut q = XusbUserIndex { size: std::mem::size_of::<XusbUserIndex>() as u32, serial, user_index: 0 };
        match self.bus.call(VIGEM_XUSB_GET_USER_INDEX, &mut q, true, &event) {
            Ok(()) if q.user_index < 4 => q.user_index as i32,
            _ => SLOT_UNKNOWN,
        }
    }
}

impl Drop for ViGEm {
    fn drop(&mut self) {
        self.unplug();
    }
}

// =====================================================================================
// Both behind one sink
// =====================================================================================

/// ViGEmBus's pads and the GyroPhone pad driver's DualSense behind one sink; at most one of them plugged.
struct Pads {
    vigem: Option<ViGEm>,
    dual_sense: Option<DualSense>,
}

impl PadSink for Pads {
    fn driver_ok(&self) -> bool {
        self.vigem.is_some()
    }

    fn dual_sense_ok(&self) -> bool {
        self.dual_sense.is_some()
    }

    fn plugged(&self) -> u8 {
        if self.dual_sense.as_ref().is_some_and(|d| d.plugged()) {
            return output_mode::DUALSENSE;
        }
        self.vigem.as_ref().map_or(output_mode::NONE, |v| v.plugged())
    }

    fn plug(&self, mode: u8) -> Result<bool, SinkError> {
        if mode == self.plugged() {
            return Ok(mode != output_mode::NONE);
        }
        self.unplug()?;
        Ok(match mode {
            output_mode::DUALSENSE => self.dual_sense.as_ref().is_some_and(|d| d.plug()),
            output_mode::XBOX360 | output_mode::DUALSHOCK4 => self.vigem.as_ref().is_some_and(|v| v.plug(mode)),
            _ => false,
        })
    }

    fn unplug(&self) -> Result<(), SinkError> {
        if let Some(d) = &self.dual_sense {
            d.unplug();
        }
        if let Some(v) = &self.vigem {
            v.unplug();
        }
        Ok(())
    }

    fn submit_x360(&self, frame: &X360Frame) -> Result<(), SinkError> {
        match &self.vigem {
            Some(v) => v.submit_x360(frame),
            None => Ok(()),
        }
    }

    fn submit_ds4(&self, frame: &Ds4Frame, motion: &Ds4Motion) -> Result<(), SinkError> {
        if let Some(d) = self.dual_sense.as_ref().filter(|d| d.plugged()) {
            return d.submit(frame, motion);
        }
        match &self.vigem {
            Some(v) => v.submit_ds4(frame, motion),
            None => Ok(()),
        }
    }

    fn set_rumble_handler(&self, handler: Option<RumbleHandler>) {
        if let Some(v) = &self.vigem {
            *v.rumble.write().unwrap_or_else(|e| e.into_inner()) = handler;
        }
    }

    fn xinput_slot(&self) -> i32 {
        self.vigem.as_ref().map_or(-1, |v| v.xinput_slot())
    }
}

/// The pad sink: ViGEmBus for Xbox 360 and DualShock 4, plus the DualSense when the GyroPhone pad driver is
/// installed. The text says why ViGEmBus can't be used (None when it can).
pub fn pads(log: &Log) -> (Arc<dyn PadSink>, Option<String>) {
    let (vigem, error) = match ViGEm::connect(log) {
        Ok(v) => (Some(v), None),
        Err(e) => {
            log(&e);
            (None, Some(e))
        }
    };
    let dual_sense = DualSense::create(log);
    if vigem.is_none() && dual_sense.is_none() {
        return (Arc::new(NullPadSink), error);
    }
    (Arc::new(Pads { vigem, dual_sense }), error)
}

// =====================================================================================
// The GyroPhone input driver: a virtual mouse and keyboard
// =====================================================================================

const INPUT_INTERFACE: GUID = GUID { data1: 0x6E0B53F1, data2: 0x7C2D, data3: 0x4C6A, data4: [0x9E, 0x45, 0x0A, 0x8D, 0x3B, 0x6F, 0x2C, 0x71] };
const INPUT_PROTOCOL: u32 = 1;
const INPUT_VERSION: u32 = 0x222400;
const INPUT_PLUG: u32 = 0x222404;
const INPUT_UNPLUG: u32 = 0x222408;
const INPUT_SUBMIT: u32 = 0x22240C;
/// Events per SUBMIT, so even a release of everything fits HID_MAX_BYTES.
const CHUNK: usize = 64;

struct HidState {
    handle: Option<Handle>,
    reports: HidInputReports,
    buf: Box<[u8; HID_MAX_BYTES]>,
}

/// Keyboard and mouse through the optional GyroPhone input driver (input-driver/ in the repo): a virtual USB mouse
/// and keyboard with media keys. Each inject is one control call that carries every report of the hand-off,
/// straight on the calling thread: no queue, no thread hop. The control codes and protocol version match
/// input-driver/src/gpinput_ioctl.h. The devices belong to this handle and go away when it closes, so a crashed
/// input process leaves no key held.
struct HidInputSink {
    log: Log,
    state: Mutex<HidState>,
    plugged: AtomicBool,
}

impl HidInputSink {
    fn open_plugged() -> Result<Handle, String> {
        let paths = device_paths(&INPUT_INTERFACE);
        let path = paths.first().ok_or("the GyroPhone input driver is not installed")?;
        // Dropped (closed) on every failure below, so a refused plug leaves no handle holding the device.
        let h = open_device(path, 0).map_err(|e| format!("error {e}"))?;
        let mut version = [0u8; 4];
        match control(&h, INPUT_VERSION, &[], &mut version) {
            Ok(4) if u32::from_le_bytes(version) == INPUT_PROTOCOL => {}
            Ok(_) => return Err("the GyroPhone input driver is a different version; run its install.cmd again".to_string()),
            Err(e) => return Err(format!("error {e}")),
        }
        control(&h, INPUT_PLUG, &INPUT_PROTOCOL.to_le_bytes(), &mut []).map_err(|e| format!("error {e}"))?;
        Ok(h)
    }

    fn unplug(&self) {
        self.plugged.store(false, Ordering::Release);
        let mut s = lock(&self.state);
        s.reports.reset();
        if let Some(h) = s.handle.take() {
            if let Err(e) = control(&h, INPUT_UNPLUG, &[], &mut []) {
                (self.log)(&format!("input driver: unplug failed: error {e}"));
            }
        }
    }
}

impl KeyMouseSink for HidInputSink {
    /// Sends the events as reports. False when the device went away underneath (driver disabled or reinstalled):
    /// the sink is then unplugged and Windows has let go of everything it held.
    fn inject(&self, events: &[InjectEvent]) -> bool {
        let mut failed = None;
        {
            let mut s = lock(&self.state);
            let s = &mut *s;
            let Some(h) = s.handle.as_ref() else { return false };
            for chunk in events.chunks(CHUNK) {
                let n = s.reports.build(chunk, &mut s.buf);
                if n == 0 {
                    continue;
                }
                if let Err(e) = control(h, INPUT_SUBMIT, &s.buf[..n], &mut []) {
                    failed = Some(e);
                    break;
                }
            }
        }
        match failed {
            None => true,
            Some(e) => {
                (self.log)(&format!("input driver: submit failed (error {e}); back to SendInput"));
                self.unplug();
                false
            }
        }
    }

    fn route(&self) -> &'static str {
        "input driver"
    }
}

impl DriverSink for HidInputSink {
    fn plugged(&self) -> bool {
        self.plugged.load(Ordering::Acquire)
    }
}

impl Drop for HidInputSink {
    fn drop(&mut self) {
        self.unplug();
    }
}

/// The optional GyroPhone input driver, as `host::Platform` takes it.
pub struct GyroPhoneInputDriver;

impl InputDriver for GyroPhoneInputDriver {
    fn installed(&self) -> bool {
        !device_paths(&INPUT_INTERFACE).is_empty()
    }

    fn create(&self, log: &dyn Fn(&str)) -> Option<Arc<dyn DriverSink>> {
        if !self.installed() {
            return None;
        }
        match HidInputSink::open_plugged() {
            Ok(h) => {
                log("GyroPhone input driver found: keys and mouse go through its virtual mouse and keyboard");
                Some(Arc::new(HidInputSink {
                    log: LOG.get().cloned().unwrap_or_else(|| Arc::new(|_: &str| {})),
                    state: Mutex::new(HidState { handle: Some(h), reports: HidInputReports::new(), buf: Box::new([0; HID_MAX_BYTES]) }),
                    plugged: AtomicBool::new(true),
                }))
            }
            Err(e) => {
                log(&format!("input driver: plug failed: {e}"));
                None
            }
        }
    }
}

/// The log the input driver's sink keeps for a later failure (`create` only lends its caller's).
static LOG: std::sync::OnceLock<Log> = std::sync::OnceLock::new();

/// Sets the log the input driver's sink writes its later failures to. Once, at start-up.
pub fn set_log(log: &Log) {
    let _ = LOG.set(log.clone());
}

const _: () = assert!(std::mem::size_of::<PluginTarget>() == 16);
const _: () = assert!(std::mem::size_of::<XusbSubmit>() == 20);
const _: () = assert!(std::mem::size_of::<XusbNotification>() == 12);
const _: () = assert!(std::mem::size_of::<Ds4Submit>() == 20);
const _: () = assert!(std::mem::size_of::<Ds4SubmitEx>() == 71);
const _: () = assert!(std::mem::size_of::<Ds4Notification>() == 16);
const _: () = assert!(ERROR_ACCESS_DENIED == 5);
