//! "Use PC controller for gyro ratchet": reads the first XInput controller that is not our own virtual pad, every
//! millisecond on its own thread, and counts each button's presses so a short one between two STATEs is not
//! lost. Free slots are looked at only every few seconds (a query on an empty slot is slow), and not while our
//! own pad is plugged but has no slot yet (it could be any). Reading never takes the controller away from games
//! or Steam Input. Each change of the held buttons is noted with its time on the engine's clock, so the PC gyro
//! ratchets each sample by what was held when it was measured. A port of XInputPcPad.cs.

use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
use windows_sys::Win32::System::Threading as th;

use super::basics::HighResolutionSleep;
use super::wide;
use crate::gyro::PadHeldHistory;
use crate::ports::{Clock, PcPad, PC_PAD_PLACES, SLOT_UNKNOWN};
use crate::watchdog::TickSleep;
use crate::udp::Log;

const PAD_L2: u32 = 1 << 6;
const PAD_R2: u32 = 1 << 7;
/// A trigger pulled this far counts as pressed (the phone ratchets at half a pull too).
const TRIGGER_PRESSED: u8 = 128;
const RESCAN_MS: i64 = 1000;
/// How long after a scan that found nothing the empty slots are queried again.
const EMPTY_RESCAN_MS: i64 = 3000;
const POLL_US: i64 = 1000;
const IDLE_US: i64 = 100_000;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Gamepad {
    buttons: u16,
    left_trigger: u8,
    right_trigger: u8,
    lx: i16,
    ly: i16,
    rx: i16,
    ry: i16,
}

/// What ordinal 100 writes: XINPUT_STATE with 4 more bytes at the end of the gamepad, 20 bytes in all. The plain
/// XInputGetState writes the first 16 of them.
#[repr(C)]
#[derive(Clone, Copy, Default)]
struct StateEx {
    packet: u32,
    gamepad: Gamepad,
    reserved: u32,
}

type GetState = unsafe extern "system" fn(u32, *mut StateEx) -> u32;

/// xinput1_4.dll's state call: the undocumented ordinal 100 (like XInputGetState, with the Guide button) when it
/// is there, else XInputGetState. None when the library isn't.
fn get_state() -> Option<GetState> {
    let name = wide("xinput1_4.dll");
    // SAFETY: a NUL-ended name; the library stays loaded for the life of the process.
    let lib = unsafe { LoadLibraryW(name.as_ptr()) };
    if lib.is_null() {
        return None;
    }
    // SAFETY: a live module; an ordinal goes in the pointer's low word, a name is NUL-ended.
    let f = unsafe { GetProcAddress(lib, 100 as *const u8).or_else(|| GetProcAddress(lib, c"XInputGetState".as_ptr().cast())) }?;
    // SAFETY: both take (DWORD, XINPUT_STATE*) and return DWORD; the buffer given is the larger of the two.
    Some(unsafe { std::mem::transmute::<unsafe extern "system" fn() -> isize, GetState>(f) })
}

fn try_get(f: GetState, slot: i32) -> Option<Gamepad> {
    let mut s = StateEx::default();
    // SAFETY: a 20-byte state, as the larger of the two calls writes.
    (unsafe { f(slot as u32, &mut s) } == 0).then_some(s.gamepad)
}

fn stick(v: i16) -> f32 {
    (v as f32 / 32767.0).max(-1.0)
}

fn place_bits(g: &Gamepad) -> u32 {
    let b = g.buttons as u32;
    let mut p = 0u32;
    let mut map = |mask: u32, place: u32| {
        if b & mask != 0 {
            p |= 1 << place;
        }
    };
    map(0x1000, 0); // A: south
    map(0x2000, 1); // B: east
    map(0x4000, 2); // X: west
    map(0x8000, 3); // Y: north
    map(0x0100, 4); // LB
    map(0x0200, 5); // RB
    map(0x0020, 8); // Back: minus
    map(0x0010, 9); // Start: plus
    map(0x0400, 10); // Guide: home (ordinal 100 only)
    map(0x0040, 12); // left stick press
    map(0x0080, 13); // right stick press
    map(0x0001, 14); // D-pad up
    map(0x0002, 15);
    map(0x0004, 16);
    map(0x0008, 17);
    if g.left_trigger >= TRIGGER_PRESSED {
        p |= PAD_L2;
    }
    if g.right_trigger >= TRIGGER_PRESSED {
        p |= PAD_R2;
    }
    p
}

#[derive(Default)]
struct State {
    history: PadHeldHistory,
    presses: [u32; PC_PAD_PLACES],
    held: u32,
    axes: [f32; 6],
    /// The running reader's stop flag: each reader has its own, so a new one never revives an old one.
    run: Option<Arc<AtomicBool>>,
    own_slot: Option<Arc<dyn Fn() -> i32 + Send + Sync>>,
}

/// A copy of the held buttons, sticks and triggers that `peek` reads without the lock: written under the lock
/// after each change (odd `seq` while it is written), read lock-free.
#[derive(Default)]
struct Published {
    seq: AtomicU32,
    held: AtomicU32,
    axes: [AtomicU32; 6],
    on: AtomicBool,
}

struct Shared {
    log: Log,
    clock: Arc<dyn Clock>,
    state: Mutex<State>,
    slot: AtomicI32,
    published: Published,
}

impl Shared {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Copies the state for `peek`; under the lock.
    fn publish(&self, s: &State) {
        let p = &self.published;
        p.seq.fetch_add(1, Ordering::AcqRel);
        p.held.store(s.held, Ordering::Release);
        for (a, v) in p.axes.iter().zip(s.axes) {
            a.store(v.to_bits(), Ordering::Release);
        }
        p.on.store(self.slot.load(Ordering::Acquire) >= 0, Ordering::Release);
        p.seq.fetch_add(1, Ordering::AcqRel);
    }

    fn own_slot(&self) -> i32 {
        let f = self.lock().own_slot.clone();
        f.map_or(-1, |f| f())
    }

    /// The first connected slot that is not ours, or -1; true while our own pad's slot is unknown.
    fn scan(&self, get: GetState) -> (i32, bool) {
        let own = self.own_slot();
        if own == SLOT_UNKNOWN {
            return (-1, true);
        }
        ((0..4).find(|i| *i != own && try_get(get, *i).is_some()).unwrap_or(-1), false)
    }

    fn store(&self, run: &AtomicBool, g: &Gamepad) {
        let held = place_bits(g);
        let now = self.clock.now_us();
        let mut s = self.lock();
        if run.load(Ordering::Acquire) {
            return;
        }
        let down = held & !s.held;
        for i in 0..PC_PAD_PLACES {
            if down & (1 << i) != 0 {
                s.presses[i] = s.presses[i].wrapping_add(1);
            }
        }
        s.held = held;
        s.history.add(now, held);
        // XInput sticks are y up; the overlay's are y down.
        s.axes = [stick(g.lx), -stick(g.ly), stick(g.rx), -stick(g.ry), g.left_trigger as f32 / 255.0, g.right_trigger as f32 / 255.0];
        self.publish(&s);
    }

    fn run(&self, stopped: &AtomicBool) {
        let Some(get) = get_state() else {
            (self.log)("PC controller for gyro ratchet stopped: xinput1_4.dll is missing");
            return;
        };
        // SAFETY: the calling thread's own pseudo-handle.
        unsafe {
            th::SetThreadPriority(th::GetCurrentThread(), th::THREAD_PRIORITY_ABOVE_NORMAL);
        }
        let mut sleep = HighResolutionSleep::new();
        let mut last_scan = -RESCAN_MS;
        let mut next_scan = 0i64;
        let start = self.clock.now_us();
        let mut slot = -1i32;
        while !stopped.load(Ordering::Acquire) {
            let now = (self.clock.now_us() - start) / 1000;
            // Our own pad may have taken the slot read (it was plugged after the scan): never read it.
            if slot >= 0 && now - last_scan >= RESCAN_MS {
                last_scan = now;
                if self.own_slot() == slot {
                    (self.log)(&format!("PC controller for gyro ratchet: controller {} is our own pad, looking again", slot + 1));
                    slot = -1;
                    next_scan = 0;
                }
            }
            if slot < 0 && now >= next_scan {
                last_scan = now;
                let (found, own_unknown) = self.scan(get);
                slot = found;
                next_scan = now + if slot < 0 && !own_unknown { EMPTY_RESCAN_MS } else { RESCAN_MS };
                {
                    let s = self.lock();
                    if stopped.load(Ordering::Acquire) {
                        return;
                    }
                    self.slot.store(slot, Ordering::Release);
                    self.publish(&s);
                }
                if slot >= 0 {
                    (self.log)(&format!("PC controller for gyro ratchet: reading XInput controller {}", slot + 1));
                }
            }
            if slot >= 0 {
                match try_get(get, slot) {
                    Some(g) => self.store(stopped, &g),
                    None => {
                        (self.log)(&format!("PC controller for gyro ratchet: controller {} went away", slot + 1));
                        slot = -1;
                        let mut s = self.lock();
                        if stopped.load(Ordering::Acquire) {
                            return;
                        }
                        s.held = 0;
                        s.history.add(self.clock.now_us(), 0);
                        s.axes = [0.0; 6];
                        self.slot.store(-1, Ordering::Release);
                        self.publish(&s);
                    }
                }
            }
            sleep.sleep(if slot >= 0 { POLL_US } else { IDLE_US });
        }
    }
}

/// The XInput reader, as `host::Platform` takes it. `clock` is the engine's: held times are on it.
pub struct XInputPcPad(Arc<Shared>);

impl XInputPcPad {
    pub fn new(log: Log, clock: Arc<dyn Clock>) -> XInputPcPad {
        XInputPcPad(Arc::new(Shared { log, clock, state: Mutex::new(State::default()), slot: AtomicI32::new(-1), published: Published::default() }))
    }
}

impl PcPad for XInputPcPad {
    fn start(&self, own_slot: Arc<dyn Fn() -> i32 + Send + Sync>) {
        let mut s = self.0.lock();
        s.own_slot = Some(own_slot);
        if s.run.is_some() {
            return;
        }
        let stopped = Arc::new(AtomicBool::new(false));
        let (shared, flag) = (self.0.clone(), stopped.clone());
        if std::thread::Builder::new().name("gp-pc-pad".to_string()).spawn(move || shared.run(&flag)).is_ok() {
            s.run = Some(stopped);
        }
    }

    /// Stops the reader without waiting for its thread (the engine calls this under its lock, and the thread may
    /// be in a 100 ms wait or a slow scan): it ends on its own, and writes nothing once stopped.
    fn stop(&self) {
        let mut s = self.0.lock();
        if let Some(run) = s.run.take() {
            run.store(true, Ordering::Release);
        }
        s.held = 0;
        s.history = PadHeldHistory::default();
        s.axes = [0.0; 6];
        self.0.slot.store(-1, Ordering::Release);
        self.0.publish(&s);
    }

    fn read(&self, presses: &mut [u32; PC_PAD_PLACES]) -> Option<u32> {
        let s = self.0.lock();
        *presses = s.presses;
        (self.0.slot.load(Ordering::Acquire) >= 0).then_some(s.held)
    }

    fn read_axes(&self, axes: &mut [f32; 6]) -> bool {
        let s = self.0.lock();
        *axes = s.axes;
        self.0.slot.load(Ordering::Acquire) >= 0
    }

    fn peek(&self, axes: &mut [f32; 6]) -> Option<u32> {
        let p = &self.0.published;
        loop {
            let before = p.seq.load(Ordering::Acquire);
            let held = p.held.load(Ordering::Acquire);
            for (a, v) in axes.iter_mut().zip(&p.axes) {
                *a = f32::from_bits(v.load(Ordering::Acquire));
            }
            let on = p.on.load(Ordering::Acquire);
            if before & 1 == 0 && p.seq.load(Ordering::Acquire) == before {
                return on.then_some(held);
            }
            std::hint::spin_loop();
        }
    }

    fn held_at(&self, us: i64) -> u32 {
        self.0.lock().history.at(us)
    }

    fn name(&self) -> Option<String> {
        let s = self.0.slot.load(Ordering::Acquire);
        (s >= 0).then(|| format!("Controller {}", s + 1))
    }
}

impl Drop for XInputPcPad {
    fn drop(&mut self) {
        self.stop();
    }
}
