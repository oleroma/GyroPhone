//! Reading raw input, never changing it: "Press a key" on the phone (this PC's keyboard and mouse while the phone
//! listens) and Diagnostics' delivery probe (our own mouse moves, timed as a game reading raw input gets them).
//! Both use RIDEV_INPUTSINK on a hidden message-only window on a thread of their own: every key, click and move
//! still goes to Windows and the game as usual (no hook, nothing held back). Ports of RawInputListener.cs,
//! RawMouse.cs, RawMouseOwner.cs, OwnInputDevice.cs and the Windows half of DeliveryProbe.cs.

use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::{Arc, Mutex};

use windows_sys::core::GUID;
use windows_sys::Win32::Devices::DeviceAndDriverInstallation::{CM_Get_DevNode_PropertyW, CM_Get_Parent, CM_Locate_DevNodeW};
use windows_sys::Win32::Devices::Properties::DEVPROPKEY;
use windows_sys::Win32::Foundation::{HANDLE, HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
use windows_sys::Win32::System::Threading as th;
use windows_sys::Win32::UI::Input::{GetRawInputData, GetRawInputDeviceInfoW, RegisterRawInputDevices, RAWINPUTDEVICE, RAWINPUTHEADER};
use windows_sys::Win32::UI::WindowsAndMessaging as wm;

use super::{last_error, wide, Handle};
use crate::codec::{pc_press, PcPress};
use crate::control::DeliveryNumbers;
use crate::delivery::{ProbeShared, ProbeThread, Wake};
use crate::host::DeliveryProbe;
use crate::mapping::hid_scan_codes;
use crate::ports::KeyMouseListener;
use crate::udp::Log;

const RID_INPUT: u32 = 0x1000_0003;
const RIDI_DEVICENAME: u32 = 0x2000_0007;
const RIDEV_INPUTSINK: u32 = 0x0000_0100;
const RIDEV_REMOVE: u32 = 0x0000_0001;
const RI_KEY_BREAK: u16 = 1;
const RI_KEY_E0: u16 = 2;
const HEADER: usize = std::mem::size_of::<RAWINPUTHEADER>();
const WM_REGISTER: u32 = wm::WM_USER + 0x47;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn qpc() -> i64 {
    let mut t = 0i64;
    // SAFETY: a valid out pointer.
    unsafe {
        QueryPerformanceCounter(&mut t);
    }
    t
}

fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}

fn i32_at(b: &[u8], at: usize) -> i32 {
    i32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]])
}

// =====================================================================================
// what both readers share
// =====================================================================================

/// A process has one raw mouse input target. "Press a key" listening takes it while it runs; the delivery probe
/// registers or removes it only while the listener doesn't hold it, and registers again when the listener lets
/// go. Held around every raw mouse register or remove in the process; true while the listener holds it.
static RAW_MOUSE_LISTENER: Mutex<bool> = Mutex::new(false);

/// The running delivery probe's window (0: none), for the listener to tell when it gives the raw mouse up.
static PROBE_WINDOW: AtomicIsize = AtomicIsize::new(0);

fn raw_mouse_released() {
    let h = PROBE_WINDOW.load(Ordering::Acquire);
    if h != 0 {
        // SAFETY: posting to a window that is gone fails harmlessly.
        unsafe {
            wm::PostMessageW(h as HWND, WM_REGISTER, 0, 0);
        }
    }
}

fn devices(flags: u32, target: HWND, keyboard: bool) -> ([RAWINPUTDEVICE; 2], u32) {
    let mouse = RAWINPUTDEVICE { usUsagePage: 1, usUsage: 2, dwFlags: flags, hwndTarget: target };
    let keys = RAWINPUTDEVICE { usUsagePage: 1, usUsage: 6, dwFlags: flags, hwndTarget: target };
    if keyboard {
        ([keys, mouse], 2)
    } else {
        ([mouse, mouse], 1)
    }
}

fn register(flags: u32, target: HWND, keyboard: bool) -> Result<(), u32> {
    let (d, n) = devices(flags, target, keyboard);
    // SAFETY: `n` devices of the size given.
    if unsafe { RegisterRawInputDevices(d.as_ptr(), n, std::mem::size_of::<RAWINPUTDEVICE>() as u32) } == 0 {
        Err(last_error())
    } else {
        Ok(())
    }
}

/// A hidden message-only window of the class named, made on the calling thread (which then runs its messages).
fn message_window(class: &str, proc: unsafe extern "system" fn(HWND, u32, WPARAM, LPARAM) -> LRESULT) -> Result<HWND, u32> {
    let name = wide(class);
    // SAFETY: the class's strings outlive both calls; the procedure is a plain function that lives forever.
    // Registering fails harmlessly when an earlier window already did.
    unsafe {
        let instance = GetModuleHandleW(std::ptr::null());
        let mut cls: wm::WNDCLASSEXW = std::mem::zeroed();
        cls.cbSize = std::mem::size_of::<wm::WNDCLASSEXW>() as u32;
        cls.lpfnWndProc = Some(proc);
        cls.hInstance = instance;
        cls.lpszClassName = name.as_ptr();
        wm::RegisterClassExW(&cls);
        let empty = [0u16];
        let hwnd = wm::CreateWindowExW(0, name.as_ptr(), empty.as_ptr(), 0, 0, 0, 0, 0, wm::HWND_MESSAGE, std::ptr::null_mut(), instance, std::ptr::null());
        if hwnd.is_null() {
            Err(last_error())
        } else {
            Ok(hwnd)
        }
    }
}

/// Reads one WM_INPUT into `buf`; false when it can't be read.
fn read_raw(lparam: LPARAM, buf: &mut [u64; 32]) -> bool {
    let mut size = std::mem::size_of_val(buf) as u32;
    // SAFETY: the buffer is `size` bytes, 8-aligned as RAWINPUT asks.
    unsafe { GetRawInputData(lparam as _, RID_INPUT, buf.as_mut_ptr().cast(), &mut size, HEADER as u32) != u32::MAX }
}

fn bytes(buf: &[u64; 32]) -> &[u8] {
    // SAFETY: plain numbers read as their bytes, within the array.
    unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<u8>(), std::mem::size_of_val(buf)) }
}

fn header(buf: &[u64; 32]) -> (u32, HANDLE) {
    // SAFETY: the buffer is larger than a RAWINPUTHEADER and aligned for it; every bit pattern is valid.
    let h = unsafe { &*(buf.as_ptr() as *const RAWINPUTHEADER) };
    (h.dwType, h.hDevice)
}

// =====================================================================================
// the input driver's own devices
// =====================================================================================

/// The virtual devices' ids, as in Windows' device paths: raw input readers skip them.
const DEVICE_IDS: &str = "VID_1209&PID_6770";
/// The input driver's own container id (GpiContainerId in input-driver/src/driver.c): its mouse and keyboard
/// carry it.
const CONTAINER_ID: GUID = GUID { data1: 0xb7d1e5a2, data2: 0x3c84, data3: 0x4f19, data4: [0xa6, 0xe0, 0x5d, 0x2c, 0x8b, 0x91, 0xf4, 0x73] };
const DEVPKEY_CONTAINER_ID: DEVPROPKEY =
    DEVPROPKEY { fmtid: GUID { data1: 0x8c7ed206, data2: 0x3f8a, data3: 0x4827, data4: [0xb3, 0xab, 0xae, 0x9e, 0x1f, 0xae, 0xfc, 0x6c] }, pid: 2 };
const DEVPROP_TYPE_GUID: u32 = 0x0D;

static KNOWN_DEVICES: Mutex<Option<HashMap<isize, bool>>> = Mutex::new(None);

/// Tells the input driver's own virtual mouse and keyboard apart from the PC's real ones in raw input, looked up
/// once per device handle. The driver's devices are virtual HID collections whose names carry no vendor or
/// product id, and other such devices exist on a PC, so a device is ours when it carries the driver's own
/// container id. A name with the driver's ids counts too.
fn is_own_device(device: HANDLE) -> bool {
    if device.is_null() {
        return false;
    }
    let key = device as isize;
    if let Some(own) = lock(&KNOWN_DEVICES).get_or_insert_with(HashMap::new).get(&key) {
        return *own;
    }
    let own = device_name(device).is_some_and(|name| name.to_ascii_uppercase().contains(DEVICE_IDS) || in_our_container(&name));
    lock(&KNOWN_DEVICES).get_or_insert_with(HashMap::new).insert(key, own);
    own
}

fn device_name(device: HANDLE) -> Option<String> {
    let mut size = 0u32;
    // SAFETY: a null buffer asks for the size in characters.
    if unsafe { GetRawInputDeviceInfoW(device, RIDI_DEVICENAME, std::ptr::null_mut(), &mut size) } != 0 || size == 0 || size > 1024 {
        return None;
    }
    let mut buf = vec![0u16; size as usize];
    // SAFETY: the buffer is `size` characters.
    if unsafe { GetRawInputDeviceInfoW(device, RIDI_DEVICENAME, buf.as_mut_ptr().cast(), &mut size) } == u32::MAX {
        return None;
    }
    let end = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
    Some(String::from_utf16_lossy(&buf[..end]))
}

/// `\\?\HID#VID_1&Col01#2&x&0#{guid}` to `HID\VID_1&Col01\2&x&0`; None when it isn't one.
pub fn instance_id_of(raw_name: &str) -> Option<String> {
    let mut s = raw_name.trim_end_matches('\0');
    if s.starts_with(r"\\?\") || s.starts_with(r"\\.\") {
        s = &s[4..];
    }
    if let Some(cls) = s.rfind("#{").filter(|i| *i > 0) {
        s = &s[..cls];
    }
    (!s.is_empty() && s.contains('#')).then(|| s.replace('#', "\\"))
}

fn container_of(node: u32) -> Option<GUID> {
    let mut kind = 0u32;
    let mut buf = [0u8; 16];
    let mut size = 16u32;
    // SAFETY: a 16-byte buffer and valid out pointers.
    if unsafe { CM_Get_DevNode_PropertyW(node, &DEVPKEY_CONTAINER_ID, &mut kind, buf.as_mut_ptr(), &mut size, 0) } != 0 || kind != DEVPROP_TYPE_GUID || size != 16 {
        return None;
    }
    Some(GUID {
        data1: u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]),
        data2: u16::from_le_bytes([buf[4], buf[5]]),
        data3: u16::from_le_bytes([buf[6], buf[7]]),
        data4: [buf[8], buf[9], buf[10], buf[11], buf[12], buf[13], buf[14], buf[15]],
    })
}

fn same_guid(a: &GUID, b: &GUID) -> bool {
    a.data1 == b.data1 && a.data2 == b.data2 && a.data3 == b.data3 && a.data4 == b.data4
}

/// True when the raw input device (or the virtual HID device above it) carries the input driver's container id.
fn in_our_container(raw_name: &str) -> bool {
    let Some(instance) = instance_id_of(raw_name) else { return false };
    let id = wide(&instance);
    let mut node = 0u32;
    // SAFETY: a NUL-ended id and a valid out pointer.
    if unsafe { CM_Locate_DevNodeW(&mut node, id.as_ptr(), 0) } != 0 {
        return false;
    }
    for _ in 0..3 {
        if container_of(node).is_some_and(|c| same_guid(&c, &CONTAINER_ID)) {
            return true;
        }
        let mut parent = 0u32;
        // SAFETY: a valid out pointer.
        if unsafe { CM_Get_Parent(&mut parent, node, 0) } != 0 {
            return false;
        }
        node = parent;
    }
    false
}

// =====================================================================================
// "Press a key"
// =====================================================================================

const MAX_HELD: usize = 64;

/// One listen: its window once made (0 before), and whether it was stopped. Both changed under the state lock.
#[derive(Default)]
struct Run {
    hwnd: AtomicIsize,
    stopped: AtomicBool,
}

#[derive(Default)]
struct ListenState {
    // What is held now, and what was pressed since the last read (kept until then even if let go).
    held: Vec<PcPress>,
    pressed: Vec<PcPress>,
    wheel_up: u32,
    wheel_down: u32,
    // The listen under way, and the thread of the latest one (the next waits for it to have let go).
    run: Option<Arc<Run>>,
    thread: Option<std::thread::JoinHandle<()>>,
    logged_failure: bool,
}

struct ListenShared {
    log: Log,
    state: Mutex<ListenState>,
}

thread_local! {
    /// The listener whose window this thread runs.
    static LISTENING: RefCell<Option<Arc<ListenShared>>> = const { RefCell::new(None) };
    /// The probe whose window this thread runs.
    static PROBING: RefCell<Option<ProbeLocal>> = const { RefCell::new(None) };
}

impl ListenShared {
    fn fail(&self, why: &str) {
        let mut s = lock(&self.state);
        if !s.logged_failure {
            s.logged_failure = true;
            drop(s);
            (self.log)(&format!("press a key: {why}"));
        }
    }

    /// Input came to the window of the listen under way (not one stopped and still letting go).
    fn current(s: &ListenState, hwnd: HWND) -> bool {
        s.run.as_ref().is_some_and(|r| r.hwnd.load(Ordering::Acquire) == hwnd as isize)
    }

    fn edge(&self, hwnd: HWND, p: PcPress, down: bool) {
        let mut s = lock(&self.state);
        if !Self::current(&s, hwnd) {
            return;
        }
        if down {
            if !s.held.contains(&p) && s.held.len() < MAX_HELD {
                s.held.push(p);
            }
            if !s.pressed.contains(&p) && s.pressed.len() < MAX_HELD {
                s.pressed.push(p);
            }
        } else {
            s.held.retain(|h| *h != p);
        }
    }

    fn on_input(&self, hwnd: HWND, lparam: LPARAM) {
        let mut buf = [0u64; 32];
        if !read_raw(lparam, &mut buf) {
            return;
        }
        let (kind, device) = header(&buf);
        // The input driver's own mouse and keyboard are GyroPhone's output, never the PC's keys.
        if is_own_device(device) {
            return;
        }
        let b = bytes(&buf);
        // No device behind it: injected (SendInput, ours too) for a key. A laptop's precision touchpad sends its
        // clicks with no device too, so a mouse button counts anyway: GyroPhone sends no clicks while the phone
        // listens.
        if kind == 1 && !device.is_null() {
            // RAWKEYBOARD: MakeCode u16, Flags u16, Reserved u16, VKey u16, ...
            let (scan, flags, vk) = (u16_at(b, HEADER), u16_at(b, HEADER + 2), u16_at(b, HEADER + 6));
            if vk == 0xFF {
                return; // the fake shifts some keyboards wrap round other keys
            }
            let usage = hid_scan_codes::usage_of(scan, flags & RI_KEY_E0 != 0, vk);
            if usage == 0 {
                return;
            }
            self.edge(hwnd, PcPress { kind: pc_press::KEY, code: usage as u16, arg: 0 }, flags & RI_KEY_BREAK == 0);
        } else if kind == 0 {
            // RAWMOUSE: usFlags u16, 2 bytes, usButtonFlags u16, usButtonData u16, ...
            let buttons = u16_at(b, HEADER + 4);
            if buttons == 0 {
                return;
            }
            for i in 0..5u16 {
                let p = PcPress { kind: pc_press::MOUSE, code: i, arg: 0 };
                if buttons & (1 << (2 * i)) != 0 {
                    self.edge(hwnd, p, true);
                }
                if buttons & (2 << (2 * i)) != 0 {
                    self.edge(hwnd, p, false);
                }
            }
            if buttons & 0x0400 != 0 {
                let delta = u16_at(b, HEADER + 6) as i16;
                let mut s = lock(&self.state);
                if !Self::current(&s, hwnd) {
                    return;
                }
                if delta > 0 {
                    s.wheel_up += 1;
                } else if delta < 0 {
                    s.wheel_down += 1;
                }
            }
        }
    }

    fn run(self: &Arc<Self>, run: &Run, previous: Option<std::thread::JoinHandle<()>>) {
        // The listen before may still be letting go of raw input: registering before it did would lose ours.
        if let Some(p) = previous {
            let _ = p.join();
        }
        let hwnd = match message_window("GyroPhonePressListener", listen_proc) {
            Ok(h) => h,
            Err(e) => return self.fail(&format!("no listener window (error {e})")),
        };
        let registered = {
            let mut listener = lock(&RAW_MOUSE_LISTENER);
            let r = register(RIDEV_INPUTSINK, hwnd, true);
            // The process's raw mouse input is this listen's now: the delivery probe leaves it alone until it ends.
            if r.is_ok() {
                *listener = true;
            }
            r
        };
        if let Err(e) = registered {
            self.fail(&format!("raw input not registered (error {e})"));
            // SAFETY: our own window, on its thread.
            unsafe {
                wm::DestroyWindow(hwnd);
            }
            return;
        }
        LISTENING.with(|l| *l.borrow_mut() = Some(self.clone()));
        let stopped = {
            let _s = lock(&self.state);
            let stopped = run.stopped.load(Ordering::Acquire);
            if !stopped {
                run.hwnd.store(hwnd as isize, Ordering::Release);
            }
            stopped
        };
        // SAFETY: MSG is plain data; the loop runs on the window's own thread.
        unsafe {
            let mut msg: wm::MSG = std::mem::zeroed();
            while !stopped && wm::GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
                if msg.message == wm::WM_CLOSE && msg.hwnd == hwnd {
                    break;
                }
                wm::DispatchMessageW(&msg);
            }
        }
        LISTENING.with(|l| *l.borrow_mut() = None);
        {
            let mut listener = lock(&RAW_MOUSE_LISTENER);
            let _ = register(RIDEV_REMOVE, std::ptr::null_mut(), true);
            *listener = false;
        }
        // SAFETY: our own window, on its thread.
        unsafe {
            wm::DestroyWindow(hwnd);
        }
        // That took the process's raw mouse input away from Diagnostics' delivery probe too: it registers again.
        raw_mouse_released();
    }
}

unsafe extern "system" fn listen_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if msg == wm::WM_INPUT {
        let listener = LISTENING.with(|l| l.borrow().clone());
        if let Some(l) = listener {
            l.on_input(hwnd, lparam);
        }
    }
    // SAFETY: the arguments Windows gave this procedure.
    unsafe { wm::DefWindowProcW(hwnd, msg, wparam, lparam) }
}

/// "Press a key" on the phone, as `host::Platform` takes it. Keyboard input with no device behind it (SendInput,
/// ours included) is skipped.
pub struct RawInputListener(Arc<ListenShared>);

impl RawInputListener {
    pub fn new(log: Log) -> RawInputListener {
        RawInputListener(Arc::new(ListenShared { log, state: Mutex::new(ListenState::default()) }))
    }
}

impl KeyMouseListener for RawInputListener {
    /// Starts listening without waiting for the listener's window (the engine calls this under its lock): until
    /// it is up, `read` has nothing.
    fn start(&self) {
        let mut s = lock(&self.0.state);
        if s.run.is_some() {
            return;
        }
        let run = Arc::new(Run::default());
        let previous = s.thread.take();
        let (shared, run2) = (self.0.clone(), run.clone());
        match std::thread::Builder::new().name("gp-press-listen".to_string()).spawn(move || shared.run(&run2, previous)) {
            Ok(t) => {
                s.run = Some(run);
                s.thread = Some(t);
            }
            Err(_) => {
                drop(s);
                self.0.fail("no listener thread");
            }
        }
    }

    /// Stops listening without waiting for the listener's thread (the engine calls this under its lock): it ends
    /// on its own.
    fn stop(&self) {
        let hwnd = {
            let mut s = lock(&self.0.state);
            s.held.clear();
            s.pressed.clear();
            s.wheel_up = 0;
            s.wheel_down = 0;
            let Some(run) = s.run.take() else { return };
            run.stopped.store(true, Ordering::Release);
            run.hwnd.load(Ordering::Acquire)
        };
        if hwnd != 0 {
            // SAFETY: posting to a window that is gone fails harmlessly.
            unsafe {
                wm::PostMessageW(hwnd as HWND, wm::WM_CLOSE, 0, 0);
            }
        }
    }

    fn read(&self, into: &mut [PcPress]) -> usize {
        let mut s = lock(&self.0.state);
        let s = &mut *s;
        let mut n = 0;
        let mut put = |p: PcPress| {
            if n < into.len() {
                into[n] = p;
                n += 1;
            }
        };
        for p in &s.held {
            put(*p);
        }
        for p in &s.pressed {
            if !s.held.contains(p) {
                put(*p);
            }
        }
        s.pressed.clear();
        if s.wheel_up > 0 {
            put(PcPress { kind: pc_press::WHEEL, code: 0, arg: 1 });
        }
        if s.wheel_down > 0 {
            put(PcPress { kind: pc_press::WHEEL, code: 0, arg: -1 });
        }
        s.wheel_up = 0;
        s.wheel_down = 0;
        n
    }
}

impl Drop for RawInputListener {
    fn drop(&mut self) {
        self.stop();
    }
}

// =====================================================================================
// Diagnostics' delivery probe
// =====================================================================================

/// The probe thread's own state: the portable half, and what it knows about its own waking.
struct ProbeLocal {
    thread: ProbeThread,
    log: Log,
    // When this thread last woke from waiting for messages, how long it had waited, and messages handled since.
    woke_at: i64,
    slept: i64,
    since_wake: u32,
    read_at: i64,
    last_front_window: isize,
    last_front: String,
}

/// The program in front, by its file's name without ".exe".
fn front_name(window: HWND) -> String {
    if window.is_null() {
        return "none".to_string();
    }
    let mut pid = 0u32;
    // SAFETY: a valid out pointer; a window that is gone leaves it 0.
    unsafe {
        wm::GetWindowThreadProcessId(window, &mut pid);
    }
    // SAFETY: only the right to ask its name; the handle is closed by `Handle`.
    let name = Handle::new(unsafe { th::OpenProcess(th::PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) }).and_then(|h| {
        let mut buf = [0u16; 520];
        let mut len = buf.len() as u32;
        // SAFETY: the buffer is `len` characters.
        (unsafe { th::QueryFullProcessImageNameW(h.raw(), 0, buf.as_mut_ptr(), &mut len) } != 0).then(|| String::from_utf16_lossy(&buf[..len as usize]))
    });
    match name {
        Some(path) => {
            let file = path.rsplit(['\\', '/']).next().unwrap_or(&path);
            let stem = if file.len() > 4 && file[file.len() - 4..].eq_ignore_ascii_case(".exe") { &file[..file.len() - 4] } else { file };
            stem.to_string()
        }
        None => format!("pid {pid}"),
    }
}

impl ProbeLocal {
    fn register(&self, hwnd: HWND) {
        let r = {
            let listener = lock(&RAW_MOUSE_LISTENER);
            // "Press a key" holds it: the probe takes it when the listener lets go.
            if *listener {
                return;
            }
            register(RIDEV_INPUTSINK, hwnd, false)
        };
        if let Err(e) = r {
            (self.log)(&format!("delivery probe: raw mouse input not registered (error {e})"));
        }
    }

    /// One WM_INPUT: only a relative mouse move from GyroPhone counts: SendInput's (no device behind it) or the
    /// input driver's own mouse, never the PC's own mouse.
    fn on_input(&mut self, lparam: LPARAM) {
        let now = qpc();
        let mut buf = [0u64; 32];
        if !read_raw(lparam, &mut buf) {
            return;
        }
        let (kind, device) = header(&buf);
        if kind != 0 {
            return;
        }
        let driver = !device.is_null();
        if driver && !is_own_device(device) {
            return;
        }
        let b = bytes(&buf);
        // RAWMOUSE: usFlags u16, (pad) u16, ulButtons u32, ulRawButtons u32, lLastX i32, lLastY i32.
        if u16_at(b, HEADER) & 1 != 0 {
            return; // absolute: not ours
        }
        let (x, y) = (i32_at(b, HEADER + 12), i32_at(b, HEADER + 16));
        if x == 0 && y == 0 {
            return;
        }
        let wake = Wake { slept_ticks: self.slept, read_after_wake_ticks: self.read_at - self.woke_at, since_wake: self.since_wake };
        let (last_window, last_front) = (&mut self.last_front_window, &mut self.last_front);
        self.thread.raw(now, x, y, driver, &wake, &mut || {
            // Looked up again only when the front window changed.
            // SAFETY: no arguments.
            let w = unsafe { wm::GetForegroundWindow() };
            if w as isize != *last_window {
                *last_window = w as isize;
                *last_front = front_name(w);
            }
            last_front.clone()
        });
    }
}

unsafe extern "system" fn probe_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if msg == wm::WM_INPUT || msg == wm::WM_TIMER || msg == WM_REGISTER {
        PROBING.with(|p| {
            // Windows may call a window procedure again from inside one of its own calls: that one is let pass.
            if let Ok(mut p) = p.try_borrow_mut() {
                if let Some(p) = p.as_mut() {
                    match msg {
                        wm::WM_INPUT => p.on_input(lparam),
                        wm::WM_TIMER => p.thread.second(qpc()),
                        _ => p.register(hwnd),
                    }
                }
            }
        });
    }
    // SAFETY: the arguments Windows gave this procedure.
    unsafe { wm::DefWindowProcW(hwnd, msg, wparam, lparam) }
}

fn probe_run(shared: Arc<ProbeShared>, log: Log, window: Arc<AtomicIsize>, ready: std::sync::mpsc::Sender<()>) {
    let hwnd = match message_window("GyroPhoneDeliveryProbe", probe_proc) {
        Ok(h) => h,
        Err(e) => return log(&format!("delivery probe: no window (error {e})")),
    };
    // SAFETY: the calling thread's own pseudo-handle.
    unsafe {
        th::SetThreadPriority(th::GetCurrentThread(), th::THREAD_PRIORITY_HIGHEST);
    }
    let local = ProbeLocal { thread: ProbeThread::new(shared), log, woke_at: 0, slept: 0, since_wake: 0, read_at: 0, last_front_window: 0, last_front: "?".to_string() };
    window.store(hwnd as isize, Ordering::Release);
    PROBE_WINDOW.store(hwnd as isize, Ordering::Release);
    local.register(hwnd);
    PROBING.with(|p| *p.borrow_mut() = Some(local));
    // SAFETY: our own window on its own thread; MSG is plain data.
    unsafe {
        wm::SetTimer(hwnd, 1, 1000, None);
        let _ = ready.send(());
        let mut msg: wm::MSG = std::mem::zeroed();
        loop {
            // Nothing waiting: GetMessage sleeps until something comes, so note when it went to sleep and woke.
            let sleeps = wm::GetQueueStatus(wm::QS_ALLINPUT) >> 16 == 0;
            let before = qpc();
            if wm::GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) <= 0 {
                break;
            }
            let read_at = qpc();
            PROBING.with(|p| {
                if let Some(p) = p.borrow_mut().as_mut() {
                    p.read_at = read_at;
                    if sleeps {
                        p.woke_at = read_at;
                        p.slept = read_at - before;
                        p.since_wake = 0;
                    } else {
                        p.since_wake = p.since_wake.saturating_add(1);
                    }
                }
            });
            if msg.message == wm::WM_CLOSE && msg.hwnd == hwnd {
                break;
            }
            wm::DispatchMessageW(&msg);
        }
    }
    let _ = PROBE_WINDOW.compare_exchange(hwnd as isize, 0, Ordering::AcqRel, Ordering::Acquire);
    window.store(0, Ordering::Release);
    PROBING.with(|p| *p.borrow_mut() = None);
    {
        // While "Press a key" holds the raw mouse input, it is the listener's: removing it would take it from it.
        let listener = lock(&RAW_MOUSE_LISTENER);
        if !*listener {
            let _ = register(RIDEV_REMOVE, std::ptr::null_mut(), false);
        }
    }
    // SAFETY: our own window, on its thread.
    unsafe {
        wm::DestroyWindow(hwnd);
    }
}

/// Diagnostics' delivery probe, as `host::Platform` takes it: how long each of GyroPhone's mouse moves takes from
/// the start of its hand-off to Windows delivering it as raw input. Runs only between `start` and `stop`. The
/// input path only stores the move's start time and size (`sent`: no lock, nothing made).
pub struct RawDeliveryProbe {
    shared: Arc<ProbeShared>,
    log: Log,
    window: Arc<AtomicIsize>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl RawDeliveryProbe {
    pub fn new(log: Log) -> RawDeliveryProbe {
        let mut frequency = 0i64;
        // SAFETY: a valid out pointer.
        unsafe {
            QueryPerformanceFrequency(&mut frequency);
        }
        RawDeliveryProbe { shared: ProbeShared::new(frequency), log, window: Arc::new(AtomicIsize::new(0)), thread: Mutex::new(None) }
    }
}

impl DeliveryProbe for RawDeliveryProbe {
    fn start(&self) {
        let mut t = lock(&self.thread);
        if t.is_some() {
            return;
        }
        let (tx, rx) = std::sync::mpsc::channel();
        let (shared, log, window) = (self.shared.clone(), self.log.clone(), self.window.clone());
        *t = std::thread::Builder::new().name("gp-delivery".to_string()).spawn(move || probe_run(shared, log, window, tx)).ok();
        // Up before the first move is stored (or it gave up: the sender is dropped).
        let _ = rx.recv_timeout(std::time::Duration::from_millis(1000));
    }

    fn stop(&self) {
        let Some(t) = lock(&self.thread).take() else { return };
        let h = self.window.load(Ordering::Acquire);
        if h != 0 {
            // SAFETY: posting to a window that is gone fails harmlessly.
            unsafe {
                wm::PostMessageW(h as HWND, wm::WM_CLOSE, 0, 0);
            }
        }
        // Up to a second for it to end; a thread stuck longer ends on its own later.
        for _ in 0..100 {
            if t.is_finished() {
                let _ = t.join();
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
    }

    fn sent(&self, ticks: i64, dx: i32, dy: i32) {
        self.shared.ring.put(ticks, dx, dy);
    }

    fn second_text(&self) -> Option<String> {
        self.shared.second_text()
    }

    fn set_slow_line(&self, to: Option<Arc<dyn Fn(&str) + Send + Sync>>) {
        self.shared.set_slow_line(to);
    }

    fn start_test(&self, counting: bool) {
        self.shared.start_test(counting);
    }

    fn count_test(&self, counting: bool) {
        self.shared.count_test(counting);
    }

    fn stop_test(&self) {
        self.shared.stop_test();
    }

    fn test_numbers(&self) -> Option<DeliveryNumbers> {
        self.shared.test_numbers()
    }
}

impl Drop for RawDeliveryProbe {
    fn drop(&mut self) {
        self.stop();
    }
}
