//! Keep-awake, the link test, the log control datagrams and the mouse catch-up through the engine: ports of the
//! C# KeepAwakeTests, LinkTestTests, LogCtlTests and MouseCatchUpTests' engine tests.

mod common;

use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

use common::harness::{Harness, Setup};
use common::phone::FakePhone;
use gp_input::codec::LinkInfo;
use gp_input::engine::{keep_awake_wanted, KEEP_AWAKE_IDLE_WAIT_MS};
use gp_input::mapping;
use gp_input::state_frame::StateFrame;
use gp_input::wire::{self, link_info_flags, link_path, packet, state_flags, test_mode_flags};

fn at(ip: &str) -> SocketAddr {
    SocketAddr::new(ip.parse().unwrap(), 47800)
}

fn link(flags: u8, path: u8) -> LinkInfo {
    LinkInfo { clock_offset_us: 0, rtt_min_us: 0, send_rate_hz: 0, rssi_dbm: 0, band: 0, link_mbps: 0, freq_mhz: 0, flags, path }
}

fn state(h: &Harness, phone: &mut FakePhone, change: impl FnOnce(&mut StateFrame)) -> Option<Vec<u8>> {
    let d = phone.state(change);
    h.send(phone, &d)
}

// ---- KeepAwakeTests ----

#[test]
fn sent_over_wifi_and_before_the_first_link_info() {
    assert!(keep_awake_wanted(None, at("192.168.137.212"), None));
    assert!(keep_awake_wanted(Some(&link(0, link_path::WIFI)), at("192.168.137.212"), None));
    assert!(keep_awake_wanted(Some(&link(0, link_path::HOTSPOT)), at("10.0.0.5"), None));
    assert!(keep_awake_wanted(None, at("fe80::1"), None));
}

#[test]
fn skipped_over_wires_and_loopback() {
    assert!(!keep_awake_wanted(Some(&link(0, link_path::USB_TETHER)), at("192.168.42.129"), None));
    assert!(!keep_awake_wanted(Some(&link(0, link_path::USB_DIRECT)), at("192.168.42.129"), None));
    assert!(!keep_awake_wanted(Some(&link(0, link_path::ETHERNET)), at("192.168.1.20"), None));
    assert!(!keep_awake_wanted(Some(&link(link_info_flags::USB, link_path::UNKNOWN)), at("192.168.42.129"), None));
    assert!(!keep_awake_wanted(Some(&link(link_info_flags::ETHERNET, link_path::UNKNOWN)), at("192.168.1.20"), None));
    assert!(!keep_awake_wanted(None, at("127.0.0.1"), None));
    assert!(!keep_awake_wanted(None, at("::1"), None));
    assert!(!keep_awake_wanted(None, at("::ffff:127.0.0.1"), None));
}

#[test]
fn no_phone_means_no_keep_awake_and_an_idle_watchdog() {
    let h = Harness::new();
    assert!(!h.engine.keep_awake());
    h.tick(50);
    assert!(h.engine.watchdog_idle());
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // A session ends both rests at once.
    assert!(!h.engine.watchdog_idle());
    h.sender.clear();
    assert!(h.engine.keep_awake());
    assert_eq!(packet::KEEP_AWAKE, phone.try_open(&h.sender.last().unwrap()).unwrap().0);
    h.tick(50);
    assert!(!h.engine.watchdog_idle());
}

#[test]
fn a_resting_keep_awake_wakes_when_a_phone_connects() {
    let h = Harness::new();
    let start = Instant::now();
    let engine = h.engine.clone();
    let rest = std::thread::spawn(move || engine.rest_keep_awake());
    std::thread::sleep(Duration::from_millis(50));
    assert!(!rest.is_finished());
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    rest.join().unwrap();
    assert!(start.elapsed() < Duration::from_millis(KEEP_AWAKE_IDLE_WAIT_MS), "woke after {:?}", start.elapsed());
    // With the session up there is no rest.
    let start = Instant::now();
    h.engine.rest_keep_awake();
    assert!(start.elapsed() < Duration::from_millis(KEEP_AWAKE_IDLE_WAIT_MS / 2));
}

#[test]
fn skipped_where_the_pc_is_no_wifi_client() {
    // A cable to the router, or the PC's own hotspot: the phone is on Wi-Fi, but no radio of the PC sleeps.
    assert!(!keep_awake_wanted(Some(&link(0, link_path::WIFI)), at("192.168.137.212"), Some(false)));
    assert!(!keep_awake_wanted(None, at("192.168.1.20"), Some(false)));
    assert!(keep_awake_wanted(Some(&link(0, link_path::WIFI)), at("192.168.1.20"), Some(true)));
    assert!(keep_awake_wanted(Some(&link(0, link_path::HOTSPOT)), at("10.0.0.5"), None));
    assert!(!keep_awake_wanted(Some(&link(0, link_path::USB_TETHER)), at("192.168.42.129"), Some(true)));
}

#[test]
fn the_pc_adapter_decides_only_for_the_phone_it_was_found_for() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let at = h.engine.phone_address().unwrap();
    assert!(h.engine.keep_awake());
    // The PC's hotspot, or Ethernet toward the router (no Wi-Fi card that joins networks): none sent.
    h.engine.set_pc_adapter(Some(at), Some(false));
    assert!(!h.engine.keep_awake());
    // The PC's Wi-Fi card.
    h.engine.set_pc_adapter(Some(at), Some(true));
    assert!(h.engine.keep_awake());
    // Found for another address, or not found: as before.
    let other: IpAddr = "192.168.1.51".parse().unwrap();
    assert_ne!(other, at);
    h.engine.set_pc_adapter(Some(other), Some(false));
    assert!(h.engine.keep_awake());
    h.engine.set_pc_adapter(Some(at), None);
    assert!(h.engine.keep_awake());
}

#[test]
fn a_resting_keep_awake_wakes_when_the_pc_turns_out_to_be_a_wifi_client() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let at = h.engine.phone_address().unwrap();
    h.engine.set_pc_adapter(Some(at), Some(false));
    let start = Instant::now();
    let engine = h.engine.clone();
    let rest = std::thread::spawn(move || engine.rest_keep_awake());
    std::thread::sleep(Duration::from_millis(50));
    assert!(!rest.is_finished());
    h.engine.set_pc_adapter(Some(at), Some(true));
    rest.join().unwrap();
    assert!(start.elapsed() < Duration::from_millis(KEEP_AWAKE_IDLE_WAIT_MS), "woke after {:?}", start.elapsed());
}

// ---- LinkTestTests ----

fn ctl(ptype: u8, b12: u8, b13: u8) -> [u8; 16] {
    let mut d = [0u8; 16];
    d[0] = wire::MAGIC0;
    d[1] = wire::MAGIC1;
    d[2] = wire::VERSION;
    d[3] = ptype;
    d[12] = b12;
    d[13] = b13;
    d
}

fn test_ctl(seconds: u16) -> [u8; 16] {
    ctl(packet::LINK_TEST_CTL, seconds as u8, (seconds >> 8) as u8)
}

fn loopback() -> SocketAddr {
    "127.0.0.1:50000".parse().unwrap()
}

fn moved_abs(h: &Harness) -> i64 {
    h.key_mouse.all().iter().filter(|e| e.flags & mapping::MOUSE_MOVE != 0).map(|e| (e.dx as i64).abs()).sum()
}

fn moved(h: &Harness) -> i64 {
    h.key_mouse.all().iter().filter(|e| e.flags & mapping::MOUSE_MOVE != 0).map(|e| e.dx as i64).sum()
}

#[test]
fn loopback_starts_it_and_the_phone_is_told() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.sender.clear();
    h.engine.on_datagram(&test_ctl(600), loopback(), h.now());
    assert!(h.engine.link_testing());
    let (t, tm) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::TEST_MODE, t);
    assert_eq!(test_mode_flags::LINK_TEST, tm[0]);

    h.sender.clear();
    h.engine.on_datagram(&test_ctl(0), loopback(), h.now());
    assert!(!h.engine.link_testing());
    let (t, tm) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::TEST_MODE, t);
    assert_eq!(0, tm[0]);
}

#[test]
fn only_this_pc_may_start_it() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.engine.on_datagram(&test_ctl(600), phone.addr, h.now());
    assert!(!h.engine.link_testing());
}

#[test]
fn ends_when_time_is_up() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.engine.on_datagram(&test_ctl(2), loopback(), h.now());
    h.tick(1000);
    assert!(h.engine.link_testing());
    h.tick(1100);
    assert!(!h.engine.link_testing());
}

#[test]
fn test_input_is_never_injected() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.engine.set_link_test(60, "test");
    let before = moved_abs(&h);
    for i in 1..=50 {
        h.clock.advance_us(1000);
        state(&h, &mut phone, |f| {
            f.flags = state_flags::LINK_TEST;
            f.mouse_total_x = i * 3;
            f.phone_time_us = (i * 1000) as u32;
        });
    }
    assert_eq!(before, moved_abs(&h));
    // The first real frame after the test rebases (its gap to the fake totals is no movement),
    // then real input moves again.
    h.engine.set_link_test(0, "test");
    h.clock.advance_us(1000);
    state(&h, &mut phone, |f| {
        f.flags = 0;
        f.mouse_total_x = 9000;
        f.wheel_total = 40;
        f.phone_time_us = 51000;
    });
    assert_eq!(before, moved_abs(&h));
    h.clock.advance_us(1000);
    state(&h, &mut phone, |f| {
        f.flags = 0;
        f.mouse_total_x = 9050;
        f.wheel_total = 40;
        f.phone_time_us = 52000;
    });
    assert_eq!(before + 50, moved_abs(&h));
    assert!(!h.key_mouse.all().iter().any(|e| e.flags & mapping::MOUSE_WHEEL != 0));
}

#[test]
fn starting_a_test_does_not_jump_either() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.clock.advance_us(1000);
    state(&h, &mut phone, |f| {
        f.mouse_total_x = 5000;
        f.phone_time_us = 1000;
    });
    h.clock.advance_us(1000);
    state(&h, &mut phone, |f| {
        f.mouse_total_x = 5010;
        f.phone_time_us = 2000;
    });
    let before = moved_abs(&h);
    h.engine.set_link_test(60, "test");
    for i in 1..=5 {
        h.clock.advance_us(1000);
        state(&h, &mut phone, |f| {
            f.flags = state_flags::LINK_TEST;
            f.mouse_total_x = i * 3;
            f.phone_time_us = (2000 + i * 1000) as u32;
        });
    }
    h.engine.set_link_test(0, "test");
    // Back to the real totals, which moved on by 20 meanwhile; nothing jumps, and the catch-up has nothing left.
    h.clock.advance_us(1000);
    state(&h, &mut phone, |f| {
        f.flags = 0;
        f.mouse_total_x = 5030;
        f.phone_time_us = 8000;
    });
    for _ in 0..20 {
        h.tick(1);
    }
    assert_eq!(before, moved_abs(&h));
}

// ---- LogCtlTests ----

fn log_ctl(what: u8, value: u8) -> [u8; 16] {
    ctl(packet::LOG_CTL, what, value)
}

#[test]
fn loopback_switches_diagnostics_and_sets_the_minutes() {
    let h = Harness::new();
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_DIAGNOSTICS, 1), loopback(), h.now());
    assert!(h.engine.settings().diagnostics);
    assert!(h.engine.trace().enabled());
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_KEEP, 12), loopback(), h.now());
    assert_eq!(12, h.engine.settings().diagnostics_minutes);
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_KEEP, 99), loopback(), h.now());
    assert_eq!(30, h.engine.settings().diagnostics_minutes);
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_SAVE, 0), loopback(), h.now());
    assert_eq!(1, h.hooks.saves());
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_DIAGNOSTICS, 0), loopback(), h.now());
    assert!(!h.engine.settings().diagnostics);
    assert!(!h.engine.trace().enabled());
}

#[test]
fn a_switch_from_a_datagram_is_told_off_the_receive_thread() {
    let h = Harness::with(Setup { inline_workers: false, ..Setup::default() });
    let me = std::thread::current().id();
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_DIAGNOSTICS, 1), loopback(), h.now());
    h.until("the switch is told", || !h.hooks.diagnostics.lock().unwrap().is_empty());
    let first = h.hooks.diagnostics.lock().unwrap()[0];
    assert!(first.0);
    // gp-rx (here the test's thread) never starts or stops the probe's thread itself.
    assert_ne!(me, first.1);

    // Switched on the PC (no lock held): told at once, on the caller's thread.
    h.engine.set_diagnostics(false);
    assert_eq!(Some(&(false, me)), h.hooks.diagnostics.lock().unwrap().get(1));
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(2, h.hooks.diagnostics.lock().unwrap().len());
}

#[test]
fn from_another_machine_it_is_dropped() {
    let h = Harness::new();
    h.engine.on_datagram(&log_ctl(wire::LOG_CTL_DIAGNOSTICS, 1), "192.168.1.20:50000".parse().unwrap(), h.now());
    assert!(!h.engine.settings().diagnostics);
}

// ---- MouseCatchUpTests (the engine's) ----

fn warmed(h: &Harness) -> (FakePhone, i64, i32) {
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
    let t0 = h.now();
    let mut total = 0;
    for i in 0..=40 {
        total = i * 3;
        send_at(h, &mut phone, t0, i, total);
    }
    (phone, t0, total)
}

fn send_at(h: &Harness, phone: &mut FakePhone, t0: i64, i: i32, x: i32) {
    h.clock.set(t0 + i as i64 * 1000);
    state(h, phone, |f| {
        f.phone_time_us = (i * 1000) as u32;
        f.mouse_total_x = x;
    });
}

#[test]
fn engine_catch_up_off_jumps_at_once() {
    let h = Harness::new();
    h.engine.set_catch_up_ms(0);
    assert_eq!(0, h.engine.settings().catch_up_ms);
    let (mut phone, t0, mut total) = warmed(&h);
    phone.seq += 29;
    total += 300;
    send_at(&h, &mut phone, t0, 70, total);
    assert_eq!(total as i64, moved(&h));
    assert!(!h.engine.mouse_catching_up());
}

/// Through the engine: lost packets with real phone times catch up, flush without packets moves the catch-up on.
#[test]
fn engine_catches_up_a_lost_stretch() {
    let h = Harness::new();
    let (mut phone, t0, mut total) = warmed(&h);
    assert_eq!(120, moved(&h));
    assert!(!h.engine.mouse_catching_up());

    // 29 packets lost (their seqs skipped), then one with 30 ms of motion.
    phone.seq += 29;
    total += 300;
    send_at(&h, &mut phone, t0, 70, total);
    let after_arrival = moved(&h);
    assert!((1..=30).contains(&(after_arrival - 120)), "{after_arrival}");
    assert!(h.engine.mouse_catching_up());
    let mut k = 1;
    while k <= 20 && h.engine.mouse_catching_up() {
        h.clock.set(t0 + 70_000 + k * 1000);
        h.engine.flush(h.now());
        k += 1;
    }
    assert!(!h.engine.mouse_catching_up());
    assert_eq!(total as i64, moved(&h));
}
