//! MOTION plaintext (phone to PC, only to a host whose WELCOME has `welcome_flags::MOTION` while
//! HOST_STATUS says its pad is a plugged DualShock 4): the phone's raw motion for the pad's gyro and
//! accelerometer, sent as each gyro sample is read. Fixed size, little-endian: u8 flags
//! (`FLAG_MAY_MOVE`), u8 count (0..8), u16 newest sample's seq (wrapping), u32 newest sample's phone
//! time (µs, low 32 bits), then 8 sample slots, oldest first, each u16 µs since the sample before it
//! (0 for the first after a stop), i16 × 3 gyro in 1/16 deg/s and i16 × 3 accelerometer in 1/8192 g.
//! The phone has already mapped them into the DualShock 4's own frame and units (x right, y out of
//! its face, z toward the player; turns by the right-hand rule; +1 g on y lying still and flat) by
//! the workspace's pad axes, so the PC copies them into the report. Each packet repeats the last few
//! samples, so a lost one loses none.

use crate::le;

pub const MAX_SAMPLES: usize = 8;
pub const HEADER_SIZE: usize = 8;
pub const SAMPLE_SIZE: usize = 14;
pub const SIZE: usize = HEADER_SIZE + MAX_SAMPLES * SAMPLE_SIZE;
/// Gyro units per deg/s.
pub const GYRO_SCALE: f32 = 16.0;
/// Accelerometer units per g.
pub const ACCEL_SCALE: f32 = 8192.0;
/// Input is allowed on the phone: the samples are live. Off, the pad's gyro rests.
pub const FLAG_MAY_MOVE: u8 = 1;

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct MotionFrame {
    pub flags: u8,
    /// 0..=MAX_SAMPLES.
    pub count: usize,
    pub newest_seq: u16,
    pub newest_time_us: u32,
    pub dt_us: [u16; MAX_SAMPLES],
    /// [sample * 3 + axis]: the pad's x, y, z.
    pub gyro: [i16; MAX_SAMPLES * 3],
    pub accel: [i16; MAX_SAMPLES * 3],
}

impl MotionFrame {
    #[inline]
    pub fn may_move(&self) -> bool {
        self.flags & FLAG_MAY_MOVE != 0
    }

    /// Reads `SIZE` bytes; false (frame untouched) when `b` is shorter.
    pub fn decode(&mut self, b: &[u8]) -> bool {
        if b.len() < SIZE {
            return false;
        }
        self.flags = b[0];
        self.count = (b[1] as usize).min(MAX_SAMPLES);
        self.newest_seq = le::u16(b, 2);
        self.newest_time_us = le::u32(b, 4);
        for i in 0..MAX_SAMPLES {
            let o = HEADER_SIZE + i * SAMPLE_SIZE;
            self.dt_us[i] = le::u16(b, o);
            for a in 0..3 {
                self.gyro[i * 3 + a] = le::i16(b, o + 2 + a * 2);
                self.accel[i * 3 + a] = le::i16(b, o + 8 + a * 2);
            }
        }
        true
    }

    /// Writes `SIZE` bytes (tests and tools).
    pub fn encode(&self, b: &mut [u8]) {
        b[..SIZE].fill(0);
        let count = self.count.min(MAX_SAMPLES);
        b[0] = self.flags;
        b[1] = count as u8;
        le::put_u16(b, 2, self.newest_seq);
        le::put_u32(b, 4, self.newest_time_us);
        for i in 0..count {
            let o = HEADER_SIZE + i * SAMPLE_SIZE;
            le::put_u16(b, o, self.dt_us[i]);
            for a in 0..3 {
                le::put_i16(b, o + 2 + a * 2, self.gyro[i * 3 + a]);
                le::put_i16(b, o + 8 + a * 2, self.accel[i * 3 + a]);
            }
        }
    }
}
