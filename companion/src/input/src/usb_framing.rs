//! Datagrams over the direct USB cable (Android Open Accessory), which is a byte stream: each one
//! goes as 0x47, length (u16 LE), bytes. A frame whose size is a multiple of 64 gets one 0x00 after
//! it, so no USB transfer ends on a full packet (the other side would wait for more). The reader
//! skips anything that is not a frame start, which also finds its feet again mid-stream. Same
//! format as the phone's UsbFrames.

use crate::wire;

pub const MAGIC: u8 = 0x47;
pub const HEADER: usize = 3;

/// Room `encode` needs for a datagram of `len` bytes.
pub const fn max_size(len: usize) -> usize {
    HEADER + len + 1
}

/// Writes the frame for `datagram` to `dst` and returns its size; `None` when the datagram is empty
/// or longer than a datagram may be, or `dst` is shorter than `max_size`.
pub fn encode(datagram: &[u8], dst: &mut [u8]) -> Option<usize> {
    let len = datagram.len();
    if len < 1 || len > wire::MAX_DATAGRAM || dst.len() < max_size(len) {
        return None;
    }
    dst[0] = MAGIC;
    dst[1] = len as u8;
    dst[2] = (len >> 8) as u8;
    dst[HEADER..HEADER + len].copy_from_slice(datagram);
    let mut n = HEADER + len;
    if n % 64 == 0 {
        dst[n] = 0;
        n += 1;
    }
    Some(n)
}

/// Splits the USB byte stream back into datagrams. One reader.
pub struct UsbDeframer {
    buf: [u8; HEADER + wire::MAX_DATAGRAM],
    have: usize,
    need: usize,
}

impl Default for UsbDeframer {
    fn default() -> Self {
        UsbDeframer { buf: [0; HEADER + wire::MAX_DATAGRAM], have: 0, need: 0 }
    }
}

impl UsbDeframer {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feeds data; `on_frame` gets each datagram (only valid during the call).
    pub fn feed(&mut self, data: &[u8], mut on_frame: impl FnMut(&[u8])) {
        for &b in data {
            if self.have == 0 {
                if b == MAGIC {
                    self.buf[0] = b;
                    self.have = 1;
                }
                continue;
            }
            self.buf[self.have] = b;
            self.have += 1;
            if self.have == HEADER {
                self.need = self.buf[1] as usize | (self.buf[2] as usize) << 8;
                if self.need < 1 || self.need > wire::MAX_DATAGRAM {
                    self.have = 0; // not a frame: look for the next start
                    continue;
                }
            }
            if self.have > HEADER && self.have == HEADER + self.need {
                on_frame(&self.buf[HEADER..HEADER + self.need]);
                self.have = 0;
            }
        }
    }
}
