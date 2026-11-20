//! Code pairing through the engine (its prompt, its settings and its window): a port of the C# PairingManagerTests,
//! which run through HostEngine, and the engine test of SettingsAndStatsTests.

mod common;

use common::harness::{Harness, Setup};
use common::phone::FakePhone;
use common::range_n;
use gp_input::codec::{self, PairAck, PairDone};
use gp_input::pairing::REVEAL_TIMEOUT_US;
use gp_input::wire::{self, announce_flags, pair_ack, pair_done};

fn new_host(open_window: bool) -> Harness {
    let h = Harness::with(Setup { pair_default: false, ..Setup::default() });
    if open_window {
        h.engine.start(h.now()); // nothing paired: the window opens by itself
    }
    h
}

fn new_phone(seed: u8, ip: &str) -> FakePhone {
    FakePhone::new(range_n(seed), "OnePlus 15", &format!("{ip}:40000"))
}

fn phone60() -> FakePhone {
    new_phone(0x60, "192.168.1.60")
}

fn ack(d: Option<Vec<u8>>) -> PairAck {
    codec::decode_pair_ack(&d.expect("a reply")).expect("PAIR_ACK")
}

fn done(d: Option<Vec<u8>>) -> PairDone {
    codec::decode_pair_done(&d.expect("a reply")).expect("PAIR_DONE")
}

fn start(h: &Harness, phone: &mut FakePhone) -> Option<Vec<u8>> {
    let s = phone.pair_start();
    h.send(phone, &s)
}

/// START and REVEAL: the host has shown its prompt.
fn start_and_reveal(h: &Harness, phone: &mut FakePhone) {
    let r = start(h, phone).unwrap();
    assert!(phone.on_pair_host(&r));
    assert_eq!(pair_ack::OK, ack(h.send(phone, &phone.pair_reveal(None))).status);
}

fn confirm(h: &Harness, phone: &FakePhone) -> Option<Vec<u8>> {
    h.send(phone, &phone.pair_confirm(false))
}

fn shown(h: &Harness) -> Vec<(u32, String, String)> {
    h.prompt.shown.lock().unwrap().clone()
}

fn closed(h: &Harness) -> Vec<u32> {
    h.prompt.closed.lock().unwrap().clone()
}

#[test]
fn closed_window_gives_closed() {
    let h = new_host(false);
    let mut phone = phone60();
    let a = ack(start(&h, &mut phone));
    assert_eq!((pair_ack::CLOSED, 0), (a.status, a.attempt_id));
}

#[test]
fn full_flow_allow_then_confirm_stores_pairing() {
    let h = new_host(true);
    let mut phone = phone60();
    start_and_reveal(&h, &mut phone);
    assert_eq!(vec![(phone.attempt_id, "OnePlus 15".to_string(), phone.sas.clone().unwrap())], shown(&h));
    assert_eq!(Some(phone.attempt_id), h.engine.snapshot().pair_prompt.map(|p| p.0));

    h.engine.answer_pair(phone.attempt_id, true);
    assert!(h.engine.settings().paired_phones.is_empty()); // Allow alone does not store
    assert!(h.engine.take_dirty_settings().is_none() || h.engine.settings().paired_phones.is_empty());

    let (ok, status) = phone.on_pair_done(&confirm(&h, &phone).unwrap());
    assert!(ok);
    assert_eq!(pair_done::ACCEPTED, status);
    let stored = h.engine.settings().paired_phones;
    assert_eq!(1, stored.len());
    assert_eq!(phone.phone_id.to_vec(), stored[0].phone_id);
    assert_eq!(phone.pair_key.unwrap().to_vec(), stored[0].protected_key); // plain protector in tests
    assert!(h.engine.take_dirty_settings().is_some()); // and the file is to be written
    assert_eq!(0, h.engine.snapshot().pairing_window_s); // success closes the window
    assert_eq!(1, h.engine.snapshot().paired_phones.len());

    // The new pairing works for a session.
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
}

#[test]
fn confirm_before_allow_is_pending_then_accepted() {
    let h = new_host(true);
    let mut phone = phone60();
    start_and_reveal(&h, &mut phone);
    assert_eq!(pair_done::PENDING, done(confirm(&h, &phone)).status);
    assert_eq!(pair_done::PENDING, done(confirm(&h, &phone)).status);
    assert!(h.engine.settings().paired_phones.is_empty());

    h.engine.answer_pair(phone.attempt_id, true);
    assert_eq!(1, h.engine.settings().paired_phones.len()); // valid confirmP already seen + Allow
    assert!(phone.on_pair_done(&confirm(&h, &phone).unwrap()).0);
    // Retransmitted CONFIRM after completion: same cached DONE.
    assert!(phone.on_pair_done(&confirm(&h, &phone).unwrap()).0);
    assert_eq!(1, h.engine.settings().paired_phones.len());
}

#[test]
fn allow_with_bad_confirm_does_not_store() {
    let h = new_host(true);
    let mut phone = phone60();
    start_and_reveal(&h, &mut phone);
    h.engine.answer_pair(phone.attempt_id, true);
    let d = done(h.send(&phone, &phone.pair_confirm(true)));
    assert_eq!(pair_done::BAD_CONFIRM, d.status);
    assert_eq!([0u8; 32], d.confirm_h);
    assert!(h.engine.settings().paired_phones.is_empty());
}

#[test]
fn deny_gives_denied_and_cooldown() {
    let h = new_host(true);
    let mut phone = phone60();
    start_and_reveal(&h, &mut phone);
    h.engine.answer_pair(phone.attempt_id, false);
    assert!(closed(&h).contains(&phone.attempt_id));
    assert!(h.engine.snapshot().pair_prompt.is_none());
    assert_eq!(pair_done::DENIED, done(confirm(&h, &phone)).status);
    assert!(h.engine.settings().paired_phones.is_empty());

    let mut again = phone60();
    assert_eq!(pair_ack::COOLDOWN, ack(start(&h, &mut again)).status);
    h.clock.advance_ms(wire::PAIR_COOLDOWN_MS + 1);
    let r = start(&h, &mut again).unwrap();
    assert!(again.on_pair_host(&r));
}

#[test]
fn prompt_times_out() {
    let h = new_host(true);
    let mut phone = phone60();
    start_and_reveal(&h, &mut phone);
    h.tick(wire::PAIR_USER_TIMEOUT_S * 1000 + 10);
    assert!(closed(&h).contains(&phone.attempt_id)); // the watchdog takes the prompt away
    assert_eq!(pair_done::TIMEOUT, done(confirm(&h, &phone)).status);
}

#[test]
fn second_phone_is_busy() {
    let h = new_host(true);
    let mut first = phone60();
    start_and_reveal(&h, &mut first);
    let mut second = new_phone(0x70, "192.168.1.70");
    assert_eq!(pair_ack::BUSY, ack(start(&h, &mut second)).status);
}

#[test]
fn an_attempt_that_never_reveals_does_not_hold_the_pc_for_the_whole_window() {
    let h = new_host(true);
    let mut squatter = new_phone(0x50, "192.168.1.50");
    let r = start(&h, &mut squatter).unwrap();
    assert!(squatter.on_pair_host(&r));
    // Meanwhile another phone hears nothing (so it keeps resending) rather than a final BUSY.
    let mut phone = phone60();
    let s = phone.pair_start();
    assert!(h.send(&phone, &s).is_none());
    h.clock.advance_us(REVEAL_TIMEOUT_US);
    assert!(phone.on_pair_host(&h.send(&phone, &s).unwrap()));
    assert_eq!(pair_ack::OK, ack(h.send(&phone, &phone.pair_reveal(None))).status);
    assert_eq!(vec![phone.attempt_id], shown(&h).iter().map(|s| s.0).collect::<Vec<_>>());
}

#[test]
fn restarting_unrevealed_attempts_do_not_hold_the_pc_past_the_first_deadline() {
    let h = new_host(true);
    let mut squatter = new_phone(0x50, "192.168.1.50");
    let r = start(&h, &mut squatter).unwrap();
    assert!(squatter.on_pair_host(&r));
    let mut phone = phone60();
    let s = phone.pair_start();
    // The squatter keeps starting over with new commits, never revealing.
    for _ in 0..3 {
        h.clock.advance_us(REVEAL_TIMEOUT_US / 4);
        assert!(start(&h, &mut squatter).is_some());
        assert!(h.send(&phone, &s).is_none());
    }
    h.clock.advance_us(REVEAL_TIMEOUT_US / 4);
    assert!(phone.on_pair_host(&h.send(&phone, &s).unwrap()));
    assert_eq!(pair_ack::OK, ack(h.send(&phone, &phone.pair_reveal(None))).status);
}

#[test]
fn flooded_starts_generate_few_keys() {
    let h = new_host(true);
    let mut phone = phone60();
    let mut replies = 0;
    for _ in 0..50 {
        if start(&h, &mut phone).is_some() {
            replies += 1;
        }
    }
    assert_eq!(2, replies); // the rest are dropped before any key generation
    h.clock.advance_ms(1000);
    assert!(start(&h, &mut phone).is_some());
}

#[test]
fn same_phone_restart_replaces_its_own_attempt() {
    let h = new_host(true);
    let mut phone = phone60();
    start_and_reveal(&h, &mut phone);
    let old_id = phone.attempt_id;
    let s = phone.pair_start(); // cancelled on the phone, started again: new key and commit
    assert!(phone.on_pair_host(&h.send(&phone, &s).unwrap()));
    assert_ne!(old_id, phone.attempt_id);
    assert!(closed(&h).contains(&old_id));
    assert_eq!(pair_ack::OK, ack(h.send(&phone, &phone.pair_reveal(None))).status);
    assert_eq!(phone.attempt_id, shown(&h).last().unwrap().0);

    // A repeat of the new START gets the same PAIR_HOST; another phone is still busy.
    let again = h.send(&phone, &s).unwrap();
    assert_eq!(phone.attempt_id, codec::decode_pair_host(&again).unwrap().attempt_id);
    let mut second = new_phone(0x70, "192.168.1.70");
    assert_eq!(pair_ack::BUSY, ack(start(&h, &mut second)).status);

    h.engine.answer_pair(phone.attempt_id, true);
    assert!(phone.on_pair_done(&confirm(&h, &phone).unwrap()).0);
}

#[test]
fn bad_commit_counts_and_three_failures_close_window() {
    let h = new_host(true);
    for i in 0..wire::PAIR_MAX_FAILS as u8 {
        let mut phone = new_phone(0x60 + i, "192.168.1.60");
        let r = start(&h, &mut phone).unwrap();
        assert!(phone.on_pair_host(&r));
        assert_eq!(pair_ack::BAD_COMMIT, ack(h.send(&phone, &phone.pair_reveal(Some(range_n(0x99))))).status);
        assert!(shown(&h).is_empty());
        h.clock.advance_ms(wire::PAIR_COOLDOWN_MS + 1);
    }
    let mut late = new_phone(0x6F, "192.168.1.60");
    assert_eq!(pair_ack::CLOSED, ack(start(&h, &mut late)).status);
    assert_eq!(0, h.engine.snapshot().pairing_window_s);
}

#[test]
fn replies_are_idempotent() {
    let h = new_host(true);
    let mut phone = phone60();
    let s = phone.pair_start();
    let host1 = h.send(&phone, &s);
    let host2 = h.send(&phone, &s);
    assert_eq!(host1, host2); // same attempt, same nH, same key
    assert!(phone.on_pair_host(&host1.unwrap()));
    let reveal = phone.pair_reveal(None);
    assert_eq!(h.send(&phone, &reveal), h.send(&phone, &reveal));
    assert_eq!(1, shown(&h).len()); // prompt shown once
}

/// The prompt is shown after the engine lock is let go: a UI that answers from inside `show` cannot deadlock the
/// receive thread, and other traffic keeps flowing while the user decides.
#[test]
fn prompt_does_not_block_on_datagram() {
    let h = std::sync::Arc::new(new_host(true));
    let mut phone = phone60();
    let r = start(&h, &mut phone).unwrap();
    assert!(phone.on_pair_host(&r));
    let reveal = phone.pair_reveal(None);
    let (h2, addr) = (h.clone(), phone.addr);
    let reply = std::thread::spawn(move || {
        let before = h2.sender.count();
        h2.engine.on_datagram(&reveal, addr, h2.now());
        h2.engine.flush(h2.now());
        h2.sender.at(before)
    })
    .join()
    .unwrap();
    assert_eq!(pair_ack::OK, ack(Some(reply)).status);
    assert_eq!(phone.attempt_id, shown(&h).last().unwrap().0);
    // Other traffic keeps flowing while the user decides.
    let other = new_phone(0x70, "192.168.1.70");
    assert!(h.send(&other, &other.discover(1)).is_some());
    assert_eq!(pair_done::PENDING, done(confirm(&h, &phone)).status);
    // The UI answers from its own thread.
    let (h2, id) = (h.clone(), phone.attempt_id);
    std::thread::spawn(move || h2.engine.answer_pair(id, true)).join().unwrap();
    assert!(phone.on_pair_done(&confirm(&h, &phone).unwrap()).0);
}

#[test]
fn unknown_attempt_for_stray_reveal() {
    let h = new_host(true);
    let mut phone = phone60();
    let r = start(&h, &mut phone).unwrap();
    assert!(phone.on_pair_host(&r));
    let stranger = new_phone(0x70, "192.168.1.70");
    let mut b = [0u8; wire::PAIR_REVEAL_SIZE];
    codec::encode_pair_reveal(&codec::PairReveal { attempt_id: phone.attempt_id, phone_id: stranger.phone_id, n_p: range_n(0) }, &mut b);
    assert_eq!(pair_ack::UNKNOWN_ATTEMPT, ack(h.send(&stranger, &b)).status);
}

#[test]
fn announce_shows_pairing_open() {
    let h = new_host(true);
    let phone = phone60();
    let a = codec::decode_announce(&h.send(&phone, &phone.discover(42)).unwrap()).unwrap();
    assert_eq!(42, a.req_id);
    assert_ne!(0, a.flags & announce_flags::PAIRING_OPEN);
    assert_ne!(0, a.flags & announce_flags::VIGEM_OK);
    assert_eq!(0, a.flags & announce_flags::PAIRED_WITH_YOU);
    assert_eq!("DESKTOP-OLEX", a.name);
}

#[test]
fn snapshot_carries_the_phone_address() {
    let h = Harness::new();
    assert!(h.engine.snapshot().phone_address.is_none());
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let first = h.engine.snapshot().phone_address;
    assert_eq!(Some("192.168.1.50".to_string()), first);
    assert_eq!(h.engine.phone_address().map(|a| a.to_string()), first);
}
