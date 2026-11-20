//! Pad motion, Press a key and Measure buttons through the engine: ports of the engine tests in the C#
//! Ds4MotionTests, PressCaptureTests and PressLatencyTests.

mod common;

use std::sync::Arc;

use common::fakes::{FakeKeyListener, FakePcPad};
use common::harness::Harness;
use common::phone::FakePhone;
use gp_input::codec::{self, pc_press, LinkInfo, PcPress, PressTime};
use gp_input::motion::{self, MotionFrame};
use gp_input::reports::Ds4MotionFeed;
use gp_input::state_frame::StateFrame;
use gp_input::wire::{self, capture_flags, output_mode, packet, test_mode_flags, welcome_flags};

fn state(h: &Harness, phone: &mut FakePhone, change: impl FnOnce(&mut StateFrame)) -> Option<Vec<u8>> {
    let d = phone.state(change);
    h.send(phone, &d)
}

/// The MOTION the phone's MotionFeedTest encodes: two samples, seq 5, time 1000 + 2000 µs, flags MAY_MOVE.
fn vector_frame() -> [u8; motion::SIZE] {
    let mut f = MotionFrame { flags: motion::FLAG_MAY_MOVE, count: 2, newest_seq: 5, newest_time_us: 2000, ..MotionFrame::default() };
    f.dt_us[0] = 0;
    f.gyro[..3].copy_from_slice(&[16, 32, 48]);
    f.accel[..3].copy_from_slice(&[-10, -20, 8192]);
    f.dt_us[1] = 1000;
    f.gyro[3..6].copy_from_slice(&[1, -2, 253]);
    f.accel[3..6].copy_from_slice(&[0, 0, -8256]);
    let mut b = [0u8; motion::SIZE];
    f.encode(&mut b);
    b
}

#[test]
fn engine_sends_each_sample_to_the_dualshock4() {
    let h = Harness::mode(output_mode::DUALSHOCK4);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    assert_ne!(0, phone.welcome_host_flags & welcome_flags::MOTION);
    state(&h, &mut phone, |f| f.lx = 32767);
    let n = h.pad.ds4().len();
    h.sealed(&mut phone, packet::MOTION, &vector_frame());
    assert_eq!(n + 2, h.pad.ds4().len());
    // Each with the pad's buttons and sticks as they are.
    assert_eq!(255, h.pad.ds4().last().unwrap().lx);
    let m = h.pad.ds4_motion();
    assert_eq!(16, m[m.len() - 2].gx);
    assert_eq!(253, m[m.len() - 1].gz);
    // A STATE after it keeps the motion.
    state(&h, &mut phone, |f| f.lx = 0);
    assert_eq!(253, h.pad.ds4_motion().last().unwrap().gz);
    // No MOTION for a while: the gyro rests.
    h.clock.advance_us(Ds4MotionFeed::STALE_US);
    state(&h, &mut phone, |f| f.mouse_total_x += 1);
    h.engine.on_tick(h.now());
    assert!(!h.pad.ds4_motion().last().unwrap().turning());
}

#[test]
fn a_state_in_the_same_burst_goes_out_before_the_motion() {
    let h = Harness::mode(output_mode::DUALSHOCK4);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    let calls = h.key_mouse.calls();
    // STATE then MOTION in one receive burst: no flush between them.
    let d = phone.state(|f| {
        f.lx = 32767;
        f.mouse_total_x += 5;
    });
    h.burst(&phone, &d);
    let d = phone.sealed(packet::MOTION, &vector_frame());
    h.burst(&phone, &d);
    // The mouse move went out before the motion readings, which carry the new stick.
    assert_eq!(calls + 1, h.key_mouse.calls());
    let ds4 = h.pad.ds4();
    assert_eq!(255, ds4[ds4.len() - 1].lx);
    assert_eq!(255, ds4[ds4.len() - 2].lx);
    assert_eq!(253, h.pad.ds4_motion().last().unwrap().gz);
    h.flush();
    assert_eq!(calls + 1, h.key_mouse.calls());
}

#[test]
fn a_pad_tap_split_by_a_motion_in_one_burst_stays_visible() {
    let h = Harness::mode(output_mode::DUALSHOCK4);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // Press, MOTION, release in one receive burst: the MOTION injects the press first.
    let d = phone.state(|f| f.buttons = 1);
    h.burst(&phone, &d);
    let d = phone.sealed(packet::MOTION, &vector_frame());
    h.burst(&phone, &d);
    assert_ne!(0, h.pad.ds4().last().unwrap().buttons);
    let d = phone.state(|f| f.buttons = 0);
    h.burst(&phone, &d);
    h.flush();
    // The button stays visible to a polling game for a moment, then lets go.
    assert_ne!(0, h.pad.ds4().last().unwrap().buttons);
    h.tick(wire::WATCHDOG_TICK_MS);
    assert_eq!(0, h.pad.ds4().last().unwrap().buttons);
}

#[test]
fn engine_ignores_motion_for_an_xbox_pad() {
    let h = Harness::mode(output_mode::XBOX360);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    h.sealed(&mut phone, packet::MOTION, &vector_frame());
    assert!(h.pad.ds4().is_empty());
}

fn capture(flags: u8) -> [u8; wire::CAPTURE_PLAIN] {
    let mut b = [0u8; wire::CAPTURE_PLAIN];
    codec::encode_byte4(flags, &mut b);
    b
}

fn listeners(h: &Harness) -> (Arc<FakeKeyListener>, Arc<FakePcPad>) {
    let keys = Arc::new(FakeKeyListener::default());
    let pad = Arc::new(FakePcPad::default());
    h.engine.set_key_mouse_listener(keys.clone());
    h.engine.set_pc_pad(pad.clone());
    (keys, pad)
}

#[test]
fn listens_only_while_the_phone_asks() {
    let h = Harness::new();
    let (keys, pad) = listeners(&h);
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    assert!(!keys.running());
    assert!(!pad.running());

    h.sealed(&mut phone, packet::CAPTURE, &capture(capture_flags::ON));
    assert!(keys.running());
    assert!(pad.running());

    let press = PcPress { kind: pc_press::MOUSE, code: 3, arg: 0 };
    keys.hold(press);
    h.sender.clear();
    h.tick(50);
    let sent = h.opened(&mut phone, packet::CAPTURED);
    assert_eq!(1, sent.len());
    let mut into = [PcPress::default(); wire::CAPTURED_MAX];
    assert_eq!(1, codec::decode_captured(&sent[0], &mut into));
    assert_eq!(press, into[0]);

    // No CAPTURE for 3 s: it stops by itself.
    for _ in 0..61 {
        h.tick(50);
    }
    assert!(!keys.running());
    assert!(!pad.running());

    // Asked again, then told it closed.
    h.sealed(&mut phone, packet::CAPTURE, &capture(capture_flags::ON));
    assert!(keys.running());
    h.sealed(&mut phone, packet::CAPTURE, &capture(0));
    assert!(!keys.running());
    assert!(!pad.running());
}

#[test]
fn switched_off_the_pc_is_never_read() {
    let h = Harness::new();
    let (keys, pad) = listeners(&h);
    let mut phone = h.new_phone();
    h.connect(&mut phone);

    // Turned off while the phone listens: reading stops at once.
    h.sealed(&mut phone, packet::CAPTURE, &capture(capture_flags::ON));
    assert!(keys.running());
    h.engine.set_press_key_listen(false);
    assert!(!keys.running());
    assert!(!pad.running());

    // Off: the phone's asking reads nothing.
    h.sealed(&mut phone, packet::CAPTURE, &capture(capture_flags::ON));
    assert!(!keys.running());
    assert!(!h.engine.snapshot().capturing);

    h.engine.set_press_key_listen(true);
    h.sealed(&mut phone, packet::CAPTURE, &capture(capture_flags::ON));
    assert!(keys.running());
    assert!(h.engine.snapshot().capturing);
}

#[test]
fn measure_buttons_asks_the_phone_and_times_its_presses() {
    let h = Harness::new();
    let mut phone = h.new_phone();
    h.connect(&mut phone);
    // Phone clock = host clock - 1 s.
    let offset: i64 = 1_000_000;
    let mut li = [0u8; wire::LINK_INFO_PLAIN];
    codec::encode_link_info(
        &LinkInfo { clock_offset_us: offset, rtt_min_us: 500, send_rate_hz: 1000, rssi_dbm: 0, band: 5, link_mbps: 0, freq_mhz: 0, flags: 0, path: 0 },
        &mut li,
    );
    h.sealed(&mut phone, packet::LINK_INFO, &li);

    h.sender.clear();
    h.engine.set_measure_buttons(true);
    let (t, tm) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::TEST_MODE, t);
    assert_eq!(test_mode_flags::PRESS_TIMES, tm[0]);

    // A press seen 3 ms before its STATE, which goes to the game at once.
    let state_us = (h.now() - offset - 1_000) as u32;
    state(&h, &mut phone, |f| {
        f.buttons = 1;
        f.phone_time_us = state_us;
    });
    let mut pt = [0u8; wire::PRESS_TIME_PLAIN];
    codec::encode_press_time(PressTime { state_time_us: state_us, event_time_us: state_us.wrapping_sub(3_000) }, &mut pt);
    h.sealed(&mut phone, packet::PRESS_TIME, &pt);
    let b = h.engine.press_latency_now().unwrap();
    assert_eq!(4_000, b.total_us);
    assert_eq!(3_000, b.phone_us);
    assert_eq!(1, b.count);

    // Off: the phone is told, and nothing is shown.
    h.sender.clear();
    h.engine.set_measure_buttons(false);
    let (t, tm) = phone.try_open(&h.sender.last().unwrap()).unwrap();
    assert_eq!(packet::TEST_MODE, t);
    assert_eq!(0, tm[0]);
    assert!(h.engine.press_latency_now().is_none());
}
