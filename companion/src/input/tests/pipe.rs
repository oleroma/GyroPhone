//! The companion's line: the framing and the reader (PipeChannel's tests in SplitTests.cs), the outbox, the
//! JSON shapes the companion reads (checked against the C# records' names), and every call it can make.

mod common;

use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use common::companion::{frame, Companion};
use common::rng;
use gp_input::codec::LinkInfo;
use gp_input::control::{self, DeliveryNumbers, InputControl, InputHello, InputSettingsView, InputStatus};
use gp_input::engine::{ButtonLatency, HostCounters, HostSnapshot, PairedPhoneInfo, PhoneSecond};
use gp_input::pipe::{self, Channel, Duplex, EventOutbox, Payload, KIND_CALL, KIND_EVENT, KIND_REPLY, MAX_MESSAGE};
use gp_input::recorder::LinkSecond;
use gp_input::settings::HostSettings;
use gp_input::udp::Log;
use serde_json::{json, Value};

fn wait_for(ms: u64, cond: impl Fn() -> bool) -> bool {
    let start = Instant::now();
    while start.elapsed() < Duration::from_millis(ms) {
        if cond() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(2));
    }
    cond()
}

fn socket_pair() -> (TcpStream, TcpStream) {
    let l = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let client = TcpStream::connect(l.local_addr().unwrap()).unwrap();
    let (server, _) = l.accept().unwrap();
    (server, client)
}

type Lines = Arc<Mutex<Vec<String>>>;

fn logger() -> (Log, Lines) {
    let lines: Lines = Arc::default();
    let l = lines.clone();
    (Arc::new(move |s: &str| l.lock().unwrap().push(s.to_string())), lines)
}

/// A line whose calls are answered by `on_call`, and the companion at its other end.
fn pair(on_call: pipe::CallHandler) -> (Arc<Channel>, Companion, Lines) {
    let (server, client) = socket_pair();
    let (log, lines) = logger();
    let channel = Channel::serve(Arc::new(server), on_call, log, "test-pipe").unwrap();
    (channel, Companion::over(client), lines)
}

fn calc() -> pipe::CallHandler {
    Box::new(|method, args| match method {
        "Add" => Ok(json!(args[0].as_i64().unwrap() + args[1].as_i64().unwrap())),
        "Echo" => Ok(args.first().cloned().unwrap_or(Value::Null)),
        "Nothing" => Ok(Value::Null),
        "Fail" => Err("InvalidOperationException: no".to_string()),
        _ => Err(format!("MissingMethodException: {method}")),
    })
}

// =====================================================================================
// the line
// =====================================================================================

#[test]
fn calls_are_answered_in_order() {
    let (_channel, c, _) = pair(calc());
    assert_eq!(json!(3), c.call("Add", json!([1, 2])));
    assert_eq!(json!("héllo \"x\"\n"), c.call("Echo", json!(["héllo \"x\"\n"])));
    assert_eq!(Value::Null, c.call("Nothing", json!([])));
    // Many at once: each reply carries its call's id.
    for i in 0..200 {
        assert_eq!(json!(i + 1), c.call("Add", json!([i, 1])));
    }
}

#[test]
fn a_reply_has_the_fields_the_companion_reads() {
    let (_channel, c, _) = pair(calc());
    let ok = c.call_raw("Add", json!([2, 2])).unwrap();
    assert_eq!(json!({ "id": 1, "ok": true, "error": null, "result": 4 }), ok);
    let failed = c.call_raw("Fail", json!([])).unwrap();
    assert_eq!(json!({ "id": 2, "ok": false, "error": "InvalidOperationException: no", "result": null }), failed);
}

#[test]
fn a_failed_call_is_logged_and_the_line_stays() {
    let (channel, c, lines) = pair(calc());
    assert_eq!("MissingMethodException: Nope", c.call_err("Nope", json!([])));
    assert_eq!(json!(5), c.call("Add", json!([2, 3])));
    assert!(!channel.is_closed());
    assert_eq!(vec!["pipe: Nope failed: MissingMethodException: Nope".to_string()], *lines.lock().unwrap());
}

#[test]
fn events_reach_the_companion() {
    let (channel, c, _) = pair(calc());
    assert!(channel.post("status", &json!({ "connected": true })));
    assert!(channel.post_with("pair-close", |b| b.extend_from_slice(b"7")));
    assert!(channel.post("debug-save", &Value::Null));
    assert!(c.wait_event("debug-save", 5000, |_| true).is_some());
    assert_eq!(vec![json!({ "connected": true })], c.events("status"));
    assert_eq!(vec![json!(7)], c.events("pair-close"));
}

#[test]
fn a_message_in_pieces_is_put_together() {
    let (_channel, c, _) = pair(calc());
    let body = serde_json::to_vec(&json!({ "id": 9, "method": "Add", "args": [40, 2] })).unwrap();
    for b in frame(KIND_CALL, &body) {
        assert!(c.send_raw(&[b]));
        std::thread::sleep(Duration::from_micros(200));
    }
    // The next call's reply comes after the pieced one's: both were answered.
    assert_eq!(json!(2), c.call("Add", json!([1, 1])));
}

#[test]
fn a_long_message_goes_through_both_ways() {
    let (_channel, c, _) = pair(calc());
    let text = "x".repeat(1_000_000);
    assert_eq!(json!(text), c.call("Echo", json!([text])));
}

#[test]
fn the_companions_replies_and_events_are_read_and_dropped() {
    let (channel, c, lines) = pair(calc());
    assert!(c.send_raw(&frame(KIND_REPLY, br#"{"id":5,"ok":true,"error":null,"result":1}"#)));
    assert!(c.send_raw(&frame(KIND_EVENT, br#"{"name":"x","payload":null}"#)));
    assert!(c.send_raw(&frame(9, b"{}")));
    assert_eq!(json!(3), c.call("Add", json!([1, 2])));
    assert!(!channel.is_closed());
    assert!(lines.lock().unwrap().is_empty());
}

#[test]
fn a_call_without_arguments_array_takes_none() {
    let (_channel, c, _) = pair(calc());
    let body = br#"{"id":500,"method":"Echo","args":null}"#;
    assert!(c.send_raw(&frame(KIND_CALL, body)));
    assert_eq!(json!(2), c.call("Add", json!([1, 1])));
}

fn closes_on(bytes: &[u8], why: &str) {
    let (channel, c, lines) = pair(calc());
    assert!(c.send_raw(bytes));
    assert!(channel.wait_closed(5000), "{why}: still open");
    assert!(c.wait_closed(5000));
    assert_eq!(vec![format!("pipe: {why}")], *lines.lock().unwrap());
    assert!(!channel.post("status", &Value::Null));
}

#[test]
fn a_message_that_is_not_ours_closes_the_line() {
    closes_on(&[0xFF, 0xFF, 0xFF, 0xFF, KIND_CALL], "bad message length");
    closes_on(&((MAX_MESSAGE as i32 + 1).to_le_bytes().iter().copied().chain([KIND_CALL]).collect::<Vec<u8>>()), "bad message length");
    closes_on(&frame(KIND_CALL, b"not json"), "a message is not JSON");
    closes_on(&frame(KIND_EVENT, b"{"), "a message is not JSON");
    closes_on(&frame(KIND_CALL, br#"{"method":"Add","args":[]}"#), "a call has no id");
    closes_on(&frame(KIND_CALL, br#"{"id":"1","method":"Add","args":[]}"#), "a call has no id");
    closes_on(&frame(KIND_CALL, br#"{"id":99999999999,"method":"Add","args":[]}"#), "a call has no id");
    closes_on(&frame(KIND_CALL, br#"{"id":1,"method":5,"args":[]}"#), "a call has no method");
    closes_on(&frame(KIND_CALL, br#"{"id":1,"method":"Add"}"#), "a call has no arguments");
    closes_on(&frame(KIND_CALL, b"[1,2]"), "a call has no id");
}

#[test]
fn the_companion_going_away_closes_the_line_quietly() {
    let (channel, c, lines) = pair(calc());
    assert_eq!(json!(2), c.call("Add", json!([1, 1])));
    c.vanish();
    assert!(channel.wait_closed(5000));
    assert!(channel.is_closed());
    assert!(!channel.post("status", &Value::Null));
    channel.close_and_join();
    assert!(lines.lock().unwrap().is_empty());
}

#[test]
fn closing_ends_the_reader_and_tells_the_other_side() {
    let (channel, c, _) = pair(calc());
    channel.close_and_join();
    assert!(channel.wait_closed(0));
    assert!(c.wait_closed(5000));
    assert!(c.call_raw("Add", json!([1, 1])).is_none());
}

/// Whatever bytes come, the reader never panics: it answers what is a call and closes on what is not ours.
#[test]
fn hammering_the_reader_with_broken_messages() {
    let mut next = rng(77);
    let panicked = Arc::new(AtomicBool::new(false));
    for round in 0..150 {
        let (server, client) = socket_pair();
        let p = panicked.clone();
        let on_call: pipe::CallHandler = Box::new(move |method, args| {
            // The real answerer, with a control that does nothing.
            let r = std::panic::catch_unwind(|| control::dispatch(&Recording::default(), method, args));
            r.unwrap_or_else(|_| {
                p.store(true, Ordering::SeqCst);
                Err("panic".to_string())
            })
        });
        let channel = Channel::serve(Arc::new(server), on_call, Arc::new(|_| {}), "hammer").unwrap();
        let c = Companion::over(client);
        let mut bytes = Vec::new();
        for _ in 0..(1 + next() % 6) {
            match next() % 5 {
                // Random bytes.
                0 => bytes.extend((0..next() % 64).map(|_| next() as u8)),
                // A well-framed message of random bytes.
                1 => {
                    let body: Vec<u8> = (0..next() % 48).map(|_| next() as u8).collect();
                    bytes.extend(frame((next() % 5) as u8, &body));
                }
                // A real call with odd arguments.
                2 => {
                    let method = control::METHODS[(next() % control::METHODS.len() as u64) as usize];
                    let odd = [json!(null), json!(-1), json!(1e300), json!("x"), json!([[]]), json!({}), json!(true), json!(4294967296u64)];
                    let args: Vec<Value> = (0..next() % 5).map(|_| odd[(next() % odd.len() as u64) as usize].clone()).collect();
                    let body = serde_json::to_vec(&json!({ "id": round, "method": method, "args": args })).unwrap();
                    bytes.extend(frame(KIND_CALL, &body));
                }
                // A call cut short.
                3 => {
                    let body = br#"{"id":1,"method":"Snapshot","args":[]}"#;
                    let f = frame(KIND_CALL, body);
                    bytes.extend(&f[..(next() % f.len() as u64) as usize]);
                }
                // JSON of the wrong shape.
                _ => {
                    let shapes: [&[u8]; 6] = [b"null", b"[]", b"{}", br#"{"id":1}"#, br#"{"id":1,"method":null,"args":{}}"#, br#"{"id":-5,"method":"","args":[]}"#];
                    bytes.extend(frame(KIND_CALL, shapes[(next() % 6) as usize]));
                }
            }
        }
        c.send_raw(&bytes);
        c.vanish();
        assert!(channel.wait_closed(5000), "round {round}: the reader hangs");
        channel.close_and_join();
    }
    assert!(!panicked.load(Ordering::SeqCst), "a call panicked");
}

// =====================================================================================
// the outbox
// =====================================================================================

/// A stream whose writes wait at a gate (a companion that stopped reading), keeping what was written.
#[derive(Default)]
struct GatedStream {
    held: Mutex<bool>,
    open: Condvar,
    written: Mutex<Vec<u8>>,
    closed: AtomicBool,
    writes: AtomicUsize,
}

impl GatedStream {
    fn hold(&self, on: bool) {
        *self.held.lock().unwrap() = on;
        self.open.notify_all();
    }

    fn events(&self) -> Vec<(String, Value)> {
        let w = self.written.lock().unwrap();
        let mut at = 0;
        let mut out = Vec::new();
        while at + 5 <= w.len() {
            let len = i32::from_le_bytes([w[at], w[at + 1], w[at + 2], w[at + 3]]) as usize;
            assert_eq!(KIND_EVENT, w[at + 4]);
            let v: Value = serde_json::from_slice(&w[at + 5..at + 5 + len]).unwrap();
            out.push((v["name"].as_str().unwrap().to_string(), v["payload"].clone()));
            at += 5 + len;
        }
        assert_eq!(at, w.len());
        out
    }
}

impl Duplex for GatedStream {
    fn read(&self, _buf: &mut [u8]) -> std::io::Result<usize> {
        // Nothing ever comes; ends when closed.
        let g = self.held.lock().unwrap();
        let _g = self.open.wait_while(g, |_| !self.closed.load(Ordering::SeqCst)).unwrap();
        Ok(0)
    }

    fn write_all(&self, buf: &[u8]) -> std::io::Result<()> {
        self.writes.fetch_add(1, Ordering::SeqCst);
        let g = self.held.lock().unwrap();
        let _g = self.open.wait_while(g, |held| *held && !self.closed.load(Ordering::SeqCst)).unwrap();
        if self.closed.load(Ordering::SeqCst) {
            return Err(std::io::Error::other("closed"));
        }
        self.written.lock().unwrap().extend_from_slice(buf);
        Ok(())
    }

    fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        let _g = self.held.lock().unwrap();
        self.open.notify_all();
    }
}

fn gated() -> (Arc<GatedStream>, Arc<Channel>) {
    let stream = Arc::new(GatedStream::default());
    let channel = Channel::serve(stream.clone(), calc(), Arc::new(|_| {}), "gated").unwrap();
    (stream, channel)
}

#[test]
fn events_without_a_companion_are_dropped_and_counted() {
    let out = EventOutbox::new();
    out.post("status", Payload::None);
    out.post("debug-save", Payload::None);
    assert_eq!(2, out.dropped());
    assert!(!out.attached());
}

#[test]
fn events_go_out_in_order_on_the_outbox_thread() {
    let (stream, channel) = gated();
    let out = EventOutbox::new();
    out.set_channel(Some(channel.clone()));
    for i in 0..500u32 {
        out.post("pair-close", Payload::PairClose(i));
    }
    assert!(wait_for(5000, || stream.events().len() == 500));
    let got: Vec<Value> = stream.events().into_iter().map(|(_, p)| p).collect();
    assert_eq!((0..500u32).map(|i| json!(i)).collect::<Vec<_>>(), got);
    assert_eq!(0, out.dropped());
}

#[test]
fn a_companion_that_stopped_reading_costs_only_dropped_events() {
    let (stream, channel) = gated();
    let out = EventOutbox::new();
    out.set_channel(Some(channel.clone()));
    stream.hold(true);
    out.post("pair-close", Payload::PairClose(0));
    // The outbox thread now waits in the write; the queue fills behind it.
    assert!(wait_for(5000, || stream.writes.load(Ordering::SeqCst) == 1));
    let start = Instant::now();
    for i in 1..=(EventOutbox::MAX_QUEUED as u32 + 100) {
        out.post("pair-close", Payload::PairClose(i));
    }
    assert!(start.elapsed() < Duration::from_secs(2), "post waited for the pipe");
    assert_eq!(100, out.dropped());
    stream.hold(false);
    assert!(wait_for(10_000, || stream.events().len() == EventOutbox::MAX_QUEUED + 1));
    assert_eq!(json!(EventOutbox::MAX_QUEUED as u32), stream.events().last().unwrap().1);
}

#[test]
fn a_closed_line_drops_what_was_queued() {
    let (stream, channel) = gated();
    let out = EventOutbox::new();
    out.set_channel(Some(channel.clone()));
    channel.close();
    for _ in 0..10 {
        out.post("status", Payload::None);
    }
    assert!(wait_for(5000, || out.dropped() == 10));
    assert!(stream.events().is_empty());
    // The line taken away: a newer one stays when the old one is cleared late.
    let (_s2, c2) = gated();
    out.set_channel(Some(c2.clone()));
    out.clear_channel(&channel);
    assert!(out.attached());
    out.clear_channel(&c2);
    assert!(!out.attached());
}

#[test]
fn flush_sends_what_waits_on_the_callers_thread() {
    let (stream, channel) = gated();
    let out = EventOutbox::new();
    out.set_channel(Some(channel));
    out.stop();
    out.post("debug-save", Payload::None);
    out.post("pair-close", Payload::PairClose(4));
    out.flush();
    assert_eq!(vec![("debug-save".to_string(), Value::Null), ("pair-close".to_string(), json!(4))], stream.events());
}

// =====================================================================================
// what the companion reads
// =====================================================================================

fn payload_json(p: &Payload) -> Value {
    let mut b = Vec::new();
    pipe::push_payload(&mut b, p);
    serde_json::from_slice(&b).unwrap_or_else(|e| panic!("{e}: {}", String::from_utf8_lossy(&b)))
}

#[test]
fn event_payloads_have_the_companions_names() {
    assert_eq!(Value::Null, payload_json(&Payload::None));
    assert_eq!(json!(12), payload_json(&Payload::PairClose(12)));
    assert_eq!(
        json!({ "us": 5_000_000_123i64, "kind": 4, "text": "a \"line\"\twith\\ odd \u{1} chars é" }),
        payload_json(&Payload::Debug { us: 5_000_000_123, kind: 4, text: "a \"line\"\twith\\ odd \u{1} chars é".to_string() })
    );
    assert_eq!(
        json!({ "attemptId": 4_000_000_000u32, "phoneName": "OnePlus 15", "sas": "123 456" }),
        payload_json(&Payload::PairShow { attempt_id: 4_000_000_000, phone_name: "OnePlus 15".into(), sas: "123 456".into() })
    );
    // LinkSecond(AtUs, Rx, Lost, Late, MaxLateMs, Gaps, Releases, LatP50Ms, LatP99Ms, JitterMs) and its Trouble.
    let s = LinkSecond {
        at_us: 7, rx: 1000, lost: 0, late: 2, max_late_ms: 3.5, gaps: 0, releases: 0, lat_p50_ms: 1.25, lat_p99_ms: -1.0, jitter_ms: 0.5,
        floor_ms: 0.75, rtt_typ_ms: 2.0, over_p50_ms: 0.25, over_p99_ms: 4.5, late3: 3, hand_off_max_ms: 0.5, slow_hand_offs: 1, lock_wait_ms: 0.125,
        stall_max_ms: -1.0, stalls: 4, neg_lat: 5, air: 6, pc: 7, phone: 8, moving: 900,
        over: [1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9],
    };
    assert_eq!(
        json!({ "atUs": 7, "rx": 1000, "lost": 0, "late": 2, "maxLateMs": 3.5, "gaps": 0, "releases": 0, "latP50Ms": 1.25, "latP99Ms": -1.0, "jitterMs": 0.5,
            "floorMs": 0.75, "rttTypMs": 2.0, "overP50Ms": 0.25, "overP99Ms": 4.5, "late3": 3, "handOffMaxMs": 0.5, "slowHandOffs": 1, "lockWaitMs": 0.125,
            "stallMaxMs": -1.0, "stalls": 4, "negLat": 5, "air": 6, "pc": 7, "phone": 8, "moving": 900,
            "over": [1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9], "trouble": true }),
        payload_json(&Payload::LinkSecond(s))
    );
    assert_eq!(json!(false), payload_json(&Payload::LinkSecond(LinkSecond { late: 0, ..s }))["trouble"]);
    // PhoneSecond(AtUs, States, Packets, Failed, SlowSends, SendMaxMs, WakeLateMs, GapMaxMs, PhoneTx).
    let p = PhoneSecond {
        at_us: -3, states: 1, packets: 2, failed: 3, slow_sends: 4, send_max_ms: 0.25, wake_late_ms: 8.0, gap_max_ms: 16.5, phone_tx: 1 << 40,
        rtt_min_ms: 1.5, rtt_typ_ms: 2.25, drift_ppm: -40.5, pings: 5, battery: 80, setup_flags: 5, temp_deci_c: 315, thermal: 1, usb_vendor: 3034, usb_product: 33107, wifi_standard: 6,
    };
    assert_eq!(
        json!({ "atUs": -3, "states": 1, "packets": 2, "failed": 3, "slowSends": 4, "sendMaxMs": 0.25, "wakeLateMs": 8.0, "gapMaxMs": 16.5, "phoneTx": 1u64 << 40,
            "rttMinMs": 1.5, "rttTypMs": 2.25, "driftPpm": -40.5, "pings": 5, "battery": 80, "setupFlags": 5, "tempDeciC": 315, "thermal": 1,
            "usbVendor": 3034, "usbProduct": 33107, "wifiStandard": 6 }),
        payload_json(&Payload::PhoneSecond(p))
    );
}

/// A float that is not a number goes as the text System.Text.Json's AllowNamedFloatingPointLiterals reads; a
/// bare NaN would end the companion's reader.
#[test]
fn floats_that_are_not_numbers_go_as_their_names() {
    let p = PhoneSecond { at_us: 0, states: 0, packets: 0, failed: 0, slow_sends: 0, send_max_ms: f32::NAN, wake_late_ms: f32::INFINITY, gap_max_ms: f32::NEG_INFINITY, phone_tx: 0, ..Default::default() };
    let v = payload_json(&Payload::PhoneSecond(p));
    assert_eq!((json!("NaN"), json!("Infinity"), json!("-Infinity")), (v["sendMaxMs"].clone(), v["wakeLateMs"].clone(), v["gapMaxMs"].clone()));
    let n = DeliveryNumbers { n: 0, p50: f64::NAN, p99: 1.5, max: f64::INFINITY, slow: 0, unmatched: 0, merged: 0, stray: 0 };
    let v = control::delivery_json(&n);
    assert_eq!((json!("NaN"), json!(1.5), json!("Infinity"), json!(0.0)), (v["p50"].clone(), v["p99"].clone(), v["max"].clone(), v["slowPer1000"].clone()));
}

fn keys(v: &Value) -> Vec<String> {
    let mut k: Vec<String> = v.as_object().unwrap().keys().cloned().collect();
    k.sort();
    k
}

/// The C# record's parameter and property names (as the source spells them), as the pipe's camelCase policy
/// writes them: the leading capitals lowered.
fn camel(names: &[&str]) -> Vec<String> {
    let mut out: Vec<String> = names
        .iter()
        .map(|n| {
            let mut c = n.chars();
            let first = c.next().unwrap().to_ascii_lowercase();
            format!("{first}{}", c.as_str())
        })
        .collect();
    out.sort();
    out
}

fn full_snapshot() -> HostSnapshot {
    HostSnapshot {
        phone_name: Some("OnePlus 15".into()),
        connected: true,
        silent: false,
        output_mode: 3,
        driver_ok: false,
        pad_plugged: true,
        paused: false,
        elevated_foreground: true,
        network_public: false,
        pairing_window_s: 42,
        pair_prompt: Some((7, "Pixel".into(), "111 222".into())),
        rx_per_sec: 1000,
        loss_permille: 3,
        lat_p50_us: 1800,
        lat_p99_us: 0xFFFF_FFFF,
        jitter_us: 90,
        phone_link: Some(LinkInfo { clock_offset_us: -5, rtt_min_us: 900, send_rate_hz: 1000, rssi_dbm: -48, band: 2, link_mbps: 2400, freq_mhz: 5180, flags: 1, path: 2 }),
        any_datagram_seen: true,
        paired_phones: Arc::new(vec![
            PairedPhoneInfo { phone_id: vec![1, 2, 3, 255], name: "A".into(), last_seen_utc: "2026-11-19T20:21:18.351Z".into(), by_key: false, key_file: None, fingerprint: None },
            PairedPhoneInfo { phone_id: vec![9; 16], name: "B".into(), last_seen_utc: String::new(), by_key: true, key_file: Some(r"C:\k\b.pub".into()), fingerprint: Some("ab:cd".into()) },
        ]),
        counters: HostCounters { datagrams: 1, dropped: 2, bad_auth: 3, states: 4, injections: 5, unknown_usages: 6, errors: 7, exceptions: 8 },
        host_key_fingerprint: Some("11:22".into()),
        qr_pairing_s: 30,
        qr_enrolled_phone: Some("Q".into()),
        pc_controller: Some("Controller 2".into()),
        dual_sense_ok: true,
        capturing: true,
        phone_address: Some("192.168.137.20".into()),
    }
}

fn full_status() -> InputStatus {
    let mut settings = HostSettings::create_default("PC");
    settings.diagnostics = true;
    settings.catch_up_ms = 16;
    InputStatus {
        snapshot: full_snapshot(),
        settings: InputSettingsView::of(&settings),
        server: "Running",
        bind_retrying: true,
        bind_wait_long: false,
        key_error: Some("Other: locked".into()),
        key_retrying: true,
        pad_error: None,
        input_driver_ready: true,
        input_driver_installed: true,
        usb_enabled: true,
        usb_state: "Connected".into(),
        usb_detail: "ok".into(),
        authorized_scan: Some("1 of 1 .pub file(s) valid, 20:00:00".into()),
        send_input_short_writes: 2,
        rx_exceptions: 1,
        buttons: Some(ButtonLatency { total_us: 4000, phone_us: 1500, count: 12, last_us: 3900 }),
        elevated: false,
        keys_folder: r"C:\Users\u\AppData\Roaming\GyroPhone".into(),
        log_error: None,
        diagnostics_saved: 3,
    }
}

#[test]
fn the_snapshot_has_the_csharp_records_names() {
    let v = control::snapshot_json(&full_snapshot());
    // HostSnapshot in HostEngine.cs, with its OutputDriverOk property.
    assert_eq!(
        camel(&[
            "PhoneName", "Connected", "Silent", "OutputMode", "DriverOk", "PadPlugged", "Paused", "ElevatedForeground", "NetworkPublic",
            "PairingWindowS", "PairPrompt", "RxPerSec", "LossPermille", "LatP50Us", "LatP99Us", "JitterUs", "PhoneLink", "AnyDatagramSeen",
            "PairedPhones", "Counters", "HostKeyFingerprint", "QrPairingS", "QrEnrolledPhone", "PcController", "DualSenseOk", "Capturing",
            "PhoneAddress", "OutputDriverOk",
        ]),
        keys(&v)
    );
    assert_eq!(json!(3), v["outputMode"], "enums go as numbers");
    assert_eq!(json!(true), v["outputDriverOk"], "the DualSense's own driver counts for it");
    assert_eq!(json!({ "item1": 7, "item2": "Pixel", "item3": "111 222" }), v["pairPrompt"], "a value tuple's fields");
    assert_eq!(json!(4294967295u32), v["latP99Us"]);
    assert_eq!(camel(&["ClockOffsetUs", "RttMinUs", "SendRateHz", "RssiDbm", "Band", "LinkMbps", "FreqMhz", "Flags", "Path"]), keys(&v["phoneLink"]));
    assert_eq!(json!(-48), v["phoneLink"]["rssiDbm"]);
    assert_eq!(camel(&["Datagrams", "Dropped", "BadAuth", "States", "Injections", "UnknownUsages", "Errors", "Exceptions"]), keys(&v["counters"]));
    let phones = v["pairedPhones"].as_array().unwrap();
    assert_eq!(camel(&["PhoneId", "Name", "LastSeenUtc", "ByKey", "KeyFile", "Fingerprint"]), keys(&phones[0]));
    assert_eq!(json!("AQID/w=="), phones[0]["phoneId"], "bytes go as base64");
    assert_eq!(json!("2026-11-19T20:21:18.351Z"), phones[0]["lastSeenUtc"]);
    assert_eq!(Value::Null, phones[0]["keyFile"]);
    assert_eq!(json!("0001-01-01T00:00:00"), phones[1]["lastSeenUtc"], "never seen is DateTime.MinValue, never an empty text");
    assert_eq!(json!(r"C:\k\b.pub"), phones[1]["keyFile"]);

    let empty = HostSnapshot { phone_name: None, pair_prompt: None, phone_link: None, output_mode: 1, ..full_snapshot() };
    let v = control::snapshot_json(&empty);
    assert_eq!((Value::Null, Value::Null, Value::Null), (v["phoneName"].clone(), v["pairPrompt"].clone(), v["phoneLink"].clone()));
    assert_eq!(json!(false), v["outputDriverOk"]);
}

#[test]
fn the_status_and_hello_have_the_csharp_records_names() {
    let status = full_status();
    let v = control::status_json(&status);
    // InputStatus and InputSettingsView in InputControl.cs.
    assert_eq!(
        camel(&[
            "Snapshot", "Settings", "Server", "BindRetrying", "BindWaitLong", "KeyError", "KeyRetrying", "PadError", "InputDriverReady",
            "InputDriverInstalled", "UsbEnabled", "UsbState", "UsbDetail", "AuthorizedScan", "SendInputShortWrites", "RxExceptions", "Buttons",
            "Elevated", "KeysFolder", "LogError", "DiagnosticsSaved",
        ]),
        keys(&v)
    );
    assert_eq!(
        camel(&[
            "HostName", "OutputMode", "StartWithWindows", "UsbDirect", "MeasureButtons", "Diagnostics", "DiagnosticsMinutes", "UseInputDriver",
            "PressKeyListen", "PressKeyNotice", "CatchUpMs",
        ]),
        keys(&v["settings"])
    );
    assert_eq!(json!({ "totalUs": 4000, "phoneUs": 1500, "count": 12, "lastUs": 3900 }), v["buttons"]);
    assert_eq!((json!("Running"), json!("PC"), json!(16), Value::Null), (v["server"].clone(), v["settings"]["hostName"].clone(), v["settings"]["catchUpMs"].clone(), v["padError"].clone()));
    assert_eq!(Value::Null, control::status_json(&InputStatus { buttons: None, ..status.clone() })["buttons"]);

    let hello = InputHello { version: "0.1.900".into(), pid: 4242, shared_name: control::names::REGION.into(), layout_version: 2, overlay_wake_name: control::names::OVERLAY_WAKE.into(), status };
    let h = control::hello_json(&hello);
    assert_eq!(camel(&["Version", "Pid", "SharedName", "LayoutVersion", "OverlayWakeName", "Status"]), keys(&h));
    assert_eq!(json!(r"Local\GyroPhoneShared"), h["sharedName"]);
    assert_eq!(v, h["status"]);
    // DeliveryNumbers in DeliveryMatch.cs, with its SlowPer1000 property.
    let d = control::delivery_json(&DeliveryNumbers { n: 2000, p50: 0.4, p99: 1.9, max: 6.0, slow: 3, unmatched: 1, merged: 2, stray: 0 });
    assert_eq!(camel(&["N", "P50", "P99", "Max", "Slow", "Unmatched", "Merged", "Stray", "SlowPer1000"]), keys(&d));
    assert_eq!(json!(1.5), d["slowPer1000"]);
    assert_eq!("GyroPhoneInput.olex.3", control::names::pipe("olex", 3));
}

// =====================================================================================
// the companion's calls
// =====================================================================================

/// Notes every call it gets.
#[derive(Default)]
struct Recording(Mutex<Vec<String>>);

impl Recording {
    fn note(&self, what: String) {
        self.0.lock().unwrap().push(what);
    }
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut *self.0.lock().unwrap())
    }
}

impl InputControl for Recording {
    fn attach(&self, companion_pid: i32) -> InputHello {
        self.note(format!("attach {companion_pid}"));
        InputHello { version: "v".into(), pid: 1, shared_name: "s".into(), layout_version: 2, overlay_wake_name: "w".into(), status: full_status() }
    }
    fn snapshot(&self) -> HostSnapshot {
        self.note("snapshot".into());
        full_snapshot()
    }
    fn exit(&self) {
        self.note("exit".into());
    }
    fn set_output_mode(&self, mode: u8) {
        self.note(format!("mode {mode}"));
    }
    fn set_paused(&self, paused: bool) {
        self.note(format!("paused {paused}"));
    }
    fn set_locked(&self, locked: bool) {
        self.note(format!("locked {locked}"));
    }
    fn retry_driver(&self) -> String {
        self.note("retry".into());
        "ViGEmBus not found".into()
    }
    fn set_catch_up_ms(&self, ms: i32) {
        self.note(format!("catch-up {ms}"));
    }
    fn set_use_input_driver(&self, on: bool) {
        self.note(format!("input driver {on}"));
    }
    fn open_pairing_window(&self) {
        self.note("open pairing".into());
    }
    fn answer_pair(&self, attempt_id: u32, allow: bool) {
        self.note(format!("answer {attempt_id} {allow}"));
    }
    fn open_qr_pairing(&self) -> Option<Vec<u8>> {
        self.note("open qr".into());
        Some(vec![0xFB, 0xFF, 0x00])
    }
    fn close_qr_pairing(&self) {
        self.note("close qr".into());
    }
    fn host_public_key(&self) -> Option<Vec<u8>> {
        self.note("host key".into());
        None
    }
    fn forget(&self, phone: &PairedPhoneInfo) {
        self.note(format!("forget {phone:?}"));
    }
    fn set_usb_direct(&self, on: bool) {
        self.note(format!("usb {on}"));
    }
    fn take_usb_from_adb(&self) {
        self.note("take usb".into());
    }
    fn set_start_with_windows(&self, on: bool) {
        self.note(format!("start with windows {on}"));
    }
    fn set_diagnostics(&self, on: bool) {
        self.note(format!("diagnostics {on}"));
    }
    fn set_diagnostics_minutes(&self, minutes: i32) {
        self.note(format!("minutes {minutes}"));
    }
    fn save_diagnostics(&self) {
        self.note("save".into());
    }
    fn log_file_access(&self) -> Option<(u32, [u8; 16], std::net::IpAddr)> {
        self.note("log-file".into());
        Some((7, [9; 16], std::net::IpAddr::from([192, 168, 1, 33])))
    }
    fn set_press_key_listen(&self, on: bool) {
        self.note(format!("listen {on}"));
    }
    fn set_press_key_notice(&self, on: bool) {
        self.note(format!("notice {on}"));
    }
    fn set_measure_buttons(&self, on: bool) {
        self.note(format!("measure {on}"));
    }
    fn set_link_watch(&self, on: bool) {
        self.note(format!("link watch {on}"));
    }
    fn set_delivery_test(&self, on: bool, counting: bool) {
        self.note(format!("delivery {on} {counting}"));
    }
    fn delivery_test_numbers(&self) -> Option<DeliveryNumbers> {
        self.note("numbers".into());
        None
    }
    fn set_overlay(&self, on: bool, compass: bool, samples: bool, keys: bool) {
        self.note(format!("overlay {on} {compass} {samples} {keys}"));
    }
}

fn one(c: &Recording, method: &str, args: Value) -> (Result<Value, String>, Vec<String>) {
    let r = control::dispatch(c, method, args.as_array().unwrap());
    (r, c.take())
}

#[test]
fn every_call_of_the_companion_reaches_its_method() {
    let c = Recording::default();
    let ok = |method: &str, args: Value, result: Value, noted: &str| {
        let (r, calls) = one(&c, method, args);
        assert_eq!(Ok(result), r, "{method}");
        assert_eq!(vec![noted.to_string()], calls, "{method}");
    };
    ok("Exit", json!([]), Value::Null, "exit");
    ok("SetOutputMode", json!([2]), Value::Null, "mode 2");
    ok("SetPaused", json!([true]), Value::Null, "paused true");
    ok("SetLocked", json!([true]), Value::Null, "locked true");
    ok("RetryDriver", json!([]), json!("ViGEmBus not found"), "retry");
    ok("SetCatchUpMs", json!([16]), Value::Null, "catch-up 16");
    ok("SetUseInputDriver", json!([true]), Value::Null, "input driver true");
    ok("OpenPairingWindow", json!([]), Value::Null, "open pairing");
    ok("AnswerPair", json!([4_000_000_000u32, true]), Value::Null, "answer 4000000000 true");
    ok("OpenQrPairing", json!([]), json!("+/8A"), "open qr");
    ok("CloseQrPairing", json!([]), Value::Null, "close qr");
    ok("HostPublicKey", json!([]), Value::Null, "host key");
    ok("SetUsbDirect", json!([true]), Value::Null, "usb true");
    ok("TakeUsbFromAdb", json!([]), Value::Null, "take usb");
    ok("SetStartWithWindows", json!([true]), Value::Null, "start with windows true");
    ok("SetDiagnostics", json!([true]), Value::Null, "diagnostics true");
    ok("SetDiagnosticsMinutes", json!([30]), Value::Null, "minutes 30");
    ok("SaveDiagnostics", json!([]), Value::Null, "save");
    ok("LogFileAccess", json!([]), json!({ "sessionId": 7, "key": "CQkJCQkJCQkJCQkJCQkJCQ==", "address": "192.168.1.33" }), "log-file");
    ok("SetPressKeyListen", json!([true]), Value::Null, "listen true");
    ok("SetPressKeyNotice", json!([false]), Value::Null, "notice false");
    ok("SetMeasureButtons", json!([true]), Value::Null, "measure true");
    ok("SetLinkWatch", json!([true]), Value::Null, "link watch true");
    ok("SetDeliveryTest", json!([true, false]), Value::Null, "delivery true false");
    ok("DeliveryTestNumbers", json!([]), Value::Null, "numbers");
    ok("SetOverlay", json!([true, false, true, true]), Value::Null, "overlay true false true true");
    // SetOverlay's last argument has a default in C#; an older companion leaves it out.
    ok("SetOverlay", json!([true, true, false]), Value::Null, "overlay true true false false");

    let (r, calls) = one(&c, "Attach", json!([777]));
    assert_eq!(vec!["attach 777".to_string()], calls);
    assert_eq!(json!("v"), r.unwrap()["version"]);
    let (r, calls) = one(&c, "Snapshot", json!([]));
    assert_eq!(vec!["snapshot".to_string()], calls);
    assert_eq!(control::snapshot_json(&full_snapshot()), r.unwrap());

    // Forget takes the phone as the snapshot gave it (the companion sends the record back).
    let phones = control::snapshot_json(&full_snapshot())["pairedPhones"].clone();
    let (r, calls) = one(&c, "Forget", json!([phones[1]]));
    assert_eq!(Ok(Value::Null), r);
    assert_eq!(vec![format!("forget {:?}", PairedPhoneInfo { last_seen_utc: "0001-01-01T00:00:00".into(), ..full_snapshot().paired_phones[1].clone() })], calls);
    // Names in any letter case, as System.Text.Json reads them.
    let (_, calls) = one(&c, "Forget", json!([{ "PhoneId": "AQI=", "NAME": "n", "bykey": true, "KeyFile": "f" }]));
    assert!(calls[0].contains("phone_id: [1, 2]") && calls[0].contains("by_key: true") && calls[0].contains("key_file: Some(\"f\")"), "{calls:?}");

    // Every method named is answered (none falls through to "not found").
    for m in control::METHODS {
        let r = control::dispatch(&c, m, &[]);
        assert!(!r.as_ref().is_err_and(|e| e.starts_with("MissingMethodException")), "{m}: {r:?}");
    }
    c.take();
}

#[test]
fn a_missing_argument_takes_its_default_and_a_wrong_one_fails_the_call() {
    let c = Recording::default();
    assert_eq!((Ok(Value::Null), vec!["paused false".to_string()]), one(&c, "SetPaused", json!([])));
    assert_eq!((Ok(Value::Null), vec!["catch-up 0".to_string()]), one(&c, "SetCatchUpMs", json!([])));
    assert_eq!((Ok(Value::Null), vec!["answer 5 false".to_string()]), one(&c, "AnswerPair", json!([5])));
    // More arguments than the method takes are left alone.
    assert_eq!((Ok(Value::Null), vec!["paused true".to_string()]), one(&c, "SetPaused", json!([true, 1, "x"])));

    let fails = |method: &str, args: Value| {
        let (r, calls) = one(&c, method, args.clone());
        assert!(r.is_err(), "{method} {args}: {r:?}");
        assert!(calls.is_empty(), "{method} {args}: called anyway");
    };
    fails("SetPaused", json!([null]));
    fails("SetPaused", json!([1]));
    fails("SetPaused", json!(["true"]));
    fails("SetCatchUpMs", json!([1.5]));
    fails("SetCatchUpMs", json!([2147483648u32]));
    fails("SetCatchUpMs", json!(["8"]));
    fails("AnswerPair", json!([-1, true]));
    fails("AnswerPair", json!([4294967296u64, true]));
    fails("AnswerPair", json!([1, "yes"]));
    // An output mode that doesn't exist is refused rather than handed to the engine.
    fails("SetOutputMode", json!([4]));
    fails("SetOutputMode", json!([-1]));
    fails("SetOutputMode", json!(["Xbox360"]));
    fails("SetOverlay", json!([true, true, 0]));
    fails("Forget", json!([]));
    fails("Forget", json!([null]));
    fails("Forget", json!([{ "phoneId": "not base64!" }]));
    fails("Forget", json!([{ "phoneId": "AQI=", "byKey": "yes" }]));
    fails("Forget", json!([{ "phoneId": "AQI=", "keyFile": 5 }]));
    let (r, _) = one(&c, "Format", json!([]));
    assert_eq!(Err("MissingMethodException: Method 'IInputControl.Format' not found.".to_string()), r);
    // The method's name is exact, as the companion's proxy sends it.
    assert!(one(&c, "setpaused", json!([true])).0.is_err());
}
