//! The gyro run on the PC ("Use PC controller for gyro ratchet"): the phone sends its raw gyro
//! samples and settings with each STATE_GYRO, and the PC turns them into mouse counts, wheel units
//! and game pad axes, ratcheted by a controller plugged into it.
//!
//! `GyroMath` and `in_space` are exact ports of the phone's GyroMath.kt and GyroSpaces.kt (through
//! the companion's GyroMath.cs and GyroSpaces.cs): the same float operations in the same order with
//! the same rounding, checked to the bit by the shared vectors (gyro-math-v1.json). Change one side,
//! change the others and the vectors. Rust never fuses a multiply and an add on its own, so the
//! arithmetic below is plain IEEE single precision, as on the phone.

use crate::le;
use crate::state_frame::StateFrame;
use crate::wire;

pub const PAD_AXES: usize = 6;
pub const MAX_CURVE_POINTS: usize = 6;
pub const MAX_ROUTES: usize = 12;
pub const MAX_SAMPLES: usize = 8;

/// Gyro spaces: how an axis is measured.
pub const SPACE_LOCAL: u8 = 0;
pub const SPACE_WORLD: u8 = 1;
pub const SPACE_PLAYER: u8 = 2;

/// A route's target: mouse x, mouse y, wheel, horizontal wheel, then `TARGET_PAD` + game pad axis (lx, ly, rx, ry, lt, rt).
pub const TARGET_MOUSE_X: u8 = 0;
pub const TARGET_MOUSE_Y: u8 = 1;
pub const TARGET_WHEEL: u8 = 2;
pub const TARGET_HWHEEL: u8 = 3;
pub const TARGET_PAD: u8 = 4;

/// One gyro mapping: `axis` (0 yaw, 1 pitch, 2 roll) to `target`, times `scale`.
#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct Route {
    pub axis: u8,
    pub target: u8,
    pub scale: f32,
}

/// What one gyro configuration does, resolved on the phone: per axis (yaw, pitch, roll) its
/// sensitivity (counts per degree), glide and acceleration curve, and where each axis goes.
#[derive(Clone, Debug, PartialEq)]
pub struct GyroConfig {
    pub sensitivity: [f32; 3],
    pub inertia: [bool; 3],
    pub glide_ms: [f32; 3],
    pub linear: [bool; 3],
    pub routes: [Route; MAX_ROUTES],
    pub route_count: usize,
    /// Per axis: speeds (deg/s) and gains interleaved, `curve_points` points (none = gain 1).
    pub curves: [[f32; MAX_CURVE_POINTS * 2]; 3],
    pub curve_points: [usize; 3],
    /// Per axis: the curve's speed is the axis's own, not the whole phone's.
    pub axis_speed: [bool; 3],
    /// Per axis: the curve is a smooth bend through the points rather than straight lines.
    pub smooth: [bool; 3],
    /// Per axis the gyro space it is measured in.
    pub space: [u8; 3],
}

impl Default for GyroConfig {
    fn default() -> Self {
        GyroConfig {
            sensitivity: [GyroMath::DEFAULT_SENSITIVITY; 3],
            inertia: [false; 3],
            glide_ms: [GyroMath::DEFAULT_GLIDE_MS; 3],
            linear: [false; 3],
            routes: [Route::default(); MAX_ROUTES],
            route_count: 0,
            curves: [[0.0; MAX_CURVE_POINTS * 2]; 3],
            curve_points: [0; 3],
            axis_speed: [false; 3],
            smooth: [false; 3],
            space: [SPACE_LOCAL; 3],
        }
    }
}

impl GyroConfig {
    pub fn routes(&self) -> &[Route] {
        &self.routes[..self.route_count]
    }

    /// Adds a route; false (and nothing added) when all `MAX_ROUTES` are taken.
    pub fn add_route(&mut self, r: Route) -> bool {
        if self.route_count == MAX_ROUTES {
            return false;
        }
        self.routes[self.route_count] = r;
        self.route_count += 1;
        true
    }

    /// Sets an axis's curve to `flat` (speeds and gains interleaved).
    pub fn set_curve(&mut self, axis: usize, flat: &[f32], axis_speed: bool, smooth: bool) {
        let n = (flat.len() / 2).min(MAX_CURVE_POINTS);
        self.curves[axis][..n * 2].copy_from_slice(&flat[..n * 2]);
        self.curve_points[axis] = n;
        self.axis_speed[axis] = axis_speed && n > 0;
        self.smooth[axis] = smooth && n > 0;
    }

    /// Reads a config block (STATE_GYRO's, or PAD_GYRO_SET's after its 2 bytes) of
    /// `wire::PC_GYRO_CONFIG_SIZE` bytes; returns the global (tightening, smoothing) thresholds.
    pub fn decode(&mut self, b: &[u8]) -> (f32, f32) {
        for a in 0..3 {
            self.sensitivity[a] = f32_at(b, a * 4);
            self.glide_ms[a] = f32_at(b, 12 + a * 4);
        }
        let glide = b[24];
        for a in 0..3 {
            self.inertia[a] = glide & (1 << a) != 0;
            self.linear[a] = glide & (1 << (3 + a)) != 0;
        }
        let r = (b[25] as usize).min(MAX_ROUTES);
        let tightening = f32_at(b, 26);
        let smoothing = f32_at(b, 30);
        self.route_count = 0;
        for i in 0..r {
            let o = ROUTES_REL + i * ROUTE_SIZE;
            let (axis, target) = (b[o], b[o + 1]);
            // A route this host cannot follow (a newer phone) is left out, not guessed.
            if axis > 2 || target >= TARGET_PAD + PAD_AXES as u8 {
                continue;
            }
            self.routes[self.route_count] = Route { axis, target, scale: f32_at(b, o + 2) };
            self.route_count += 1;
        }
        let mut flat = [0f32; MAX_CURVE_POINTS * 2];
        for a in 0..3 {
            let o = CURVES_REL + a * CURVE_SIZE;
            let n = ((b[o] & 0x0F) as usize).min(MAX_CURVE_POINTS);
            for j in 0..n {
                flat[2 * j] = le::u16(b, o + 1 + j * 4) as f32 / 10.0;
                flat[2 * j + 1] = le::u16(b, o + 3 + j * 4) as f32 / 1000.0;
            }
            self.set_curve(a, &flat[..n * 2], b[o] & CURVE_AXIS_SPEED != 0, b[o] & CURVE_SMOOTH != 0);
        }
        // A space this host does not know (a newer phone) reads Local.
        for a in 0..3 {
            let sp = (b[SPACES_REL] >> (2 * a)) & 3;
            self.space[a] = if sp <= SPACE_PLAYER { sp } else { SPACE_LOCAL };
        }
        (tightening, smoothing)
    }

    /// Writes `wire::PC_GYRO_CONFIG_SIZE` bytes of config block (tests and tools).
    pub fn encode(&self, b: &mut [u8], tightening: f32, smoothing: f32) {
        b[..wire::PC_GYRO_CONFIG_SIZE].fill(0);
        let mut glide = 0u8;
        for a in 0..3 {
            put_f32(b, a * 4, self.sensitivity[a]);
            put_f32(b, 12 + a * 4, self.glide_ms[a]);
            if self.inertia[a] {
                glide |= 1 << a;
            }
            if self.linear[a] {
                glide |= 1 << (3 + a);
            }
        }
        b[24] = glide;
        b[25] = self.route_count as u8;
        put_f32(b, 26, tightening);
        put_f32(b, 30, smoothing);
        for (i, r) in self.routes().iter().enumerate() {
            let o = ROUTES_REL + i * ROUTE_SIZE;
            b[o] = r.axis;
            b[o + 1] = r.target;
            put_f32(b, o + 2, r.scale);
        }
        for a in 0..3 {
            let o = CURVES_REL + a * CURVE_SIZE;
            let n = self.curve_points[a];
            b[o] = n as u8 | if self.axis_speed[a] { CURVE_AXIS_SPEED } else { 0 } | if self.smooth[a] { CURVE_SMOOTH } else { 0 };
            for j in 0..n {
                le::put_u16(b, o + 1 + j * 4, (self.curves[a][2 * j] * 10.0).round() as u16);
                le::put_u16(b, o + 3 + j * 4, (self.curves[a][2 * j + 1] * 1000.0).round() as u16);
            }
        }
        let mut spaces = 0u8;
        for a in 0..3 {
            spaces |= (self.space[a] & 3) << (2 * a);
        }
        b[SPACES_REL] = spaces;
    }
}

const ROUTE_SIZE: usize = 6;
const ROUTES_REL: usize = 34;
const CURVES_REL: usize = ROUTES_REL + MAX_ROUTES * ROUTE_SIZE;
const CURVE_SIZE: usize = 1 + MAX_CURVE_POINTS * 4;
const SPACES_REL: usize = CURVES_REL + 3 * CURVE_SIZE;
const CURVE_AXIS_SPEED: u8 = 0x80;
const CURVE_SMOOTH: u8 = 0x40;
const _: () = assert!(SPACES_REL + 1 == wire::PC_GYRO_CONFIG_SIZE);

fn f32_at(b: &[u8], off: usize) -> f32 {
    f32::from_bits(le::u32(b, off))
}

fn put_f32(b: &mut [u8], off: usize, v: f32) {
    le::put_u32(b, off, v.to_bits());
}

// =====================================================================================
// the math
// =====================================================================================

/// Where `GyroMath::step` sent one sample: mouse counts, wheel units and game pad axes (lx, ly, rx, ry, lt, rt).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Routed {
    pub dx: i32,
    pub dy: i32,
    pub wheel: i32,
    pub hwheel: i32,
    pub pad: [f32; PAD_AXES],
}

/// The most shares being spread at once: a window's worth of samples at 4 kHz.
const SPREAD_MAX: usize = 32;
const LY: usize = 1;
const RY: usize = 3;
const LT: usize = 4;
const RT: usize = 5;

/// The gyro's math after the motion is known: yaw, pitch and roll (deg/s) to mouse counts, wheel
/// units and game pad axes, with glide (inertia) when a ratchet turns an axis off, jitter tightening
/// and smoothing, and the sub-count remainders kept.
#[derive(Clone, Debug)]
pub struct GyroMath {
    rem_x: f32,
    rem_y: f32,
    rem_wheel: f32,
    rem_hwheel: f32,
    // Smoothing: each sample's smoothed share (counts still to send) spread evenly over the
    // window from its own sample on, with its age (s). Oldest first, `spread_n` in use.
    spread_x: [f32; SPREAD_MAX],
    spread_y: [f32; SPREAD_MAX],
    spread_age: [f32; SPREAD_MAX],
    spread_n: usize,
    spread_out_x: f32,
    spread_out_y: f32,
    // Inertia, per axis: its recent speed (deg/s EMA), its glide speed (deg/s, 0 = none), glide time
    // (ms) and linear slowing (deg/s per ms, 0 = smooth); the axes on last sample.
    speed: [f32; 3],
    glide_v: [f32; 3],
    glide_tau: [f32; 3],
    glide_dec: [f32; 3],
    glide_out: [f32; 3],
    last_axes: u8,
    // Acceleration, per axis: the gain of this sample; an axis off keeps the one it had when it was last on.
    gain: [f32; 3],
}

impl Default for GyroMath {
    fn default() -> Self {
        GyroMath {
            rem_x: 0.0,
            rem_y: 0.0,
            rem_wheel: 0.0,
            rem_hwheel: 0.0,
            spread_x: [0.0; SPREAD_MAX],
            spread_y: [0.0; SPREAD_MAX],
            spread_age: [0.0; SPREAD_MAX],
            spread_n: 0,
            spread_out_x: 0.0,
            spread_out_y: 0.0,
            speed: [0.0; 3],
            glide_v: [0.0; 3],
            glide_tau: [0.0; 3],
            glide_dec: [0.0; 3],
            glide_out: [0.0; 3],
            last_axes: 0,
            gain: [1.0; 3],
        }
    }
}

impl GyroMath {
    /// Smoothing spreads each sample's smoothed motion evenly over this time (s), whatever the sample rate.
    pub const SMOOTHING_WINDOW_S: f32 = 0.008;
    /// Counts per degree.
    pub const DEFAULT_SENSITIVITY: f32 = 16.0;
    pub const DEFAULT_GLIDE_MS: f32 = 325.0;
    /// One wheel notch in wheel units (1/120 notch).
    pub const WHEEL_UNIT: f32 = 120.0;
    /// Degrees of turn per wheel notch when gyro scrolls.
    pub const DEG_PER_NOTCH: f32 = 15.0;
    /// Turning this fast (deg/s) deflects a game pad axis fully.
    pub const STICK_FULL_DEG_PER_S: f32 = 90.0;
    /// The recent speed a glide starts from: an EMA with this time constant (ms).
    const SPEED_TAU_MS: f32 = 15.0;
    /// An axis turned off slower than this (deg/s) does not glide; a glide stops below `GLIDE_STOP_DEG_PER_S`.
    pub const GLIDE_MIN_DEG_PER_S: f32 = 5.0;
    pub const GLIDE_STOP_DEG_PER_S: f32 = 0.5;
    pub const MIN_GLIDE_MS: f32 = 100.0;
    pub const MAX_GLIDE_MS: f32 = 1000.0;

    /// An axis glides, or one was on last sample (turning it off may start a glide): `step` wants every sample.
    pub fn coasting(&self) -> bool {
        self.last_axes != 0 || self.glide_v[0] != 0.0 || self.glide_v[1] != 0.0 || self.glide_v[2] != 0.0
    }

    pub fn reset(&mut self) {
        self.rem_x = 0.0;
        self.rem_y = 0.0;
        self.rem_wheel = 0.0;
        self.rem_hwheel = 0.0;
        self.spread_n = 0;
        self.spread_out_x = 0.0;
        self.spread_out_y = 0.0;
        self.speed = [0.0; 3];
        self.glide_v = [0.0; 3];
        self.glide_out = [0.0; 3];
        self.last_axes = 0;
        self.gain = [1.0; 3];
    }

    /// One sample: yaw, pitch, roll (deg/s) over `dt` seconds with `axes` on (bit 0 yaw, 1 pitch,
    /// 2 roll), sent where `c` says. `tightening` and `smoothing` are thresholds in deg/s (0 = off).
    /// An axis off moves only by its glide, and only the mouse and wheel.
    #[allow(clippy::too_many_arguments)]
    pub fn step(&mut self, yaw: f32, pitch: f32, roll: f32, dt: f32, c: &GyroConfig, axes: u8, tightening: f32, smoothing: f32) -> Routed {
        self.coast(c, yaw, pitch, roll, axes, dt);
        self.accelerate(c, yaw, pitch, roll, axes);
        let (mut vx, mut vy, mut w, mut hw) = (0f32, 0f32, 0f32, 0f32);
        // The phone's own turning (deg/s) that moves the cursor: before sensitivity and acceleration.
        let (mut px, mut py) = (0f32, 0f32);
        let mut out = Routed::default();
        for r in c.routes() {
            let axis = r.axis as usize;
            if axis > 2 {
                continue; // never decoded; a hand-made config cannot index past the axes
            }
            let on = axes & (1 << axis) != 0;
            let g = if on { 0.0 } else { self.glide_out[axis] };
            if !on && (g == 0.0 || r.target >= TARGET_PAD) {
                continue;
            }
            let k = c.sensitivity[axis];
            let raw = if on {
                match axis {
                    0 => yaw,
                    1 => pitch,
                    _ => roll,
                }
            } else {
                g
            };
            let v = raw * self.gain[axis];
            match r.target {
                TARGET_MOUSE_X => {
                    vx += v * k * r.scale;
                    px += raw * sign(r.scale);
                }
                TARGET_MOUSE_Y => {
                    vy += v * k * r.scale;
                    py += raw * sign(r.scale);
                }
                TARGET_WHEEL => w += v * k / Self::DEFAULT_SENSITIVITY * r.scale,
                TARGET_HWHEEL => hw += v * k / Self::DEFAULT_SENSITIVITY * r.scale,
                t => {
                    let a = (t - TARGET_PAD) as usize;
                    if a >= PAD_AXES {
                        continue; // not a game pad axis (the phone never sends one)
                    }
                    // Stick Y is + down: pitching up pushes it up.
                    let up = if a == LY || a == RY { -1f32 } else { 1f32 };
                    out.pad[a] += up * v * k / Self::DEFAULT_SENSITIVITY * r.scale / Self::STICK_FULL_DEG_PER_S;
                }
            }
        }
        for (a, p) in out.pad.iter_mut().enumerate() {
            *p = if a == LT || a == RT { coerce_at_most(p.abs(), 1.0) } else { coerce_in(*p, -1.0, 1.0) };
        }
        let (dx, dy) = self.counts(vx, vy, (px * px + py * py).sqrt(), dt, tightening, smoothing);
        out.dx = dx;
        out.dy = dy;
        let fw = w * dt * Self::WHEEL_UNIT / Self::DEG_PER_NOTCH + self.rem_wheel;
        let fh = hw * dt * Self::WHEEL_UNIT / Self::DEG_PER_NOTCH + self.rem_hwheel;
        out.wheel = to_int(fw);
        self.rem_wheel = fw - out.wheel as f32;
        out.hwheel = to_int(fh);
        self.rem_hwheel = fh - out.hwheel as f32;
        out
    }

    /// Mouse counts of cursor velocity `vx0` (right +) and `vy0` (up +) in counts/s over `dt`:
    /// filtered by the phone's turning speed (deg/s), the remainder kept.
    fn counts(&mut self, vx0: f32, vy0: f32, speed: f32, dt: f32, tightening: f32, smoothing: f32) -> (i32, i32) {
        let mut vx = vx0;
        let mut vy = vy0;
        if tightening > 0.0 && speed < tightening {
            let k = speed / tightening;
            vx *= k;
            vy *= k;
        }
        let (mut sx, mut sy) = (0f32, 0f32);
        if smoothing > 0.0 || self.spread_n > 0 {
            // Fully smoothed below threshold/2, fully direct above threshold, blended between. Only
            // the smoothed share is spread.
            let direct = if smoothing > 0.0 { coerce_in((speed - smoothing / 2.0) / (smoothing / 2.0), 0.0, 1.0) } else { 1.0 };
            let share = 1.0 - direct;
            self.spread(vx * share * dt, vy * share * dt, dt);
            sx = self.spread_out_x;
            sy = self.spread_out_y;
            vx *= direct;
            vy *= direct;
        }
        let fx = vx * dt + sx + self.rem_x;
        let fy = -(vy * dt + sy) + self.rem_y;
        let dx = round_to_int(fx);
        let dy = round_to_int(fy);
        self.rem_x = fx - dx as f32;
        self.rem_y = fy - dy as f32;
        (dx, dy)
    }

    /// Adds this sample's smoothed counts to the spread and takes what every share sends over the next `dt`.
    fn spread(&mut self, x: f32, y: f32, dt: f32) {
        if x != 0.0 || y != 0.0 {
            if self.spread_n == SPREAD_MAX {
                // Full (very short samples): the oldest goes out now.
                self.rem_x += self.spread_x[0];
                self.rem_y -= self.spread_y[0];
                self.drop_share(0);
            }
            let n = self.spread_n;
            self.spread_x[n] = x;
            self.spread_y[n] = y;
            self.spread_age[n] = 0.0;
            self.spread_n += 1;
        }
        let (mut ox, mut oy) = (0f32, 0f32);
        let mut i = 0;
        while i < self.spread_n {
            let a0 = self.spread_age[i];
            let a1 = a0 + dt;
            if a1 >= Self::SMOOTHING_WINDOW_S {
                ox += self.spread_x[i];
                oy += self.spread_y[i];
                self.drop_share(i);
                continue;
            }
            let part = dt / (Self::SMOOTHING_WINDOW_S - a0);
            let gx = self.spread_x[i] * part;
            let gy = self.spread_y[i] * part;
            ox += gx;
            oy += gy;
            self.spread_x[i] -= gx;
            self.spread_y[i] -= gy;
            self.spread_age[i] = a1;
            i += 1;
        }
        self.spread_out_x = ox;
        self.spread_out_y = oy;
    }

    fn drop_share(&mut self, i: usize) {
        let n = self.spread_n;
        self.spread_x.copy_within(i + 1..n, i);
        self.spread_y.copy_within(i + 1..n, i);
        self.spread_age.copy_within(i + 1..n, i);
        self.spread_n -= 1;
    }

    /// Each axis's acceleration gain for this sample: an axis on takes its curve's gain at its own
    /// speed or the whole phone's; one off keeps its last.
    fn accelerate(&mut self, c: &GyroConfig, yaw: f32, pitch: f32, roll: f32, axes: u8) {
        let whole = (yaw * yaw + pitch * pitch + roll * roll).sqrt();
        for i in 0..3 {
            if axes & (1 << i) == 0 {
                continue;
            }
            let n = c.curve_points[i].min(MAX_CURVE_POINTS);
            if n == 0 {
                self.gain[i] = 1.0;
                continue;
            }
            let speed = if c.axis_speed[i] {
                match i {
                    0 => yaw,
                    1 => pitch,
                    _ => roll,
                }
                .abs()
            } else {
                whole
            };
            self.gain[i] = gain(&c.curves[i], n, speed, c.smooth[i]);
        }
    }

    /// Inertia for one sample with `axes` on, over `dt`: an axis on follows its speed and never
    /// glides; one just turned off with inertia glides on from its recent speed; a gliding one slows
    /// down by its glide time and curve.
    fn coast(&mut self, c: &GyroConfig, yaw: f32, pitch: f32, roll: f32, axes: u8, dt: f32) {
        let dt_ms = dt * 1000.0;
        for i in 0..3 {
            let bit = 1u8 << i;
            self.glide_out[i] = 0.0;
            if axes & bit != 0 {
                // A short EMA, so one noisy sample does not set the glide.
                let k = if dt_ms <= 0.0 { 0.0 } else { 1.0 - exp(-dt_ms / Self::SPEED_TAU_MS) };
                let m = match i {
                    0 => yaw,
                    1 => pitch,
                    _ => roll,
                };
                self.speed[i] += k * (m - self.speed[i]);
                self.glide_v[i] = 0.0;
                continue;
            }
            if self.last_axes & bit != 0 {
                // Just turned off: glides on from its recent speed, this sample at that speed.
                self.glide_v[i] = 0.0;
                if c.inertia[i] && self.speed[i].abs() >= Self::GLIDE_MIN_DEG_PER_S {
                    let tau = coerce_in(c.glide_ms[i], Self::MIN_GLIDE_MS, Self::MAX_GLIDE_MS);
                    self.glide_v[i] = self.speed[i];
                    self.glide_tau[i] = tau;
                    // Linear: stops after 2·T, so it turns as far as the smooth one.
                    self.glide_dec[i] = if c.linear[i] { self.speed[i].abs() / (2.0 * tau) } else { 0.0 };
                    self.glide_out[i] = self.speed[i];
                }
                self.speed[i] = 0.0;
                continue;
            }
            let v = self.glide_v[i];
            if v == 0.0 || dt_ms <= 0.0 {
                continue;
            }
            // The sample moves by the glide's mean speed over it.
            let next;
            if self.glide_dec[i] == 0.0 {
                let decay = exp(-dt_ms / self.glide_tau[i]);
                next = v * decay;
                self.glide_out[i] = v * self.glide_tau[i] * (1.0 - decay) / dt_ms;
            } else {
                let t = min_nan(dt_ms, v.abs() / self.glide_dec[i]);
                next = (v.abs() - self.glide_dec[i] * t) * sign(v);
                self.glide_out[i] = (v + next) / 2.0 * t / dt_ms;
            }
            self.glide_v[i] = if next.abs() < Self::GLIDE_STOP_DEG_PER_S { 0.0 } else { next };
        }
        self.last_axes = axes;
    }
}

/// The gain of a curve of `n` points (speeds and gains interleaved, speeds rising) at `speed`,
/// straight or `smooth`.
pub fn gain(c: &[f32; MAX_CURVE_POINTS * 2], n: usize, speed: f32, smooth: bool) -> f32 {
    let n = n.min(MAX_CURVE_POINTS);
    if n == 0 {
        return 1.0;
    }
    if speed <= c[0] {
        return c[1];
    }
    for j in 1..n {
        let s1 = c[2 * j];
        if speed <= s1 {
            let s0 = c[2 * j - 2];
            let g0 = c[2 * j - 1];
            let g1 = c[2 * j + 1];
            let h = s1 - s0;
            let t = (speed - s0) / h;
            if !smooth {
                return g0 + (g1 - g0) * t;
            }
            let m0 = slope(c, n, j - 1);
            let m1 = slope(c, n, j);
            let u = 1.0 - t;
            let h00 = (1.0 + 2.0 * t) * u * u;
            let h10 = t * u * u;
            let h01 = t * t * (3.0 - 2.0 * t);
            let h11 = t * t * (t - 1.0);
            return h00 * g0 + h10 * h * m0 + h01 * g1 + h11 * h * m1;
        }
    }
    c[2 * n - 1]
}

/// The smooth curve's slope at point `k` of `n`.
fn slope(c: &[f32; MAX_CURVE_POINTS * 2], n: usize, k: usize) -> f32 {
    if k == 0 || k == n - 1 {
        return 0.0;
    }
    let d0 = (c[2 * k + 1] - c[2 * k - 1]) / (c[2 * k] - c[2 * k - 2]);
    let d1 = (c[2 * k + 3] - c[2 * k + 1]) / (c[2 * k + 2] - c[2 * k]);
    if d0 * d1 <= 0.0 {
        return 0.0;
    }
    2.0 * d0 * d1 / (d0 + d1)
}

// Kotlin's rounding and helpers, exactly.

/// Kotlin Float.roundToInt (floor(x + 0.5) in double, saturating); NaN gives 0.
pub fn round_to_int(x: f32) -> i32 {
    // `as` saturates and turns NaN into 0, which is what the Kotlin and C# helpers spell out.
    libm::floor(x as f64 + 0.5) as i32
}

/// Kotlin Float.toInt: toward zero, saturating, NaN = 0.
pub fn to_int(x: f32) -> i32 {
    x as i32
}

/// kotlin.math.exp(Float): computed in double, then rounded to float. `libm` rather than the
/// platform's exp, so a test here gives the bits the PC will.
fn exp(x: f32) -> f32 {
    libm::exp(x as f64) as f32
}

/// kotlin.math.sign(Float): ±1, and 0 (keeping its sign) or NaN as they are.
fn sign(x: f32) -> f32 {
    if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        x
    }
}

/// Kotlin coerceIn: NaN passes through.
fn coerce_in(x: f32, min: f32, max: f32) -> f32 {
    if x < min {
        min
    } else if x > max {
        max
    } else {
        x
    }
}

/// Kotlin coerceAtMost: NaN passes through.
fn coerce_at_most(x: f32, max: f32) -> f32 {
    if x > max {
        max
    } else {
        x
    }
}

/// Kotlin's and .NET's min: NaN when either is NaN (Rust's `f32::min` would drop the NaN).
fn min_nan(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else {
        a.min(b)
    }
}

// =====================================================================================
// gyro spaces
// =====================================================================================

const FLAT_MIN: f32 = 0.1;
const FLAT_FULL: f32 = 0.35;
const PLAYER_RELAX: f32 = 1.41;

/// A sample (the phone's own yaw, pitch, roll in deg/s) measured in `space` against the world's up
/// in the phone's frame (right, up, toward the player, unit length). Local, or no up (all 0),
/// leaves it as it is.
pub fn in_space(yaw: f32, pitch: f32, roll: f32, space: u8, ux: f32, uy: f32, uz: f32) -> [f32; 3] {
    let mut o = [yaw, pitch, roll];
    if space == SPACE_LOCAL || (ux == 0.0 && uy == 0.0 && uz == 0.0) {
        return o;
    }
    let (wr, wu, wt) = (pitch, -yaw, -roll);
    if space == SPACE_WORLD {
        o[2] = -(wr * ux + wu * uy + wt * uz);
        let (mut rx, mut ry, mut rz) = (1.0 - ux * ux, -ux * uy, -ux * uz);
        let n = (rx * rx + ry * ry + rz * rz).sqrt();
        let b = coerce_in((n - FLAT_MIN) / (FLAT_FULL - FLAT_MIN), 0.0, 1.0);
        if b <= 0.0 {
            return o;
        }
        rx /= n;
        ry /= n;
        rz /= n;
        let (tx, ty, tz) = (ry * uz - rz * uy, rz * ux - rx * uz, rx * uy - ry * ux);
        let wy = wr * tx + wu * ty + wt * tz;
        let wp = wr * rx + wu * ry + wt * rz;
        o[0] = yaw + (wy - yaw) * b;
        o[1] = pitch + (wp - pitch) * b;
    } else if space == SPACE_PLAYER {
        let world = -(wu * uy + wt * uz);
        let most = (wu * wu + wt * wt).sqrt();
        o[2] = sign(world) * min_nan(world.abs() * PLAYER_RELAX, most);
    }
    o
}

/// Each axis measured in its own space: yaw from the sample in yaw's space, pitch in pitch's, roll in roll's.
pub fn in_spaces(yaw: f32, pitch: f32, roll: f32, spaces: &[u8; 3], ux: f32, uy: f32, uz: f32) -> [f32; 3] {
    let mut o = [0f32; 3];
    for a in 0..3 {
        o[a] = in_space(yaw, pitch, roll, spaces[a], ux, uy, uz)[a];
    }
    o
}

// =====================================================================================
// the STATE_GYRO tail
// =====================================================================================

const SAMPLE_OFF: usize = 8;
const SAMPLE_SIZE: usize = 15;
const TOTALS_OFF: usize = SAMPLE_OFF + MAX_SAMPLES * SAMPLE_SIZE;
const CONFIG_OFF: usize = TOTALS_OFF + 24;
const _: () = assert!(CONFIG_OFF + wire::PC_GYRO_CONFIG_SIZE == wire::PC_GYRO_TAIL_SIZE);

/// The STATE_GYRO tail after STATE_HIRES: the phone's raw gyro samples and its resolved settings.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct PcGyroTail {
    pub flags: u8,
    /// Samples in this packet, oldest first (0..=8).
    pub count: usize,
    /// The newest sample's seq (wrapping); the one before it is one less.
    pub newest_seq: u16,
    /// The newest sample's time, µs (low 32 bits; host clock with `FLAG_CLOCK`).
    pub newest_time_us: u32,
    /// Per sample: µs since the sample before it (0 for the first after a stop).
    pub dt_us: [u16; MAX_SAMPLES],
    /// [sample][axis]: yaw, pitch, roll in 1/16 deg/s.
    pub rate: [[i16; 3]; MAX_SAMPLES],
    /// Per sample: the phone's gyro axes on (bits 0..2).
    pub axes: [u8; MAX_SAMPLES],
    /// [sample][k]: the world's up in the phone's frame in 1/16384; all 0 = not known.
    pub up: [[i16; 3]; MAX_SAMPLES],
    /// Per axis: running total of rate × dt µs over every sample sent this session (wrapping).
    pub totals: [i64; 3],
    /// The phone's current resolved gyro settings.
    pub config: GyroConfig,
    /// Global jitter tightening and smoothing thresholds (deg/s, 0 = off).
    pub tightening: f32,
    pub smoothing: f32,
}

impl PcGyroTail {
    /// Rates are sent in 1/16 deg/s.
    pub const RATE_SCALE: f32 = 16.0;
    /// The up vector is sent in 1/16384.
    pub const UP_SCALE: f32 = 16384.0;
    /// The gyro may move (input allowed, not measuring, gyro on, samples flowing).
    pub const FLAG_MAY_MOVE: u8 = 1;
    /// Sample times are host time (phone time plus the clock sync offset); else phone time.
    pub const FLAG_CLOCK: u8 = 2;

    pub fn may_move(&self) -> bool {
        self.flags & Self::FLAG_MAY_MOVE != 0
    }

    pub fn clock(&self) -> bool {
        self.flags & Self::FLAG_CLOCK != 0
    }

    /// Reads `wire::PC_GYRO_TAIL_SIZE` bytes.
    pub fn decode(&mut self, b: &[u8]) {
        self.flags = b[0];
        self.count = (b[1] as usize).min(MAX_SAMPLES);
        self.newest_seq = le::u16(b, 2);
        self.newest_time_us = le::u32(b, 4);
        for i in 0..MAX_SAMPLES {
            let o = SAMPLE_OFF + i * SAMPLE_SIZE;
            self.dt_us[i] = le::u16(b, o);
            for a in 0..3 {
                self.rate[i][a] = le::i16(b, o + 2 + a * 2);
                self.up[i][a] = le::i16(b, o + 9 + a * 2);
            }
            self.axes[i] = b[o + 8] & 7;
        }
        for a in 0..3 {
            self.totals[a] = le::i64(b, TOTALS_OFF + a * 8);
        }
        let (t, s) = self.config.decode(&b[CONFIG_OFF..CONFIG_OFF + wire::PC_GYRO_CONFIG_SIZE]);
        self.tightening = t;
        self.smoothing = s;
    }

    /// Writes `wire::PC_GYRO_TAIL_SIZE` bytes (tests and tools).
    pub fn encode(&self, b: &mut [u8]) {
        b[..wire::PC_GYRO_TAIL_SIZE].fill(0);
        b[0] = self.flags;
        b[1] = self.count.min(MAX_SAMPLES) as u8;
        le::put_u16(b, 2, self.newest_seq);
        le::put_u32(b, 4, self.newest_time_us);
        for i in 0..self.count.min(MAX_SAMPLES) {
            let o = SAMPLE_OFF + i * SAMPLE_SIZE;
            le::put_u16(b, o, self.dt_us[i]);
            for a in 0..3 {
                le::put_i16(b, o + 2 + a * 2, self.rate[i][a]);
                le::put_i16(b, o + 9 + a * 2, self.up[i][a]);
            }
            b[o + 8] = self.axes[i];
        }
        for a in 0..3 {
            le::put_i64(b, TOTALS_OFF + a * 8, self.totals[a]);
        }
        self.config.encode(&mut b[CONFIG_OFF..], self.tightening, self.smoothing);
    }
}

// =====================================================================================
// the PC controller's ratchets
// =====================================================================================

pub const PLACES: usize = 18;

/// PAD_GYRO: the phone's controller buttons that ratchet or toggle the gyro, by place (bit index
/// 0 = south .. 17 = D-pad right).
#[derive(Clone, Debug)]
pub struct PadGyroConfig {
    /// "Use PC controller for gyro ratchet" is on at the phone.
    pub enabled: bool,
    /// Changes when the phone's own Toggle gyro resets (workspace or its gyro changed).
    pub epoch: u8,
    /// By place: the axes its ratchets flip (bits 0..2), `KIND_TOGGLE` and `KIND_SETTINGS`.
    pub kind: [u8; PLACES],
    /// By place: the gyro settings to use while that button is the last pressed of those held that
    /// have their own; `has_held` says its PAD_GYRO_SET came.
    held: [GyroConfig; PLACES],
    has_held: [bool; PLACES],
}

impl Default for PadGyroConfig {
    fn default() -> Self {
        PadGyroConfig { enabled: false, epoch: 0, kind: [0; PLACES], held: std::array::from_fn(|_| GyroConfig::default()), has_held: [false; PLACES] }
    }
}

impl PadGyroConfig {
    pub const FLAG_ENABLED: u8 = 1;
    /// A kind bit: the button toggles the gyro (the low 3 bits are the axes its ratchets flip).
    pub const KIND_TOGGLE: u8 = 8;
    /// A kind bit: the button has gyro settings of its own while held (from PAD_GYRO_SET).
    pub const KIND_SETTINGS: u8 = 16;

    /// The places that do something, as a mask of place bits.
    pub fn used(&self) -> u32 {
        let mut m = 0;
        for (i, k) in self.kind.iter().enumerate() {
            if *k != 0 {
                m |= 1 << i;
            }
        }
        m
    }

    /// PAD_GYRO_SET (`wire::PAD_GYRO_SET_PLAIN` bytes): u8 place, u8 0, then a config block.
    pub fn decode_held(&mut self, b: &[u8]) {
        let place = b[0] as usize;
        if place >= PLACES {
            return;
        }
        self.held[place].decode(&b[2..2 + wire::PC_GYRO_CONFIG_SIZE]);
        self.has_held[place] = true;
    }

    /// The settings of `place` while held, or None: none (any more).
    pub fn held_config(&self, place: usize) -> Option<&GyroConfig> {
        if place < PLACES && self.kind[place] & Self::KIND_SETTINGS != 0 && self.has_held[place] {
            Some(&self.held[place])
        } else {
            None
        }
    }

    /// PAD_GYRO (`wire::PAD_GYRO_PLAIN` bytes).
    pub fn decode(&mut self, b: &[u8]) {
        self.enabled = b[0] & Self::FLAG_ENABLED != 0;
        self.epoch = b[1];
        let n = (b[2] as usize).min(wire::PAD_GYRO_MAX_BUTTONS);
        self.kind = [0; PLACES];
        for i in 0..n {
            let place = b[4 + i * 2] as usize;
            if place < PLACES {
                self.kind[place] = b[5 + i * 2] & 0x1F;
            }
        }
    }

    /// Writes `wire::PAD_GYRO_PLAIN` bytes (tests and tools).
    pub fn encode(&self, b: &mut [u8]) -> usize {
        b[..wire::PAD_GYRO_PLAIN].fill(0);
        b[0] = if self.enabled { Self::FLAG_ENABLED } else { 0 };
        b[1] = self.epoch;
        let mut n = 0;
        for i in 0..PLACES {
            if self.kind[i] == 0 {
                continue;
            }
            b[4 + n * 2] = i as u8;
            b[5 + n * 2] = self.kind[i];
            n += 1;
        }
        for i in n..wire::PAD_GYRO_MAX_BUTTONS {
            b[4 + i * 2] = 0xFF;
            b[5 + i * 2] = 0xFF;
        }
        b[2] = n as u8;
        wire::PAD_GYRO_PLAIN
    }
}

/// A controller plugged into the host, read for the gyro: buttons held by place and how many times
/// each was pressed (wrapping), so no short press between two STATEs is lost.
#[derive(Clone, Copy, Debug)]
pub struct PcPadState<'a> {
    pub connected: bool,
    pub held: u32,
    /// Press counts by place (wrapping): a change is a press. None: not read.
    pub presses: Option<&'a [u32; PLACES]>,
}

/// Reads a controller plugged into the host for "Use PC controller for gyro ratchet": which gyro
/// axes its held ratchets flip, its Toggle gyro presses and its ratchet presses.
#[derive(Clone, Debug)]
pub struct GyroGate {
    seen_presses: [u32; PLACES],
    seen_valid: bool,
    epoch: i32,
    toggled: bool,
    flips: u8,
    ratchet_presses: i32,
}

impl Default for GyroGate {
    fn default() -> Self {
        GyroGate { seen_presses: [0; PLACES], seen_valid: false, epoch: -1, toggled: false, flips: 0, ratchet_presses: 0 }
    }
}

impl GyroGate {
    /// The PC controller's Toggle gyro presses flip every axis (odd count).
    pub fn toggled(&self) -> bool {
        self.toggled
    }

    /// Axes the PC controller's held ratchets flip now (bits 0..2).
    pub fn flips(&self) -> u8 {
        self.flips
    }

    /// PC ratchet presses so far (wrapping), for the overlays' "On ratchet".
    pub fn ratchet_presses(&self) -> i32 {
        self.ratchet_presses
    }

    /// A new session with another phone: the toggle is off and the epoch unknown.
    pub fn reset(&mut self) {
        self.clear_pc();
        self.epoch = -1;
    }

    /// The PC controller is no longer read: its toggle and held ratchets flip nothing.
    pub fn clear_pc(&mut self) {
        self.seen_valid = false;
        self.toggled = false;
        self.flips = 0;
    }

    /// Reads the PC controller against `cfg`: held ratchets' flips, Toggle gyro presses since the
    /// last call, ratchet presses.
    pub fn update(&mut self, pad: &PcPadState, cfg: &PadGyroConfig) {
        if cfg.epoch as i32 != self.epoch {
            self.epoch = cfg.epoch as i32;
            self.toggled = false;
        }
        let presses = match pad.presses {
            Some(p) if cfg.enabled && pad.connected => p,
            _ => {
                self.flips = 0;
                self.seen_valid = false;
                return;
            }
        };
        for i in 0..PLACES {
            let kind = cfg.kind[i];
            if self.seen_valid && kind != 0 {
                let n = presses[i].wrapping_sub(self.seen_presses[i]);
                if kind & PadGyroConfig::KIND_TOGGLE != 0 && n & 1 != 0 {
                    self.toggled = !self.toggled;
                }
                if kind & 7 != 0 {
                    self.ratchet_presses = self.ratchet_presses.wrapping_add(n as i32);
                }
            }
            self.seen_presses[i] = presses[i];
        }
        self.seen_valid = true;
        self.flips = Self::flips_of(pad.held, cfg);
    }

    /// The axes the ratchets in `held` (place bits) flip, by `cfg`.
    pub fn flips_of(held: u32, cfg: &PadGyroConfig) -> u8 {
        if !cfg.enabled || held == 0 {
            return 0;
        }
        let mut flips = 0;
        for i in 0..PLACES {
            if held & (1 << i) != 0 {
                flips ^= cfg.kind[i] & 7;
            }
        }
        flips
    }

    /// The axes on for phone axes `phone_axes` after the PC controller.
    pub fn effective_of(&self, phone_axes: u8, may_move: bool) -> u8 {
        if may_move {
            (phone_axes ^ self.flips ^ if self.toggled { 7 } else { 0 }) & 7
        } else {
            0
        }
    }
}

/// The buttons a controller held, by time: each change as (host µs, place bits), so the PC gyro can
/// ask what was held when a sample was measured. Keeps the last `CAPACITY` changes. Not thread safe:
/// the owner locks.
#[derive(Clone, Debug)]
pub struct PadHeldHistory {
    us: [i64; Self::CAPACITY],
    held: [u32; Self::CAPACITY],
    count: usize,
    next: usize,
}

impl Default for PadHeldHistory {
    fn default() -> Self {
        PadHeldHistory { us: [0; Self::CAPACITY], held: [0; Self::CAPACITY], count: 0, next: 0 }
    }
}

impl PadHeldHistory {
    pub const CAPACITY: usize = 64;

    /// Notes that `held` is held from `us` on (nothing when it is no change).
    pub fn add(&mut self, mut us: i64, held: u32) {
        if self.count > 0 {
            let last = (self.next + Self::CAPACITY - 1) % Self::CAPACITY;
            if self.held[last] == held {
                return;
            }
            if us < self.us[last] {
                us = self.us[last]; // never back in time
            }
        }
        self.us[self.next] = us;
        self.held[self.next] = held;
        self.next = (self.next + 1) % Self::CAPACITY;
        if self.count < Self::CAPACITY {
            self.count += 1;
        }
    }

    /// What was held at `us`: the last change at or before it; before every change kept, the oldest
    /// kept; nothing when none was noted.
    pub fn at(&self, us: i64) -> u32 {
        if self.count == 0 {
            return 0;
        }
        let mut idx = (self.next + Self::CAPACITY - 1) % Self::CAPACITY;
        for k in 0..self.count {
            if self.us[idx] <= us {
                return self.held[idx];
            }
            if k < self.count - 1 {
                idx = (idx + Self::CAPACITY - 1) % Self::CAPACITY;
            }
        }
        self.held[idx]
    }

    pub fn clear(&mut self) {
        self.count = 0;
        self.next = 0;
    }
}

/// What a controller's buttons were at a host time (the PC controller reader answers this).
pub trait HeldAt {
    fn held_at(&self, us: i64) -> u32;
}

impl HeldAt for PadHeldHistory {
    fn held_at(&self, us: i64) -> u32 {
        self.at(us)
    }
}

// =====================================================================================
// the gyro run on the PC
// =====================================================================================

/// After this long without a STATE_GYRO, missing samples are not filled in: the host has let go of input meanwhile.
const STALE_US: i64 = wire::RELEASE_TIMEOUT_MS * 1000;
/// A gap longer than this (or with times from another clock) gets a guessed length.
const MAX_GAP_US: i64 = 1_000_000;
const GUESS_DT_US: i64 = 1000;
/// A packet this many samples behind the last one run is not late but from a phone counting again.
const RESTART_SEQS: i32 = 1024;

/// Takes each STATE_GYRO's raw samples once, by seq, fills a gap from the running totals, works out
/// per sample which axes are on (the phone's, flipped by the PC controller's ratchets held at that
/// sample's time and by its Toggle gyro), measures each sample in the gyro space of each axis, runs
/// `GyroMath` with the phone's settings and puts the result into the frame: mouse and wheel into its
/// wrapping totals, the game pad axes merged into its pad the way the phone merges them. Frames
/// without the tail keep the totals offset, so the cursor never jumps back. One per session.
#[derive(Clone, Debug, Default)]
pub struct PcGyro {
    math: GyroMath,
    pad: [f32; PAD_AXES],
    own: [i64; 3],
    host_us: [i64; MAX_SAMPLES],
    has_base: bool,
    last_seq: u16,
    last_time: u32,
    last_clock: bool,
    last_rx_us: i64,
    adj_x: i32,
    adj_y: i32,
    adj_w: i32,
    adj_h: i32,
    // PC controller buttons with gyro settings of their own, held now, in the order they were pressed.
    held_order: [u8; PLACES],
    held_order_n: usize,
    may_move: bool,
    effective: u8,
    phone_axes: u8,
    samples: u32,
    gap_fills: u32,
}

impl PcGyro {
    /// The last STATE_GYRO said the gyro may move.
    pub fn may_move(&self) -> bool {
        self.may_move
    }

    /// The gyro axes on (bits 0..2) at the last sample run, after the PC controller.
    pub fn effective(&self) -> u8 {
        self.effective
    }

    /// The newest STATE_GYRO's phone axes (bits 0..2), before the PC controller.
    pub fn phone_axes(&self) -> u8 {
        self.phone_axes
    }

    /// Samples run so far, and synthetic ones made for gaps (both wrapping; for tests).
    pub fn samples(&self) -> u32 {
        self.samples
    }

    pub fn gap_fills(&self) -> u32 {
        self.gap_fills
    }

    /// A new session with another phone: nothing carries over.
    pub fn reset(&mut self) {
        self.rebase();
        self.may_move = false;
        self.effective = 0;
        self.phone_axes = 0;
    }

    /// The same phone in a new session: its seq and totals start again, and so do the STATE totals.
    pub fn rebase(&mut self) {
        self.has_base = false;
        self.adj_x = 0;
        self.adj_y = 0;
        self.adj_w = 0;
        self.adj_h = 0;
        self.math.reset();
        self.pad = [0.0; PAD_AXES];
    }

    /// Runs the new samples of `f` (a STATE, STATE_HIRES or STATE_GYRO received at host time
    /// `now_us`) and puts the gyro's output into it. `gate` gives the PC controller's toggle (as
    /// read now), `cfg` its ratchet buttons and `held_at` its buttons held at a host time (None: no
    /// PC controller is read).
    pub fn apply(&mut self, f: &mut StateFrame, now_us: i64, gate: &GyroGate, cfg: &PadGyroConfig, held_at: Option<&dyn HeldAt>) {
        if !f.has_pc_gyro {
            // The PC no longer runs the gyro: drop its glide and smoothing, so turning the mode on again starts clean.
            self.math.reset();
            self.pad = [0.0; PAD_AXES];
            self.effective = 0;
            self.may_move = false;
            self.offset(f);
            return;
        }
        self.may_move = f.tail.may_move();
        self.phone_axes = if f.tail.count > 0 { f.tail.axes[f.tail.count - 1] } else { 0 };
        if !f.tail.may_move() {
            // Nothing moves; the samples are only remembered, so they are not run later.
            self.math.reset();
            self.pad = [0.0; PAD_AXES];
            self.effective = 0;
            self.remember(&f.tail, now_us);
        } else {
            self.run(&f.tail, now_us, gate, cfg, held_at);
        }
        self.offset(f);
        self.merge_pad(f);
        f.flags = if self.effective != 0 { f.flags | wire::state_flags::GYRO_ACTIVE } else { f.flags & !wire::state_flags::GYRO_ACTIVE };
    }

    fn run(&mut self, t: &PcGyroTail, now_us: i64, gate: &GyroGate, cfg: &PadGyroConfig, held_at: Option<&dyn HeldAt>) {
        let n = t.count as i32;
        let mut ahead = if self.has_base { t.newest_seq.wrapping_sub(self.last_seq) as i16 as i32 } else { n };
        if ahead < -RESTART_SEQS {
            // Far behind what was run: the phone counts from the start again. Its samples are a new base.
            self.has_base = false;
            ahead = n;
        }
        if ahead <= 0 {
            return; // a repeat or an older packet: nothing new
        }
        let stale = now_us.wrapping_sub(self.last_rx_us) > STALE_US;
        if self.has_base && stale {
            // Nothing new for so long that the host let go of input: no glide or smoothing carries over the silence.
            self.math.reset();
            self.pad = [0.0; PAD_AXES];
        }
        let first = (n - ahead).max(0) as usize;
        let n = n as usize;
        let toggle = if gate.toggled() { 7 } else { 0 };
        self.host_times(t, now_us);

        let mut sum = [0i64; 3];
        for i in first..n {
            for a in 0..3 {
                sum[a] = sum[a].wrapping_add(t.rate[i][a] as i64 * t.dt_us[i] as i64);
            }
        }
        if !self.has_base {
            // New base: every sample here is new, and the totals before them are what the phone sent before.
            for a in 0..3 {
                self.own[a] = t.totals[a].wrapping_sub(sum[a]);
            }
        } else if ahead as usize > n && !stale {
            // Samples were lost: one sample carries the angle they turned, over the time they took.
            let y = t.totals[0].wrapping_sub(self.own[0]).wrapping_sub(sum[0]);
            let p = t.totals[1].wrapping_sub(self.own[1]).wrapping_sub(sum[1]);
            let r = t.totals[2].wrapping_sub(self.own[2]).wrapping_sub(sum[2]);
            if y != 0 || p != 0 || r != 0 {
                let gap = self.gap_us(t, first, ahead as i64 - n as i64);
                let at = if n > first { self.host_us[first] - t.dt_us[first] as i64 } else { now_us };
                let axes = if n > first { t.axes[first] } else { self.phone_axes };
                let dt_us = gap as f32;
                let up_at = if n > first { Some(first) } else { n.checked_sub(1) };
                self.sample(y as f32 / dt_us, p as f32 / dt_us, r as f32 / dt_us, gap as f32 / 1e6, axes, at, toggle, t, cfg, held_at, up_at);
                self.gap_fills = self.gap_fills.wrapping_add(1);
            }
        }
        for i in first..n {
            self.sample(t.rate[i][0] as f32, t.rate[i][1] as f32, t.rate[i][2] as f32, t.dt_us[i] as f32 / 1e6, t.axes[i], self.host_us[i], toggle, t, cfg, held_at, Some(i));
        }
        self.remember(t, now_us);
    }

    /// One sample: rates in 1/16 deg/s about the phone's own axes over `dt` seconds, the phone's
    /// `phone_axes`, at host time `at_us`, with the up of tail sample `up_at` (None: none).
    #[allow(clippy::too_many_arguments)]
    fn sample(&mut self, yaw_q: f32, pitch_q: f32, roll_q: f32, dt: f32, phone_axes: u8, at_us: i64, toggle: u8, t: &PcGyroTail, cfg: &PadGyroConfig, held_at: Option<&dyn HeldAt>, up_at: Option<usize>) {
        let held = held_at.map_or(0, |h| h.held_at(at_us));
        let flips = if held_at.is_some() { GyroGate::flips_of(held, cfg) } else { 0 };
        let config = self.held_config(held, cfg).unwrap_or(&t.config);
        let eff = (phone_axes ^ flips ^ toggle) & 7;
        self.effective = eff;
        self.samples = self.samples.wrapping_add(1);
        if eff == 0 && !self.math.coasting() {
            self.math.reset();
            self.pad = [0.0; PAD_AXES];
            return;
        }
        let (mut ux, mut uy, mut uz) = (0f32, 0f32, 0f32);
        if let Some(u) = up_at {
            ux = t.up[u][0] as f32 / PcGyroTail::UP_SCALE;
            uy = t.up[u][1] as f32 / PcGyroTail::UP_SCALE;
            uz = t.up[u][2] as f32 / PcGyroTail::UP_SCALE;
        }
        // Each axis in its own space, as the phone measures it.
        let s = in_spaces(yaw_q / PcGyroTail::RATE_SCALE, pitch_q / PcGyroTail::RATE_SCALE, roll_q / PcGyroTail::RATE_SCALE, &config.space, ux, uy, uz);
        let o = self.math.step(s[0], s[1], s[2], dt, config, eff, t.tightening, t.smoothing);
        self.adj_x = self.adj_x.wrapping_add(o.dx);
        self.adj_y = self.adj_y.wrapping_add(o.dy);
        self.adj_w = self.adj_w.wrapping_add(o.wheel);
        self.adj_h = self.adj_h.wrapping_add(o.hwheel);
        self.pad = o.pad;
    }

    /// The settings of the last pressed of the PC controller buttons in `held` that have their own,
    /// or None: the phone's.
    fn held_config<'a>(&mut self, held: u32, cfg: &'a PadGyroConfig) -> Option<&'a GyroConfig> {
        let mut k = self.held_order_n;
        while k > 0 {
            k -= 1;
            let p = self.held_order[k] as usize;
            if held & (1 << p) == 0 || cfg.held_config(p).is_none() {
                self.held_order.copy_within(k + 1..self.held_order_n, k);
                self.held_order_n -= 1;
            }
        }
        for p in 0..PLACES {
            if held & (1 << p) != 0 && cfg.held_config(p).is_some() && !self.held_order[..self.held_order_n].contains(&(p as u8)) {
                // At most PLACES distinct places, so there is always room.
                self.held_order[self.held_order_n] = p as u8;
                self.held_order_n += 1;
            }
        }
        if self.held_order_n == 0 {
            None
        } else {
            cfg.held_config(self.held_order[self.held_order_n - 1] as usize)
        }
    }

    /// Host time of each sample: its own clock's when the phone synced, else by arrival.
    fn host_times(&mut self, t: &PcGyroTail, now_us: i64) {
        let newest = if t.clock() { now_us.wrapping_add(t.newest_time_us.wrapping_sub(now_us as u32) as i32 as i64) } else { now_us };
        let mut back = 0i64;
        for i in (0..t.count).rev() {
            self.host_us[i] = newest - back;
            back += t.dt_us[i] as i64;
        }
    }

    /// The µs between the last sample run and the first new one's start (where the lost ones ran), by
    /// the sample times; guessed from the samples' spacing when the times cannot tell.
    fn gap_us(&self, t: &PcGyroTail, first: usize, lost: i64) -> i64 {
        if t.clock() == self.last_clock && t.count > first {
            let first_start = time_of(t, first).wrapping_sub(t.dt_us[first] as u32);
            let gap = first_start.wrapping_sub(self.last_time) as i32 as i64;
            if gap > 0 && gap <= MAX_GAP_US {
                return gap;
            }
        }
        let spacing = if t.count > first && t.dt_us[first] > 0 { t.dt_us[first] as i64 } else { GUESS_DT_US };
        lost * spacing
    }

    fn remember(&mut self, t: &PcGyroTail, now_us: i64) {
        if self.has_base && (t.newest_seq.wrapping_sub(self.last_seq) as i16) <= 0 {
            return; // older than what was run: keep the newer base
        }
        self.has_base = true;
        self.last_seq = t.newest_seq;
        self.last_time = t.newest_time_us;
        self.last_clock = t.clock();
        self.last_rx_us = now_us;
        self.own = t.totals;
    }

    fn offset(&self, f: &mut StateFrame) {
        f.mouse_total_x = f.mouse_total_x.wrapping_add(self.adj_x);
        f.mouse_total_y = f.mouse_total_y.wrapping_add(self.adj_y);
        f.wheel_total = f.wheel_total.wrapping_add(self.adj_w);
        f.hwheel_total = f.hwheel_total.wrapping_add(self.adj_h);
    }

    /// The gyro's pad over the frame's (which carries only the rest in this mode), like the phone merges them.
    fn merge_pad(&self, f: &mut StateFrame) {
        f.lx = stick(f.lx, self.pad[0]);
        f.ly = stick(f.ly, self.pad[1]);
        f.rx = stick(f.rx, self.pad[2]);
        f.ry = stick(f.ry, self.pad[3]);
        f.lt = trigger(f.lt, self.pad[4]);
        f.rt = trigger(f.rt, self.pad[5]);
    }
}

/// Sample `i`'s time in the tail's own clock (wrapping).
fn time_of(t: &PcGyroTail, i: usize) -> u32 {
    let mut time = t.newest_time_us;
    let mut k = t.count;
    while k > i + 1 {
        k -= 1;
        time = time.wrapping_sub(t.dt_us[k] as u32);
    }
    time
}

// Like the phone: the gyro's push adds to the stick's and trigger's own, up to full. A NaN push
// (settings no phone sends) counts as none.
fn stick(other: i16, g: f32) -> i16 {
    let v = (coerce_in(g, -1.0, 1.0) * 32767.0) as i32;
    (v + other as i32).clamp(-32767, 32767) as i16
}

fn trigger(other: u16, g: f32) -> u16 {
    let v = (coerce_at_most(g.abs(), 1.0) * 1023.0) as i32;
    (v + other as i32).clamp(0, 1023) as u16
}

impl<F: Fn(i64) -> u32> HeldAt for F {
    fn held_at(&self, us: i64) -> u32 {
        self(us)
    }
}
