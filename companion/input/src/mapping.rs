//! Turning accepted STATEs into keyboard, mouse and pad events (SPEC 3.3): the events themselves,
//! the HID usage to scan code table, the diff against what was already injected, held volume key
//! repeat and the STATE to pad report mapping. Ports of `GyroPhone.Protocol.Mapping`.

use crate::state_frame::{StateFrame, HAT_CENTER, KEY_BITMAP_SIZE, MODIFIER_BYTE};

// ---------------------------------------------------------------------------------------------
// InjectEvent

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
#[repr(u8)]
pub enum InjectKind {
    #[default]
    Key = 0,
    Mouse = 1,
}

pub const KEY_EXTENDED: u32 = 0x0001; // KEYEVENTF_EXTENDEDKEY
pub const KEY_UP_FLAG: u32 = 0x0002; // KEYEVENTF_KEYUP
pub const KEY_SCANCODE: u32 = 0x0008; // KEYEVENTF_SCANCODE

pub const MOUSE_MOVE: u32 = 0x0001;
pub const MOUSE_LEFT_DOWN: u32 = 0x0002;
pub const MOUSE_LEFT_UP: u32 = 0x0004;
pub const MOUSE_RIGHT_DOWN: u32 = 0x0008;
pub const MOUSE_RIGHT_UP: u32 = 0x0010;
pub const MOUSE_MIDDLE_DOWN: u32 = 0x0020;
pub const MOUSE_MIDDLE_UP: u32 = 0x0040;
pub const MOUSE_X_DOWN: u32 = 0x0080;
pub const MOUSE_X_UP: u32 = 0x0100;
pub const MOUSE_WHEEL: u32 = 0x0800;
pub const MOUSE_HWHEEL: u32 = 0x1000;
pub const XBUTTON1: i32 = 1;
pub const XBUTTON2: i32 = 2;
pub const WHEEL_DELTA: i32 = 120;

pub const VK_PAUSE: u16 = 0x13;
pub const VK_VOLUME_MUTE: u16 = 0xAD;
pub const VK_VOLUME_DOWN: u16 = 0xAE;
pub const VK_VOLUME_UP: u16 = 0xAF;
pub const VK_MEDIA_NEXT: u16 = 0xB0;
pub const VK_MEDIA_PREV: u16 = 0xB1;
pub const VK_MEDIA_PLAY_PAUSE: u16 = 0xB3;

/// One keyboard or mouse event, already in SendInput terms so the Windows sink only copies fields.
/// Flag values are the Win32 KEYEVENTF_* / MOUSEEVENTF_* constants.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct InjectEvent {
    pub kind: InjectKind,
    /// HID usage of a key event (diagnostics and the held-keys marker).
    pub usage: u8,
    pub scan: u16,
    pub vk: u16,
    /// KEYEVENTF_* for keys, MOUSEEVENTF_* for mouse.
    pub flags: u32,
    pub dx: i32,
    pub dy: i32,
    /// mouseData: XBUTTON1/2 or wheel amount.
    pub mouse_data: i32,
}

impl InjectEvent {
    #[inline]
    pub fn is_key_up(&self) -> bool {
        self.kind == InjectKind::Key && self.flags & KEY_UP_FLAG != 0
    }

    pub fn key(usage: u8, sc: ScanCode, up: bool) -> InjectEvent {
        let mut e = InjectEvent { kind: InjectKind::Key, usage, ..Default::default() };
        if sc.vk != 0 {
            e.vk = sc.vk;
            if sc.extended {
                e.flags = KEY_EXTENDED;
            }
        } else {
            e.scan = sc.scan;
            e.flags = KEY_SCANCODE | if sc.extended { KEY_EXTENDED } else { 0 };
        }
        if up {
            e.flags |= KEY_UP_FLAG;
        }
        e
    }

    /// Button index is the STATE mouseButtons bit: 0 L, 1 R, 2 M, 3 BACK (X1), 4 FORWARD (X2).
    /// Any other index gives an event with no flags, which injects nothing.
    pub fn mouse_button(button: u32, up: bool) -> InjectEvent {
        let mut e = InjectEvent { kind: InjectKind::Mouse, ..Default::default() };
        match button {
            0 => e.flags = if up { MOUSE_LEFT_UP } else { MOUSE_LEFT_DOWN },
            1 => e.flags = if up { MOUSE_RIGHT_UP } else { MOUSE_RIGHT_DOWN },
            2 => e.flags = if up { MOUSE_MIDDLE_UP } else { MOUSE_MIDDLE_DOWN },
            3 => {
                e.flags = if up { MOUSE_X_UP } else { MOUSE_X_DOWN };
                e.mouse_data = XBUTTON1;
            }
            4 => {
                e.flags = if up { MOUSE_X_UP } else { MOUSE_X_DOWN };
                e.mouse_data = XBUTTON2;
            }
            _ => {}
        }
        e
    }

    pub fn mouse_move(dx: i32, dy: i32) -> InjectEvent {
        InjectEvent { kind: InjectKind::Mouse, flags: MOUSE_MOVE, dx, dy, ..Default::default() }
    }

    /// Wheel in 1/120 detent (WHEEL_DELTA = one notch), + = away from the user.
    pub fn wheel(amount: i32) -> InjectEvent {
        InjectEvent { kind: InjectKind::Mouse, flags: MOUSE_WHEEL, mouse_data: amount, ..Default::default() }
    }

    /// Horizontal wheel in 1/120 detent, + = right.
    pub fn hwheel(amount: i32) -> InjectEvent {
        InjectEvent { kind: InjectKind::Mouse, flags: MOUSE_HWHEEL, mouse_data: amount, ..Default::default() }
    }
}

// ---------------------------------------------------------------------------------------------
// HidScanCodes

/// A Set 1 scan code, or a virtual key when `vk` is non-zero (Pause, the media keys).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct ScanCode {
    pub scan: u16,
    pub extended: bool,
    pub vk: u16,
}

impl ScanCode {
    #[inline]
    pub const fn is_mapped(&self) -> bool {
        self.scan != 0 || self.vk != 0
    }
}

const NONE: ScanCode = ScanCode { scan: 0, extended: false, vk: 0 };

const fn build_table() -> [ScanCode; 256] {
    const fn s(scan: u16) -> ScanCode {
        ScanCode { scan, extended: false, vk: 0 }
    }
    const fn e(scan: u16) -> ScanCode {
        ScanCode { scan, extended: true, vk: 0 }
    }
    const fn m(vk: u16) -> ScanCode {
        ScanCode { scan: 0, extended: true, vk }
    }
    const fn run<const N: usize>(mut t: [ScanCode; 256], first: usize, scans: [u16; N]) -> [ScanCode; 256] {
        let mut i = 0;
        while i < N {
            t[first + i] = s(scans[i]);
            i += 1;
        }
        t
    }

    let mut t = [NONE; 256];
    // 0x04..0x1D A..Z
    t = run(
        t,
        0x04,
        [
            0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10,
            0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
        ],
    );
    // 0x1E..0x27 1..9, 0
    let mut i = 0;
    while i < 10 {
        t[0x1E + i] = s(0x02 + i as u16);
        i += 1;
    }
    t = run(
        t,
        0x28,
        [
            0x1C, 0x01, 0x0E, 0x0F, 0x39, 0x0C, 0x0D, 0x1A, 0x1B, 0x2B, 0x2B, 0x27, 0x28, 0x29, 0x33, 0x34, 0x35,
            0x3A,
        ],
    );
    // 0x3A..0x43 F1..F10, then F11, F12
    let mut i = 0;
    while i < 10 {
        t[0x3A + i] = s(0x3B + i as u16);
        i += 1;
    }
    t[0x44] = s(0x57);
    t[0x45] = s(0x58);
    t[0x46] = e(0x37); // PrintScreen
    t[0x47] = s(0x46); // ScrollLock
    t[0x48] = ScanCode { scan: 0, extended: false, vk: VK_PAUSE }; // Pause: VK path
    t[0x49] = e(0x52); // Insert
    t[0x4A] = e(0x47); // Home
    t[0x4B] = e(0x49); // PgUp
    t[0x4C] = e(0x53); // Delete
    t[0x4D] = e(0x4F); // End
    t[0x4E] = e(0x51); // PgDn
    t[0x4F] = e(0x4D); // Right
    t[0x50] = e(0x4B); // Left
    t[0x51] = e(0x50); // Down
    t[0x52] = e(0x48); // Up
    t[0x53] = e(0x45); // NumLock (plain 0x45 is Pause)
    t[0x54] = e(0x35); // KP /
    t[0x55] = s(0x37); // KP *
    t[0x56] = s(0x4A); // KP -
    t[0x57] = s(0x4E); // KP +
    t[0x58] = e(0x1C); // KP Enter
    // KP1..9, KP0, KP.
    t = run(t, 0x59, [0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52, 0x53]);
    t[0x64] = s(0x56); // Non-US backslash
    t[0x65] = e(0x5D); // Application
    // Media keys (the phone's HidUsage.media): Windows' media virtual keys, extended like a keyboard's.
    t[0x7F] = m(VK_VOLUME_MUTE);
    t[0x80] = m(VK_VOLUME_UP);
    t[0x81] = m(VK_VOLUME_DOWN);
    t[0xE8] = m(VK_MEDIA_PLAY_PAUSE);
    t[0xEA] = m(VK_MEDIA_PREV);
    t[0xEB] = m(VK_MEDIA_NEXT);
    // F13..F24
    t = run(t, 0x68, [0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x76]);
    t[0xE0] = s(0x1D); // LCtrl
    t[0xE1] = s(0x2A); // LShift
    t[0xE2] = s(0x38); // LAlt
    t[0xE3] = e(0x5B); // LWin
    t[0xE4] = e(0x1D); // RCtrl
    t[0xE5] = s(0x36); // RShift
    t[0xE6] = e(0x38); // RAlt
    t[0xE7] = e(0x5C); // RWin
    t
}

static TABLE: [ScanCode; 256] = build_table();

/// HID keyboard usage (page 0x07) to Set 1 scan code (SPEC 3.3).
pub mod hid_scan_codes {
    use super::{ScanCode, NONE, TABLE};

    /// Scan code for a usage; `is_mapped` is false for unknown usages.
    #[inline]
    pub fn for_usage(usage: u32) -> ScanCode {
        if usage < 256 {
            TABLE[usage as usize]
        } else {
            NONE
        }
    }

    /// The HID usage of a key a keyboard sent as Set 1 `scan` (E0-prefixed when `extended`), or by
    /// virtual key `vk` for keys without one (Pause, the media keys); 0 when unknown. The reverse
    /// of `for_usage`: the lowest usage wins where two share a scan code.
    pub fn usage_of(scan: u16, extended: bool, vk: u16) -> u32 {
        if vk != 0 {
            if let Some(u) = TABLE.iter().position(|c| c.vk == vk) {
                return u as u32;
            }
        }
        if scan != 0 {
            let by_scan = |ext: bool| TABLE.iter().position(|c| c.vk == 0 && c.scan == scan && c.extended == ext);
            // A few keys come with or without E0 by keyboard (NumLock's 0x45 often without): try the other.
            if let Some(u) = by_scan(extended).or_else(|| by_scan(!extended)) {
                return u as u32;
            }
        }
        0
    }

    /// Modifier usages 0xE0..0xE7.
    #[inline]
    pub fn is_modifier(usage: u32) -> bool {
        (0xE0..=0xE7).contains(&usage)
    }

    /// Volume up and down: held, they repeat like a keyboard's (Windows doesn't repeat injected keys).
    #[inline]
    pub fn repeats(usage: u32) -> bool {
        usage == 0x80 || usage == 0x81
    }
}

// ---------------------------------------------------------------------------------------------
// InputDiff

/// Diffs the injected keyboard/mouse state against a target STATE and writes SendInput events
/// (SPEC 3.3). Order: modifier downs, key downs, mouse buttons, key ups, modifier ups, move, wheel,
/// horizontal wheel: releases mirror presses, so a Ctrl + click lets go of the button before Ctrl.
/// The injected state is updated in place for every event written, so anything that did not fit
/// in the output is picked up by the next call.
pub mod input_diff {
    use super::*;

    pub const MAX_EVENTS: usize = 96;
    const MOUSE_BUTTON_COUNT: u32 = 5;
    /// Room kept for mouse buttons, move and both wheels so keys can never starve them.
    const MOUSE_RESERVE: usize = MOUSE_BUTTON_COUNT as usize + 3;
    /// The most events one `release_all` can write: every key and every mouse button.
    pub const MAX_RELEASE_EVENTS: usize = 256 + MOUSE_BUTTON_COUNT as usize;

    /// `injected`/`injected_mouse`: what the host has sent so far (updated). `target`/`target_mouse`:
    /// the new STATE. Wheels are in 1/120 notch. Usages 0..3 and usages with no scan code are never
    /// injected; each newly seen unknown usage increments `unknown`. Returns the event count.
    ///
    /// `output` must hold at least `MOUSE_RESERVE` (8) events; a shorter one gets nothing written
    /// and nothing marked as injected.
    #[allow(clippy::too_many_arguments)]
    pub fn diff(
        injected: &mut [u8; KEY_BITMAP_SIZE],
        target: &[u8; KEY_BITMAP_SIZE],
        injected_mouse: &mut u8,
        target_mouse: u8,
        dx: i32,
        dy: i32,
        wheel: i32,
        hwheel: i32,
        output: &mut [InjectEvent],
        unknown: &mut u32,
    ) -> usize {
        if output.len() < MOUSE_RESERVE {
            return 0;
        }
        let mut n = 0usize;
        let key_limit = output.len().min(MAX_EVENTS) - MOUSE_RESERVE;

        // 1. modifier downs, 2. key downs
        n = keys(injected, target, MODIFIER_BYTE, true, output, n, key_limit, unknown);
        for i in 0..KEY_BITMAP_SIZE {
            if i != MODIFIER_BYTE {
                n = keys(injected, target, i, true, output, n, key_limit, unknown);
            }
        }
        // 3. mouse buttons: after the keys that go down with them, before the keys that go up with them
        let changed = (*injected_mouse ^ target_mouse) & 0x1F;
        for bit in 0..MOUSE_BUTTON_COUNT {
            if changed & (1 << bit) == 0 {
                continue;
            }
            let up = target_mouse & (1 << bit) == 0;
            output[n] = InjectEvent::mouse_button(bit, up);
            n += 1;
        }
        *injected_mouse = target_mouse & 0x1F;

        // 4. key ups, 5. modifier ups
        for i in 0..KEY_BITMAP_SIZE {
            if i != MODIFIER_BYTE {
                n = keys(injected, target, i, false, output, n, key_limit, unknown);
            }
        }
        n = keys(injected, target, MODIFIER_BYTE, false, output, n, key_limit, unknown);

        // 6. move, 7. wheel, 8. horizontal wheel
        if dx != 0 || dy != 0 {
            output[n] = InjectEvent::mouse_move(dx, dy);
            n += 1;
        }
        if wheel != 0 {
            output[n] = InjectEvent::wheel(wheel);
            n += 1;
        }
        if hwheel != 0 {
            output[n] = InjectEvent::hwheel(hwheel);
            n += 1;
        }
        n
    }

    /// Releases everything injected: mouse-button ups, then key ups, then modifier ups, as far as
    /// `output` holds (`MAX_RELEASE_EVENTS` always fits everything). What was written is cleared
    /// from the state; anything that did not fit stays held for the next call.
    pub fn release_all(
        injected: &mut [u8; KEY_BITMAP_SIZE],
        injected_mouse: &mut u8,
        output: &mut [InjectEvent],
    ) -> usize {
        let none = [0u8; KEY_BITMAP_SIZE];
        let mut unknown = 0u32;
        let mut n = 0usize;
        for bit in 0..MOUSE_BUTTON_COUNT {
            if *injected_mouse & (1 << bit) != 0 {
                if n >= output.len() {
                    return n;
                }
                output[n] = InjectEvent::mouse_button(bit, true);
                n += 1;
                *injected_mouse &= !(1 << bit);
            }
        }
        *injected_mouse = 0;
        let limit = output.len();
        for i in 0..KEY_BITMAP_SIZE {
            if i != MODIFIER_BYTE {
                n = keys(injected, &none, i, false, output, n, limit, &mut unknown);
            }
        }
        keys(injected, &none, MODIFIER_BYTE, false, output, n, limit, &mut unknown)
    }

    /// True when any key or mouse button is held in this state.
    pub fn any_held(injected: &[u8; KEY_BITMAP_SIZE], injected_mouse: u8) -> bool {
        injected_mouse != 0 || injected.iter().any(|&b| b != 0)
    }

    #[allow(clippy::too_many_arguments)]
    fn keys(
        injected: &mut [u8; KEY_BITMAP_SIZE],
        target: &[u8; KEY_BITMAP_SIZE],
        i: usize,
        down: bool,
        output: &mut [InjectEvent],
        mut n: usize,
        limit: usize,
        unknown: &mut u32,
    ) -> usize {
        let mut want = target[i];
        if i == 0 {
            want &= 0xF0; // usages 0..3 are not keys
        }
        let diff = (injected[i] ^ want) & if down { want } else { !want };
        if diff == 0 {
            return n;
        }
        for bit in 0..8u32 {
            if diff & (1 << bit) == 0 {
                continue;
            }
            let usage = ((i as u32) << 3) | bit;
            let sc = hid_scan_codes::for_usage(usage);
            if sc.is_mapped() {
                if n >= limit {
                    return n;
                }
                output[n] = InjectEvent::key(usage as u8, sc, !down);
                n += 1;
            } else if down {
                *unknown = unknown.wrapping_add(1);
            }
            // Track unknown usages as injected too, so they are counted once, not every flush.
            if down {
                injected[i] |= 1 << bit;
            } else {
                injected[i] &= !(1 << bit);
            }
        }
        n
    }
}

// ---------------------------------------------------------------------------------------------
// KeyRepeat

/// Repeats held volume keys (`hid_scan_codes::repeats`) the way a keyboard does: Windows doesn't
/// repeat injected keys, so a held Volume Up would only step once. After `DELAY_US` held, a key
/// down goes again every `INTERVAL_US` (or at each call, when calls come further apart) until the
/// key is let go.
#[derive(Clone, Debug)]
pub struct KeyRepeat {
    next_us: [i64; 2],
}

impl Default for KeyRepeat {
    fn default() -> Self {
        KeyRepeat { next_us: [-1, -1] }
    }
}

impl KeyRepeat {
    pub const DELAY_US: i64 = 400_000;
    pub const INTERVAL_US: i64 = 50_000;
    const USAGES: [u32; 2] = [0x80, 0x81];

    pub fn new() -> Self {
        Self::default()
    }

    /// Writes a key down for each repeating usage held in `injected` whose next repeat is due; a
    /// newly held one starts its delay. Returns the event count.
    pub fn due(&mut self, injected: &[u8; KEY_BITMAP_SIZE], now_us: i64, output: &mut [InjectEvent]) -> usize {
        let mut n = 0;
        for (i, &u) in Self::USAGES.iter().enumerate() {
            let held = injected[(u >> 3) as usize] & (1 << (u & 7)) != 0;
            if !held {
                self.next_us[i] = -1;
                continue;
            }
            if self.next_us[i] < 0 {
                self.next_us[i] = now_us.saturating_add(Self::DELAY_US);
                continue;
            }
            if now_us < self.next_us[i] || n >= output.len() {
                continue;
            }
            output[n] = InjectEvent::key(u as u8, hid_scan_codes::for_usage(u), false);
            n += 1;
            self.next_us[i] = self.next_us[i].saturating_add(Self::INTERVAL_US).max(now_us);
        }
        n
    }

    /// Forgets every held key (after a release-all or a session end).
    pub fn reset(&mut self) {
        self.next_us = [-1, -1];
    }
}

// ---------------------------------------------------------------------------------------------
// PadMapper

/// Xbox 360 report as ViGEm takes it: XUSB button word, signed sticks (+Y = up), byte triggers.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct X360Frame {
    pub buttons: u16,
    pub lx: i16,
    pub ly: i16,
    pub rx: i16,
    pub ry: i16,
    pub lt: u8,
    pub rt: u8,
}

impl X360Frame {
    pub const NEUTRAL: X360Frame = X360Frame { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
}

/// DualShock 4 report in ViGEm terms. Buttons use `ds4_buttons`; special uses `ds4_special`; hat is
/// 0..7 (north, clockwise), 8 = none. Sticks are bytes with 128 = centre and no Y flip.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ds4Frame {
    pub buttons: u16,
    pub special: u8,
    pub hat: u8,
    pub lx: u8,
    pub ly: u8,
    pub rx: u8,
    pub ry: u8,
    pub lt: u8,
    pub rt: u8,
}

impl Ds4Frame {
    pub const NEUTRAL: Ds4Frame =
        Ds4Frame { buttons: 0, special: 0, hat: pad_mapper::DS4_HAT_NONE, lx: 128, ly: 128, rx: 128, ry: 128, lt: 0, rt: 0 };
}

impl Default for Ds4Frame {
    fn default() -> Self {
        Self::NEUTRAL
    }
}

/// XUSB button bits.
pub mod xusb_buttons {
    pub const DPAD_UP: u16 = 0x0001;
    pub const DPAD_DOWN: u16 = 0x0002;
    pub const DPAD_LEFT: u16 = 0x0004;
    pub const DPAD_RIGHT: u16 = 0x0008;
    pub const START: u16 = 0x0010;
    pub const BACK: u16 = 0x0020;
    pub const LEFT_THUMB: u16 = 0x0040;
    pub const RIGHT_THUMB: u16 = 0x0080;
    pub const LEFT_SHOULDER: u16 = 0x0100;
    pub const RIGHT_SHOULDER: u16 = 0x0200;
    pub const GUIDE: u16 = 0x0400;
    pub const A: u16 = 0x1000;
    pub const B: u16 = 0x2000;
    pub const X: u16 = 0x4000;
    pub const Y: u16 = 0x8000;
}

/// DS4 button bits, matching the ViGEm DualShock4Button values.
pub mod ds4_buttons {
    pub const THUMB_RIGHT: u16 = 1 << 15;
    pub const THUMB_LEFT: u16 = 1 << 14;
    pub const OPTIONS: u16 = 1 << 13;
    pub const SHARE: u16 = 1 << 12;
    pub const TRIGGER_RIGHT: u16 = 1 << 11;
    pub const TRIGGER_LEFT: u16 = 1 << 10;
    pub const SHOULDER_RIGHT: u16 = 1 << 9;
    pub const SHOULDER_LEFT: u16 = 1 << 8;
    pub const TRIANGLE: u16 = 1 << 7;
    pub const CIRCLE: u16 = 1 << 6;
    pub const CROSS: u16 = 1 << 5;
    pub const SQUARE: u16 = 1 << 4;
}

/// DS4 special button bits, matching the ViGEm DualShock4SpecialButton values.
pub mod ds4_special {
    pub const PS: u8 = 1 << 0;
    pub const TOUCHPAD: u8 = 1 << 1;
}

/// Pure STATE to pad mapping (SPEC 3.3).
pub mod pad_mapper {
    use super::*;

    pub const DS4_HAT_NONE: u8 = 8;

    /// Index order of the DS4 hat table.
    pub const DS4_HAT_NAMES: [&str; 9] =
        ["North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest", "None"];

    // Phone GamepadButton bit -> XUSB. Bits 11..15 (M1..M4, SHARE) have no X360 button.
    const X360_BUTTON_MAP: [u16; 11] = [
        xusb_buttons::A,
        xusb_buttons::B,
        xusb_buttons::X,
        xusb_buttons::Y,
        xusb_buttons::LEFT_SHOULDER,
        xusb_buttons::RIGHT_SHOULDER,
        xusb_buttons::BACK,
        xusb_buttons::START,
        xusb_buttons::LEFT_THUMB,
        xusb_buttons::RIGHT_THUMB,
        xusb_buttons::GUIDE,
    ];

    /// Phone buttons X360 cannot show (M1..M4, SHARE); the host logs these once.
    pub const X360_DROPPED_BUTTONS: u16 = 0xF800;

    // Hat 0..7 (UP, UP_RIGHT, ... UP_LEFT), 8 = centre.
    const X360_HAT_MAP: [u16; 9] = [
        xusb_buttons::DPAD_UP,
        xusb_buttons::DPAD_UP | xusb_buttons::DPAD_RIGHT,
        xusb_buttons::DPAD_RIGHT,
        xusb_buttons::DPAD_DOWN | xusb_buttons::DPAD_RIGHT,
        xusb_buttons::DPAD_DOWN,
        xusb_buttons::DPAD_DOWN | xusb_buttons::DPAD_LEFT,
        xusb_buttons::DPAD_LEFT,
        xusb_buttons::DPAD_UP | xusb_buttons::DPAD_LEFT,
        0,
    ];

    // Phone bit -> DS4 button (A..RS); GUIDE and SHARE go to the special byte.
    const DS4_BUTTON_MAP: [u16; 10] = [
        ds4_buttons::CROSS,
        ds4_buttons::CIRCLE,
        ds4_buttons::SQUARE,
        ds4_buttons::TRIANGLE,
        ds4_buttons::SHOULDER_LEFT,
        ds4_buttons::SHOULDER_RIGHT,
        ds4_buttons::SHARE,
        ds4_buttons::OPTIONS,
        ds4_buttons::THUMB_LEFT,
        ds4_buttons::THUMB_RIGHT,
    ];

    const GUIDE_BIT: u32 = 10;
    const SHARE_BIT: u32 = 15;

    pub fn to_x360(s: &StateFrame) -> X360Frame {
        let mut b = 0u16;
        for (i, &m) in X360_BUTTON_MAP.iter().enumerate() {
            if s.buttons & (1 << i) != 0 {
                b |= m;
            }
        }
        b |= X360_HAT_MAP[hat_of(s) as usize];
        X360Frame {
            buttons: b,
            lx: stick(s.lx),
            ly: -stick(s.ly),
            rx: stick(s.rx),
            ry: -stick(s.ry),
            lt: trigger_byte(s.lt),
            rt: trigger_byte(s.rt),
        }
    }

    pub fn to_ds4(s: &StateFrame) -> Ds4Frame {
        let mut b = 0u16;
        for (i, &m) in DS4_BUTTON_MAP.iter().enumerate() {
            if s.buttons & (1 << i) != 0 {
                b |= m;
            }
        }
        let mut special = 0u8;
        if s.buttons & (1 << GUIDE_BIT) != 0 {
            special |= ds4_special::PS;
        }
        if s.buttons & (1 << SHARE_BIT) != 0 {
            special |= ds4_special::TOUCHPAD;
        }
        let (lt, rt) = (trigger_byte(s.lt), trigger_byte(s.rt));
        if lt >= 1 {
            b |= ds4_buttons::TRIGGER_LEFT;
        }
        if rt >= 1 {
            b |= ds4_buttons::TRIGGER_RIGHT;
        }
        Ds4Frame {
            buttons: b,
            special,
            hat: hat_of(s),
            lx: ds4_axis(s.lx),
            ly: ds4_axis(s.ly),
            rx: ds4_axis(s.rx),
            ry: ds4_axis(s.ry),
            lt,
            rt,
        }
    }

    /// Hat values above 8 are treated as centre.
    #[inline]
    pub fn hat_of(s: &StateFrame) -> u8 {
        s.hat.min(HAT_CENTER)
    }

    /// -32768 is mapped to -32767 so the axis is symmetric and negation cannot overflow.
    #[inline]
    pub fn stick(v: i16) -> i16 {
        v.max(-32767)
    }

    /// 0..1023 to 0..255 with rounding: 0 -> 0, 512 -> 128, 1023 -> 255.
    #[inline]
    pub fn trigger_byte(v: u16) -> u8 {
        ((v.min(1023) as u32 * 255 + 511) / 1023) as u8
    }

    /// Signed stick to DS4 byte: -32767 -> 0, 0 -> 128, 32767 -> 255.
    #[inline]
    pub fn ds4_axis(v: i16) -> u8 {
        (((v as i32).clamp(-32767, 32767) + 32768) >> 8) as u8
    }
}
