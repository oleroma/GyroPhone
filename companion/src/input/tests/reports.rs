//! Ports of Ds4MotionTests, DualSenseReportTests, HidInputReportsTests and UsbFramingTests (the
//! parts that don't go through the engine), plus hammering of the deframer and the report builder.

mod common;

use gp_input::le;
use gp_input::mapping::*;
use gp_input::motion::{self, MotionFrame};
use gp_input::reports::*;
use gp_input::state_frame::StateFrame;
use gp_input::usb_framing::{self, UsbDeframer};
use gp_input::wire;

/// The MOTION the phone's MotionFeedTest encodes (MotionFeedTest.VECTOR in core): two samples,
/// seq 5, time 1000 + 2000 µs, then flags MAY_MOVE.
const VECTOR: &str = concat!(
    "01020500d0070000",
    "0000100020003000f6ffecff0020",
    "e8030100fefffd0000000000c0df",
    "0000000000000000000000000000",
    "0000000000000000000000000000",
    "0000000000000000000000000000",
    "0000000000000000000000000000",
    "0000000000000000000000000000",
    "0000000000000000000000000000"
);

fn vector_frame() -> MotionFrame {
    let mut f = MotionFrame { flags: motion::FLAG_MAY_MOVE, count: 2, newest_seq: 5, newest_time_us: 2000, ..Default::default() };
    f.dt_us[0] = 0;
    f.gyro[..3].copy_from_slice(&[16, 32, 48]);
    f.accel[..3].copy_from_slice(&[-10, -20, 8192]);
    f.dt_us[1] = 1000;
    f.gyro[3..6].copy_from_slice(&[1, -2, 253]);
    f.accel[3..6].copy_from_slice(&[0, 0, -8256]);
    f
}

fn rng(seed: u64) -> impl FnMut() -> u64 {
    let mut s = seed;
    move || {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        s
    }
}

// ---- MotionFrame, Ds4Report, Ds4MotionFeed ---------------------------------------------------

#[test]
fn motion_packet_size_and_vector() {
    assert_eq!(wire::MOTION_PLAIN, 120);
    assert_eq!(motion::SIZE, wire::MOTION_PLAIN);
    assert_eq!(wire::packet::size(wire::packet::MOTION), Some(wire::HEADER + 120 + wire::GCM_TAG));
    assert!(wire::packet::is_phone_to_host_sealed(wire::packet::MOTION));
    let mut b = [0u8; motion::SIZE];
    vector_frame().encode(&mut b);
    assert_eq!(common::hex(&b), VECTOR);
    let mut d = MotionFrame::default();
    assert!(d.decode(&common::unhex(VECTOR)));
    assert!(d.may_move());
    assert_eq!(d.count, 2);
    assert_eq!(d.newest_seq, 5);
    assert_eq!(d.dt_us[1], 1000);
    assert_eq!(d.gyro[5], 253);
    assert_eq!(d.accel[5], -8256);
    assert_eq!(d, vector_frame());
    assert!(!d.decode(&[0u8; motion::SIZE - 1]));
}

#[test]
fn report_carries_buttons_motion_and_no_touch() {
    let mut r = [0xEEu8; ds4_report::SIZE];
    let f = Ds4Frame {
        buttons: ds4_buttons::CROSS | ds4_buttons::THUMB_RIGHT,
        special: ds4_special::PS,
        hat: 2,
        lx: 1,
        ly: 2,
        rx: 3,
        ry: 4,
        lt: 5,
        rt: 6,
    };
    ds4_report::build(&mut r, &f, &Ds4Motion { clock: 0x12340, gx: 16, gy: -16, gz: 300, ax: -8192, ay: 8192, az: 7 });
    assert_eq!(r[..4], [1, 2, 3, 4]);
    assert_eq!(le::u16(&r, 4), ds4_buttons::CROSS | ds4_buttons::THUMB_RIGHT | 2);
    assert_eq!(r[6], ds4_special::PS);
    assert_eq!(r[7], 5);
    assert_eq!(r[8], 6);
    assert_eq!(le::u16(&r, 9), 0x1234);
    assert_eq!(le::i16(&r, 12), 16);
    assert_eq!(le::i16(&r, 14), -16);
    assert_eq!(le::i16(&r, 16), 300);
    assert_eq!(le::i16(&r, 18), -8192);
    assert_eq!(le::i16(&r, 20), 8192);
    assert_eq!(le::i16(&r, 22), 7);
    assert_eq!(r[32], 1);
    for o in [34, 38, 43, 47, 52, 56] {
        assert_eq!(r[o], 0x80);
    }
    // No hat: the D-pad nibble says none.
    ds4_report::build(&mut r, &Ds4Frame::NEUTRAL, &Ds4Motion::NEUTRAL);
    assert_eq!(le::u16(&r, 4), 8);
    assert_eq!(le::i16(&r, 20), 8192);
    // A hat past the table still says none.
    ds4_report::build(&mut r, &Ds4Frame { hat: 200, ..Ds4Frame::NEUTRAL }, &Ds4Motion::NEUTRAL);
    assert_eq!(le::u16(&r, 4), 8);
}

#[test]
fn feed_takes_each_sample_once_with_its_time() {
    let mut feed = Ds4MotionFeed::new();
    let mut o = [Ds4Motion::NEUTRAL; motion::MAX_SAMPLES];
    let mut f = vector_frame();
    assert_eq!(feed.take(&f, &mut o, 0), 2);
    assert_eq!(o[0].gx, 16);
    assert_eq!(o[1].gz, 253);
    // 1000 µs is 187.5 ticks of 16/3 µs.
    assert_eq!(o[1].ds4_timestamp().wrapping_sub(o[0].ds4_timestamp()), 187);
    // The DualSense's clock is exact: 1000 µs is 3000 ticks of 1/3 µs.
    assert_eq!(o[1].clock.wrapping_sub(o[0].clock), 3000);
    // The same packet again (a repeat): nothing new.
    assert_eq!(feed.take(&f, &mut o, 100), 0);
    // The next one repeats sample 5 and adds 6: only 6 is new.
    f.dt_us[0] = 1000;
    f.gyro[..3].copy_from_slice(&[1, -2, 253]);
    f.dt_us[1] = 1000;
    f.gyro[3..6].copy_from_slice(&[7, 8, 9]);
    f.newest_seq = 6;
    assert_eq!(feed.take(&f, &mut o, 1000), 1);
    assert_eq!(o[0].gx, 7);
    assert_eq!(feed.current().gx, 7);
    // An older packet arriving late is dropped.
    f.newest_seq = 4;
    assert_eq!(feed.take(&f, &mut o, 1100), 0);
}

#[test]
fn feed_counts_the_time_of_samples_lost_beyond_the_repeats() {
    let mut feed = Ds4MotionFeed::new();
    let mut o = [Ds4Motion::NEUTRAL; motion::MAX_SAMPLES];
    let mut f = vector_frame(); // seq 5 at 2000 µs
    feed.take(&f, &mut o, 0);
    let last = o[1].clock;
    // 20 samples later, 1 ms apart: the packet holds only the newest 2 (seq 24, 25), 18 are lost.
    f.newest_seq = 25;
    f.newest_time_us = 22_000;
    f.dt_us[0] = 1000;
    f.dt_us[1] = 1000;
    assert_eq!(feed.take(&f, &mut o, 20_000), 2);
    // Seq 24 is 19 ms after seq 5, seq 25 1 ms after that: 3 ticks per µs.
    assert_eq!(o[0].clock.wrapping_sub(last), 19_000 * 3);
    assert_eq!(o[1].clock.wrapping_sub(last), 20_000 * 3);

    // Within the repeats nothing extra is added.
    f.newest_seq = 26;
    f.newest_time_us = 23_000;
    assert_eq!(feed.take(&f, &mut o, 21_000), 1);
    assert_eq!(o[0].clock.wrapping_sub(last), 21_000 * 3);
}

#[test]
fn feed_rests_when_stopped_or_silent() {
    let mut feed = Ds4MotionFeed::new();
    let mut o = [Ds4Motion::NEUTRAL; motion::MAX_SAMPLES];
    let mut f = vector_frame();
    feed.take(&f, &mut o, 0);
    assert!(feed.current().turning());
    assert!(!feed.is_stale(Ds4MotionFeed::STALE_US - 1));
    assert!(feed.is_stale(Ds4MotionFeed::STALE_US));
    assert_eq!(feed.rest(&mut o, Ds4MotionFeed::STALE_US), 1);
    assert!(!o[0].turning());
    assert_eq!(o[0].az, -8256); // the accelerometer stays
    assert!(!feed.is_stale(10 * Ds4MotionFeed::STALE_US));
    assert_eq!(feed.rest(&mut o, 10 * Ds4MotionFeed::STALE_US), 0);
    // Input not allowed on the phone: one resting reading if it was turning.
    f.newest_seq = 9;
    feed.take(&f, &mut o, 0);
    f.flags = 0;
    assert_eq!(feed.take(&f, &mut o, 10), 1);
    assert!(!o[0].turning());
    assert_eq!(feed.take(&f, &mut o, 20), 0);
}

#[test]
fn feed_reset_keeps_the_clock_and_counts_afresh() {
    let mut feed = Ds4MotionFeed::new();
    let mut o = [Ds4Motion::NEUTRAL; motion::MAX_SAMPLES];
    let f = vector_frame();
    feed.take(&f, &mut o, 0);
    let clock = feed.current().clock;
    feed.reset();
    assert_eq!(feed.current(), Ds4Motion { clock, ..Ds4Motion::NEUTRAL });
    assert!(!feed.is_stale(i64::MAX));
    // The same seq is new again after a reset.
    assert_eq!(feed.take(&f, &mut o, 0), 2);
}

/// Any 120 bytes as MOTION, at any host time: no panic, never more readings than samples.
#[test]
fn feed_takes_any_bytes() {
    let mut next = rng(0xA5A5_1234_5678_9ABC);
    let mut feed = Ds4MotionFeed::new();
    let mut o = [Ds4Motion::NEUTRAL; motion::MAX_SAMPLES];
    let mut f = MotionFrame::default();
    let times = [i64::MIN, -1, 0, 1, 60_000, i64::MAX];
    for round in 0..20_000 {
        let mut b = [0u8; motion::SIZE];
        for x in b.iter_mut() {
            *x = next() as u8;
        }
        if round % 3 == 0 {
            b[0] |= motion::FLAG_MAY_MOVE;
        }
        assert!(f.decode(&b));
        assert!(f.count <= motion::MAX_SAMPLES);
        let now = if round % 5 == 0 { times[(next() % 6) as usize] } else { next() as i64 };
        let n = feed.take(&f, &mut o, now);
        assert!(n <= f.count.max(1));
        feed.is_stale(next() as i64);
        if round % 7 == 0 {
            feed.rest(&mut o, next() as i64);
        }
        if round % 501 == 0 {
            feed.reset();
        }
    }
}

// ---- DualSenseReport -------------------------------------------------------------------------

#[test]
fn dualsense_build_puts_each_field_where_the_dualsense_has_it() {
    let mut r = [0xEEu8; dualsense_report::SIZE];
    let f = Ds4Frame {
        buttons: ds4_buttons::CROSS | ds4_buttons::SHOULDER_LEFT | ds4_buttons::THUMB_RIGHT,
        special: ds4_special::PS | ds4_special::TOUCHPAD,
        hat: 2,
        lx: 1,
        ly: 2,
        rx: 3,
        ry: 4,
        lt: 5,
        rt: 6,
    };
    dualsense_report::build(&mut r, &f, &Ds4Motion { clock: 0x12345678, gx: 16, gy: -16, gz: 300, ax: -8192, ay: 8192, az: 7 });
    assert_eq!(r[0], 0x01);
    // LX LY RX RY L2 R2
    assert_eq!(r[1..7], [1, 2, 3, 4, 5, 6]);
    // Counter: the driver's.
    assert_eq!(r[7], 0);
    // Hat 2 (east) in the low nibble, Cross above it; L1 and R3; PS and touchpad click.
    assert_eq!(r[8], 0x22);
    assert_eq!(r[9], 0x81);
    assert_eq!(r[10], 0x03);
    assert_eq!(le::i16(&r, 16), 16);
    assert_eq!(le::i16(&r, 18), -16);
    assert_eq!(le::i16(&r, 20), 300);
    assert_eq!(le::i16(&r, 22), -8192);
    assert_eq!(le::i16(&r, 24), 8192);
    assert_eq!(le::i16(&r, 26), 7);
    // The sensor timestamp, in 1/3 µs.
    assert_eq!(le::u32(&r, 28), 0x12345678);
    // No finger on either touch point; on cable, charging.
    assert_eq!(r[33], 0x80);
    assert_eq!(r[37], 0x80);
    assert_eq!(r[53], 0x1A);
}

#[test]
fn dualsense_neutral_is_centred_with_no_hat() {
    let mut r = [0u8; dualsense_report::SIZE];
    dualsense_report::build(&mut r, &Ds4Frame::NEUTRAL, &Ds4Motion::NEUTRAL);
    assert_eq!(r[1..7], [128, 128, 128, 128, 0, 0]);
    assert_eq!(r[8], 0x08);
    assert_eq!(r[9], 0);
    assert_eq!(r[10], 0);
    assert_eq!(le::i16(&r, 24), 8192);
}

// ---- HidInputReports -------------------------------------------------------------------------

struct Hid {
    r: HidInputReports,
    buf: Box<[u8; HID_MAX_BYTES]>,
    injected: [u8; 32],
    mouse: u8,
    unknown: u32,
}

fn split(buf: &[u8]) -> Vec<Vec<u8>> {
    let mut list = Vec::new();
    let mut at = 0;
    while at < buf.len() {
        let size = match buf[at] {
            MOUSE_ID => MOUSE_SIZE,
            KEYBOARD_ID => KEYBOARD_SIZE,
            MEDIA_ID => MEDIA_SIZE,
            id => panic!("bad id {id}"),
        };
        list.push(buf[at..at + size].to_vec());
        at += size;
    }
    list
}

impl Hid {
    fn new() -> Self {
        Hid { r: HidInputReports::new(), buf: Box::new([0; HID_MAX_BYTES]), injected: [0; 32], mouse: 0, unknown: 0 }
    }

    fn reports(&mut self, events: &[InjectEvent]) -> Vec<Vec<u8>> {
        let n = self.r.build(events, &mut self.buf);
        split(&self.buf[..n])
    }

    fn diff5(&mut self, target: &StateFrame, dx: i32, dy: i32, wheel: i32, hwheel: i32) -> Vec<Vec<u8>> {
        let mut events = [InjectEvent::default(); input_diff::MAX_EVENTS];
        let n = input_diff::diff(
            &mut self.injected,
            &target.keys,
            &mut self.mouse,
            target.mouse_buttons,
            dx,
            dy,
            wheel,
            hwheel,
            &mut events,
            &mut self.unknown,
        );
        self.reports(&events[..n])
    }

    fn diff(&mut self, target: &StateFrame) -> Vec<Vec<u8>> {
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

fn key_bit(report: &[u8], usage: usize) -> bool {
    report[1 + (usage >> 3)] & (1 << (usage & 7)) != 0
}

fn s16(r: &[u8], at: usize) -> i16 {
    le::i16(r, at)
}

fn key_event(usage: u8, up: bool) -> InjectEvent {
    InjectEvent::key(usage, hid_scan_codes::for_usage(usage as u32), up)
}

#[test]
fn move_is_one_mouse_report() {
    let mut h = Hid::new();
    let r = h.diff5(&StateFrame::default(), 5, -7, 0, 0);
    assert_eq!(r.len(), 1);
    assert_eq!(r[0][0], MOUSE_ID);
    assert_eq!(r[0][1], 0);
    assert_eq!(s16(&r[0], 2), 5);
    assert_eq!(s16(&r[0], 4), -7);
}

#[test]
fn click_with_move_and_wheels_shares_one_report() {
    let mut h = Hid::new();
    let s = StateFrame { mouse_buttons: 1, ..Default::default() };
    let r = h.diff5(&s, 3, 4, 30, -240);
    assert_eq!(r.len(), 1);
    assert_eq!(r[0][1], 1);
    assert_eq!((s16(&r[0], 2), s16(&r[0], 4), s16(&r[0], 6), s16(&r[0], 8)), (3, 4, 30, -240));
}

#[test]
fn nothing_changed_writes_nothing() {
    assert!(Hid::new().diff(&StateFrame::default()).is_empty());
}

#[test]
fn held_state_carries_over_and_releases() {
    let mut h = Hid::new();
    let mut s = keys(&[0x04]);
    s.mouse_buttons = 2;
    h.diff(&s);
    let mut t = keys(&[0x04, 0x05]);
    t.mouse_buttons = 2;
    let r = h.diff5(&t, 1, 0, 0, 0);
    assert_eq!(r.len(), 2);
    assert!(key_bit(&r[0], 0x04));
    assert!(key_bit(&r[0], 0x05));
    assert_eq!(r[1][1], 2); // right button still held in the move's report

    let mut events = [InjectEvent::default(); input_diff::MAX_RELEASE_EVENTS];
    let n = input_diff::release_all(&mut h.injected, &mut h.mouse, &mut events);
    let rel = h.reports(&events[..n]);
    assert_eq!(rel.len(), 2);
    assert!(rel[0][1..].iter().all(|&b| b == 0));
    assert_eq!(rel[1][1], 0);
    assert!(!h.r.any_held());
}

#[test]
fn modifier_goes_down_before_and_up_after_the_key() {
    let mut h = Hid::new();
    let r = h.diff(&keys(&[0xE1, 0x04])); // LShift, A
    assert_eq!(r.len(), 2);
    assert!(key_bit(&r[0], 0xE1));
    assert!(!key_bit(&r[0], 0x04));
    assert!(key_bit(&r[1], 0xE1));
    assert!(key_bit(&r[1], 0x04));

    let r = h.diff(&StateFrame::default());
    assert_eq!(r.len(), 2);
    assert!(!key_bit(&r[0], 0x04));
    assert!(key_bit(&r[0], 0xE1));
    assert!(!key_bit(&r[1], 0xE1));
}

#[test]
fn key_changing_twice_gets_two_reports() {
    let mut h = Hid::new();
    let r = h.reports(&[key_event(0x04, false), key_event(0x04, true)]);
    assert_eq!(r.len(), 2);
    assert!(key_bit(&r[0], 0x04));
    assert!(!key_bit(&r[1], 0x04));
}

#[test]
fn move_before_click_stays_before_it() {
    let mut h = Hid::new();
    let r = h.reports(&[InjectEvent::mouse_move(2, 0), InjectEvent::mouse_button(0, false)]);
    assert_eq!(r.len(), 2);
    assert_eq!((r[0][1], s16(&r[0], 2)), (0, 2));
    assert_eq!((r[1][1], s16(&r[1], 2)), (1, 0));
}

#[test]
fn media_keys_go_to_their_own_report() {
    let mut h = Hid::new();
    let r = h.diff(&keys(&[0x80, 0xE8])); // volume up, play/pause
    assert_eq!(r.len(), 1);
    assert_eq!(r[0][0], MEDIA_ID);
    assert_eq!(r[0][1], 0b1_0001);
}

#[test]
fn back_and_forward_buttons() {
    let mut h = Hid::new();
    let r = h.diff(&StateFrame { mouse_buttons: 0b1_1000, ..Default::default() });
    assert_eq!(r.len(), 1);
    assert_eq!(r[0][1], 0b1_1000);
}

#[test]
fn reset_forgets_held_keys() {
    let mut h = Hid::new();
    h.diff(&keys(&[0x04]));
    assert!(h.r.any_held());
    h.r.reset();
    assert!(!h.r.any_held());
}

#[test]
fn reports_keep_the_events_order_across_devices() {
    let mut h = Hid::new();
    let r = h.reports(&[InjectEvent::mouse_button(0, false), key_event(0x04, false), InjectEvent::mouse_button(0, true)]);
    assert_eq!(r.len(), 3);
    assert_eq!(r[0][0], MOUSE_ID);
    assert_eq!(r[1][0], KEYBOARD_ID);
    assert_eq!(r[2][0], MOUSE_ID);
    assert_eq!(r[2][1], 0);
}

#[test]
fn big_moves_split_instead_of_wrapping() {
    let mut h = Hid::new();
    let r = h.reports(&[InjectEvent::mouse_move(30000, 0), InjectEvent::mouse_move(30000, 0), InjectEvent::wheel(20000), InjectEvent::wheel(20000)]);
    let sum: i32 = r.iter().map(|x| s16(x, 2) as i32).sum();
    let wheel: i32 = r.iter().map(|x| s16(x, 6) as i32).sum();
    assert_eq!((sum, wheel), (60000, 40000));
}

/// Random event lists (far beyond what the engine sends, including more than the buffer holds):
/// no panic, every report well formed, and what the builder remembers is exactly what the reports
/// it wrote say.
#[test]
fn any_events_keep_reports_and_memory_in_step() {
    let mut next = rng(0xDEAD_BEEF_0BAD_F00D);
    for round in 0..300 {
        let mut h = Hid::new();
        let (mut k, mut b, mut m) = ([0u8; 29], 0u8, 0u8);
        for _ in 0..6 {
            let len = if round % 10 == 0 { 2000 } else { (next() % 120) as usize };
            let events: Vec<InjectEvent> = (0..len)
                .map(|_| match next() % 6 {
                    0 => InjectEvent::mouse_button((next() % 5) as u32, next() & 1 == 0),
                    1 => InjectEvent::mouse_move(next() as i32 >> (next() % 32), next() as i32 >> (next() % 32)),
                    2 => InjectEvent::wheel(next() as i32 >> (next() % 32)),
                    3 => InjectEvent::hwheel(next() as i32 >> (next() % 32)),
                    _ => InjectEvent { kind: InjectKind::Key, usage: next() as u8, flags: (next() as u32) & 0xF, ..Default::default() },
                })
                .collect();
            for r in h.reports(&events) {
                match r[0] {
                    KEYBOARD_ID => k.copy_from_slice(&r[1..]),
                    MOUSE_ID => b = r[1],
                    _ => m = r[1],
                }
            }
            assert_eq!(k[0] & 0x0F, 0);
            assert!(b <= 0x1F && m <= 0x3F);
            assert_eq!(h.r.any_held(), b != 0 || m != 0 || k.iter().any(|&x| x != 0));
        }
        // Whatever is held, a release of everything it could hold lets go of all of it.
        let mut release: Vec<InjectEvent> = (0..5).map(|i| InjectEvent::mouse_button(i, true)).collect();
        release.extend((0..=255u8).map(|u| InjectEvent { kind: InjectKind::Key, usage: u, flags: KEY_UP_FLAG, ..Default::default() }));
        h.reports(&release);
        assert!(!h.r.any_held());
    }
}

// ---- UsbFraming ------------------------------------------------------------------------------

fn frame(d: &[u8]) -> Vec<u8> {
    let mut b = vec![0u8; usb_framing::max_size(d.len())];
    let n = usb_framing::encode(d, &mut b).expect("frame");
    b.truncate(n);
    b
}

fn deframe(stream: &[u8], chunk: usize) -> Vec<Vec<u8>> {
    let mut d = UsbDeframer::new();
    let mut got = Vec::new();
    for c in stream.chunks(chunk) {
        d.feed(c, |f| got.push(f.to_vec()));
    }
    got
}

#[test]
fn round_trips_in_any_chunking() {
    let a: Vec<u8> = (0..wire::MAX_DATAGRAM).map(|i| (i ^ 0x47) as u8).collect();
    let b = vec![1u8, 2, 3];
    let stream = [frame(&a), frame(&b), frame(&a)].concat();
    for chunk in [1, 7, 64, stream.len()] {
        let got = deframe(&stream, chunk);
        assert_eq!(got, vec![a.clone(), b.clone(), a.clone()]);
    }
}

#[test]
fn pads_frames_that_fill_whole_packets() {
    assert_eq!(frame(&[0; 61]).len(), 65);
    assert_eq!(frame(&[0; 60]).len(), 63);
}

#[test]
fn skips_garbage_and_bad_lengths() {
    let junk = [1, 2, usb_framing::MAGIC, 0, 0, usb_framing::MAGIC, 0xFF, 0x7F];
    let got = deframe(&[&junk[..], &frame(&[9, 8, 7])].concat(), 64);
    assert_eq!(got, vec![vec![9u8, 8, 7]]);
}

#[test]
fn matches_the_phone_byte_for_byte() {
    // Same vector as the phone's UsbFramesTest: 0x47, length LE, bytes.
    assert_eq!(frame(&[9, 8, 7]), [0x47, 3, 0, 9, 8, 7]);
}

#[test]
fn encode_refuses_what_is_not_a_datagram() {
    let mut dst = [0u8; 1024];
    assert_eq!(usb_framing::encode(&[], &mut dst), None);
    assert_eq!(usb_framing::encode(&[0; wire::MAX_DATAGRAM + 1], &mut dst), None);
    assert_eq!(usb_framing::encode(&[1, 2, 3], &mut dst[..6]), None);
    assert!(usb_framing::encode(&[0; wire::MAX_DATAGRAM], &mut dst).is_some());
}

/// A stream of random bytes in random chunks: no panic, every frame a legal size; and real frames
/// after the noise still come through once the reader has found a start again.
#[test]
fn deframer_takes_any_bytes() {
    let mut next = rng(0x0123_4567_89AB_CDEF);
    let mut d = UsbDeframer::new();
    let mut frames = 0usize;
    for _ in 0..3000 {
        let len = (next() % 700) as usize;
        let chunk: Vec<u8> = (0..len).map(|_| if next() % 4 == 0 { usb_framing::MAGIC } else { next() as u8 }).collect();
        d.feed(&chunk, |f| {
            assert!((1..=wire::MAX_DATAGRAM).contains(&f.len()));
            frames += 1;
        });
    }
    assert!(frames > 0);
    // At most one frame's worth of zeros ends whatever the noise left half read; then real frames pass.
    let mut got = Vec::new();
    d.feed(&vec![0u8; usb_framing::HEADER + wire::MAX_DATAGRAM], |_| {});
    d.feed(&[frame(&[9, 8, 7]), frame(&[5])].concat(), |f| got.push(f.to_vec()));
    assert_eq!(got, vec![vec![9u8, 8, 7], vec![5u8]]);
}
