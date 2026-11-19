//! Sessions, pairing, link statistics and the rate limiter on their own. The same behaviour seen
//! through the engine (SessionManagerTests, PairingManagerTests) is in `engine.rs`.

mod common;

use std::net::SocketAddr;
use std::sync::Mutex;

use common::phone::FakePhone;
use common::{range_n, rng};
use gp_input::codec::{self, LinkInfo};
use gp_input::crypto::P256;
use gp_input::link_stats::{self, LinkStats};
use gp_input::pairing::{PairEvent, PairingManager, REVEAL_TIMEOUT_US};
use gp_input::rate_limiter::{ipv4_key, RateLimiter};
use gp_input::session::{system_random, DatagramSender, SessionManager, SEEN_NP_MAX};
use gp_input::wire::{self, pair_ack, pair_done};

const HOST_ID: [u8; 16] = [0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F];
const HOST_NAME: &str = "DESKTOP-OLEX";

#[derive(Default)]
struct FakeSender {
    sent: Mutex<Vec<(Vec<u8>, SocketAddr)>>,
}

impl DatagramSender for FakeSender {
    fn send(&self, datagram: &[u8], to: SocketAddr) {
        self.sent.lock().unwrap().push((datagram.to_vec(), to));
    }
}

impl FakeSender {
    fn take(&self) -> Vec<(Vec<u8>, SocketAddr)> {
        std::mem::take(&mut self.sent.lock().unwrap())
    }
}

fn phone1() -> FakePhone {
    FakePhone::paired(range_n(0x00), "OnePlus 15", range_n(0x40), "192.168.1.50:40000")
}

/// HELLO to a pending session; the phone takes the WELCOME. Returns the session id.
fn hello(m: &mut SessionManager, phone: &mut FakePhone, now_us: i64) -> Option<u32> {
    let h = phone.hello(None);
    let hello = codec::decode_hello(&h).expect("hello");
    let key = phone.pair_key.unwrap();
    assert!(codec::verify_mac(&key, &h));
    let sid = m.create_pending(&hello, &phone.name, &key, &HOST_ID, wire::output_mode::XBOX360, 0, phone.addr, now_us)?;
    let welcome = m.find(sid).unwrap().welcome;
    assert!(phone.on_welcome(&welcome));
    Some(sid)
}

// ---- sessions --------------------------------------------------------------------------------

#[test]
fn hello_makes_a_pending_session_both_sides_can_talk_over() {
    let mut m = SessionManager::new(system_random());
    let mut phone = phone1();
    let sid = hello(&mut m, &mut phone, 1000).unwrap();
    assert_ne!(sid, 0);
    assert_eq!(phone.session_id, sid);
    assert!(m.active().is_none());
    assert_eq!(m.pending().len(), 1);

    // Phone to host: opens once, in order only.
    let s1 = phone.state(|f| f.lx = 1);
    let s2 = phone.state(|f| f.lx = 2);
    let mut plain = [0u8; wire::MAX_DATAGRAM];
    let s = m.find_mut(sid).unwrap();
    assert_eq!(s.try_open(&s2, &mut plain), Some(wire::STATE_PLAIN));
    assert_eq!(s.highest_rx_seq(), 2);
    assert_eq!(s.try_open(&s1, &mut plain), None); // older than the highest
    assert_eq!(s.try_open(&s2, &mut plain), None); // a replay
    // A forged packet with a higher seq does not move the highest.
    let mut forged = s2.clone();
    forged[8] = 9;
    assert_eq!(s.try_open(&forged, &mut plain), None);
    assert_eq!(s.highest_rx_seq(), 2);
    assert_eq!(s.try_open(&[0u8; 5], &mut plain), None);

    // Host to phone: seq counts up from 1 and the phone can open each.
    let out = FakeSender::default();
    s.send_sealed(wire::packet::HOST_STATUS, &[0u8; wire::HOST_STATUS_PLAIN], Some(&out));
    s.send_sealed(wire::packet::HOST_STATUS, &[0u8; wire::HOST_STATUS_PLAIN], Some(&out));
    s.send_sealed(wire::packet::HOST_STATUS, &[0u8; wire::HOST_STATUS_PLAIN], None);
    let sent = out.take();
    assert_eq!(sent.len(), 2);
    for (i, (d, to)) in sent.iter().enumerate() {
        assert_eq!(*to, phone.addr);
        assert_eq!(phone.try_open(d).map(|(t, _)| t), Some(wire::packet::HOST_STATUS));
        assert_eq!(phone.rx_highest, i as u32 + 1);
    }
}

#[test]
fn promotion_replaces_the_active_session_and_a_dropped_one_sends_nothing() {
    let mut m = SessionManager::new(system_random());
    let mut phone = phone1();
    let first = hello(&mut m, &mut phone, 0).unwrap();
    assert!(m.promote(first).is_none());
    assert!(m.is_active(first));
    assert!(m.active().unwrap().promoted);
    assert!(m.pending().is_empty());
    assert!(m.promote(first).is_none()); // not pending any more: nothing changes
    assert!(m.is_active(first));

    let second = hello(&mut m, &mut phone, 10).unwrap();
    assert_ne!(first, second);
    assert!(m.is_active(first)); // a HELLO alone never replaces the active session
    let old = m.promote(second).expect("replaced session");
    assert_eq!(old.session_id, first);
    assert!(m.is_active(second));

    // The replaced session can still send its goodbye; once dropped, its sender is dead.
    let out = FakeSender::default();
    let handle = old.sender().clone();
    handle.send_sealed(wire::packet::HOST_BYE, &[0u8; 4], Some(&out));
    assert_eq!(out.take().len(), 1);
    drop(old);
    handle.send_sealed(wire::packet::HOST_BYE, &[0u8; 4], Some(&out));
    handle.send_raw(&[1, 2, 3], Some(&out));
    assert!(out.take().is_empty());

    m.remove(second);
    assert!(m.active().is_none());
}

#[test]
fn a_new_hello_replaces_that_phones_pending_session_only() {
    let mut m = SessionManager::new(system_random());
    let mut phone = phone1();
    let mut other = FakePhone::paired(range_n(0x80), "Other", range_n(0x41), "192.168.1.51:40000");
    let a = hello(&mut m, &mut phone, 0).unwrap();
    let b = hello(&mut m, &mut other, 0).unwrap();
    let c = hello(&mut m, &mut phone, 1).unwrap();
    assert!(m.find(a).is_none());
    assert!(m.find(b).is_some());
    assert!(m.find(c).is_some());
    assert_eq!(m.pending().len(), 2);
    assert_eq!(m.find_by_hello(&phone.phone_id, &phone.hello_nonce).map(|s| s.session_id), Some(c));
    assert!(m.find_by_hello(&other.phone_id, &phone.hello_nonce).is_none());
}

#[test]
fn a_replayed_hello_never_replaces_a_newer_pending_session() {
    let mut m = SessionManager::new(system_random());
    let mut phone = phone1();
    let key = phone.pair_key.unwrap();
    let old_hello = codec::decode_hello(&phone.hello(None)).unwrap();
    let make = |m: &mut SessionManager, h| m.create_pending(h, "p", &key, &HOST_ID, 1, 0, "10.0.0.9:1".parse().unwrap(), 0);
    let first = make(&mut m, &old_hello).unwrap();
    let new_hello = codec::decode_hello(&phone.hello(None)).unwrap();
    let second = make(&mut m, &new_hello).unwrap();
    assert!(m.find(first).is_none());
    // The captured old HELLO again: refused, the newer pending session stays.
    assert_eq!(make(&mut m, &old_hello), None);
    assert!(m.find(second).is_some());
    // With nothing newer pending (it expired), the same old HELLO gets a session again.
    assert_eq!(m.expire_pending(wire::PENDING_SESSION_MS * 1000), 0);
    assert_eq!(m.expire_pending(wire::PENDING_SESSION_MS * 1000 + 1), 1);
    assert!(make(&mut m, &old_hello).is_some());
}

#[test]
fn the_seen_nonce_table_stays_bounded() {
    let mut next = rng(7);
    let mut m = SessionManager::new(system_random());
    let key = [7u8; 32];
    let first = codec::Hello { phone_id: [1; 16], n_p: [0xAA; 16], caps: 0, max_rate_hz: 0 };
    let from: SocketAddr = "10.0.0.9:1".parse().unwrap();
    m.create_pending(&first, "p", &key, &HOST_ID, 1, 0, from, 0).unwrap();
    for _ in 0..SEEN_NP_MAX + 10 {
        let mut n_p = [0u8; 16];
        n_p[..8].copy_from_slice(&next().to_le_bytes());
        n_p[8..].copy_from_slice(&next().to_le_bytes());
        m.create_pending(&codec::Hello { phone_id: [1; 16], n_p, caps: 0, max_rate_hz: 0 }, "p", &key, &HOST_ID, 1, 0, from, 0)
            .unwrap();
        assert_eq!(m.pending().len(), 1);
    }
    // The first nonce fell out of the table, so it counts as new again (and is accepted).
    assert!(m.create_pending(&first, "p", &key, &HOST_ID, 1, 0, from, 0).is_some());
}

#[test]
fn session_ids_are_never_zero_and_never_repeat_among_live_sessions() {
    // The generator hands out 0, then the id already in use, before a fresh one.
    let script: Vec<Vec<u8>> = vec![
        vec![5, 0, 0, 0],
        vec![9; 16],
        vec![0, 0, 0, 0],
        vec![5, 0, 0, 0],
        vec![6, 0, 0, 0],
        vec![8; 16],
    ];
    let mut at = 0;
    let mut m = SessionManager::new(Box::new(move |out: &mut [u8]| {
        out.copy_from_slice(&script[at]);
        at += 1;
    }));
    let key = [7u8; 32];
    let from: SocketAddr = "10.0.0.9:1".parse().unwrap();
    let a = m.create_pending(&codec::Hello { phone_id: [1; 16], n_p: [1; 16], caps: 0, max_rate_hz: 0 }, "a", &key, &HOST_ID, 1, 0, from, 0);
    let b = m.create_pending(&codec::Hello { phone_id: [2; 16], n_p: [2; 16], caps: 0, max_rate_hz: 0 }, "b", &key, &HOST_ID, 1, 0, from, 0);
    assert_eq!((a, b), (Some(5), Some(6)));
}

#[test]
fn the_endpoint_follows_the_phone() {
    let mut m = SessionManager::new(system_random());
    let mut phone = phone1();
    let sid = hello(&mut m, &mut phone, 0).unwrap();
    let s = m.find_mut(sid).unwrap();
    let moved: SocketAddr = "192.168.1.77:41000".parse().unwrap();
    assert!(s.is_endpoint(phone.addr) && !s.is_endpoint(moved));
    s.update_endpoint(moved);
    assert!(s.is_endpoint(moved));
    assert_eq!(s.sender().endpoint(), moved);
    let out = FakeSender::default();
    s.send_raw(&[1], Some(&out));
    assert_eq!(out.take()[0].1, moved);
}

// ---- link statistics -------------------------------------------------------------------------

#[test]
fn latency_unwraps_phone_time() {
    // phone clock = host - 5000 us; packet took 1500 us; phone 32-bit time wrapped.
    let offset = 5000;
    let host = 0x1_0000_0100i64 + offset + 1500;
    assert_eq!(link_stats::latency_us(0x100, host, offset), 1500);
    assert_eq!(link_stats::latency_us(0xFFFF_FF00, 0xFFFF_FF00i64 + offset + 1500, offset), 1500);
    // Any values at all: no panic.
    for &h in &[i64::MIN, -1, 0, i64::MAX] {
        for &o in &[i64::MIN, -1, 0, i64::MAX] {
            link_stats::latency_us(u32::MAX, h, o);
        }
    }
}

#[test]
fn stats_window() {
    let mut s = LinkStats::new();
    assert_eq!(s.on_state(0, 0), None); // no clock offset yet
    s.reset();
    s.on_link_info(&LinkInfo { send_rate_hz: 1000, band: 5, ..Default::default() });
    let mut seq = 0u32;
    for i in 0..100 {
        seq += if i % 10 == 9 { 2 } else { 1 }; // one gap per ten
        s.on_sealed(seq);
        assert_eq!(s.on_state(seq * 1000, seq as i64 * 1000 + 2000), Some(2000));
    }
    assert_eq!(s.last_seq(), seq);
    assert_eq!((s.total_lost, s.total_states), (10, 100));
    s.roll();
    assert_eq!(s.rx_per_sec, 100);
    assert!((80..=100).contains(&s.loss_permille), "{}", s.loss_permille);
    assert_eq!(s.lat_p50_us, 2025);
    assert_eq!(s.lat_p99_us, 2025);
    assert!(s.jitter_us > 0);
    s.roll();
    assert_eq!(s.lat_p50_us, wire::STATUS_UNKNOWN);
    assert_eq!((s.rx_per_sec, s.loss_permille, s.jitter_us), (0, 0, 0));
}

#[test]
fn stats_take_any_seq_and_times() {
    let mut next = rng(99);
    let mut s = LinkStats::new();
    s.on_link_info(&LinkInfo { clock_offset_us: i64::MIN, ..Default::default() });
    for i in 0..50_000 {
        s.on_sealed(next() as u32);
        s.on_state(next() as u32, next() as i64);
        if i % 1000 == 0 {
            s.roll();
            assert!(s.loss_permille <= 1000);
        }
    }
    s.on_sealed(u32::MAX);
    s.on_sealed(0);
    s.on_sealed(u32::MAX);
    s.roll();
}

// ---- rate limiter ----------------------------------------------------------------------------

#[test]
fn rate_limiter_windows() {
    let mut r = RateLimiter::new(2, 3);
    assert!(r.try_acquire(1, 0));
    assert!(r.try_acquire(1, 10));
    assert!(!r.try_acquire(1, 20)); // per address
    assert!(r.try_acquire(2, 30));
    assert!(!r.try_acquire(3, 40)); // in total
    assert!(!r.try_acquire(3, 999_999));
    assert!(r.try_acquire(3, 1_000_000)); // next window
    // A clock that jumps back opens a new window instead of shutting the limiter.
    assert!(r.try_acquire(3, 5));
    assert!(r.try_acquire(3, 6));
    assert!(!r.try_acquire(3, 7));

    let mut many = RateLimiter::new(1, 5000);
    let mut ok = 0;
    for ip in 0..3000 {
        if many.try_acquire(ip, 0) {
            ok += 1;
        }
    }
    assert_eq!(ok, 1024); // bounded table
    assert_eq!(ipv4_key("1.2.3.4:5".parse().unwrap()), u32::from_le_bytes([1, 2, 3, 4]));
    assert_eq!(ipv4_key("[::1]:5".parse().unwrap()), 0);
}

// ---- pairing ---------------------------------------------------------------------------------

struct Pairing {
    m: PairingManager,
    now: i64,
    shown: Vec<(u32, String, String)>,
    closed: Vec<u32>,
    paired: Vec<([u8; 16], String, [u8; 32])>,
}

impl Pairing {
    fn new(open: bool) -> Pairing {
        let mut m = PairingManager::new(system_random(), Box::new(P256::generate));
        let now = 1_000_000_000;
        if open {
            m.open(now);
        }
        Pairing { m, now, shown: vec![], closed: vec![], paired: vec![] }
    }

    fn drain(&mut self) {
        for e in self.m.drain_events() {
            match e {
                PairEvent::Show { attempt_id, phone_name, sas } => self.shown.push((attempt_id, phone_name, sas)),
                PairEvent::Close { attempt_id } => self.closed.push(attempt_id),
                PairEvent::Paired { phone_id, phone_name, pair_key } => self.paired.push((phone_id, phone_name, *pair_key)),
            }
        }
    }

    /// Delivers one pairing datagram; None when the host stays silent.
    fn send(&mut self, phone: &FakePhone, d: &[u8]) -> Option<Vec<u8>> {
        let mut reply = [0u8; wire::MAX_DATAGRAM];
        let n = match d[3] {
            wire::packet::PAIR_START => {
                let m = codec::decode_pair_start(d).expect("start");
                self.m.on_start(&m, &HOST_ID, HOST_NAME, self.now, &mut reply, ipv4_key(phone.addr))
            }
            wire::packet::PAIR_REVEAL => self.m.on_reveal(&codec::decode_pair_reveal(d).expect("reveal"), self.now, &mut reply),
            wire::packet::PAIR_CONFIRM => self.m.on_confirm(&codec::decode_pair_confirm(d).expect("confirm"), self.now, &mut reply),
            t => panic!("not a pairing packet {t:#x}"),
        };
        self.drain();
        (n > 0).then(|| reply[..n].to_vec())
    }

    /// A fresh PAIR_START from the phone (new key and commit).
    fn start(&mut self, phone: &mut FakePhone) -> Option<Vec<u8>> {
        let s = phone.pair_start();
        self.send(phone, &s)
    }

    fn answer(&mut self, attempt_id: u32, allow: bool) -> bool {
        let stored = self.m.answer(attempt_id, allow, self.now);
        self.drain();
        stored
    }

    fn advance_ms(&mut self, ms: i64) {
        self.now += ms * 1000;
        self.m.tick(self.now);
        self.drain();
    }

    fn start_and_reveal(&mut self, phone: &mut FakePhone) {
        let start = phone.pair_start();
        assert!(phone.on_pair_host(&self.send(phone, &start).expect("PAIR_HOST")));
        assert_eq!(ack(self.send(phone, &phone.pair_reveal(None))).status, pair_ack::OK);
    }
}

fn new_phone(seed: u8, ip: &str) -> FakePhone {
    FakePhone::new(range_n(seed), "OnePlus 15", &format!("{ip}:40000"))
}

fn phone60() -> FakePhone {
    new_phone(0x60, "192.168.1.60")
}

fn ack(d: Option<Vec<u8>>) -> codec::PairAck {
    codec::decode_pair_ack(&d.expect("a reply")).expect("PAIR_ACK")
}

fn done(d: Option<Vec<u8>>) -> codec::PairDone {
    codec::decode_pair_done(&d.expect("a reply")).expect("PAIR_DONE")
}

#[test]
fn closed_window_gives_closed() {
    let mut h = Pairing::new(false);
    let mut phone = phone60();
    let a = ack(h.start(&mut phone));
    assert_eq!((a.status, a.attempt_id), (pair_ack::CLOSED, 0));
    assert!(!h.m.busy(h.now));
}

#[test]
fn full_flow_allow_then_confirm_stores_pairing() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    assert_eq!(h.shown.len(), 1);
    assert_eq!(h.shown[0], (phone.attempt_id, "OnePlus 15".to_owned(), phone.sas.clone().unwrap()));
    assert_eq!(h.m.prompt_info().map(|p| p.0), Some(phone.attempt_id));

    assert!(!h.answer(phone.attempt_id, true));
    assert!(h.paired.is_empty()); // Allow alone does not store
    assert!(h.m.prompt_info().is_none());

    let (ok, status) = phone.on_pair_done(&h.send(&phone, &phone.pair_confirm(false)).unwrap());
    assert!(ok);
    assert_eq!(status, pair_done::ACCEPTED);
    assert_eq!(h.paired.len(), 1);
    assert_eq!(h.paired[0].0, phone.phone_id);
    assert_eq!(Some(h.paired[0].2), phone.pair_key);
    assert_eq!(h.closed, vec![phone.attempt_id]);
    // Success closes the window.
    assert!(!h.m.is_open(h.now));
    assert_eq!(h.m.window_remaining_s(h.now), 0);
}

#[test]
fn confirm_before_allow_is_pending_then_accepted() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    assert_eq!(done(h.send(&phone, &phone.pair_confirm(false))).status, pair_done::PENDING);
    assert_eq!(done(h.send(&phone, &phone.pair_confirm(false))).status, pair_done::PENDING);
    assert!(h.paired.is_empty());

    assert!(h.answer(phone.attempt_id, true)); // valid confirmP already seen + Allow
    assert_eq!(h.paired.len(), 1);
    assert!(phone.on_pair_done(&h.send(&phone, &phone.pair_confirm(false)).unwrap()).0);
    // Retransmitted CONFIRM after completion: same cached DONE.
    assert!(phone.on_pair_done(&h.send(&phone, &phone.pair_confirm(false)).unwrap()).0);
    assert_eq!(h.paired.len(), 1);
}

#[test]
fn allow_with_bad_confirm_does_not_store() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    h.answer(phone.attempt_id, true);
    let d = done(h.send(&phone, &phone.pair_confirm(true)));
    assert_eq!(d.status, pair_done::BAD_CONFIRM);
    assert_eq!(d.confirm_h, [0u8; 32]);
    assert!(h.paired.is_empty());
    assert_eq!(h.m.failures(), 1);
}

#[test]
fn deny_gives_denied_and_cooldown() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    assert!(!h.answer(phone.attempt_id, false));
    assert!(h.closed.contains(&phone.attempt_id));
    assert_eq!(done(h.send(&phone, &phone.pair_confirm(false))).status, pair_done::DENIED);
    assert!(h.paired.is_empty());

    let mut again = phone60();
    assert_eq!(ack(h.start(&mut again)).status, pair_ack::COOLDOWN);
    h.advance_ms(wire::PAIR_COOLDOWN_MS + 1);
    let start = again.pair_start();
    assert!(again.on_pair_host(&h.send(&again, &start).unwrap()));
}

#[test]
fn prompt_times_out() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    h.advance_ms(wire::PAIR_USER_TIMEOUT_S * 1000 + 10);
    assert_eq!(done(h.send(&phone, &phone.pair_confirm(false))).status, pair_done::TIMEOUT);
    assert_eq!(h.closed, vec![phone.attempt_id]);
    // A late answer for it changes nothing.
    assert!(!h.answer(phone.attempt_id, true));
    assert!(h.paired.is_empty());
}

#[test]
fn second_phone_is_busy() {
    let mut h = Pairing::new(true);
    let mut first = phone60();
    h.start_and_reveal(&mut first);
    let mut second = new_phone(0x70, "192.168.1.70");
    assert_eq!(ack(h.start(&mut second)).status, pair_ack::BUSY);
}

#[test]
fn an_attempt_that_never_reveals_does_not_hold_the_pc_for_the_whole_window() {
    let mut h = Pairing::new(true);
    let mut squatter = new_phone(0x50, "192.168.1.50");
    let s = squatter.pair_start();
    assert!(squatter.on_pair_host(&h.send(&squatter, &s).unwrap()));
    // Meanwhile another phone hears nothing (so it keeps resending) rather than a final BUSY.
    let mut phone = phone60();
    let start = phone.pair_start();
    assert_eq!(h.send(&phone, &start), None);
    h.now += REVEAL_TIMEOUT_US;
    assert!(phone.on_pair_host(&h.send(&phone, &start).unwrap()));
    assert_eq!(ack(h.send(&phone, &phone.pair_reveal(None))).status, pair_ack::OK);
    assert_eq!(h.shown.len(), 1);
    assert_eq!(h.shown[0].0, phone.attempt_id);
}

#[test]
fn restarting_unrevealed_attempts_do_not_hold_the_pc_past_the_first_deadline() {
    let mut h = Pairing::new(true);
    let mut squatter = new_phone(0x50, "192.168.1.50");
    let s = squatter.pair_start();
    assert!(squatter.on_pair_host(&h.send(&squatter, &s).unwrap()));
    let mut phone = phone60();
    let start = phone.pair_start();
    // The squatter keeps starting over with new commits, never revealing.
    for _ in 0..3 {
        h.now += REVEAL_TIMEOUT_US / 4;
        let s = squatter.pair_start();
        assert!(h.send(&squatter, &s).is_some());
        assert_eq!(h.send(&phone, &start), None);
    }
    h.now += REVEAL_TIMEOUT_US / 4;
    assert!(phone.on_pair_host(&h.send(&phone, &start).unwrap()));
    assert_eq!(ack(h.send(&phone, &phone.pair_reveal(None))).status, pair_ack::OK);
}

#[test]
fn flooded_starts_generate_few_keys() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    let mut replies = 0;
    for _ in 0..50 {
        let s = phone.pair_start();
        if h.send(&phone, &s).is_some() {
            replies += 1;
        }
    }
    assert_eq!(replies, 2); // the rest are dropped before any key generation
    h.now += 1_000_000;
    let s = phone.pair_start();
    assert!(h.send(&phone, &s).is_some());
}

#[test]
fn same_phone_restart_replaces_its_own_attempt() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    let old_id = phone.attempt_id;
    let start = phone.pair_start(); // cancelled on the phone, started again: new key and commit
    assert!(phone.on_pair_host(&h.send(&phone, &start).unwrap()));
    assert_ne!(phone.attempt_id, old_id);
    assert!(h.closed.contains(&old_id));
    assert_eq!(ack(h.send(&phone, &phone.pair_reveal(None))).status, pair_ack::OK);
    assert_eq!(h.shown.last().unwrap().0, phone.attempt_id);

    // A repeat of the new START gets the same PAIR_HOST; another phone is still busy.
    let again = codec::decode_pair_host(&h.send(&phone, &start).unwrap()).unwrap();
    assert_eq!(again.attempt_id, phone.attempt_id);
    let mut second = new_phone(0x70, "192.168.1.70");
    assert_eq!(ack(h.start(&mut second)).status, pair_ack::BUSY);

    h.answer(phone.attempt_id, true);
    assert!(phone.on_pair_done(&h.send(&phone, &phone.pair_confirm(false)).unwrap()).0);
}

#[test]
fn swapping_a_revealed_attempt_three_times_closes_the_window() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    for i in 0..wire::PAIR_MAX_FAILS {
        h.now += 1_000_000; // past the new-attempt rate limit
        let start = phone.pair_start();
        let reply = h.send(&phone, &start);
        if i + 1 < wire::PAIR_MAX_FAILS {
            assert!(phone.on_pair_host(&reply.unwrap()));
            assert_eq!(ack(h.send(&phone, &phone.pair_reveal(None))).status, pair_ack::OK);
        } else {
            assert_eq!(ack(reply).status, pair_ack::CLOSED);
        }
    }
    assert!(!h.m.is_open(h.now));
}

#[test]
fn bad_commit_counts_and_three_failures_close_window() {
    let mut h = Pairing::new(true);
    for i in 0..wire::PAIR_MAX_FAILS {
        let mut phone = new_phone(0x60 + i as u8, "192.168.1.60");
        let s = phone.pair_start();
        assert!(phone.on_pair_host(&h.send(&phone, &s).unwrap()));
        let wrong = phone.pair_reveal(Some(range_n(0x99)));
        assert_eq!(ack(h.send(&phone, &wrong)).status, pair_ack::BAD_COMMIT);
        // The retransmit gets the same cached answer.
        assert_eq!(ack(h.send(&phone, &wrong)).status, pair_ack::BAD_COMMIT);
        assert!(h.shown.is_empty());
        h.advance_ms(wire::PAIR_COOLDOWN_MS + 1);
    }
    let mut late = new_phone(0x6F, "192.168.1.60");
    assert_eq!(ack(h.start(&mut late)).status, pair_ack::CLOSED);
    assert_eq!(h.m.window_remaining_s(h.now), 0);
}

#[test]
fn replies_are_idempotent() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    let start = phone.pair_start();
    let host1 = h.send(&phone, &start);
    let host2 = h.send(&phone, &start);
    assert_eq!(host1, host2); // same attempt, same nH, same key
    assert!(phone.on_pair_host(&host1.unwrap()));
    let reveal = phone.pair_reveal(None);
    assert_eq!(h.send(&phone, &reveal), h.send(&phone, &reveal));
    assert_eq!(h.shown.len(), 1); // prompt shown once
}

#[test]
fn unknown_attempt_for_stray_reveal_and_confirm() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    let s = phone.pair_start();
    assert!(phone.on_pair_host(&h.send(&phone, &s).unwrap()));
    let stranger = new_phone(0x70, "192.168.1.70");
    let mut b = [0u8; wire::PAIR_REVEAL_SIZE];
    codec::encode_pair_reveal(&codec::PairReveal { attempt_id: phone.attempt_id, phone_id: stranger.phone_id, n_p: [0; 16] }, &mut b);
    assert_eq!(ack(h.send(&stranger, &b)).status, pair_ack::UNKNOWN_ATTEMPT);
    // CONFIRM before REVEAL: unknown too.
    let mut c = [0u8; wire::PAIR_CONFIRM_SIZE];
    codec::encode_pair_confirm(&codec::PairConfirm { attempt_id: phone.attempt_id, phone_id: phone.phone_id, confirm_p: [0; 32] }, &mut c);
    assert_eq!(ack(h.send(&phone, &c)).status, pair_ack::UNKNOWN_ATTEMPT);
}

#[test]
fn an_invalid_phone_key_gets_no_answer_and_no_attempt() {
    let mut h = Pairing::new(true);
    let phone = phone60();
    let mut b = [0u8; wire::PAIR_START_SIZE];
    let mut pk = [0u8; wire::PUBLIC_KEY_SIZE];
    pk[0] = 4; // not a point on the curve
    codec::encode_pair_start(&codec::PairStart { phone_id: phone.phone_id, pk_p: pk, commit: [1; 32], name: "x".into() }, &mut b);
    assert_eq!(h.send(&phone, &b), None);
    // The slot is still free for a real phone.
    let mut real = new_phone(0x70, "192.168.1.70");
    let s = real.pair_start();
    assert!(real.on_pair_host(&h.send(&real, &s).unwrap()));
}

#[test]
fn closing_the_window_ends_the_attempt() {
    let mut h = Pairing::new(true);
    let mut phone = phone60();
    h.start_and_reveal(&mut phone);
    h.m.close();
    h.drain();
    assert_eq!(h.closed, vec![phone.attempt_id]);
    assert!(!h.m.busy(h.now));
    assert_eq!(ack(h.send(&phone, &phone.pair_confirm(false))).status, pair_ack::UNKNOWN_ATTEMPT);
}

#[test]
fn window_runs_out_and_reply_buffers_too_short_stay_silent() {
    let mut h = Pairing::new(true);
    assert_eq!(h.m.window_remaining_s(h.now), wire::PAIRING_WINDOW_S as i32);
    assert_eq!(h.m.window_remaining_s(h.now + 1), wire::PAIRING_WINDOW_S as i32);
    assert_eq!(h.m.window_remaining_s(h.now + 1_000_000), wire::PAIRING_WINDOW_S as i32 - 1);
    let mut phone = phone60();
    let start = codec::decode_pair_start(&phone.pair_start()).unwrap();
    let mut tiny = [0u8; 4];
    assert_eq!(h.m.on_start(&start, &HOST_ID, HOST_NAME, h.now, &mut tiny, 1), 0);
    h.now += wire::PAIRING_WINDOW_S * 1_000_000;
    assert!(!h.m.is_open(h.now));
}
