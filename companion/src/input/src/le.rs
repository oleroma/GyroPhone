//! Little-endian reads and writes at an offset. They index the slice, so a short buffer panics
//! rather than reading past it; callers check sizes first (`codec::check_frame`).

#[inline]
pub fn u16(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes([b[off], b[off + 1]])
}

#[inline]
pub fn i16(b: &[u8], off: usize) -> i16 {
    u16(b, off) as i16
}

#[inline]
pub fn u32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

#[inline]
pub fn i32(b: &[u8], off: usize) -> i32 {
    u32(b, off) as i32
}

#[inline]
pub fn u64(b: &[u8], off: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[off..off + 8]);
    u64::from_le_bytes(a)
}

#[inline]
pub fn i64(b: &[u8], off: usize) -> i64 {
    u64(b, off) as i64
}

#[inline]
pub fn put_u16(b: &mut [u8], off: usize, v: u16) {
    b[off..off + 2].copy_from_slice(&v.to_le_bytes());
}

#[inline]
pub fn put_i16(b: &mut [u8], off: usize, v: i16) {
    put_u16(b, off, v as u16);
}

#[inline]
pub fn put_u32(b: &mut [u8], off: usize, v: u32) {
    b[off..off + 4].copy_from_slice(&v.to_le_bytes());
}

#[inline]
pub fn put_i32(b: &mut [u8], off: usize, v: i32) {
    put_u32(b, off, v as u32);
}

#[inline]
pub fn put_u64(b: &mut [u8], off: usize, v: u64) {
    b[off..off + 8].copy_from_slice(&v.to_le_bytes());
}

#[inline]
pub fn put_i64(b: &mut [u8], off: usize, v: i64) {
    put_u64(b, off, v as u64);
}
