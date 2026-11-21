//! Diagnostics' PC stall watch (docs/spec/pc-companion.md, "Diagnostics"). Port of StallWatch.cs.
//!
//! A thread of its own asks to wake every millisecond and notes how late Windows woke it. A late wake means the PC
//! couldn't run a waiting thread on time (a driver, another program, power saving), which is what a late phone
//! packet looks like from inside the PC. So a late packet with a calm watch was held up before it reached the PC
//! (Wi-Fi, router, cable), and one with a late watch waited inside the PC.
//!
//! Runs only while Diagnostics is on, a step below gp-rx (AboveNormal, while gp-rx has the games boost), so it never
//! competes with the input. Nothing here touches the input path: the recorder reads its numbers once a second and
//! when a hold-up ends.

use crate::ports::Clock;
use crate::sys::{self, Priority};
use crate::watchdog::TickSleepFactory;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

/// Asked wake interval.
pub const PERIOD_US: i64 = 1000;
/// A wake this much late or more counts as a stall.
pub const STALL_US: i64 = 2000;
/// Wakes later than this are kept (time, lateness) for `worst_between`.
const KEEP_US: i64 = 500;
const RING: usize = 4096;

struct Shared {
    stop: AtomicBool,
    running: AtomicBool,
    // Late wakes (one writer, the watch thread).
    at: Vec<AtomicI64>,
    late: Vec<AtomicI64>,
    head: AtomicI64,
    // This second.
    s_max_us: AtomicI64,
    s_count: AtomicI64,
    s_wakes: AtomicI64,
}

pub struct StallWatch {
    sh: Arc<Shared>,
    sleep: TickSleepFactory,
    clock: Arc<dyn Clock>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl StallWatch {
    pub fn new(sleep: TickSleepFactory, clock: Arc<dyn Clock>) -> StallWatch {
        StallWatch {
            sh: Arc::new(Shared {
                stop: AtomicBool::new(false),
                running: AtomicBool::new(false),
                at: (0..RING).map(|_| AtomicI64::new(0)).collect(),
                late: (0..RING).map(|_| AtomicI64::new(0)).collect(),
                head: AtomicI64::new(0),
                s_max_us: AtomicI64::new(0),
                s_count: AtomicI64::new(0),
                s_wakes: AtomicI64::new(0),
            }),
            sleep,
            clock,
            thread: Mutex::new(None),
        }
    }

    pub fn running(&self) -> bool {
        self.sh.running.load(Ordering::Acquire)
    }

    pub fn start(&self) {
        let mut t = self.thread.lock().unwrap_or_else(|e| e.into_inner());
        if t.is_some() {
            return;
        }
        self.sh.stop.store(false, Ordering::SeqCst);
        let sh = self.sh.clone();
        let sleep = self.sleep.clone();
        let clock = self.clock.clone();
        let spawned = std::thread::Builder::new().name("gp-stallwatch".into()).spawn(move || {
            sys::set_thread_priority(Priority::AboveNormal);
            let mut sleep = sleep();
            let mut next = clock.now_us() + PERIOD_US;
            while !sh.stop.load(Ordering::Relaxed) {
                let wait = next - clock.now_us();
                if wait > 0 {
                    sleep.sleep(wait);
                }
                let now = clock.now_us();
                add(&sh, now, now - next);
                next += PERIOD_US;
                // Far behind (the PC slept, or a long stall): start again from now rather than rush to catch up.
                if now - next > 50 * PERIOD_US {
                    next = now + PERIOD_US;
                }
            }
        });
        if let Ok(h) = spawned {
            self.sh.running.store(true, Ordering::Release);
            *t = Some(h);
        }
    }

    pub fn stop(&self) {
        let h = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take();
        self.sh.stop.store(true, Ordering::SeqCst);
        self.sh.running.store(false, Ordering::Release);
        if let Some(h) = h {
            let _ = h.join();
        }
    }

    /// Tests: one wake `late_us` late at `now_us`.
    pub fn add(&self, now_us: i64, late_us: i64) {
        add(&self.sh, now_us, late_us);
    }

    /// The second just ended: worst lateness (µs), stalls over `STALL_US`, wakes. Clears them.
    pub fn take_second(&self) -> (i64, i64, i64) {
        (
            self.sh.s_max_us.swap(0, Ordering::AcqRel),
            self.sh.s_count.swap(0, Ordering::AcqRel),
            self.sh.s_wakes.swap(0, Ordering::AcqRel),
        )
    }

    /// The worst lateness of a wake that was due inside [from_us, to_us] (a wake stamped at t that was L late was
    /// due at t − L, and the PC was busy from then to t), 0 when calm, −1 when the watch isn't running.
    pub fn worst_between(&self, from_us: i64, to_us: i64) -> i64 {
        if !self.running() {
            return -1;
        }
        let head = self.sh.head.load(Ordering::Acquire);
        let mut worst = 0;
        let mut k = head - 1;
        while k >= 0 && k >= head - RING as i64 {
            let i = (k as usize) & (RING - 1);
            let at = self.sh.at[i].load(Ordering::Acquire);
            let late = self.sh.late[i].load(Ordering::Acquire);
            if at < from_us {
                break;
            }
            if at - late <= to_us && late > worst {
                worst = late;
            }
            k -= 1;
        }
        worst
    }
}

impl Drop for StallWatch {
    fn drop(&mut self) {
        self.stop();
    }
}

fn add(sh: &Shared, now: i64, late: i64) {
    sh.s_wakes.fetch_add(1, Ordering::Relaxed);
    if late < KEEP_US {
        return;
    }
    let h = sh.head.load(Ordering::Relaxed);
    let i = (h as usize) & (RING - 1);
    sh.at[i].store(now, Ordering::Release);
    sh.late[i].store(late, Ordering::Release);
    sh.head.store(h + 1, Ordering::Release);
    if late >= STALL_US {
        sh.s_count.fetch_add(1, Ordering::Relaxed);
    }
    sh.s_max_us.fetch_max(late, Ordering::AcqRel);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::watchdog::PlainTickSleep;

    struct Fixed;
    impl Clock for Fixed {
        fn now_us(&self) -> i64 {
            0
        }
    }

    #[test]
    fn worst_between_finds_overlapping_stall() {
        let w = StallWatch::new(Arc::new(|| Box::new(PlainTickSleep)), Arc::new(Fixed));
        w.sh.running.store(true, Ordering::Release);
        w.add(1_000, 100);
        w.add(10_000, 3_000); // busy 7 000..10 000
        w.add(20_000, 600);
        assert_eq!(w.worst_between(8_000, 9_000), 3_000);
        assert_eq!(w.worst_between(11_000, 15_000), 0);
        assert_eq!(w.worst_between(19_500, 30_000), 600);
        let (max, stalls, wakes) = w.take_second();
        assert_eq!((max, stalls, wakes), (3_000, 1, 3));
        assert_eq!(w.take_second(), (0, 0, 0));
        w.sh.running.store(false, Ordering::Release);
        assert_eq!(w.worst_between(0, 100_000), -1);
    }
}
