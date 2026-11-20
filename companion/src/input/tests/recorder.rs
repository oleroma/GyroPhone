//! The input log. Port of the C# InputRecorderTests.

mod common;

use common::rng;
use gp_input::link_stats::LinkStats;
use gp_input::recorder::*;

struct Rec {
    rec: InputRecorder,
    lines: Vec<String>,
    seconds: Vec<LinkSecond>,
}

impl Rec {
    fn new() -> Rec {
        let mut r = Rec { rec: InputRecorder::new(), lines: Vec::new(), seconds: Vec::new() };
        r.rec.session_started("phone", 0);
        r
    }
    fn drain(&mut self) {
        let mut out = Vec::new();
        self.rec.take(&mut out);
        for o in out {
            match o {
                RecorderOut::Line(l) => self.lines.push(l.text()),
                RecorderOut::Second(s) => self.seconds.push(s),
            }
        }
    }
    fn with(&mut self, what: &str) -> Vec<String> {
        self.drain();
        self.lines.iter().filter(|l| l.contains(what)).cloned().collect()
    }
}

const NONE: HandOffCauses = HandOffCauses::NONE;

#[test]
fn packets_held_up_on_the_way_are_one_late_line() {
    let mut r = Rec::new();
    let mut t = 1_000_000i64;
    let mut phone = 5_000u32;
    for _ in 0..200 {
        r.rec.state(phone, t + 3000, true, true, false);
        t += 1000;
        phone += 1000;
    }
    // 30 packets sent on time arrive in a burst 40 ms late, then normal again.
    for i in 0..30i64 {
        r.rec.state(phone, t + 3000 + 40_000 - i * 1000 + i * 50, true, true, false);
        phone += 1000;
    }
    t += 30_000;
    for _ in 0..50 {
        r.rec.state(phone, t + 3000 + 20_000, true, true, false);
        t += 1000;
        phone += 1000;
    }
    for _ in 0..50 {
        r.rec.state(phone, t + 3000, true, true, false);
        t += 1000;
        phone += 1000;
    }
    let late = r.with(" late ");
    assert_eq!(late.len(), 1);
    assert!(late[0].contains("held up on the way"));
    assert!(late[0].contains("up to 40.0 ms late"), "{}", late[0]);
}

#[test]
fn not_writing_makes_no_text_but_still_counts_the_second() {
    let mut r = Rec::new();
    r.rec.write_lines = false;
    r.drain();
    r.lines.clear();
    let notes = std::sync::Arc::new(std::sync::atomic::AtomicU32::new(0));
    let n = notes.clone();
    r.rec.second_note = Some(Box::new(move || {
        n.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Some(" note".to_string())
    }));
    r.rec.state(1_000, 10_000, true, true, false);
    r.rec.inject(70, 0, Some(10_000), 13_000, false, &NONE);
    r.rec.lost(5, 42, 20_000);
    r.rec.second(&LinkStats::default(), 1_000_000);
    r.drain();
    assert!(r.lines.is_empty());
    assert_eq!(notes.load(std::sync::atomic::Ordering::SeqCst), 1);
    assert_eq!(r.seconds.len(), 1);
    assert_eq!((r.seconds[0].lost, r.seconds[0].at_us, r.seconds[0].lat_p50_ms), (5, 1_000_000, -1.0));
    // Writing again: events get lines.
    r.rec.write_lines = true;
    r.rec.inject(70, 0, Some(1_010_000), 1_010_100, false, &NONE);
    assert_eq!(r.with("big move").len(), 1);
}

#[test]
fn phone_not_sending_while_moving_is_a_phone_gap() {
    let mut r = Rec::new();
    r.rec.state(1_000, 10_000, true, true, false);
    r.rec.state(2_000, 11_000, true, true, false);
    r.rec.state(52_000, 61_000, true, true, false);
    // A lost stretch is not the phone's gap, and an idle pause isn't either.
    r.rec.state(102_000, 111_000, true, true, true);
    r.rec.state(103_000, 112_000, false, false, false);
    r.rec.state(200_000, 209_000, false, false, false);
    let gaps = r.with("phone gap");
    assert_eq!(gaps.len(), 1);
    assert_eq!(gaps[0], "     0.061 phone gap     phone sent nothing for 50.0 ms while input was moving or held");
}

#[test]
fn second_line_and_session_summary_count_without_naming_keys() {
    let mut r = Rec::new();
    r.rec.state(1_000, 10_000, true, true, false);
    r.rec.inject(3, -2, Some(10_000), 10_100, false, &NONE);
    r.rec.inject(70, 0, Some(10_000), 13_000, false, &NONE);
    r.rec.lost(5, 42, 20_000);
    r.rec.released_for_silence(2, 300_000);
    r.rec.second(&LinkStats::default(), 1_000_000);
    r.rec.session_ended("timed out", 2_000_000);
    r.drain();
    assert_eq!(r.lines[0], "     0.000 session start phone 'phone'");
    assert_eq!(r.with("big move"), ["     0.013 big move      70,0 px in one step"]);
    assert_eq!(r.with("slow hand-off"), ["     0.013 slow hand-off 3.0 ms from receive to SendInput: the rest 3.0 ms"]);
    assert_eq!(r.with(" lost    "), ["     0.020 lost          5 packets before seq 42"]);
    assert_eq!(r.with(" release "), ["     0.300 release       phone silent 250 ms: let go of 2 held keys or buttons"]);
    assert_eq!(
        r.with(" sec "),
        ["     1.000 sec           rx 0 moving 1 lost 5 late 0 maxlate 0.0 over 0.1/0.1 jitter 0.0 inj 2 px 75 maxstep 70 handoff 3.0 slow 1 bigmoves 1"]
    );
    assert_eq!(
        r.with("session end"),
        ["     2.000 session end   timed out after 2 s: states 1 lost 5 late 0 late3 0 holdups 0 (air 0 pc 0 phone 0) phone-gaps 0 catch-ups 0 jumps 1 slow 1 releases 1 refused 0; over the floor ms p50 <0.1 p99 <0.1 p99.9 <0.1"]
    );
}

#[test]
fn a_flick_writes_a_few_lines_and_counts_the_rest() {
    let mut r = Rec::new();
    for i in 0..300 {
        r.rec.inject(80, 0, None, 1_000 + i * 1000, false, &NONE);
    }
    r.rec.second(&LinkStats::default(), 1_000_000);
    assert_eq!(r.with("big move").len(), 10);
    let sec = r.with(" sec ");
    assert!(sec[0].contains("bigmoves 300"));
    assert!(sec[0].contains("290 more events not written"));
    r.rec.inject(80, 0, None, 1_100_000, false, &NONE);
    assert_eq!(r.with("big move").len(), 11);
}

#[test]
fn a_slow_hand_off_says_what_took_the_time() {
    let mut r = Rec::new();
    let mut causes = HandOffCauses::waited(500, Some("overlay"));
    causes.add_lock_wait(2_000, Some("watchdog"));
    causes.pad_us = 200;
    causes.send_input_us = 300;
    causes.route = Some("input driver");
    r.rec.inject(5, 0, Some(10_000), 14_000, false, &causes);
    let slow = r.with("slow hand-off");
    assert!(
        slow[0].ends_with(
            "4.0 ms from receive to input driver: engine lock 2.5 ms (watchdog had it), pad driver 0.2 ms, input driver 0.3 ms, the rest 1.0 ms"
        ),
        "{}",
        slow[0]
    );
    r.rec.second(&LinkStats::default(), 1_000_000);
    assert!(r.with(" sec ")[0].contains("lockwait 2.5 (watchdog)"));
    // Refused by Windows, a catch-up, a note.
    r.rec.inject(0, 0, None, 1_500_000, true, &NONE);
    r.rec.catch_up(12_340, 55, 8_000, 1_600_000);
    r.rec.note("link test", "started for 5 s", 1_700_000);
    assert!(r.with("refused")[0].contains("Windows took fewer events than given"));
    assert!(r.with("catch-up")[0].ends_with("55 px from 12.3 ms of late or lost motion spread over 8.0 ms"));
    assert_eq!(r.with("link test"), ["     1.700 link test     started for 5 s"]);
}

#[test]
fn nothing_is_written_outside_a_session() {
    let mut rec = InputRecorder::new();
    rec.state(1, 2, true, true, false);
    rec.inject(100, 0, Some(0), 10_000, true, &NONE);
    rec.second(&LinkStats::default(), 1_000_000);
    rec.lost(9, 1, 5);
    rec.note("a", "b", 1);
    assert!(!rec.has_pending());
}

#[test]
fn milliseconds_read_as_dotnet_writes_them() {
    assert_eq!(ms1(0), "0.0");
    assert_eq!(ms1(49), "0.0");
    assert_eq!(ms1(50), "0.1");
    assert_eq!(ms1(150), "0.2");
    assert_eq!(ms1(250), "0.3");
    assert_eq!(ms1(1_949), "1.9");
    assert_eq!(ms1(1_950), "2.0");
    assert_eq!(ms1(40_000), "40.0");
    assert_eq!(ms1(-1_250), "-1.3");
    assert_eq!(ms1(i64::MAX), "9223372036854775.8");
    assert_eq!(ms1(i64::MIN), "-9223372036854775.8");
}

#[test]
fn any_numbers_are_taken_without_a_panic_and_the_queue_stays_small() {
    let mut next = rng(4242);
    let mut rec = InputRecorder::new();
    let stats = LinkStats::default();
    let mut out = Vec::new();
    for i in 0..300_000u32 {
        let a = next();
        let (x, y, t) = (next() as i32, next() as i32, next() as i64);
        let t = if a & 16 == 0 { t } else { (i as i64) * 700 };
        match a % 9 {
            0 => rec.session_started("p", t),
            1 => rec.session_ended("x", t),
            2 => rec.lost(t, x as u32, t),
            3 | 4 => rec.state(x as u32, t, a & 32 == 0, a & 64 == 0, a & 128 == 0),
            5 => {
                let mut c = HandOffCauses::waited(next() as i64, Some("ui"));
                c.pad_us = next() as i64;
                c.send_input_us = next() as i64;
                rec.inject(x, y, if a & 256 == 0 { Some(next() as i64) } else { None }, t, a & 512 == 0, &c)
            }
            6 => rec.catch_up(t, x, next() as i64, t),
            7 => rec.released_for_silence(x as u32, t),
            _ => rec.second(&stats, t),
        }
        if a & 0xFF00_0000 == 0 {
            rec.take(&mut out);
        }
    }
    rec.take(&mut out);
    assert!(out.len() <= 4096);
}

/// 200 on-time packets, then 30 that arrive 40 ms late, then normal again; returns the time after.
fn hold_up(r: &mut Rec, t0: i64) -> i64 {
    let mut t = t0;
    let mut phone = 5_000u32;
    for _ in 0..200 {
        r.rec.state(phone, t + 3000, true, true, false);
        t += 1000;
        phone += 1000;
    }
    for _ in 0..30 {
        r.rec.state(phone, t + 3000 + 40_000, true, true, false);
        t += 1000;
        phone += 1000;
    }
    for _ in 0..50 {
        r.rec.state(phone, t + 3000, true, true, false);
        t += 1000;
        phone += 1000;
    }
    t
}

fn watch() -> std::sync::Arc<gp_input::stall_watch::StallWatch> {
    struct Zero;
    impl gp_input::ports::Clock for Zero {
        fn now_us(&self) -> i64 {
            0
        }
    }
    std::sync::Arc::new(gp_input::stall_watch::StallWatch::new(
        std::sync::Arc::new(|| Box::new(gp_input::watchdog::PlainTickSleep)),
        std::sync::Arc::new(Zero),
    ))
}

#[test]
fn a_hold_up_says_whether_it_waited_inside_the_pc_or_on_the_way() {
    // No stall watch (Diagnostics off): the old wording.
    let mut r = Rec::new();
    hold_up(&mut r, 1_000_000);
    r.rec.second(&LinkStats::default(), 2_000_000);
    assert!(r.with(" late ")[0].ends_with("(sent on time: held up on the way)"), "{:?}", r.lines);
    assert_eq!(r.seconds[0].air, 30);

    // The PC stalled 5 ms while the packets waited: PC.
    let w = watch();
    w.start();
    let mut r = Rec::new();
    r.rec.stall = Some(w.clone());
    w.add(1_230_000, 5_000);
    hold_up(&mut r, 1_000_000);
    r.rec.second(&LinkStats::default(), 2_000_000);
    let late = r.with(" late ");
    assert!(late[0].ends_with("(sent on time, the PC stalled 5.0 ms then: PC)"), "{}", late[0]);
    assert_eq!((r.seconds[0].pc, r.seconds[0].air, r.seconds[0].late), (30, 0, 30));
    assert!(r.with(" sec ")[0].contains("heldup air 0 pc 30 phone 0"));

    // The PC ran on time: on the way.
    let mut r = Rec::new();
    r.rec.stall = Some(w.clone());
    hold_up(&mut r, 3_000_000);
    r.rec.second(&LinkStats::default(), 4_000_000);
    assert!(r.with(" late ")[0].ends_with("(sent on time, the PC ran on time: air)"));
    assert_eq!(r.seconds[0].air, 30);
    w.stop();
}

#[test]
fn the_second_splits_floor_and_over_the_floor() {
    let mut r = Rec::new();
    let mut phone = 1_000u32;
    let mut t = 10_000i64;
    // 90 packets on time, 5 at 5 ms over (late3), 5 at 1 ms over.
    for i in 0..100 {
        let extra = if i >= 95 { 1_000 } else if i >= 90 { 5_000 } else { 0 };
        r.rec.state(phone, t + extra, true, true, false);
        phone += 1000;
        t += 1000;
    }
    r.rec.pad_hand_off(t, t + 2_500, &NONE);
    let mut stats = LinkStats::default();
    stats.phone_rtt_typ_us = 2_500;
    stats.negative_per_sec = 2;
    stats.last_link_info = Some(gp_input::codec::LinkInfo { rtt_min_us: 1_500, ..Default::default() });
    r.rec.second(&stats, 1_000_000);
    r.drain();
    let s = r.seconds[0];
    assert_eq!((s.late3, s.floor_ms, s.rtt_typ_ms, s.neg_lat, s.slow_hand_offs, s.moving), (5, 0.75, 2.5, 2, 1, 100));
    assert_eq!((s.over_p50_ms, s.over_p99_ms), (0.05, 5.05));
    assert_eq!(s.hand_off_max_ms, 2.5);
    assert_eq!(s.stall_max_ms, -1.0);
    let sec = &r.with(" sec ")[0];
    for part in ["floor 0.75", "rtt 1.50/2.50", "over 0.1/5.1", "late3 5", "neglat 2", "padonly 1", "slow 1"] {
        assert!(sec.contains(part), "{part}: {sec}");
    }
    assert_eq!(r.with("slow hand-off").len(), 1);
}
