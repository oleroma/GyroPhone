//! The input process put together, with everything Windows faked: it starts, takes a companion on its line,
//! pairs and plays with a phone over a real loopback socket, keeps going when the companion crashes, and stops
//! cleanly. What InputHost.cs does, as the companion and the phone see it.

mod common;

use std::net::{SocketAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use common::companion::{Companion, LoopbackPipes};
use common::harness::{finishes, FakeKeyMouse, FakePad, PlainProtector};
use common::phone::FakePhone;
use common::range_n;
use gp_input::codec;
use gp_input::control::DeliveryNumbers;
use gp_input::crypto::key_pairing::PubKeyFile;
use gp_input::crypto::{base64_decode, P256};
use gp_input::held_marker;
use gp_input::host::{DeliveryProbe, DriverSink, InputDriver, InputHost, Platform, SendInput, UsbLink, SLOW_RELAUNCH_DELAY_MS};
use gp_input::mapping::InjectEvent;
use gp_input::ports::{KeyMouseSink, MonotonicClock, PadSink};
use gp_input::settings::{HostSettings, SettingsStore};
use gp_input::shared::SharedRegion;
use gp_input::state_frame::KEY_BITMAP_SIZE;
use gp_input::watchdog::Probes;
use gp_input::wire::{pair_ack, pair_done, packet};
use serde_json::{json, Value};

const USAGE_W: u32 = 0x1A;
const SCAN_W: u16 = 0x11;

fn wait_for(ms: u64, cond: impl Fn() -> bool) -> bool {
    let start = Instant::now();
    while start.elapsed() < Duration::from_millis(ms) {
        if cond() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    cond()
}

/// A folder of its own under the system's temp folder, removed at the end.
struct Dir(PathBuf);

impl Dir {
    fn new() -> Dir {
        static N: AtomicU32 = AtomicU32::new(0);
        let d = std::env::temp_dir().join(format!("gp-host-{}-{}", std::process::id(), N.fetch_add(1, Ordering::SeqCst)));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        Dir(d)
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

// =====================================================================================
// Windows, faked
// =====================================================================================

#[derive(Default)]
struct FakeSendInput(FakeKeyMouse);

impl KeyMouseSink for FakeSendInput {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        self.0.inject(events)
    }
}

impl SendInput for FakeSendInput {
    fn short_writes(&self) -> i64 {
        7
    }
}

struct FakeDriverSink {
    km: Arc<FakeKeyMouse>,
    plugged: AtomicBool,
    alive: Arc<AtomicI64>,
}

impl KeyMouseSink for FakeDriverSink {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        self.km.inject(events)
    }
    fn route(&self) -> &'static str {
        "input driver"
    }
}

impl DriverSink for FakeDriverSink {
    fn plugged(&self) -> bool {
        self.plugged.load(Ordering::SeqCst)
    }
}

impl Drop for FakeDriverSink {
    fn drop(&mut self) {
        self.alive.fetch_sub(1, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct FakeDriver {
    installed: AtomicBool,
    plugs: AtomicBool,
    creates: AtomicI64,
    /// Sinks made and not yet dropped (plugged devices in Windows).
    alive: Arc<AtomicI64>,
    km: Arc<FakeKeyMouse>,
}

impl InputDriver for FakeDriver {
    fn installed(&self) -> bool {
        self.installed.load(Ordering::SeqCst)
    }
    fn create(&self, log: &dyn Fn(&str)) -> Option<Arc<dyn DriverSink>> {
        self.creates.fetch_add(1, Ordering::SeqCst);
        if !self.plugs.load(Ordering::SeqCst) {
            log("input driver: plug failed");
            return None;
        }
        self.alive.fetch_add(1, Ordering::SeqCst);
        Some(Arc::new(FakeDriverSink { km: self.km.clone(), plugged: AtomicBool::new(true), alive: self.alive.clone() }))
    }
}

#[derive(Default)]
struct FakeUsb {
    enabled: AtomicBool,
    started: AtomicBool,
    stopped: AtomicBool,
    takes: AtomicI64,
}

impl UsbLink for FakeUsb {
    fn start(&self) {
        self.started.store(true, Ordering::SeqCst);
    }
    fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
    }
    fn enabled(&self) -> bool {
        self.enabled.load(Ordering::SeqCst)
    }
    fn set_enabled(&self, on: bool) {
        self.enabled.store(on, Ordering::SeqCst);
    }
    fn state(&self) -> String {
        if self.enabled() { "NoPhone" } else { "Off" }.to_string()
    }
    fn detail(&self) -> String {
        "no phone on the cable".to_string()
    }
    fn take_from_adb(&self) {
        self.takes.fetch_add(1, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct ProbeState {
    running: bool,
    starts: u32,
    stops: u32,
    sent: Vec<(i64, i32, i32)>,
    slow: Option<Arc<dyn Fn(&str) + Send + Sync>>,
    test: Option<bool>,
    second: Option<String>,
}

#[derive(Default)]
struct FakeProbe(Mutex<ProbeState>);

impl FakeProbe {
    fn running_and_test(&self) -> (bool, Option<bool>) {
        let s = self.0.lock().unwrap();
        (s.running, s.test)
    }
}

impl DeliveryProbe for FakeProbe {
    fn start(&self) {
        let mut s = self.0.lock().unwrap();
        s.running = true;
        s.starts += 1;
    }
    fn stop(&self) {
        let mut s = self.0.lock().unwrap();
        s.running = false;
        s.stops += 1;
    }
    fn sent(&self, ticks: i64, dx: i32, dy: i32) {
        self.0.lock().unwrap().sent.push((ticks, dx, dy));
    }
    fn second_text(&self) -> Option<String> {
        self.0.lock().unwrap().second.take()
    }
    fn set_slow_line(&self, to: Option<Arc<dyn Fn(&str) + Send + Sync>>) {
        self.0.lock().unwrap().slow = to;
    }
    fn start_test(&self, counting: bool) {
        self.0.lock().unwrap().test = Some(counting);
    }
    fn count_test(&self, counting: bool) {
        self.0.lock().unwrap().test = Some(counting);
    }
    fn stop_test(&self) {
        self.0.lock().unwrap().test = None;
    }
    fn test_numbers(&self) -> Option<DeliveryNumbers> {
        self.0.lock().unwrap().test.map(|_| DeliveryNumbers { n: 10, p50: 0.5, p99: 1.5, max: 2.5, slow: 1, unmatched: 0, merged: 0, stray: 0 })
    }
}

type Lines = Arc<Mutex<Vec<String>>>;

/// What a test sets before the start, and reads after.
struct Rig {
    dir: Dir,
    send_input: Arc<FakeSendInput>,
    pad: Arc<FakePad>,
    pads_error: Arc<Mutex<Option<String>>>,
    pads_made: Arc<AtomicI64>,
    driver: Arc<FakeDriver>,
    usb: Arc<FakeUsb>,
    probe: Arc<FakeProbe>,
    probes_made: Arc<AtomicI64>,
    pipes: Arc<LoopbackPipes>,
    logs: Lines,
    log_error: Arc<Mutex<Option<String>>>,
    relaunches: Arc<Mutex<Vec<(i32, u64, Box<dyn Fn() -> bool + Send>)>>>,
    yields: Arc<AtomicI64>,
    wakes: Arc<AtomicI64>,
    folder_changed: Arc<Mutex<Option<Arc<dyn Fn() + Send + Sync>>>>,
    region: SharedRegion,
    bind: SocketAddr,
    retry_ms: u64,
}

impl Rig {
    fn new() -> Rig {
        Rig {
            dir: Dir::new(),
            send_input: Arc::default(),
            pad: Arc::new(FakePad::new()),
            pads_error: Arc::default(),
            pads_made: Arc::default(),
            driver: Arc::default(),
            usb: Arc::default(),
            probe: Arc::default(),
            probes_made: Arc::default(),
            pipes: Arc::new(LoopbackPipes::new()),
            logs: Arc::default(),
            log_error: Arc::default(),
            relaunches: Arc::default(),
            yields: Arc::default(),
            wakes: Arc::default(),
            folder_changed: Arc::default(),
            region: SharedRegion::in_process(),
            bind: SocketAddr::from(([127, 0, 0, 1], 0)),
            retry_ms: 50,
        }
    }

    fn settings_path(&self) -> PathBuf {
        self.dir.0.join("settings.json")
    }
    fn held_path(&self) -> PathBuf {
        self.dir.0.join("held.json")
    }
    fn keys_dir(&self) -> PathBuf {
        self.dir.0.join("keys")
    }

    /// settings.json as a run before left it.
    fn write_settings(&self, change: impl FnOnce(&mut HostSettings)) {
        let mut s = HostSettings::create_default("TEST-PC");
        change(&mut s);
        SettingsStore::new(self.settings_path(), 100).save(&s).unwrap();
    }

    fn saved_settings(&self) -> Value {
        serde_json::from_slice(&std::fs::read(self.settings_path()).unwrap()).unwrap()
    }

    fn platform(&self) -> Platform {
        let logs = self.logs.clone();
        let log_error = self.log_error.clone();
        let (pad, pads_error, pads_made) = (self.pad.clone(), self.pads_error.clone(), self.pads_made.clone());
        let wakes = self.wakes.clone();
        let (probe, probes_made) = (self.probe.clone(), self.probes_made.clone());
        let folder_changed = self.folder_changed.clone();
        let yields = self.yields.clone();
        let relaunches = self.relaunches.clone();
        Platform {
            version: "0.1.900-test".to_string(),
            pid: 4242,
            elevated: false,
            machine_name: "TEST-PC".to_string(),
            log: Arc::new(move |s: &str| logs.lock().unwrap().push(s.to_string())),
            log_error: Arc::new(move || log_error.lock().unwrap().clone()),
            input_log: Arc::new(|_| {}),
            local_time: Arc::new(|| "12:34:56".to_string()),
            clock: Arc::new(MonotonicClock::new()),
            settings_path: self.settings_path(),
            held_path: self.held_path(),
            keys_dir: self.keys_dir(),
            send_input: self.send_input.clone(),
            input_driver: self.driver.clone(),
            pads: Arc::new(move || {
                pads_made.fetch_add(1, Ordering::SeqCst);
                let p: Arc<dyn PadSink> = pad.clone();
                (p, pads_error.lock().unwrap().clone())
            }),
            protector: Arc::new(PlainProtector),
            region: self.region.clone(),
            region_name: r"Local\GyroPhoneShared".to_string(),
            overlay_wake: Arc::new(move || {
                wakes.fetch_add(1, Ordering::SeqCst);
            }),
            overlay_wake_name: r"Local\GyroPhoneOverlayWake".to_string(),
            pc_pad: None,
            key_listener: None,
            delivery_probe: Arc::new(move || {
                probes_made.fetch_add(1, Ordering::SeqCst);
                let p: Arc<dyn DeliveryProbe> = probe.clone();
                Some(p)
            }),
            watch_folder: Arc::new(move |_dir: &Path, changed| {
                *folder_changed.lock().unwrap() = Some(changed);
                let guard: Box<dyn Send + Sync> = Box::new(());
                Some(guard)
            }),
            rx_thread_start: None,
            catch_up_wait: None,
            tick_sleep: None,
            probes: Probes::default(),
            usb: self.usb.clone(),
            yield_port: Arc::new(move || {
                yields.fetch_add(1, Ordering::SeqCst);
                false
            }),
            pipes: self.pipes.clone(),
            relaunch_companion: Arc::new(move |pid, delay, still| relaunches.lock().unwrap().push((pid, delay, still))),
            bind: Some(self.bind),
            retry_ms: self.retry_ms,
        }
    }

    fn start(&self) -> Running<'_> {
        let host = InputHost::start(self.platform(), false).expect("the input process starts");
        host.serve();
        Running { rig: self, host }
    }

    fn logged(&self, starts_with: &str) -> usize {
        self.logs.lock().unwrap().iter().filter(|l| l.starts_with(starts_with)).count()
    }

    fn wait_logged(&self, starts_with: &str) {
        assert!(wait_for(5000, || self.logged(starts_with) > 0), "never logged '{starts_with}': {:?}", self.logs.lock().unwrap());
    }
}

/// The input process, shut down at the end of the test whatever happens.
struct Running<'a> {
    rig: &'a Rig,
    host: Arc<InputHost>,
}

impl Drop for Running<'_> {
    fn drop(&mut self) {
        self.host.shutdown();
    }
}

impl Running<'_> {
    /// A companion on the line, attached.
    fn companion(&self, pid: i32) -> (Companion, Value) {
        let c = Companion::connect(self.rig.pipes.port());
        let hello = c.call("Attach", json!([pid]));
        (c, hello)
    }

    fn status(&self, c: &Companion, want: impl Fn(&Value) -> bool) -> Value {
        c.wait_event("status", 5000, want).unwrap_or_else(|| panic!("no such status; the last: {:?}", c.events("status").last()))
    }
}

/// A phone on a real socket.
struct Phone {
    sock: UdpSocket,
    host: SocketAddr,
    fake: FakePhone,
}

impl Phone {
    fn new(port: u16) -> Phone {
        let sock = UdpSocket::bind(("127.0.0.1", 0)).unwrap();
        sock.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        Phone { sock, host: SocketAddr::from(([127, 0, 0, 1], port)), fake: FakePhone::new(range_n(0x00), "OnePlus 15", "127.0.0.1:1") }
    }

    fn tx(&self, d: &[u8]) {
        self.sock.send_to(d, self.host).unwrap();
    }

    fn rx(&self, accept: impl Fn(&[u8]) -> bool, timeout_ms: u64) -> Option<Vec<u8>> {
        let mut buf = [0u8; 2048];
        let start = Instant::now();
        while start.elapsed() < Duration::from_millis(timeout_ms) {
            if let Ok((n, _)) = self.sock.recv_from(&mut buf) {
                if accept(&buf[..n]) {
                    return Some(buf[..n].to_vec());
                }
            }
        }
        None
    }

    /// Sends until a matching reply arrives, like the phone's retransmit loops.
    fn exchange(&self, request: &[u8], accept: impl Fn(&[u8]) -> bool) -> Vec<u8> {
        for _ in 0..40 {
            self.tx(request);
            if let Some(r) = self.rx(&accept, 250) {
                return r;
            }
        }
        panic!("no reply");
    }

    /// Pairs by code, with the companion's user allowing it, then starts a session.
    fn pair_and_connect(&mut self, r: &Running, c: &Companion) {
        c.call("OpenPairingWindow", json!([]));
        let ann = self.exchange(&self.fake.discover(1), |d| d[3] == packet::ANNOUNCE);
        assert_eq!("TEST-PC", codec::decode_announce(&ann).unwrap().name);
        let start = self.fake.pair_start();
        let pair_host = self.exchange(&start, |d| d[3] == packet::PAIR_HOST);
        assert!(self.fake.on_pair_host(&pair_host));
        let ack = self.exchange(&self.fake.pair_reveal(None), |d| d[3] == packet::PAIR_ACK);
        assert_eq!(pair_ack::OK, ack[16]);
        // The companion's pair window shows the code and the user allows it.
        let shown = c.wait_event("pair-show", 5000, |_| true).expect("the pairing is shown");
        assert_eq!(json!("OnePlus 15"), shown["phoneName"]);
        assert_eq!(7, shown["sas"].as_str().unwrap().len(), "{shown}");
        let prompt = c.call("Snapshot", json!([]))["pairPrompt"].clone();
        assert_eq!((shown["attemptId"].clone(), shown["sas"].clone()), (prompt["item1"].clone(), prompt["item3"].clone()));
        c.call("AnswerPair", json!([shown["attemptId"], true]));
        let done = self.exchange(&self.fake.pair_confirm(false), |d| d[3] == packet::PAIR_DONE && d[16] != pair_done::PENDING);
        assert!(self.fake.on_pair_done(&done).0);
        assert_eq!(Some(shown["attemptId"].clone()), c.wait_event("pair-close", 5000, |_| true));

        let hello = self.fake.hello(None);
        let welcome = self.exchange(&hello, |d| d[3] == packet::WELCOME);
        assert!(self.fake.on_welcome(&welcome));
        self.state(|_| {});
        let s = r.status(c, |s| s["snapshot"]["connected"] == json!(true));
        assert_eq!(json!("OnePlus 15"), s["snapshot"]["phoneName"]);
    }

    /// Keeps sending the state as it is every 20 ms, like the phone's keepalive (a silent phone's keys are let
    /// go), until `done` or five seconds passed; true when done.
    fn keep_until(&mut self, done: impl Fn() -> bool) -> bool {
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(5) {
            if done() {
                return true;
            }
            self.state(|_| {});
            std::thread::sleep(Duration::from_millis(20));
        }
        done()
    }

    fn state(&mut self, change: impl FnOnce(&mut gp_input::state_frame::StateFrame)) {
        let d = self.fake.state(change);
        self.tx(&d);
    }
}

// =====================================================================================
// start, attach, status
// =====================================================================================

#[test]
fn it_starts_and_tells_an_attaching_companion_what_it_is() {
    let rig = Rig::new();
    let r = rig.start();
    assert!(r.host.udp_port() != 0);
    let (c, hello) = r.companion(1111);
    assert_eq!(
        (json!("0.1.900-test"), json!(4242), json!(r"Local\GyroPhoneShared"), json!(2), json!(r"Local\GyroPhoneOverlayWake")),
        (hello["version"].clone(), hello["pid"].clone(), hello["sharedName"].clone(), hello["layoutVersion"].clone(), hello["overlayWakeName"].clone())
    );
    let s = &hello["status"];
    assert_eq!(json!("Running"), s["server"]);
    assert_eq!((json!(false), json!(false)), (s["bindRetrying"].clone(), s["bindWaitLong"].clone()));
    assert_eq!(json!("TEST-PC"), s["settings"]["hostName"]);
    assert_eq!(json!(1), s["settings"]["outputMode"]);
    assert_eq!(json!(false), s["snapshot"]["connected"]);
    assert_eq!(json!(7), s["sendInputShortWrites"]);
    assert_eq!(json!(false), s["elevated"]);
    assert_eq!(json!(rig.keys_dir().to_string_lossy()), s["keysFolder"]);
    assert_eq!((json!("Off"), json!(false)), (s["usbState"].clone(), s["usbEnabled"].clone()));
    assert_eq!((Value::Null, Value::Null, Value::Null), (s["keyError"].clone(), s["padError"].clone(), s["logError"].clone()));
    assert_eq!(json!("0 of 0 .pub file(s) valid, 12:34:56"), s["authorizedScan"]);
    assert!(s["snapshot"]["hostKeyFingerprint"].is_string());
    // The start-up, as the log tells it.
    assert_eq!(1, rig.logged("input 0.1.900-test starting, elevated false, pid 4242"));
    assert_eq!(1, rig.logged(&format!("listening on UDP {} as 'TEST-PC'", r.host.udp_port())));
    assert_eq!(1, rig.logged("input: companion attached (pid 1111)"));
    assert!(rig.usb.started.load(Ordering::SeqCst));
    assert_eq!(1, rig.yields.load(Ordering::SeqCst), "a running watcher is asked for the port once");
    // New settings and the PC's key were written.
    assert!(rig.settings_path().exists());
    assert!(rig.keys_dir().join("host.key").exists() && rig.keys_dir().join("host.pub").exists());
    drop(c);
}

#[test]
fn a_change_is_pushed_as_a_status_and_one_comes_each_second_anyway() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    c.call("SetOutputMode", json!([2]));
    c.call("SetCatchUpMs", json!([16]));
    c.call("SetStartWithWindows", json!([true]));
    c.call("SetPressKeyListen", json!([false]));
    c.call("SetPressKeyNotice", json!([false]));
    c.call("SetMeasureButtons", json!([true]));
    c.call("SetDiagnosticsMinutes", json!([30]));
    let s = r.status(&c, |s| s["settings"]["diagnosticsMinutes"] == json!(30));
    let v = &s["settings"];
    assert_eq!(
        (json!(2), json!(16), json!(true), json!(false), json!(false), json!(true)),
        (v["outputMode"].clone(), v["catchUpMs"].clone(), v["startWithWindows"].clone(), v["pressKeyListen"].clone(), v["pressKeyNotice"].clone(), v["measureButtons"].clone())
    );
    c.call("SetPaused", json!([true]));
    r.status(&c, |s| s["snapshot"]["paused"] == json!(true));
    // A burst of changes is not a burst of statuses: at most 10 a second.
    c.clear_events();
    for i in 0..50 {
        c.call("SetPaused", json!([i % 2 == 0]));
    }
    assert!(c.events("status").len() <= 3, "{} statuses for one burst", c.events("status").len());
    // Nothing changes: one still comes each second.
    c.clear_events();
    std::thread::sleep(Duration::from_millis(2300));
    let n = c.events("status").len();
    assert!((1..=4).contains(&n), "{n} statuses in 2.3 quiet seconds");
}

#[test]
fn exit_releases_saves_and_stops_everything() {
    let rig = Rig::new();
    let r = rig.start();
    let port = r.host.udp_port();
    let (c, _) = r.companion(77);
    let mut phone = Phone::new(port);
    phone.pair_and_connect(&r, &c);
    phone.state(|f| f.set_key(USAGE_W, true));
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));
    c.call("SetOutputMode", json!([2]));
    assert!(!r.host.wait_exit_for(0));

    assert_eq!(Value::Null, c.call("Exit", json!([])));
    assert!(r.host.wait_exit_for(5000));
    let host = r.host.clone();
    assert!(finishes(15_000, move || host.shutdown()), "shutdown hangs");

    assert!(rig.send_input.0.nothing_held(), "W stays down after exit");
    // The phone is told the PC is leaving.
    assert!(phone.rx(|d| d[3] == packet::HOST_BYE, 2000).is_some() || phone.fake.frame.keys != [0; KEY_BITMAP_SIZE]);
    assert!(c.wait_closed(5000), "the companion's line stays open");
    assert!(!rig.held_path().exists(), "the held-keys marker is left behind");
    let saved = rig.saved_settings();
    assert_eq!(json!("DualShock4"), saved["OutputMode"]);
    assert_eq!(1, saved["PairedPhones"].as_array().unwrap().len(), "the pairing was not saved");
    assert!(rig.usb.stopped.load(Ordering::SeqCst));
    assert_eq!((1, 1), (rig.logged("input: exit asked"), rig.logged("input: stopped")));
    assert!(rig.relaunches.lock().unwrap().is_empty(), "the companion is started again after Exit");
    // The port is free for the next run.
    assert!(UdpSocket::bind(("127.0.0.1", port)).is_ok());
    // Again does nothing.
    r.host.shutdown();
    assert_eq!(1, rig.logged("input: stopped"));
}

#[test]
fn shutdown_without_a_companion_ends_every_thread() {
    let rig = Rig::new();
    let r = rig.start();
    let host = r.host.clone();
    assert!(finishes(15_000, move || host.shutdown()), "shutdown hangs");
    assert!(r.host.wait_exit_for(0));
    // Only the test holds the host now: every thread of its own let go of it.
    assert!(wait_for(5000, || Arc::strong_count(&r.host) == 1), "{} still hold the host", Arc::strong_count(&r.host));
}

// =====================================================================================
// the companion going away
// =====================================================================================

#[test]
fn a_crashed_companion_is_started_again_and_input_goes_on() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(500);
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    c.call("SetDeliveryTest", json!([true, true]));
    c.vanish();
    assert!(wait_for(5000, || rig.relaunches.lock().unwrap().len() == 1));
    {
        let l = rig.relaunches.lock().unwrap();
        assert_eq!((500, 2000), (l[0].0, l[0].1));
        assert!((l[0].2)(), "nothing attached: the companion is still wanted");
    }
    rig.wait_logged("input: the companion (pid 500) went away without Exit; starting it again in 2 s");
    // Input never noticed.
    phone.state(|f| f.set_key(USAGE_W, true));
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));
    assert!(r.host.engine().snapshot().connected);
    // The new companion attaches to the same input process, and finds the phone still connected.
    let (c2, hello) = r.companion(501);
    assert_eq!(json!(true), hello["status"]["snapshot"]["connected"]);
    assert!(!(rig.relaunches.lock().unwrap()[0].2)(), "a companion attached: no second one is started");
    // The old companion's Delivery test went with it.
    assert_eq!(Value::Null, c2.call("DeliveryTestNumbers", json!([])));
    assert!(!rig.probe.0.lock().unwrap().running, "the probe runs on with no test and no Diagnostics");
    r.status(&c2, |_| true);
}

#[test]
fn a_companion_that_keeps_crashing_is_started_only_once_a_minute() {
    let rig = Rig::new();
    let r = rig.start();
    for i in 0..7 {
        let (c, _) = r.companion(100 + i);
        c.vanish();
        assert!(wait_for(5000, || rig.relaunches.lock().unwrap().len() == i as usize + 1), "crash {i}");
    }
    let delays: Vec<u64> = rig.relaunches.lock().unwrap().iter().map(|l| l.1).collect();
    assert_eq!(vec![2000, 2000, 2000, 2000, 2000, SLOW_RELAUNCH_DELAY_MS, SLOW_RELAUNCH_DELAY_MS], delays);
    assert_eq!(vec![100, 101, 102, 103, 104, 105, 106], rig.relaunches.lock().unwrap().iter().map(|l| l.0).collect::<Vec<_>>());
}

#[test]
fn a_pipe_that_fails_is_opened_again() {
    let rig = Rig::new();
    rig.pipes.fail_next.store(true, Ordering::SeqCst);
    let r = rig.start();
    rig.wait_logged("input: pipe failed: ");
    let (_c, hello) = r.companion(9);
    assert_eq!(json!("Running"), hello["status"]["server"]);
}

#[test]
fn garbage_on_the_line_costs_that_companion_only() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(31);
    assert!(c.send_raw(&[0xFF; 64]));
    assert!(c.wait_closed(5000));
    assert!(wait_for(5000, || rig.relaunches.lock().unwrap().len() == 1));
    let (c2, _) = r.companion(32);
    assert_eq!(json!(false), c2.call("Snapshot", json!([]))["connected"]);
    assert_eq!(
        "MissingMethodException: Method 'IInputControl.DeleteEverything' not found.",
        c2.call_err("DeleteEverything", json!([]))
    );
    assert_eq!(json!(false), c2.call("Snapshot", json!([]))["paused"]);
}

// =====================================================================================
// start-up: the port, held keys, the key
// =====================================================================================

#[test]
fn a_port_in_use_is_waited_for_and_nothing_starts_before_it_is_ours() {
    let mut rig = Rig::new();
    let holder = UdpSocket::bind(("127.0.0.1", 0)).unwrap();
    rig.bind = holder.local_addr().unwrap();
    std::fs::write(rig.held_path(), held_text(USAGE_W)).unwrap();
    let r = rig.start();
    let (c, hello) = r.companion(1);
    assert_eq!((json!("PortInUse"), json!(true)), (hello["status"]["server"].clone(), hello["status"]["bindRetrying"].clone()));
    rig.wait_logged(&format!("port {} in use: retrying", rig.bind.port()));
    // Not ours yet: a run that still has the port also still holds its keys, so nothing is let go for it.
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(0, rig.send_input.0.calls());
    assert!(rig.held_path().exists());
    assert!(!rig.usb.started.load(Ordering::SeqCst));
    assert!(rig.yields.load(Ordering::SeqCst) > 2, "the watcher is asked again while waiting");

    drop(holder);
    let s = r.status(&c, |s| s["server"] == json!("Running"));
    assert_eq!(json!(false), s["bindRetrying"]);
    rig.wait_logged(&format!("port {} free after ", rig.bind.port()));
    assert!(wait_for(5000, || rig.usb.started.load(Ordering::SeqCst)));
    assert_eq!(1, rig.logged("released 2 inputs left held by a previous run"));
    // And it plays.
    let mut phone = Phone::new(rig.bind.port());
    phone.pair_and_connect(&r, &c);
}

fn held_text(usage: u32) -> String {
    let mut keys = [0u8; KEY_BITMAP_SIZE];
    keys[(usage / 8) as usize] |= 1 << (usage % 8);
    held_marker::format(&keys, 1)
}

#[test]
fn keys_a_crashed_run_left_held_are_let_go_at_the_start() {
    let rig = Rig::new();
    std::fs::write(rig.held_path(), held_text(USAGE_W)).unwrap();
    let _r = rig.start();
    let ups = rig.send_input.0.all();
    assert_eq!(2, ups.len(), "{ups:?}");
    assert!(ups.iter().any(|e| e.scan == SCAN_W && e.is_key_up()));
    assert_eq!(1, rig.logged("released 2 inputs left held by a previous run"));
    assert!(!rig.held_path().exists());
}

#[test]
fn a_crash_lets_go_of_what_is_held_without_the_engine() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    phone.state(|f| {
        f.set_key(USAGE_W, true);
        f.mouse_buttons = 1;
    });
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W) && rig.send_input.0.holds_button("L")));
    // The marker on disk says the same, for a kill that runs no code at all.
    let on_disk = || std::fs::read(rig.held_path()).ok().and_then(|t| held_marker::parse(&t));
    assert!(phone.keep_until(|| on_disk().is_some_and(|(_, mouse)| mouse == 1)), "{:?}", std::fs::read_to_string(rig.held_path()));
    assert!(rig.send_input.0.holds_key(SCAN_W));
    assert_eq!(2, r.host.emergency_release());
    assert!(rig.send_input.0.nothing_held());
}

/// A panic anywhere in the process: the keys are up before it dies, and the log says why.
#[test]
fn a_panic_lets_go_of_what_is_held_and_is_logged() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    phone.state(|f| f.set_key(USAGE_W, true));
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));
    InputHost::install_panic_hook(&r.host);
    let crashed = std::thread::Builder::new().name("gp-doomed".into()).spawn(|| panic!("the pad driver answered nonsense")).unwrap().join();
    let _ = std::panic::take_hook(); // the default one again, for the other tests
    assert!(crashed.is_err());
    assert!(rig.send_input.0.nothing_held(), "W stays down after a crash");
    assert_eq!(1, rig.logs.lock().unwrap().iter().filter(|l| l.starts_with("input: crashed: ") && l.contains("the pad driver answered nonsense") && l.ends_with("let go of 1 held inputs")).count(), "{:?}", rig.logs.lock().unwrap());
}

#[test]
fn a_key_folder_that_cannot_be_used_is_tried_again_until_it_can() {
    let rig = Rig::new();
    // A file where the folder should be: the key can't be made.
    std::fs::write(rig.keys_dir(), b"in the way").unwrap();
    let r = rig.start();
    let (c, hello) = r.companion(1);
    let s = &hello["status"];
    assert!(s["keyError"].is_string(), "{s}");
    assert_eq!(json!(true), s["keyRetrying"]);
    assert_eq!((Value::Null, Value::Null), (s["snapshot"]["hostKeyFingerprint"].clone(), s["authorizedScan"].clone()));
    assert_eq!(Value::Null, c.call("OpenQrPairing", json!([])));
    assert_eq!(Value::Null, c.call("HostPublicKey", json!([])));
    assert_eq!(1, rig.logged("key file pairing off: "));

    std::fs::remove_file(rig.keys_dir()).unwrap();
    let s = r.status(&c, |s| s["snapshot"]["hostKeyFingerprint"].is_string());
    assert_eq!((Value::Null, json!(false)), (s["keyError"].clone(), s["keyRetrying"].clone()));
    assert_eq!(1, rig.logged("host key read: key file pairing on"));
    let s = r.status(&c, |s| s["authorizedScan"].is_string());
    assert_eq!(json!("0 of 0 .pub file(s) valid, 12:34:56"), s["authorizedScan"]);
    assert!(c.call("OpenQrPairing", json!([])).is_string());
}

// =====================================================================================
// pairing keys
// =====================================================================================

#[test]
fn qr_pairing_hands_out_a_token_and_the_pcs_key() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let token = base64_decode(c.call("OpenQrPairing", json!([])).as_str().unwrap()).unwrap();
    assert_eq!(16, token.len());
    let key = base64_decode(c.call("HostPublicKey", json!([])).as_str().unwrap()).unwrap();
    assert!(P256::is_valid_public(&key));
    let s = r.status(&c, |s| s["snapshot"]["qrPairingS"].as_i64().unwrap() > 0);
    assert!(s["snapshot"]["hostKeyFingerprint"].is_string());
    // A second code replaces the first.
    let token2 = base64_decode(c.call("OpenQrPairing", json!([])).as_str().unwrap()).unwrap();
    assert_ne!(token, token2);
    c.call("CloseQrPairing", json!([]));
    r.status(&c, |s| s["snapshot"]["qrPairingS"] == json!(0));
}

#[test]
fn a_key_file_dropped_in_the_folder_pairs_its_phone_and_forget_deletes_only_there() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let folder = rig.keys_dir().join("authorized_phones");
    let phone_key = P256::generate();
    let file = folder.join("pixel.pub");
    std::fs::write(&file, PubKeyFile { public_key: *phone_key.public_key(), name: "Pixel".into() }.format()).unwrap();
    // Windows tells of the change; the folder is read a moment later.
    (rig.folder_changed.lock().unwrap().clone().expect("the folder is watched"))();
    let s = r.status(&c, |s| s["snapshot"]["pairedPhones"].as_array().is_some_and(|p| p.len() == 1));
    let phone = s["snapshot"]["pairedPhones"][0].clone();
    assert_eq!((json!("Pixel"), json!(true)), (phone["name"].clone(), phone["byKey"].clone()));
    assert_eq!(json!(file.to_string_lossy()), phone["keyFile"]);
    r.status(&c, |s| s["authorizedScan"] == json!("1 of 1 .pub file(s) valid, 12:34:56"));

    // Forget names the file to delete: one outside the folder is never touched, whatever the call says.
    let precious = rig.dir.0.join("precious.txt");
    std::fs::write(&precious, b"keep").unwrap();
    let mut elsewhere = phone.clone();
    elsewhere["keyFile"] = json!(precious.to_string_lossy());
    c.call("Forget", json!([elsewhere]));
    let mut climbing = phone.clone();
    climbing["keyFile"] = json!(folder.join("..").join("..").join("precious.txt").to_string_lossy());
    c.call("Forget", json!([climbing]));
    let mut nested = phone.clone();
    nested["keyFile"] = json!(rig.keys_dir().join("host.key").to_string_lossy());
    c.call("Forget", json!([nested]));
    assert!(precious.exists() && rig.keys_dir().join("host.key").exists() && file.exists());
    assert_eq!(1, c.call("Snapshot", json!([]))["pairedPhones"].as_array().unwrap().len());

    c.call("Forget", json!([phone]));
    assert!(!file.exists());
    assert_eq!(0, c.call("Snapshot", json!([]))["pairedPhones"].as_array().unwrap().len(), "the folder is read again at once");
}

#[test]
fn forget_drops_a_code_paired_phone_and_its_session() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    let listed = c.call("Snapshot", json!([]))["pairedPhones"][0].clone();
    assert_eq!((json!("OnePlus 15"), json!(false)), (listed["name"].clone(), listed["byKey"].clone()));
    c.call("Forget", json!([listed]));
    let s = r.status(&c, |s| s["snapshot"]["connected"] == json!(false));
    assert_eq!(0, s["snapshot"]["pairedPhones"].as_array().unwrap().len());
}

// =====================================================================================
// outputs and their drivers
// =====================================================================================

#[test]
fn the_input_driver_is_used_only_while_its_switch_is_on() {
    let rig = Rig::new();
    rig.driver.installed.store(true, Ordering::SeqCst);
    rig.driver.plugs.store(true, Ordering::SeqCst);
    let r = rig.start();
    let (c, hello) = r.companion(1);
    assert_eq!((json!(true), json!(false)), (hello["status"]["inputDriverInstalled"].clone(), hello["status"]["inputDriverReady"].clone()));
    assert_eq!(0, rig.driver.creates.load(Ordering::SeqCst), "plugged with the switch off");
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    phone.state(|f| f.set_key(USAGE_W, true));
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));

    // On: W is let go on SendInput first, and pressed again through the driver with the next state.
    c.call("SetUseInputDriver", json!([true]));
    let s = r.status(&c, |s| s["inputDriverReady"] == json!(true));
    assert_eq!(json!(true), s["settings"]["useInputDriver"]);
    assert!(rig.send_input.0.nothing_held(), "W stays down on the route that was left");
    assert!(phone.keep_until(|| rig.driver.km.holds_key(SCAN_W)));
    assert_eq!(1, rig.driver.alive.load(Ordering::SeqCst));

    // Off: back to SendInput, and the driver's devices are unplugged.
    c.call("SetUseInputDriver", json!([false]));
    r.status(&c, |s| s["inputDriverReady"] == json!(false));
    assert!(rig.driver.km.nothing_held());
    assert_eq!(0, rig.driver.alive.load(Ordering::SeqCst), "the driver's mouse and keyboard stay plugged");
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));
}

#[test]
fn an_input_driver_installed_later_is_picked_up_and_a_failing_plug_logs_once() {
    let rig = Rig::new();
    rig.write_settings(|s| s.use_input_driver = true);
    let r = rig.start();
    let (c, hello) = r.companion(1);
    assert_eq!(json!(false), hello["status"]["inputDriverInstalled"]);
    // Installed, but its devices won't plug: tried again and again, said once.
    rig.driver.installed.store(true, Ordering::SeqCst);
    r.status(&c, |s| s["inputDriverInstalled"] == json!(true));
    assert!(wait_for(5000, || rig.driver.creates.load(Ordering::SeqCst) >= 3));
    assert_eq!(1, rig.logged("input driver: plug failed"));
    // Now it plugs.
    rig.driver.plugs.store(true, Ordering::SeqCst);
    r.status(&c, |s| s["inputDriverReady"] == json!(true));
    let made = rig.driver.creates.load(Ordering::SeqCst);
    std::thread::sleep(Duration::from_millis(250));
    assert_eq!(made, rig.driver.creates.load(Ordering::SeqCst), "plugged again while already plugged");
    assert_eq!(1, rig.driver.alive.load(Ordering::SeqCst));
    // Exit unplugs it.
    r.host.shutdown();
    assert_eq!(0, rig.driver.alive.load(Ordering::SeqCst));
}

#[test]
fn retry_driver_says_why_not_or_takes_the_new_pad() {
    let rig = Rig::new();
    *rig.pads_error.lock().unwrap() = Some("ViGEmBus is not installed".to_string());
    rig.pad.set(|p| {
        p.driver_ok = false;
        p.dual_sense_ok = false;
    });
    let r = rig.start();
    let (c, hello) = r.companion(1);
    assert_eq!(json!("ViGEmBus is not installed"), hello["status"]["padError"]);
    assert_eq!(json!(false), hello["status"]["snapshot"]["driverOk"]);
    assert_eq!(json!("ViGEmBus is not installed"), c.call("RetryDriver", json!([])));
    assert_eq!(0, rig.logged("gamepad driver found"));

    // Installed meanwhile.
    *rig.pads_error.lock().unwrap() = None;
    rig.pad.set(|p| p.driver_ok = true);
    assert_eq!(json!(""), c.call("RetryDriver", json!([])));
    let s = r.status(&c, |s| s["padError"].is_null());
    assert_eq!(json!(true), s["snapshot"]["driverOk"]);
    assert_eq!(1, rig.logged("gamepad driver found"));
    assert_eq!(3, rig.pads_made.load(Ordering::SeqCst));
}

#[test]
fn the_usb_cable_switch_reaches_the_link_and_the_settings() {
    let rig = Rig::new();
    rig.write_settings(|s| s.usb_direct = true);
    let r = rig.start();
    let (c, hello) = r.companion(1);
    assert_eq!((json!(true), json!("NoPhone"), json!("no phone on the cable")), (hello["status"]["usbEnabled"].clone(), hello["status"]["usbState"].clone(), hello["status"]["usbDetail"].clone()));
    c.call("SetUsbDirect", json!([false]));
    let s = r.status(&c, |s| s["usbEnabled"] == json!(false));
    assert_eq!(json!(false), s["settings"]["usbDirect"]);
    assert_eq!(1, rig.logged("usb direct off"));
    c.call("TakeUsbFromAdb", json!([]));
    assert_eq!(1, rig.usb.takes.load(Ordering::SeqCst));
}

// =====================================================================================
// Diagnostics and the delivery probe
// =====================================================================================

#[test]
fn diagnostics_runs_the_delivery_probe_and_its_lines_reach_the_companion() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    assert_eq!(0, rig.probes_made.load(Ordering::SeqCst), "the probe runs with Diagnostics off");
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    phone.state(|f| f.mouse_total_x += 5);
    assert!(wait_for(5000, || rig.send_input.0.mouse_x() == 5));
    assert!(rig.probe.0.lock().unwrap().sent.is_empty(), "moves are stored with Diagnostics off");

    c.call("SetDiagnostics", json!([true]));
    r.status(&c, |s| s["settings"]["diagnostics"] == json!(true));
    assert!(wait_for(5000, || rig.probe.0.lock().unwrap().running));
    assert!(c.wait_event("debug", 5000, |d| d["kind"] == json!(5) && d["text"].as_str().unwrap().starts_with("diagnostics on")).is_some());
    // Each mouse move's hand-off is told to the probe.
    phone.state(|f| {
        f.mouse_total_x += 7;
        f.mouse_total_y -= 3;
    });
    assert!(wait_for(5000, || rig.probe.0.lock().unwrap().sent.len() == 1));
    let (_, dx, dy) = rig.probe.0.lock().unwrap().sent[0];
    assert_eq!((7, -3), (dx, dy));
    // The probe's line about a slow move is a Diagnostics note.
    let slow = rig.probe.0.lock().unwrap().slow.clone().expect("slow moves are wanted while Diagnostics is on");
    slow("delivery slow 4.2 ms");
    assert!(c.wait_event("debug", 5000, |d| d["kind"] == json!(5) && d["text"] == json!("delivery slow 4.2 ms")).is_some());
    // Save: the companion is told to save its side, and the count shows in the status.
    c.call("SaveDiagnostics", json!([]));
    assert!(c.wait_event("debug-save", 5000, |_| true).is_some());
    r.status(&c, |s| s["diagnosticsSaved"] == json!(1));
    // The link's seconds go to the companion's Performance page.
    for _ in 0..12 {
        phone.state(|f| f.mouse_total_x += 1);
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(c.wait_event("link-second", 5000, |s| s["rx"].as_i64().unwrap() > 0).is_some());
    assert!(c.wait_event("debug", 5000, |d| d["kind"] == json!(4)).is_some(), "the input log's lines are Diagnostics lines");

    c.call("SetDiagnostics", json!([false]));
    assert!(wait_for(5000, || !rig.probe.0.lock().unwrap().running));
    let stored = rig.probe.0.lock().unwrap().sent.len();
    phone.state(|f| f.mouse_total_x += 9);
    let before = rig.send_input.0.mouse_x();
    assert!(wait_for(5000, || rig.send_input.0.mouse_x() == before + 9));
    assert_eq!(stored, rig.probe.0.lock().unwrap().sent.len(), "moves are stored after Diagnostics went off");
    assert!(rig.probe.0.lock().unwrap().slow.is_none());
}

#[test]
fn a_delivery_test_runs_the_probe_on_its_own_and_fills_the_shared_ring() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    assert_eq!(Value::Null, c.call("DeliveryTestNumbers", json!([])));
    let ring = rig.region.moves();
    ring.put(1, 1, 1); // left over from an earlier test

    c.call("SetDeliveryTest", json!([true, false]));
    assert_eq!(1, rig.logged("input: delivery test started"));
    assert_eq!((true, Some(false)), rig.probe.running_and_test());
    assert_eq!(ring.head(), ring.tail(), "the ring is emptied for the test");
    assert!(rig.probe.0.lock().unwrap().slow.is_none(), "a Delivery test alone writes no Diagnostics lines");
    c.call("SetDeliveryTest", json!([true, true]));
    assert_eq!(Some(true), rig.probe.0.lock().unwrap().test);
    assert_eq!(1, rig.logged("input: delivery test started"));

    let at = ring.head();
    phone.state(|f| f.mouse_total_x += 11);
    assert!(wait_for(5000, || ring.head() == at + 1));
    let (ticks, dx, dy) = ring.at(at);
    assert_eq!((11, 0), (dx, dy));
    assert_eq!(vec![(ticks, 11, 0)], rig.probe.0.lock().unwrap().sent, "the probe and the test window get the same move");
    let n = c.call("DeliveryTestNumbers", json!([]));
    assert_eq!((json!(10), json!(0.5), json!(100.0)), (n["n"].clone(), n["p50"].clone(), n["slowPer1000"].clone()));

    c.call("SetDeliveryTest", json!([false, false]));
    assert_eq!(1, rig.logged("input: delivery test stopped"));
    // No Diagnostics either: the probe is gone with the test.
    assert!(!rig.probe.running_and_test().0);
    assert_eq!(1, rig.probe.0.lock().unwrap().stops);
    phone.state(|f| f.mouse_total_x += 2);
    let before = rig.send_input.0.mouse_x();
    assert!(wait_for(5000, || rig.send_input.0.mouse_x() >= before));
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(at + 1, ring.head(), "the ring is written after the test");
}

#[test]
fn diagnostics_left_on_starts_the_probe_with_the_process() {
    let rig = Rig::new();
    rig.write_settings(|s| s.diagnostics = true);
    let r = rig.start();
    assert!(rig.probe.0.lock().unwrap().running);
    assert!(rig.probe.0.lock().unwrap().slow.is_some());
    r.host.shutdown();
    assert!(!rig.probe.running_and_test().0);
    assert_eq!(1, rig.probe.0.lock().unwrap().stops);
}

// =====================================================================================
// the rest of the calls
// =====================================================================================

#[test]
fn lock_pause_overlay_and_link_watch_reach_the_engine() {
    let rig = Rig::new();
    let r = rig.start();
    let (c, _) = r.companion(1);
    let mut phone = Phone::new(r.host.udp_port());
    phone.pair_and_connect(&r, &c);
    phone.state(|f| f.set_key(USAGE_W, true));
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));
    // The PC locks: everything is let go and stays so.
    c.call("SetLocked", json!([true]));
    assert!(rig.send_input.0.nothing_held());
    phone.state(|_| {});
    std::thread::sleep(Duration::from_millis(100));
    assert!(rig.send_input.0.nothing_held());
    c.call("SetLocked", json!([false]));
    assert!(phone.keep_until(|| rig.send_input.0.holds_key(SCAN_W)));
    c.call("SetPaused", json!([true]));
    assert!(rig.send_input.0.nothing_held());
    c.call("SetPaused", json!([false]));
    // Calls that only tell the phone something: answered, and the session lives on.
    c.call("SetOverlay", json!([true, true, false, true]));
    c.call("SetLinkWatch", json!([true]));
    c.call("SetLinkWatch", json!([false]));
    c.call("SetOverlay", json!([false, false, false]));
    assert_eq!(json!(true), c.call("Snapshot", json!([]))["connected"]);
}
