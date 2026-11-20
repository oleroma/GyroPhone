//! "Measure buttons" (how long a press takes from the phone seeing it to the game getting it) and "Press a key"
//! (what is held on this PC, sent back to the phone while its screen listens).

use crate::codec::{self, pc_press, PcPress, PressTime};
use crate::link_stats::latency_us;
use crate::ports::{KeyMouseListener, PcPad, PC_PAD_PLACES};
use crate::wire;

/// "Measure buttons": how long a press or release takes from the phone seeing its input (a touch, a controller
/// key) to this PC handing it to the game. The engine notes when each STATE with a new press or release is
/// injected (`on_injected`); the phone's PRESS_TIME names that STATE and when its input happened
/// (`on_press_time`). Either can come first. The clock offset is the phone's, from LINK_INFO, as for the link
/// latency. Keeps the last `WINDOW` presses; under the engine lock.
pub struct PressLatency {
    inj_state: [u32; Self::RING],
    inj_at: [i64; Self::RING],
    inj_next: usize,
    inj_count: usize,
    waiting: [PressTime; 8],
    waiting_since: [i64; 8],
    waiting_count: usize,
    total: [i64; Self::WINDOW],
    phone: [i64; Self::WINDOW],
    next: usize,
    count: usize,
    last_total_us: i64,
    last_phone_us: i64,
}

impl Default for PressLatency {
    fn default() -> Self {
        Self::new()
    }
}

impl PressLatency {
    pub const WINDOW: usize = 64;
    const RING: usize = 32;
    /// A PRESS_TIME or an injection unmatched for this long is dropped.
    const MATCH_US: i64 = 1_000_000;

    pub fn new() -> PressLatency {
        PressLatency {
            inj_state: [0; Self::RING],
            inj_at: [0; Self::RING],
            inj_next: 0,
            inj_count: 0,
            waiting: [PressTime { state_time_us: 0, event_time_us: 0 }; 8],
            waiting_since: [0; 8],
            waiting_count: 0,
            total: [0; Self::WINDOW],
            phone: [0; Self::WINDOW],
            next: 0,
            count: 0,
            last_total_us: -1,
            last_phone_us: -1,
        }
    }

    /// Presses measured in the window (at most `WINDOW`).
    pub fn count(&self) -> usize {
        self.count
    }

    /// The newest press, total and phone part, in µs; -1 before the first.
    pub fn last_total_us(&self) -> i64 {
        self.last_total_us
    }

    pub fn last_phone_us(&self) -> i64 {
        self.last_phone_us
    }

    pub fn reset(&mut self) {
        self.inj_next = 0;
        self.inj_count = 0;
        self.waiting_count = 0;
        self.next = 0;
        self.count = 0;
        self.last_total_us = -1;
        self.last_phone_us = -1;
    }

    /// A STATE (phone time `state_time_us`) went to the game at `inject_us`. A burst injects only its newest
    /// STATE, so it also delivers every older one.
    pub fn on_injected(&mut self, state_time_us: u32, inject_us: i64, clock_offset_us: i64, have_offset: bool) {
        self.inj_state[self.inj_next] = state_time_us;
        self.inj_at[self.inj_next] = inject_us;
        self.inj_next = (self.inj_next + 1) % Self::RING;
        if self.inj_count < Self::RING {
            self.inj_count += 1;
        }
        let mut i = self.waiting_count;
        while i > 0 {
            i -= 1;
            if !Self::covers(state_time_us, self.waiting[i].state_time_us) {
                continue;
            }
            let p = self.waiting[i];
            self.remove_waiting(i);
            if have_offset {
                self.add(p, inject_us, clock_offset_us);
            }
        }
    }

    /// The phone's PRESS_TIME, received at `now_us`.
    pub fn on_press_time(&mut self, p: PressTime, now_us: i64, clock_offset_us: i64, have_offset: bool) {
        // The first injection that carried its STATE: that one, or the oldest newer one.
        let mut best: Option<usize> = None;
        let mut best_diff = u32::MAX;
        for k in 0..self.inj_count {
            let i = (self.inj_next + Self::RING - 1 - k) % Self::RING;
            if !Self::covers(self.inj_state[i], p.state_time_us) {
                continue;
            }
            let d = self.inj_state[i].wrapping_sub(p.state_time_us);
            if d < best_diff {
                best = Some(i);
                best_diff = d;
            }
        }
        if let Some(best) = best {
            if now_us.wrapping_sub(self.inj_at[best]) <= Self::MATCH_US && have_offset {
                self.add(p, self.inj_at[best], clock_offset_us);
            }
            return;
        }
        // Its STATE isn't injected yet (it waits for the next flush): keep it until it is.
        let mut i = self.waiting_count;
        while i > 0 {
            i -= 1;
            if now_us.wrapping_sub(self.waiting_since[i]) > Self::MATCH_US {
                self.remove_waiting(i);
            }
        }
        if self.waiting_count == self.waiting.len() {
            self.remove_waiting(0);
        }
        self.waiting[self.waiting_count] = p;
        self.waiting_since[self.waiting_count] = now_us;
        self.waiting_count += 1;
    }

    /// An injection of the STATE at phone time `injected` delivers the one at `state`: the same or up to a second
    /// newer.
    fn covers(injected: u32, state: u32) -> bool {
        injected.wrapping_sub(state) as i64 <= Self::MATCH_US
    }

    fn remove_waiting(&mut self, i: usize) {
        for j in i..self.waiting_count - 1 {
            self.waiting[j] = self.waiting[j + 1];
            self.waiting_since[j] = self.waiting_since[j + 1];
        }
        self.waiting_count -= 1;
    }

    fn add(&mut self, p: PressTime, inject_us: i64, clock_offset_us: i64) {
        let total = latency_us(p.event_time_us, inject_us, clock_offset_us);
        let phone = p.state_time_us.wrapping_sub(p.event_time_us) as i32 as i64;
        // A clock step or a stale input time: not a press anyone made.
        if total < 0 || total > Self::MATCH_US || phone < 0 || phone > total + 2_000 {
            return;
        }
        self.total[self.next] = total;
        self.phone[self.next] = phone;
        self.next = (self.next + 1) % Self::WINDOW;
        if self.count < Self::WINDOW {
            self.count += 1;
        }
        self.last_total_us = total;
        self.last_phone_us = phone;
    }

    /// The middle press of the window: total and phone part in µs, or None before any.
    pub fn median(&self) -> Option<(i64, i64)> {
        if self.count == 0 {
            return None;
        }
        Some((self.middle(&self.total), self.middle(&self.phone)))
    }

    fn middle(&self, values: &[i64; Self::WINDOW]) -> i64 {
        let mut a = *values;
        let a = &mut a[..self.count];
        a.sort_unstable();
        a[a.len() / 2]
    }
}

/// "Press a key" on the phone: while its screen is open (CAPTURE on, resent every second), what is held on this
/// PC's keyboard, mouse and controller goes back as CAPTURED, on change and every `RESEND_US`. Only read: nothing
/// is held back from Windows or the game. A press stays in the list for `HOLD_OVER_US` after it was last seen, so
/// a quick tap between two ticks, or one lost CAPTURED, still reaches the phone.
pub struct PressCapture {
    seen: Vec<(PcPress, i64)>,
    read: [PcPress; 64],
    now: [PcPress; wire::CAPTURED_MAX],
    sent: [PcPress; wire::CAPTURED_MAX],
    sent_count: Option<usize>,
    sent_us: Option<i64>,
    until_us: Option<i64>,
    presses: [u32; PC_PAD_PLACES],
    last_presses: [u32; PC_PAD_PLACES],
    presses_known: bool,
    axes: [f32; 6],
}

impl Default for PressCapture {
    fn default() -> Self {
        Self::new()
    }
}

impl PressCapture {
    pub const HOLD_OVER_US: i64 = 150_000;
    pub const RESEND_US: i64 = 200_000;
    const AXIS_PRESSED: f32 = 0.5;
    /// The most presses remembered at once: the listener's 64 plus every controller button and axis.
    const SEEN_MAX: usize = 64 + PC_PAD_PLACES + 6;

    // Pad places (`PcPad::read`) to XInput button bits; the triggers (places 6, 7) go as axes, capture (11) has
    // none.
    const PLACE_XINPUT: [u16; PC_PAD_PLACES] = [
        0x1000, 0x2000, 0x4000, 0x8000, 0x0100, 0x0200, 0, 0, 0x0020, 0x0010, 0x0400, 0, 0x0040, 0x0080, 0x0001,
        0x0002, 0x0004, 0x0008,
    ];

    pub fn new() -> PressCapture {
        PressCapture {
            // Never grows on the input side's threads after this.
            seen: Vec::with_capacity(Self::SEEN_MAX),
            read: [PcPress::default(); 64],
            now: [PcPress::default(); wire::CAPTURED_MAX],
            sent: [PcPress::default(); wire::CAPTURED_MAX],
            sent_count: None,
            sent_us: None,
            until_us: None,
            presses: [0; PC_PAD_PLACES],
            last_presses: [0; PC_PAD_PLACES],
            presses_known: false,
            axes: [0.0; 6],
        }
    }

    /// The phone listens.
    pub fn on(&self) -> bool {
        self.until_us.is_some()
    }

    /// CAPTURE arrived with `flags`; true when listening started or stopped.
    pub fn on_capture(&mut self, flags: u8, now_us: i64) -> bool {
        let was = self.on();
        if flags & wire::capture_flags::ON != 0 {
            self.until_us = Some(now_us.saturating_add(wire::CAPTURE_TIMEOUT_MS * 1000));
        } else {
            self.stop();
        }
        was != self.on()
    }

    /// No CAPTURE for the timeout: the phone closed its screen or went away.
    pub fn expired(&self, now_us: i64) -> bool {
        matches!(self.until_us, Some(until) if now_us >= until)
    }

    pub fn stop(&mut self) {
        self.until_us = None;
        self.seen.clear();
        self.sent_count = None;
        self.sent_us = None;
        self.presses_known = false;
    }

    /// Reads `keys` and `pad`; writes a CAPTURED plaintext into `b` and returns its length when it is due (changed,
    /// or `RESEND_US` since the last), else 0.
    pub fn tick(&mut self, keys: &dyn KeyMouseListener, pad: &dyn PcPad, now_us: i64, b: &mut [u8]) -> usize {
        if !self.on() {
            return 0;
        }
        let n = keys.read(&mut self.read).min(self.read.len());
        for i in 0..n {
            self.saw(self.read[i], now_us);
        }
        if let Some(held) = pad.read(&mut self.presses) {
            for p in 0..PC_PAD_PLACES {
                if Self::PLACE_XINPUT[p] == 0 {
                    continue;
                }
                let tapped = self.presses_known && self.presses[p] != self.last_presses[p];
                if held & (1u32 << p) != 0 || tapped {
                    self.saw(PcPress { kind: pc_press::PAD_BUTTON, code: Self::PLACE_XINPUT[p], arg: 0 }, now_us);
                }
            }
            self.last_presses = self.presses;
            self.presses_known = true;
            if pad.read_axes(&mut self.axes) {
                for a in 0..6 {
                    let v = self.axes[a];
                    if v.abs() > Self::AXIS_PRESSED {
                        let way = if v > 0.0 { 1 } else { -1 };
                        self.saw(PcPress { kind: pc_press::PAD_AXIS, code: a as u16, arg: way }, now_us);
                    }
                }
            }
        } else {
            self.presses_known = false;
        }

        self.seen.retain(|s| now_us.saturating_sub(s.1) <= Self::HOLD_OVER_US);
        let count = self.seen.len().min(wire::CAPTURED_MAX);
        for i in 0..count {
            self.now[i] = self.seen[i].0;
        }
        let changed = self.sent_count != Some(count) || self.now[..count] != self.sent[..count];
        if !changed && matches!(self.sent_us, Some(sent) if now_us.saturating_sub(sent) < Self::RESEND_US) {
            return 0;
        }
        self.sent[..count].copy_from_slice(&self.now[..count]);
        self.sent_count = Some(count);
        self.sent_us = Some(now_us);
        codec::encode_captured(&self.now[..count], b)
    }

    fn saw(&mut self, p: PcPress, now_us: i64) {
        for s in self.seen.iter_mut() {
            if s.0 == p {
                s.1 = now_us;
                return;
            }
        }
        // A listener that names more different presses than there are keys is wrong: the list never grows past
        // what it was made for.
        if self.seen.len() < Self::SEEN_MAX {
            self.seen.push((p, now_us));
        }
    }
}
