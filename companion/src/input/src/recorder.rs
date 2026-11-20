//! The input log: how the phone's input reached Windows, for finding stutter in real play
//! (docs/spec/pc-companion.md, "Input log"). Port of InputRecorder.cs; the lines read the same.
//!
//! Called by the engine under its lock; the events and seconds only queue there, as numbers, and the engine hands
//! them on (`take`) once it let go of the lock, never on the rx thread: their text is made then (`Event::text`), off
//! the input path. Per second it makes one summary line while a session is up, and at once
//! one line per event: lost packets, packets held up on the way, the phone going quiet, a catch-up, a big single
//! move, a slow hand-off to Windows, Windows refusing input, a release after silence. It never says which keys or
//! buttons, only how many.
//!
//! "Late" is measured against the session's fastest packets (the lowest receive time minus phone send time over
//! the last 5-10 s), so it needs no clock sync and is the delay a packet added on top of the best the link did
//! just before.

use crate::link_stats::LinkStats;
use crate::stall_watch::{self, StallWatch};
use crate::wire;
use std::fmt::Write;
use std::sync::Arc;

/// `us / 1000.0` as .NET's "0.0" writes it: tenths of a millisecond, halves away from zero.
pub fn ms1(us: i64) -> String {
    let tenths = us.unsigned_abs().saturating_add(50) / 100;
    format!("{}{}.{}", if us < 0 { "-" } else { "" }, tenths / 10, tenths % 10)
}

/// What held up one hand-off to Windows, for the input log's `slow hand-off` line: gp-rx's waits for the engine
/// lock (and who held it at the longest), and the time inside the pad driver and inside SendInput. All in µs.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HandOffCauses {
    pub lock_wait_us: i64,
    /// Who had the lock at the longest wait ("ui", "watchdog", ...); None without a wait.
    pub lock_holder: Option<&'static str>,
    longest_wait_us: i64,
    /// Inside the pad driver (ViGEm or the DualSense driver).
    pub pad_us: i64,
    /// Inside SendInput (or the input driver).
    pub send_input_us: i64,
    /// Where keys and mouse went: "SendInput" or "input driver".
    pub route: Option<&'static str>,
}

impl HandOffCauses {
    /// Nothing measured.
    pub const NONE: HandOffCauses = HandOffCauses {
        lock_wait_us: 0,
        lock_holder: None,
        longest_wait_us: 0,
        pad_us: 0,
        send_input_us: 0,
        route: None,
    };

    pub fn waited(us: i64, holder: Option<&'static str>) -> HandOffCauses {
        let mut c = HandOffCauses::default();
        c.add_lock_wait(us, holder);
        c
    }

    pub fn add_lock_wait(&mut self, us: i64, holder: Option<&'static str>) {
        if us <= 0 {
            return;
        }
        self.lock_wait_us = self.lock_wait_us.saturating_add(us);
        if us <= self.longest_wait_us {
            return;
        }
        self.longest_wait_us = us;
        self.lock_holder = holder;
    }
}

/// One second of the phone link, from the input log's second line.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct LinkSecond {
    /// When the second ended, on the engine's clock.
    pub at_us: i64,
    pub rx: i32,
    pub lost: i32,
    /// Packets that came more than `LATE_US` later than the fastest ones.
    pub late: i32,
    pub max_late_ms: f32,
    /// Times the phone sent nothing for longer than `PHONE_GAP_US` while input was going on.
    pub gaps: i32,
    /// Times held keys and buttons were let go because the phone went silent.
    pub releases: i32,
    /// The middle and the slowest 1% of the phone-to-PC latency, ms (-1 unknown).
    pub lat_p50_ms: f32,
    pub lat_p99_ms: f32,
    pub jitter_ms: f32,
    /// Half the best round trip, ms: the fastest the link went (-1 unknown).
    pub floor_ms: f32,
    /// The phone's typical (middle) round trip, ms (-1 unknown).
    pub rtt_typ_ms: f32,
    /// How much slower than the fastest packets this second's were: middle and slowest 1%, ms (-1 none). Needs no
    /// clock sync.
    pub over_p50_ms: f32,
    pub over_p99_ms: f32,
    /// Packets 3 to 15 ms slower than the fastest (below a hold-up, still felt in a flick).
    pub late3: i32,
    /// The slowest hand-off to Windows, ms; hand-offs over 2 ms; gp-rx's longest wait for the engine lock, ms.
    pub hand_off_max_ms: f32,
    pub slow_hand_offs: i32,
    pub lock_wait_ms: f32,
    /// The PC stall watch: its latest wake, ms (-1 not running), and wakes 2 ms or more late.
    pub stall_max_ms: f32,
    pub stalls: i32,
    /// Latencies that came out below zero (a wrong clock offset).
    pub neg_lat: i32,
    /// Late packets by where they waited: on the way (Wi-Fi, router, cable), inside the PC, or the phone paused.
    pub air: i32,
    pub pc: i32,
    pub phone: i32,
    /// Packets that moved the mouse or a stick.
    pub moving: i32,
}

/// What held up a hold-up: where its packets waited.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    /// On the way (Wi-Fi, router, cable): sent on time, the PC ran on time.
    Air,
    /// Inside the PC: the stall watch was late then.
    Pc,
    /// The phone paused.
    Phone,
}

/// One input-log event, kept as numbers until its text is made.
#[derive(Clone, Debug, PartialEq)]
pub enum Ev {
    Text { what: String, detail: String },
    Lost { count: i64, seq: u32 },
    PhoneGap { span_us: i64, while_active: bool },
    /// `stall_us`: the stall watch's worst wake then (-1: it wasn't running).
    Late { count: i32, max_us: i64, over_us: i64, stall_us: i64, verdict: Verdict },
    BigMove { dx: i32, dy: i32 },
    SlowHandOff { took_us: i64, causes: HandOffCauses },
    Refused,
    CatchUp { px: i32, backlog_us: i64, spread_us: i64 },
    Release { held: u32 },
}

/// An event and when it happened, µs into the session.
#[derive(Clone, Debug, PartialEq)]
pub struct Event {
    pub at_us: i64,
    pub ev: Ev,
}

impl Event {
    /// The line: seconds into the session (10 wide, 3 decimals), what (13 wide), detail.
    pub fn text(&self) -> String {
        let (what, detail): (&str, String) = match &self.ev {
            Ev::Text { what, detail } => (what, detail.clone()),
            Ev::Lost { count, seq } => ("lost", format!("{count} packets before seq {seq}")),
            Ev::PhoneGap { span_us, while_active } => (
                "phone gap",
                format!(
                    "phone sent nothing for {} ms{}",
                    ms1(*span_us),
                    if *while_active { " while input was moving or held" } else { "" }
                ),
            ),
            Ev::Late { count, max_us, over_us, stall_us, verdict } => {
                let tail = match verdict {
                    Verdict::Phone => " (the phone also paused: phone)".to_string(),
                    Verdict::Pc => format!(" (sent on time, the PC stalled {} ms then: PC)", ms1(*stall_us)),
                    Verdict::Air if *stall_us < 0 => " (sent on time: held up on the way)".to_string(),
                    Verdict::Air => " (sent on time, the PC ran on time: air)".to_string(),
                };
                ("late", format!("{count} packets up to {} ms late over {} ms{tail}", ms1(*max_us), ms1(*over_us)))
            }
            Ev::BigMove { dx, dy } => ("big move", format!("{dx},{dy} px in one step")),
            Ev::SlowHandOff { took_us, causes } => ("slow hand-off", InputRecorder::slow_text(*took_us, causes)),
            Ev::Refused => (
                "refused",
                "Windows took fewer events than given (an admin window or the lock screen in front)".to_string(),
            ),
            Ev::CatchUp { px, backlog_us, spread_us } => (
                "catch-up",
                format!(
                    "{px} px from {} ms of late or lost motion spread over {} ms",
                    ms1(*backlog_us),
                    ms1(*spread_us)
                ),
            ),
            Ev::Release { held } => {
                ("release", format!("phone silent {} ms: let go of {held} held keys or buttons", wire::RELEASE_TIMEOUT_MS))
            }
        };
        let us = self.at_us;
        let ms = (us.unsigned_abs() + 500) / 1000;
        let secs = format!("{}{}.{:03}", if us < 0 { "-" } else { "" }, ms / 1000, ms % 1000);
        format!("{secs:>10} {what:<13} {detail}")
    }
}

/// What the recorder hands on once the engine lock is free.
#[derive(Clone, Debug, PartialEq)]
pub enum RecorderOut {
    Line(Event),
    Second(LinkSecond),
}

pub struct InputRecorder {
    out: Vec<RecorderOut>,
    /// Lines are made; off, the seconds are still counted for the Performance page, but no text is made.
    pub write_lines: bool,
    /// More for each second's line (the delivery probe's numbers), or None; called under the engine lock, every
    /// second either way.
    pub second_note: Option<Box<dyn FnMut() -> Option<String> + Send>>,
    /// The PC stall watch while Diagnostics runs (None otherwise): tells a hold-up that waited inside the PC from one
    /// held up on the way.
    pub stall: Option<Arc<StallWatch>>,

    // Session.
    active: bool,
    session_start_us: i64,
    // Baseline (relative receive - send, us) over the current and previous window.
    have_ref: bool,
    ref_diff: u32,
    win_start_us: i64,
    min_cur: i64,
    min_prev: i64,
    // The STATE before.
    have_prev: bool,
    prev_phone_us: u32,
    prev_active: bool,
    // Hold-up in progress.
    in_holdup: bool,
    hold_start_us: i64,
    hold_last_us: i64,
    hold_max_us: i64,
    hold_count: i32,
    hold_phone_span_max_us: i64,
    // This second.
    s_max_lock_wait_us: i64,
    s_lock_holder: Option<&'static str>,
    s_states: i32,
    s_moving: i32,
    s_lost: i32,
    s_late: i32,
    s_late3: i32,
    s_air: i32,
    s_pc: i32,
    s_phone: i32,
    s_pad_hand_offs: i32,
    s_gaps: i32,
    s_releases: i32,
    s_injects: i32,
    s_max_step: i32,
    s_slow: i32,
    s_catch_ups: i32,
    s_jumps: i32,
    s_events: i32,
    s_skipped: i32,
    s_px: i64,
    s_max_late_us: i64,
    s_max_inject_us: i64,
    // This second's extra delay over the floor (0.1 ms buckets), for "over p50/p99".
    s_extra: [i32; SECOND_BUCKETS],
    s_extra_n: i32,
    // Whole session.
    t_states: i64,
    t_lost: i64,
    t_late: i64,
    t_late3: i64,
    t_air: i64,
    t_pc: i64,
    t_phone: i64,
    t_holdups: i64,
    t_gaps: i64,
    t_catch_ups: i64,
    t_jumps: i64,
    t_slow: i64,
    t_releases: i64,
    t_refused: i64,
    t_extra: Box<[i64; SESSION_BUCKETS]>, // 0.1 ms buckets of extra delay, last = 100+ ms
}

const SECOND_BUCKETS: usize = 301;
const SESSION_BUCKETS: usize = 1001;

impl Default for InputRecorder {
    fn default() -> Self {
        Self::new()
    }
}

impl InputRecorder {
    /// A packet this much later than the fastest ones starts a hold-up.
    pub const LATE_US: i64 = 15_000;
    /// The phone sends at least every 20 ms (keepalive): a longer spacing with no packet lost is the phone not
    /// sending.
    pub const PHONE_GAP_US: i64 = 30_000;
    /// A single move at least this big is logged.
    pub const JUMP_PX: i32 = 64;
    /// Receive to SendInput slower than this is logged.
    pub const SLOW_INJECT_US: i64 = 2_000;
    /// A packet this much later than the fastest ones (and up to `LATE_US`) counts as "late3": felt in a flick,
    /// below a hold-up.
    pub const LATE3_US: i64 = 3_000;
    /// Extra delay buckets: 0.1 ms steps, per second up to 30 ms and per session up to 100 ms (the last bucket holds
    /// the rest).
    pub const EXTRA_STEP_US: i64 = 100;
    /// At most this many event lines a second; the rest are only counted in the second's line.
    pub const MAX_EVENTS_PER_SECOND: i32 = 10;
    const BASE_WINDOW_US: i64 = 5_000_000;
    /// What waits for `take` at most: with nobody taking, the oldest stay and new ones are dropped.
    const MAX_QUEUED: usize = 4096;

    pub fn new() -> InputRecorder {
        InputRecorder {
            out: Vec::with_capacity(64),
            write_lines: true,
            second_note: None,
            stall: None,
            active: false,
            session_start_us: 0,
            have_ref: false,
            ref_diff: 0,
            win_start_us: 0,
            min_cur: i64::MAX,
            min_prev: i64::MAX,
            have_prev: false,
            prev_phone_us: 0,
            prev_active: false,
            in_holdup: false,
            hold_start_us: 0,
            hold_last_us: 0,
            hold_max_us: 0,
            hold_count: 0,
            hold_phone_span_max_us: 0,
            s_max_lock_wait_us: 0,
            s_lock_holder: None,
            s_states: 0,
            s_moving: 0,
            s_lost: 0,
            s_late: 0,
            s_late3: 0,
            s_air: 0,
            s_pc: 0,
            s_phone: 0,
            s_pad_hand_offs: 0,
            s_gaps: 0,
            s_releases: 0,
            s_injects: 0,
            s_max_step: 0,
            s_slow: 0,
            s_catch_ups: 0,
            s_jumps: 0,
            s_events: 0,
            s_skipped: 0,
            s_px: 0,
            s_max_late_us: 0,
            s_max_inject_us: 0,
            s_extra: [0; SECOND_BUCKETS],
            s_extra_n: 0,
            t_states: 0,
            t_lost: 0,
            t_late: 0,
            t_late3: 0,
            t_air: 0,
            t_pc: 0,
            t_phone: 0,
            t_holdups: 0,
            t_gaps: 0,
            t_catch_ups: 0,
            t_jumps: 0,
            t_slow: 0,
            t_releases: 0,
            t_refused: 0,
            t_extra: Box::new([0; SESSION_BUCKETS]),
        }
    }

    /// Something waits for `take`.
    pub fn has_pending(&self) -> bool {
        !self.out.is_empty()
    }

    /// The queued lines and seconds, in order, swapped into `into` (which comes back empty for the next ones, so
    /// nothing is allocated).
    pub fn take(&mut self, into: &mut Vec<RecorderOut>) {
        into.clear();
        std::mem::swap(&mut self.out, into);
    }

    fn push(&mut self, o: RecorderOut) {
        if self.out.len() < Self::MAX_QUEUED {
            self.out.push(o);
        }
    }

    pub fn session_started(&mut self, phone: &str, now_us: i64) {
        if self.active {
            self.session_ended("replaced", now_us);
        }
        self.active = true;
        self.session_start_us = now_us;
        self.have_ref = false;
        self.min_cur = i64::MAX;
        self.min_prev = i64::MAX;
        self.win_start_us = now_us;
        self.have_prev = false;
        self.in_holdup = false;
        self.clear_second();
        self.t_states = 0;
        self.t_lost = 0;
        self.t_late = 0;
        self.t_late3 = 0;
        self.t_air = 0;
        self.t_pc = 0;
        self.t_phone = 0;
        self.t_holdups = 0;
        self.t_gaps = 0;
        self.t_catch_ups = 0;
        self.t_jumps = 0;
        self.t_slow = 0;
        self.t_releases = 0;
        self.t_refused = 0;
        self.t_extra.fill(0);
        if self.write_lines {
            self.line(now_us, "session start", &format!("phone '{phone}'"));
        }
    }

    pub fn session_ended(&mut self, why: &str, now_us: i64) {
        if !self.active {
            return;
        }
        self.end_holdup();
        self.active = false;
        if !self.write_lines {
            return;
        }
        let secs = (now_us.saturating_sub(self.session_start_us).max(0).saturating_add(500_000)) / 1_000_000;
        let detail = format!(
            "{why} after {secs} s: states {} lost {} late {} late3 {} holdups {} (air {} pc {} phone {}) phone-gaps {} \
             catch-ups {} jumps {} slow {} releases {} refused {}; over the floor ms p50 {} p99 {} p99.9 {}",
            self.t_states,
            self.t_lost,
            self.t_late,
            self.t_late3,
            self.t_holdups,
            self.t_air,
            self.t_pc,
            self.t_phone,
            self.t_gaps,
            self.t_catch_ups,
            self.t_jumps,
            self.t_slow,
            self.t_releases,
            self.t_refused,
            self.pct(0.5),
            self.pct(0.99),
            self.pct(0.999)
        );
        self.line(now_us, "session end", &detail);
    }

    /// A free-form event line (a Link test starting or ending).
    pub fn note(&mut self, what: &str, detail: &str, now_us: i64) {
        if self.active {
            self.line(now_us, what, detail);
        }
    }

    /// Lost packets: a gap in the shared seq.
    pub fn lost(&mut self, count: i64, seq: u32, now_us: i64) {
        if !self.active || count <= 0 {
            return;
        }
        self.s_lost = self.s_lost.saturating_add(count.min(1_000_000) as i32);
        self.t_lost = self.t_lost.saturating_add(count);
        if count >= 3 && self.event_allowed() {
            self.event(now_us, Ev::Lost { count, seq });
        }
    }

    /// One accepted STATE. `active`: it moves the mouse or holds a key or button (so the phone's spacing matters);
    /// `lost`: packets were lost just before it.
    pub fn state(&mut self, phone_us: u32, recv_us: i64, moving: bool, active: bool, lost: bool) {
        if !self.active {
            return;
        }
        self.s_states = self.s_states.saturating_add(1);
        self.t_states += 1;
        if moving {
            self.s_moving = self.s_moving.saturating_add(1);
        }
        // Phone spacing: the phone didn't send (seq continuous) while something was going on.
        if self.have_prev {
            let span = phone_us.wrapping_sub(self.prev_phone_us) as i32 as i64;
            if !lost && span > Self::PHONE_GAP_US && (self.prev_active || active) {
                self.t_gaps += 1;
                self.s_gaps = self.s_gaps.saturating_add(1);
                if self.event_allowed() {
                    let while_active = self.prev_active;
                    self.event(recv_us, Ev::PhoneGap { span_us: span, while_active });
                }
            }
            if self.in_holdup {
                self.hold_phone_span_max_us = self.hold_phone_span_max_us.max(span);
            }
        }
        self.have_prev = true;
        self.prev_phone_us = phone_us;
        self.prev_active = active;
        // Extra delay against the fastest recent packets.
        let raw = (recv_us as u32).wrapping_sub(phone_us);
        if !self.have_ref {
            self.have_ref = true;
            self.ref_diff = raw;
        }
        let rel = raw.wrapping_sub(self.ref_diff) as i32 as i64;
        if recv_us.saturating_sub(self.win_start_us) > Self::BASE_WINDOW_US {
            self.min_prev = self.min_cur;
            self.min_cur = i64::MAX;
            self.win_start_us = recv_us;
        }
        if rel < self.min_cur {
            self.min_cur = rel;
        }
        let extra = rel.saturating_sub(self.min_cur.min(self.min_prev));
        let bucket = (extra / Self::EXTRA_STEP_US).max(0);
        self.s_extra[bucket.min(SECOND_BUCKETS as i64 - 1) as usize] += 1;
        self.s_extra_n = self.s_extra_n.saturating_add(1);
        self.t_extra[bucket.min(SESSION_BUCKETS as i64 - 1) as usize] += 1;
        if extra > self.s_max_late_us {
            self.s_max_late_us = extra;
        }
        if extra > Self::LATE3_US && extra <= Self::LATE_US {
            self.s_late3 = self.s_late3.saturating_add(1);
            self.t_late3 += 1;
        }
        if extra > Self::LATE_US {
            self.s_late = self.s_late.saturating_add(1);
            self.t_late += 1;
            if !self.in_holdup {
                self.in_holdup = true;
                self.hold_start_us = recv_us;
                self.hold_max_us = 0;
                self.hold_count = 0;
                self.hold_phone_span_max_us = 0;
            }
            self.hold_last_us = recv_us;
            self.hold_count = self.hold_count.saturating_add(1);
            self.hold_max_us = self.hold_max_us.max(extra);
        } else if self.in_holdup {
            self.end_holdup();
        }
    }

    fn end_holdup(&mut self) {
        if !self.in_holdup {
            return;
        }
        self.in_holdup = false;
        self.t_holdups += 1;
        // Where it waited: the phone paused; else the PC couldn't run a thread then (the stall watch); else on the
        // way (Wi-Fi, router, cable). The packets left the phone up to their extra delay before they came.
        let stall_us = match &self.stall {
            Some(w) => w.worst_between(self.hold_start_us.saturating_sub(self.hold_max_us), self.hold_last_us),
            None => -1,
        };
        let verdict = if self.hold_phone_span_max_us > Self::PHONE_GAP_US {
            self.s_phone = self.s_phone.saturating_add(self.hold_count);
            self.t_phone += 1;
            Verdict::Phone
        } else if stall_us >= stall_watch::STALL_US {
            self.s_pc = self.s_pc.saturating_add(self.hold_count);
            self.t_pc += 1;
            Verdict::Pc
        } else {
            self.s_air = self.s_air.saturating_add(self.hold_count);
            self.t_air += 1;
            Verdict::Air
        };
        if self.event_allowed() {
            let ev = Ev::Late {
                count: self.hold_count,
                max_us: self.hold_max_us,
                over_us: self.hold_last_us.saturating_sub(self.hold_start_us),
                stall_us,
                verdict,
            };
            self.event(self.hold_start_us, ev);
        }
    }

    /// One hand-off to Windows: the mouse move in it and how long after the newest packet's arrival (`recv_us`
    /// None: no new packet), with what held it up.
    pub fn inject(&mut self, dx: i32, dy: i32, recv_us: Option<i64>, now_us: i64, refused: bool, causes: &HandOffCauses) {
        if !self.active {
            return;
        }
        self.s_injects = self.s_injects.saturating_add(1);
        let (ax, ay) = (dx.unsigned_abs() as i64, dy.unsigned_abs() as i64);
        let step = ax.max(ay).min(i32::MAX as i64) as i32;
        self.s_px = self.s_px.saturating_add(ax + ay);
        if step > self.s_max_step {
            self.s_max_step = step;
        }
        if step >= Self::JUMP_PX {
            self.t_jumps += 1;
            self.s_jumps = self.s_jumps.saturating_add(1);
            if self.event_allowed() {
                self.event(now_us, Ev::BigMove { dx, dy });
            }
        }
        if let Some(recv_us) = recv_us {
            self.hand_off_took(now_us.saturating_sub(recv_us), now_us, causes);
        }
        if refused {
            self.t_refused += 1;
            if self.event_allowed() {
                self.event(now_us, Ev::Refused);
            }
        }
    }

    /// A hand-off with only the virtual controller in it (no keys or mouse for SendInput): timed like any other, so
    /// slow hand-offs show in controller-only play too.
    pub fn pad_hand_off(&mut self, recv_us: i64, now_us: i64, causes: &HandOffCauses) {
        if !self.active {
            return;
        }
        self.s_pad_hand_offs = self.s_pad_hand_offs.saturating_add(1);
        self.hand_off_took(now_us.saturating_sub(recv_us), now_us, causes);
    }

    fn hand_off_took(&mut self, took: i64, now_us: i64, causes: &HandOffCauses) {
        if took > self.s_max_inject_us {
            self.s_max_inject_us = took;
        }
        if causes.lock_wait_us > self.s_max_lock_wait_us {
            self.s_max_lock_wait_us = causes.lock_wait_us;
            self.s_lock_holder = causes.lock_holder;
        }
        if took > Self::SLOW_INJECT_US {
            self.s_slow = self.s_slow.saturating_add(1);
            self.t_slow += 1;
            if self.event_allowed() {
                self.event(now_us, Ev::SlowHandOff { took_us: took, causes: *causes });
            }
        }
    }

    /// "3.1 ms from receive to SendInput: engine lock 2.6 ms (ui had it), pad driver 0.2 ms, SendInput 0.1 ms,
    /// the rest 0.2 ms" ("input driver" in place of SendInput when keys and mouse go through it): each cause over
    /// 0.05 ms; the rest is gp-rx itself (waiting for a CPU, the burst's other packets).
    fn slow_text(took: i64, c: &HandOffCauses) -> String {
        let route = c.route.unwrap_or("SendInput");
        let mut s = String::with_capacity(160);
        let _ = write!(s, "{} ms from receive to {route}:", ms1(took));
        let mut parts = 0;
        let mut part = |s: &mut String, what: &str, us: i64, extra: Option<&str>| {
            if us < 50 {
                return;
            }
            s.push_str(if parts == 0 { " " } else { ", " });
            parts += 1;
            let _ = write!(s, "{what} {} ms", ms1(us));
            if let Some(extra) = extra {
                let _ = write!(s, " ({extra} had it)");
            }
        };
        part(&mut s, "engine lock", c.lock_wait_us, c.lock_holder);
        part(&mut s, "pad driver", c.pad_us, None);
        part(&mut s, route, c.send_input_us, None);
        let rest = took.saturating_sub(c.lock_wait_us).saturating_sub(c.pad_us).saturating_sub(c.send_input_us);
        part(&mut s, "the rest", rest, None);
        if parts == 0 {
            s.push_str(" no cause measured");
        }
        s
    }

    /// A late or lost stretch of motion is catching up instead of landing at once.
    pub fn catch_up(&mut self, backlog_us: i64, px: i32, spread_us: i64, now_us: i64) {
        if !self.active {
            return;
        }
        self.s_catch_ups = self.s_catch_ups.saturating_add(1);
        self.t_catch_ups += 1;
        if self.event_allowed() {
            self.event(now_us, Ev::CatchUp { px, backlog_us, spread_us });
        }
    }

    /// Everything let go after the phone was silent (the release timeout).
    pub fn released_for_silence(&mut self, held: u32, now_us: i64) {
        if !self.active {
            return;
        }
        self.t_releases += 1;
        self.s_releases = self.s_releases.saturating_add(1);
        if self.event_allowed() {
            self.event(now_us, Ev::Release { held });
        }
    }

    /// The once-a-second line, with the link numbers the engine rolled just before.
    pub fn second(&mut self, stats: &LinkStats, now_us: i64) {
        if !self.active {
            return;
        }
        // Asked every second either way: it also rolls the delivery probe's second.
        let note = self.second_note.as_mut().and_then(|f| f());
        let (stall_max, stalls, _) = self.stall.as_ref().map_or((-1, 0, 0), |w| w.take_second());
        let over_p50 = self.pct_ms(0.5);
        let over_p99 = self.pct_ms(0.99);
        if self.write_lines {
            self.second_line(stats, note.as_deref(), now_us, (over_p50, over_p99), (stall_max, stalls));
        }
        let lat = stats.lat_p50_us != wire::STATUS_UNKNOWN;
        let rtt_min = stats.last_link_info.as_ref().map_or(0, |l| l.rtt_min_us);
        let second = LinkSecond {
            at_us: now_us,
            rx: stats.rx_per_sec as i32,
            lost: self.s_lost,
            late: self.s_late,
            max_late_ms: self.s_max_late_us as f32 / 1000.0,
            gaps: self.s_gaps,
            releases: self.s_releases,
            lat_p50_ms: if lat { stats.lat_p50_us as f32 / 1000.0 } else { -1.0 },
            lat_p99_ms: if lat && stats.lat_p99_us != wire::STATUS_UNKNOWN {
                stats.lat_p99_us as f32 / 1000.0
            } else {
                -1.0
            },
            jitter_ms: stats.jitter_us as f32 / 1000.0,
            floor_ms: if rtt_min > 0 { rtt_min as f32 / 2000.0 } else { -1.0 },
            rtt_typ_ms: if stats.phone_rtt_typ_us > 0 { stats.phone_rtt_typ_us as f32 / 1000.0 } else { -1.0 },
            over_p50_ms: over_p50,
            over_p99_ms: over_p99,
            late3: self.s_late3,
            hand_off_max_ms: self.s_max_inject_us as f32 / 1000.0,
            slow_hand_offs: self.s_slow,
            lock_wait_ms: self.s_max_lock_wait_us as f32 / 1000.0,
            stall_max_ms: if stall_max < 0 { -1.0 } else { stall_max as f32 / 1000.0 },
            stalls: stalls.min(i32::MAX as i64) as i32,
            neg_lat: stats.negative_per_sec,
            air: self.s_air,
            pc: self.s_pc,
            phone: self.s_phone,
            moving: self.s_moving,
        };
        self.push(RecorderOut::Second(second));
        self.clear_second();
    }

    /// The "sec" line; made only when it is written.
    fn second_line(
        &mut self,
        stats: &LinkStats,
        note: Option<&str>,
        now_us: i64,
        (over_p50, over_p99): (f32, f32),
        (stall_max, stalls): (i64, i64),
    ) {
        let mut s = String::with_capacity(160);
        let _ = write!(
            s,
            "rx {} moving {} lost {} late {} maxlate {}",
            stats.rx_per_sec,
            self.s_moving,
            self.s_lost,
            self.s_late,
            ms1(self.s_max_late_us)
        );
        if stats.lat_p50_us != wire::STATUS_UNKNOWN {
            let _ = write!(s, " lat {}/{}", ms1(stats.lat_p50_us as i64), ms1(stats.lat_p99_us as i64));
        }
        // The link split in two: the floor (half the best round trip, the fastest the link went) and how much slower
        // than the fastest packets this second's were (needs no clock sync).
        let rtt_min = stats.last_link_info.as_ref().map_or(0, |l| l.rtt_min_us) as i64;
        if rtt_min > 0 {
            let _ = write!(s, " floor {}", ms2(rtt_min * 5));
        }
        if stats.phone_rtt_typ_us > 0 {
            let _ = write!(s, " rtt {}/{}", ms2(rtt_min * 10), ms2(stats.phone_rtt_typ_us as i64 * 10));
        }
        if self.s_extra_n > 0 {
            let _ = write!(s, " over {over_p50:.1}/{over_p99:.1}");
        }
        if self.s_late3 > 0 {
            let _ = write!(s, " late3 {}", self.s_late3);
        }
        if stats.negative_per_sec > 0 {
            let _ = write!(s, " neglat {}", stats.negative_per_sec);
        }
        let _ = write!(
            s,
            " jitter {} inj {} px {} maxstep {} handoff {}",
            ms1(stats.jitter_us as i64),
            self.s_injects,
            self.s_px,
            self.s_max_step,
            ms1(self.s_max_inject_us)
        );
        if self.s_pad_hand_offs > 0 {
            let _ = write!(s, " padonly {}", self.s_pad_hand_offs);
        }
        if self.s_catch_ups > 0 {
            let _ = write!(s, " catch-ups {}", self.s_catch_ups);
        }
        if self.s_slow > 0 {
            let _ = write!(s, " slow {}", self.s_slow);
        }
        if self.s_jumps > 0 {
            let _ = write!(s, " bigmoves {}", self.s_jumps);
        }
        // gp-rx's longest wait for the engine lock this second, from 0.1 ms, and who had it.
        if self.s_max_lock_wait_us >= 100 {
            let _ = write!(s, " lockwait {}", ms1(self.s_max_lock_wait_us));
            if let Some(holder) = self.s_lock_holder {
                let _ = write!(s, " ({holder})");
            }
        }
        if stall_max >= 0 {
            let _ = write!(s, " stall {}/{stalls}", ms1(stall_max));
        }
        if self.s_air + self.s_pc + self.s_phone > 0 {
            let _ = write!(s, " heldup air {} pc {} phone {}", self.s_air, self.s_pc, self.s_phone);
        }
        if let Some(note) = note {
            s.push_str(note);
        }
        if self.s_skipped > 0 {
            let _ = write!(s, " ({} more events not written)", self.s_skipped);
        }
        self.line(now_us, "sec", &s);
    }

    fn clear_second(&mut self) {
        self.s_states = 0;
        self.s_moving = 0;
        self.s_lost = 0;
        self.s_late = 0;
        self.s_late3 = 0;
        self.s_air = 0;
        self.s_pc = 0;
        self.s_phone = 0;
        self.s_pad_hand_offs = 0;
        self.s_extra = [0; SECOND_BUCKETS];
        self.s_extra_n = 0;
        self.s_gaps = 0;
        self.s_releases = 0;
        self.s_injects = 0;
        self.s_max_step = 0;
        self.s_slow = 0;
        self.s_catch_ups = 0;
        self.s_jumps = 0;
        self.s_events = 0;
        self.s_skipped = 0;
        self.s_px = 0;
        self.s_max_late_us = 0;
        self.s_max_inject_us = 0;
        self.s_max_lock_wait_us = 0;
        self.s_lock_holder = None;
    }

    /// A percentile of the session's 0.1 ms histogram, as "<0.3" (ms), "-" when empty.
    fn pct(&self, p: f64) -> String {
        let n: i64 = self.t_extra.iter().sum();
        if n == 0 {
            return "-".to_string();
        }
        let rank = (p * n as f64).ceil() as i64;
        let mut acc = 0;
        let last = self.t_extra.len() - 1;
        for (i, c) in self.t_extra.iter().enumerate() {
            acc += c;
            if acc >= rank {
                return if i == last {
                    format!("{}+", ms1(i as i64 * Self::EXTRA_STEP_US))
                } else {
                    format!("<{}", ms1((i as i64 + 1) * Self::EXTRA_STEP_US))
                };
            }
        }
        "-".to_string()
    }

    /// A percentile of this second's 0.1 ms histogram, ms (the bucket's middle), -1 when empty.
    fn pct_ms(&self, p: f64) -> f32 {
        let n = self.s_extra_n;
        if n == 0 {
            return -1.0;
        }
        let rank = (p * n as f64).ceil() as i64;
        let mut acc = 0i64;
        for (i, c) in self.s_extra.iter().enumerate() {
            acc += *c as i64;
            if acc >= rank {
                return (i as i64 * Self::EXTRA_STEP_US + Self::EXTRA_STEP_US / 2) as f32 / 1000.0;
            }
        }
        (SECOND_BUCKETS as i64 * Self::EXTRA_STEP_US) as f32 / 1000.0
    }

    /// Room for one more event line this second (at most `MAX_EVENTS_PER_SECOND`: a flick at high sensitivity can
    /// make big moves every millisecond); the rest are only counted. Checked before the line's text is made; false
    /// while no lines are written.
    fn event_allowed(&mut self) -> bool {
        if !self.write_lines {
            return false;
        }
        if self.s_events >= Self::MAX_EVENTS_PER_SECOND {
            self.s_skipped = self.s_skipped.saturating_add(1);
            return false;
        }
        self.s_events += 1;
        true
    }

    /// A free-form line (session start and end, the second's line, a note); not on the input path.
    fn line(&mut self, at_us: i64, what: &str, detail: &str) {
        if !self.write_lines {
            return;
        }
        let ev = Ev::Text { what: what.to_string(), detail: detail.to_string() };
        self.event(at_us, ev);
    }

    /// An event as numbers: its text is made later (`Event::text`), off the input path.
    fn event(&mut self, at_us: i64, ev: Ev) {
        let at_us = at_us.saturating_sub(self.session_start_us);
        self.push(RecorderOut::Line(Event { at_us, ev }));
    }
}

/// `x / 10000.0` as .NET's "0.00" writes it, where `tenths_us` is µs × 10: hundredths of a millisecond, halves away
/// from zero.
fn ms2(tenths_us: i64) -> String {
    let hundredths = tenths_us.unsigned_abs().saturating_add(50) / 100;
    format!("{}{}.{:02}", if tenths_us < 0 { "-" } else { "" }, hundredths / 100, hundredths % 100)
}
