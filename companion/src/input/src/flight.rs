//! Diagnostics' per-packet record (docs/spec/pc-companion.md, "Diagnostics", "Flight record"). Port of
//! FlightRecorder.cs; the file format is the same.
//!
//! One fixed-size entry per accepted STATE, in a ring made when Diagnostics comes on (sized for the minutes it keeps).
//! The input path only stores numbers into the preallocated ring (the engine calls it under its lock, one writer); it
//! never allocates, locks or makes text. `write` copies from any thread without the engine lock: entries the writer
//! overwrote meanwhile are left out.

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, AtomicU32, Ordering};

pub const RECORD_BYTES: usize = 32;
pub const VERSION: i32 = 1;
pub const MOVING: u32 = 1;
pub const ACTIVE: u32 = 2;
pub const LOST_BEFORE: u32 = 4;
pub const PAD_ONLY: u32 = 8;
pub const REFUSED: u32 = 16;
pub const HANDED_OFF: u32 = 32;
/// Entries per minute kept: up to a thousand packets a second, with room.
pub const PER_MINUTE: usize = 66_000;

/// One entry; atomics only so `write` may read while the writer runs (relaxed: a torn entry is left out by the
/// head check).
#[derive(Default)]
struct Entry {
    recv: AtomicI64,
    phone: AtomicU32,
    extra: AtomicU32,
    hand_off: AtomicU32,
    lock_wait: AtomicU32,
    driver: AtomicU32,
    flags: AtomicU32,
}

pub struct FlightRecorder {
    ring: Vec<Entry>,
    head: AtomicI64,
}

impl FlightRecorder {
    pub fn new(minutes: i32) -> FlightRecorder {
        let n = minutes.clamp(1, 30) as usize * PER_MINUTE;
        let mut ring = Vec::with_capacity(n);
        ring.resize_with(n, Entry::default);
        FlightRecorder { ring, head: AtomicI64::new(0) }
    }

    pub fn capacity(&self) -> usize {
        self.ring.len()
    }

    pub fn minutes(&self) -> i32 {
        (self.ring.len() / PER_MINUTE) as i32
    }

    pub fn count(&self) -> i64 {
        self.head.load(Ordering::Acquire)
    }

    /// One accepted STATE: its phone time, arrival on the engine's clock, how much later than the fastest packets.
    pub fn state(&self, phone_us: u32, recv_us: i64, extra_us: i64, flags: u32) {
        let h = self.head.load(Ordering::Relaxed);
        let e = &self.ring[(h as usize) % self.ring.len()];
        e.recv.store(recv_us, Ordering::Relaxed);
        e.phone.store(phone_us, Ordering::Relaxed);
        e.extra.store(extra_us.clamp(i32::MIN as i64, i32::MAX as i64) as i32 as u32, Ordering::Relaxed);
        e.hand_off.store(-1i32 as u32, Ordering::Relaxed);
        e.lock_wait.store(0, Ordering::Relaxed);
        e.driver.store(0, Ordering::Relaxed);
        e.flags.store(flags, Ordering::Relaxed);
        self.head.store(h + 1, Ordering::Release);
    }

    /// The newest STATE was handed to Windows: how long after its arrival, the engine lock wait and the time in the
    /// drivers (µs). A second hand-off of the same packet (a catch-up step) is not counted.
    pub fn hand_off(&self, took_us: i64, lock_wait_us: i64, driver_us: i64, flags: u32) {
        let h = self.head.load(Ordering::Relaxed);
        if h == 0 {
            return;
        }
        let e = &self.ring[((h - 1) as usize) % self.ring.len()];
        if (e.hand_off.load(Ordering::Relaxed) as i32) >= 0 {
            return;
        }
        let c = |v: i64| v.clamp(0, i32::MAX as i64) as u32;
        e.hand_off.store(c(took_us), Ordering::Relaxed);
        e.lock_wait.store(c(lock_wait_us), Ordering::Relaxed);
        e.driver.store(c(driver_us), Ordering::Relaxed);
        e.flags.fetch_or(flags | HANDED_OFF, Ordering::Relaxed);
    }

    /// Writes the entries that arrived after `from_us`, oldest first, after the header ("GPFLIGHT", version, entry size,
    /// count, the engine clock and the wall clock in Unix ms at `now_us`). Returns the count.
    pub fn write(&self, out: &mut dyn Write, from_us: i64, now_us: i64, unix_ms: i64) -> io::Result<usize> {
        let cap = self.ring.len() as i64;
        let head = self.head.load(Ordering::Acquire);
        let tail = (head - cap).max(0);
        // Leave out the newest (it may be half written).
        let end = head - 1;
        let mut all = vec![0u8; ((end - tail).max(0) as usize) * RECORD_BYTES];
        let mut n = 0usize;
        let mut k = tail;
        while k < end {
            let e = &self.ring[(k as usize) % self.ring.len()];
            k += 1;
            let recv = e.recv.load(Ordering::Relaxed);
            if recv <= from_us {
                continue;
            }
            let b = &mut all[n * RECORD_BYTES..(n + 1) * RECORD_BYTES];
            b[0..8].copy_from_slice(&recv.to_le_bytes());
            b[8..12].copy_from_slice(&e.phone.load(Ordering::Relaxed).to_le_bytes());
            b[12..16].copy_from_slice(&e.extra.load(Ordering::Relaxed).to_le_bytes());
            b[16..20].copy_from_slice(&e.hand_off.load(Ordering::Relaxed).to_le_bytes());
            b[20..24].copy_from_slice(&e.lock_wait.load(Ordering::Relaxed).to_le_bytes());
            b[24..28].copy_from_slice(&e.driver.load(Ordering::Relaxed).to_le_bytes());
            b[28..32].copy_from_slice(&e.flags.load(Ordering::Relaxed).to_le_bytes());
            n += 1;
        }
        // Entries the writer reached while this copied (only once the ring is full), and one more for the entry it
        // may be writing: left out from the oldest.
        let reached = self.head.load(Ordering::Acquire) + 1 - cap - tail;
        let skip = reached.clamp(0, n as i64) as usize;
        let count = n - skip;
        let mut header = [0u8; 40];
        header[0..8].copy_from_slice(b"GPFLIGHT");
        header[8..12].copy_from_slice(&VERSION.to_le_bytes());
        header[12..16].copy_from_slice(&(RECORD_BYTES as i32).to_le_bytes());
        header[16..24].copy_from_slice(&(count as i64).to_le_bytes());
        header[24..32].copy_from_slice(&now_us.to_le_bytes());
        header[32..40].copy_from_slice(&unix_ms.to_le_bytes());
        out.write_all(&header)?;
        out.write_all(&all[skip * RECORD_BYTES..n * RECORD_BYTES])?;
        Ok(count)
    }

    /// Writes flight-<stamp>.bin into `folder` (the logs folder) and keeps the newest 5 there; returns its path.
    pub fn save(&self, folder: &Path, stamp: &str, from_us: i64, now_us: i64) -> io::Result<PathBuf> {
        std::fs::create_dir_all(folder)?;
        let path = folder.join(format!("flight-{stamp}.bin"));
        let unix_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        {
            let mut f = io::BufWriter::with_capacity(1 << 16, std::fs::File::create(&path)?);
            self.write(&mut f, from_us, now_us, unix_ms)?;
            f.flush()?;
        }
        let mut old: Vec<PathBuf> = std::fs::read_dir(folder)?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.starts_with("flight-") && n.ends_with(".bin"))
            })
            .collect();
        old.sort();
        old.reverse();
        for p in old.into_iter().skip(5) {
            let _ = std::fs::remove_file(p);
        }
        Ok(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entries_are_written_oldest_first_with_their_hand_off() {
        let f = FlightRecorder::new(1);
        f.state(1000, 10_000, 50, MOVING);
        f.hand_off(300, 0, 120, 0);
        f.state(2000, 11_000, 2_000, 0);
        f.hand_off(5_000, 4_000, 200, PAD_ONLY);
        f.hand_off(9_000, 0, 0, 0); // a second hand-off of the same packet: not counted
        f.state(3000, 12_000, 0, 0); // the newest: left out
        let mut out = Vec::new();
        assert_eq!(f.write(&mut out, 0, 13_000, 77).unwrap(), 2);
        assert_eq!(&out[0..8], b"GPFLIGHT");
        assert_eq!(i64::from_le_bytes(out[16..24].try_into().unwrap()), 2);
        let r = &out[40 + 32..40 + 64];
        assert_eq!(i64::from_le_bytes(r[0..8].try_into().unwrap()), 11_000);
        assert_eq!(i32::from_le_bytes(r[16..20].try_into().unwrap()), 5_000);
        assert_eq!(u32::from_le_bytes(r[28..32].try_into().unwrap()), PAD_ONLY | HANDED_OFF);
        // Only after `from`.
        let mut out = Vec::new();
        assert_eq!(f.write(&mut out, 10_500, 13_000, 77).unwrap(), 1);
    }

    #[test]
    fn a_full_ring_keeps_the_newest() {
        let f = FlightRecorder::new(1);
        let cap = f.capacity() as i64;
        for i in 0..cap + 10 {
            f.state(i as u32, i + 1, 0, 0);
        }
        let mut out = Vec::new();
        let n = f.write(&mut out, 0, 0, 0).unwrap();
        assert_eq!(n as i64, cap - 2); // the newest, and one for the entry the writer may be on
        let first = i64::from_le_bytes(out[40..48].try_into().unwrap());
        assert_eq!(first, 10 + 1 + 1);
    }
}
