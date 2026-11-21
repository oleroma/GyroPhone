//! The memory the input process and the companion both map (Windows: a named file mapping in the session): the
//! input side writes, the companion reads, and neither ever waits on the other. Byte for byte the C# `SharedRegion`
//! layout version 2, so the C# companion reads what this writes:
//! `HeldMirror` (held outputs and the PC controller, for the overlays), `OverlayInbox` (the phone's overlay packets),
//! `DebugQueue` (Diagnostics' raw events) and `MoveRing` (the mouse moves the Delivery test times).
//!
//! Every part has one writer at a time (the engine under its lock; the debug queue under its own lock) and one
//! reader. The reader sides here exist for the tests and the replay tool; in the product the companion reads.
//!
//! Also `InjectedView`, the engine's in-process copy of the injected held set.

use crate::mapping::{Ds4Frame, InjectEvent, X360Frame};
use crate::state_frame::{StateFrame, KEY_BITMAP_SIZE};
use crate::wire;
use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::sync::atomic::{fence, AtomicI32, AtomicI64, AtomicU8, Ordering};
use std::sync::Arc;

pub const MAGIC: i32 = 0x3153_5047; // "GPS1"
/// Bumped whenever the layout changes: a companion and an input process of different builds never share one.
pub const LAYOUT_VERSION: i32 = 2;

const PAGE: usize = 4096;
const HEADER_BYTES: usize = 64;

const fn align_page(n: usize) -> usize {
    (n + PAGE - 1) & !(PAGE - 1)
}

pub const HELD_OFF: usize = HEADER_BYTES;
pub const OVERLAY_OFF: usize = align_page(HELD_OFF + HeldMirror::BYTES_NEEDED);
pub const DEBUG_OFF: usize = align_page(OVERLAY_OFF + OverlayInbox::BYTES_NEEDED);
pub const MOVES_OFF: usize = align_page(DEBUG_OFF + DebugQueue::BYTES_NEEDED);
pub const TOTAL_BYTES: usize = align_page(MOVES_OFF + MoveRing::BYTES_NEEDED);

/// Where the bytes live: this process's heap (tests, one-process use) or a mapping someone else unmaps.
enum Backing {
    Heap,
    /// A view mapped by the caller; `release` unmaps it.
    Mapped(Option<Box<dyn FnOnce() + Send + Sync>>),
}

struct Mem {
    base: *mut u8,
    backing: Backing,
}

// SAFETY: the region is plain bytes shared between threads and processes by design; every access goes through
// atomics or the single-writer rules written on each part.
unsafe impl Send for Mem {}
unsafe impl Sync for Mem {}

impl Drop for Mem {
    fn drop(&mut self) {
        match &mut self.backing {
            // SAFETY: allocated in `in_process` with this layout.
            Backing::Heap => unsafe { dealloc(self.base, heap_layout()) },
            Backing::Mapped(release) => {
                if let Some(f) = release.take() {
                    f()
                }
            }
        }
    }
}

fn heap_layout() -> Layout {
    Layout::from_size_align(TOTAL_BYTES, PAGE).expect("region layout")
}

/// Why a mapping can't be used.
#[derive(Debug, PartialEq, Eq)]
pub enum RegionError {
    /// An older or newer build still has it open.
    OtherLayout,
}

/// The whole region. Cheap to clone; the memory stays while any clone or part is alive.
#[derive(Clone)]
pub struct SharedRegion {
    mem: Arc<Mem>,
}

impl SharedRegion {
    /// A region in this process only (tests, and one-process use).
    pub fn in_process() -> SharedRegion {
        // SAFETY: non-zero size, valid alignment.
        let base = unsafe { alloc_zeroed(heap_layout()) };
        assert!(!base.is_null(), "out of memory");
        let r = SharedRegion { mem: Arc::new(Mem { base, backing: Backing::Heap }) };
        r.write_header();
        r
    }

    /// Takes a view the caller mapped: `TOTAL_BYTES` readable and writable bytes at `base`, page aligned, either
    /// all zeros (new) or a region of this layout. `release` unmaps it when the last part is dropped.
    ///
    /// # Safety
    /// `base` must point to such a view and stay mapped until `release` runs.
    pub unsafe fn from_view(
        base: *mut u8,
        release: Box<dyn FnOnce() + Send + Sync>,
    ) -> Result<SharedRegion, RegionError> {
        let r = SharedRegion { mem: Arc::new(Mem { base, backing: Backing::Mapped(Some(release)) }) };
        // A new mapping is zeros (a valid empty region): both sides writing the header at once write the same bytes
        // apart from the owner's pid.
        let magic = r.i32_at(0).load(Ordering::SeqCst);
        if magic == 0 {
            r.write_header();
        } else if magic != MAGIC || r.i32_at(4).load(Ordering::Relaxed) != LAYOUT_VERSION {
            return Err(RegionError::OtherLayout);
        }
        Ok(r)
    }

    fn write_header(&self) {
        self.i32_at(4).store(LAYOUT_VERSION, Ordering::Relaxed);
        self.i32_at(8).store(std::process::id() as i32, Ordering::Relaxed);
        self.i32_at(0).store(MAGIC, Ordering::SeqCst);
    }

    fn i32_at(&self, off: usize) -> &AtomicI32 {
        // SAFETY: inside the region, 4-aligned (the base is page aligned).
        unsafe { &*(self.mem.base.add(off) as *const AtomicI32) }
    }

    /// The process that made it.
    pub fn owner_pid(&self) -> i32 {
        self.i32_at(8).load(Ordering::Relaxed)
    }

    fn part(&self, off: usize) -> Part {
        // SAFETY: every part offset is inside the region.
        Part { p: unsafe { self.mem.base.add(off) }, _mem: self.mem.clone() }
    }

    pub fn held(&self) -> HeldMirror {
        HeldMirror { m: self.part(HELD_OFF) }
    }

    pub fn overlays(&self) -> OverlayInbox {
        OverlayInbox { m: self.part(OVERLAY_OFF) }
    }

    pub fn debug(&self) -> DebugQueue {
        DebugQueue { m: self.part(DEBUG_OFF) }
    }

    pub fn moves(&self) -> MoveRing {
        MoveRing { m: self.part(MOVES_OFF) }
    }

    /// The raw bytes (tests check the layout against the C# offsets).
    pub fn bytes(&self) -> &[AtomicU8] {
        // SAFETY: AtomicU8 has the layout of u8 and the region is TOTAL_BYTES long.
        unsafe { std::slice::from_raw_parts(self.mem.base as *const AtomicU8, TOTAL_BYTES) }
    }
}

/// One part's bytes; keeps the region alive.
#[derive(Clone)]
struct Part {
    p: *mut u8,
    _mem: Arc<Mem>,
}

// SAFETY: as for `Mem`.
unsafe impl Send for Part {}
unsafe impl Sync for Part {}

impl Part {
    fn i32(&self, off: usize) -> &AtomicI32 {
        debug_assert!(off % 4 == 0);
        // SAFETY: callers pass offsets inside their part, aligned (parts start 64- or page-aligned).
        unsafe { &*(self.p.add(off) as *const AtomicI32) }
    }

    fn i64(&self, off: usize) -> &AtomicI64 {
        debug_assert!(off % 8 == 0);
        // SAFETY: as above.
        unsafe { &*(self.p.add(off) as *const AtomicI64) }
    }

    fn u8(&self, off: usize) -> &AtomicU8 {
        // SAFETY: as above.
        unsafe { &*(self.p.add(off) as *const AtomicU8) }
    }

    /// Plain bytes into the part. Single writer; a reader that races is told by the part's own protocol
    /// (sequence lock, or the head it has not been given yet).
    fn put(&self, off: usize, src: &[u8]) {
        // SAFETY: callers keep `off + src.len()` inside their part.
        unsafe { std::ptr::copy_nonoverlapping(src.as_ptr(), self.p.add(off), src.len()) }
    }

    fn get(&self, off: usize, dst: &mut [u8]) {
        // SAFETY: as above.
        unsafe { std::ptr::copy_nonoverlapping(self.p.add(off) as *const u8, dst.as_mut_ptr(), dst.len()) }
    }

    fn zero(&self, off: usize, len: usize) {
        // SAFETY: as above.
        unsafe { std::ptr::write_bytes(self.p.add(off), 0, len) }
    }
}

// ---------------------------------------------------------------------------------------------------------
// HeldMirror
// ---------------------------------------------------------------------------------------------------------

/// What the overlay windows read from the input side many times a second: whether a phone is connected, the newest
/// STATE's buttons, keys and triggers, and the PC controller while the phone's "Use PC controller for gyro ratchet"
/// is on. The engine writes it under its lock; readers copy it without any lock (a sequence lock in the shared
/// memory), so a reader paused mid-copy can never keep the input thread waiting.
#[derive(Clone)]
pub struct HeldMirror {
    m: Part,
}

/// A reader's copy of the mirror.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HeldCopy {
    pub connected: bool,
    pub pad_gyro: bool,
    pub pc_on: bool,
    pub buttons: u16,
    pub hat: u8,
    pub mouse_buttons: u8,
    pub lt: u16,
    pub rt: u16,
    pub keys: [u8; KEY_BITMAP_SIZE],
    pub pc_held: u32,
    pub pc_axes: [f32; 6],
}

impl HeldMirror {
    const SEQ: usize = 0;
    const CONNECTED: usize = 4;
    const PAD_GYRO: usize = 5;
    const PC_ON: usize = 6;
    const BUTTONS: usize = 8;
    const HAT: usize = 10;
    const MOUSE: usize = 11;
    const LT: usize = 12;
    const RT: usize = 14;
    const KEYS: usize = 16;
    const PC_HELD: usize = 48;
    const PC_AXES: usize = 52;

    /// The bytes it takes in the region.
    pub const BYTES_NEEDED: usize = 128;

    fn seq(&self) -> &AtomicI32 {
        self.m.i32(Self::SEQ)
    }

    fn begin_write(&self) {
        let s = self.seq().load(Ordering::Relaxed);
        self.seq().store(s.wrapping_add(1), Ordering::SeqCst); // odd: a write is under way
        fence(Ordering::SeqCst);
    }

    fn end_write(&self) {
        let s = self.seq().load(Ordering::Relaxed);
        self.seq().store(s.wrapping_add(1), Ordering::SeqCst);
    }

    /// A new input process: nothing connected or held (and a write a crashed one left half done is ended).
    pub fn reset(&self) {
        let mut s = self.seq().load(Ordering::SeqCst);
        if s & 1 == 0 {
            s = s.wrapping_add(1);
        }
        self.seq().store(s, Ordering::SeqCst);
        fence(Ordering::SeqCst);
        self.m.zero(4, Self::BYTES_NEEDED - 4);
        self.seq().store(s.wrapping_add(1), Ordering::SeqCst);
    }

    pub fn set_connected(&self, on: bool) {
        self.begin_write();
        self.m.u8(Self::CONNECTED).store(on as u8, Ordering::Relaxed);
        self.end_write();
    }

    pub fn set_pad_gyro(&self, on: bool) {
        self.begin_write();
        self.m.u8(Self::PAD_GYRO).store(on as u8, Ordering::Relaxed);
        self.end_write();
    }

    /// The newest STATE's buttons, hat, mouse buttons, triggers and keys.
    pub fn publish_held_outputs(&self, s: &StateFrame) {
        self.begin_write();
        self.m.put(Self::BUTTONS, &s.buttons.to_le_bytes());
        self.m.put(Self::HAT, &[s.hat, s.mouse_buttons]);
        self.m.put(Self::LT, &s.lt.to_le_bytes());
        self.m.put(Self::RT, &s.rt.to_le_bytes());
        self.m.put(Self::KEYS, &s.keys);
        self.end_write();
    }

    /// The PC controller now: found, its buttons as place bits, its sticks and triggers (lx, ly, rx, ry, l2, r2).
    pub fn publish_pc_pad(&self, connected: bool, held: u32, axes: &[f32]) {
        self.begin_write();
        self.m.u8(Self::PC_ON).store(connected as u8, Ordering::Relaxed);
        self.m.put(Self::PC_HELD, &held.to_le_bytes());
        for i in 0..6 {
            let v = axes.get(i).copied().unwrap_or(0.0);
            self.m.put(Self::PC_AXES + i * 4, &v.to_le_bytes());
        }
        self.end_write();
    }

    pub fn connected(&self) -> bool {
        self.m.u8(Self::CONNECTED).load(Ordering::SeqCst) != 0
    }

    pub fn pad_gyro_on(&self) -> bool {
        self.m.u8(Self::PAD_GYRO).load(Ordering::SeqCst) != 0
    }

    /// The sequence number (tests: even when no write is under way).
    pub fn sequence(&self) -> i32 {
        self.seq().load(Ordering::SeqCst)
    }

    /// A whole copy, taken the way the companion takes it.
    pub fn read(&self) -> HeldCopy {
        loop {
            let mut s = self.seq().load(Ordering::SeqCst);
            // A write takes well under a microsecond; one that never ends was cut off by the input process
            // crashing: give up after a while and take the bytes as they are, so no reader hangs.
            let mut waited: Option<std::time::Instant> = None;
            while s & 1 != 0 {
                let since = *waited.get_or_insert_with(std::time::Instant::now);
                if since.elapsed().as_millis() >= 50 {
                    break;
                }
                std::thread::yield_now();
                s = self.seq().load(Ordering::SeqCst);
            }
            let mut b = [0u8; Self::BYTES_NEEDED];
            self.m.get(0, &mut b);
            fence(Ordering::SeqCst);
            if self.seq().load(Ordering::SeqCst) != s {
                continue;
            }
            let mut keys = [0u8; KEY_BITMAP_SIZE];
            keys.copy_from_slice(&b[Self::KEYS..Self::KEYS + KEY_BITMAP_SIZE]);
            let mut pc_axes = [0f32; 6];
            for (i, a) in pc_axes.iter_mut().enumerate() {
                let o = Self::PC_AXES + i * 4;
                *a = f32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]]);
            }
            return HeldCopy {
                connected: b[Self::CONNECTED] != 0,
                pad_gyro: b[Self::PAD_GYRO] != 0,
                pc_on: b[Self::PC_ON] != 0,
                buttons: u16::from_le_bytes([b[Self::BUTTONS], b[Self::BUTTONS + 1]]),
                hat: b[Self::HAT],
                mouse_buttons: b[Self::MOUSE],
                lt: u16::from_le_bytes([b[Self::LT], b[Self::LT + 1]]),
                rt: u16::from_le_bytes([b[Self::RT], b[Self::RT + 1]]),
                keys,
                pc_held: u32::from_le_bytes([
                    b[Self::PC_HELD],
                    b[Self::PC_HELD + 1],
                    b[Self::PC_HELD + 2],
                    b[Self::PC_HELD + 3],
                ]),
                pc_axes,
            };
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// OverlayInbox
// ---------------------------------------------------------------------------------------------------------

/// What a slot of the overlay inbox holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum OverlayKind {
    Overlay = 0,
    Samples = 1,
    Layout = 2,
    /// A new session: the next OVERLAY totals are the base.
    TotalsReset = 3,
    /// The same phone in a new session: its next layout is taken again and its press counts start again.
    Renew = 4,
    /// Overlay data starts again (phone gone, overlays off); with the flag the layout goes too.
    Clear = 5,
    /// An OVERLAY_FRAMES piece: the pad's button frames and bars.
    Frames = 6,
}

/// A slot as a reader sees it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OverlaySlot {
    pub kind: u8,
    pub rx_us: i64,
    pub rx_ms: i64,
    pub gated: bool,
    pub gate_axes: u8,
    pub gate_presses: u8,
    pub gate_held: bool,
    pub flag: bool,
    pub data: Vec<u8>,
}

/// The phone's OVERLAY, OVERLAY_SAMPLES, OVERLAY_LAYOUT and OVERLAY_FRAMES bytes as they came (decrypted), and marks
/// for when the overlay data starts again. A fixed ring of slots: putting one in is a byte copy and a few writes,
/// nothing is made, nothing waits. When the ring is full the newest is dropped; a dropped mark is kept as
/// "clear lost", so the overlay side starts again rather than carry on from a reset it never saw.
#[derive(Clone)]
pub struct OverlayInbox {
    m: Part,
}

const fn max3(a: usize, b: usize, c: usize) -> usize {
    let ab = if a > b { a } else { b };
    if ab > c {
        ab
    } else {
        c
    }
}

impl OverlayInbox {
    pub const CAPACITY: i32 = 256;
    pub const MAX_BYTES: usize =
        max3(wire::OVERLAY_PLAIN, wire::OVERLAY_SAMPLES_PLAIN, wire::OVERLAY_LAYOUT_PLAIN);

    const HEAD: usize = 0;
    const TAIL: usize = 64;
    const CLEAR_LOST: usize = 128;
    const DROPPED: usize = 136;
    const SLOTS: usize = 192;
    const S_KIND: usize = 0;
    const S_FLAGS: usize = 1;
    const S_AXES: usize = 2;
    const S_PRESSES: usize = 3;
    const S_LENGTH: usize = 4;
    const S_RX_US: usize = 8;
    const S_RX_MS: usize = 16;
    const S_DATA: usize = 32;
    const FLAG_GATED: u8 = 1;
    const FLAG_HELD: u8 = 2;
    const FLAG_MARK: u8 = 4;
    pub const SLOT_BYTES: usize = (Self::S_DATA + Self::MAX_BYTES + 63) & !63;

    /// The bytes the ring takes in the region.
    pub const BYTES_NEEDED: usize = Self::SLOTS + Self::CAPACITY as usize * Self::SLOT_BYTES;

    fn slot_off(i: i32) -> usize {
        Self::SLOTS + (i & (Self::CAPACITY - 1)) as usize * Self::SLOT_BYTES
    }

    /// Overlay packets and marks dropped because the ring was full (the reader fell behind or is gone).
    pub fn dropped(&self) -> i64 {
        self.m.i64(Self::DROPPED).load(Ordering::SeqCst)
    }

    /// A packet (Overlay, Samples, Layout, Frames); false when full or too long.
    pub fn put(&self, kind: OverlayKind, plain: &[u8], rx_us: i64, rx_ms: i64) -> bool {
        self.write(kind, plain, rx_us, rx_ms, 0, 0, 0)
    }

    /// An OVERLAY with the PC controller's gate: the axes left on, a ratchet held, its ratchet presses.
    pub fn put_gated(&self, plain: &[u8], rx_us: i64, rx_ms: i64, axes: u8, held: bool, presses: u8) -> bool {
        let flags = Self::FLAG_GATED | if held { Self::FLAG_HELD } else { 0 };
        self.write(OverlayKind::Overlay, plain, rx_us, rx_ms, flags, axes, presses)
    }

    /// A mark (TotalsReset, Renew, Clear).
    pub fn mark(&self, kind: OverlayKind, now_us: i64, flag: bool) {
        if !self.write(kind, &[], now_us, 0, if flag { Self::FLAG_MARK } else { 0 }, 0, 0) {
            self.m.i32(Self::CLEAR_LOST).store(1, Ordering::SeqCst);
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn write(&self, kind: OverlayKind, plain: &[u8], rx_us: i64, rx_ms: i64, flags: u8, axes: u8, presses: u8) -> bool {
        let head = self.m.i32(Self::HEAD).load(Ordering::Relaxed);
        let waiting = head.wrapping_sub(self.m.i32(Self::TAIL).load(Ordering::SeqCst));
        // A tail the reader's side scribbled on (waiting below zero) counts as full: nothing is written over.
        if !(0..Self::CAPACITY).contains(&waiting) || plain.len() > Self::MAX_BYTES {
            self.m.i64(Self::DROPPED).fetch_add(1, Ordering::SeqCst);
            return false;
        }
        let s = Self::slot_off(head);
        self.m.put(s + Self::S_DATA, plain);
        self.m.put(s + Self::S_KIND, &[kind as u8, flags, axes, presses]);
        self.m.put(s + Self::S_LENGTH, &(plain.len() as u16).to_le_bytes());
        self.m.put(s + Self::S_RX_US, &rx_us.to_le_bytes());
        self.m.put(s + Self::S_RX_MS, &rx_ms.to_le_bytes());
        // Hands the slot to the reader.
        self.m.i32(Self::HEAD).store(head.wrapping_add(1), Ordering::SeqCst);
        true
    }

    // ---- the reader's side (the companion in the product) ----

    /// True (once) when a mark was dropped: the reader starts everything again.
    pub fn clear_lost(&self) -> bool {
        self.m.i32(Self::CLEAR_LOST).swap(0, Ordering::SeqCst) != 0
    }

    /// Takes the oldest slot not yet read.
    pub fn try_take(&self) -> Option<OverlaySlot> {
        let tail = self.m.i32(Self::TAIL).load(Ordering::Relaxed);
        if tail == self.m.i32(Self::HEAD).load(Ordering::SeqCst) {
            return None;
        }
        let s = Self::slot_off(tail);
        let mut h = [0u8; Self::S_DATA];
        self.m.get(s, &mut h);
        let length = (u16::from_le_bytes([h[Self::S_LENGTH], h[Self::S_LENGTH + 1]]) as usize).min(Self::MAX_BYTES);
        let mut data = vec![0u8; length];
        self.m.get(s + Self::S_DATA, &mut data);
        let flags = h[Self::S_FLAGS];
        let slot = OverlaySlot {
            kind: h[Self::S_KIND],
            rx_us: i64::from_le_bytes(h[Self::S_RX_US..Self::S_RX_US + 8].try_into().unwrap()),
            rx_ms: i64::from_le_bytes(h[Self::S_RX_MS..Self::S_RX_MS + 8].try_into().unwrap()),
            gated: flags & Self::FLAG_GATED != 0,
            gate_axes: h[Self::S_AXES],
            gate_presses: h[Self::S_PRESSES],
            gate_held: flags & Self::FLAG_HELD != 0,
            flag: flags & Self::FLAG_MARK != 0,
            data,
        };
        self.m.i32(Self::TAIL).store(tail.wrapping_add(1), Ordering::SeqCst);
        Some(slot)
    }

    /// Drops everything waiting.
    pub fn skip_all(&self) {
        let head = self.m.i32(Self::HEAD).load(Ordering::SeqCst);
        self.m.i32(Self::TAIL).store(head, Ordering::SeqCst);
    }
}

// ---------------------------------------------------------------------------------------------------------
// DebugQueue
// ---------------------------------------------------------------------------------------------------------

/// One raw Diagnostics event from the input path: what was injected or given to the virtual pad, copied as is.
/// In the shared memory it is the C# `DebugRaw` struct (sequential layout): us (i64) at 0, kind (u8) at 8, the
/// inject event at 12 (kind, usage, scan u16, vk u16, 2 spare, flags u32, dx, dy, mouse data: 24 bytes), the
/// Xbox frame at 36 (buttons u16, lx, ly, rx, ry i16, lt, rt: 12 bytes), the DualShock frame at 48 (buttons u16,
/// special, hat, lx, ly, rx, ry, lt, rt: 10 bytes); 64 bytes in all.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DebugRaw {
    Inject(InjectEvent),
    X360(X360Frame),
    Ds4(Ds4Frame),
}

/// Diagnostics' raw events on their way from the input path to the companion, which makes them into lines: the
/// input process makes no text for Diagnostics. One writer at a time (the caller's lock), one reader. When full the
/// newest is dropped and counted.
#[derive(Clone)]
pub struct DebugQueue {
    m: Part,
}

impl DebugQueue {
    /// Raw events the input path may queue between two drains.
    pub const CAPACITY: i32 = 16_384;
    pub const SLOT_BYTES: usize = 64;

    const HEAD: usize = 0;
    const TAIL: usize = 64;
    const DROPPED: usize = 128;
    const SLOTS: usize = 192;
    pub const R_US: usize = 0;
    pub const R_KIND: usize = 8;
    pub const R_INJECT: usize = 12;
    pub const R_X360: usize = 36;
    pub const R_DS4: usize = 48;

    /// The bytes it takes in the region.
    pub const BYTES_NEEDED: usize = Self::SLOTS + Self::CAPACITY as usize * Self::SLOT_BYTES;

    fn slot_off(i: i32) -> usize {
        Self::SLOTS + (i & (Self::CAPACITY - 1)) as usize * Self::SLOT_BYTES
    }

    /// Raw events dropped because the queue was full (the reader fell behind, or there is none).
    pub fn dropped(&self) -> i64 {
        self.m.i64(Self::DROPPED).load(Ordering::SeqCst)
    }

    /// The event's bytes as the C# struct holds them. Only the member the kind names is written, as in C#
    /// (the others keep what the slot held before; the reader never looks at them).
    pub fn encode(us: i64, raw: &DebugRaw, slot: &mut [u8; Self::SLOT_BYTES]) {
        slot[Self::R_US..Self::R_US + 8].copy_from_slice(&us.to_le_bytes());
        match raw {
            DebugRaw::Inject(e) => {
                slot[Self::R_KIND] = 0;
                let b = &mut slot[Self::R_INJECT..Self::R_INJECT + 24];
                b[0] = e.kind as u8;
                b[1] = e.usage;
                b[2..4].copy_from_slice(&e.scan.to_le_bytes());
                b[4..6].copy_from_slice(&e.vk.to_le_bytes());
                b[8..12].copy_from_slice(&e.flags.to_le_bytes());
                b[12..16].copy_from_slice(&e.dx.to_le_bytes());
                b[16..20].copy_from_slice(&e.dy.to_le_bytes());
                b[20..24].copy_from_slice(&e.mouse_data.to_le_bytes());
            }
            DebugRaw::X360(f) => {
                slot[Self::R_KIND] = 1;
                let b = &mut slot[Self::R_X360..Self::R_X360 + 12];
                b[0..2].copy_from_slice(&f.buttons.to_le_bytes());
                b[2..4].copy_from_slice(&f.lx.to_le_bytes());
                b[4..6].copy_from_slice(&f.ly.to_le_bytes());
                b[6..8].copy_from_slice(&f.rx.to_le_bytes());
                b[8..10].copy_from_slice(&f.ry.to_le_bytes());
                b[10] = f.lt;
                b[11] = f.rt;
            }
            DebugRaw::Ds4(f) => {
                slot[Self::R_KIND] = 2;
                let b = &mut slot[Self::R_DS4..Self::R_DS4 + 10];
                b[0..2].copy_from_slice(&f.buttons.to_le_bytes());
                b[2] = f.special;
                b[3] = f.hat;
                b[4] = f.lx;
                b[5] = f.ly;
                b[6] = f.rx;
                b[7] = f.ry;
                b[8] = f.lt;
                b[9] = f.rt;
            }
        }
    }

    /// Queues one event; false when full (dropped, counted). One writer at a time.
    pub fn push(&self, us: i64, raw: &DebugRaw) -> bool {
        let head = self.m.i32(Self::HEAD).load(Ordering::Relaxed);
        let waiting = head.wrapping_sub(self.m.i32(Self::TAIL).load(Ordering::SeqCst));
        if !(0..Self::CAPACITY).contains(&waiting) {
            self.m.i64(Self::DROPPED).fetch_add(1, Ordering::SeqCst);
            return false;
        }
        let off = Self::slot_off(head);
        let mut slot = [0u8; Self::SLOT_BYTES];
        self.m.get(off, &mut slot);
        Self::encode(us, raw, &mut slot);
        self.m.put(off, &slot);
        self.m.i32(Self::HEAD).store(head.wrapping_add(1), Ordering::SeqCst);
        true
    }

    // ---- the reader's side (the companion in the product) ----

    /// The oldest event waiting, if any: its time and the event (None for a kind this build doesn't know).
    pub fn try_take(&self) -> Option<(i64, Option<DebugRaw>)> {
        let tail = self.m.i32(Self::TAIL).load(Ordering::Relaxed);
        if tail == self.m.i32(Self::HEAD).load(Ordering::SeqCst) {
            return None;
        }
        let mut s = [0u8; Self::SLOT_BYTES];
        self.m.get(Self::slot_off(tail), &mut s);
        self.m.i32(Self::TAIL).store(tail.wrapping_add(1), Ordering::SeqCst);
        let us = i64::from_le_bytes(s[0..8].try_into().unwrap());
        let u16_at = |o: usize| u16::from_le_bytes([s[o], s[o + 1]]);
        let i32_at = |o: usize| i32::from_le_bytes([s[o], s[o + 1], s[o + 2], s[o + 3]]);
        let raw = match s[Self::R_KIND] {
            0 => {
                let o = Self::R_INJECT;
                crate::mapping::InjectKind::from_u8(s[o]).map(|kind| {
                    DebugRaw::Inject(InjectEvent {
                        kind,
                        usage: s[o + 1],
                        scan: u16_at(o + 2),
                        vk: u16_at(o + 4),
                        flags: i32_at(o + 8) as u32,
                        dx: i32_at(o + 12),
                        dy: i32_at(o + 16),
                        mouse_data: i32_at(o + 20),
                    })
                })
            }
            1 => {
                let o = Self::R_X360;
                Some(DebugRaw::X360(X360Frame {
                    buttons: u16_at(o),
                    lx: u16_at(o + 2) as i16,
                    ly: u16_at(o + 4) as i16,
                    rx: u16_at(o + 6) as i16,
                    ry: u16_at(o + 8) as i16,
                    lt: s[o + 10],
                    rt: s[o + 11],
                }))
            }
            2 => {
                let o = Self::R_DS4;
                Some(DebugRaw::Ds4(Ds4Frame {
                    buttons: u16_at(o),
                    special: s[o + 2],
                    hat: s[o + 3],
                    lx: s[o + 4],
                    ly: s[o + 5],
                    rx: s[o + 6],
                    ry: s[o + 7],
                    lt: s[o + 8],
                    rt: s[o + 9],
                }))
            }
            _ => None,
        };
        Some((us, raw))
    }

    /// Drops everything waiting.
    pub fn skip_all(&self) {
        let head = self.m.i32(Self::HEAD).load(Ordering::SeqCst);
        self.m.i32(Self::TAIL).store(head, Ordering::SeqCst);
    }
}

// ---------------------------------------------------------------------------------------------------------
// MoveRing
// ---------------------------------------------------------------------------------------------------------

/// The mouse moves the Delivery test times: each move's hand-off start (performance counter ticks) and its
/// amounts. Written on the input path without a lock; when the reader fell behind (or there is none) the move is
/// dropped and counted.
#[derive(Clone)]
pub struct MoveRing {
    m: Part,
}

impl MoveRing {
    pub const CAPACITY: i64 = 4096;
    const HEAD: usize = 0;
    const TAIL: usize = 64;
    const OVERFLOW: usize = 128;
    const SLOTS: usize = 192;
    const SLOT_BYTES: usize = 16;

    /// The bytes it takes in the region.
    pub const BYTES_NEEDED: usize = Self::SLOTS + Self::CAPACITY as usize * Self::SLOT_BYTES;

    fn slot_off(i: i64) -> usize {
        Self::SLOTS + (i & (Self::CAPACITY - 1)) as usize * Self::SLOT_BYTES
    }

    /// The next move's number.
    pub fn head(&self) -> i64 {
        self.m.i64(Self::HEAD).load(Ordering::SeqCst)
    }

    /// The oldest move the reader still wants.
    pub fn tail(&self) -> i64 {
        self.m.i64(Self::TAIL).load(Ordering::SeqCst)
    }

    pub fn set_tail(&self, v: i64) {
        self.m.i64(Self::TAIL).store(v, Ordering::SeqCst)
    }

    /// Move `i`: ticks, dx, dy.
    pub fn at(&self, i: i64) -> (i64, i32, i32) {
        let mut b = [0u8; 16];
        self.m.get(Self::slot_off(i), &mut b);
        (
            i64::from_le_bytes(b[0..8].try_into().unwrap()),
            i32::from_le_bytes(b[8..12].try_into().unwrap()),
            i32::from_le_bytes(b[12..16].try_into().unwrap()),
        )
    }

    /// Moves dropped because the ring was full since the last call.
    pub fn take_overflow(&self) -> i64 {
        self.m.i64(Self::OVERFLOW).swap(0, Ordering::SeqCst)
    }

    /// Empties it. Only while nothing writes.
    pub fn reset(&self) {
        self.m.i64(Self::TAIL).store(0, Ordering::SeqCst);
        self.m.i64(Self::OVERFLOW).store(0, Ordering::SeqCst);
        self.m.i64(Self::HEAD).store(0, Ordering::SeqCst);
    }

    /// A move's hand-off started at `ticks`. Input path: no lock, nothing made.
    pub fn put(&self, ticks: i64, dx: i32, dy: i32) {
        let h = self.m.i64(Self::HEAD).load(Ordering::Relaxed);
        let waiting = h.wrapping_sub(self.m.i64(Self::TAIL).load(Ordering::SeqCst));
        if !(0..Self::CAPACITY).contains(&waiting) {
            self.m.i64(Self::OVERFLOW).fetch_add(1, Ordering::SeqCst);
            return;
        }
        let mut b = [0u8; 16];
        b[0..8].copy_from_slice(&ticks.to_le_bytes());
        b[8..12].copy_from_slice(&dx.to_le_bytes());
        b[12..16].copy_from_slice(&dy.to_le_bytes());
        self.m.put(Self::slot_off(h), &b);
        self.m.i64(Self::HEAD).store(h.wrapping_add(1), Ordering::SeqCst);
    }
}

// ---------------------------------------------------------------------------------------------------------
// InjectedView
// ---------------------------------------------------------------------------------------------------------

/// The engine's published copy of the injected held set (the keys and mouse buttons it holds down now), for the
/// held-keys marker and a Diagnostics save's header. The engine writes it under its lock after each flush; readers
/// copy it without that lock (a sequence lock), so they never keep the input thread waiting.
pub struct InjectedView {
    seq: AtomicI32,
    keys: [AtomicU8; KEY_BITMAP_SIZE],
    mouse: AtomicU8,
    version: AtomicI32,
}

impl Default for InjectedView {
    fn default() -> Self {
        Self::new()
    }
}

impl InjectedView {
    pub fn new() -> InjectedView {
        InjectedView {
            seq: AtomicI32::new(0),
            keys: std::array::from_fn(|_| AtomicU8::new(0)),
            mouse: AtomicU8::new(0),
            version: AtomicI32::new(0),
        }
    }

    pub fn version(&self) -> i32 {
        self.version.load(Ordering::SeqCst)
    }

    /// One writer at a time (the engine under its lock).
    pub fn publish(&self, keys: &[u8; KEY_BITMAP_SIZE], mouse: u8, version: i32) {
        let s = self.seq.load(Ordering::Relaxed);
        self.seq.store(s.wrapping_add(1), Ordering::SeqCst);
        for (dst, src) in self.keys.iter().zip(keys) {
            dst.store(*src, Ordering::Relaxed);
        }
        self.mouse.store(mouse, Ordering::Relaxed);
        self.version.store(version, Ordering::Relaxed);
        self.seq.store(s.wrapping_add(2), Ordering::SeqCst);
    }

    /// The injected held set into `keys`; returns the mouse buttons and its version.
    pub fn read(&self, keys: &mut [u8; KEY_BITMAP_SIZE]) -> (u8, i32) {
        loop {
            let s = self.seq.load(Ordering::SeqCst);
            if s & 1 != 0 {
                std::hint::spin_loop();
                continue;
            }
            for (dst, src) in keys.iter_mut().zip(&self.keys) {
                *dst = src.load(Ordering::Relaxed);
            }
            let mouse = self.mouse.load(Ordering::Relaxed);
            let version = self.version.load(Ordering::Relaxed);
            if self.seq.load(Ordering::SeqCst) == s {
                return (mouse, version);
            }
        }
    }
}
