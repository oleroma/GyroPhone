//! The engine against the shared vectors, the mouse sequence, hi-res wheels, the pad's submit-on-change, and
//! random datagrams: ports of the engine tests in the C# GoldenVectorTests, MouseDeltaTrackerTests,
//! PadMapperTests and HeldKeysMarkerTests, plus hammering.

mod common;

use common::harness::{paired, Harness, Setup};
use common::phone::FakePhone;
use common::{arr, bytes, hex, num, rng, text, vectors};
use gp_input::codec;
use gp_input::le;
use gp_input::mapping;
use gp_input::state_frame::{StateFrame, KEY_BITMAP_SIZE};
use gp_input::wire::{self, output_mode, packet, welcome_flags};

fn state(h: &Harness, phone: &mut FakePhone, change: impl FnOnce(&mut StateFrame)) -> Option<Vec<u8>> {
    let d = phone.state(change);
    h.send(phone, &d)
}

fn named<'a>(list: &'a serde_json::Value, name: &str) -> &'a serde_json::Value {
    list.as_array().unwrap().iter().find(|p| text(p, "name") == name).unwrap()
}

/// The engine itself, seeded so its sid and nH equal the vectors, reproduces WELCOME and rejects every invalid packet.
#[test]
fn engine_reproduces_welcome_and_rejects_invalid() {
    let root = vectors("wire-v1.json");
    let (s, p) = (&root["session"], &root["pairing"]);
    let mut sid = [0u8; 4];
    le::put_u32(&mut sid, 0, num(s, "sessionId") as u32);
    let n_h = bytes(s, "nH");
    let (phone_id, pair_key) = (bytes(p, "phoneId"), bytes(s, "pairKey"));
    let key2 = pair_key.clone();
    let h = Harness::with(Setup {
        pair_default: false,
        session_random: Some(Box::new(move |b: &mut [u8]| if b.len() == 4 { b.copy_from_slice(&sid) } else { b.copy_from_slice(&n_h) })),
        settings: Some(Box::new(move |st| st.paired_phones.push(paired(&phone_id, "OnePlus 15", &key2)))),
        ..Setup::default()
    });
    let from = "127.0.0.1:5000".parse().unwrap();

    h.engine.on_datagram(&bytes(named(&root["packets"], "HELLO"), "hex"), from, h.now());
    // The vector predates HiresWheel, PcGyro and Motion; the engine also sets those flags, so re-mac the vector with them.
    let mut welcome = bytes(named(&root["packets"], "WELCOME"), "hex");
    welcome[61] |= welcome_flags::HIRES_WHEEL | welcome_flags::PC_GYRO | welcome_flags::MOTION;
    codec::write_mac(&pair_key, &mut welcome, wire::WELCOME_SIZE - wire::HELLO_MAC);
    assert_eq!(hex(&welcome), hex(&h.sender.last().unwrap()));

    for inv in root["invalid"].as_array().unwrap() {
        h.sender.clear();
        h.engine.on_datagram(&bytes(inv, "hex"), from, h.now());
        h.engine.flush(h.now());
        assert!(!h.engine.snapshot().connected, "{}", text(inv, "name"));
        assert_eq!(0, h.engine.snapshot().counters.states);
        // Only bad_version may get a (rate-limited, advisory) ERROR; nothing else is answered.
        if text(inv, "name") == "bad_version" {
            assert_eq!("4750017f000000000000000001000100", hex(&h.sender.last().unwrap()));
        } else {
            assert_eq!(0, h.sender.count(), "{}", text(inv, "name"));
        }
    }

    h.engine.on_datagram(&bytes(named(&root["packets"], "STATE_busy"), "hex"), from, h.now());
    h.engine.flush(h.now());
    assert!(h.engine.snapshot().connected);
    assert_eq!(1, h.engine.snapshot().counters.states);
    let _: [u8; 16] = arr(p, "phoneId");
}

/// The mouse sequence vector through the engine: real seq checks, real sealing, one flush per datagram.
#[test]
fn engine_applies_mouse_sequence() {
    let root = vectors("wire-v1.json");
    let seq = &root["mouseSequence"];
    let h = Harness::new();
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
    let t0 = h.now();
    for e in seq["input"].as_array().unwrap() {
        if e["dropped"].as_bool() == Some(true) {
            continue;
        }
        let s = num(e, "seq");
        let total: Vec<i32> = e["total"].as_array().unwrap().iter().map(|x| x.as_i64().unwrap() as i32).collect();
        h.clock.set(t0 + num(e, "tMs") * 1000);
        phone.seq = s as u32 - 1;
        let d = phone.state(|f| {
            f.mouse_total_x = total[0];
            f.mouse_total_y = total[1];
            f.wheel_total = total[2];
        });
        let before = h.key_mouse.calls();
        h.send(&phone, &d);
        let calls: Vec<_> = (before..h.key_mouse.calls()).flat_map(|i| h.key_mouse.call(i)).collect();
        let moves = calls.iter().filter(|e| e.flags & mapping::MOUSE_MOVE != 0);
        let dx: i64 = moves.clone().map(|e| e.dx as i64).sum();
        let dy: i64 = moves.map(|e| e.dy as i64).sum();
        let dw: i64 = calls.iter().filter(|e| e.flags & mapping::MOUSE_WHEEL != 0).map(|e| e.mouse_data as i64).sum();
        let expected = seq["expected"].as_array().unwrap().iter().find(|x| num(x, "seq") == s).unwrap();
        match expected["applied"].as_array() {
            None => assert!(calls.is_empty(), "seq {s}"),
            Some(a) => {
                let a: Vec<i64> = a.iter().map(|x| x.as_i64().unwrap()).collect();
                assert_eq!(a, vec![dx, dy, dw / mapping::WHEEL_DELTA as i64], "seq {s}");
            }
        }
    }
}

#[test]
fn engine_injects_hires_wheels() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    let hello = phone.hello(None);
    assert!(phone.on_welcome(&h.send(&phone, &hello).unwrap()));
    assert_ne!(0, phone.welcome_host_flags & welcome_flags::HIRES_WHEEL);
    phone.frame.hires = true;
    state(&h, &mut phone, |_| {});
    h.clock.advance_us(1000);
    state(&h, &mut phone, |f| {
        f.wheel_total = 30;
        f.hwheel_total = -12;
    });
    assert_eq!(30, h.key_mouse.wheel());
    assert_eq!(-12, h.key_mouse.hwheel());
}

#[test]
fn engine_submits_only_on_change() {
    let h = Harness::mode(output_mode::DUALSHOCK4);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let n = h.pad.ds4().len();
    state(&h, &mut phone, |f| f.mouse_total_x += 5); // pad unchanged
    assert_eq!(n, h.pad.ds4().len());
    state(&h, &mut phone, |f| f.lx = 32767);
    assert_eq!(n + 1, h.pad.ds4().len());
    assert_eq!(255, h.pad.ds4().last().unwrap().lx);
}

/// What the held-keys marker reads: the version moves only when the held set does.
#[test]
fn held_snapshot_changes_only_with_the_held_set() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let mut keys = [0u8; KEY_BITMAP_SIZE];
    let (mouse, v0) = h.engine.held_snapshot(&mut keys);
    assert_eq!((0, [0u8; KEY_BITMAP_SIZE]), (mouse, keys));

    state(&h, &mut phone, |f| {
        f.set_key(0x1A, true);
        f.mouse_buttons = 1;
    });
    let (mouse, v1) = h.engine.held_snapshot(&mut keys);
    assert_ne!(v0, v1);
    assert_eq!(1, mouse);
    assert_ne!(0, keys[0x1A >> 3] & (1 << (0x1A & 7)));

    // Mouse movement alone does not change the held set.
    state(&h, &mut phone, |f| f.mouse_total_x += 10);
    assert_eq!(v1, h.engine.held_snapshot(&mut keys).1);

    state(&h, &mut phone, |f| {
        f.set_key(0x1A, false);
        f.mouse_buttons = 0;
    });
    let (mouse, v2) = h.engine.held_snapshot(&mut keys);
    assert_ne!(v1, v2);
    assert_eq!((0, [0u8; KEY_BITMAP_SIZE]), (mouse, keys));
}

// ---- Hammering: nothing a network can send makes the engine panic, hold a key or lose its session. ----

fn random_bytes(next: &mut impl FnMut() -> u64, len: usize) -> Vec<u8> {
    (0..len).map(|_| next() as u8).collect()
}

#[test]
fn random_datagrams_never_panic_and_never_inject() {
    let h = Harness::new();
    let mut next = rng(0x5EED_0001);
    let sizes = [0usize, 1, 3, 4, 11, 12, 15, 16, 17, 41, 63, 64, 65, 80, 137, 148, 200, 1200, wire::MAX_DATAGRAM, wire::MAX_DATAGRAM + 1, 4000];
    for i in 0..60_000u32 {
        let len = if i % 3 == 0 { sizes[(next() % sizes.len() as u64) as usize] } else { (next() % 300) as usize };
        let mut d = random_bytes(&mut next, len);
        // Most with a good frame start, so they get past the first check; every type, every size.
        if len >= 4 && i % 4 != 0 {
            d[0] = wire::MAGIC0;
            d[1] = wire::MAGIC1;
            d[2] = if i % 97 == 0 { 2 } else { wire::VERSION };
            if let (true, Some(size)) = (i % 2 == 0, packet::size(d[3])) {
                d.resize(size, 0x5A);
            }
        }
        let from = format!("10.{}.{}.{}:{}", next() % 4, next() % 4, 1 + next() % 250, 1024 + next() % 100).parse().unwrap();
        h.engine.on_datagram(&d, from, h.now());
        if i % 5 == 0 {
            h.engine.flush(h.now());
        }
        if i % 50 == 0 {
            h.tick(7);
        }
    }
    assert_eq!(0, h.key_mouse.calls());
    assert!(h.pad.x360().is_empty());
    assert!(!h.engine.snapshot().connected);
    assert!(h.engine.settings().paired_phones.len() == 1);
}

/// A live session under fire: real packets with flipped bits, cut short, replayed and from other addresses, mixed
/// with the phone's own. The session stays up, and when the phone lets go everything is released.
#[test]
fn a_live_session_survives_damaged_copies_of_its_own_packets() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let mut next = rng(0x5EED_0002);
    let types = [packet::PING, packet::LINK_INFO, packet::OVERLAY, packet::OVERLAY_SAMPLES, packet::MOTION, packet::CAPTURE, packet::PRESS_TIME, packet::PHONE_STATS, packet::BYE];
    let mut kept: Vec<Vec<u8>> = Vec::new();
    for i in 0..20_000u32 {
        h.clock.advance_us(500);
        // The phone's own traffic: a key held, the mouse moving.
        let good = phone.state(|f| {
            f.set_key(0x1A, true);
            f.mouse_total_x += 1;
            f.phone_time_us = f.phone_time_us.wrapping_add(500);
        });
        h.send(&phone, &good);
        // A sealed packet of some type with random plaintext of the right size: authentic, so the body is what is tested.
        if i % 7 == 0 {
            let t = types[(next() % (types.len() as u64 - 1)) as usize]; // never BYE
            let size = packet::size(t).unwrap() - wire::HEADER - wire::GCM_TAG;
            let plain = random_bytes(&mut next, size);
            let d = phone.sealed(t, &plain);
            h.send(&phone, &d);
        }
        // Damaged copies.
        let mut bad = good.clone();
        match next() % 5 {
            0 => {
                let at = (next() % bad.len() as u64) as usize;
                bad[at] ^= 1 << (next() % 8);
            }
            1 => bad.truncate((next() % bad.len() as u64) as usize),
            2 => {
                let more = 1 + (next() % 40) as usize;
                bad.extend(random_bytes(&mut next, more));
            }
            3 => bad[3] = next() as u8,
            _ => {
                if let Some(old) = kept.get((next() % kept.len().max(1) as u64) as usize) {
                    bad = old.clone(); // a replay
                }
            }
        }
        let from = if next() % 2 == 0 { phone.addr } else { "10.9.9.9:999".parse().unwrap() };
        h.engine.on_datagram(&bad, from, h.now());
        h.engine.flush(h.now());
        if i % 64 == 0 {
            kept.push(good);
            h.engine.on_tick(h.now());
        }
    }
    let snap = h.engine.snapshot();
    assert!(snap.connected);
    assert!(h.key_mouse.holds_key(0x11));
    assert_eq!(20_000, h.key_mouse.mouse_x());
    state(&h, &mut phone, |f| f.set_key(0x1A, false));
    assert!(h.key_mouse.nothing_held());
}

/// A paired phone gone wrong (or its key in the wrong hands) still cannot crash the PC: authentic packets of every
/// type with random bodies, in all three pad modes, with Diagnostics and the overlays on. When it says BYE,
/// nothing is left held.
#[test]
fn authentic_packets_with_random_bodies_never_panic_and_bye_releases() {
    for (round, mode) in [output_mode::XBOX360, output_mode::DUALSHOCK4, output_mode::DUALSENSE, output_mode::NONE].into_iter().enumerate() {
        let h = Harness::mode(mode);
        h.engine.set_diagnostics(true);
        h.engine.set_recorder(Some(gp_input::recorder::InputRecorder::new()));
        h.engine.set_overlay(true, true, true, true);
        h.engine.set_measure_buttons(true);
        let mut phone = h.new_phone();
        h.connect(&mut phone);
        let mut next = rng(0x5EED_0100 + round as u64);
        for i in 0..15_000u32 {
            h.clock.advance_us(next() as i64 % 3000);
            let t = 0x20 + (next() % 15) as u8; // every phone-to-host sealed type but BYE
            let Some(size) = packet::size(t) else { continue };
            let plain = random_bytes(&mut next, size - wire::HEADER - wire::GCM_TAG);
            let d = phone.sealed(t, &plain);
            if next() % 3 == 0 {
                phone.seq = phone.seq.wrapping_add((next() % 40) as u32); // lost packets
            }
            h.engine.on_datagram(&d, phone.addr, h.now());
            if next() % 4 != 0 {
                h.engine.flush(h.now());
            }
            if i % 37 == 0 {
                h.engine.on_tick(h.now());
            }
            if i % 2000 == 0 {
                h.engine.snapshot();
                h.engine.set_paused(i % 4000 == 0);
            }
        }
        h.engine.set_paused(false);
        // Whatever it was told to hold, an ordinary STATE with nothing pressed lets go of, and BYE ends it.
        let rest = phone.state(|f| *f = StateFrame::default());
        h.send(&phone, &rest);
        let bye = phone.bye(wire::bye_reason::USER);
        h.send(&phone, &bye);
        h.tick(wire::WATCHDOG_TICK_MS);
        assert!(h.key_mouse.nothing_held(), "mode {mode}");
        assert!(!h.engine.snapshot().connected);
    }
}
