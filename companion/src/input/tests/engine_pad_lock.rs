//! The virtual pad's plug, replies without a session, and who may wait on the engine lock: ports of the C#
//! PadPlugTests, AmplificationTests and EngineLockTests.

mod common;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use common::harness::{finishes, paired, Harness, Setup, PHONE1_ID};
use common::phone::FakePhone;
use common::{bytes, range_n, text, vectors};
use gp_input::codec;
use gp_input::mapping::{xusb_buttons, X360Frame};
use gp_input::recorder::InputRecorder;
use gp_input::state_frame::{StateFrame, KEY_BITMAP_SIZE};
use gp_input::wire::{self, bye_reason, host_status_flags, output_mode, packet};

const USAGE_W: u32 = 0x1A;
const SCAN_W: u16 = 0x11;

fn state(h: &Harness, phone: &mut FakePhone, change: impl FnOnce(&mut StateFrame)) -> Option<Vec<u8>> {
    let d = phone.state(change);
    h.send(phone, &d)
}

fn bye(h: &Harness, phone: &mut FakePhone) {
    let d = phone.bye(bye_reason::USER);
    h.send(phone, &d);
}

// ---- PadPlugTests ----

#[test]
fn pad_stays_plugged_across_sessions_and_is_reused() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| f.lx = 20000);
    bye(&h, &mut phone);
    assert!(!h.engine.snapshot().connected);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    assert_eq!(X360Frame::NEUTRAL, *h.pad.x360().last().unwrap()); // left at rest
    assert!(h.engine.snapshot().pad_plugged);

    h.tick(30_000);
    h.connect(&mut phone);
    assert!(h.engine.snapshot().connected);
    assert_eq!(1, h.pad.plugs());
    assert_eq!(0, h.pad.unplugs());
}

#[test]
fn pad_lost_underneath_is_plugged_again_off_the_input_path() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    assert_eq!(1, h.pad.plugs());
    h.pad.set(|p| {
        p.fail_with = Some("target not plugged in");
        p.lose_on_fail = true;
        p.plug_fails = true; // the driver is still resetting
    });

    // The STATE's keys and mouse go on; the submit never plugs a pad itself.
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.buttons = 1;
        f.mouse_total_x += 4;
    });
    assert!(h.key_mouse.holds_key(SCAN_W));
    assert_eq!(4, h.key_mouse.mouse_x());
    assert_eq!(1, h.pad.plugs());
    assert_eq!(output_mode::NONE, h.pad.plugged_now());
    h.pad.set(|p| p.fail_with = None);

    // The watchdog has the pad thread plug it again at once, then after 1 s, 2 s and so on while that fails.
    h.tick(wire::WATCHDOG_TICK_MS);
    assert_eq!(2, h.pad.plugs());
    h.tick(500);
    assert_eq!(2, h.pad.plugs());
    h.tick(600);
    assert_eq!(3, h.pad.plugs());
    h.tick(1_500);
    assert_eq!(3, h.pad.plugs());

    // Once the driver is back the pad is too, with the held button, and no more plugs follow.
    h.pad.set(|p| p.plug_fails = false);
    h.tick(600);
    assert_eq!(4, h.pad.plugs());
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    assert!(h.engine.snapshot().pad_plugged);
    state(&h, &mut phone, |f| f.mouse_total_x += 1);
    assert_eq!(xusb_buttons::A, h.pad.x360().last().unwrap().buttons);
    h.tick(5_000);
    assert_eq!(4, h.pad.plugs());
}

#[test]
fn pad_unplugged_after_five_minutes_without_session() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    bye(&h, &mut phone);
    h.tick(299_000);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    h.tick(2_000);
    assert_eq!(output_mode::NONE, h.pad.plugged_now());
    assert_eq!(1, h.pad.unplugs());
    h.tick(60_000);
    assert_eq!(1, h.pad.unplugs());

    // The next session plugs it again.
    h.connect(&mut phone);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    assert_eq!(2, h.pad.plugs());
}

#[test]
fn output_mode_change_replugs_with_session_and_unplugs_without() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.engine.set_output_mode(output_mode::DUALSHOCK4);
    assert_eq!(output_mode::DUALSHOCK4, h.pad.plugged_now());

    bye(&h, &mut phone);
    assert_eq!(output_mode::DUALSHOCK4, h.pad.plugged_now());
    h.engine.set_output_mode(output_mode::XBOX360);
    assert_eq!(output_mode::NONE, h.pad.plugged_now());
}

#[test]
fn forget_and_exit_unplug() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    assert!(h.engine.forget(&PHONE1_ID));
    assert_eq!(output_mode::NONE, h.pad.plugged_now());

    let h2 = Harness::new();
    let mut phone2 = h2.new_phone();
    h2.connect(&mut phone2);
    bye(&h2, &mut phone2);
    assert_eq!(output_mode::XBOX360, h2.pad.plugged_now());
    h2.engine.shutdown();
    assert_eq!(output_mode::NONE, h2.pad.plugged_now());
}

#[test]
fn forgetting_another_phone_keeps_the_pad_for_the_last_one() {
    let other: [u8; 16] = range_n(0x80);
    let h = Harness::with(Setup {
        settings: Some(Box::new(move |s| s.paired_phones.push(paired(&other, "Pixel", &range_n::<32>(0xA0))))),
        ..Setup::default()
    });
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    bye(&h, &mut phone);
    assert!(!h.engine.snapshot().connected);
    assert!(h.engine.forget(&other));
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    assert!(h.engine.forget(&PHONE1_ID));
    assert_eq!(output_mode::NONE, h.pad.plugged_now());
}

#[test]
fn plug_runs_on_pad_thread_while_input_goes_on() {
    let h = Harness::with(Setup { inline_workers: false, ..Setup::default() });
    h.pad.1.close(); // the driver takes its time
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.lx = 20000;
    });

    // Promoted and typing, the plug still going: nothing went to the pad and the phone hears it isn't plugged.
    assert!(h.engine.snapshot().connected);
    assert!(h.key_mouse.holds_key(SCAN_W));
    h.until("the pad thread reaches the driver", || h.pad.1.waiting() == 1);
    assert_eq!(0, h.pad.plugs());
    assert!(h.pad.x360().is_empty());
    assert!(!h.engine.snapshot().pad_plugged);
    // Input goes on meanwhile.
    state(&h, &mut phone, |f| f.mouse_total_x += 9);
    assert_eq!(9, h.key_mouse.mouse_x());
    assert!(h.pad.x360().is_empty());

    // The pad thread plugs outside the lock; the STATE already handled then goes to the pad.
    h.sender.clear();
    h.pad.1.open();
    h.until("the pad is plugged", || h.engine.snapshot().pad_plugged);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    assert_eq!(20000, h.pad.x360().last().unwrap().lx);
    h.until("the status goes out", || h.sender.count() > 0);
    let (t, st) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::HOST_STATUS, t);
    assert_ne!(0, codec::decode_host_status(&st).flags & host_status_flags::PAD_PLUGGED);
}

#[test]
fn mode_change_during_plug_is_taken_by_the_same_job() {
    let h = Harness::with(Setup { inline_workers: false, ..Setup::default() });
    h.pad.1.close();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.until("the pad thread reaches the driver", || h.pad.1.waiting() == 1);
    h.engine.set_output_mode(output_mode::DUALSHOCK4);
    assert_eq!(1, h.pad.1.waiting()); // no second job: the running one takes the new mode
    h.pad.1.open();
    h.until("the pad is the new kind", || h.engine.snapshot().pad_plugged && h.pad.plugged_now() == output_mode::DUALSHOCK4);
    assert_eq!(2, h.pad.plugs());
}

// ---- AmplificationTests ----

/// Every unauthenticated reply is no larger than the request that caused it.
#[test]
fn unauthenticated_replies_never_exceed_request() {
    let h = Harness::with(Setup { pair_default: false, ..Setup::default() });
    let pairs = Mutex::new(Vec::<(usize, usize, &str)>::new());
    let send = |p: &FakePhone, d: Vec<u8>, what: &'static str| {
        let r = h.send(p, &d);
        if let Some(r) = &r {
            pairs.lock().unwrap().push((d.len(), r.len(), what));
        }
        r
    };

    let mut a = FakePhone::new(range_n(0x60), "A", "192.168.1.60:40000");
    let mut b = FakePhone::new(range_n(0x70), "B", "192.168.1.70:40000");
    send(&a, a.discover(1), "discover");
    let d = a.pair_start();
    send(&a, d, "start closed");
    h.engine.open_pairing_window();
    let d = a.pair_start();
    let r = send(&a, d, "start ok").unwrap();
    assert!(a.on_pair_host(&r));
    let d = b.pair_start();
    send(&b, d, "start busy");
    send(&a, a.pair_confirm(false), "confirm before reveal");
    send(&a, a.pair_reveal(None), "reveal ok");
    send(&a, a.pair_reveal(None), "reveal dup");
    send(&a, a.pair_confirm(false), "confirm pending");
    h.engine.answer_pair(a.attempt_id, false);
    send(&a, a.pair_confirm(false), "confirm denied");
    let d = b.pair_start();
    send(&b, d, "start cooldown");
    h.clock.advance_ms(wire::PAIR_COOLDOWN_MS + 1);
    let d = b.pair_start();
    let r = send(&b, d, "start ok 2").unwrap();
    assert!(b.on_pair_host(&r));
    send(&b, b.pair_reveal(Some(range_n(0))), "reveal bad commit");
    send(&b, b.pair_reveal(None), "reveal after fail");

    let pairs = pairs.into_inner().unwrap();
    assert!(pairs.len() >= 12, "{}", pairs.len());
    for (req, rep, what) in pairs {
        assert!(rep <= req, "{what}: {rep} > {req}");
    }
}

#[test]
fn wrong_sizes_and_short_packets_get_nothing() {
    let h = Harness::new();
    let phone = h.new_phone();
    let d = phone.discover(1);
    assert!(h.send(&phone, &d[..63]).is_none());
    let mut long = d.clone();
    long.push(0);
    assert!(h.send(&phone, &long).is_none());
    // bad version but shorter than 16 bytes: no ERROR
    let mut short_bad = [0u8; 12];
    codec::write_header(&mut short_bad, packet::DISCOVER, 0, 0);
    short_bad[2] = 2;
    assert!(h.send(&phone, &short_bad).is_none());
    // unknown type
    let mut unknown = [0u8; 64];
    codec::write_header(&mut unknown, 0x55, 0, 0);
    assert!(h.send(&phone, &unknown).is_none());
    // host-to-phone types are not accepted from the network
    let root = vectors("wire-v1.json");
    let announce = root["packets"].as_array().unwrap().iter().find(|p| text(p, "name") == "ANNOUNCE").unwrap();
    assert!(h.send(&phone, &bytes(announce, "hex")).is_none());
}

#[test]
fn error_rate_limits() {
    let h = Harness::new();
    let root = vectors("wire-v1.json");
    let bad = bytes(root["invalid"].as_array().unwrap().iter().find(|e| text(e, "name") == "bad_version").unwrap(), "hex");
    let one = "10.0.0.1:1".parse().unwrap();
    for _ in 0..100 {
        h.engine.on_datagram(&bad, one, h.now());
    }
    assert_eq!(wire::ERROR_RATE_PER_IP as usize, h.sender.count());

    h.clock.advance_ms(1000);
    h.sender.clear();
    for ip in 0..20 {
        let from = format!("10.0.1.{ip}:1").parse().unwrap();
        for _ in 0..10 {
            h.engine.on_datagram(&bad, from, h.now());
        }
    }
    assert_eq!(wire::ERROR_RATE_GLOBAL as usize, h.sender.count());
    assert!(h.sender.sent().iter().all(|s| s.0.len() == wire::ERROR_SIZE));
}

// ---- EngineLockTests ----

/// The overlays, the UI and the input log never make gp-rx wait on the engine lock for them.
#[test]
fn overlay_and_marker_reads_never_take_the_engine_lock() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.engine.set_overlay(true, false, false, false);
    h.sealed(&mut phone, packet::OVERLAY, &[0u8; wire::OVERLAY_PLAIN]);
    // The left button held: the Fire display reads it from then on.
    state(&h, &mut phone, |f| f.mouse_buttons = 1);
    let readers_done = Arc::new(AtomicBool::new(false));
    let snapshot_blocked = Arc::new(AtomicBool::new(false));
    let (engine, rd, sb) = (h.engine.clone(), readers_done.clone(), snapshot_blocked.clone());
    *h.key_mouse.on_inject.lock().unwrap() = Some(Box::new(move || {
        // Inject runs under the engine lock: every lock-free read still finishes from another thread...
        let e = engine.clone();
        rd.store(
            finishes(5000, move || {
                assert!(e.connected());
                let mut keys = [0u8; KEY_BITMAP_SIZE];
                e.held_snapshot(&mut keys);
                let _ = e.mouse_catching_up();
                assert_eq!(1, e.shared().held().read().mouse_buttons);
                let _ = e.shared().overlays().dropped();
                let _ = e.link_test_running();
                let _ = e.capturing();
            }),
            Ordering::SeqCst,
        );
        // ...while a call that takes the lock waits (so the lock really is held here).
        let e = engine.clone();
        sb.store(
            !finishes(100, move || {
                e.snapshot();
            }),
            Ordering::SeqCst,
        );
    }));
    state(&h, &mut phone, |f| f.mouse_total_x += 20);
    assert!(readers_done.load(Ordering::SeqCst));
    assert!(snapshot_blocked.load(Ordering::SeqCst));
    *h.key_mouse.on_inject.lock().unwrap() = None;
}

#[test]
fn fire_reads_the_newest_state_even_when_it_was_never_flushed() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // A STATE with the left button that no flush hands off, then the phone goes quiet: the release after
    // silence drops the pending STATE, and Fire still shows what the phone sent last.
    let d = phone.state(|f| f.mouse_buttons = 1);
    h.burst(&phone, &d);
    h.tick(wire::RELEASE_TIMEOUT_MS + 50);
    assert_eq!(1, h.engine.shared().held().read().mouse_buttons);
}

#[test]
fn input_log_lines_are_written_after_the_lock_is_let_go() {
    let h = Harness::new();
    let all_free = Arc::new(AtomicBool::new(true));
    h.engine.set_diagnostics(true); // off by default
    h.engine.set_recorder(Some(InputRecorder::new()));
    let (engine, free) = (h.engine.clone(), all_free.clone());
    *h.hooks.on_recorder.lock().unwrap() = Some(Box::new(move || {
        let e = engine.clone();
        if !finishes(5000, move || {
            e.snapshot();
        }) {
            free.store(false, Ordering::SeqCst);
        }
    }));
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.tick(1000);
    let lines = h.hooks.recorder_lines();
    assert!(lines.iter().any(|l| l.contains("session start")), "{lines:?}");
    assert!(lines.iter().any(|l| l.contains(" sec ")), "{lines:?}");
    assert!(all_free.load(Ordering::SeqCst));
    *h.hooks.on_recorder.lock().unwrap() = None;
}

#[test]
fn a_slow_hand_off_names_who_held_the_lock() {
    let h = Harness::new();
    h.engine.set_diagnostics(true); // off by default
    h.engine.set_recorder(Some(InputRecorder::new()));
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let (entered_tx, entered_rx) = std::sync::mpsc::channel::<()>();
    let (go_tx, go_rx) = std::sync::mpsc::channel::<()>();
    // The UI holds the engine lock (a change of the keyboard and mouse route) while a moving STATE comes in.
    let engine = h.engine.clone();
    let ui = std::thread::spawn(move || {
        engine.change_key_mouse_route(
            || {
                entered_tx.send(()).unwrap();
                let _ = go_rx.recv_timeout(std::time::Duration::from_secs(5));
            },
            "test",
        )
    });
    entered_rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap();
    let clock = h.clock.clone();
    let release = std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(30));
        clock.advance_ms(5);
        go_tx.send(()).unwrap();
    });
    let recv = h.now();
    let d = phone.state(|f| f.mouse_total_x += 20);
    h.engine.on_datagram(&d, phone.addr, recv);
    h.engine.flush(h.now());
    ui.join().unwrap();
    release.join().unwrap();
    let lines = h.hooks.recorder_lines();
    let slow: Vec<_> = lines.iter().filter(|l| l.contains("slow hand-off")).collect();
    assert_eq!(1, slow.len(), "{lines:?}");
    assert!(slow[0].contains("5.0 ms from receive to SendInput: engine lock"), "{}", slow[0]);
    assert!(slow[0].contains("(ui had it)"), "{}", slow[0]);
}

#[test]
fn a_slow_hand_off_names_the_time_in_send_input() {
    let h = Harness::new();
    h.engine.set_diagnostics(true); // off by default
    h.engine.set_recorder(Some(InputRecorder::new()));
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let clock = h.clock.clone();
    *h.key_mouse.on_inject.lock().unwrap() = Some(Box::new(move || clock.advance_ms(5)));
    state(&h, &mut phone, |f| f.mouse_total_x += 20);
    let lines = h.hooks.recorder_lines();
    let slow: Vec<_> = lines.iter().filter(|l| l.contains("slow hand-off")).collect();
    assert_eq!(1, slow.len(), "{lines:?}");
    assert!(slow[0].contains("SendInput"));
    assert!(!slow[0].contains("engine lock"));
}
