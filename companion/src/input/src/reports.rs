//! The byte reports the outputs take: the DualShock 4 report for ViGEm, the DualSense report for
//! the GyroPhone pad driver, the pad's motion readings made from MOTION packets, and the keyboard,
//! mouse and media reports for the GyroPhone input driver. Ports of Ds4Report.cs, DualSenseReport.cs
//! and HidInputReports.cs.

use crate::le;
use crate::mapping::{self, pad_mapper, Ds4Frame, InjectEvent, InjectKind};
use crate::motion::{self, MotionFrame};

// ---------------------------------------------------------------------------------------------
// Ds4Motion

/// The pad's motion fields: the sample's time (`clock`, in 1/3 µs, wrapping), gyro in 1/16 deg/s
/// and accelerometer in 1/8192 g, in the pad's frame (x right, y out of its face, z toward the
/// player). The DualShock 4 and the DualSense share the frame and these units; the DualSense takes
/// the clock as it is, the DualShock 4 in 16/3 µs (`ds4_timestamp`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ds4Motion {
    pub clock: u32,
    pub gx: i16,
    pub gy: i16,
    pub gz: i16,
    pub ax: i16,
    pub ay: i16,
    pub az: i16,
}

impl Ds4Motion {
    /// Still and flat: no turn, 1 g out of the face.
    pub const NEUTRAL: Ds4Motion =
        Ds4Motion { clock: 0, gx: 0, gy: 0, gz: 0, ax: 0, ay: motion::ACCEL_SCALE as i16, az: 0 };

    /// The DualShock 4's report timestamp: the clock in 16/3 µs, wrapping at 16 bits.
    #[inline]
    pub fn ds4_timestamp(&self) -> u16 {
        (self.clock >> 4) as u16
    }

    /// This reading with the gyro at rest (the accelerometer kept), at `clock`.
    #[inline]
    pub fn resting(&self, clock: u32) -> Ds4Motion {
        Ds4Motion { clock, gx: 0, gy: 0, gz: 0, ..*self }
    }

    #[inline]
    pub fn turning(&self) -> bool {
        self.gx != 0 || self.gy != 0 || self.gz != 0
    }
}

impl Default for Ds4Motion {
    fn default() -> Self {
        Self::NEUTRAL
    }
}

// ---------------------------------------------------------------------------------------------
// Ds4Report

/// The full DualShock 4 input report as ViGEm's DS4_REPORT_EX takes it (63 bytes, no report id):
/// sticks, buttons with the D-pad in the low nibble, special buttons, triggers, timestamp, gyro,
/// accelerometer, battery (on cable, full) and the touchpad with no finger on it.
pub mod ds4_report {
    use super::*;

    pub const SIZE: usize = 63;

    pub fn build(r: &mut [u8; SIZE], f: &Ds4Frame, m: &Ds4Motion) {
        r.fill(0);
        r[0] = f.lx;
        r[1] = f.ly;
        r[2] = f.rx;
        r[3] = f.ry;
        // The hat index (N, NE, ... NW, none) is the DS4 D-pad value.
        let hat = f.hat.min(pad_mapper::DS4_HAT_NONE) as u16;
        le::put_u16(r, 4, (f.buttons & 0xFFF0) | hat);
        r[6] = f.special & 0x03;
        r[7] = f.lt;
        r[8] = f.rt;
        le::put_u16(r, 9, m.ds4_timestamp());
        r[11] = 0xFF;
        le::put_i16(r, 12, m.gx);
        le::put_i16(r, 14, m.gy);
        le::put_i16(r, 16, m.gz);
        le::put_i16(r, 18, m.ax);
        le::put_i16(r, 20, m.ay);
        le::put_i16(r, 22, m.az);
        // Cable connected (bit 4), battery full.
        r[29] = 0x1B;
        // One touch packet; every finger slot of it and the two before says "not touching" (bit 7).
        r[32] = 1;
        for t in 0..3 {
            let o = 33 + t * 9;
            r[o + 1] = 0x80;
            r[o + 5] = 0x80;
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DualSenseReport

/// The wired DualSense's input report 0x01 (64 bytes with its id), as the GyroPhone pad driver
/// (driver/src/dualsense.h) passes it to Windows. Its buttons are the DualShock 4's, so it takes the
/// same `Ds4Frame`: sticks, triggers, the D-pad hat in the low nibble with Square, Cross, Circle,
/// Triangle above it, then L1 R1 L2 R2 Create Options L3 R3, then PS and touchpad click. Gyro and
/// accelerometer go in the DualShock 4's units (1/16 deg/s, 1/8192 g), which the driver's
/// calibration report makes the DualSense's too, and the sample's time in 1/3 µs (the DualSense's
/// sensor timestamp), so a game adds up each turn at the time it was measured. The driver fills in
/// the report counter.
pub mod dualsense_report {
    use super::*;

    pub const SIZE: usize = 64;
    pub const ID: u8 = 0x01;

    pub fn build(r: &mut [u8; SIZE], f: &Ds4Frame, m: &Ds4Motion) {
        r.fill(0);
        r[0] = ID;
        r[1] = f.lx;
        r[2] = f.ly;
        r[3] = f.rx;
        r[4] = f.ry;
        r[5] = f.lt;
        r[6] = f.rt;
        // r[7]: the report counter, set by the driver.
        let hat = f.hat.min(pad_mapper::DS4_HAT_NONE);
        r[8] = (f.buttons & 0xF0) as u8 | hat;
        r[9] = (f.buttons >> 8) as u8;
        r[10] = f.special & 0x03;
        le::put_i16(r, 16, m.gx);
        le::put_i16(r, 18, m.gy);
        le::put_i16(r, 20, m.gz);
        le::put_i16(r, 22, m.ax);
        le::put_i16(r, 24, m.ay);
        le::put_i16(r, 26, m.az);
        le::put_u32(r, 28, m.clock);
        // Both touch points: not touching (bit 7).
        r[33] = 0x80;
        r[37] = 0x80;
        // On cable, charging, battery level 10 of 10.
        r[53] = 0x1A;
    }
}

// ---------------------------------------------------------------------------------------------
// Ds4MotionFeed

/// Turns MOTION packets into the pad's motion (DualShock 4 or DualSense), one reading per new
/// sample, each stamped with its own time so a game adds up the turn exactly. A packet repeats the
/// last few samples: the newest seq says which are new.
#[derive(Clone, Debug)]
pub struct Ds4MotionFeed {
    have_seq: bool,
    seq: u16,
    clock: u32,
    /// Host time of the last reading; `None` before the first.
    last_us: Option<i64>,
    /// Phone time (µs, low 32 bits) of the last sample sent; valid while `time_known`.
    last_phone_us: u32,
    /// The clock stands at `last_phone_us`: no resting reading moved it on by host time since.
    time_known: bool,
    current: Ds4Motion,
}

impl Default for Ds4MotionFeed {
    fn default() -> Self {
        Ds4MotionFeed {
            have_seq: false,
            seq: 0,
            clock: 0,
            last_us: None,
            last_phone_us: 0,
            time_known: false,
            current: Ds4Motion::NEUTRAL,
        }
    }
}

impl Ds4MotionFeed {
    /// The pad's gyro rests when no MOTION came for this long (a lost stop, the phone gone).
    pub const STALE_US: i64 = 60_000;
    /// Lost samples' time the clock catches up at most (a longer gap is a stop, not a loss).
    const MAX_LOST_US: i64 = 1_000_000;

    pub fn new() -> Self {
        Self::default()
    }

    /// The reading the pad shows now.
    pub fn current(&self) -> Ds4Motion {
        self.current
    }

    /// Writes the readings `f` brings, oldest first, into `into` and returns how many; `current` is
    /// the last. With input not allowed on the phone, one resting reading if the gyro was turning.
    pub fn take(&mut self, f: &MotionFrame, into: &mut [Ds4Motion; motion::MAX_SAMPLES], now_us: i64) -> usize {
        if !f.may_move() {
            self.have_seq = false;
            return self.rest(into, now_us);
        }
        let count = f.count.min(motion::MAX_SAMPLES);
        let fresh;
        if !self.have_seq {
            fresh = count;
        } else {
            let diff = f.newest_seq.wrapping_sub(self.seq) as usize;
            // Nothing new, or an older packet arriving late.
            if diff == 0 || diff >= 0x8000 {
                return 0;
            }
            fresh = diff.min(count);
            // More were lost than the packet repeats: the first fresh sample's step covers only the
            // gap from its own lost predecessor, so the lost samples' time comes from the phone's times.
            if diff > count && self.time_known {
                let covered: i64 = f.dt_us[..count].iter().map(|&d| d as i64).sum();
                let lost = f.newest_time_us.wrapping_sub(self.last_phone_us) as i64 - covered;
                if lost > 0 {
                    self.advance(lost.min(Self::MAX_LOST_US) as u32);
                }
            }
        }
        self.have_seq = true;
        self.seq = f.newest_seq;
        self.last_phone_us = f.newest_time_us;
        self.time_known = true;
        self.last_us = Some(now_us);
        let mut n = 0;
        for i in count - fresh..count {
            self.advance(f.dt_us[i] as u32);
            let m = Ds4Motion {
                clock: self.clock,
                gx: f.gyro[i * 3],
                gy: f.gyro[i * 3 + 1],
                gz: f.gyro[i * 3 + 2],
                ax: f.accel[i * 3],
                ay: f.accel[i * 3 + 1],
                az: f.accel[i * 3 + 2],
            };
            into[n] = m;
            n += 1;
            self.current = m;
        }
        n
    }

    /// The gyro stops (no MOTION for a while, input paused): one resting reading if it was turning,
    /// else none.
    pub fn rest(&mut self, into: &mut [Ds4Motion; motion::MAX_SAMPLES], now_us: i64) -> usize {
        if !self.current.turning() {
            return 0;
        }
        let gap = match self.last_us {
            None => 0,
            Some(last) => now_us.saturating_sub(last).clamp(0, 1_000_000),
        };
        self.last_us = Some(now_us);
        self.advance(gap as u32);
        self.time_known = false; // the clock moved by host time: phone times no longer line up with it
        self.current = self.current.resting(self.clock);
        into[0] = self.current;
        1
    }

    /// No MOTION since `STALE_US`: the gyro should rest.
    pub fn is_stale(&self, now_us: i64) -> bool {
        match self.last_us {
            Some(last) => self.current.turning() && now_us.saturating_sub(last) >= Self::STALE_US,
            None => false,
        }
    }

    /// A new session or pad: still and upright, samples counted afresh.
    pub fn reset(&mut self) {
        self.have_seq = false;
        self.time_known = false;
        self.last_us = None;
        self.current = Ds4Motion { clock: self.clock, ..Ds4Motion::NEUTRAL };
    }

    // The clock counts 1/3 µs, the DualSense's unit; the DualShock 4's 16/3 µs is every 16th tick.
    #[inline]
    fn advance(&mut self, us: u32) {
        self.clock = self.clock.wrapping_add(us.wrapping_mul(3));
    }
}

// ---------------------------------------------------------------------------------------------
// HidInputReports

pub const MOUSE_ID: u8 = 1;
pub const KEYBOARD_ID: u8 = 2;
pub const MEDIA_ID: u8 = 3;
pub const MOUSE_SIZE: usize = 10;
pub const KEYBOARD_SIZE: usize = 30;
pub const MEDIA_SIZE: usize = 2;
/// Room for every report one hand-off can need (a release of every key is 2 keyboard reports).
pub const HID_MAX_BYTES: usize = 4096;
const KEY_BYTES: usize = KEYBOARD_SIZE - 1;
const MAX_USAGE: u32 = 0xE7;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    None,
    Keyboard,
    Mouse,
    Media,
}

/// Turns one hand-off's keyboard and mouse events into the GyroPhone input driver's HID input
/// reports (input-driver/src/gpinput_reports.h keeps the same layout), written back to back for one
/// SUBMIT:
///
/// - 1, mouse (10 bytes): id, buttons (bit 0 L, 1 R, 2 M, 3 back, 4 forward), X, Y, wheel,
///   horizontal wheel (int16 LE; wheels in 1/120 notch);
/// - 2, keyboard (30 bytes): id, one bit per keyboard usage 0x00..0xE7, modifiers included;
/// - 3, media keys (2 bytes): id, bit 0 play/pause, 1 previous, 2 next, 3 mute, 4 volume up,
///   5 volume down.
///
/// A report holds the whole state, so events that happen at the same moment share one report: the
/// usual hand-off (a move, maybe a click) is one mouse report. A new report starts only where
/// merging would change what Windows sees: a key or button changing twice, a modifier that must go
/// down before (or up after) other keys, a move before a click, and a switch between keyboard and
/// mouse with presses pending, so the order of presses stays the order `input_diff` wrote them in.
/// Held keys and buttons are remembered between calls.
#[derive(Clone, Debug)]
pub struct HidInputReports {
    // What Windows has been sent.
    keys: [u8; KEY_BYTES],
    buttons: u8,
    media: u8,

    // The report being built, per kind, and what it changed so far.
    pend_keys: [u8; KEY_BYTES],
    pend_buttons: u8,
    pend_media: u8,
    keys_touched: [u8; KEY_BYTES],
    buttons_touched: u8,
    media_touched: u8,
    /// -1: nothing pending; 0 modifier downs, 1 other keys, 2 modifier ups.
    key_phase: i8,
    dx: i32,
    dy: i32,
    wheel: i32,
    hwheel: i32,
    mouse_dirty: bool,
    moved: bool,
    last: Kind,
}

impl Default for HidInputReports {
    fn default() -> Self {
        HidInputReports {
            keys: [0; KEY_BYTES],
            buttons: 0,
            media: 0,
            pend_keys: [0; KEY_BYTES],
            pend_buttons: 0,
            pend_media: 0,
            keys_touched: [0; KEY_BYTES],
            buttons_touched: 0,
            media_touched: 0,
            key_phase: -1,
            dx: 0,
            dy: 0,
            wheel: 0,
            hwheel: 0,
            mouse_dirty: false,
            moved: false,
            last: Kind::None,
        }
    }
}

/// The output of one `build`: bytes written so far into a fixed buffer.
struct Out<'a> {
    buf: &'a mut [u8; HID_MAX_BYTES],
    n: usize,
}

impl Out<'_> {
    #[inline]
    fn room(&self, size: usize) -> bool {
        self.n + size <= HID_MAX_BYTES
    }
}

impl HidInputReports {
    pub fn new() -> Self {
        Self::default()
    }

    /// Keys, media keys and mouse buttons held as far as Windows knows.
    pub fn any_held(&self) -> bool {
        self.buttons != 0 || self.media != 0 || self.keys.iter().any(|&b| b != 0)
    }

    /// Writes the reports for `events` into `output` and returns the bytes written (0 when nothing
    /// changes).
    ///
    /// The buffer holds far more than the engine's hand-offs can need. Should a list of events ever
    /// need more, the reports that no longer fit are not written and not counted as sent, so what
    /// this remembers always matches what Windows was given.
    pub fn build(&mut self, events: &[InjectEvent], output: &mut [u8; HID_MAX_BYTES]) -> usize {
        let mut out = Out { buf: output, n: 0 };
        self.begin_pending();
        for e in events {
            if e.kind == InjectKind::Key {
                self.key(e, &mut out);
            } else {
                self.mouse(e, &mut out);
            }
        }
        self.flush_keyboard(&mut out);
        self.flush_media(&mut out);
        self.flush_mouse(&mut out);
        out.n
    }

    /// Forgets what was sent: the device went away, and Windows let go of everything it held.
    pub fn reset(&mut self) {
        self.keys = [0; KEY_BYTES];
        self.buttons = 0;
        self.media = 0;
        self.begin_pending();
    }

    /// The media key bit of a keyboard usage `hid_scan_codes` sends as a virtual key.
    pub fn media_bit(usage: u32) -> Option<u32> {
        match usage {
            0xE8 => Some(0), // play/pause
            0xEA => Some(1), // previous
            0xEB => Some(2), // next
            0x7F => Some(3), // mute
            0x80 => Some(4), // volume up
            0x81 => Some(5), // volume down
            _ => None,
        }
    }

    fn begin_pending(&mut self) {
        self.pend_keys = self.keys;
        self.keys_touched = [0; KEY_BYTES];
        self.pend_buttons = self.buttons;
        self.pend_media = self.media;
        self.buttons_touched = 0;
        self.media_touched = 0;
        self.key_phase = -1;
        self.dx = 0;
        self.dy = 0;
        self.wheel = 0;
        self.hwheel = 0;
        self.mouse_dirty = false;
        self.moved = false;
        self.last = Kind::None;
    }

    fn key(&mut self, e: &InjectEvent, out: &mut Out) {
        let usage = e.usage as u32;
        let down = !e.is_key_up();
        if let Some(media) = Self::media_bit(usage) {
            self.switch_to(Kind::Media, out);
            let bit = 1u8 << media;
            if self.media_touched & bit != 0 {
                self.flush_media(out);
            }
            if down {
                self.pend_media |= bit;
            } else {
                self.pend_media &= !bit;
            }
            self.media_touched |= bit;
            return;
        }
        if !(4..=MAX_USAGE).contains(&usage) {
            return; // not a key the report has (input_diff never sends one)
        }
        self.switch_to(Kind::Keyboard, out);
        let modifier = usage >= 0xE0;
        let phase: i8 = if modifier {
            if down {
                0
            } else {
                2
            }
        } else {
            1
        };
        let i = (usage >> 3) as usize;
        let mask = 1u8 << (usage & 7);
        // Modifiers go down in a report before the keys they shift and up in one after them;
        // a key changing twice needs a report in between.
        if self.key_phase >= 0 && (phase != self.key_phase || self.keys_touched[i] & mask != 0) {
            self.flush_keyboard(out);
        }
        if down {
            self.pend_keys[i] |= mask;
        } else {
            self.pend_keys[i] &= !mask;
        }
        self.keys_touched[i] |= mask;
        self.key_phase = phase;
    }

    fn mouse(&mut self, e: &InjectEvent, out: &mut Out) {
        let f = e.flags;
        self.switch_to(Kind::Mouse, out);
        if let Some(bit) = button_bit(f, e.mouse_data) {
            let mask = 1u8 << bit;
            // A click after a move, or a button changing twice, goes in its own report.
            if self.moved || self.buttons_touched & mask != 0 {
                self.flush_mouse(out);
            }
            let down = f
                & (mapping::MOUSE_LEFT_DOWN | mapping::MOUSE_RIGHT_DOWN | mapping::MOUSE_MIDDLE_DOWN | mapping::MOUSE_X_DOWN)
                != 0;
            if down {
                self.pend_buttons |= mask;
            } else {
                self.pend_buttons &= !mask;
            }
            self.buttons_touched |= mask;
            self.mouse_dirty = true;
        }
        if f & mapping::MOUSE_MOVE != 0 && (e.dx != 0 || e.dy != 0) {
            if overflows(self.dx, e.dx) || overflows(self.dy, e.dy) {
                self.flush_mouse(out);
            }
            self.dx = self.dx.wrapping_add(e.dx);
            self.dy = self.dy.wrapping_add(e.dy);
            self.moved = true;
            self.mouse_dirty = true;
        }
        if f & mapping::MOUSE_WHEEL != 0 && e.mouse_data != 0 {
            if overflows(self.wheel, e.mouse_data) {
                self.flush_mouse(out);
            }
            self.wheel = self.wheel.wrapping_add(e.mouse_data);
            self.mouse_dirty = true;
        }
        if f & mapping::MOUSE_HWHEEL != 0 && e.mouse_data != 0 {
            if overflows(self.hwheel, e.mouse_data) {
                self.flush_mouse(out);
            }
            self.hwheel = self.hwheel.wrapping_add(e.mouse_data);
            self.mouse_dirty = true;
        }
    }

    /// Presses of another kind are sent first, so presses keep their order across devices.
    fn switch_to(&mut self, kind: Kind, out: &mut Out) {
        if self.last == kind {
            return;
        }
        match self.last {
            Kind::Keyboard => self.flush_keyboard(out),
            Kind::Media => self.flush_media(out),
            Kind::Mouse if self.buttons_touched != 0 => self.flush_mouse(out),
            _ => {}
        }
        self.last = kind;
    }

    fn flush_keyboard(&mut self, out: &mut Out) {
        if self.key_phase < 0 {
            return;
        }
        self.key_phase = -1;
        self.keys_touched = [0; KEY_BYTES];
        if self.pend_keys == self.keys {
            return;
        }
        if !out.room(KEYBOARD_SIZE) {
            self.pend_keys = self.keys;
            return;
        }
        self.keys = self.pend_keys;
        out.buf[out.n] = KEYBOARD_ID;
        out.buf[out.n + 1..out.n + KEYBOARD_SIZE].copy_from_slice(&self.keys);
        out.n += KEYBOARD_SIZE;
    }

    fn flush_media(&mut self, out: &mut Out) {
        if self.media_touched == 0 {
            return;
        }
        self.media_touched = 0;
        if self.pend_media == self.media {
            return;
        }
        if !out.room(MEDIA_SIZE) {
            self.pend_media = self.media;
            return;
        }
        self.media = self.pend_media;
        out.buf[out.n] = MEDIA_ID;
        out.buf[out.n + 1] = self.media;
        out.n += MEDIA_SIZE;
    }

    fn flush_mouse(&mut self, out: &mut Out) {
        if !self.mouse_dirty {
            return;
        }
        let buttons = self.pend_buttons != self.buttons;
        let motion = self.dx != 0 || self.dy != 0 || self.wheel != 0 || self.hwheel != 0;
        if buttons || motion {
            if out.room(MOUSE_SIZE) {
                self.buttons = self.pend_buttons;
                let r = &mut out.buf[out.n..out.n + MOUSE_SIZE];
                r[0] = MOUSE_ID;
                r[1] = self.buttons;
                le::put_i16(r, 2, self.dx as i16);
                le::put_i16(r, 4, self.dy as i16);
                le::put_i16(r, 6, self.wheel as i16);
                le::put_i16(r, 8, self.hwheel as i16);
                out.n += MOUSE_SIZE;
            } else {
                self.pend_buttons = self.buttons;
            }
        }
        self.dx = 0;
        self.dy = 0;
        self.wheel = 0;
        self.hwheel = 0;
        self.buttons_touched = 0;
        self.mouse_dirty = false;
        self.moved = false;
    }
}

fn button_bit(f: u32, data: i32) -> Option<u32> {
    if f & (mapping::MOUSE_LEFT_DOWN | mapping::MOUSE_LEFT_UP) != 0 {
        Some(0)
    } else if f & (mapping::MOUSE_RIGHT_DOWN | mapping::MOUSE_RIGHT_UP) != 0 {
        Some(1)
    } else if f & (mapping::MOUSE_MIDDLE_DOWN | mapping::MOUSE_MIDDLE_UP) != 0 {
        Some(2)
    } else if f & (mapping::MOUSE_X_DOWN | mapping::MOUSE_X_UP) != 0 {
        Some(if data == mapping::XBUTTON2 { 4 } else { 3 })
    } else {
        None
    }
}

// The report fields are int16; a move past them (never from input_diff, whose steps are smaller) splits.
#[inline]
fn overflows(acc: i32, add: i32) -> bool {
    (acc as i64 + add as i64).abs() > i16::MAX as i64
}
