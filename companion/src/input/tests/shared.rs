//! The shared memory layout and its rings: offsets pinned to the C# `SharedRegion` layout version 2.

mod common;

use common::rng;
use gp_input::mapping::{Ds4Frame, InjectEvent, InjectKind, X360Frame};
use gp_input::shared::*;
use gp_input::state_frame::StateFrame;
use std::sync::atomic::Ordering;

fn byte(r: &SharedRegion, off: usize) -> u8 {
    r.bytes()[off].load(Ordering::SeqCst)
}

fn i32_at(r: &SharedRegion, off: usize) -> i32 {
    i32::from_le_bytes([byte(r, off), byte(r, off + 1), byte(r, off + 2), byte(r, off + 3)])
}

fn i64_at(r: &SharedRegion, off: usize) -> i64 {
    (i32_at(r, off) as u32 as i64) | ((i32_at(r, off + 4) as i64) << 32)
}

#[test]
fn the_layout_is_the_csharp_one() {
    // HeldMirror at 64 (128 bytes), then each part on its own page.
    assert_eq!(HELD_OFF, 64);
    assert_eq!(OVERLAY_OFF, 4096);
    assert_eq!(OverlayInbox::MAX_BYTES, 264);
    assert_eq!(OverlayInbox::SLOT_BYTES, 320);
    assert_eq!(OverlayInbox::BYTES_NEEDED, 192 + 256 * 320);
    assert_eq!(DEBUG_OFF, 4096 + 86016);
    assert_eq!(DebugQueue::BYTES_NEEDED, 192 + 16384 * 64);
    assert_eq!(MOVES_OFF, DEBUG_OFF + 1052672);
    assert_eq!(MoveRing::BYTES_NEEDED, 192 + 4096 * 16);
    assert_eq!(TOTAL_BYTES, MOVES_OFF + 69632);

    let r = SharedRegion::in_process();
    assert_eq!(i32_at(&r, 0), 0x3153_5047);
    assert_eq!(i32_at(&r, 4), 2);
    assert_eq!(r.owner_pid(), std::process::id() as i32);
}

#[test]
fn a_view_of_another_layout_is_refused_and_a_zeroed_one_is_taken() {
    let layout = std::alloc::Layout::from_size_align(TOTAL_BYTES, 4096).unwrap();
    for (magic, version, ok) in [(0, 0, true), (0x3153_5047, 2, true), (0x3153_5047, 1, false), (0x1234, 2, false)] {
        let p = unsafe { std::alloc::alloc_zeroed(layout) };
        unsafe {
            (p as *mut i32).write(magic);
            (p.add(4) as *mut i32).write(version);
        }
        let addr = p as usize;
        let release = Box::new(move || unsafe { std::alloc::dealloc(addr as *mut u8, layout) });
        let r = unsafe { SharedRegion::from_view(p, release) };
        assert_eq!(r.is_ok(), ok, "magic {magic:x} version {version}");
        if let Ok(r) = r {
            assert_eq!(i32_at(&r, 0), 0x3153_5047);
            assert_eq!(i32_at(&r, 4), 2);
        }
    }
}

#[test]
fn the_held_mirror_writes_the_csharp_offsets() {
    let r = SharedRegion::in_process();
    let held = r.held();
    let mut s = StateFrame::default();
    s.buttons = 0xA1B2;
    s.hat = 3;
    s.mouse_buttons = 5;
    s.lt = 0x1234;
    s.rt = 0x5678;
    for (i, k) in s.keys.iter_mut().enumerate() {
        *k = i as u8 + 1;
    }
    held.set_connected(true);
    held.set_pad_gyro(true);
    held.publish_held_outputs(&s);
    held.publish_pc_pad(true, 0xDEAD_BEEF, &[0.5, -0.25, 1.0]);

    let b = HELD_OFF;
    assert_eq!(held.sequence() & 1, 0);
    assert_eq!(i32_at(&r, b), 8); // four writes
    assert_eq!((byte(&r, b + 4), byte(&r, b + 5), byte(&r, b + 6)), (1, 1, 1));
    assert_eq!((byte(&r, b + 8), byte(&r, b + 9)), (0xB2, 0xA1));
    assert_eq!((byte(&r, b + 10), byte(&r, b + 11)), (3, 5));
    assert_eq!((byte(&r, b + 12), byte(&r, b + 13)), (0x34, 0x12));
    assert_eq!((byte(&r, b + 14), byte(&r, b + 15)), (0x78, 0x56));
    for i in 0..32 {
        assert_eq!(byte(&r, b + 16 + i), i as u8 + 1);
    }
    assert_eq!(i32_at(&r, b + 48) as u32, 0xDEAD_BEEF);
    assert_eq!(f32::from_bits(i32_at(&r, b + 52) as u32), 0.5);
    assert_eq!(f32::from_bits(i32_at(&r, b + 56) as u32), -0.25);
    assert_eq!(f32::from_bits(i32_at(&r, b + 60) as u32), 1.0);
    assert_eq!(i32_at(&r, b + 64), 0); // axes past the ones given are zero

    let c = held.read();
    assert!(c.connected && c.pad_gyro && c.pc_on);
    assert_eq!((c.buttons, c.hat, c.mouse_buttons, c.lt, c.rt), (0xA1B2, 3, 5, 0x1234, 0x5678));
    assert_eq!(c.keys, s.keys);
    assert_eq!(c.pc_held, 0xDEAD_BEEF);
    assert_eq!(c.pc_axes, [0.5, -0.25, 1.0, 0.0, 0.0, 0.0]);
    assert!(held.connected() && held.pad_gyro_on());
}

#[test]
fn a_reset_ends_a_write_cut_off_and_clears_everything() {
    let r = SharedRegion::in_process();
    let held = r.held();
    held.set_connected(true);
    held.publish_pc_pad(true, 7, &[1.0; 6]);
    // A crashed input process left a write half done: the sequence is odd.
    let odd = held.sequence() + 1;
    for (i, b) in odd.to_le_bytes().iter().enumerate() {
        r.bytes()[HELD_OFF + i].store(*b, Ordering::SeqCst);
    }
    held.reset();
    assert_eq!(held.sequence(), odd + 1);
    for i in 4..128 {
        assert_eq!(byte(&r, HELD_OFF + i), 0);
    }
    // And from an even sequence it still ends even.
    held.reset();
    assert_eq!(held.sequence() & 1, 0);
    assert!(!held.read().connected);
}

#[test]
fn overlay_packets_and_marks_cross_in_order() {
    let r = SharedRegion::in_process();
    let inbox = r.overlays();
    let data: Vec<u8> = (0..152u8).collect();
    assert!(inbox.put_gated(&data, 100, 5, 0b101, true, 9));
    inbox.mark(OverlayKind::Clear, 200, true);
    assert!(inbox.put(OverlayKind::Frames, &data[..10], 300, 6));

    // The first slot's bytes, where the C# reader looks.
    let s = OVERLAY_OFF + 192;
    assert_eq!(i32_at(&r, OVERLAY_OFF), 3); // head
    assert_eq!(byte(&r, s), 0); // kind
    assert_eq!(byte(&r, s + 1), 3); // gated | held
    assert_eq!((byte(&r, s + 2), byte(&r, s + 3)), (0b101, 9));
    assert_eq!((byte(&r, s + 4), byte(&r, s + 5)), (152, 0));
    assert_eq!(i64_at(&r, s + 8), 100);
    assert_eq!(i64_at(&r, s + 16), 5);
    assert_eq!(byte(&r, s + 32 + 151), 151);
    // The second slot starts 320 bytes on.
    assert_eq!(byte(&r, s + 320), 5);
    assert_eq!(byte(&r, s + 321), 4);

    let a = inbox.try_take().unwrap();
    assert_eq!(a.kind, OverlayKind::Overlay as u8);
    assert!(a.gated && a.gate_held && !a.flag);
    assert_eq!((a.gate_axes, a.gate_presses, a.rx_us, a.rx_ms), (0b101, 9, 100, 5));
    assert_eq!(a.data, data);
    let b = inbox.try_take().unwrap();
    assert_eq!(b.kind, OverlayKind::Clear as u8);
    assert!(b.flag && !b.gated);
    assert_eq!((b.rx_us, b.data.len()), (200, 0));
    let c = inbox.try_take().unwrap();
    assert_eq!((c.kind, c.data.len(), c.rx_us), (OverlayKind::Frames as u8, 10, 300));
    assert!(inbox.try_take().is_none());
    assert_eq!(i32_at(&r, OVERLAY_OFF + 64), 3); // tail
}

#[test]
fn a_full_inbox_drops_the_newest_and_a_lost_mark_starts_again() {
    let r = SharedRegion::in_process();
    let inbox = r.overlays();
    for i in 0..256 {
        assert!(inbox.put(OverlayKind::Samples, &[i as u8], i, i));
    }
    assert!(!inbox.put(OverlayKind::Samples, &[1], 1, 1));
    assert_eq!(inbox.dropped(), 1);
    assert_eq!(i64_at(&r, OVERLAY_OFF + 136), 1);
    assert!(!inbox.clear_lost());
    inbox.mark(OverlayKind::Renew, 5, false);
    assert_eq!(inbox.dropped(), 2);
    assert_eq!(i32_at(&r, OVERLAY_OFF + 128), 1);
    assert!(inbox.clear_lost());
    assert!(!inbox.clear_lost());
    // The oldest are kept.
    assert_eq!(inbox.try_take().unwrap().data, [0]);
    assert!(inbox.put(OverlayKind::Samples, &[77], 1, 1));
    inbox.skip_all();
    assert!(inbox.try_take().is_none());
    // Too long is dropped whole, never cut.
    assert!(!inbox.put(OverlayKind::Layout, &[0; 265], 1, 1));
    assert!(inbox.put(OverlayKind::Layout, &[0; 264], 1, 1));
    assert_eq!(inbox.dropped(), 3);
}

#[test]
fn the_inbox_goes_on_past_the_wrap_of_its_counters() {
    let r = SharedRegion::in_process();
    let inbox = r.overlays();
    // Head and tail just under the i32 wrap, as after months of packets.
    for off in [OVERLAY_OFF, OVERLAY_OFF + 64] {
        for (i, b) in (i32::MAX - 2).to_le_bytes().iter().enumerate() {
            r.bytes()[off + i].store(*b, Ordering::SeqCst);
        }
    }
    for i in 0..10u8 {
        assert!(inbox.put(OverlayKind::Overlay, &[i], i as i64, 0));
    }
    for i in 0..10u8 {
        assert_eq!(inbox.try_take().unwrap().data, [i]);
    }
    assert!(inbox.try_take().is_none());
    assert_eq!(inbox.dropped(), 0);
}

fn key(usage: u8) -> InjectEvent {
    InjectEvent {
        kind: InjectKind::Key,
        usage,
        scan: 0xE01D,
        vk: 0x1234,
        flags: 0x8000_000B,
        dx: 0,
        dy: 0,
        mouse_data: 0,
    }
}

#[test]
fn debug_events_are_the_csharp_struct() {
    let r = SharedRegion::in_process();
    let q = r.debug();
    let mouse = InjectEvent {
        kind: InjectKind::Mouse,
        usage: 0,
        scan: 0,
        vk: 0,
        flags: 0x0801,
        dx: -3,
        dy: 70_000,
        mouse_data: -120,
    };
    let x = X360Frame { buttons: 0x9001, lx: -32768, ly: 32767, rx: 1, ry: -1, lt: 200, rt: 7 };
    let d = Ds4Frame { buttons: 0x8040, special: 3, hat: 8, lx: 1, ly: 2, rx: 3, ry: 4, lt: 5, rt: 6 };
    assert!(q.push(0x0102_0304_0506_0708, &DebugRaw::Inject(key(0x1D))));
    assert!(q.push(2, &DebugRaw::Inject(mouse)));
    assert!(q.push(3, &DebugRaw::X360(x)));
    assert!(q.push(4, &DebugRaw::Ds4(d)));

    let s = DEBUG_OFF + 192;
    assert_eq!(i32_at(&r, DEBUG_OFF), 4);
    assert_eq!(i64_at(&r, s), 0x0102_0304_0506_0708);
    assert_eq!(byte(&r, s + 8), 0);
    assert_eq!((byte(&r, s + 12), byte(&r, s + 13)), (0, 0x1D)); // kind, usage
    assert_eq!((byte(&r, s + 14), byte(&r, s + 15)), (0x1D, 0xE0)); // scan
    assert_eq!((byte(&r, s + 16), byte(&r, s + 17)), (0x34, 0x12)); // vk
    assert_eq!(i32_at(&r, s + 20) as u32, 0x8000_000B); // flags
    let m = s + 64;
    assert_eq!(byte(&r, m + 12), 1);
    assert_eq!((i32_at(&r, m + 20), i32_at(&r, m + 24), i32_at(&r, m + 28), i32_at(&r, m + 32)), (0x0801, -3, 70_000, -120));
    let xs = s + 128;
    assert_eq!(byte(&r, xs + 8), 1);
    assert_eq!((byte(&r, xs + 36), byte(&r, xs + 37)), (0x01, 0x90));
    assert_eq!((byte(&r, xs + 38), byte(&r, xs + 39)), (0x00, 0x80));
    assert_eq!((byte(&r, xs + 46), byte(&r, xs + 47)), (200, 7));
    let ds = s + 192;
    assert_eq!(byte(&r, ds + 8), 2);
    assert_eq!((byte(&r, ds + 48), byte(&r, ds + 49), byte(&r, ds + 50), byte(&r, ds + 51)), (0x40, 0x80, 3, 8));
    assert_eq!((byte(&r, ds + 52), byte(&r, ds + 57)), (1, 6));

    assert_eq!(q.try_take(), Some((0x0102_0304_0506_0708, Some(DebugRaw::Inject(key(0x1D))))));
    assert_eq!(q.try_take(), Some((2, Some(DebugRaw::Inject(mouse)))));
    assert_eq!(q.try_take(), Some((3, Some(DebugRaw::X360(x)))));
    assert_eq!(q.try_take(), Some((4, Some(DebugRaw::Ds4(d)))));
    assert_eq!(q.try_take(), None);
}

#[test]
fn a_full_debug_queue_drops_the_newest_and_counts() {
    let r = SharedRegion::in_process();
    let q = r.debug();
    for i in 0..16_384 {
        assert!(q.push(i, &DebugRaw::Inject(key(i as u8))));
    }
    assert!(!q.push(99, &DebugRaw::Inject(key(1))));
    assert!(!q.push(99, &DebugRaw::Inject(key(1))));
    assert_eq!(q.dropped(), 2);
    assert_eq!(i64_at(&r, DEBUG_OFF + 128), 2);
    assert_eq!(q.try_take().unwrap().0, 0);
    assert!(q.push(100, &DebugRaw::Inject(key(2))));
    q.skip_all();
    assert_eq!(q.try_take(), None);
    // The last slot ends inside the queue's part.
    assert!(192 + 16_384 * DebugQueue::SLOT_BYTES <= MOVES_OFF - DEBUG_OFF);
}

#[test]
fn moves_are_stored_until_the_reader_falls_a_ring_behind() {
    let r = SharedRegion::in_process();
    let ring = r.moves();
    ring.put(1_000, 3, -4);
    ring.put(2_000, -5, 6);
    assert_eq!((ring.head(), ring.tail()), (2, 0));
    assert_eq!(ring.at(0), (1_000, 3, -4));
    assert_eq!(ring.at(1), (2_000, -5, 6));
    let s = MOVES_OFF + 192;
    assert_eq!(i64_at(&r, MOVES_OFF), 2);
    assert_eq!(i64_at(&r, s + 16), 2_000);
    assert_eq!((i32_at(&r, s + 24), i32_at(&r, s + 28)), (-5, 6));

    for i in 2..4096 {
        ring.put(i, 1, 1);
    }
    ring.put(9, 9, 9);
    ring.put(9, 9, 9);
    assert_eq!(ring.head(), 4096);
    assert_eq!(ring.take_overflow(), 2);
    assert_eq!(ring.take_overflow(), 0);
    assert_eq!(ring.at(0), (1_000, 3, -4)); // not written over
    ring.set_tail(4000);
    ring.put(7, 7, 7);
    assert_eq!(ring.at(4096), (7, 7, 7));
    ring.reset();
    assert_eq!((ring.head(), ring.tail(), ring.take_overflow()), (0, 0, 0));
}

#[test]
fn a_reader_never_sees_a_half_written_mirror_or_view() {
    let r = SharedRegion::in_process();
    let held = r.held();
    let view = std::sync::Arc::new(InjectedView::new());
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let writer = {
        let (held, view, stop) = (held.clone(), view.clone(), stop.clone());
        std::thread::spawn(move || {
            let mut s = StateFrame::default();
            let mut n = 0u32;
            while !stop.load(Ordering::Relaxed) {
                n = n.wrapping_add(1);
                let v = n as u8;
                s.buttons = u16::from_le_bytes([v, v]);
                s.hat = v;
                s.mouse_buttons = v;
                s.lt = s.buttons;
                s.rt = s.buttons;
                s.keys = [v; 32];
                held.publish_held_outputs(&s);
                view.publish(&[v; 32], v, v as i32);
            }
        })
    };
    let mut keys = [0u8; 32];
    for _ in 0..200_000 {
        let c = held.read();
        let v = c.hat;
        assert_eq!(c.buttons, u16::from_le_bytes([v, v]));
        assert_eq!((c.mouse_buttons, c.lt, c.rt), (v, c.buttons, c.buttons));
        assert!(c.keys.iter().all(|k| *k == v));
        let (mouse, version) = view.read(&mut keys);
        assert!(keys.iter().all(|k| *k == mouse));
        assert_eq!(version, mouse as i32);
    }
    stop.store(true, Ordering::Relaxed);
    writer.join().unwrap();
}

#[test]
fn the_rings_hand_everything_over_between_two_threads() {
    let r = SharedRegion::in_process();
    let (inbox, q) = (r.overlays(), r.debug());
    const N: i64 = 200_000;
    let reader = {
        let (inbox, q) = (inbox.clone(), q.clone());
        std::thread::spawn(move || {
            let (mut next_o, mut next_d, mut got_o, mut got_d) = (0i64, 0i64, 0i64, 0i64);
            let mut idle = 0u32;
            while idle < 2_000_000 {
                let mut any = false;
                if let Some(s) = inbox.try_take() {
                    // In order, never torn: the bytes say the same number as the header.
                    assert!(s.rx_us >= next_o);
                    assert_eq!(s.data, s.rx_us.to_le_bytes());
                    next_o = s.rx_us + 1;
                    got_o += 1;
                    any = true;
                }
                if let Some((us, raw)) = q.try_take() {
                    assert!(us >= next_d);
                    match raw {
                        Some(DebugRaw::Inject(e)) => assert_eq!(e.dx as i64, us),
                        other => panic!("torn event {other:?}"),
                    }
                    next_d = us + 1;
                    got_d += 1;
                    any = true;
                }
                if any {
                    idle = 0;
                } else {
                    idle += 1;
                    if next_o == N && next_d == N {
                        break;
                    }
                    std::hint::spin_loop();
                }
            }
            (got_o, got_d)
        })
    };
    for i in 0..N {
        inbox.put(OverlayKind::Overlay, &i.to_le_bytes(), i, 0);
        let mut e = key(1);
        e.dx = i as i32;
        q.push(i, &DebugRaw::Inject(e));
    }
    let (got_o, got_d) = reader.join().unwrap();
    assert_eq!(got_o + inbox.dropped(), N);
    assert_eq!(got_d + q.dropped(), N);
}

#[test]
fn scribbled_reader_fields_never_make_a_writer_write_outside_its_part() {
    // The companion's side of the memory is not trusted to be sane: whatever the tails say, writes stay in bounds
    // (the region's last page is followed by nothing we own, so a stray write would show as a crash under the
    // sanitizer or as changed guard bytes here).
    let r = SharedRegion::in_process();
    let mut rnd = rng(0x5EED);
    let mut next = move || rnd() as i64;
    let (inbox, q, ring) = (r.overlays(), r.debug(), r.moves());
    for _ in 0..20_000 {
        let v = next();
        for off in [OVERLAY_OFF + 64, DEBUG_OFF + 64, MOVES_OFF + 64, OVERLAY_OFF, DEBUG_OFF, MOVES_OFF] {
            if next() & 3 == 0 {
                for (i, b) in v.to_le_bytes().iter().enumerate() {
                    r.bytes()[off + i].store(*b, Ordering::SeqCst);
                }
            }
        }
        inbox.put(OverlayKind::Samples, &[0xEE; 264], v, v);
        inbox.mark(OverlayKind::Clear, v, true);
        q.push(v, &DebugRaw::X360(X360Frame { buttons: 0xEEEE, lx: -1, ly: -1, rx: -1, ry: -1, lt: 0xEE, rt: 0xEE }));
        ring.put(v, -1, -1);
        let _ = inbox.try_take();
        let _ = q.try_take();
    }
    // The header and the held mirror, which sit before the rings, were never touched.
    assert_eq!(i32_at(&r, 0), 0x3153_5047);
    for i in 4..128 {
        assert_eq!(byte(&r, HELD_OFF + i), 0);
    }
    // The spare bytes between the parts stay zero.
    for off in OVERLAY_OFF + OverlayInbox::BYTES_NEEDED..DEBUG_OFF {
        assert_eq!(byte(&r, off), 0);
    }
    for off in DEBUG_OFF + DebugQueue::BYTES_NEEDED..MOVES_OFF {
        assert_eq!(byte(&r, off), 0);
    }
    for off in MOVES_OFF + MoveRing::BYTES_NEEDED..TOTAL_BYTES {
        assert_eq!(byte(&r, off), 0);
    }
}
