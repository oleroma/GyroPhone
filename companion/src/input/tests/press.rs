//! Measure buttons and Press a key. Ports of the C# PressLatencyTests and PressCaptureTests (the engine-level
//! ones are in tests/engine.rs).

mod common;

use common::fakes::{FakeKeyListener, FakePcPad};
use common::rng;
use gp_input::codec::{self, pc_press, PcPress, PressTime};
use gp_input::press::{PressCapture, PressLatency};
use gp_input::wire;

// Host µs = phone µs + OFFSET.
const OFFSET: i64 = 5_000_000_000;

fn pt(state: u32, event: u32) -> PressTime {
    PressTime { state_time_us: state, event_time_us: event }
}

#[test]
fn press_time_after_injection_measures_input_to_game() {
    let mut p = PressLatency::new();
    // Input at phone 1_000_000, STATE sent 4 ms later, injected 3 ms after that.
    p.on_injected(1_004_000, OFFSET + 1_007_000, OFFSET, true);
    p.on_press_time(pt(1_004_000, 1_000_000), OFFSET + 1_007_100, OFFSET, true);
    assert_eq!(p.median(), Some((7_000, 4_000)));
    assert_eq!(p.count(), 1);
    assert_eq!((p.last_total_us(), p.last_phone_us()), (7_000, 4_000));
}

#[test]
fn press_time_before_injection_waits_for_it() {
    let mut p = PressLatency::new();
    p.on_press_time(pt(2_002_000, 2_000_000), OFFSET + 2_004_000, OFFSET, true);
    assert_eq!(p.median(), None);
    p.on_injected(2_002_000, OFFSET + 2_005_000, OFFSET, true);
    assert_eq!(p.median(), Some((5_000, 2_000)));
}

#[test]
fn a_newer_state_injected_in_the_same_burst_carries_it() {
    let mut p = PressLatency::new();
    p.on_press_time(pt(3_001_000, 3_000_000), OFFSET + 3_002_000, OFFSET, true);
    // Its STATE merged into the next one (1 ms newer), which went out.
    p.on_injected(3_002_000, OFFSET + 3_003_000, OFFSET, true);
    assert_eq!(p.median(), Some((3_000, 1_000)));
}

#[test]
fn first_carrying_injection_counts() {
    let mut p = PressLatency::new();
    p.on_injected(4_001_000, OFFSET + 4_002_000, OFFSET, true);
    p.on_injected(4_003_000, OFFSET + 4_004_000, OFFSET, true);
    p.on_press_time(pt(4_001_000, 4_000_000), OFFSET + 4_004_500, OFFSET, true);
    assert_eq!(p.median().unwrap().0, 2_000);
}

#[test]
fn phone_clock_wraps_around() {
    let mut p = PressLatency::new();
    let ev = u32::MAX - 500;
    let st = ev.wrapping_add(1_500);
    let host_ev = OFFSET + ev as i64; // the event's host time
    p.on_injected(st, host_ev + 2_500, OFFSET, true);
    p.on_press_time(pt(st, ev), host_ev + 2_600, OFFSET, true);
    assert_eq!(p.median(), Some((2_500, 1_500)));
}

#[test]
fn no_clock_offset_measures_nothing() {
    let mut p = PressLatency::new();
    p.on_injected(1_004_000, OFFSET + 1_007_000, OFFSET, false);
    p.on_press_time(pt(1_004_000, 1_000_000), OFFSET + 1_007_100, OFFSET, false);
    assert_eq!(p.median(), None);
}

#[test]
fn median_of_many_presses() {
    let mut p = PressLatency::new();
    let lat = [9_000i64, 5_000, 7_000, 30_000, 6_000];
    for (i, l) in lat.iter().enumerate() {
        let ev = 10_000_000 + i as u32 * 100_000;
        p.on_injected(ev + 1_000, OFFSET + ev as i64 + l, OFFSET, true);
        p.on_press_time(pt(ev + 1_000, ev), OFFSET + ev as i64 + l + 100, OFFSET, true);
    }
    assert_eq!(p.median().unwrap().0, 7_000);
    assert_eq!(p.count(), 5);
    p.reset();
    assert_eq!((p.count(), p.median(), p.last_total_us()), (0, None, -1));
}

#[test]
fn any_press_times_and_injections_are_taken_without_a_panic() {
    let mut next = rng(77);
    let mut p = PressLatency::new();
    for _ in 0..200_000 {
        let a = next();
        let (x, y, t) = (next() as u32, next() as u32, next() as i64);
        let off = if a & 8 == 0 { next() as i64 } else { OFFSET };
        match a & 3 {
            0 => p.on_injected(x, t, off, a & 4 == 0),
            1 => p.on_press_time(pt(x, y), t, off, a & 4 == 0),
            // Near each other, so matches and the waiting list are exercised too.
            2 => p.on_press_time(pt(x & 0xFFFFF, y & 0xFFFFF), t & 0xFFFFF, OFFSET, true),
            _ => p.on_injected(x & 0xFFFFF, t & 0xFFFFF, OFFSET, true),
        }
        assert!(p.count() <= PressLatency::WINDOW);
        if let Some((total, phone)) = p.median() {
            assert!((0..=1_000_000).contains(&total) && phone >= 0);
        }
    }
}

fn decode(b: &[u8]) -> Vec<PcPress> {
    let mut into = [PcPress::default(); wire::CAPTURED_MAX];
    let n = codec::decode_captured(b, &mut into);
    into[..n].to_vec()
}

fn key(code: u16) -> PcPress {
    PcPress { kind: pc_press::KEY, code, arg: 0 }
}

#[test]
fn sends_what_is_held_on_change_and_keeps_a_tap_briefly() {
    let mut c = PressCapture::new();
    let keys = FakeKeyListener::default();
    let pad = FakePcPad::default();
    let mut b = [0u8; wire::CAPTURED_PLAIN];
    assert_eq!(c.tick(&keys, &pad, 0, &mut b), 0); // not listening
    assert!(c.on_capture(wire::capture_flags::ON, 0));

    assert_eq!(c.tick(&keys, &pad, 0, &mut b), 64);
    assert!(decode(&b).is_empty());
    assert_eq!(c.tick(&keys, &pad, 50_000, &mut b), 0); // nothing changed

    keys.hold(key(0xE0));
    keys.once(key(0x06));
    assert_eq!(c.tick(&keys, &pad, 100_000, &mut b), 64);
    assert_eq!(decode(&b), [key(0xE0), key(0x06)]);
    // The tap stays for HOLD_OVER_US, then goes.
    assert_eq!(c.tick(&keys, &pad, 200_000, &mut b), 0);
    assert_eq!(c.tick(&keys, &pad, 300_000, &mut b), 64);
    assert_eq!(decode(&b), [key(0xE0)]);
    // Resent while unchanged.
    assert_eq!(c.tick(&keys, &pad, 500_000, &mut b), 64);
}

#[test]
fn reads_controller_buttons_taps_and_axes() {
    let mut c = PressCapture::new();
    let keys = FakeKeyListener::default();
    let pad = FakePcPad::default();
    let mut b = [0u8; wire::CAPTURED_PLAIN];
    c.on_capture(wire::capture_flags::ON, 0);
    c.tick(&keys, &pad, 0, &mut b);
    pad.set(|p| {
        p.held = 1 << 0; // south: A
        p.presses[14] += 1; // D-pad up pressed and let go between two reads
        p.axes[3] = -0.9; // right stick up
        p.axes[5] = 0.7; // right trigger
        p.axes[0] = 0.3; // left stick a little: not pressed
    });
    c.tick(&keys, &pad, 50_000, &mut b);
    assert_eq!(
        decode(&b),
        [
            PcPress { kind: pc_press::PAD_BUTTON, code: 0x1000, arg: 0 },
            PcPress { kind: pc_press::PAD_BUTTON, code: 0x0001, arg: 0 },
            PcPress { kind: pc_press::PAD_AXIS, code: 3, arg: -1 },
            PcPress { kind: pc_press::PAD_AXIS, code: 5, arg: 1 },
        ]
    );
    // The controller unplugged: its presses go after the hold-over, and plugging back is not a tap.
    pad.set(|p| p.connected = false);
    c.tick(&keys, &pad, 300_000, &mut b);
    assert!(decode(&b).is_empty());
    pad.set(|p| {
        p.connected = true;
        p.held = 0;
        p.axes = [0.0; 6];
        p.presses[2] += 5;
    });
    assert_eq!(c.tick(&keys, &pad, 350_000, &mut b), 0);
}

#[test]
fn stops_when_told_or_after_the_timeout() {
    let mut c = PressCapture::new();
    c.on_capture(wire::capture_flags::ON, 0);
    assert!(!c.expired(2_000_000));
    c.on_capture(wire::capture_flags::ON, 2_000_000);
    assert!(!c.expired(4_000_000));
    assert!(c.expired(5_000_000));
    assert!(c.on_capture(0, 5_000_000));
    assert!(!c.on());
    assert!(!c.expired(9_000_000));
}

#[test]
fn more_presses_than_fit_send_the_first_fifteen_and_the_list_never_grows() {
    let mut c = PressCapture::new();
    let keys = FakeKeyListener::default();
    let pad = FakePcPad::default();
    let mut b = [0u8; wire::CAPTURED_PLAIN];
    c.on_capture(wire::capture_flags::ON, 0);
    let mut now = 0;
    for round in 0..50u16 {
        // 64 different keys every tick, never the same twice, and every controller button and axis.
        keys.0.lock().unwrap().once = (0..64).map(|i| key(round * 64 + i)).collect();
        pad.set(|p| {
            p.held = u32::MAX;
            p.axes = [1.0; 6];
        });
        now += 1_000;
        let n = c.tick(&keys, &pad, now, &mut b);
        // The first fifteen stay the same while they are held over, so only the first tick sends.
        assert_eq!(n, if round == 0 { 64 } else { 0 });
        assert_eq!(decode(&b).len(), 15);
    }
}
