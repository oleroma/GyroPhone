//! The direct USB link (Android Open Accessory). Thread gp-usb looks at the USB devices as soon as a device
//! arrives or goes, every 250 ms while a phone is switching to accessory mode, and otherwise as a fallback: a
//! phone in accessory mode with the WinUSB driver gets a `UsbBridge` into the UDP server; an Android phone in
//! normal mode (found by its adb interface, the one interface of a phone Windows lets a program send requests to)
//! is asked to switch to accessory mode, naming GyroPhone, so the app on the phone picks the cable up. Nothing is
//! installed here: the accessory interface needs WinUSB once, which the user does. The system's side (listing
//! devices, WinUSB) is `UsbSystem`. A port of AoaUsb.cs.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use crate::host::UsbLink;
use crate::udp::Log;
use crate::usb_bridge::{UsbBridge, UsbPipe};

pub const MANUFACTURER: &str = "GyroPhone";
pub const MODEL: &str = "GyroPhone Companion";

const RETRY_SWITCH: Duration = Duration::from_secs(10);
const RETRY_NO_AOA: Duration = Duration::from_secs(60);
const ERROR_ACCESS_DENIED: u32 = 5;
const ERROR_SHARING_VIOLATION: u32 = 32;

/// Where the direct USB link is, for the main window.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UsbState {
    /// Turned off in the menu.
    Off,
    /// No Android phone with USB debugging on the cable.
    NoPhone,
    /// adb (or another program) holds the phone's USB interface, so it can't be switched.
    AdbBusy,
    /// The phone was asked to switch to accessory mode and should come back as one.
    Switching,
    /// adb is not the phone's first USB function (USB tethering or file transfer is on), and Windows only passes
    /// device requests from the first one, so the switch is refused.
    WrongUsbMode,
    /// The phone is in accessory mode but Windows has no WinUSB driver for it.
    NeedsDriver,
    Connected,
    Failed,
}

impl UsbState {
    pub fn name(self) -> &'static str {
        match self {
            UsbState::Off => "Off",
            UsbState::NoPhone => "NoPhone",
            UsbState::AdbBusy => "AdbBusy",
            UsbState::Switching => "Switching",
            UsbState::WrongUsbMode => "WrongUsbMode",
            UsbState::NeedsDriver => "NeedsDriver",
            UsbState::Connected => "Connected",
            UsbState::Failed => "Failed",
        }
    }
}

/// How long gp-usb sleeps between looks. Each look without a bridge lists every USB device, so with device
/// notifications working the states that only a device change (or the switch in the menu) can end sleep long; a
/// phone held by adb, or one waiting for its next try, still gets looked at each second.
pub fn wait_ms(state: UsbState, notified: bool) -> u64 {
    match state {
        UsbState::Switching => 250,
        UsbState::Off | UsbState::NoPhone | UsbState::NeedsDriver if notified => 30_000,
        _ => 1000,
    }
}

/// A USB device node (a device, or one interface of a composite device).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct UsbNode {
    pub dev_inst: u32,
    pub instance_id: String,
    pub hardware_ids: Vec<String>,
    pub compatible_ids: Vec<String>,
    pub interface_guids: Vec<String>,
}

fn hex(s: &str) -> Option<u32> {
    (!s.is_empty() && s.bytes().all(|b| b.is_ascii_hexdigit())).then(|| u32::from_str_radix(s, 16).ok()).flatten()
}

/// The hex digits (`count` of them) right after the first `tag` in `id`, any letter case.
fn hex_after(id: &str, tag: &str, count: usize) -> Option<u32> {
    let upper = id.to_ascii_uppercase();
    let mut from = 0;
    while let Some(i) = upper[from..].find(tag) {
        let at = from + i + tag.len();
        if let Some(v) = upper.get(at..at + count).and_then(hex) {
            return Some(v);
        }
        from = at;
    }
    None
}

impl UsbNode {
    /// VID and PID, from the instance id (USB\VID_xxxx&PID_xxxx...).
    pub fn vid_pid(&self) -> Option<(u32, u32)> {
        let id = self.instance_id.to_ascii_uppercase();
        let rest = id.strip_prefix(r"USB\VID_")?;
        let vid = hex(rest.get(..4)?)?;
        let pid = hex(rest.get(4..)?.strip_prefix("&PID_")?.get(..4)?)?;
        Some((vid, pid))
    }

    /// The interface number of a composite device's function (MI_xx), or None for a whole device. A vendor's
    /// composite driver names interfaces ...&ADB or ...&MS_COMP_MTP in the instance id, so it is read from any
    /// id that has &MI_xx.
    pub fn interface(&self) -> Option<u32> {
        std::iter::once(&self.instance_id).chain(&self.hardware_ids).find_map(|id| hex_after(id, "&MI_", 2))
    }

    pub fn name(&self) -> &str {
        let id = self.hardware_ids.first().map_or("", |s| s.as_str());
        match id.get(..4) {
            Some(p) if p.eq_ignore_ascii_case(r"USB\") => &id[4..],
            _ => id,
        }
    }

    /// Any node of a phone in accessory mode (18D1:2D00 to 2D05): never asked to switch again.
    pub fn in_accessory_mode(&self) -> bool {
        matches!(self.vid_pid(), Some((0x18D1, 0x2D00..=0x2D05)))
    }

    /// The accessory interface: 18D1:2D00 itself, or interface 0 of 18D1:2D01 (accessory + adb).
    pub fn is_accessory(&self) -> bool {
        match (self.vid_pid(), self.interface()) {
            (Some((0x18D1, 0x2D00)), None) => true,
            (Some((0x18D1, 0x2D01)), Some(0)) => true,
            _ => false,
        }
    }

    /// An adb interface (class FF, subclass 42, protocol 1) of a phone not in accessory mode.
    pub fn is_adb(&self) -> bool {
        !self.in_accessory_mode()
            && !self.interface_guids.is_empty()
            && self.compatible_ids.iter().any(|c| c.to_ascii_uppercase().contains("CLASS_FF&SUBCLASS_42&PROT_01"))
    }
}

/// One USB interface opened for requests (WinUSB).
pub trait UsbDevice: Send {
    /// A control transfer; the bytes moved, or the system's error.
    fn control(&self, request_type: u8, request: u8, value: u16, index: u16, data: &mut [u8]) -> Result<u32, u32>;
    /// Its bulk pipes for the bridge; the text says why not.
    fn into_pipe(self: Box<Self>) -> Result<Arc<dyn UsbPipe>, String>;
}

/// What the link needs from the system.
pub trait UsbSystem: Send + Sync {
    /// The USB device nodes present now.
    fn list(&self) -> Result<Vec<UsbNode>, String>;
    /// The path to open the node with, or None when it has no driver interface (no WinUSB).
    fn interface_path(&self, node: &UsbNode) -> Option<String>;
    /// Opens it, or the system's error (access denied: another program has it).
    fn open(&self, path: &str) -> Result<Box<dyn UsbDevice>, u32>;
    /// Stops the adb server (it starts again on its next use).
    fn stop_adb_server(&self, log: &Log);
    /// Calls `changed` when any device arrives or goes, until what it returns is dropped. None: can't.
    fn watch(&self, changed: Arc<dyn Fn() + Send + Sync>, log: &Log) -> Option<Box<dyn Send + Sync>>;
    /// This PC's name, for the phone.
    fn machine_name(&self) -> String;
    /// Run at the start of each of the bridge's threads (they carry every packet ahead of gp-rx).
    fn on_bridge_thread(&self) -> Option<Arc<dyn Fn() + Send + Sync>> {
        None
    }
}

/// Android Open Accessory requests (protocol v1): asks the phone to come back as an accessory.
pub fn aoa_switch(dev: &dyn UsbDevice, machine_name: &str) -> Result<(), String> {
    const GET_PROTOCOL: u8 = 51;
    const SEND_STRING: u8 = 52;
    const START_ACCESSORY: u8 = 53;
    let mut version = [0u8; 2];
    match dev.control(0xC0, GET_PROTOCOL, 0, 0, &mut version) {
        Ok(got) if got >= 2 => {}
        Ok(_) => return Err("no AOA answer (error 0)".to_string()),
        Err(e) => return Err(format!("no AOA answer (error {e})")),
    }
    if u16::from_le_bytes(version) < 1 {
        return Err("the phone does not support accessory mode".to_string());
    }
    let strings = [MANUFACTURER, MODEL, "Gamepad, keyboard and mouse for this PC", "1", "https://github.com/oleroma/GyroPhone", machine_name];
    for (i, s) in strings.iter().enumerate() {
        let mut bytes: Vec<u8> = s.bytes().chain(std::iter::once(0)).collect();
        dev.control(0x40, SEND_STRING, 0, i as u16, &mut bytes).map_err(|e| format!("string {i} refused (error {e})"))?;
    }
    dev.control(0x40, START_ACCESSORY, 0, 0, &mut []).map_err(|e| format!("start refused (error {e})"))?;
    Ok(())
}

struct Tried {
    at: Instant,
    wait: Duration,
    wrong_mode: bool,
}

struct Status {
    state: UsbState,
    detail: String,
}

/// An event a waiter sleeps on: set from any thread, cleared by the waiter.
#[derive(Default)]
struct Wake {
    set: Mutex<bool>,
    cv: Condvar,
}

impl Wake {
    fn set(&self) {
        *self.set.lock().unwrap_or_else(|e| e.into_inner()) = true;
        self.cv.notify_all();
    }

    fn reset(&self) {
        *self.set.lock().unwrap_or_else(|e| e.into_inner()) = false;
    }

    fn wait(&self, ms: u64) {
        let guard = self.set.lock().unwrap_or_else(|e| e.into_inner());
        let _ = self.cv.wait_timeout_while(guard, Duration::from_millis(ms), |set| !*set);
    }
}

struct Inner {
    system: Arc<dyn UsbSystem>,
    server_port: u16,
    log: Log,
    wake: Wake,
    enabled: AtomicBool,
    stopping: AtomicBool,
    stop_adb: AtomicBool,
    status: Mutex<Status>,
    watching: AtomicBool,
}

/// gp-usb's own state.
struct Scanner {
    tried: HashMap<String, Tried>,
    bridge: Option<UsbBridge>,
    last_logged: String,
}

impl Inner {
    fn set(&self, sc: &mut Scanner, state: UsbState, detail: &str) {
        {
            let mut s = self.status.lock().unwrap_or_else(|e| e.into_inner());
            s.state = state;
            if s.detail != detail {
                s.detail = detail.to_string();
            }
        }
        // The connected bridge's counters change all the time: logged once.
        let line = if state == UsbState::Connected { "Connected".to_string() } else { format!("{} {detail}", state.name()) };
        if line != sc.last_logged {
            (self.log)(&format!("usb: {line}"));
            sc.last_logged = line;
        }
    }

    fn state(&self) -> UsbState {
        self.status.lock().unwrap_or_else(|e| e.into_inner()).state
    }

    fn scan(&self, sc: &mut Scanner) {
        if self.stop_adb.swap(false, Ordering::AcqRel) {
            self.system.stop_adb_server(&self.log);
            sc.tried.clear();
        }
        if !self.enabled.load(Ordering::Acquire) {
            sc.bridge = None;
            return self.set(sc, UsbState::Off, "");
        }
        if let Some(b) = &sc.bridge {
            if b.alive() {
                let detail = format!("{} in, {} out, {} unsent", b.from_phone(), b.to_phone(), b.write_failures());
                return self.set(sc, UsbState::Connected, &detail);
            }
            sc.bridge = None;
        }
        let nodes = match self.system.list() {
            Ok(n) => n,
            Err(e) => return self.set(sc, UsbState::Failed, &e),
        };
        if let Some(accessory) = nodes.iter().find(|n| n.is_accessory()) {
            return self.open_accessory(sc, accessory);
        }
        if nodes.iter().any(|n| n.in_accessory_mode()) {
            // Switched, but its accessory interface isn't listed (yet): still starting.
            return self.set(sc, UsbState::Switching, "");
        }
        let (mut busy, mut waiting, mut wrong_mode) = (false, false, false);
        let now = Instant::now();
        for n in nodes.iter().filter(|n| n.is_adb()) {
            let key = n.instance_id.to_ascii_uppercase();
            if let Some(t) = sc.tried.get(&key).filter(|t| now.duration_since(t.at) < t.wait) {
                waiting |= !t.wrong_mode && t.wait == RETRY_SWITCH;
                wrong_mode |= t.wrong_mode;
                continue;
            }
            let Some(path) = self.system.interface_path(n) else { continue };
            let dev = match self.system.open(&path) {
                Ok(d) => d,
                Err(e) => {
                    busy |= e == ERROR_ACCESS_DENIED || e == ERROR_SHARING_VIOLATION;
                    continue;
                }
            };
            match aoa_switch(dev.as_ref(), &self.system.machine_name()) {
                Ok(()) => {
                    sc.tried.insert(key, Tried { at: now, wait: RETRY_SWITCH, wrong_mode: false });
                    (self.log)(&format!("usb: asked {} to switch to accessory mode", n.name()));
                    return self.set(sc, UsbState::Switching, n.name());
                }
                Err(why) => {
                    // Windows hands device requests only from a composite device's first interface to it.
                    let not_first = n.interface().is_some_and(|mi| mi != 0);
                    sc.tried.insert(key, Tried { at: now, wait: if not_first { RETRY_SWITCH } else { RETRY_NO_AOA }, wrong_mode: not_first });
                    wrong_mode |= not_first;
                    (self.log)(&format!("usb: {} did not switch: {why}", n.name()));
                }
            }
        }
        let state = if waiting {
            UsbState::Switching
        } else if wrong_mode {
            UsbState::WrongUsbMode
        } else if busy {
            UsbState::AdbBusy
        } else {
            UsbState::NoPhone
        };
        self.set(sc, state, "");
    }

    fn open_accessory(&self, sc: &mut Scanner, n: &UsbNode) {
        let Some(path) = self.system.interface_path(n) else { return self.set(sc, UsbState::NeedsDriver, n.name()) };
        let dev = match self.system.open(&path) {
            Ok(d) => d,
            Err(e) => return self.set(sc, UsbState::Failed, &format!("cannot open the accessory (error {e})")),
        };
        let pipe = match dev.into_pipe() {
            Ok(p) => p,
            Err(why) => return self.set(sc, UsbState::Failed, &why),
        };
        let bridge = UsbBridge::new(pipe, self.server_port, Some(self.log.clone()), self.system.on_bridge_thread()).and_then(|b| b.start().map(|()| b));
        match bridge {
            Ok(b) => {
                (self.log)(&format!("usb: accessory open ({}), bridged to UDP {} from {}", n.name(), self.server_port, b.local_addr()));
                sc.bridge = Some(b);
                self.set(sc, UsbState::Connected, "");
            }
            Err(e) => self.set(sc, UsbState::Failed, &format!("no bridge: {e}")),
        }
    }

    fn run(&self) {
        let mut sc = Scanner { tried: HashMap::new(), bridge: None, last_logged: String::new() };
        while !self.stopping.load(Ordering::Acquire) {
            // Cleared before the scan, not after the wait: a device change during the scan wakes the next wait.
            self.wake.reset();
            self.scan(&mut sc);
            // Each step of the switch (the phone leaving, coming back as an accessory, its driver loading) is a
            // device arrival, which wakes this at once; the short wait only backs up a missed one.
            self.wake.wait(wait_ms(self.state(), self.watching.load(Ordering::Acquire)));
        }
        sc.bridge = None;
    }
}

/// The direct USB link, as `host::Platform` takes it.
pub struct AoaUsbLink {
    inner: Arc<Inner>,
    running: Mutex<Option<(std::thread::JoinHandle<()>, Option<Box<dyn Send + Sync>>)>>,
}

impl AoaUsbLink {
    pub fn new(system: Arc<dyn UsbSystem>, server_port: u16, log: Log) -> AoaUsbLink {
        AoaUsbLink {
            inner: Arc::new(Inner {
                system,
                server_port,
                log,
                wake: Wake::default(),
                enabled: AtomicBool::new(false),
                stopping: AtomicBool::new(false),
                stop_adb: AtomicBool::new(false),
                status: Mutex::new(Status { state: UsbState::Off, detail: String::new() }),
                watching: AtomicBool::new(false),
            }),
            running: Mutex::new(None),
        }
    }
}

impl UsbLink for AoaUsbLink {
    fn start(&self) {
        let mut running = self.running.lock().unwrap_or_else(|e| e.into_inner());
        if running.is_some() || self.inner.stopping.load(Ordering::Acquire) {
            return;
        }
        let inner = self.inner.clone();
        let watch = self.inner.system.watch(Arc::new(move || inner.wake.set()), &self.inner.log);
        self.inner.watching.store(watch.is_some(), Ordering::Release);
        let inner = self.inner.clone();
        match std::thread::Builder::new().name("gp-usb".to_string()).spawn(move || inner.run()) {
            Ok(t) => *running = Some((t, watch)),
            Err(e) => (self.inner.log)(&format!("usb: no thread: {e}")),
        }
    }

    fn stop(&self) {
        self.inner.stopping.store(true, Ordering::Release);
        let Some((thread, watch)) = self.running.lock().unwrap_or_else(|e| e.into_inner()).take() else { return };
        drop(watch);
        self.inner.wake.set();
        // Up to 3 s: a scan may be inside a device request. A thread stuck longer ends on its own.
        for _ in 0..300 {
            if thread.is_finished() {
                let _ = thread.join();
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn enabled(&self) -> bool {
        self.inner.enabled.load(Ordering::Acquire)
    }

    fn set_enabled(&self, on: bool) {
        self.inner.enabled.store(on, Ordering::Release);
        self.inner.wake.set();
    }

    fn state(&self) -> String {
        self.inner.state().name().to_string()
    }

    fn detail(&self) -> String {
        self.inner.status.lock().unwrap_or_else(|e| e.into_inner()).detail.clone()
    }

    /// The user asked to take the phone from adb: stops the adb server (it starts again on its next use, and
    /// finds the phone on the accessory's own adb interface) and switches the phone.
    fn take_from_adb(&self) {
        self.inner.stop_adb.store(true, Ordering::Release);
        self.inner.wake.set();
    }
}

impl Drop for AoaUsbLink {
    fn drop(&mut self) {
        self.stop();
    }
}
