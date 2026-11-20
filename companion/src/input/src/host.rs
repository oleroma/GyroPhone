//! The input process itself (InputHost.cs): it puts the engine, the phone link, the outputs and the companion's
//! line together, starts them in the right order and stops them cleanly.
//!
//! Everything that is Windows comes in through `Platform` (the outputs, the drivers, the named pipe, the probes),
//! so this file, with the start-up, the retries, the status and the shut-down, runs and is tested anywhere.
//!
//! Lifetime: tray Exit stops it. When the companion goes away without saying so (it crashed), input keeps going
//! and the companion is started again, which attaches again.

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock, RwLock, Weak};
use std::time::{Duration, Instant};

use serde_json::Value;

use crate::control::{self, DeliveryNumbers, InputControl, InputHello, InputSettingsView, InputStatus};
use crate::engine::{
    EngineHooks, EngineOptions, EnrollSave, HostEngine, HostSnapshot, PairedPhoneInfo, PhoneSecond, Signal, TraceKind,
};
use crate::held_marker::HeldKeysMarker;
use crate::key_files::{AuthorizedPhones, KeyError, KeyFiles};
use crate::mapping::input_diff::{self, MAX_RELEASE_EVENTS};
use crate::mapping::InjectEvent;
use crate::pipe::{Channel, Duplex, EventOutbox, Payload};
use crate::ports::{Clock, HostEvents, KeyMouseListener, KeyMouseSink, KeyProtector, PadSink, PairPrompt, PcPad};
use crate::recorder::{InputRecorder, RecorderOut};
use crate::settings::{self, LoadError, SettingsStore};
use crate::shared::{MoveRing, SharedRegion, LAYOUT_VERSION};
use crate::state_frame::KEY_BITMAP_SIZE;
use crate::sys::{self, Priority};
use crate::udp::{CatchUpWaitFactory, Log, ServerState, UdpHostServer, UdpServerOptions};
use crate::watchdog::{Probes, TickSleepFactory, Watchdog, WatchdogOptions};
use crate::wire;

/// How long after the companion went away (not by Exit) it is started again.
pub const RELAUNCH_DELAY_MS: u64 = 2000;
/// A companion that keeps crashing (more than 5 starts within 10 minutes) is started again only once a minute.
pub const SLOW_RELAUNCH_DELAY_MS: u64 = 60_000;
const RELAUNCH_WINDOW: Duration = Duration::from_secs(600);
const FAST_BIND: Duration = Duration::from_millis(5000);
const RETRY_MS: u64 = 5000;

type Action = Arc<dyn Fn() + Send + Sync>;

// =====================================================================================
// what Windows provides
// =====================================================================================

/// SendInput.
pub trait SendInput: KeyMouseSink {
    /// Calls Windows took fewer events from than it was given.
    fn short_writes(&self) -> i64 {
        0
    }
}

/// The input driver's plugged mouse and keyboard. Dropping it unplugs them.
pub trait DriverSink: KeyMouseSink {
    fn plugged(&self) -> bool;
}

/// The optional GyroPhone input driver.
pub trait InputDriver: Send + Sync {
    fn installed(&self) -> bool;
    /// Plugs its mouse and keyboard (slow: never under the engine lock on the input path); None when it can't.
    fn create(&self, log: &dyn Fn(&str)) -> Option<Arc<dyn DriverSink>>;
}

/// No input driver.
pub struct NoInputDriver;

impl InputDriver for NoInputDriver {
    fn installed(&self) -> bool {
        false
    }
    fn create(&self, _log: &dyn Fn(&str)) -> Option<Arc<dyn DriverSink>> {
        None
    }
}

/// The direct USB link (Android Open Accessory) with its own thread.
pub trait UsbLink: Send + Sync {
    fn start(&self);
    fn stop(&self);
    fn enabled(&self) -> bool;
    fn set_enabled(&self, on: bool);
    /// Off, NoPhone, AdbBusy, Switching, WrongUsbMode, NeedsDriver, Connected or Failed.
    fn state(&self) -> String;
    fn detail(&self) -> String;
    fn take_from_adb(&self);
}

/// No USB link.
pub struct NoUsbLink;

impl UsbLink for NoUsbLink {
    fn start(&self) {}
    fn stop(&self) {}
    fn enabled(&self) -> bool {
        false
    }
    fn set_enabled(&self, _on: bool) {}
    fn state(&self) -> String {
        "Off".to_string()
    }
    fn detail(&self) -> String {
        String::new()
    }
    fn take_from_adb(&self) {}
}

/// Diagnostics' delivery probe: reads our own mouse moves back from Windows and times them. `sent` is the input
/// path's (no lock, no allocation); the rest is called off it.
pub trait DeliveryProbe: Send + Sync {
    fn start(&self);
    fn stop(&self);
    fn sent(&self, ticks: i64, dx: i32, dy: i32);
    /// The last second's numbers for the input log's second line (handed over once).
    fn second_text(&self) -> Option<String>;
    /// Who takes one line per slow move (None: none made).
    fn set_slow_line(&self, to: Option<Arc<dyn Fn(&str) + Send + Sync>>);
    fn start_test(&self, counting: bool);
    fn count_test(&self, counting: bool);
    fn stop_test(&self);
    fn test_numbers(&self) -> Option<DeliveryNumbers>;
}

/// Where companions connect (a named pipe for the current user only).
pub trait PipeListener: Send + Sync {
    /// Waits for a companion.
    fn accept(&self) -> std::io::Result<Arc<dyn Duplex>>;
    /// Ends a waiting `accept` (it fails), now and from here on.
    fn cancel(&self);
}

/// Everything the input process needs from the system.
pub struct Platform {
    pub version: String,
    pub pid: u32,
    pub elevated: bool,
    pub machine_name: String,
    pub log: Log,
    /// Why the log file can't be written, if it can't.
    pub log_error: Arc<dyn Fn() -> Option<String> + Send + Sync>,
    /// input.log, written only during a Link test (its script reads it).
    pub input_log: Log,
    /// The local time as HH:mm:ss.
    pub local_time: Arc<dyn Fn() -> String + Send + Sync>,
    pub clock: Arc<dyn Clock>,
    pub settings_path: PathBuf,
    pub held_path: PathBuf,
    pub keys_dir: PathBuf,
    pub send_input: Arc<dyn SendInput>,
    pub input_driver: Arc<dyn InputDriver>,
    /// Makes the pad sink; the text says why ViGEmBus can't be used (None when it can).
    pub pads: Arc<dyn Fn() -> (Arc<dyn PadSink>, Option<String>) + Send + Sync>,
    pub protector: Arc<dyn KeyProtector>,
    pub region: SharedRegion,
    pub region_name: String,
    /// Wakes the companion's overlay thread.
    pub overlay_wake: Action,
    pub overlay_wake_name: String,
    pub pc_pad: Option<Arc<dyn PcPad>>,
    pub key_listener: Option<Arc<dyn KeyMouseListener>>,
    pub delivery_probe: Arc<dyn Fn() -> Option<Arc<dyn DeliveryProbe>> + Send + Sync>,
    /// Watches a folder: calls the action on any change until what it returns is dropped. None: can't watch.
    pub watch_folder: Arc<dyn Fn(&Path, Action) -> Option<Box<dyn Send + Sync>> + Send + Sync>,
    pub rx_thread_start: Option<Action>,
    pub catch_up_wait: Option<CatchUpWaitFactory>,
    pub tick_sleep: Option<TickSleepFactory>,
    pub probes: Probes,
    pub usb: Arc<dyn UsbLink>,
    /// Asks a running watcher to let go of the UDP port; true when one was asked.
    pub yield_port: Arc<dyn Fn() -> bool + Send + Sync>,
    pub pipes: Arc<dyn PipeListener>,
    /// The companion (pid) went away without Exit: once it is surely gone and the wait (ms) is over, start it
    /// again if the check still says so and none runs. Returns at once (its own thread).
    pub relaunch_companion: Arc<dyn Fn(i32, u64, Box<dyn Fn() -> bool + Send>) + Send + Sync>,
    /// Where the UDP server binds; None: every address, port 47800.
    pub bind: Option<SocketAddr>,
    /// How often the host key (while its file is held) and the input driver (while it isn't plugged) are tried
    /// again, ms; 0: every 5 s.
    pub retry_ms: u64,
}

// =====================================================================================
// the keyboard and mouse route
// =====================================================================================

/// The engine's keyboard and mouse sink: SendInput, unless "Use input driver" (Performance) is on and the driver
/// is installed and plugged. Inject runs under the engine lock; the switch is changed only through the engine,
/// which lets go of everything first, so no key stays held on the route it leaves.
pub struct KeyMouseRouter {
    send_input: Arc<dyn SendInput>,
    factory: Arc<dyn InputDriver>,
    log: Log,
    // Read on every inject; written only under the engine lock, when the route changes.
    driver: RwLock<Option<Arc<dyn DriverSink>>>,
    use_input_driver: AtomicBool,
    via_driver: AtomicBool,
}

impl KeyMouseRouter {
    pub fn new(send_input: Arc<dyn SendInput>, factory: Arc<dyn InputDriver>, use_input_driver: bool, log: Log) -> KeyMouseRouter {
        KeyMouseRouter {
            send_input,
            factory,
            log,
            driver: RwLock::new(None),
            use_input_driver: AtomicBool::new(use_input_driver),
            via_driver: AtomicBool::new(false),
        }
    }

    /// The input driver is installed and its devices are plugged.
    pub fn driver_ready(&self) -> bool {
        self.driver.read().unwrap_or_else(|e| e.into_inner()).as_ref().is_some_and(|d| d.plugged())
    }

    pub fn use_input_driver(&self) -> bool {
        self.use_input_driver.load(Ordering::Relaxed)
    }

    pub fn send_input(&self) -> &Arc<dyn SendInput> {
        &self.send_input
    }

    /// Switches "Use input driver". On plugs the driver's mouse and keyboard now (when it's installed); off
    /// unplugs them. Called through `HostEngine::set_use_input_driver`, under the engine lock, after everything
    /// held was let go.
    pub fn set_use_input_driver(&self, on: bool) {
        self.use_input_driver.store(on, Ordering::Relaxed);
        if on {
            self.retry();
        } else {
            *self.driver.write().unwrap_or_else(|e| e.into_inner()) = None;
        }
    }

    /// Plugs the driver's mouse and keyboard when the switch is on and they aren't plugged yet.
    pub fn retry(&self) -> bool {
        if !self.use_input_driver() {
            return false;
        }
        if self.driver_ready() {
            return true;
        }
        let mut d = self.driver.write().unwrap_or_else(|e| e.into_inner());
        *d = None;
        *d = self.factory.create(&*self.log);
        d.as_ref().is_some_and(|d| d.plugged())
    }

    /// Takes a driver sink plugged off the engine lock, when the switch is still on and no driver is ready yet;
    /// otherwise drops it. True when the driver is ready. Called through `HostEngine::change_key_mouse_route`.
    pub fn adopt(&self, plugged: Arc<dyn DriverSink>) -> bool {
        if !self.use_input_driver() || self.driver_ready() {
            drop(plugged);
            return self.driver_ready();
        }
        let mut d = self.driver.write().unwrap_or_else(|e| e.into_inner());
        *d = Some(plugged);
        d.as_ref().is_some_and(|d| d.plugged())
    }

    /// Unplugs the driver's devices (exit).
    pub fn close(&self) {
        *self.driver.write().unwrap_or_else(|e| e.into_inner()) = None;
    }
}

impl KeyMouseSink for KeyMouseRouter {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        let d = self.driver.read().unwrap_or_else(|e| e.into_inner());
        if let Some(d) = d.as_ref() {
            if d.plugged() && self.use_input_driver() {
                self.via_driver.store(true, Ordering::Relaxed);
                // Gone underneath: false, so the engine sends the held set again (now through SendInput).
                return d.inject(events);
            }
        }
        drop(d);
        self.via_driver.store(false, Ordering::Relaxed);
        self.send_input.inject(events)
    }

    fn route(&self) -> &'static str {
        if self.via_driver.load(Ordering::Relaxed) {
            "input driver"
        } else {
            "SendInput"
        }
    }
}

// =====================================================================================
// the engine's callbacks
// =====================================================================================

#[derive(Default)]
struct ProbeState {
    diag_on: bool,
    test_on: bool,
    probe: Option<Arc<dyn DeliveryProbe>>,
}

/// Where the input path's mouse moves go.
#[derive(Default)]
struct MoveRoute {
    probe: Option<Arc<dyn DeliveryProbe>>,
    /// Also into the shared ring the Delivery test window reads.
    ring: Option<MoveRing>,
}

/// What the engine's callbacks need. They come from the rx or watchdog thread: each only queues or sets a flag.
struct Core {
    log: Log,
    input_log: Log,
    clock: Arc<dyn Clock>,
    out: EventOutbox,
    engine: OnceLock<Arc<HostEngine>>,
    overlay_wake: Action,
    status_queued: AtomicBool,
    status_wake: Signal,
    probe_factory: Arc<dyn Fn() -> Option<Arc<dyn DeliveryProbe>> + Send + Sync>,
    probe_gate: Mutex<ProbeState>,
    diag_on: AtomicBool,
    moves: RwLock<MoveRoute>,
    me: Weak<Core>,
}

impl Core {
    fn engine(&self) -> Option<&Arc<HostEngine>> {
        self.engine.get()
    }

    fn state_changed(&self) {
        if !self.status_queued.swap(true, Ordering::AcqRel) {
            self.status_wake.set();
        }
    }

    /// A Diagnostics line of the input process's own, stamped now.
    fn trace(&self, kind: TraceKind, text: &str) {
        if self.engine().is_some_and(|e| e.trace().enabled()) {
            self.out.post("debug", Payload::Debug { us: self.clock.now_us(), kind: kind as u8, text: text.to_string() });
        }
    }

    /// Starts or stops Diagnostics' delivery probe with Diagnostics (any thread but the input path's).
    fn set_delivery_probe(&self, on: bool) {
        let mut st = self.probe_gate.lock().unwrap_or_else(|e| e.into_inner());
        st.diag_on = on;
        self.diag_on.store(on, Ordering::Relaxed);
        self.update_probe(&mut st);
    }

    /// The probe runs while Diagnostics is on or a Delivery test runs; the engine's moves go to it, and to the
    /// shared ring the test window reads while a test runs. Off, the input path stores nothing.
    fn update_probe(&self, st: &mut ProbeState) {
        let want = st.diag_on || st.test_on;
        if want && st.probe.is_none() {
            if let Some(p) = (self.probe_factory)() {
                p.start();
                st.probe = Some(p);
            }
        }
        if let Some(p) = &st.probe {
            let me = self.me.clone();
            let slow: Option<Arc<dyn Fn(&str) + Send + Sync>> = if st.diag_on {
                Some(Arc::new(move |text: &str| {
                    if let Some(me) = me.upgrade() {
                        me.trace(TraceKind::Note, text);
                    }
                }))
            } else {
                None
            };
            p.set_slow_line(slow);
        }
        let probe = if want { st.probe.clone() } else { None };
        let ring = if st.test_on && probe.is_some() { self.engine().map(|e| e.shared().moves()) } else { None };
        let watching = probe.is_some();
        *self.moves.write().unwrap_or_else(|e| e.into_inner()) = MoveRoute { probe, ring };
        if let Some(e) = self.engine() {
            e.set_move_watch(watching);
        }
        if !want {
            if let Some(p) = st.probe.take() {
                p.stop();
            }
        }
    }

    fn set_delivery_test(&self, on: bool, counting: bool) {
        let mut st = self.probe_gate.lock().unwrap_or_else(|e| e.into_inner());
        if on != st.test_on {
            st.test_on = false;
            self.update_probe(&mut st); // the ring gets nothing while it is emptied
            if on {
                if let Some(e) = self.engine() {
                    e.shared().moves().reset();
                }
            }
            st.test_on = on;
            self.update_probe(&mut st);
            if let Some(p) = &st.probe {
                if on {
                    p.start_test(counting);
                } else {
                    p.stop_test();
                }
            }
            (self.log)(&format!("input: delivery test {}", if on { "started" } else { "stopped" }));
        } else if on {
            if let Some(p) = &st.probe {
                p.count_test(counting);
            }
        }
    }

    fn delivery_test_numbers(&self) -> Option<DeliveryNumbers> {
        let st = self.probe_gate.lock().unwrap_or_else(|e| e.into_inner());
        if st.test_on {
            st.probe.as_ref().and_then(|p| p.test_numbers())
        } else {
            None
        }
    }
}

impl HostEvents for Core {
    fn state_changed(&self) {
        Core::state_changed(self);
    }

    fn log(&self, message: &str) {
        (self.log)(message);
    }
}

impl PairPrompt for Core {
    fn show(&self, attempt_id: u32, phone_name: &str, sas_display: &str) {
        self.out.post(
            "pair-show",
            Payload::PairShow { attempt_id, phone_name: phone_name.to_string(), sas: sas_display.to_string() },
        );
        Core::state_changed(self);
    }

    fn close(&self, attempt_id: u32) {
        self.out.post("pair-close", Payload::PairClose(attempt_id));
        Core::state_changed(self);
    }
}

impl EngineHooks for Core {
    fn debug_line(&self, us: i64, kind: TraceKind, text: &str) {
        self.out.post("debug", Payload::Debug { us, kind: kind as u8, text: text.to_string() });
    }

    /// The input recorder's lines go into Diagnostics (the companion's), and into input.log only during a Link
    /// test; its seconds go to the companion's Performance monitor.
    fn recorder(&self, out: &[RecorderOut]) {
        let Some(engine) = self.engine() else { return };
        for o in out {
            match o {
                RecorderOut::Line(ev) => {
                    let line = ev.text();
                    self.trace(TraceKind::Link, &line);
                    if engine.link_test_running() {
                        (self.input_log)(&line);
                    }
                }
                RecorderOut::Second(s) => self.out.post("link-second", Payload::LinkSecond(*s)),
            }
        }
    }

    fn overlay_wake(&self) {
        (self.overlay_wake)();
    }

    fn debug_save_requested(&self) {
        self.out.post("debug-save", Payload::None);
    }

    fn diagnostics_changed(&self, on: bool) {
        self.set_delivery_probe(on);
        Core::state_changed(self);
    }

    fn phone_second(&self, s: &PhoneSecond) {
        self.out.post("phone-second", Payload::PhoneSecond(*s));
    }

    fn move_sent(&self, t0_ticks: i64, dx: i32, dy: i32) {
        let r = self.moves.read().unwrap_or_else(|e| e.into_inner());
        if let Some(p) = &r.probe {
            p.sent(t0_ticks, dx, dy);
            if let Some(ring) = &r.ring {
                ring.put(t0_ticks, dx, dy);
            }
        }
    }
}

// =====================================================================================
// the authorized_phones folder
// =====================================================================================

struct AuthorizedInner {
    files: Arc<KeyFiles>,
    engine: Arc<HostEngine>,
    log: Log,
    local_time: Arc<dyn Fn() -> String + Send + Sync>,
    phones: Mutex<AuthorizedPhones>,
    last_scan: Mutex<String>,
    /// When the folder is read next.
    due: Mutex<Option<Instant>>,
    wake: Condvar,
    stop: AtomicBool,
}

/// Keeps the engine's key-paired phones the same as the authorized_phones folder: read at start, shortly after
/// any change, and again a second later while a file was held by another program.
struct AuthorizedWatcher {
    inner: Arc<AuthorizedInner>,
    watch: Mutex<Option<Box<dyn Send + Sync>>>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl AuthorizedInner {
    fn kick(&self, ms: u64) {
        *self.due.lock().unwrap_or_else(|e| e.into_inner()) = Some(Instant::now() + Duration::from_millis(ms));
        self.wake.notify_all();
    }

    fn reload(&self) {
        let mut phones = self.phones.lock().unwrap_or_else(|e| e.into_inner());
        if self.stop.load(Ordering::Acquire) {
            return;
        }
        let (list, retry) = phones.reload(&self.files, &*self.log);
        let time = (self.local_time)();
        let scan = match list {
            Some(list) => {
                self.engine.set_authorized_phones(list);
                format!("{}, {time}", phones.last_scan)
            }
            None => format!("failed {time}{}", phones.last_scan.strip_prefix("failed").unwrap_or(&phones.last_scan)),
        };
        *self.last_scan.lock().unwrap_or_else(|e| e.into_inner()) = scan;
        if retry {
            self.kick(AuthorizedPhones::RETRY_MS);
        }
    }

    fn run(&self) {
        loop {
            {
                let mut due = self.due.lock().unwrap_or_else(|e| e.into_inner());
                loop {
                    if self.stop.load(Ordering::Acquire) {
                        return;
                    }
                    match *due {
                        None => due = self.wake.wait(due).unwrap_or_else(|e| e.into_inner()),
                        Some(at) => {
                            let now = Instant::now();
                            if at <= now {
                                *due = None;
                                break;
                            }
                            due = self.wake.wait_timeout(due, at - now).unwrap_or_else(|e| e.into_inner()).0;
                        }
                    }
                }
            }
            self.reload();
        }
    }
}

impl AuthorizedWatcher {
    fn start(files: Arc<KeyFiles>, engine: Arc<HostEngine>, platform: &Platform) -> AuthorizedWatcher {
        let log = platform.log.clone();
        let inner = Arc::new(AuthorizedInner {
            files,
            engine,
            log: log.clone(),
            local_time: platform.local_time.clone(),
            phones: Mutex::new(AuthorizedPhones::new()),
            last_scan: Mutex::new("not read yet".to_string()),
            due: Mutex::new(None),
            wake: Condvar::new(),
            stop: AtomicBool::new(false),
        });
        let dir = inner.files.authorized_directory();
        let mut watch = None;
        match std::fs::create_dir_all(&dir) {
            Ok(()) => {
                let kicked = Arc::downgrade(&inner);
                let on_change: Action = Arc::new(move || {
                    if let Some(i) = kicked.upgrade() {
                        i.kick(AuthorizedPhones::DEBOUNCE_MS);
                    }
                });
                watch = (platform.watch_folder)(&dir, on_change);
                if watch.is_none() {
                    log("cannot watch authorized_phones");
                }
            }
            Err(e) => log(&format!("cannot watch authorized_phones: {:?}", e.kind())),
        }
        let for_thread = inner.clone();
        let thread = std::thread::Builder::new()
            .name("gp-authorized".to_string())
            .spawn(move || {
                sys::set_thread_priority(Priority::BelowNormal);
                for_thread.run();
            })
            .ok();
        inner.reload();
        AuthorizedWatcher { inner, watch: Mutex::new(watch), thread: Mutex::new(thread) }
    }

    fn last_scan(&self) -> String {
        self.inner.last_scan.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn reload(&self) {
        self.inner.reload();
    }

    fn stop(&self) {
        // Under the reload's lock, so no read of the folder is still handing phones to the engine afterwards.
        {
            let _phones = self.inner.phones.lock().unwrap_or_else(|e| e.into_inner());
            self.inner.stop.store(true, Ordering::Release);
        }
        *self.watch.lock().unwrap_or_else(|e| e.into_inner()) = None;
        {
            let _due = self.inner.due.lock().unwrap_or_else(|e| e.into_inner());
            self.inner.wake.notify_all();
        }
        let t = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(t) = t {
            let _ = t.join();
        }
    }
}

// =====================================================================================
// the host
// =====================================================================================

/// The input process, running.
pub struct InputHost {
    p: Platform,
    core: Arc<Core>,
    key_mouse: Arc<KeyMouseRouter>,
    engine: Arc<HostEngine>,
    server: UdpHostServer,
    watchdog: Watchdog,
    key_files: Arc<KeyFiles>,
    authorized: Mutex<Option<AuthorizedWatcher>>,
    pad: Mutex<Arc<dyn PadSink>>,
    pad_error: Mutex<Option<String>>,
    key_error: Mutex<Option<String>>,
    key_retrying: AtomicBool,
    bind_retrying: AtomicBool,
    bind_wait_long: AtomicBool,
    input_driver_installed: AtomicBool,
    /// The last line the input driver's plug logged, so a plug that keeps failing logs once.
    last_plug_line: Mutex<Option<String>>,
    diag_saved: AtomicI32,
    exit: Signal,
    /// Ends the waits of the timer threads.
    stopping: Signal,
    exited: AtomicBool,
    exit_asked: AtomicBool,
    companion_pid: AtomicI32,
    threads: Mutex<Vec<std::thread::JoinHandle<()>>>,
    me: Weak<InputHost>,
}

fn error_text(e: &std::io::Error) -> String {
    format!("{:?}: {e}", e.kind())
}

impl InputHost {
    /// Starts the input process. Err: settings.json stayed locked (the companion says so).
    pub fn start(p: Platform, long_wait: bool) -> Result<Arc<InputHost>, LoadError> {
        let log = p.log.clone();
        log(&format!("input {} starting, elevated {}, pid {}", p.version, p.elevated, p.pid));
        let locked_wait_ms = if long_wait { settings::LONG_LOCKED_WAIT_MS } else { settings::DEFAULT_LOCKED_WAIT_MS };

        let store = SettingsStore::new(p.settings_path.clone(), locked_wait_ms);
        let settings = store.load(&p.machine_name, &*log)?;
        let key_mouse =
            Arc::new(KeyMouseRouter::new(p.send_input.clone(), p.input_driver.clone(), settings.use_input_driver, log.clone()));
        let (pad, pad_error) = (p.pads)();
        let key_files = Arc::new(KeyFiles::new(p.keys_dir.clone(), locked_wait_ms));
        let mut key_error = None;
        let mut key_retrying = false;
        let host_key = match key_files.load_or_create_host_key(&*p.protector, &settings.host_name, &*log) {
            Ok(k) => Some(k),
            Err(KeyError::Io(e)) => {
                // Access refused stays refused; anything else (the file held by another program) is tried again.
                key_retrying = e.kind() != std::io::ErrorKind::PermissionDenied;
                let text = error_text(&e);
                log(&format!("key file pairing off: {text}{}", if key_retrying { "; retrying in the background" } else { "" }));
                key_error = Some(text);
                None
            }
        };

        // The memory the companion maps (kept from a run that crashed while the companion held it: nothing held
        // now).
        p.region.held().reset();

        let core = Arc::new_cyclic(|me| Core {
            log: log.clone(),
            input_log: p.input_log.clone(),
            clock: p.clock.clone(),
            out: EventOutbox::new(),
            engine: OnceLock::new(),
            overlay_wake: p.overlay_wake.clone(),
            status_queued: AtomicBool::new(false),
            status_wake: Signal::new(false),
            probe_factory: p.delivery_probe.clone(),
            probe_gate: Mutex::new(ProbeState::default()),
            diag_on: AtomicBool::new(false),
            moves: RwLock::new(MoveRoute::default()),
            me: me.clone(),
        });
        let diagnostics = settings.diagnostics;
        let usb_direct = settings.usb_direct;
        let host_name = settings.host_name.clone();
        let has_key = host_key.is_some();
        let engine = HostEngine::new(
            settings,
            pad.clone(),
            key_mouse.clone(),
            p.protector.clone(),
            EngineOptions {
                clock: p.clock.clone(),
                events: core.clone(),
                prompt: core.clone(),
                hooks: core.clone(),
                session_random: None,
                pairing_random: None,
                new_pair_key: None,
                host_key,
                shared: Some(p.region.clone()),
                inline_workers: false,
            },
        );
        let _ = core.engine.set(engine.clone());

        let mut recorder = InputRecorder::new();
        let for_note = Arc::downgrade(&core);
        recorder.second_note = Some(Box::new(move || {
            let core = for_note.upgrade()?;
            // Asked every second either way: it also rolls the probe's second. A Delivery test alone adds
            // nothing to the log.
            let text = core.moves.read().unwrap_or_else(|e| e.into_inner()).probe.as_ref().and_then(|p| p.second_text());
            if core.diag_on.load(Ordering::Relaxed) {
                text
            } else {
                None
            }
        }));
        engine.set_recorder(Some(recorder));
        engine.set_stall_sleep(p.tick_sleep.clone());
        if let Some(pc_pad) = &p.pc_pad {
            engine.set_pc_pad(pc_pad.clone());
        }
        if let Some(listener) = &p.key_listener {
            engine.set_key_mouse_listener(listener.clone());
        }
        core.set_delivery_probe(diagnostics);

        let authorized = if has_key { Some(AuthorizedWatcher::start(key_files.clone(), engine.clone(), &p)) } else { None };
        let server = UdpHostServer::new(
            engine.clone(),
            p.clock.clone(),
            UdpServerOptions {
                bind: p.bind,
                on_rx_thread_start: p.rx_thread_start.clone(),
                log: Some(log.clone()),
                catch_up_wait: p.catch_up_wait.clone(),
            },
        );
        p.usb.set_enabled(usb_direct);
        let mut p = p;
        let watchdog = Watchdog::new(
            engine.clone(),
            p.clock.clone(),
            WatchdogOptions {
                marker: Some(HeldKeysMarker::new(p.held_path.clone())),
                store: Some(store),
                probes: std::mem::take(&mut p.probes),
                log: Some(log.clone()),
                tick_sleep: p.tick_sleep.clone(),
            },
        );

        let host = Arc::new_cyclic(|me| InputHost {
            p,
            core,
            key_mouse,
            engine,
            server,
            watchdog,
            key_files,
            authorized: Mutex::new(authorized),
            pad: Mutex::new(pad),
            pad_error: Mutex::new(pad_error),
            key_error: Mutex::new(key_error),
            key_retrying: AtomicBool::new(key_retrying),
            bind_retrying: AtomicBool::new(false),
            bind_wait_long: AtomicBool::new(false),
            input_driver_installed: AtomicBool::new(false),
            last_plug_line: Mutex::new(None),
            diag_saved: AtomicI32::new(0),
            exit: Signal::new(false),
            stopping: Signal::new(false),
            exited: AtomicBool::new(false),
            exit_asked: AtomicBool::new(false),
            companion_pid: AtomicI32::new(0),
            threads: Mutex::new(Vec::new()),
            me: me.clone(),
        });
        host.spawn("gp-status", Some(Priority::BelowNormal), |h| h.status_loop());
        host.start_server();
        if key_retrying {
            host.spawn("gp-key", Some(Priority::BelowNormal), move |h| h.retry_host_key(&host_name));
        }
        Ok(host)
    }

    pub fn engine(&self) -> &Arc<HostEngine> {
        &self.engine
    }

    /// The UDP server's port (tests bind port 0).
    pub fn udp_port(&self) -> u16 {
        self.server.local_port()
    }

    fn log(&self, line: &str) {
        (self.p.log)(line);
    }

    fn is_exited(&self) -> bool {
        self.exited.load(Ordering::Acquire)
    }

    /// Runs `f` on a thread of its own that holds the host until it returns.
    fn spawn(&self, name: &str, priority: Option<Priority>, f: impl FnOnce(&InputHost) + Send + 'static) {
        let Some(me) = self.me.upgrade() else { return };
        let spawned = std::thread::Builder::new().name(name.to_string()).spawn(move || {
            if let Some(p) = priority {
                sys::set_thread_priority(p);
            }
            f(&me);
        });
        match spawned {
            Ok(t) => self.threads.lock().unwrap_or_else(|e| e.into_inner()).push(t),
            Err(e) => self.log(&format!("input: thread {name} could not start: {:?}", e.kind())),
        }
    }

    // =================================================================================
    // the companion's line
    // =================================================================================

    /// Takes companions on the pipe, one at a time, on its own thread (gp-pipe-accept).
    pub fn serve(&self) {
        self.spawn("gp-pipe-accept", Some(Priority::BelowNormal), |h| h.accept_loop());
    }

    /// Waits until Exit is asked.
    pub fn wait_exit(&self) {
        while !self.exit.wait(60_000) {}
    }

    /// Waits up to `ms` for Exit to be asked; true when it was.
    pub fn wait_exit_for(&self, ms: u64) -> bool {
        self.exit.wait(ms)
    }

    fn accept_loop(&self) {
        let mut relaunches: VecDeque<Instant> = VecDeque::new();
        while !self.is_exited() {
            let stream = match self.p.pipes.accept() {
                Ok(s) => s,
                Err(e) => {
                    if self.is_exited() {
                        return;
                    }
                    self.log(&format!("input: pipe failed: {}", error_text(&e)));
                    self.stopping.wait(1000);
                    continue;
                }
            };
            if self.is_exited() {
                stream.close();
                return;
            }
            let Some(me) = self.me.upgrade() else { return };
            let on_call = Box::new(move |method: &str, args: &[Value]| control::dispatch(&*me, method, args));
            let channel = match Channel::serve(stream, on_call, self.p.log.clone(), "gp-pipe") {
                Ok(c) => c,
                Err(e) => {
                    self.log(&format!("input: pipe failed: {}", error_text(&e)));
                    self.stopping.wait(1000);
                    continue;
                }
            };
            self.core.out.set_channel(Some(channel.clone()));
            while !channel.wait_closed(60_000) {}
            self.core.out.clear_channel(&channel);
            channel.close_and_join();
            let pid = self.companion_pid.swap(0, Ordering::AcqRel);
            if self.exit_asked.load(Ordering::Acquire) || self.is_exited() {
                return;
            }
            // Quick at first; once a minute when it keeps crashing, so a crash loop doesn't load the PC during play.
            let now = Instant::now();
            while relaunches.front().is_some_and(|t| now.duration_since(*t) > RELAUNCH_WINDOW) {
                relaunches.pop_front();
            }
            relaunches.push_back(now);
            let delay = if relaunches.len() > 5 { SLOW_RELAUNCH_DELAY_MS } else { RELAUNCH_DELAY_MS };
            self.log(&format!(
                "input: the companion (pid {pid}) went away without Exit; starting it again in {} s",
                delay / 1000
            ));
            let weak = self.me.clone();
            let still_wanted = Box::new(move || {
                weak.upgrade().is_some_and(|h| !h.exit_asked.load(Ordering::Acquire) && !h.is_exited() && !h.core.out.attached())
            });
            (self.p.relaunch_companion)(pid, delay, still_wanted);
        }
    }

    // =================================================================================
    // status for the companion (gp-status: coalesced, and once a second)
    // =================================================================================

    pub fn state_changed(&self) {
        self.core.state_changed();
    }

    fn status_loop(&self) {
        while !self.is_exited() {
            self.core.status_wake.wait(1000);
            self.core.status_wake.reset();
            self.core.status_queued.store(false, Ordering::Release);
            if self.is_exited() {
                return;
            }
            if !self.core.out.attached() {
                continue;
            }
            self.core.out.post("status", Payload::Json(control::status_json(&self.status())));
            // At most 10 a second: a burst of changes is one status (each takes the engine lock for a moment).
            self.stopping.wait(100);
        }
    }

    pub fn status(&self) -> InputStatus {
        let snapshot = self.engine.snapshot();
        let authorized = self.authorized.lock().unwrap_or_else(|e| e.into_inner()).as_ref().map(|a| a.last_scan());
        InputStatus {
            snapshot,
            settings: InputSettingsView::of(&self.engine.settings()),
            server: match self.server.state() {
                ServerState::Running => "Running",
                ServerState::PortInUse => "PortInUse",
                ServerState::Failed => "Failed",
                ServerState::Stopped => "Stopped",
            },
            bind_retrying: self.bind_retrying.load(Ordering::Relaxed),
            bind_wait_long: self.bind_wait_long.load(Ordering::Relaxed),
            key_error: self.key_error.lock().unwrap_or_else(|e| e.into_inner()).clone(),
            key_retrying: self.key_retrying.load(Ordering::Relaxed),
            pad_error: self.pad_error.lock().unwrap_or_else(|e| e.into_inner()).clone(),
            input_driver_ready: self.key_mouse.driver_ready() && self.key_mouse.use_input_driver(),
            input_driver_installed: self.input_driver_installed.load(Ordering::Relaxed),
            usb_enabled: self.p.usb.enabled(),
            usb_state: self.p.usb.state(),
            usb_detail: self.p.usb.detail(),
            authorized_scan: authorized,
            send_input_short_writes: self.p.send_input.short_writes(),
            rx_exceptions: self.server.rx_errors(),
            buttons: self.engine.press_latency_now(),
            elevated: self.p.elevated,
            keys_folder: self.key_files.directory().to_string_lossy().into_owned(),
            log_error: (self.p.log_error)(),
            diagnostics_saved: self.diag_saved.load(Ordering::Relaxed),
        }
    }

    // =================================================================================
    // start-up: the port, the key
    // =================================================================================

    fn retry_ms(&self) -> u64 {
        if self.p.retry_ms == 0 {
            RETRY_MS
        } else {
            self.p.retry_ms
        }
    }

    fn port(&self) -> u16 {
        self.p.bind.map(|a| a.port()).unwrap_or(wire::PORT)
    }

    fn start_server(&self) {
        if (self.p.yield_port)() {
            self.log(&format!("watcher running: asked it to let go of port {}", self.port()));
        }
        if self.server.start(true) {
            self.server_started();
            return;
        }
        if self.server.state() != ServerState::PortInUse {
            self.log("UDP server failed to start");
            return;
        }
        self.log(&format!("port {} in use: retrying", self.port()));
        self.bind_retrying.store(true, Ordering::Relaxed);
        self.spawn("gp-bind", None, |h| h.bind_loop());
    }

    fn bind_loop(&self) {
        let started = Instant::now();
        while !self.is_exited() {
            let fast = started.elapsed() < FAST_BIND;
            if self.stopping.wait(if fast { 25 } else { 1000 }) || self.is_exited() {
                return;
            }
            (self.p.yield_port)();
            if self.server.start(false) {
                self.log(&format!("port {} free after {} ms", self.port(), started.elapsed().as_millis()));
                self.server_started();
                return;
            }
            if self.server.state() != ServerState::PortInUse {
                self.log("UDP server failed to start");
                self.bind_retrying.store(false, Ordering::Relaxed);
                self.state_changed();
                return;
            }
            if !fast && !self.bind_wait_long.swap(true, Ordering::Relaxed) {
                self.log(&format!("port {} still in use by another program: retrying every second", self.port()));
                self.state_changed();
            }
        }
    }

    /// The port is ours: the rest of the start-up (owning the port is what makes this the one that runs).
    fn server_started(&self) {
        self.bind_retrying.store(false, Ordering::Relaxed);
        if self.is_exited() {
            self.server.stop();
            return;
        }
        // Keys a crashed run left held went through SendInput (a crashed run's driver devices are gone, and
        // Windows let go of what they held), so they are let go through SendInput.
        let recovered = HeldKeysMarker::recover(&self.p.held_path, &*self.p.send_input);
        if recovered > 0 {
            self.log(&format!("released {recovered} inputs left held by a previous run"));
        }
        self.check_input_driver();
        self.engine.start(self.p.clock.now_us());
        self.watchdog.start();
        self.p.usb.start();
        self.log(&format!("listening on UDP {} as '{}'", self.server.local_port(), self.engine.settings().host_name));
        // The input driver: looked for every 5 s (installed later, or back after it went away).
        self.spawn("gp-driver", Some(Priority::BelowNormal), |h| {
            while !h.stopping.wait(h.retry_ms()) && !h.is_exited() {
                h.check_input_driver();
            }
        });
        self.state_changed();
    }

    /// Notes whether the input driver is installed (Performance shows "Use input driver" only then) and, while
    /// that switch is on, plugs its mouse and keyboard when they aren't plugged (off the input path).
    fn check_input_driver(&self) {
        if self.is_exited() || self.key_mouse.driver_ready() {
            return;
        }
        let installed = self.p.input_driver.installed();
        if installed != self.input_driver_installed.swap(installed, Ordering::Relaxed) {
            self.state_changed();
        }
        if self.server.state() != ServerState::Running || !installed || !self.key_mouse.use_input_driver() {
            return;
        }
        // Plugged here, off the engine lock; the route changes (letting go of everything held first) only once it
        // did, so a driver that won't plug costs input nothing. The same failure is logged once, not every 5 s.
        let log_once = |line: &str| {
            let mut last = self.last_plug_line.lock().unwrap_or_else(|e| e.into_inner());
            if last.as_deref() != Some(line) {
                *last = Some(line.to_string());
                self.log(line);
            }
        };
        let Some(sink) = self.p.input_driver.create(&log_once) else { return };
        *self.last_plug_line.lock().unwrap_or_else(|e| e.into_inner()) = None;
        let mut ok = false;
        self.engine.change_key_mouse_route(|| ok = self.key_mouse.adopt(sink), "input driver plugged");
        if ok {
            self.state_changed();
        }
    }

    fn retry_host_key(&self, host_name: &str) {
        while !self.stopping.wait(self.retry_ms()) && !self.is_exited() {
            match self.key_files.load_or_create_host_key(&*self.p.protector, host_name, &*self.p.log) {
                Ok(key) => {
                    if self.is_exited() || !self.engine.set_host_key(key) {
                        return;
                    }
                    {
                        let mut a = self.authorized.lock().unwrap_or_else(|e| e.into_inner());
                        if a.is_none() && !self.is_exited() {
                            *a = Some(AuthorizedWatcher::start(self.key_files.clone(), self.engine.clone(), &self.p));
                        }
                    }
                    *self.key_error.lock().unwrap_or_else(|e| e.into_inner()) = None;
                    self.key_retrying.store(false, Ordering::Relaxed);
                    self.log("host key read: key file pairing on");
                    self.state_changed();
                    return;
                }
                Err(KeyError::Io(e)) => {
                    let text = error_text(&e);
                    let refused = e.kind() == std::io::ErrorKind::PermissionDenied;
                    *self.key_error.lock().unwrap_or_else(|e| e.into_inner()) = Some(text.clone());
                    if refused {
                        self.key_retrying.store(false, Ordering::Relaxed);
                        self.log(&format!("key file pairing off: {text}"));
                        self.state_changed();
                        return;
                    }
                }
            }
        }
    }

    // =================================================================================
    // exit
    // =================================================================================

    /// Lets go of every key and button the engine holds, straight through SendInput and without the engine lock:
    /// for a crash (the panic hook), where nothing else can be trusted any more.
    pub fn emergency_release(&self) -> usize {
        let mut keys = [0u8; KEY_BITMAP_SIZE];
        let (mut mouse, _) = self.engine.held_snapshot(&mut keys);
        let mut events = [InjectEvent::default(); MAX_RELEASE_EVENTS];
        let n = input_diff::release_all(&mut keys, &mut mouse, &mut events);
        if n > 0 {
            self.p.send_input.inject(&events[..n]);
        }
        n
    }

    /// Makes a crash (a panic on any thread) let go of everything held before the process dies, and say why in
    /// the log. The release goes first and takes no lock; the log line is given half a second, on a thread of its
    /// own, in case the crash happened inside the log itself. The companion then starts the process again.
    pub fn install_panic_hook(host: &Arc<InputHost>) {
        let weak = Arc::downgrade(host);
        let before = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            if let Some(h) = weak.upgrade() {
                let n = h.emergency_release();
                let line = format!("input: crashed: {info}; let go of {n} held inputs");
                let (done_tx, done) = std::sync::mpsc::channel();
                let log = h.p.log.clone();
                let spawned = std::thread::Builder::new().name("gp-crash-log".to_string()).spawn(move || {
                    log(&line);
                    let _ = done_tx.send(());
                });
                if spawned.is_ok() {
                    let _ = done.recv_timeout(Duration::from_millis(500));
                }
            }
            before(info);
        }));
    }

    /// Release-all, HOST_BYE(EXIT), unplug, save, clear the held-keys marker. Runs once.
    pub fn shutdown(&self) {
        if self.exited.swap(true, Ordering::AcqRel) {
            return;
        }
        self.stopping.set();
        self.exit.set();
        self.p.pipes.cancel();
        let authorized = self.authorized.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(a) = authorized {
            a.stop();
        }
        self.watchdog.stop();
        self.engine.shutdown();
        self.server.stop();
        self.p.usb.stop();
        // What is left to write: the settings, and the held-keys marker (now empty).
        self.watchdog.save_settings();
        self.watchdog.write_files();
        self.core.set_delivery_probe(false);
        {
            // No Delivery test either: the probe stops.
            let mut st = self.core.probe_gate.lock().unwrap_or_else(|e| e.into_inner());
            st.test_on = false;
            self.core.update_probe(&mut st);
        }
        self.key_mouse.close();
        self.core.status_wake.set();
        self.core.out.flush();
        if let Some(c) = self.core.out.channel() {
            c.close();
        }
        self.core.out.stop();
        self.log("input: stopped");
        // The threads end on their own now; the one calling this may be one of them.
        let threads = std::mem::take(&mut *self.threads.lock().unwrap_or_else(|e| e.into_inner()));
        let me = std::thread::current().id();
        for t in threads {
            if t.thread().id() != me {
                let _ = t.join();
            }
        }
    }
}

// =====================================================================================
// the companion's calls (pipe thread)
// =====================================================================================

impl InputControl for InputHost {
    fn attach(&self, companion_pid: i32) -> InputHello {
        self.companion_pid.store(companion_pid, Ordering::Release);
        self.log(&format!("input: companion attached (pid {companion_pid})"));
        self.core.set_delivery_test(false, false); // a test window went with the companion before
        self.state_changed();
        InputHello {
            version: self.p.version.clone(),
            pid: self.p.pid,
            shared_name: self.p.region_name.clone(),
            layout_version: LAYOUT_VERSION,
            overlay_wake_name: self.p.overlay_wake_name.clone(),
            status: self.status(),
        }
    }

    fn snapshot(&self) -> HostSnapshot {
        self.engine.snapshot()
    }

    fn exit(&self) {
        self.exit_asked.store(true, Ordering::Release);
        self.log("input: exit asked");
        self.exit.set();
    }

    fn set_output_mode(&self, mode: u8) {
        self.engine.set_output_mode(mode);
    }

    fn set_paused(&self, paused: bool) {
        self.engine.set_paused(paused);
    }

    fn set_locked(&self, locked: bool) {
        self.engine.set_locked(locked);
    }

    fn retry_driver(&self) -> String {
        let (pad, error) = (self.p.pads)();
        if let Some(e) = &error {
            if !pad.dual_sense_ok() {
                *self.pad_error.lock().unwrap_or_else(|e| e.into_inner()) = error.clone();
                drop(pad);
                self.state_changed();
                return e.clone();
            }
        }
        // The engine unplugs and drops the old sink on its pad thread.
        self.engine.set_pad_sink(pad.clone());
        *self.pad.lock().unwrap_or_else(|e| e.into_inner()) = pad;
        *self.pad_error.lock().unwrap_or_else(|e| e.into_inner()) = error;
        self.log("gamepad driver found");
        self.state_changed();
        String::new()
    }

    fn set_catch_up_ms(&self, ms: i32) {
        self.engine.set_catch_up_ms(ms);
    }

    fn set_use_input_driver(&self, on: bool) {
        self.engine.set_use_input_driver(on, |on| self.key_mouse.set_use_input_driver(on));
    }

    fn open_pairing_window(&self) {
        self.engine.open_pairing_window();
    }

    fn answer_pair(&self, attempt_id: u32, allow: bool) {
        self.engine.answer_pair(attempt_id, allow);
    }

    fn open_qr_pairing(&self) -> Option<Vec<u8>> {
        let files = self.key_files.clone();
        let save: EnrollSave = Arc::new(move |key| files.add_authorized(key).map(Some).map_err(|e| error_text(&e)));
        self.engine.open_qr_pairing(save).map(|t| t.to_vec())
    }

    fn close_qr_pairing(&self) {
        self.engine.close_qr_pairing();
    }

    fn host_public_key(&self) -> Option<Vec<u8>> {
        self.engine.host_public_key().map(|k| k.to_vec())
    }

    fn forget(&self, phone: &PairedPhoneInfo) {
        if !phone.by_key {
            self.engine.forget(&phone.phone_id);
            return;
        }
        // Only a file right in authorized_phones is ever deleted, whatever path the call names.
        if let Some(f) = &phone.key_file {
            let file = std::path::absolute(f).ok();
            let dir = std::path::absolute(self.key_files.authorized_directory()).ok();
            let same = |a: &Path, b: &Path| {
                let norm = |p: &Path| p.to_string_lossy().trim_end_matches(['\\', '/']).to_lowercase();
                norm(a) == norm(b)
            };
            if let (Some(file), Some(dir)) = (file, dir) {
                if file.parent().is_some_and(|parent| same(parent, &dir)) {
                    if let Err(e) = std::fs::remove_file(&file) {
                        self.log(&format!("could not delete key file: {:?}", e.kind()));
                    }
                }
            }
        }
        let a = self.authorized.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(a) = a.as_ref() {
            a.reload();
        }
    }

    fn set_usb_direct(&self, on: bool) {
        self.p.usb.set_enabled(on);
        self.engine.set_usb_direct(on);
        self.log(&format!("usb direct {}", if on { "on" } else { "off" }));
        self.state_changed();
    }

    fn take_usb_from_adb(&self) {
        self.p.usb.take_from_adb();
    }

    fn set_start_with_windows(&self, on: bool) {
        self.engine.set_start_with_windows(on);
    }

    fn set_diagnostics(&self, on: bool) {
        self.engine.set_diagnostics(on);
    }

    fn set_diagnostics_minutes(&self, minutes: i32) {
        self.engine.set_diagnostics_minutes(minutes);
    }

    fn save_diagnostics(&self) {
        self.diag_saved.fetch_add(1, Ordering::Relaxed);
        self.engine.save_diagnostics();
    }

    fn set_press_key_listen(&self, on: bool) {
        self.engine.set_press_key_listen(on);
    }

    fn set_press_key_notice(&self, on: bool) {
        self.engine.set_press_key_notice(on);
    }

    fn set_measure_buttons(&self, on: bool) {
        self.engine.set_measure_buttons(on);
    }

    fn set_link_watch(&self, on: bool) {
        self.engine.set_link_watch(on);
    }

    fn set_delivery_test(&self, on: bool, counting: bool) {
        self.core.set_delivery_test(on, counting);
    }

    fn delivery_test_numbers(&self) -> Option<DeliveryNumbers> {
        self.core.delivery_test_numbers()
    }

    fn set_overlay(&self, on: bool, compass: bool, samples: bool, keys: bool) {
        self.engine.set_overlay(on, compass, samples, keys);
    }
}
