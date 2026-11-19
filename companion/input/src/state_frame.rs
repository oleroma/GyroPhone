//! The STATE plaintext as a reusable frame: 66 bytes (STATE), 70 (STATE_HIRES: the wheel total in
//! 1/120 notch and a horizontal wheel total) or 404 (STATE_GYRO: plus the phone's raw gyro samples).
//! Pad, keys and mouse buttons are absolute; mouse and wheel are cumulative wrapping totals.

use crate::le;
use crate::wire;

pub const KEY_BITMAP_SIZE: usize = 32;
/// Bitmap byte holding usages 0xE0..0xE7, equal to the HID modifier byte.
pub const MODIFIER_BYTE: usize = 28;
pub const HAT_CENTER: u8 = 8;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StateFrame {
    pub phone_time_us: u32,
    pub flags: u16,
    pub buttons: u16,
    pub hat: u8,
    pub mouse_buttons: u8,
    pub lx: i16,
    pub ly: i16,
    pub rx: i16,
    pub ry: i16,
    pub lt: u16,
    pub rt: u16,
    pub keys: [u8; KEY_BITMAP_SIZE],
    pub mouse_total_x: i32,
    pub mouse_total_y: i32,
    pub wheel_total: i32,
    /// Horizontal wheel total in 1/120 notch, right = +. STATE_HIRES only.
    pub hwheel_total: i32,
    /// Encoded / decoded as STATE_HIRES.
    pub hires: bool,
}

impl Default for StateFrame {
    fn default() -> Self {
        StateFrame {
            phone_time_us: 0,
            flags: 0,
            buttons: 0,
            hat: HAT_CENTER,
            mouse_buttons: 0,
            lx: 0,
            ly: 0,
            rx: 0,
            ry: 0,
            lt: 0,
            rt: 0,
            keys: [0; KEY_BITMAP_SIZE],
            mouse_total_x: 0,
            mouse_total_y: 0,
            wheel_total: 0,
            hwheel_total: 0,
            hires: false,
        }
    }
}

impl StateFrame {
    #[inline]
    pub fn modifiers(&self) -> u8 {
        self.keys[MODIFIER_BYTE]
    }

    #[inline]
    pub fn is_key_down(&self, usage: u32) -> bool {
        usage < 256 && self.keys[(usage >> 3) as usize] & (1 << (usage & 7)) != 0
    }

    pub fn set_key(&mut self, usage: u32, down: bool) {
        if usage > 255 {
            return;
        }
        let bit = 1u8 << (usage & 7);
        if down {
            self.keys[(usage >> 3) as usize] |= bit;
        } else {
            self.keys[(usage >> 3) as usize] &= !bit;
        }
    }

    /// Neutral pad, nothing held. Totals are left alone.
    pub fn clear_inputs(&mut self) {
        self.buttons = 0;
        self.hat = HAT_CENTER;
        self.mouse_buttons = 0;
        self.lx = 0;
        self.ly = 0;
        self.rx = 0;
        self.ry = 0;
        self.lt = 0;
        self.rt = 0;
        self.keys = [0; KEY_BITMAP_SIZE];
    }

    /// Writes STATE (66 bytes) or, with `hires`, STATE_HIRES (70); returns the length.
    pub fn encode(&self, b: &mut [u8]) -> usize {
        le::put_u32(b, 0, self.phone_time_us);
        le::put_u16(b, 4, self.flags);
        le::put_u16(b, 6, self.buttons);
        b[8] = self.hat;
        b[9] = self.mouse_buttons;
        le::put_i16(b, 10, self.lx);
        le::put_i16(b, 12, self.ly);
        le::put_i16(b, 14, self.rx);
        le::put_i16(b, 16, self.ry);
        le::put_u16(b, 18, self.lt);
        le::put_u16(b, 20, self.rt);
        b[22..54].copy_from_slice(&self.keys);
        le::put_i32(b, 54, self.mouse_total_x);
        le::put_i32(b, 58, self.mouse_total_y);
        le::put_i32(b, 62, self.wheel_total);
        if !self.hires {
            return wire::STATE_PLAIN;
        }
        le::put_i32(b, 66, self.hwheel_total);
        wire::STATE_HIRES_PLAIN
    }

    /// Reads the STATE part of a plaintext of at least `STATE_PLAIN` bytes (the caller checked the
    /// size by the packet's type). Values are taken as sent; clamping is the mapper's job.
    pub fn decode(&mut self, b: &[u8]) {
        self.phone_time_us = le::u32(b, 0);
        self.flags = le::u16(b, 4);
        self.buttons = le::u16(b, 6);
        self.hat = b[8];
        self.mouse_buttons = b[9];
        self.lx = le::i16(b, 10);
        self.ly = le::i16(b, 12);
        self.rx = le::i16(b, 14);
        self.ry = le::i16(b, 16);
        self.lt = le::u16(b, 18);
        self.rt = le::u16(b, 20);
        self.keys.copy_from_slice(&b[22..54]);
        self.mouse_total_x = le::i32(b, 54);
        self.mouse_total_y = le::i32(b, 58);
        self.wheel_total = le::i32(b, 62);
        self.hires = b.len() >= wire::STATE_HIRES_PLAIN;
        self.hwheel_total = if self.hires { le::i32(b, 66) } else { 0 };
    }
}
