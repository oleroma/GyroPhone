//! Ports of InputDiffTests, KeyRepeatTests, PadMapperTests, MouseDeltaTrackerTests,
//! MouseCatchUpTests and the keyboard usages golden test. The tests that go through the engine
//! are in `engine.rs`.

mod common;

use gp_input::mapping::*;
use gp_input::mouse::{MouseCatchUp, MouseDeltaTracker};
use gp_input::state_frame::StateFrame;
use gp_input::wire;

// ---- InputDiff -------------------------------------------------------------------------------

struct Differ {
    injected: [u8; 32],
    mouse: u8,
    unknown: u32,
}

impl Differ {
    fn new() -> Self {
        Differ { injected: [0; 32], mouse: 0, unknown: 0 }
    }

    fn diff5(&mut self, target: &StateFrame, dx: i32, dy: i32, wheel: i32, hwheel: i32) -> Vec<InjectEvent> {
        let mut out = [InjectEvent::default(); input_diff::MAX_EVENTS];
        let n = input_diff::diff(
            &mut self.injected,
            &target.keys,
            &mut self.mouse,
            target.mouse_buttons,
            dx,
            dy,
            wheel,
            hwheel,
            &mut out,
            &mut self.unknown,
        );
        out[..n].to_vec()
    }

    fn diff(&mut self, target: &StateFrame) -> Vec<InjectEvent> {
        self.diff5(target, 0, 0, 0, 0)
    }
}

fn keys(usages: &[u32]) -> StateFrame {
    let mut s = StateFrame::default();
    for &u in usages {
        s.set_key(u, true);
    }
    s
}

fn single(e: &[InjectEvent], usage: u8) -> InjectEvent {
    let v: Vec<_> = e.iter().filter(|x| x.usage == usage && x.kind == InjectKind::Key).collect();
    assert_eq!(v.len(), 1, "usage {usage:#x}");
    *v[0]
}

#[test]
fn order_is_mod_downs_key_downs_buttons_key_ups_mod_ups_move_wheel() {
    let mut d = Differ::new();
    let mut s = keys(&[0x04, 0xE0]); // A, LCtrl
    s.mouse_buttons = 1;
    d.diff(&s);

    let mut t = keys(&[0x05, 0xE1]); // B down, LShift down; LCtrl up; A up
    t.mouse_buttons = 2; // L up, R down
    let e = d.diff5(&t, 3, -4, 240, 0);

    assert_eq!(e.len(), 8);
    assert_eq!((e[0].usage, e[0].is_key_up()), (0xE1, false));
    assert_eq!((e[1].usage, e[1].is_key_up()), (0x05, false));
    assert_eq!(e[2].flags, MOUSE_LEFT_UP);
    assert_eq!(e[3].flags, MOUSE_RIGHT_DOWN);
    assert_eq!((e[4].usage, e[4].is_key_up()), (0x04, true));
    assert_eq!((e[5].usage, e[5].is_key_up()), (0xE0, true));
    assert_eq!((e[6].flags, e[6].dx, e[6].dy), (MOUSE_MOVE, 3, -4));
    assert_eq!(e[7].flags, MOUSE_WHEEL);
    assert_eq!(e[7].mouse_data, 240);
}

#[test]
fn scancode_and_extended_flags() {
    let mut d = Differ::new();
    let e = d.diff(&keys(&[0x52, 0x1A, 0xE4])); // Up arrow E0 48, W 11, RCtrl E0 1D
    let up = single(&e, 0x52);
    assert_eq!(up.scan, 0x48);
    assert_eq!(up.flags, KEY_SCANCODE | KEY_EXTENDED);
    let w = single(&e, 0x1A);
    assert_eq!(w.scan, 0x11);
    assert_eq!(w.flags, KEY_SCANCODE);
    let rctrl = single(&e, 0xE4);
    assert_eq!(rctrl.scan, 0x1D);
    assert_eq!(rctrl.flags, KEY_SCANCODE | KEY_EXTENDED);

    let released = d.diff(&StateFrame::default());
    assert!(released.iter().all(|x| x.is_key_up()));
    assert_eq!(single(&released, 0x52).flags, KEY_SCANCODE | KEY_EXTENDED | KEY_UP_FLAG);
}

#[test]
fn num_lock_is_extended_and_pause_uses_vk() {
    let mut d = Differ::new();
    let e = d.diff(&keys(&[0x53, 0x48]));
    let num_lock = single(&e, 0x53);
    assert_eq!(num_lock.scan, 0x45);
    assert_ne!(num_lock.flags & KEY_EXTENDED, 0);
    let pause = single(&e, 0x48);
    assert_eq!(pause.vk, VK_PAUSE);
    assert_eq!(pause.flags, 0);
}

#[test]
fn hires_wheels_pass_fractions() {
    let mut d = Differ::new();
    let e = d.diff5(&StateFrame::default(), 0, 0, 17, -5);
    assert_eq!((e[0].flags, e[0].mouse_data), (MOUSE_WHEEL, 17));
    assert_eq!((e[1].flags, e[1].mouse_data), (MOUSE_HWHEEL, -5));
}

#[test]
fn x_buttons_and_wheel() {
    let mut d = Differ::new();
    let s = StateFrame { mouse_buttons: 0b11000, ..Default::default() };
    let e = d.diff5(&s, 0, 0, -360, 0);
    assert_eq!((e[0].flags, e[0].mouse_data), (MOUSE_X_DOWN, XBUTTON1));
    assert_eq!((e[1].flags, e[1].mouse_data), (MOUSE_X_DOWN, XBUTTON2));
    assert_eq!(e[2].mouse_data, -360);
    let r = d.diff(&StateFrame::default());
    assert_eq!((r[0].flags, r[0].mouse_data), (MOUSE_X_UP, XBUTTON1));
    assert_eq!((r[1].flags, r[1].mouse_data), (MOUSE_X_UP, XBUTTON2));
}

#[test]
fn reserved_and_unknown_usages_are_ignored_and_counted_once() {
    let mut d = Differ::new();
    let mut s = keys(&[0x66]); // Power: no scan code
    s.keys[0] = 0x0F; // usages 0..3
    assert!(d.diff(&s).is_empty());
    assert_eq!(d.unknown, 1);
    assert!(d.diff(&s).is_empty());
    assert_eq!(d.unknown, 1);
    assert!(d.diff(&StateFrame::default()).is_empty());
}

#[test]
fn no_events_when_nothing_changed() {
    let mut d = Differ::new();
    let s = keys(&[0x04]);
    assert_eq!(d.diff(&s).len(), 1);
    assert!(d.diff(&s).is_empty());
}

#[test]
fn release_all_order_buttons_then_keys_then_modifiers() {
    let mut d = Differ::new();
    let mut s = keys(&[0xE2, 0x2C]);
    s.mouse_buttons = 0b101;
    d.diff(&s);
    let mut buf = [InjectEvent::default(); 300];
    let n = input_diff::release_all(&mut d.injected, &mut d.mouse, &mut buf);
    assert_eq!(n, 4);
    assert_eq!(buf[0].flags, MOUSE_LEFT_UP);
    assert_eq!(buf[1].flags, MOUSE_MIDDLE_UP);
    assert_eq!(buf[2].usage, 0x2C);
    assert_eq!(buf[3].usage, 0xE2);
    assert!(!input_diff::any_held(&d.injected, d.mouse));
}

#[test]
fn ctrl_click_presses_ctrl_first_and_releases_it_last() {
    let mut d = Differ::new();
    let mut s = keys(&[0xE0]);
    s.mouse_buttons = 1;
    let down = d.diff(&s);
    assert_eq!(down.len(), 2);
    assert_eq!((down[0].usage, down[0].is_key_up()), (0xE0, false));
    assert_eq!(down[1].flags, MOUSE_LEFT_DOWN);

    let up = d.diff(&StateFrame::default());
    assert_eq!(up.len(), 2);
    assert_eq!(up[0].flags, MOUSE_LEFT_UP);
    assert_eq!((up[1].usage, up[1].is_key_up()), (0xE0, true));
}

#[test]
fn overflow_carries_to_next_call() {
    let mut d = Differ::new();
    let mut s = StateFrame::default();
    for u in 0x04..=0x73 {
        s.set_key(u, true); // ~110 keys, more than fit
    }
    s.mouse_buttons = 1;
    let first = d.diff5(&s, 1, 1, 120, 0);
    assert!(first.len() <= input_diff::MAX_EVENTS);
    assert!(first.iter().any(|x| x.kind == InjectKind::Mouse && x.flags == MOUSE_LEFT_DOWN));
    assert!(first.iter().any(|x| x.kind == InjectKind::Mouse && x.flags == MOUSE_MOVE));
    let second = d.diff(&s);
    assert!(!second.is_empty());
    assert!(d.diff(&s).is_empty());
}

/// Every key bitmap a phone could send, every output size: nothing panics, every down gets its up,
/// and a full release leaves nothing held.
#[test]
fn any_bitmap_and_any_output_size_is_safe_and_releases_fully() {
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    let mut next = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for round in 0..2000 {
        let mut d = Differ::new();
        let mut held = std::collections::BTreeSet::new();
        for _ in 0..4 {
            let mut s = StateFrame::default();
            for b in s.keys.iter_mut() {
                *b = if round % 3 == 0 { 0xFF } else { next() as u8 & next() as u8 };
            }
            s.mouse_buttons = next() as u8;
            let size = (next() % 120) as usize;
            let mut out = vec![InjectEvent::default(); size];
            // Until nothing more comes out, as the engine's flush loop does.
            for _ in 0..40 {
                let n = input_diff::diff(
                    &mut d.injected,
                    &s.keys,
                    &mut d.mouse,
                    s.mouse_buttons,
                    0,
                    0,
                    0,
                    0,
                    &mut out,
                    &mut d.unknown,
                );
                assert!(n <= size.min(input_diff::MAX_EVENTS));
                for e in &out[..n] {
                    if e.kind == InjectKind::Key {
                        assert!(hid_scan_codes::for_usage(e.usage as u32).is_mapped());
                        if e.is_key_up() {
                            assert!(held.remove(&e.usage), "up without down {:#x}", e.usage);
                        } else {
                            assert!(held.insert(e.usage), "down twice {:#x}", e.usage);
                        }
                    }
                }
                if n == 0 {
                    break;
                }
            }
        }
        let mut out = [InjectEvent::default(); input_diff::MAX_RELEASE_EVENTS];
        let n = input_diff::release_all(&mut d.injected, &mut d.mouse, &mut out);
        for e in &out[..n] {
            if e.kind == InjectKind::Key {
                assert!(e.is_key_up());
                assert!(held.remove(&e.usage));
            }
        }
        assert!(held.is_empty());
        assert!(!input_diff::any_held(&d.injected, d.mouse));
    }
}

#[test]
fn release_all_into_a_small_buffer_keeps_the_rest_held() {
    let mut d = Differ::new();
    let mut s = keys(&[0x04, 0x05, 0x06, 0xE0]);
    s.mouse_buttons = 0b11;
    d.diff(&s);
    let mut total = 0;
    let mut out = [InjectEvent::default(); 2];
    for _ in 0..10 {
        let n = input_diff::release_all(&mut d.injected, &mut d.mouse, &mut out);
        total += n;
        if n == 0 {
            break;
        }
    }
    assert_eq!(total, 6);
    assert!(!input_diff::any_held(&d.injected, d.mouse));
}

// ---- HidScanCodes ----------------------------------------------------------------------------

#[test]
fn keyboard_usages_all_map() {
    let v = common::vectors("wire-v1.json");
    let usages: Vec<u32> = v["keyboardUsages"].as_array().unwrap().iter().map(|e| e.as_u64().unwrap() as u32).collect();
    assert_eq!(usages.len(), 123);
    // Pause and the media keys go as virtual keys, everything else as scan codes.
    let vk_path = [0x48, 0x7F, 0x80, 0x81, 0xE8, 0xEA, 0xEB];
    for u in usages {
        let sc = hid_scan_codes::for_usage(u);
        assert!(sc.is_mapped(), "usage {u:#x} has no scan code");
        if vk_path.contains(&u) {
            assert_eq!(sc.scan, 0);
        } else {
            assert_eq!(sc.vk, 0);
        }
    }
}

#[test]
fn usage_of_reverses_the_table() {
    for u in 0..256u32 {
        let sc = hid_scan_codes::for_usage(u);
        if !sc.is_mapped() {
            continue;
        }
        let back = hid_scan_codes::usage_of(sc.scan, sc.extended, sc.vk);
        // 0x31 and 0x32 share scan 0x2B: the lower usage wins.
        assert_eq!(back, if u == 0x32 { 0x31 } else { u }, "usage {u:#x}");
    }
    assert_eq!(hid_scan_codes::usage_of(0x45, false, 0), 0x53); // NumLock without E0
    assert_eq!(hid_scan_codes::usage_of(0, false, 0), 0);
    assert_eq!(hid_scan_codes::usage_of(0x7E, false, 0), 0);
    assert_eq!(hid_scan_codes::usage_of(0x1E, false, 0x41), 0x04); // unknown vk falls back to the scan
    assert!(hid_scan_codes::is_modifier(0xE0) && hid_scan_codes::is_modifier(0xE7));
    assert!(!hid_scan_codes::is_modifier(0xDF) && !hid_scan_codes::is_modifier(0xE8));
    assert!(hid_scan_codes::repeats(0x80) && hid_scan_codes::repeats(0x81) && !hid_scan_codes::repeats(0x7F));
}

// ---- KeyRepeat -------------------------------------------------------------------------------

fn held(usages: &[u32]) -> [u8; 32] {
    keys(usages).keys
}

#[test]
fn held_volume_repeats_after_the_delay() {
    let mut r = KeyRepeat::new();
    let mut out = [InjectEvent::default(); 4];
    let h = held(&[0x80]);
    assert_eq!(r.due(&h, 0, &mut out), 0);
    assert_eq!(r.due(&h, KeyRepeat::DELAY_US - 1, &mut out), 0);
    assert_eq!(r.due(&h, KeyRepeat::DELAY_US, &mut out), 1);
    assert_eq!(out[0].usage, 0x80);
    assert!(!out[0].is_key_up());
    assert_eq!(out[0].vk, VK_VOLUME_UP);
    assert_eq!(r.due(&h, KeyRepeat::DELAY_US + KeyRepeat::INTERVAL_US - 1, &mut out), 0);
    assert_eq!(r.due(&h, KeyRepeat::DELAY_US + KeyRepeat::INTERVAL_US, &mut out), 1);
}

#[test]
fn release_restarts_the_delay_and_other_keys_never_repeat() {
    let mut r = KeyRepeat::new();
    let mut out = [InjectEvent::default(); 4];
    r.due(&held(&[0x81]), 0, &mut out);
    assert_eq!(r.due(&held(&[]), KeyRepeat::DELAY_US, &mut out), 0);
    assert_eq!(r.due(&held(&[0x81]), KeyRepeat::DELAY_US + 1, &mut out), 0);
    assert_eq!(r.due(&held(&[0x81]), 2 * KeyRepeat::DELAY_US, &mut out), 0);
    assert_eq!(r.due(&held(&[0x81]), 2 * KeyRepeat::DELAY_US + 1, &mut out), 1);
    // Mute and letters stay single presses.
    let mut other = KeyRepeat::new();
    other.due(&held(&[0x7F, 0x04]), 0, &mut out);
    assert_eq!(other.due(&held(&[0x7F, 0x04]), 10 * KeyRepeat::DELAY_US, &mut out), 0);
}

// ---- PadMapper -------------------------------------------------------------------------------

#[test]
fn x360_negates_y_and_clamps_min_value() {
    let s = StateFrame { lx: 1000, ly: 2000, rx: i16::MIN, ry: i16::MIN, ..Default::default() };
    let f = pad_mapper::to_x360(&s);
    assert_eq!((f.lx, f.ly, f.rx, f.ry), (1000, -2000, -32767, 32767));
}

#[test]
fn triggers() {
    for (v, expected) in [(0u16, 0u8), (512, 128), (1023, 255), (5000, 255), (u16::MAX, 255)] {
        let s = StateFrame { lt: v, rt: v, ..Default::default() };
        assert_eq!(pad_mapper::to_x360(&s).lt, expected);
        assert_eq!(pad_mapper::to_ds4(&s).rt, expected);
        let digital = pad_mapper::to_ds4(&s).buttons & ds4_buttons::TRIGGER_RIGHT != 0;
        assert_eq!(digital, expected >= 1);
    }
}

#[test]
fn hats_both_modes() {
    use xusb_buttons::*;
    let cases = [
        (0u8, DPAD_UP, 0u8),
        (1, DPAD_UP | DPAD_RIGHT, 1),
        (2, DPAD_RIGHT, 2),
        (3, DPAD_DOWN | DPAD_RIGHT, 3),
        (4, DPAD_DOWN, 4),
        (5, DPAD_DOWN | DPAD_LEFT, 5),
        (6, DPAD_LEFT, 6),
        (7, DPAD_UP | DPAD_LEFT, 7),
        (8, 0, 8),
        (200, 0, 8),
        (255, 0, 8),
    ];
    for (hat, xusb, ds4) in cases {
        let s = StateFrame { hat, ..Default::default() };
        assert_eq!(pad_mapper::to_x360(&s).buttons, xusb);
        assert_eq!(pad_mapper::to_ds4(&s).hat, ds4);
    }
}

#[test]
fn ds4_hat_table_order() {
    assert_eq!(
        pad_mapper::DS4_HAT_NAMES,
        ["North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest", "None"]
    );
}

#[test]
fn ds4_axis_bytes() {
    for (v, expected) in [(-32767i16, 0u8), (i16::MIN, 0), (0, 128), (32767, 255)] {
        let s = StateFrame { lx: v, ly: v, ..Default::default() };
        let f = pad_mapper::to_ds4(&s);
        assert_eq!(f.lx, expected);
        assert_eq!(f.ly, expected); // no Y flip
    }
}

#[test]
fn x360_buttons() {
    let mut s = StateFrame { buttons: 0x07FF, ..Default::default() };
    assert_eq!(pad_mapper::to_x360(&s).buttons, 0xF7F0);
    s.buttons = 0x8003; // A, B, SHARE: SHARE dropped
    assert_eq!(pad_mapper::to_x360(&s).buttons, xusb_buttons::A | xusb_buttons::B);
}

#[test]
fn ds4_buttons_and_special() {
    let s = StateFrame { buttons: (1 << 0) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 10) | (1 << 15), ..Default::default() };
    let f = pad_mapper::to_ds4(&s);
    assert_eq!(f.buttons, ds4_buttons::CROSS | ds4_buttons::TRIANGLE | ds4_buttons::SHARE | ds4_buttons::OPTIONS);
    assert_eq!(f.special, ds4_special::PS | ds4_special::TOUCHPAD);
}

#[test]
fn neutral_frames() {
    assert_eq!(pad_mapper::to_x360(&StateFrame::default()), X360Frame::NEUTRAL);
    assert_eq!(pad_mapper::to_ds4(&StateFrame::default()), Ds4Frame::NEUTRAL);
}

// ---- MouseDeltaTracker -----------------------------------------------------------------------

#[test]
fn matches_mouse_sequence_vector() {
    let v = common::vectors("wire-v1.json");
    let seq = &v["mouseSequence"];
    let mut t = MouseDeltaTracker::new();
    let mut highest = 0i64;
    let mut actual: Vec<(i64, Option<[i64; 3]>)> = Vec::new();
    for e in seq["input"].as_array().unwrap() {
        if e["dropped"].as_bool() == Some(true) {
            continue;
        }
        let s = common::num(e, "seq");
        if s <= highest {
            actual.push((s, None));
            continue;
        }
        highest = s;
        let total: Vec<i32> = e["total"].as_array().unwrap().iter().map(|x| x.as_i64().unwrap() as i32).collect();
        let d = t.apply(total[0], total[1], total[2], 0, false, common::num(e, "tMs") * 1000);
        actual.push((s, Some([d.dx as i64, d.dy as i64, d.dw as i64])));
    }
    let expected: Vec<(i64, Option<[i64; 3]>)> = seq["expected"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| {
            let a = e["applied"].as_array().map(|a| [a[0].as_i64().unwrap(), a[1].as_i64().unwrap(), a[2].as_i64().unwrap()]);
            (common::num(e, "seq"), a)
        })
        .collect();
    assert_eq!(actual, expected);
}

#[test]
fn reset_rebases() {
    let mut t = MouseDeltaTracker::new();
    t.apply(100, 0, 0, 0, false, 0);
    assert_eq!(t.apply(110, 0, 0, 0, false, 1000).dx, 10);
    t.reset();
    assert_eq!(t.apply(500, 0, 0, 0, false, 2000).dx, 0);
}

#[test]
fn wheel_clamps_to_64() {
    let mut t = MouseDeltaTracker::new();
    t.apply(0, 0, 0, 0, false, 0);
    let d = t.apply(0, -100000, 1000, 0, false, 1000);
    assert_eq!(d.dy, -wire::MAX_MOUSE_DELTA);
    assert_eq!(d.dw, wire::MAX_WHEEL_DELTA);
}

#[test]
fn hires_units_and_mode_switch_rebases() {
    let mut t = MouseDeltaTracker::new();
    t.apply(0, 0, 0, 0, true, 0);
    let d = t.apply(0, 0, 30, -7, true, 1000);
    assert_eq!((d.dw, d.dh), (30, -7));
    assert_eq!(t.apply(0, 0, 100000, 0, true, 2000).dw, wire::MAX_WHEEL_DELTA_HIRES);
    // Back to plain STATE: totals are in other units, so no delta.
    assert_eq!(t.apply(0, 0, 5, 0, false, 3000).dw, 0);
    assert_eq!(t.apply(0, 0, 6, 0, false, 4000).dw, 1);
}

#[test]
fn tracker_takes_any_totals_and_times() {
    let mut t = MouseDeltaTracker::new();
    let edge = [i32::MIN, -1, 0, 1, i32::MAX];
    let times = [i64::MIN, -1, 0, 1, i64::MAX];
    for &a in &edge {
        for &b in &edge {
            for &now in &times {
                let d = t.apply(a, b, a, b, a & 1 == 0, now);
                assert!(d.dx.abs() <= wire::MAX_MOUSE_DELTA && d.dy.abs() <= wire::MAX_MOUSE_DELTA);
                assert!(d.dw.abs() <= wire::MAX_WHEEL_DELTA_HIRES && d.dh.abs() <= wire::MAX_WHEEL_DELTA_HIRES);
            }
        }
    }
}

// ---- MouseCatchUp ----------------------------------------------------------------------------

/// Learns a 1 ms spacing from ordinary packets, as a 1000 Hz phone sends them.
fn warmed(now: &mut i64) -> MouseCatchUp {
    let mut c = MouseCatchUp::new();
    for _ in 0..40 {
        c.add(3, 0, 1000, false);
        *now += 1000;
        c.take(*now);
    }
    c
}

#[test]
fn ordinary_packets_go_out_at_once() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    c.add(7, -2, 1000, false);
    assert_eq!(c.take(now + 1000), (7, -2));
    assert!(!c.catching_up());
}

#[test]
fn jitter_burst_goes_out_at_once() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    // Three packets bunched by ordinary jitter: 3 ms of phone time, under the backlog limit.
    c.add(5, 0, 1000, false);
    c.add(5, 0, 1000, false);
    c.add(5, 0, 1000, false);
    assert_eq!(c.take(now + 3000), (15, 0));
    assert!(!c.catching_up());
}

#[test]
fn motion_after_an_idle_pause_is_not_delayed() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    // Still for 18 ms (keepalives carry no motion), then a move: nothing was lost, all of it is fresh.
    c.add(0, 0, 18000, false);
    now += 18000;
    c.take(now);
    c.add(40, 10, 18000, false);
    assert_eq!(c.take(now + 18000), (40, 10));
    assert!(!c.catching_up());
}

#[test]
fn lost_stretch_catches_up_and_nothing_is_lost() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    // 29 packets lost: the next one carries 30 ms of motion, 300 counts.
    c.add(300, -60, 30000, true);
    now += 30000;
    let first = c.take(now);
    // Only the newest 1 ms goes out now; the rest catches up.
    assert_eq!(first, (10, -2));
    assert!(c.catching_up());
    assert_eq!(c.catch_up_end_us(), now + 14500); // half the 29 ms backlog
    assert!(c.backlog_taken());
    assert_eq!((c.backlog_span_us(), c.backlog_px(), c.backlog_catch_up_us()), (29000, 290 + 58, 14500));
    let (mut sx, mut sy, mut steps, mut biggest) = (first.0, first.1, 0, 0);
    while c.catching_up() {
        now += 1000;
        let d = c.take(now);
        sx += d.0;
        sy += d.1;
        biggest = biggest.max(d.0.abs());
        steps += 1;
    }
    assert_eq!((sx, sy), (300, -60));
    assert!(steps >= 14, "{steps} steps");
    assert!(biggest <= 25, "biggest step {biggest}");
}

#[test]
fn fresh_motion_still_goes_out_at_once_during_a_catch_up() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    c.add(300, 0, 30000, true);
    now += 30000;
    c.take(now);
    let before = c.catch_up_end_us();
    now += 1000;
    // The same catch-up step plus a new packet's 50 counts.
    c.add(50, 0, 1000, false);
    let d = c.take(now);
    assert!(d.0 >= 50 + 15, "{}", d.0);
    assert_eq!(c.catch_up_end_us(), before);
}

#[test]
fn late_burst_catches_up_all_but_the_newest() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    // A 20 ms stall, then 20 late packets in one go, nothing lost.
    for _ in 0..20 {
        c.add(4, 1, 1000, false);
    }
    now += 20000;
    assert_eq!(c.take(now), (4, 1));
    assert!(c.catching_up());
    let (mut sx, mut sy) = (4, 1);
    while c.catching_up() {
        now += 1000;
        let d = c.take(now);
        sx += d.0;
        sy += d.1;
    }
    assert_eq!((sx, sy), (80, 20));
}

#[test]
fn catch_up_caps_at_catch_up_time() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    c.add(2000, 0, 200000, true);
    now += 200000;
    c.take(now);
    assert_eq!(c.catch_up_end_us(), now + MouseCatchUp::DEFAULT_MAX_US);
}

#[test]
fn off_sends_everything_at_once() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    c.max_us = 0;
    c.add(300, -60, 30000, true);
    assert_eq!(c.take(now + 30000), (300, -60));
    assert!(!c.catching_up());
}

#[test]
fn catch_up_time_sets_the_longest_spread() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    c.max_us = 8000;
    c.add(300, 0, 30000, true);
    now += 30000;
    c.take(now);
    assert_eq!(c.catch_up_end_us(), now + 8000);
}

#[test]
fn clear_drops_the_backlog() {
    let mut now = 0;
    let mut c = warmed(&mut now);
    c.add(300, 0, 30000, true);
    now += 30000;
    c.take(now);
    c.clear();
    assert!(!c.catching_up());
    assert_eq!(c.take(now + 1000), (0, 0));
}

#[test]
fn learns_the_spacing() {
    let mut c = MouseCatchUp::new();
    for i in 0..100i64 {
        c.add(1, 0, 4000, false);
        c.take(i * 4000);
    }
    assert!((3800..=4000).contains(&c.fresh_us()), "{}", c.fresh_us());
}

/// Random deltas, spans, losses and injection times, including a clock that jumps: every count
/// that goes in comes out, and nothing panics.
#[test]
fn every_count_comes_out_whatever_the_timing() {
    let mut seed = 0x1234_5678_9ABC_DEF1u64;
    let mut next = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for _ in 0..300 {
        let mut c = MouseCatchUp::new();
        c.max_us = [0, 4000, 8000, 16000, 32000][(next() % 5) as usize];
        let (mut in_x, mut in_y, mut out_x, mut out_y) = (0i64, 0i64, 0i64, 0i64);
        let mut now = (next() % 1_000_000) as i64;
        for _ in 0..400 {
            for _ in 0..(next() % 4) {
                let dx = (next() % 801) as i32 - 400;
                let dy = (next() % 801) as i32 - 400;
                let span = match next() % 6 {
                    0 => 0,
                    1 => -5,
                    2 => (next() % 300_000) as i64,
                    _ => (next() % 3000) as i64,
                };
                c.add(dx, dy, span, next() % 5 == 0);
                in_x += dx as i64;
                in_y += dy as i64;
            }
            now += match next() % 20 {
                0 => 0,
                1 => 200_000,
                _ => (next() % 3000) as i64,
            };
            let d = c.take(now);
            out_x += d.0 as i64;
            out_y += d.1 as i64;
        }
        for _ in 0..200 {
            if !c.catching_up() {
                break;
            }
            now += 1000;
            let d = c.take(now);
            out_x += d.0 as i64;
            out_y += d.1 as i64;
        }
        assert!(!c.catching_up());
        assert_eq!((out_x, out_y), (in_x, in_y));
    }
}

#[test]
fn catch_up_takes_extreme_values() {
    let mut c = MouseCatchUp::new();
    for &(dx, dy, span, lost) in &[
        (i32::MAX, i32::MIN, i64::MAX, true),
        (i32::MIN, i32::MAX, i64::MAX, false),
        (i32::MAX, i32::MAX, i64::MIN, true),
        (i32::MIN, i32::MIN, 1, true),
    ] {
        c.add(dx, dy, span, lost);
        c.add(dx, dy, span, lost);
        c.take(i64::MAX);
        c.add(dx, dy, span, lost);
        c.take(i64::MIN);
        c.take(0);
    }
}
