//! The engine's sessions end to end: ports of the C# SessionManagerTests that run through HostEngine.

mod common;

use common::harness::{paired, Harness, Setup, PHONE1_ID};
use common::phone::FakePhone;
use common::{hex, range_n};
use gp_input::codec::{self, Rumble};
use gp_input::le;
use gp_input::mapping::{self, xusb_buttons, InjectKind, X360Frame};
use gp_input::state_frame::HAT_CENTER;
use gp_input::wire::{self, bye_reason, error_code, host_bye_reason, host_status_flags, host_status_flags2, output_mode, packet, rumble_pattern};

const USAGE_W: u32 = 0x1A;
const SCAN_W: u16 = 0x11;

fn state(h: &Harness, phone: &mut FakePhone, change: impl FnOnce(&mut gp_input::state_frame::StateFrame)) -> Option<Vec<u8>> {
    let d = phone.state(change);
    h.send(phone, &d)
}

fn burst(h: &Harness, phone: &mut FakePhone, change: impl FnOnce(&mut gp_input::state_frame::StateFrame)) {
    let d = phone.state(change);
    h.burst(phone, &d);
}

#[test]
fn pending_session_promoted_on_first_state() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
    assert!(!h.engine.snapshot().connected);
    assert_eq!(output_mode::NONE, h.pad.plugged_now());

    h.sender.clear();
    state(&h, &mut phone, |_| {});
    let snap = h.engine.snapshot();
    assert!(snap.connected);
    assert_eq!(Some("OnePlus 15".to_string()), snap.phone_name);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    // HOST_STATUS goes out right after promotion.
    let (t, st) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::HOST_STATUS, t);
    assert_ne!(0, codec::decode_host_status(&st).flags & host_status_flags::PAD_PLUGGED);
}

#[test]
fn status_says_whether_the_cable_is_on_and_tells_at_once_when_it_changes() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // Off by default. Each change goes out at once, not with the next second's status.
    assert!(!h.engine.settings().usb_direct);
    h.sender.clear();
    h.engine.set_usb_direct(true);
    let (t, on) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::HOST_STATUS, t);
    assert_eq!(host_status_flags2::USB_DIRECT, codec::decode_host_status(&on).flags2);
    h.sender.clear();
    h.engine.set_usb_direct(false);
    let (t, off) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::HOST_STATUS, t);
    assert_eq!(0, codec::decode_host_status(&off).flags2);
}

#[test]
fn replayed_hello_does_not_disturb_active_session() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    let welcome = h.send(&phone, &hello).unwrap();
    assert!(phone.on_welcome(&welcome));
    state(&h, &mut phone, |f| f.set_key(USAGE_W, true));
    let injections = h.key_mouse.calls();

    // Exact retransmit: the same WELCOME, same sid.
    assert_eq!(Some(welcome.clone()), h.send(&phone, &hello));
    // An attacker replays it from elsewhere: still nothing changes.
    let attacker = h.phone_at("10.0.0.99:1234");
    assert_eq!(Some(welcome), h.send(&attacker, &hello));
    assert!(h.engine.snapshot().connected);
    assert_eq!(injections, h.key_mouse.calls());
    assert!(!h.key_mouse.nothing_held());

    // An old HELLO (other nP) only makes a pending session that expires; the active one keeps working.
    let mut old = h.phone_at("10.0.0.99:1234");
    let old_hello = old.hello(None);
    assert!(h.send(&old, &old_hello).is_some());
    h.tick(wire::PENDING_SESSION_MS + 50);
    state(&h, &mut phone, |_| {});
    assert!(h.engine.snapshot().connected);
}

#[test]
fn replayed_hello_never_replaces_the_newer_pending_session() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let first = phone.hello(None);
    assert!(h.send(&phone, &first).is_some());
    h.tick(wire::PENDING_SESSION_MS + 50);

    // The phone tries again with a new nP; an attacker keeps replaying the captured first HELLO.
    let attacker = h.phone_at("10.0.0.99:1234");
    let second = phone.hello(None);
    let welcome2 = h.send(&phone, &second).unwrap();
    assert!(h.send(&attacker, &first).is_none());
    assert!(phone.on_welcome(&welcome2));
    h.sender.clear();
    state(&h, &mut phone, |_| {});
    // The new session came up: its first STATE drew no SESSION_UNKNOWN.
    assert!(h.engine.snapshot().connected);
    assert!(h.sender.sent().iter().all(|p| codec::decode_error(&p.0).is_none()));
}

#[test]
fn same_hello_after_the_pending_session_expired_still_connects() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    assert!(h.send(&phone, &hello).is_some()); // this WELCOME is lost
    h.tick(wire::PENDING_SESSION_MS + 50);
    let w = h.send(&phone, &hello).unwrap();
    assert!(phone.on_welcome(&w));
    state(&h, &mut phone, |_| {});
    assert!(h.engine.snapshot().connected);
}

#[test]
fn a_replayed_older_hello_does_not_block_the_resent_newer_one() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let first = phone.hello(None);
    assert!(h.send(&phone, &first).is_some());
    h.tick(wire::PENDING_SESSION_MS + 50);
    let second = phone.hello(None);
    assert!(h.send(&phone, &second).is_some()); // this WELCOME is lost
    h.tick(wire::PENDING_SESSION_MS + 50);

    // With nothing pending, a replay of the first HELLO gets a pending session of its own...
    let attacker = h.phone_at("10.0.0.99:1234");
    h.send(&attacker, &first);
    // ...but the phone's resent, newer HELLO still replaces it and connects.
    let w = h.send(&phone, &second).unwrap();
    assert!(phone.on_welcome(&w));
    state(&h, &mut phone, |_| {});
    assert!(h.engine.snapshot().connected);
}

#[test]
fn forged_packets_from_elsewhere_are_limited_but_the_session_keeps_working_and_can_move() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let attacker = "192.168.1.99:5000".parse().unwrap();
    let mut forged = phone.state(|_| {});
    for i in 0..200u32 {
        le::put_u32(&mut forged, 8, 0xF000_0000 + i); // sid and seq are in clear; the tag no longer matches
        h.engine.on_datagram(&forged, attacker, h.now());
    }
    assert_eq!(200, h.engine.snapshot().counters.bad_auth);
    // The phone at its own address is never limited.
    state(&h, &mut phone, |f| f.set_key(USAGE_W, true));
    assert!(h.key_mouse.holds_key(SCAN_W));
    // A move to a new address goes through once the second is over.
    h.clock.advance_ms(1000);
    phone.addr = "192.168.1.51:40001".parse().unwrap();
    state(&h, &mut phone, |f| f.set_key(USAGE_W, false));
    assert!(h.key_mouse.nothing_held());
    assert!(h.engine.snapshot().connected);
}

#[test]
fn bad_mac_gets_no_reply() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let mut hello = phone.hello(None);
    *hello.last_mut().unwrap() ^= 1;
    assert!(h.send(&phone, &hello).is_none());
    assert_eq!(1, h.engine.snapshot().counters.bad_auth);
}

#[test]
fn unknown_phone_gets_not_paired() {
    let h = Harness::new();
    let mut stranger = FakePhone::paired(range_n(0x90), "Other", range_n(0x20), "192.168.1.50:40000");
    let hello = stranger.hello(None);
    assert_eq!("4750017f000000000000000002000100", hex(&h.send(&stranger, &hello).unwrap()));
}

#[test]
fn unknown_session_gets_session_unknown() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // Host restarted: a fresh engine has no session.
    let h2 = Harness::new();
    let reply = state(&h2, &mut phone, |_| {}).unwrap();
    assert_eq!(error_code::SESSION_UNKNOWN, codec::decode_error(&reply).unwrap().code);
}

#[test]
fn release_all_exactly_once_at_250_ms() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.mouse_buttons = 1;
        f.lx = 20000;
    });
    assert!(h.key_mouse.holds_key(SCAN_W));
    let calls = h.key_mouse.calls();
    let pad_submits = h.pad.x360().len();

    for _ in 0..4 {
        h.tick(50); // 200 ms: nothing yet
    }
    assert_eq!(calls, h.key_mouse.calls());
    h.tick(50); // 250 ms
    assert_eq!(calls + 1, h.key_mouse.calls());
    assert!(h.key_mouse.nothing_held());
    assert_eq!(X360Frame::NEUTRAL, *h.pad.x360().last().unwrap());
    assert_eq!(pad_submits + 1, h.pad.x360().len());
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now()); // the pad stays plugged

    for _ in 0..20 {
        h.tick(50);
    }
    assert_eq!(calls + 1, h.key_mouse.calls());

    // Traffic resumes: the held state comes back, mouse rebases (no jump).
    let mx = h.key_mouse.mouse_x();
    state(&h, &mut phone, |f| f.mouse_total_x += 5000);
    assert!(h.key_mouse.holds_key(SCAN_W));
    assert_eq!(mx, h.key_mouse.mouse_x());
}

#[test]
fn session_drops_at_10_seconds() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.tick(wire::SESSION_TIMEOUT_MS - 100);
    assert!(h.engine.snapshot().connected);
    h.tick(150);
    assert!(!h.engine.snapshot().connected);
    // The pad stays plugged, at rest, for the next session.
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now());
    assert_eq!(0, h.pad.unplugs());
    assert_eq!(X360Frame::NEUTRAL, *h.pad.x360().last().unwrap());
}

#[test]
fn up_dropped_by_windows_is_sent_again() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.mouse_buttons = 1;
    });
    assert!(h.key_mouse.holds_key(SCAN_W));
    // An admin window comes to the front and the user lets go: Windows drops the ups.
    h.key_mouse.set_dropping(true);
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, false);
        f.mouse_buttons = 0;
    });
    h.tick(50);
    assert!(h.key_mouse.holds_key(SCAN_W));
    assert!(h.engine.snapshot().elevated_foreground);
    // Focus comes back: the next tick sends the ups again.
    h.key_mouse.set_dropping(false);
    h.tick(50);
    assert!(h.key_mouse.nothing_held());
    assert!(!h.engine.snapshot().elevated_foreground);
    let calls = h.key_mouse.calls();
    h.tick(50);
    assert_eq!(calls, h.key_mouse.calls());
}

#[test]
fn bye_releases() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.mouse_buttons = 0b10000;
    });
    assert!(!h.key_mouse.nothing_held());
    let bye = phone.bye(bye_reason::TRANSPORT_SWITCH);
    h.send(&phone, &bye);
    assert!(h.key_mouse.nothing_held());
    assert!(!h.engine.snapshot().connected);
    assert_eq!(output_mode::XBOX360, h.pad.plugged_now()); // kept, neutral, for the next session
}

#[test]
fn replacement_sends_host_bye_to_old_endpoint() {
    let second_id: [u8; 16] = range_n(0x80);
    let second_key: [u8; 32] = range_n(0xA0);
    let h = Harness::with(Setup {
        settings: Some(Box::new(move |s| s.paired_phones.push(paired(&second_id, "Pixel", &second_key)))),
        ..Setup::default()
    });
    let mut a = h.phone_at("192.168.1.50:40000");
    let mut b = FakePhone::paired(second_id, "Pixel", second_key, "192.168.1.77:41000");

    let hello = a.hello(None);
    assert!(a.on_welcome(&h.send(&a, &hello).unwrap()));
    state(&h, &mut a, |f| f.set_key(USAGE_W, true));
    assert!(!h.key_mouse.nothing_held());

    let hello = b.hello(None);
    assert!(b.on_welcome(&h.send(&b, &hello).unwrap()));
    assert_eq!(Some("OnePlus 15".to_string()), h.engine.snapshot().phone_name); // HELLO alone does not replace
    h.sender.clear();
    state(&h, &mut b, |_| {});
    assert_eq!(Some("Pixel".to_string()), h.engine.snapshot().phone_name);
    assert!(h.key_mouse.nothing_held());

    let to_a = h.sender.to(a.addr);
    assert_eq!(3, to_a.len());
    for d in to_a {
        let (t, plain) = a.try_open(&d).unwrap();
        assert_eq!(packet::HOST_BYE, t);
        assert_eq!(host_bye_reason::REPLACED, plain[0]);
    }
    // The old session is gone: its STATEs are now unknown.
    let reply = state(&h, &mut a, |_| {}).unwrap();
    assert_eq!(error_code::SESSION_UNKNOWN, codec::decode_error(&reply).unwrap().code);
}

#[test]
fn same_phone_replacement_keeps_held_input() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.mouse_buttons = 1;
        f.buttons = 1;
        f.mouse_total_x = 500;
    });
    let old_sid = phone.session_id;
    let late = phone.state(|_| {}); // an old-session copy still in flight
    let calls = h.key_mouse.calls();
    let frames = h.pad.x360().len();
    let mx = h.key_mouse.mouse_x();

    // Network change: a new HELLO from the same phone; its totals restart at 0.
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
    assert_ne!(old_sid, phone.session_id);
    h.sender.clear();
    state(&h, &mut phone, |f| f.mouse_total_x = 0);
    assert!(h.engine.snapshot().connected);
    assert_eq!(calls, h.key_mouse.calls()); // no ups, no re-press
    assert!(!h.pad.x360()[frames..].contains(&X360Frame::NEUTRAL));
    assert!(h.key_mouse.holds_key(SCAN_W));
    assert!(h.key_mouse.holds_button("L"));
    assert!(h.sender.sent().iter().all(|x| x.0[3] != packet::HOST_BYE));

    // The late old-session packet is dropped silently (an ERROR would restart the phone's session).
    h.sender.clear();
    assert!(h.send(&phone, &late).is_none());

    // Motion continues from the new baseline, and a dropped key is released once.
    state(&h, &mut phone, |f| {
        f.mouse_total_x = 7;
        f.set_key(USAGE_W, false);
    });
    assert_eq!(mx + 7, h.key_mouse.mouse_x()); // the new session's first STATE is its baseline
    assert!(!h.key_mouse.holds_key(SCAN_W));
    assert_eq!(1, h.key_mouse.all().iter().filter(|e| e.kind == InjectKind::Key && e.is_key_up()).count());
}

#[test]
fn tap_inside_one_burst_is_still_injected() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let frames = h.pad.x360().len();
    // Press and release reach the socket together: one drain, one flush.
    burst(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.mouse_buttons = 1;
        f.buttons = 1;
    });
    burst(&h, &mut phone, |f| f.mouse_total_x += 3); // a repeat with motion only
    burst(&h, &mut phone, |f| {
        f.set_key(USAGE_W, false);
        f.mouse_buttons = 0;
        f.buttons = 0;
    });
    h.flush();

    let all = h.key_mouse.all();
    assert!(all.iter().any(|e| e.kind == InjectKind::Key && !e.is_key_up()));
    assert!(all.iter().any(|e| e.kind == InjectKind::Key && e.is_key_up()));
    assert!(all.iter().any(|e| e.flags & mapping::MOUSE_LEFT_DOWN != 0 && e.kind != InjectKind::Key));
    assert!(all.iter().any(|e| e.flags & mapping::MOUSE_LEFT_UP != 0 && e.kind != InjectKind::Key));
    assert!(h.key_mouse.nothing_held());
    assert_eq!(3, h.key_mouse.mouse_x());

    // The pad button stays visible to a polling game for a moment, then lets go.
    assert_eq!(xusb_buttons::A, h.pad.x360().last().unwrap().buttons);
    assert_eq!(1, h.pad.x360()[frames..].iter().filter(|f| f.buttons == xusb_buttons::A).count());
    h.clock.advance_ms(5);
    state(&h, &mut phone, |_| {}); // a keepalive inside the hold changes nothing
    assert_eq!(xusb_buttons::A, h.pad.x360().last().unwrap().buttons);
    h.tick(wire::WATCHDOG_TICK_MS);
    assert_eq!(0, h.pad.x360().last().unwrap().buttons);
}

#[test]
fn long_held_pad_button_released_inside_burst_lets_go_at_once() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| {
        f.buttons = 1;
        f.hat = 0;
    });
    h.clock.advance_ms(500);
    // One drain: the stick moves while A and up are still held, then both are released.
    burst(&h, &mut phone, |f| f.rx = 9000);
    burst(&h, &mut phone, |f| {
        f.buttons = 0;
        f.hat = HAT_CENTER;
    });
    h.flush();
    assert_eq!(0, h.pad.x360().last().unwrap().buttons);
}

#[test]
fn key_edge_does_not_extend_held_pad_button() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| f.buttons = 1);
    h.clock.advance_ms(500);
    // A key tap inside one drain while A is held steadily.
    burst(&h, &mut phone, |f| f.set_key(USAGE_W, true));
    burst(&h, &mut phone, |f| f.set_key(USAGE_W, false));
    h.flush();
    h.clock.advance_ms(3);
    state(&h, &mut phone, |f| f.buttons = 0);
    assert_eq!(0, h.pad.x360().last().unwrap().buttons);
}

#[test]
fn pad_tap_spanning_several_burst_frames_is_held() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // A is pressed, a key edge follows, then A is released: all in one drain.
    burst(&h, &mut phone, |f| f.buttons = 1);
    burst(&h, &mut phone, |f| f.set_key(USAGE_W, true));
    burst(&h, &mut phone, |f| {
        f.buttons = 0;
        f.set_key(USAGE_W, false);
    });
    h.flush();
    assert_eq!(xusb_buttons::A, h.pad.x360().last().unwrap().buttons);
    h.tick(wire::WATCHDOG_TICK_MS);
    assert_eq!(0, h.pad.x360().last().unwrap().buttons);
}

#[test]
fn pad_failure_never_stops_keyboard_mouse_or_release() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.pad.set(|p| p.fail_with = Some("bus gone"));

    state(&h, &mut phone, |f| {
        f.set_key(USAGE_W, true);
        f.buttons = 1;
        f.mouse_total_x += 4;
    });
    assert!(h.key_mouse.holds_key(SCAN_W));
    assert_eq!(4, h.key_mouse.mouse_x());
    state(&h, &mut phone, |f| f.buttons = 2);
    assert_eq!(1, h.events.logged("pad submit failed"));

    // Silence still releases the keys, and the session still ends cleanly.
    h.tick(wire::RELEASE_TIMEOUT_MS + 10);
    assert!(h.key_mouse.nothing_held());
    h.tick(wire::SESSION_TIMEOUT_MS);
    assert!(!h.engine.snapshot().connected);
}

#[test]
fn pad_recovers_after_failure() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.pad.set(|p| p.fail_with = Some("bus gone"));
    state(&h, &mut phone, |f| f.buttons = 1);
    h.pad.set(|p| p.fail_with = None);
    let frames = h.pad.x360().len();
    state(&h, &mut phone, |f| f.mouse_total_x += 1); // a broken bus is not retried on every packet
    assert_eq!(frames, h.pad.x360().len());
    h.clock.advance_ms(100);
    state(&h, &mut phone, |f| f.mouse_total_x += 1); // same pad frame: sent again, it never arrived
    assert_eq!(xusb_buttons::A, h.pad.x360().last().unwrap().buttons);
}

#[test]
fn motion_only_burst_injects_once() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let calls = h.key_mouse.calls();
    for _ in 0..4 {
        burst(&h, &mut phone, |f| f.mouse_total_x += 2);
    }
    h.flush();
    assert_eq!(calls + 1, h.key_mouse.calls());
    assert_eq!(8, h.key_mouse.mouse_x());
}

#[test]
fn only_authenticated_packet_moves_endpoint() {
    let h = Harness::new();
    let mut phone = h.phone_at("192.168.1.50:40000");
    h.connect(&mut phone);
    let spoof = "10.6.6.6:666".parse().unwrap();

    // Tampered packet from another address: dropped, endpoint unchanged.
    let mut bad = phone.state(|_| {});
    *bad.last_mut().unwrap() ^= 1;
    h.engine.on_datagram(&bad, spoof, h.now());
    // Replayed (old seq) valid packet from another address: dropped too.
    let first = phone.state(|_| {});
    h.send(&phone, &first);
    h.engine.on_datagram(&first, spoof, h.now());

    h.sender.clear();
    h.tick(wire::STATUS_INTERVAL_MS);
    assert_eq!(1, h.sender.count());
    assert_eq!(phone.addr, h.sender.sent()[0].1);

    // A valid packet from the new address (phone roamed) moves it.
    let roamed = "192.168.1.51:40001".parse().unwrap();
    let d = phone.state(|_| {});
    h.engine.on_datagram(&d, roamed, h.now());
    h.sender.clear();
    h.tick(wire::STATUS_INTERVAL_MS);
    assert_eq!(roamed, h.sender.sent()[0].1);
}

#[test]
fn ping_gets_pong() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let ping = phone.ping(123);
    let pong = h.send(&phone, &ping).unwrap();
    let (t, plain) = phone.try_open(&pong).unwrap();
    assert_eq!(packet::PONG, t);
    let p = codec::decode_pong(&plain);
    assert_eq!(123, p.t1);
    assert_eq!(h.now() as u64, p.t2);
}

#[test]
fn host_to_phone_seq_strictly_increases() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    for i in 0..5 {
        let ping = phone.ping(i);
        h.send(&phone, &ping);
    }
    let mut last = 0;
    for (d, _) in h.sender.sent().iter().filter(|s| matches!(s.0[3], packet::PONG | packet::HOST_STATUS)) {
        let seq = le::u32(d, 8);
        assert!(seq > last);
        last = seq;
    }
}

#[test]
fn pause_injects_nothing_but_keeps_baseline() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    state(&h, &mut phone, |f| f.set_key(USAGE_W, true));
    h.engine.set_paused(true);
    assert!(h.key_mouse.nothing_held());
    let calls = h.key_mouse.calls();
    state(&h, &mut phone, |f| f.mouse_total_x += 100);
    assert_eq!(calls, h.key_mouse.calls());
    h.engine.set_paused(false);
    state(&h, &mut phone, |f| f.mouse_total_x += 7);
    assert_eq!(7, h.key_mouse.mouse_x()); // the paused 100 never lands
}

#[test]
fn forget_ends_session_with_host_bye() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.sender.clear();
    assert!(h.engine.forget(&PHONE1_ID));
    assert!(!h.engine.snapshot().connected);
    assert!(h.engine.settings().paired_phones.is_empty());
    let byes = h.opened(&mut phone, packet::HOST_BYE);
    assert_eq!(3, byes.iter().filter(|p| p[0] == host_bye_reason::FORGOTTEN).count());
}

fn rumbles(h: &Harness, phone: &mut FakePhone) -> Vec<Rumble> {
    h.opened(phone, packet::RUMBLE).iter().map(|p| codec::decode_rumble(p)).collect()
}

fn motors(large: u8, small: u8) -> Rumble {
    Rumble { large, small, pattern: rumble_pattern::MOTORS }
}

#[test]
fn rumble_is_forwarded_and_rate_limited() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.sender.clear();
    h.pad.raise_rumble(255, 64);
    assert_eq!(0, h.sender.count()); // the driver thread only records it
    h.flush();
    assert_eq!(1, h.sender.count());
    let (t, p) = phone.try_open(&h.sender.at(0)).unwrap();
    assert_eq!(packet::RUMBLE, t);
    assert_eq!(motors(255, 64), codec::decode_rumble(&p));

    h.pad.raise_rumble(10, 10);
    h.pad.raise_rumble(0, 0);
    h.flush(); // within 10 ms: held back
    assert_eq!(1, h.sender.count());
    h.clock.advance_ms(10);
    h.flush(); // newest value wins
    assert_eq!(2, h.sender.count());
    let (t, p) = phone.try_open(&h.sender.at(1)).unwrap();
    assert_eq!(packet::RUMBLE, t);
    assert_eq!(motors(0, 0), codec::decode_rumble(&p));
}

#[test]
fn rumble_repeats_while_on_and_a_few_times_after_it_stops() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.sender.clear();
    h.pad.raise_rumble(200, 0);
    h.flush();
    assert_eq!(1, rumbles(&h, &mut phone).len());
    // Flushes (the input path) never repeat it; the watchdog does once a second.
    h.clock.advance_ms(1500);
    h.flush();
    assert_eq!(1, rumbles(&h, &mut phone).len());
    // The phone goes on sending (keepalives), so the session stays up while the watchdog runs.
    for _ in 0..100 {
        state(&h, &mut phone, |_| {});
        h.tick(50); // 5 s
    }
    let on = rumbles(&h, &mut phone).len();
    assert!((5..=7).contains(&on), "{on}");
    assert!(rumbles(&h, &mut phone).iter().all(|r| *r == motors(200, 0)));
    // Off: sent, then 3 more times, then quiet.
    h.pad.raise_rumble(0, 0);
    for _ in 0..200 {
        state(&h, &mut phone, |_| {});
        h.tick(50); // 10 s
    }
    let all = rumbles(&h, &mut phone);
    assert_eq!(on + 1 + 3, all.len());
    assert!(all[on..].iter().all(|r| *r == motors(0, 0)));
}

#[test]
fn a_new_session_gets_the_ongoing_rumble() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.pad.raise_rumble(120, 30);
    h.flush();
    // The phone reconnects (a new session, its rumble off) while the game keeps rumbling the same.
    let mut again = h.new_phone();
    h.sender.clear();
    h.clock.advance_ms(20);
    h.connect(&mut again);
    assert!(rumbles(&h, &mut again).contains(&motors(120, 30)));
}

#[test]
fn buzz_is_sent_twice_with_its_pattern() {
    let h = Harness::new();
    h.engine.buzz(); // no phone: nothing
    assert_eq!(0, h.sender.count());
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.sender.clear();
    h.engine.buzz();
    assert_eq!(2, h.sender.count());
    let (t, p) = phone.try_open(&h.sender.at(0)).unwrap();
    assert_eq!(packet::RUMBLE, t);
    assert_eq!(Rumble { large: 0, small: 0, pattern: rumble_pattern::BUZZ }, codec::decode_rumble(&p));
}
