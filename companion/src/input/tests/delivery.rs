//! Pairing raw mouse input with GyroPhone's handed-off moves (ports of DeliveryMatchTests.cs), and the probe's
//! lines.

use std::sync::{Arc, Mutex};

use gp_input::delivery::*;

const FREQ: i64 = 10_000_000;
const MS: i64 = FREQ / 1000;

struct Rig {
    ring: Arc<LocalMoveRing>,
    matcher: DeliveryMatcher,
    got: Vec<Delivery>,
    missed: i64,
    stray: i64,
}

impl Rig {
    fn new() -> Rig {
        let ring = Arc::new(LocalMoveRing::new());
        Rig { matcher: DeliveryMatcher::new(ring.clone(), FREQ), ring, got: Vec::new(), missed: 0, stray: 0 }
    }

    fn take(&mut self, found: Vec<Matched>) {
        for m in found {
            match m {
                Matched::Delivered(d) => self.got.push(d),
                Matched::Missed(n) => self.missed += n,
                Matched::Stray => self.stray += 1,
            }
        }
    }

    fn raw(&mut self, now: i64, x: i32, y: i32, driver: bool) {
        let mut found = Vec::new();
        self.matcher.raw(now, x, y, driver, &mut |m| found.push(m));
        self.take(found);
    }

    fn settle(&mut self, now: i64) {
        let mut found = Vec::new();
        self.matcher.settle(now, &mut |m| found.push(m));
        self.take(found);
    }
}

#[test]
fn one_by_one_moves_are_timed_each() {
    let mut r = Rig::new();
    r.ring.put(100 * MS, 3, 0);
    r.raw(101 * MS, 3, 0, true);
    r.ring.put(110 * MS, -2, 1);
    r.raw(113 * MS, -2, 1, true);
    assert_eq!(r.got.len(), 2);
    assert_eq!(r.got[0].ticks, MS);
    assert_eq!(r.got[1].ticks, 3 * MS);
    assert_eq!(r.got[1].gap_ticks, 10 * MS);
    assert_eq!(r.got[1].merged, 0);
    assert_eq!(r.missed, 0);
}

#[test]
fn merged_run_is_timed_from_its_newest_move() {
    let mut r = Rig::new();
    r.ring.put(100 * MS, 1, 0);
    r.ring.put(101 * MS, 2, 0);
    r.ring.put(102 * MS, 3, 1);
    r.raw(108 * MS, 6, 1, true);
    assert_eq!(r.got.len(), 1);
    assert_eq!(r.got[0].ticks, 6 * MS);
    assert_eq!(r.got[0].merged, 2);
    assert_eq!(r.got[0].gap_ticks, MS);
}

#[test]
fn raw_move_waits_for_its_hand_off_then_strays() {
    let mut r = Rig::new();
    r.raw(100 * MS, 5, 5, false);
    assert!(r.got.is_empty());
    r.ring.put(99 * MS, 5, 5);
    r.settle(101 * MS);
    assert_eq!(r.got.len(), 1);
    r.raw(200 * MS, 7, 7, false);
    r.settle(300 * MS);
    assert_eq!(r.stray, 1);
}

#[test]
fn moves_that_never_came_are_missed() {
    let mut r = Rig::new();
    r.ring.put(100 * MS, 1, 0);
    r.ring.put(101 * MS, 4, 0);
    r.raw(103 * MS, 4, 0, true);
    assert_eq!(r.missed, 1);
    r.ring.put(110 * MS, 9, 9);
    r.settle(300 * MS);
    assert_eq!(r.missed, 2);
}

#[test]
fn skip_forgets_without_counting() {
    let mut r = Rig::new();
    r.ring.put(100 * MS, 1, 0);
    r.ring.put(101 * MS, 1, 0);
    r.matcher.skip();
    r.settle(500 * MS);
    assert_eq!(r.missed, 0);
    r.ring.put(510 * MS, 2, 2);
    r.raw(511 * MS, 2, 2, true);
    assert_eq!(r.got.len(), 1);
    assert_eq!(r.got[0].gap_ticks, 409 * MS);
}

#[test]
fn full_ring_drops_and_counts() {
    let ring = LocalMoveRing::new();
    for i in 0..LocalMoveRing::CAPACITY + 5 {
        ring.put(i, 1, 0);
    }
    assert_eq!(ring.take_overflow(), 5);
    assert_eq!(ring.take_overflow(), 0);
    assert_eq!(ring.dx_dy(3), (1, 0));
    ring.put(0, -7, i32::MIN);
    assert_eq!(ring.take_overflow(), 1);
    ring.set_tail(1);
    ring.put(9, -7, i32::MIN);
    assert_eq!(ring.dx_dy(LocalMoveRing::CAPACITY), (-7, i32::MIN));
}

#[test]
fn tally_counts_slow() {
    let mut t = DeliveryTally::new(FREQ, 16);
    for _ in 0..998 {
        t.add(&Delivery { ticks: MS / 2, merged: 0, driver: true, gap_ticks: 0 });
    }
    t.add(&Delivery { ticks: 3 * MS, merged: 0, driver: true, gap_ticks: 0 });
    t.add(&Delivery { ticks: 20 * MS, merged: 1, driver: true, gap_ticks: 0 });
    t.take(&Matched::Missed(2));
    let n = t.numbers();
    assert_eq!((n.n, n.slow, n.unmatched, n.merged, n.stray), (1000, 2, 2, 1, 0));
    assert!((n.max - 20.0).abs() < 1e-9);
    assert!((n.p50 - 0.5).abs() < 1e-9);
}

#[test]
fn the_probe_makes_the_seconds_text_and_slow_lines() {
    let shared = ProbeShared::new(FREQ);
    let lines = Arc::new(Mutex::new(Vec::<String>::new()));
    let l2 = lines.clone();
    shared.set_slow_line(Some(Arc::new(move |s: &str| l2.lock().unwrap().push(s.to_string()))));
    let mut probe = ProbeThread::new(shared.clone());
    assert_eq!(shared.second_text(), None);
    shared.start_test(true);

    shared.ring.put(100 * MS, 3, 0);
    probe.raw(100 * MS + MS / 2, 3, 0, false, &Wake::default(), &mut || unreachable!());
    shared.ring.put(110 * MS, 1, 0);
    shared.ring.put(111 * MS, 1, 0);
    let wake = Wake { slept_ticks: 12 * MS, read_after_wake_ticks: MS / 50, since_wake: 0 };
    probe.raw(114 * MS, 2, 0, true, &wake, &mut || "Game".to_string());
    assert_eq!(
        lines.lock().unwrap().as_slice(),
        ["delivery slow 3.00 ms (driver): gap 1.0 ms since the move before, merged 1, front Game, probe slept 12.0 ms, read 0.02 ms after waking, 1st since waking"]
    );

    probe.second(120 * MS);
    assert_eq!(shared.second_text().as_deref(), Some(" delivery[both] n 2 p50 3.00 p99 3.00 max 3.00 over2 1 unmatched 0 merged 1"));
    assert_eq!(shared.second_text(), None);
    probe.second(1120 * MS);
    assert_eq!(shared.second_text(), None);

    let n = shared.test_numbers().unwrap();
    assert_eq!((n.n, n.slow, n.merged), (2, 1, 1));
    shared.count_test(false);
    shared.ring.put(2000 * MS, 1, 1);
    probe.raw(2001 * MS, 1, 1, false, &Wake::default(), &mut || unreachable!());
    assert_eq!(shared.test_numbers().unwrap().n, 2);
    shared.stop_test();
    assert_eq!(shared.test_numbers(), None);
}

#[test]
fn random_moves_never_break_the_matcher() {
    let mut r = Rig::new();
    let mut s = 0x9E37_79B9_7F4A_7C15u64;
    let mut next = move || {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        s
    };
    let mut now = 0i64;
    for _ in 0..200_000 {
        let v = next();
        now = now.wrapping_add((v % (80 * MS as u64)) as i64);
        match v >> 60 {
            0..=6 => r.ring.put(if v & 1 == 0 { now } else { v as i64 }, (v >> 8) as i32 % 5, (v >> 20) as i32),
            7..=12 => r.raw(now, (v >> 8) as i32 % 9, (v >> 20) as i32 % 3, v & 2 != 0),
            13 => r.matcher.skip(),
            _ => r.settle(if v & 4 == 0 { now } else { (v >> 3) as i64 }),
        }
        assert!(r.ring.tail() <= r.ring.head());
    }
}
