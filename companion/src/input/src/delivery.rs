//! Timing our own mouse moves from their hand-off to Windows delivering them as raw input: the ring the input
//! path stores each move in, the matcher that pairs raw moves with stored ones, the tally, and the delivery
//! probe's own numbers and lines. The raw input reading itself is the Windows layer's. Ports of DeliveryMatch.cs
//! and the portable half of DeliveryProbe.cs. Times are performance counter ticks.

use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use crate::control::DeliveryNumbers;

/// GyroPhone's mouse moves as the input path handed them off, in this process only (the Delivery test window's
/// ring is the shared region's). One writer (the input path, under the engine lock: no lock, nothing made), one
/// reader. Full, a move is dropped and counted.
pub struct LocalMoveRing {
    head: AtomicI64,
    tail: AtomicI64,
    overflow: AtomicI64,
    ticks: Box<[AtomicI64]>,
    /// dx in the low half, dy in the high half.
    moves: Box<[AtomicU64]>,
}

impl Default for LocalMoveRing {
    fn default() -> Self {
        Self::new()
    }
}

impl LocalMoveRing {
    pub const CAPACITY: i64 = 4096;

    pub fn new() -> LocalMoveRing {
        LocalMoveRing {
            head: AtomicI64::new(0),
            tail: AtomicI64::new(0),
            overflow: AtomicI64::new(0),
            ticks: (0..Self::CAPACITY).map(|_| AtomicI64::new(0)).collect(),
            moves: (0..Self::CAPACITY).map(|_| AtomicU64::new(0)).collect(),
        }
    }

    fn slot(i: i64) -> usize {
        (i & (Self::CAPACITY - 1)) as usize
    }

    pub fn head(&self) -> i64 {
        self.head.load(Ordering::Acquire)
    }

    pub fn tail(&self) -> i64 {
        self.tail.load(Ordering::Acquire)
    }

    pub fn set_tail(&self, v: i64) {
        self.tail.store(v, Ordering::Release)
    }

    pub fn ticks(&self, i: i64) -> i64 {
        self.ticks[Self::slot(i)].load(Ordering::Acquire)
    }

    pub fn dx_dy(&self, i: i64) -> (i32, i32) {
        let m = self.moves[Self::slot(i)].load(Ordering::Acquire);
        (m as u32 as i32, (m >> 32) as u32 as i32)
    }

    /// Moves dropped because the ring was full since the last call (the reader fell behind, or there is none).
    pub fn take_overflow(&self) -> i64 {
        self.overflow.swap(0, Ordering::AcqRel)
    }

    /// A move's hand-off started at `ticks`. Input path: no lock, nothing made.
    pub fn put(&self, ticks: i64, dx: i32, dy: i32) {
        let h = self.head.load(Ordering::Relaxed);
        if !(0..Self::CAPACITY).contains(&h.wrapping_sub(self.tail.load(Ordering::Acquire))) {
            self.overflow.fetch_add(1, Ordering::AcqRel);
            return;
        }
        let s = Self::slot(h);
        self.ticks[s].store(ticks, Ordering::Release);
        self.moves[s].store(dx as u32 as u64 | (dy as u32 as u64) << 32, Ordering::Release);
        self.head.store(h.wrapping_add(1), Ordering::Release);
    }
}

/// One raw move matched to GyroPhone's moves.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Delivery {
    /// From the start of the newest matched move's hand-off to the raw move being read.
    pub ticks: i64,
    /// GyroPhone's moves that came inside this raw move besides the newest.
    pub merged: i64,
    /// The input driver's mouse (else SendInput).
    pub driver: bool,
    /// From the hand-off of the move before the newest one to the newest one's: how sparse the moves were.
    pub gap_ticks: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Matched {
    Delivered(Delivery),
    /// Stored moves that never showed up (lost, or the reader didn't get them).
    Missed(i64),
    /// A raw move from GyroPhone that matched nothing.
    Stray,
}

const PENDING: usize = 32;
const MAX_SKIP: i64 = 32;
const MAX_RUN: i64 = 128;

#[derive(Clone, Copy, Default)]
struct Pending {
    ticks: i64,
    x: i32,
    y: i32,
    driver: bool,
}

/// Pairs raw mouse input with GyroPhone's moves in a ring. Windows 11 hands a background raw input reader (the
/// probe; the game in front still gets every report) a device's moves merged, at most about 125 times a second,
/// so one raw move is the sum of a run of stored moves in a row; SendInput moves come one by one. A raw move that
/// matches no run yet waits a little (its hand-off may not be stored yet) instead of being thrown away. The
/// reader's thread only.
pub struct DeliveryMatcher {
    ring: Arc<LocalMoveRing>,
    /// 50 ms.
    stale: i64,
    pending: [Pending; PENDING],
    first: usize,
    count: usize,
    prev_ticks: i64,
}

impl DeliveryMatcher {
    /// `frequency`: ticks per second.
    pub fn new(ring: Arc<LocalMoveRing>, frequency: i64) -> DeliveryMatcher {
        DeliveryMatcher { ring, stale: (frequency / 20).max(1), pending: [Pending::default(); PENDING], first: 0, count: 0, prev_ticks: 0 }
    }

    /// A raw move from GyroPhone read at `now`.
    pub fn raw(&mut self, now: i64, x: i32, y: i32, driver: bool, out: &mut dyn FnMut(Matched)) {
        if self.count == PENDING {
            out(Matched::Stray);
            self.drop_first();
        }
        let slot = (self.first + self.count) % PENDING;
        self.count += 1;
        self.pending[slot] = Pending { ticks: now, x, y, driver };
        self.settle(now, out);
    }

    /// Forgets every stored and waiting move without counting them (the reader wasn't listening).
    pub fn skip(&mut self) {
        self.count = 0;
        let head = self.ring.head();
        if head > 0 {
            self.prev_ticks = self.ring.ticks(head - 1);
        }
        self.ring.set_tail(head);
    }

    /// Matches waiting raw moves in order, and gives up on stored moves and raw moves too old to still pair.
    pub fn settle(&mut self, now: i64, out: &mut dyn FnMut(Matched)) {
        let (mut tail, head) = (self.ring.tail(), self.ring.head());
        let safe = tail; // moves from here on can't be written over until the tail moves
        while self.count > 0 {
            let p = self.pending[self.first];
            // Stored moves too old to come with this raw move never showed up.
            let mut dropped = 0;
            while tail < head && p.ticks.wrapping_sub(self.ring.ticks(tail)) > self.stale {
                self.prev_ticks = self.ring.ticks(tail);
                tail += 1;
                dropped += 1;
            }
            if dropped > 0 {
                out(Matched::Missed(dropped));
            }
            if let Some((first, last)) = self.try_run(tail, head, p.x, p.y) {
                // Moves before the run never showed up; the run's newest move is the one this raw move delivered last.
                if first > tail {
                    out(Matched::Missed(first - tail));
                }
                let last_ticks = self.ring.ticks(last);
                let prev = if last - 1 >= safe { self.ring.ticks(last - 1) } else { self.prev_ticks };
                out(Matched::Delivered(Delivery {
                    ticks: p.ticks.wrapping_sub(last_ticks),
                    merged: last - first,
                    driver: p.driver,
                    gap_ticks: if prev == 0 { 0 } else { last_ticks.wrapping_sub(prev) },
                }));
                self.prev_ticks = last_ticks;
                tail = last + 1;
                self.ring.set_tail(tail);
                self.drop_first();
                continue;
            }
            self.ring.set_tail(tail);
            if now.wrapping_sub(p.ticks) <= self.stale {
                break; // its hand-off may not be stored yet
            }
            out(Matched::Stray);
            self.drop_first();
        }
        if self.count == 0 {
            // Nothing waiting: stored moves long past any raw move never showed up.
            let mut dropped = 0;
            while tail < head && now.wrapping_sub(self.ring.ticks(tail)) > 2 * self.stale {
                self.prev_ticks = self.ring.ticks(tail);
                tail += 1;
                dropped += 1;
            }
            if dropped > 0 {
                out(Matched::Missed(dropped));
            }
        }
        self.ring.set_tail(tail);
    }

    /// The first run of stored moves in a row (starting at most `MAX_SKIP` in) that adds up to x, y.
    fn try_run(&self, tail: i64, head: i64, x: i32, y: i32) -> Option<(i64, i64)> {
        for s in tail..head.min(tail + MAX_SKIP) {
            let (mut sx, mut sy) = (0i32, 0i32);
            for k in s..head.min(s + MAX_RUN) {
                let (dx, dy) = self.ring.dx_dy(k);
                sx = sx.wrapping_add(dx);
                sy = sy.wrapping_add(dy);
                if sx == x && sy == y {
                    return Some((s, k));
                }
            }
        }
        None
    }

    fn drop_first(&mut self) {
        self.first = (self.first + 1) % PENDING;
        self.count -= 1;
    }
}

/// Adds up `Delivery` results. One thread at a time (the caller's).
pub struct DeliveryTally {
    frequency: i64,
    lat: Vec<i64>,
    max: i64,
    slow: i64,
    unmatched: i64,
    merged: i64,
    stray: i64,
}

impl DeliveryTally {
    pub fn new(frequency: i64, capacity: usize) -> DeliveryTally {
        DeliveryTally { frequency: frequency.max(1), lat: Vec::with_capacity(capacity), max: 0, slow: 0, unmatched: 0, merged: 0, stray: 0 }
    }

    /// "Slow": a delivery over 2 ms.
    pub fn slow_ticks(&self) -> i64 {
        self.frequency / 500
    }

    pub fn is_empty(&self) -> bool {
        self.lat.is_empty() && self.unmatched == 0 && self.stray == 0
    }

    pub fn add(&mut self, d: &Delivery) {
        self.lat.push(d.ticks);
        self.max = self.max.max(d.ticks);
        if d.ticks > self.slow_ticks() {
            self.slow += 1;
        }
        self.merged += d.merged;
    }

    pub fn take(&mut self, m: &Matched) {
        match m {
            Matched::Delivered(d) => self.add(d),
            Matched::Missed(n) => self.unmatched += n,
            Matched::Stray => self.stray += 1,
        }
    }

    pub fn ms(&self, ticks: i64) -> f64 {
        ticks as f64 * 1000.0 / self.frequency as f64
    }

    /// The numbers so far (sorts the times it holds).
    pub fn numbers(&mut self) -> DeliveryNumbers {
        self.lat.sort_unstable();
        let n = self.lat.len();
        DeliveryNumbers {
            n: n as i64,
            p50: if n > 0 { self.ms(self.lat[n / 2]) } else { 0.0 },
            p99: if n > 0 { self.ms(self.lat[(n - 1).min(n * 99 / 100)]) } else { 0.0 },
            max: self.ms(self.max),
            slow: self.slow,
            unmatched: self.unmatched,
            merged: self.merged,
            stray: self.stray,
        }
    }

    pub fn clear(&mut self) {
        self.lat.clear();
        self.max = 0;
        self.slow = 0;
        self.unmatched = 0;
        self.merged = 0;
        self.stray = 0;
    }
}

/// What the probe's thread knows about its own waking when a raw move is read, for a slow move's line.
#[derive(Clone, Copy, Default)]
pub struct Wake {
    /// How long the thread had waited for messages before it last woke.
    pub slept_ticks: i64,
    /// From that waking to this message being read.
    pub read_after_wake_ticks: i64,
    /// Messages handled since that waking, before this one.
    pub since_wake: u32,
}

const MAX_SLOW_LINES: u32 = 20;

/// What the probe shares with other threads: the ring the input path writes, the last second's text, a Delivery
/// test's numbers, and who takes slow lines.
pub struct ProbeShared {
    pub ring: Arc<LocalMoveRing>,
    frequency: i64,
    text: Mutex<Option<String>>,
    test: Mutex<Option<DeliveryTally>>,
    test_counting: AtomicBool,
    slow_line: RwLock<Option<Arc<dyn Fn(&str) + Send + Sync>>>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl ProbeShared {
    pub fn new(frequency: i64) -> Arc<ProbeShared> {
        Arc::new(ProbeShared {
            ring: Arc::new(LocalMoveRing::new()),
            frequency: frequency.max(1),
            text: Mutex::new(None),
            test: Mutex::new(None),
            test_counting: AtomicBool::new(false),
            slow_line: RwLock::new(None),
        })
    }

    /// The last second's delivery numbers for the input log's second line, or None when no move was matched or
    /// missed. Made on the probe thread; this only hands the text over (once).
    pub fn second_text(&self) -> Option<String> {
        lock(&self.text).take()
    }

    pub fn set_slow_line(&self, to: Option<Arc<dyn Fn(&str) + Send + Sync>>) {
        *self.slow_line.write().unwrap_or_else(|e| e.into_inner()) = to;
    }

    /// A Delivery test starts: its moves are added up from now (while `counting`).
    pub fn start_test(&self, counting: bool) {
        *lock(&self.test) = Some(DeliveryTally::new(self.frequency, 16_384));
        self.test_counting.store(counting, Ordering::Release);
    }

    /// The test window is in front (its moves count) or not (paused).
    pub fn count_test(&self, counting: bool) {
        self.test_counting.store(counting, Ordering::Release);
    }

    pub fn stop_test(&self) {
        *lock(&self.test) = None;
    }

    /// The Delivery test's numbers so far, or None without one.
    pub fn test_numbers(&self) -> Option<DeliveryNumbers> {
        lock(&self.test).as_mut().map(|t| t.numbers())
    }
}

/// The probe thread's own half: matches each raw move, adds up the second, makes the lines.
pub struct ProbeThread {
    shared: Arc<ProbeShared>,
    matcher: DeliveryMatcher,
    second: DeliveryTally,
    saw_driver: bool,
    saw_send_input: bool,
    slow_lines: u32,
    slow_skipped: u32,
    found: Vec<Matched>,
}

impl ProbeThread {
    pub fn new(shared: Arc<ProbeShared>) -> ProbeThread {
        let f = shared.frequency;
        ProbeThread {
            matcher: DeliveryMatcher::new(shared.ring.clone(), f),
            second: DeliveryTally::new(f, 4096),
            shared,
            saw_driver: false,
            saw_send_input: false,
            slow_lines: 0,
            slow_skipped: 0,
            found: Vec::with_capacity(64),
        }
    }

    /// A raw move from GyroPhone read at `now`. `front` names the program in front (asked only for a slow line).
    pub fn raw(&mut self, now: i64, x: i32, y: i32, driver: bool, wake: &Wake, front: &mut dyn FnMut() -> String) {
        let mut found = std::mem::take(&mut self.found);
        self.matcher.raw(now, x, y, driver, &mut |m| found.push(m));
        self.took(&mut found, wake, front);
        self.found = found;
    }

    /// Each second: settles what waited, and the second's numbers become the text `second_text` hands out.
    pub fn second(&mut self, now: i64) {
        let mut found = std::mem::take(&mut self.found);
        self.matcher.settle(now, &mut |m| found.push(m));
        self.took(&mut found, &Wake::default(), &mut || "?".to_string());
        self.found = found;
        self.roll();
    }

    fn took(&mut self, found: &mut Vec<Matched>, wake: &Wake, front: &mut dyn FnMut() -> String) {
        let counting = self.shared.test_counting.load(Ordering::Acquire);
        for m in found.drain(..) {
            self.second.take(&m);
            if counting {
                if let Some(t) = lock(&self.shared.test).as_mut() {
                    t.take(&m);
                }
            }
            let Matched::Delivered(d) = m else { continue };
            if d.driver {
                self.saw_driver = true;
            } else {
                self.saw_send_input = true;
            }
            if d.ticks > self.second.slow_ticks() {
                let line = self.shared.slow_line.read().unwrap_or_else(|e| e.into_inner()).clone();
                if let Some(line) = line {
                    if self.slow_lines >= MAX_SLOW_LINES {
                        self.slow_skipped += 1;
                    } else {
                        self.slow_lines += 1;
                        line(&self.slow_text(&d, wake, &front()));
                    }
                }
            }
        }
    }

    /// One slow move's line, with what may explain it.
    fn slow_text(&self, d: &Delivery, wake: &Wake, front: &str) -> String {
        let ms = |t: i64| self.second.ms(t);
        let nth = match wake.since_wake {
            0 => "1st".to_string(),
            1 => "2nd".to_string(),
            2 => "3rd".to_string(),
            n => format!("{}th", n + 1),
        };
        format!(
            "delivery slow {:.2} ms ({}): gap {:.1} ms since the move before, merged {}, front {}, probe slept {:.1} ms, read {:.2} ms after waking, {} since waking",
            ms(d.ticks),
            if d.driver { "driver" } else { "SendInput" },
            ms(d.gap_ticks),
            d.merged,
            front,
            ms(wake.slept_ticks),
            ms(wake.read_after_wake_ticks),
            nth
        )
    }

    fn roll(&mut self) {
        let over = self.shared.ring.take_overflow();
        let skipped = std::mem::take(&mut self.slow_skipped);
        self.slow_lines = 0;
        if self.second.is_empty() {
            *lock(&self.shared.text) = None;
            return;
        }
        let n = self.second.numbers();
        let route = match (self.saw_driver, self.saw_send_input) {
            (true, true) => "both",
            (true, false) => "driver",
            _ => "SendInput",
        };
        let mut text = format!(
            " delivery[{route}] n {} p50 {:.2} p99 {:.2} max {:.2} over2 {} unmatched {} merged {}",
            n.n, n.p50, n.p99, n.max, n.slow, n.unmatched, n.merged
        );
        if n.stray > 0 {
            text.push_str(&format!(" stray {}", n.stray));
        }
        if over > 0 {
            text.push_str(&format!(" full {over}"));
        }
        if skipped > 0 {
            text.push_str(&format!(" slowlines-skipped {skipped}"));
        }
        *lock(&self.shared.text) = Some(text);
        self.second.clear();
        self.saw_driver = false;
        self.saw_send_input = false;
    }
}
