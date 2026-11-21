//! The link threads end to end: the UDP server over a real socket on loopback with a fake phone, the USB bridge
//! with a fake cable in front of it, the watchdog with its files, and the held-keys marker. Ports of the C#
//! UdpLoopbackTests, UsbBridgeTests, HeldKeysMarkerTests and the watchdog tests of SettingsAndStatsTests.

mod common;

use std::net::{SocketAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use common::harness::{FakeKeyMouse, FakePad, Harness, PlainProtector, RecordingEvents, PHONE1_ID};
use common::phone::FakePhone;
use common::{range_n, rng};
use gp_input::codec::{self, Discover};
use gp_input::engine::{EngineOptions, HostEngine, NullHooks};
use gp_input::held_marker::{self, HeldKeysMarker};
use gp_input::mapping::{self, InjectEvent};
use gp_input::ports::{Clock, KeyMouseSink, MonotonicClock, PairPrompt};
use gp_input::settings::{HostSettings, SettingsStore};
use gp_input::state_frame::{KEY_BITMAP_SIZE, MODIFIER_BYTE};
use gp_input::udp::{ServerState, UdpHostServer, UdpServerOptions};
use gp_input::usb_bridge::{self, UsbBridge, UsbPipe};
use gp_input::usb_framing::{self, UsbDeframer};
use gp_input::watchdog::{Watchdog, WatchdogOptions};
use gp_input::wire::{self, announce_flags, pair_ack, pair_done, packet};

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
    fn new(name: &str) -> Dir {
        static N: AtomicU32 = AtomicU32::new(0);
        let d = std::env::temp_dir().join(format!("gp-link-{name}-{}-{}", std::process::id(), N.fetch_add(1, Ordering::SeqCst)));
        let _ = std::fs::remove_dir_all(&d);
        Dir(d)
    }
    fn file(&self, name: &str) -> PathBuf {
        self.0.join(name)
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// Host clock running 20x slower than real time, so a scheduler stall on a loaded machine can never reach the
/// 250 ms rebase gap and skew the mouse sum.
struct SlowClock(MonotonicClock);

impl Clock for SlowClock {
    fn now_us(&self) -> i64 {
        self.0.now_us() / 20
    }
}

/// Like the tray: answers later, from another thread.
#[derive(Default)]
struct AutoAllowPrompt(Mutex<Option<Arc<HostEngine>>>);

impl PairPrompt for AutoAllowPrompt {
    fn show(&self, attempt_id: u32, _: &str, _: &str) {
        let engine = self.0.lock().unwrap().clone().unwrap();
        std::thread::spawn(move || engine.answer_pair(attempt_id, true));
    }
    fn close(&self, _: u32) {}
}

/// A FakeKeyMouse that can hold the rx thread inside `inject`.
#[derive(Default)]
struct GatedKeyMouse {
    inner: FakeKeyMouse,
    armed: AtomicBool,
    gate: (Mutex<(bool, bool)>, Condvar), // (blocked, released)
}

impl GatedKeyMouse {
    fn arm(&self) {
        *self.gate.0.lock().unwrap() = (false, false);
        self.armed.store(true, Ordering::SeqCst);
    }
    fn wait_blocked(&self) -> bool {
        let g = self.gate.0.lock().unwrap();
        let (g, _) = self.gate.1.wait_timeout_while(g, Duration::from_secs(5), |s| !s.0).unwrap();
        g.0
    }
    fn release(&self) {
        self.gate.0.lock().unwrap().1 = true;
        self.gate.1.notify_all();
    }
}

impl KeyMouseSink for GatedKeyMouse {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        self.inner.inject(events);
        if self.armed.swap(false, Ordering::SeqCst) {
            let mut g = self.gate.0.lock().unwrap();
            g.0 = true;
            self.gate.1.notify_all();
            let _ = self.gate.1.wait_timeout_while(g, Duration::from_secs(5), |s| !s.1).unwrap();
        }
        true
    }
}

struct Loopback {
    km: Arc<GatedKeyMouse>,
    engine: Arc<HostEngine>,
    server: UdpHostServer,
    clock: Arc<SlowClock>,
    phone_sock: UdpSocket,
    host: SocketAddr,
}

fn engine_with(name: &str, clock: Arc<dyn Clock>, km: Arc<dyn KeyMouseSink>, prompt: Arc<dyn PairPrompt>) -> Arc<HostEngine> {
    let mut settings = HostSettings::create_default(name);
    settings.host_id = range_n(0x10);
    HostEngine::new(
        settings,
        Arc::new(FakePad::default()),
        km,
        Arc::new(PlainProtector),
        EngineOptions {
            clock,
            events: Arc::new(RecordingEvents::default()),
            prompt,
            hooks: Arc::new(NullHooks),
            session_random: None,
            pairing_random: None,
            new_pair_key: None,
            host_key: None,
            shared: None,
            inline_workers: true,
        },
    )
}

fn local(port: u16) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], port))
}

impl Loopback {
    fn new() -> Loopback {
        let km = Arc::new(GatedKeyMouse::default());
        let clock = Arc::new(SlowClock(MonotonicClock::new()));
        let prompt = Arc::new(AutoAllowPrompt::default());
        let engine = engine_with("LOOPBACK-PC", clock.clone(), km.clone(), prompt.clone());
        *prompt.0.lock().unwrap() = Some(engine.clone());
        engine.start(clock.now_us());
        let server = UdpHostServer::new(engine.clone(), clock.clone(), UdpServerOptions { bind: Some(local(0)), ..UdpServerOptions::default() });
        assert!(server.start(true));
        assert_eq!(ServerState::Running, server.state());
        let host = local(server.local_port());
        let phone_sock = UdpSocket::bind(local(0)).unwrap();
        phone_sock.set_read_timeout(Some(Duration::from_millis(250))).unwrap();
        Loopback { km, engine, server, clock, phone_sock, host }
    }

    fn tx(&self, d: &[u8]) {
        self.phone_sock.send_to(d, self.host).unwrap();
    }

    fn rx(&self, accept: impl Fn(&[u8]) -> bool, timeout_ms: u64) -> Option<Vec<u8>> {
        let mut buf = [0u8; 2048];
        let start = Instant::now();
        while start.elapsed() < Duration::from_millis(timeout_ms) {
            if let Ok((n, _)) = self.phone_sock.recv_from(&mut buf) {
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

    fn discover_pair_and_connect(&self) -> FakePhone {
        let mut phone = FakePhone::new(range_n(0x00), "OnePlus 15", "127.0.0.1:1");
        let ann = self.exchange(&phone.discover(99), |d| d[3] == packet::ANNOUNCE);
        let a = codec::decode_announce(&ann).unwrap();
        assert_eq!("LOOPBACK-PC", a.name);
        assert_ne!(0, a.flags & announce_flags::PAIRING_OPEN);

        let start = phone.pair_start();
        assert!(phone.on_pair_host(&self.exchange(&start, |d| d[3] == packet::PAIR_HOST)));
        let ack = self.exchange(&phone.pair_reveal(None), |d| d[3] == packet::PAIR_ACK);
        assert_eq!(pair_ack::OK, ack[16]);
        let done = self.exchange(&phone.pair_confirm(false), |d| d[3] == packet::PAIR_DONE && d[16] != pair_done::PENDING);
        assert!(phone.on_pair_done(&done).0);

        let hello = phone.hello(None);
        assert!(phone.on_welcome(&self.exchange(&hello, |d| d[3] == packet::WELCOME)));
        self.tx(&phone.state(|_| {}));
        assert!(wait_for(5000, || self.engine.snapshot().connected));
        phone
    }
}

impl Drop for Loopback {
    fn drop(&mut self) {
        self.km.release();
        self.server.stop();
    }
}

#[test]
fn discover_pair_hello_and_lossy_stream() {
    let l = Loopback::new();
    let mut phone = l.discover_pair_and_connect();
    let mut next = rng(1234);
    let mut pick = |n: u64| (next() % n) as i32;
    let mut packets = Vec::new();
    for i in 0..1000 {
        packets.push(phone.state(|f| {
            f.mouse_total_x += pick(81) - 40;
            f.mouse_total_y += pick(81) - 40;
            if i % 50 == 0 {
                f.wheel_total += 1;
            }
            f.set_key(0x04 + pick(8) as u32, pick(2) == 0);
            f.mouse_buttons = pick(32) as u8;
            f.lx = (pick(65535) - 32767) as i16;
        }));
    }
    // Final neutral STATE: nothing held, totals unchanged.
    packets.push(phone.state(|f| {
        f.keys = [0; KEY_BITMAP_SIZE];
        f.mouse_buttons = 0;
        f.buttons = 0;
        f.lx = 0;
    }));

    let last = packets.len() - 1;
    let mut i = 0;
    while i < packets.len() {
        if i != last && pick(100) < 10 {
            i += 1; // 10 % loss
            continue;
        }
        if i != last && i + 1 < last && pick(100) < 5 {
            l.tx(&packets[i + 1]); // reorder: the older one arrives late and is dropped
            l.tx(&packets[i]);
            i += 2;
            continue;
        }
        l.tx(&packets[i]);
        if i % 100 == 0 {
            std::thread::sleep(Duration::from_millis(1)); // keep well inside the socket buffer
        }
        i += 1;
    }

    // Like the phone's 20 ms keepalive: if the socket buffer overflowed and the final STATE was lost, the next
    // keepalive (same state, new seq) repairs it.
    let (want_x, want_y, want_wheel) = (phone.frame.mouse_total_x as i64, phone.frame.mouse_total_y as i64, phone.frame.wheel_total as i64);
    let keepalive = Mutex::new((Instant::now(), phone));
    let done = wait_for(5000, || {
        let mut k = keepalive.lock().unwrap();
        if k.0.elapsed() >= Duration::from_millis(20) {
            let d = k.1.state(|_| {});
            l.tx(&d);
            k.0 = Instant::now();
        }
        l.km.inner.mouse_x() == want_x && l.km.inner.mouse_y() == want_y && l.km.inner.nothing_held()
    });
    assert!(done, "mouse ({},{}) expected ({want_x},{want_y}), nothing held = {}", l.km.inner.mouse_x(), l.km.inner.mouse_y(), l.km.inner.nothing_held());
    assert_eq!(want_wheel * mapping::WHEEL_DELTA as i64, l.km.inner.wheel());
    assert_eq!(0, l.server.rx_errors());
}

#[test]
fn backlog_of_hundred_states_gives_one_injection() {
    let l = Loopback::new();
    let mut phone = l.discover_pair_and_connect();
    l.km.arm(); // the next inject holds the rx thread
    l.tx(&phone.state(|f| f.set_key(0x1A, true)));
    assert!(l.km.wait_blocked());
    let calls_before = l.km.inner.calls();
    for _ in 0..100 {
        l.tx(&phone.state(|f| f.mouse_total_x += 1));
    }
    std::thread::sleep(Duration::from_millis(50));
    l.km.release();
    assert!(wait_for(5000, || l.km.inner.mouse_x() == 100));
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(calls_before + 1, l.km.inner.calls());
    let _ = l.clock.now_us();
}

#[test]
fn second_server_on_same_port_reports_port_in_use() {
    let l = Loopback::new();
    let other = UdpHostServer::new(l.engine.clone(), l.clock.clone(), UdpServerOptions { bind: Some(l.host), ..UdpServerOptions::default() });
    let started = other.start(true);
    // Windows refuses the second bind (the port is ours alone), and so does Linux without SO_REUSEPORT.
    assert!(!started);
    assert_eq!(ServerState::PortInUse, other.state());
    other.stop();
    // The first server is still the engine's way out.
    let phone = FakePhone::new(range_n(0x30), "x", "127.0.0.1:1");
    assert!(!l.exchange(&phone.discover(1), |d| d[3] == packet::ANNOUNCE).is_empty());
}

#[test]
fn the_server_stops_and_starts_again_on_its_port() {
    let l = Loopback::new();
    let port = l.server.local_port();
    let stopped = Instant::now();
    l.server.stop();
    assert!(stopped.elapsed() < Duration::from_secs(1), "stop took {:?}", stopped.elapsed());
    assert_eq!(ServerState::Stopped, l.server.state());
    assert_eq!(0, l.server.local_port());
    // The port is free again at once: gp-rx let the socket go.
    let again = UdpHostServer::new(l.engine.clone(), l.clock.clone(), UdpServerOptions { bind: Some(local(port)), ..UdpServerOptions::default() });
    assert!(again.start(true));
    let phone = FakePhone::new(range_n(0x30), "x", "127.0.0.1:1");
    let mut buf = [0u8; 2048];
    let mut got = false;
    for _ in 0..40 {
        l.phone_sock.send_to(&phone.discover(1), local(port)).unwrap();
        if let Ok((n, _)) = l.phone_sock.recv_from(&mut buf) {
            got = n > 3 && buf[3] == packet::ANNOUNCE;
            break;
        }
    }
    assert!(got);
    again.stop();
}

// ---- The USB bridge with a fake cable in front of the real UDP server ----

struct FakePipe {
    from_phone: Mutex<Receiver<Vec<u8>>>,
    feed: Mutex<Option<Sender<Vec<u8>>>>,
    to_phone_tx: Mutex<Sender<Vec<u8>>>,
    to_phone: Mutex<Receiver<Vec<u8>>>,
}

impl FakePipe {
    fn new() -> Arc<FakePipe> {
        let (feed, from_phone) = channel();
        let (to_phone_tx, to_phone) = channel();
        Arc::new(FakePipe { from_phone: Mutex::new(from_phone), feed: Mutex::new(Some(feed)), to_phone_tx: Mutex::new(to_phone_tx), to_phone: Mutex::new(to_phone) })
    }
    fn add(&self, d: &[u8]) {
        self.feed.lock().unwrap().as_ref().unwrap().send(d.to_vec()).unwrap();
    }
    fn take(&self, ms: u64) -> Option<Vec<u8>> {
        self.to_phone.lock().unwrap().recv_timeout(Duration::from_millis(ms)).ok()
    }
}

impl UsbPipe for FakePipe {
    fn read(&self, buffer: &mut [u8]) -> std::io::Result<usize> {
        match self.from_phone.lock().unwrap().recv() {
            Ok(d) => {
                buffer[..d.len()].copy_from_slice(&d);
                Ok(d.len())
            }
            Err(_) => Err(std::io::Error::new(std::io::ErrorKind::BrokenPipe, "unplugged")),
        }
    }
    fn write(&self, data: &[u8]) -> bool {
        self.to_phone_tx.lock().unwrap().send(data.to_vec()).is_ok()
    }
    fn cancel(&self) {
        *self.feed.lock().unwrap() = None; // the read ends with "unplugged"
    }
}

struct Usb {
    engine: Arc<HostEngine>,
    server: UdpHostServer,
    pipe: Arc<FakePipe>,
    bridge: UsbBridge,
}

impl Usb {
    fn new() -> Usb {
        let clock = Arc::new(MonotonicClock::new());
        let engine = engine_with("USB-PC", clock.clone(), Arc::new(FakeKeyMouse::default()), Arc::new(AutoAllowPrompt::default()));
        engine.start(clock.now_us());
        let server = UdpHostServer::new(engine.clone(), clock, UdpServerOptions { bind: Some(local(0)), ..UdpServerOptions::default() });
        assert!(server.start(true));
        let pipe = FakePipe::new();
        let bridge = UsbBridge::new(pipe.clone(), server.local_port(), None, None).unwrap();
        bridge.start().unwrap();
        Usb { engine, server, pipe, bridge }
    }
}

fn framed(d: &[u8]) -> Vec<u8> {
    let mut f = vec![0u8; usb_framing::max_size(d.len())];
    let n = usb_framing::encode(d, &mut f).unwrap();
    f.truncate(n);
    f
}

fn discover(req_id: u32) -> Vec<u8> {
    let mut b = vec![0u8; wire::DISCOVER_SIZE];
    codec::encode_discover(&Discover { req_id, phone_id: range_n(0x20), flags: 0, host_key_id: [0; 8], phone_key_id: [0; 8] }, &mut b);
    b
}

#[test]
fn discover_over_the_cable_gets_an_announce_back() {
    let u = Usb::new();
    let frame = framed(&discover(77));
    // Split across two USB reads, as a slow cable could deliver it.
    u.pipe.add(&frame[..2]);
    u.pipe.add(&frame[2..]);

    let back = u.pipe.take(2000).expect("something down the cable");
    let mut announce = None;
    UsbDeframer::new().feed(&back, |d| announce = Some(d.to_vec()));
    let a = codec::decode_announce(&announce.unwrap()).unwrap();
    assert_eq!(77, a.req_id);
    assert_eq!("USB-PC", a.name);
    assert!(u.bridge.alive());
    // The counter is raised once the write is done, a moment after the data is here.
    assert!(wait_for(2000, || u.bridge.to_phone() == 1));
    assert_eq!((1, 0), (u.bridge.from_phone(), u.bridge.write_failures()));
    assert!(u.bridge.local_addr().ip().is_loopback());
}

#[test]
fn local_only_controls_from_the_cable_are_dropped() {
    let u = Usb::new();
    let mut log = [0u8; wire::LOG_CTL_SIZE];
    log[..4].copy_from_slice(&[wire::MAGIC0, wire::MAGIC1, wire::VERSION, packet::LOG_CTL]);
    log[12] = wire::LOG_CTL_DIAGNOSTICS;
    log[13] = 1;
    let mut test = log;
    test[3] = packet::LINK_TEST_CTL;
    assert!(!usb_bridge::forwards(&log));
    assert!(!usb_bridge::forwards(&test));
    assert!(usb_bridge::forwards(&[wire::MAGIC0, wire::MAGIC1, wire::VERSION, packet::DISCOVER]));

    u.pipe.add(&framed(&log));
    // A DISCOVER behind it still gets its ANNOUNCE: by then the LOG_CTL would have been handled.
    u.pipe.add(&framed(&discover(5)));
    assert!(u.pipe.take(2000).is_some());
    assert!(!u.engine.settings().diagnostics);
    assert_eq!(ServerState::Running, u.server.state());
}

#[test]
fn unplugging_ends_the_bridge() {
    let u = Usb::new();
    u.pipe.cancel();
    assert!(wait_for(2000, || !u.bridge.alive()));
    let stopping = Instant::now();
    u.bridge.stop(); // both threads are gone already: nothing to wait for
    assert!(stopping.elapsed() < Duration::from_millis(900), "{:?}", stopping.elapsed());
}

#[test]
fn stopping_the_bridge_ends_both_threads_at_once() {
    let u = Usb::new();
    let stopping = Instant::now();
    u.bridge.stop();
    assert!(stopping.elapsed() < Duration::from_millis(900), "{:?}", stopping.elapsed());
    assert!(!u.bridge.alive());
}

#[test]
fn start_hook_runs_on_both_cable_threads() {
    let u = Usb::new();
    let names = Arc::new(Mutex::new(Vec::new()));
    let n2 = names.clone();
    let bridge = UsbBridge::new(
        FakePipe::new(),
        u.server.local_port(),
        None,
        Some(Arc::new(move || n2.lock().unwrap().push(std::thread::current().name().unwrap_or("").to_string()))),
    )
    .unwrap();
    bridge.start().unwrap();
    assert!(wait_for(5000, || names.lock().unwrap().len() == 2));
    let mut got = names.lock().unwrap().clone();
    got.sort();
    assert_eq!(vec!["gp-usb-rx", "gp-usb-tx"], got);
}

// ---- The held-keys marker ----

fn held(usages: &[usize]) -> [u8; KEY_BITMAP_SIZE] {
    let mut keys = [0u8; KEY_BITMAP_SIZE];
    for &u in usages {
        keys[u >> 3] |= 1 << (u & 7);
    }
    keys
}

#[test]
fn written_while_held_deleted_when_released() {
    let dir = Dir::new("held");
    let path = dir.file("held.json");
    let h = Harness::new();
    let marker = HeldKeysMarker::new(path.clone());
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let mut buf = [0u8; KEY_BITMAP_SIZE];
    let update = |buf: &mut [u8; KEY_BITMAP_SIZE]| {
        let (mouse, v) = h.engine.held_snapshot(buf);
        marker.update(v, buf, mouse);
    };

    update(&mut buf);
    assert!(!path.exists());

    let d = phone.state(|f| {
        f.set_key(0x1A, true);
        f.mouse_buttons = 1;
    });
    h.send(&phone, &d);
    update(&mut buf);
    assert_eq!("{\"Keys\":[26],\"MouseButtons\":1}", std::fs::read_to_string(&path).unwrap());

    let d = phone.state(|f| {
        f.set_key(0x1A, false);
        f.mouse_buttons = 0;
    });
    h.send(&phone, &d);
    update(&mut buf);
    assert!(!path.exists());
}

#[test]
fn recover_releases_and_deletes() {
    let dir = Dir::new("recover");
    let path = dir.file("held.json");
    let marker = HeldKeysMarker::new(path.clone());
    let mut keys = held(&[0x1A]);
    keys[MODIFIER_BYTE] = 0x01;
    marker.update(1, &keys, 0b10001);
    assert!(path.exists());

    let sink = FakeKeyMouse::default();
    assert_eq!(4, HeldKeysMarker::recover(&path, &sink));
    let e = sink.all();
    assert_eq!(mapping::MOUSE_LEFT_UP, e[0].flags);
    assert_eq!((mapping::MOUSE_X_UP, mapping::XBUTTON2), (e[1].flags, e[1].mouse_data));
    assert_eq!(0x1A, e[2].usage);
    assert!(e[2].is_key_up());
    assert_eq!(0xE0, e[3].usage);
    assert!(e[3].is_key_up());
    assert!(!path.exists());
    assert_eq!(0, HeldKeysMarker::recover(&path, &sink));
}

#[test]
fn the_marker_is_the_csharp_file() {
    // As the C# input process writes it (System.Text.Json, no spaces), and as a hand-edited or older one may look.
    assert_eq!("{\"Keys\":[4,26,224],\"MouseButtons\":17}", held_marker::format(&held(&[4, 26, 224]), 17));
    assert_eq!(Some((held(&[4, 26, 224]), 17)), held_marker::parse(b"{\"Keys\":[4,26,224],\"MouseButtons\":17}"));
    assert_eq!(Some((held(&[26]), 0)), held_marker::parse(b"{ \"Keys\": [26, -1, 256, 99999] }"));
    assert_eq!(Some((held(&[]), 1)), held_marker::parse(b"{\"Keys\":null,\"MouseButtons\":33}"));
    for bad in ["", "{not json", "[]", "{\"Keys\":[\"a\"]}", "{\"Keys\":7}", "{\"Keys\":[1.5]}", "{\"MouseButtons\":\"x\"}"] {
        assert_eq!(None, held_marker::parse(bad.as_bytes()), "{bad}");
    }
    // Any bytes at all are read without a panic.
    let mut next = rng(77);
    for _ in 0..5000 {
        let len = (next() % 80) as usize;
        let junk: Vec<u8> = (0..len).map(|_| b"{}[]\":,0123456789KeysMouseButtons nul-.e"[(next() % 40) as usize]).collect();
        let _ = held_marker::parse(&junk);
    }
}

#[test]
fn corrupt_marker_is_deleted() {
    let dir = Dir::new("corrupt");
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.file("held.json");
    std::fs::write(&path, "{not json").unwrap();
    assert_eq!(0, HeldKeysMarker::recover(&path, &FakeKeyMouse::default()));
    assert!(!path.exists());
}

#[test]
fn a_marker_that_cannot_be_written_is_tried_again() {
    let dir = Dir::new("retry");
    let path = dir.file("held.json");
    std::fs::create_dir_all(&path).unwrap(); // a folder where the file should be: the write fails
    let marker = HeldKeysMarker::new(path.clone());
    marker.update(1, &held(&[0x2C]), 0);
    assert!(path.is_dir());
    std::fs::remove_dir(&path).unwrap();
    marker.update(1, &held(&[0x2C]), 0); // the same version: still owed
    assert!(path.is_file());
}

// ---- The watchdog ----

fn watchdog(h: &Harness, marker: Option<PathBuf>, store: Option<PathBuf>) -> Watchdog {
    Watchdog::new(
        h.engine.clone(),
        h.clock.clone(),
        WatchdogOptions { marker: marker.map(HeldKeysMarker::new), store: store.map(|p| SettingsStore::new(p, 0)), ..WatchdogOptions::default() },
    )
}

#[test]
fn watchdog_writes_marker_off_rx_thread() {
    let dir = Dir::new("wd-marker");
    let path = dir.file("held.json");
    let h = Harness::new();
    let wd = watchdog(&h, Some(path.clone()), None);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let d = phone.state(|f| f.set_key(0x2C, true));
    h.send(&phone, &d);
    assert!(!path.exists()); // rx never writes it
    wd.tick();
    assert!(!path.exists()); // nor the tick: the writer thread does
    wd.write_files();
    assert!(path.exists());
    h.clock.advance_ms(300);
    wd.tick(); // silence release
    wd.write_files();
    assert!(!path.exists());
}

#[test]
fn started_watchdog_ticks_and_writes_on_its_own_threads() {
    let dir = Dir::new("wd-threads");
    let path = dir.file("held.json");
    let h = Harness::new();
    let wd = watchdog(&h, Some(path.clone()), None);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let d = phone.state(|f| f.set_key(0x2C, true));
    h.send(&phone, &d);
    assert!(wd.start());
    // The writer writes the marker within its half-second turn.
    assert!(wait_for(5000, || path.exists()));
    // 250 ms of silence: the tick lets go, and the writer deletes the marker.
    h.clock.advance_ms(300);
    assert!(wait_for(5000, || !path.exists()));
    assert!(h.key_mouse.nothing_held());
    let stopping = Instant::now();
    wd.stop();
    assert!(stopping.elapsed() < Duration::from_secs(2), "{:?}", stopping.elapsed());
}

#[test]
fn failed_settings_write_is_retried() {
    let dir = Dir::new("wd-retry");
    let path = dir.file("settings.json");
    let tmp = dir.file("settings.json.tmp");
    std::fs::create_dir_all(&tmp).unwrap(); // the write cannot create its temp file
    let h = Harness::new();
    let wd = watchdog(&h, None, Some(path.clone()));
    assert!(h.engine.forget(&PHONE1_ID));
    wd.tick();
    wd.write_files();
    assert!(!path.exists());
    std::fs::remove_dir(&tmp).unwrap();
    h.clock.advance_ms(100);
    wd.write_files(); // still waiting out the retry pause
    assert!(!path.exists());
    h.clock.advance_ms(1000);
    wd.write_files();
    assert!(path.exists());
    let back = SettingsStore::new(path, 0).load("x", &|_| {}).unwrap();
    assert!(back.paired_phones.is_empty());
}

#[test]
fn engine_persists_pairing_via_watchdog() {
    let dir = Dir::new("wd-save");
    let path = dir.file("settings.json");
    let h = Harness::new();
    let wd = watchdog(&h, None, Some(path.clone()));
    assert!(h.engine.forget(&PHONE1_ID));
    wd.tick();
    assert!(!path.exists()); // the tick never writes
    wd.write_files();
    assert!(SettingsStore::new(path, 0).load("x", &|_| {}).unwrap().paired_phones.is_empty());
}

#[test]
fn probes_reach_the_engine_off_the_tick() {
    let h = Harness::new();
    let mut options = WatchdogOptions::default();
    options.probes.elevated_foreground = Some(Box::new(|| true));
    options.probes.network_public = Some(Box::new(|| true));
    options.probes.pc_wifi_client = Some(Box::new(|_| Some(false)));
    let wd = Watchdog::new(h.engine.clone(), h.clock.clone(), options);
    wd.tick();
    assert!(!h.engine.snapshot().network_public); // the tick only asks for them
    assert!(wd.start());
    assert!(wait_for(5000, || h.engine.snapshot().network_public));
    wd.stop();
}
