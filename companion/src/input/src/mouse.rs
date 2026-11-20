//! Mouse motion between the wire and the injection: `MouseDeltaTracker` turns the STATEs' wrapping
//! totals into deltas (SPEC 1.8) and `MouseCatchUp` keeps a lost or late stretch of motion from
//! landing as one jump. Ports of the C# classes of the same names.
//!
//! Everything that comes from the phone is added with wrapping arithmetic, as the C# does, so no
//! packet can make this code panic.

use crate::wire;

/// Turns the cumulative wrapping mouse/wheel totals of accepted STATEs into deltas (SPEC 1.8).
/// Rebases (delta 0) on the first STATE, after more than RELEASE_TIMEOUT_MS of silence and when the
/// phone switches between STATE and STATE_HIRES; clamps each delta; always moves the baseline to
/// the new total.
#[derive(Clone, Debug, Default)]
pub struct MouseDeltaTracker {
    has_base: bool,
    hires: bool,
    last_us: i64,
    last_x: i32,
    last_y: i32,
    last_w: i32,
    last_h: i32,
}

/// One STATE's deltas. Wheels are in the units of the packet.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct MouseDelta {
    pub dx: i32,
    pub dy: i32,
    pub dw: i32,
    pub dh: i32,
}

impl MouseDeltaTracker {
    const REBASE_GAP_US: i64 = wire::RELEASE_TIMEOUT_MS * 1000;

    pub fn new() -> Self {
        Self::default()
    }

    /// Forget the baseline; the next STATE rebases.
    pub fn reset(&mut self) {
        self.has_base = false;
    }

    /// STATE (`hires` false: wheel in notches, no horizontal wheel) or STATE_HIRES (both wheels in
    /// 1/120 notch). Wheel deltas come out in the units of the packet.
    pub fn apply(&mut self, total_x: i32, total_y: i32, total_w: i32, total_h: i32, hires: bool, now_us: i64) -> MouseDelta {
        let d = if !self.has_base || now_us.wrapping_sub(self.last_us) > Self::REBASE_GAP_US || hires != self.hires {
            self.has_base = true;
            MouseDelta::default()
        } else {
            let max_w = if hires { wire::MAX_WHEEL_DELTA_HIRES } else { wire::MAX_WHEEL_DELTA };
            let m = wire::MAX_MOUSE_DELTA;
            MouseDelta {
                dx: total_x.wrapping_sub(self.last_x).clamp(-m, m),
                dy: total_y.wrapping_sub(self.last_y).clamp(-m, m),
                dw: total_w.wrapping_sub(self.last_w).clamp(-max_w, max_w),
                dh: total_h.wrapping_sub(self.last_h).clamp(-max_w, max_w),
            }
        };
        self.hires = hires;
        self.last_x = total_x;
        self.last_y = total_y;
        self.last_w = total_w;
        self.last_h = total_h;
        self.last_us = now_us;
        d
    }
}

/// Keeps a lost or late stretch of mouse motion from landing as one jump. Each accepted STATE's
/// mouse delta comes in through `add` with the phone time it covers; `take` (once per injection)
/// hands out what to move now:
///
/// - The newest motion (the last STATE's, or, after lost packets, its share of the newest
///   `fresh_us` of phone time) always goes out at once, so aiming never waits.
/// - Older motion that arrives in the same injection (a late burst, or the part of a gap that lost
///   packets would have carried) goes out at once too while it covers at most `MIN_BACKLOG_US`,
///   which is ordinary Wi-Fi jitter. A longer backlog is played out over half the time it covers,
///   at most `max_us` ("Catch-up time"), on top of the newest motion.
///
/// Nothing is lost: every count goes out, a catch-up just spreads some over a few milliseconds.
#[derive(Clone, Debug)]
pub struct MouseCatchUp {
    // This injection's motion: the newest STATE's own (fresh) and the older ones' (old).
    fresh_x: i32,
    fresh_y: i32,
    old_x: i32,
    old_y: i32,
    fresh_span: i64,
    old_span: i64,
    has_fresh: bool,

    // The catch-up: counts still to go out, from spread_from_us until spread_end_us.
    spread_x: f64,
    spread_y: f64,
    spread_from_us: i64,
    spread_end_us: i64,
    frac_x: f64,
    frac_y: f64,

    /// The longest a backlog is spread over ("Catch-up time"); 0: off, everything goes out at once.
    pub max_us: i64,
    fresh_us: i32,
    catching_up: bool,

    backlog_taken: bool,
    backlog_span_us: i64,
    backlog_px: i32,
    backlog_catch_up_us: i64,
}

impl Default for MouseCatchUp {
    fn default() -> Self {
        MouseCatchUp {
            fresh_x: 0,
            fresh_y: 0,
            old_x: 0,
            old_y: 0,
            fresh_span: 0,
            old_span: 0,
            has_fresh: false,
            spread_x: 0.0,
            spread_y: 0.0,
            spread_from_us: 0,
            spread_end_us: 0,
            frac_x: 0.0,
            frac_y: 0.0,
            max_us: Self::DEFAULT_MAX_US,
            fresh_us: Self::START_FRESH_US,
            catching_up: false,
            backlog_taken: false,
            backlog_span_us: 0,
            backlog_px: 0,
            backlog_catch_up_us: 0,
        }
    }
}

impl MouseCatchUp {
    /// A backlog covering at most this much phone time goes out at once.
    pub const MIN_BACKLOG_US: i64 = 4_000;
    /// The longest a backlog is spread over by default.
    pub const DEFAULT_MAX_US: i64 = 16_000;
    const MIN_FRESH_US: i64 = 500;
    const MAX_FRESH_US: i64 = 8_000;
    const START_FRESH_US: i32 = 1_000;
    /// Spans longer than this are idle pauses, not the phone's send spacing.
    const MAX_SPACING_US: i64 = 25_000;

    pub fn new() -> Self {
        Self::default()
    }

    /// The phone's usual spacing between moving STATEs, µs (learned).
    pub fn fresh_us(&self) -> i32 {
        self.fresh_us
    }

    /// Some backlog is still to go out: `take` should run again by `catch_up_end_us`.
    pub fn catching_up(&self) -> bool {
        self.catching_up
    }

    pub fn catch_up_end_us(&self) -> i64 {
        self.spread_end_us
    }

    /// The last `take` started or lengthened a catch-up: the backlog's phone time, counts and
    /// catch-up length (for the input log).
    pub fn backlog_taken(&self) -> bool {
        self.backlog_taken
    }

    pub fn backlog_span_us(&self) -> i64 {
        self.backlog_span_us
    }

    pub fn backlog_px(&self) -> i32 {
        self.backlog_px
    }

    pub fn backlog_catch_up_us(&self) -> i64 {
        self.backlog_catch_up_us
    }

    /// Nothing pending (a new session, a release, a pause): the backlog is dropped.
    pub fn clear(&mut self) {
        self.fresh_x = 0;
        self.fresh_y = 0;
        self.old_x = 0;
        self.old_y = 0;
        self.fresh_span = 0;
        self.old_span = 0;
        self.has_fresh = false;
        self.spread_x = 0.0;
        self.spread_y = 0.0;
        self.frac_x = 0.0;
        self.frac_y = 0.0;
        self.catching_up = false;
    }

    /// One STATE's mouse delta. `span_us`: phone time since the STATE before it (0 when unknown);
    /// `lost`: packets were missing between the two, so the delta also carries their motion.
    pub fn add(&mut self, dx: i32, dy: i32, span_us: i64, lost: bool) {
        let span_us = span_us.max(0);
        if self.has_fresh {
            // A newer STATE in the same injection: the one before it is now backlog.
            self.old_x = self.old_x.wrapping_add(self.fresh_x);
            self.old_y = self.old_y.wrapping_add(self.fresh_y);
            self.old_span = self.old_span.saturating_add(self.fresh_span);
        }
        self.has_fresh = true;
        let moved = dx != 0 || dy != 0;
        let fresh = self.fresh_us as i64;
        if !lost && moved && span_us > 0 && span_us <= Self::MAX_SPACING_US {
            // Learn the spacing slowly, so one odd span does not move it.
            self.fresh_us = (fresh + (span_us - fresh) / 8).clamp(Self::MIN_FRESH_US, Self::MAX_FRESH_US) as i32;
        }
        let fresh = self.fresh_us as i64;
        if lost && span_us > fresh {
            // The lost packets' motion came before this STATE's own: only the newest share is fresh.
            let share = fresh as f64 / span_us as f64;
            let fx = (dx as f64 * share).round_ties_even() as i32;
            let fy = (dy as f64 * share).round_ties_even() as i32;
            self.fresh_x = fx;
            self.fresh_y = fy;
            self.old_x = self.old_x.wrapping_add(dx.wrapping_sub(fx));
            self.old_y = self.old_y.wrapping_add(dy.wrapping_sub(fy));
            self.old_span = self.old_span.saturating_add(span_us - fresh);
            self.fresh_span = fresh;
        } else {
            // Nothing lost: the phone sent as soon as it moved, so all of it is fresh (after an
            // idle pause the span is long but the motion is recent).
            self.fresh_x = dx;
            self.fresh_y = dy;
            self.fresh_span = span_us.min(fresh);
        }
    }

    /// What to move now, at host time `now_us`; call once per injection.
    pub fn take(&mut self, now_us: i64) -> (i32, i32) {
        let mut x = self.fresh_x as f64;
        let mut y = self.fresh_y as f64;
        self.backlog_taken = false;
        if self.max_us > 0 && self.old_span > Self::MIN_BACKLOG_US {
            let end = now_us.saturating_add((self.old_span / 2).min(self.max_us).max(1));
            self.backlog_taken = true;
            self.backlog_span_us = self.old_span;
            self.backlog_px = self.old_x.saturating_abs().saturating_add(self.old_y.saturating_abs());
            self.backlog_catch_up_us = end.saturating_sub(now_us);
            if !self.catching_up {
                self.spread_from_us = now_us;
                self.spread_end_us = end;
            } else if end > self.spread_end_us {
                self.spread_end_us = end;
            }
            self.spread_x += self.old_x as f64;
            self.spread_y += self.old_y as f64;
            self.catching_up = true;
        } else {
            x += self.old_x as f64;
            y += self.old_y as f64;
        }
        self.fresh_x = 0;
        self.fresh_y = 0;
        self.old_x = 0;
        self.old_y = 0;
        self.fresh_span = 0;
        self.old_span = 0;
        self.has_fresh = false;

        if self.catching_up {
            let part = if now_us >= self.spread_end_us {
                1.0
            } else {
                now_us.saturating_sub(self.spread_from_us) as f64
                    / self.spread_end_us.saturating_sub(self.spread_from_us) as f64
            };
            if part > 0.0 {
                let gx = self.spread_x * part;
                let gy = self.spread_y * part;
                self.spread_x -= gx;
                self.spread_y -= gy;
                x += gx;
                y += gy;
                self.spread_from_us = now_us;
            }
            if now_us >= self.spread_end_us {
                self.catching_up = false;
                self.spread_x = 0.0;
                self.spread_y = 0.0;
            }
        }
        // Whole counts go out; the catch-up's fractions wait for the next injection.
        x += self.frac_x;
        y += self.frac_y;
        let mut dx = x.trunc() as i32;
        let mut dy = y.trunc() as i32;
        self.frac_x = x - dx as f64;
        self.frac_y = y - dy as f64;
        if !self.catching_up {
            // Fractions only come from a catch-up; once it ends, the last of them go out with it.
            dx = dx.wrapping_add(self.frac_x.round_ties_even() as i32);
            dy = dy.wrapping_add(self.frac_y.round_ties_even() as i32);
            self.frac_x = 0.0;
            self.frac_y = 0.0;
        }
        (dx, dy)
    }
}
