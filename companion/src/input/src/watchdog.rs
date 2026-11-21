//! The 50 ms tick driving `HostEngine::on_tick`, on its own thread (gp-watchdog, AboveNormal, an exact timer where
//! the system has one) so the release of held input never waits behind other work. Environment probes (1 Hz) run
//! on their own thread (gp-probe), and the files (the held-keys marker and settings) on a writer thread (gp-save,
//! BelowNormal) at most twice a second: a slow disk or a virus scan never holds up a tick. KEEP_AWAKE has its own
//! exact timer (gp-keepawake). Port of Watchdog.cs.

use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::codec::KeyLabels;
use crate::engine::{HostEngine, Signal};
use crate::held_marker::HeldKeysMarker;
use crate::ports::Clock;
use crate::settings::SettingsStore;
use crate::state_frame::KEY_BITMAP_SIZE;
use crate::sys::{self, Priority};
use crate::udp::Log;
use crate::wire;

const PROBE_INTERVAL_US: i64 = 1_000_000;
/// A failed settings write is tried again after this long.
const SAVE_RETRY_US: i64 = 1_000_000;
/// How often the writer looks for changes: held.json and settings are written at most this often.
pub const WRITE_INTERVAL_MS: u64 = 500;

/// Sleeps the calling thread for a set time, as exactly as the system allows. Each timer thread makes one for
/// itself. On Windows it is a high-resolution waitable timer, so a 50 ms tick is not stretched to the next
/// 15.6 ms system timer step.
pub trait TickSleep {
    fn sleep(&mut self, us: i64);
}

/// `thread::sleep`: the fallback where no exact timer exists.
pub struct PlainTickSleep;

impl TickSleep for PlainTickSleep {
    fn sleep(&mut self, us: i64) {
        std::thread::sleep(Duration::from_micros(us.max(1) as u64));
    }
}

pub type TickSleepFactory = Arc<dyn Fn() -> Box<dyn TickSleep> + Send + Sync>;

/// What the watchdog asks the system once a second, off the tick. Each may be slow, and may be absent.
#[derive(Default)]
pub struct Probes {
    /// An elevated window (or the lock screen) is in front: SendInput can't reach it.
    pub elevated_foreground: Option<Box<dyn Fn() -> bool + Send + Sync>>,
    /// The network is marked Public in Windows (the firewall may drop the phone).
    pub network_public: Option<Box<dyn Fn() -> bool + Send + Sync>>,
    /// What this PC's keyboard layout prints on each key, for the phone's key names.
    pub key_labels: Option<Box<dyn Fn() -> Option<KeyLabels> + Send + Sync>>,
    /// Whether the PC is a Wi-Fi client toward the phone at that address (only then is KEEP_AWAKE needed); None
    /// when it can't tell.
    pub pc_wifi_client: Option<Box<dyn Fn(Option<IpAddr>) -> Option<bool> + Send + Sync>>,
    /// The PC setup bits for the phone at that address.
    pub pc_setup: Option<Box<dyn Fn(Option<IpAddr>) -> u8 + Send + Sync>>,
}

impl Probes {
    fn any(&self) -> bool {
        self.elevated_foreground.is_some() || self.network_public.is_some() || self.key_labels.is_some()
    }
}

#[derive(Default)]
pub struct WatchdogOptions {
    pub marker: Option<HeldKeysMarker>,
    pub store: Option<SettingsStore>,
    pub probes: Probes,
    pub log: Option<Log>,
    pub tick_sleep: Option<TickSleepFactory>,
}

struct WriteState {
    held: [u8; KEY_BITMAP_SIZE],
    /// Settings are not written before this time (µs on the engine's clock) after a failed write.
    save_retry_after_us: Option<i64>,
}

struct Inner {
    engine: Arc<HostEngine>,
    clock: Arc<dyn Clock>,
    marker: Option<HeldKeysMarker>,
    store: Option<SettingsStore>,
    probes: Probes,
    log: Log,
    tick_sleep: TickSleepFactory,
    stop: AtomicBool,
    stop_writes: Signal,
    probe_wake: Signal,
    running: AtomicBool,
    probing: AtomicBool,
    last_probe_us: Mutex<i64>,
    write: Mutex<WriteState>,
}

pub struct Watchdog {
    inner: Arc<Inner>,
    threads: Mutex<Vec<(std::thread::JoinHandle<()>, std::sync::mpsc::Receiver<()>, u64)>>,
}

impl Watchdog {
    pub fn new(engine: Arc<HostEngine>, clock: Arc<dyn Clock>, options: WatchdogOptions) -> Watchdog {
        Watchdog {
            inner: Arc::new(Inner {
                engine,
                clock,
                marker: options.marker,
                store: options.store,
                probes: options.probes,
                log: options.log.unwrap_or_else(|| Arc::new(|_| {})),
                tick_sleep: options.tick_sleep.unwrap_or_else(|| Arc::new(|| Box::new(PlainTickSleep))),
                stop: AtomicBool::new(false),
                stop_writes: Signal::new(false),
                probe_wake: Signal::new(false),
                running: AtomicBool::new(false),
                probing: AtomicBool::new(false),
                last_probe_us: Mutex::new(-PROBE_INTERVAL_US),
                write: Mutex::new(WriteState { held: [0; KEY_BITMAP_SIZE], save_retry_after_us: None }),
            }),
            threads: Mutex::new(Vec::new()),
        }
    }

    /// Starts the tick, the writer, the keep-awake timer and the probes' thread. A thread that can't start is
    /// logged; the tick is the one that must run, so false is returned without it.
    pub fn start(&self) -> bool {
        let mut threads = self.threads.lock().unwrap_or_else(|e| e.into_inner());
        if !threads.is_empty() {
            return true;
        }
        type Run = fn(&Inner);
        let all: [(&str, Priority, Run, u64); 4] = [
            ("gp-watchdog", Priority::AboveNormal, Inner::tick_loop, 1000),
            ("gp-save", Priority::BelowNormal, Inner::write_loop, 2000),
            ("gp-keepawake", Priority::AboveNormal, Inner::keep_awake_loop, 1000),
            ("gp-probe", Priority::BelowNormal, Inner::probe_loop, 1000),
        ];
        let mut ok = true;
        for (name, priority, run, join_ms) in all {
            let inner = self.inner.clone();
            let (done_tx, done) = std::sync::mpsc::channel();
            let spawned = std::thread::Builder::new().name(name.into()).spawn(move || {
                sys::set_thread_priority(priority);
                run(&inner);
                let _ = done_tx.send(());
            });
            match spawned {
                Ok(t) => threads.push((t, done, join_ms)),
                Err(_) => {
                    (self.inner.log)(&format!("{name} thread could not start"));
                    ok &= name != "gp-watchdog";
                }
            }
        }
        ok
    }

    /// One tick: only in-memory work and waking the probes. Overlapping calls (a slow tick meeting the next) are
    /// skipped.
    pub fn tick(&self) {
        self.inner.tick();
    }

    /// The writer's turn (gp-save, every `WRITE_INTERVAL_MS`): held.json when the held set changed, and settings
    /// when the engine changed them (all the changes since the last turn in one write).
    pub fn write_files(&self) {
        self.inner.write_files();
    }

    /// Writes settings if the engine changed them. Also called on exit.
    pub fn save_settings(&self) {
        let mut w = self.inner.write.lock().unwrap_or_else(|e| e.into_inner());
        self.inner.save_settings(&mut w);
    }

    /// One round of the probes, on the caller's thread (tests; the thread does this once a second).
    pub fn run_probes(&self) {
        self.inner.run_probes();
    }

    /// Stops the threads (waiting a moment for a write in progress); the caller then saves what is left.
    pub fn stop(&self) {
        self.inner.stop.store(true, Ordering::SeqCst);
        self.inner.stop_writes.set();
        self.inner.probe_wake.set();
        self.inner.engine.wake_timers();
        let threads = std::mem::take(&mut *self.threads.lock().unwrap_or_else(|e| e.into_inner()));
        let me = std::thread::current().id();
        for (t, done, join_ms) in threads {
            if t.thread().id() != me && done.recv_timeout(Duration::from_millis(join_ms)).is_ok() {
                let _ = t.join();
            }
        }
    }
}

impl Drop for Watchdog {
    fn drop(&mut self) {
        self.stop();
    }
}

impl Inner {
    fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }

    /// Ticks every 50 ms from a fixed start, so a late tick doesn't push the later ones back; one that is a whole
    /// tick late is skipped.
    fn tick_loop(&self) {
        let mut sleep = (self.tick_sleep)();
        let start = Instant::now(); // real time, whatever clock the engine is given
        let elapsed = || start.elapsed().as_micros() as i64;
        let period_us = wire::WATCHDOG_TICK_MS * 1000;
        let mut next = period_us;
        while !self.stopped() {
            let wait = next - elapsed();
            if wait > 0 {
                sleep.sleep(wait);
            }
            if self.stopped() {
                return;
            }
            self.tick();
            next += period_us;
            // Nothing needs the 50 ms tick (no phone connected): rest up to WATCHDOG_IDLE_TICK_MS, so an idle
            // laptop isn't woken 20 times a second; a session starting ends the rest at once.
            if self.engine.watchdog_idle() && !self.stopped() {
                self.engine.rest_watchdog();
                next = elapsed();
            }
            let now = elapsed();
            if next <= now {
                next = now + period_us;
            }
        }
    }

    /// KEEP_AWAKE every `KEEP_AWAKE_MS` (the engine decides whether to send), on its own exact timer. While none
    /// is wanted (no phone, or a wire) it rests on the engine instead of waking 100 times a second.
    fn keep_awake_loop(&self) {
        let mut sleep = (self.tick_sleep)();
        while !self.stopped() {
            sleep.sleep(wire::KEEP_AWAKE_MS * 1000);
            if self.stopped() {
                return;
            }
            if !self.engine.keep_awake() && !self.stopped() {
                self.engine.rest_keep_awake();
            }
        }
    }

    fn write_loop(&self) {
        while !self.stop_writes.wait(WRITE_INTERVAL_MS) {
            self.write_files();
        }
    }

    fn probe_loop(&self) {
        loop {
            self.probe_wake.wait(60_000);
            if self.stopped() {
                return;
            }
            self.probe_wake.reset();
            if self.probing.load(Ordering::SeqCst) {
                self.run_probes();
            }
        }
    }

    fn tick(&self) {
        if self.running.swap(true, Ordering::SeqCst) {
            return;
        }
        let now = self.clock.now_us();
        self.engine.on_tick(now);
        if self.probes.any() {
            let mut last = self.last_probe_us.lock().unwrap_or_else(|e| e.into_inner());
            if now.saturating_sub(*last) >= PROBE_INTERVAL_US && !self.probing.swap(true, Ordering::SeqCst) {
                *last = now;
                self.probe_wake.set();
            }
        }
        self.running.store(false, Ordering::SeqCst);
    }

    fn write_files(&self) {
        let mut w = self.write.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(marker) = &self.marker {
            let (mouse, version) = self.engine.held_snapshot(&mut w.held);
            marker.update(version, &w.held, mouse);
        }
        if w.save_retry_after_us.is_none_or(|t| self.clock.now_us() >= t) {
            self.save_settings(&mut w);
        }
    }

    fn save_settings(&self, w: &mut WriteState) {
        let Some(store) = &self.store else { return };
        let Some(json) = self.engine.take_dirty_settings() else { return };
        match store.write_atomic(&json) {
            Ok(()) => w.save_retry_after_us = None,
            Err(e) => {
                // Still to be written (a new pairing, say): try again shortly, since a scanner or backup tool may
                // hold the file for a moment.
                self.engine.mark_settings_dirty();
                if w.save_retry_after_us.is_none() {
                    (self.log)(&format!("settings save failed: {:?}; retrying", e.kind()));
                }
                w.save_retry_after_us = Some(self.clock.now_us().saturating_add(SAVE_RETRY_US));
            }
        }
    }

    fn run_probes(&self) {
        let p = &self.probes;
        let elevated = p.elevated_foreground.as_ref().is_some_and(|f| f());
        let public = p.network_public.as_ref().is_some_and(|f| f());
        self.engine.set_environment(elevated, public);
        if let Some(f) = &p.key_labels {
            self.engine.set_key_labels(f());
        }
        let phone = self.engine.phone_address();
        // For KEEP_AWAKE: only a PC that is a Wi-Fi client toward the phone needs it.
        self.engine.set_pc_adapter(phone, p.pc_wifi_client.as_ref().and_then(|f| f(phone)));
        if let Some(f) = &p.pc_setup {
            self.engine.set_pc_setup(f(phone));
        }
        self.probing.store(false, Ordering::SeqCst);
    }
}
