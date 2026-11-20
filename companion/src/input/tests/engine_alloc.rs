//! The input thread makes no new memory per packet: everything it uses is made once and reused, so the allocator
//! (and its locks) never sits between a packet and SendInput. Port of the C# ZeroAllocationTests; here a counting
//! allocator names every allocation made on the test's thread while packets are handled.

mod common;

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use common::harness::{paired, phone1_key, FakeClock, PlainProtector, PHONE1_ID};
use common::phone::FakePhone;
use common::range_n;
use gp_input::codec::{self, LinkInfo};
use gp_input::engine::{EngineOptions, HostEngine, NullHooks};
use gp_input::mapping::{Ds4Frame, InjectEvent, X360Frame};
use gp_input::ports::{KeyMouseSink, NullHostEvents, PadSink, PairPrompt, SinkError};
use gp_input::reports::Ds4Motion;
use gp_input::recorder::InputRecorder;
use gp_input::session::DatagramSender;
use gp_input::settings::HostSettings;
use gp_input::wire::{self, output_mode, packet};

thread_local! {
    static COUNTING: Cell<bool> = const { Cell::new(false) };
    static MADE: Cell<(u64, u64)> = const { Cell::new((0, 0)) };
}

struct Counting;

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        note(layout.size());
        unsafe { System.alloc(layout) }
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        note(new_size);
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

fn note(size: usize) {
    let _ = COUNTING.try_with(|c| {
        if c.get() {
            let _ = MADE.try_with(|m| {
                let (n, b) = m.get();
                m.set((n + 1, b + size as u64));
            });
        }
    });
}

#[global_allocator]
static ALLOC: Counting = Counting;

#[derive(Default)]
struct CountingKeyMouse(AtomicI64);

impl KeyMouseSink for CountingKeyMouse {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        self.0.fetch_add(events.len() as i64, Ordering::Relaxed);
        true
    }
}

#[derive(Default)]
struct CountingSender(AtomicI64, std::sync::Mutex<Option<Vec<u8>>>);

impl DatagramSender for CountingSender {
    fn send(&self, datagram: &[u8], _to: SocketAddr) {
        self.0.fetch_add(1, Ordering::Relaxed);
        // Only the WELCOME is kept (before the measured part).
        if datagram[3] == packet::WELCOME {
            *self.1.lock().unwrap() = Some(datagram.to_vec());
        }
    }
}

/// A pad that plugs and takes every frame.
#[derive(Default)]
struct CountingPad {
    plugged: std::sync::atomic::AtomicU8,
    frames: AtomicI64,
}

impl PadSink for CountingPad {
    fn driver_ok(&self) -> bool {
        true
    }
    fn dual_sense_ok(&self) -> bool {
        true
    }
    fn plugged(&self) -> u8 {
        self.plugged.load(Ordering::Relaxed)
    }
    fn plug(&self, mode: u8) -> Result<bool, SinkError> {
        self.plugged.store(mode, Ordering::Relaxed);
        Ok(true)
    }
    fn unplug(&self) -> Result<(), SinkError> {
        self.plugged.store(output_mode::NONE, Ordering::Relaxed);
        Ok(())
    }
    fn submit_x360(&self, _: &X360Frame) -> Result<(), SinkError> {
        self.frames.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
    fn submit_ds4(&self, _: &Ds4Frame, _: &Ds4Motion) -> Result<(), SinkError> {
        self.frames.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
    fn set_rumble_handler(&self, _: Option<Arc<dyn Fn(u8, u8) + Send + Sync>>) {}
}

struct NoPrompt;

impl PairPrompt for NoPrompt {
    fn show(&self, _: u32, _: &str, _: &str) {}
    fn close(&self, _: u32) {}
}

/// What the phone sends while you play: STATEs (keys, buttons, sticks, mouse, wheel), pings, link info, overlay data.
fn play(phone: &mut FakePhone, count: i32) -> Vec<Vec<u8>> {
    let mut list = Vec::with_capacity(count as usize * 2);
    let mut overlay = [0u8; wire::OVERLAY_PLAIN];
    let samples = [0u8; wire::OVERLAY_SAMPLES_PLAIN];
    let mut link = [0u8; wire::LINK_INFO_PLAIN];
    for i in 0..count {
        list.push(phone.state(|f| {
            f.phone_time_us = f.phone_time_us.wrapping_add(1000);
            f.mouse_total_x += (i % 7) - 3;
            f.mouse_total_y += (i % 5) - 2;
            if i % 50 == 0 {
                f.wheel_total += 120;
            }
            f.lx = (i * 97) as i16;
            f.rt = if i % 2 == 0 { 0 } else { 1023 };
            f.buttons = if (i / 20) % 2 == 0 { 0 } else { 1 };
            f.mouse_buttons = ((i / 30) % 2) as u8;
            // W held for a while, then let go.
            f.set_key(0x1A, (i / 40) % 2 == 0);
        }));
        if i % 16 == 0 {
            gp_input::le::put_u32(&mut overlay, 0, (i * 1000) as u32);
            list.push(phone.sealed(packet::OVERLAY_SAMPLES, &samples));
            list.push(phone.sealed(packet::OVERLAY, &overlay));
        }
        if i % 200 == 0 {
            list.push(phone.ping(i as u64));
        }
        if i % 1000 == 0 {
            codec::encode_link_info(
                &LinkInfo { clock_offset_us: 0, rtt_min_us: 500, send_rate_hz: 1000, rssi_dbm: 0, band: 5, link_mbps: 0, freq_mhz: 0, flags: 0, path: 0 },
                &mut link,
            );
            list.push(phone.sealed(packet::LINK_INFO, &link));
        }
    }
    list
}

fn run_and_measure(diagnostics: bool, mode: u8) -> (u64, String) {
    let mut settings = HostSettings::create_default("DESKTOP-OLEX");
    settings.host_id = range_n(0x10);
    settings.output_mode = mode;
    settings.diagnostics = diagnostics;
    settings.paired_phones.push(paired(&PHONE1_ID, "OnePlus 15", &phone1_key()));
    let clock = Arc::new(FakeClock(AtomicI64::new(1_000_000_000)));
    let sender = Arc::new(CountingSender::default());
    let key_mouse = Arc::new(CountingKeyMouse::default());
    let pad = Arc::new(CountingPad::default());
    let engine = HostEngine::new(
        settings,
        pad.clone(),
        key_mouse.clone(),
        Arc::new(PlainProtector),
        EngineOptions {
            clock: clock.clone(),
            events: Arc::new(NullHostEvents),
            prompt: Arc::new(NoPrompt),
            hooks: Arc::new(NullHooks),
            session_random: None,
            pairing_random: None,
            new_pair_key: None,
            host_key: None,
            shared: None,
            inline_workers: true,
        },
    );
    engine.attach_sender(sender.clone());
    engine.set_recorder(Some(InputRecorder::new()));
    engine.set_overlay(true, true, true, true);

    let mut phone = FakePhone::paired(PHONE1_ID, "OnePlus 15", phone1_key(), "192.168.1.50:40000");
    let hello = phone.hello(None);
    engine.on_datagram(&hello, phone.addr, clock.now());
    let welcome = sender.1.lock().unwrap().clone().expect("welcome");
    assert!(phone.on_welcome(&welcome));

    // The first run warms everything up (buffers grow to their working size). The second is measured, packet by
    // packet, so a failure names where the memory was made.
    let mut made = 0u64;
    let mut where_ = String::new();
    for run in 0..2 {
        let packets = play(&mut phone, 3000);
        made = 0;
        let mut offenders = 0;
        where_.clear();
        for (k, d) in packets.iter().enumerate() {
            clock.advance_us(333);
            let now = clock.now();
            MADE.with(|m| m.set((0, 0)));
            COUNTING.with(|c| c.set(true));
            engine.on_datagram(d, phone.addr, now);
            let (n1, b1) = MADE.with(|m| m.get());
            engine.flush(now);
            COUNTING.with(|c| c.set(false));
            let (n2, b2) = MADE.with(|m| m.get());
            if n2 == 0 {
                continue;
            }
            made += b2;
            offenders += 1;
            if where_.is_empty() {
                where_ = format!("packet {k} of {} (type 0x{:02X}): {n1} allocations ({b1} B) in on_datagram, {} ({} B) in flush", packets.len(), d[3], n2 - n1, b2 - b1);
            }
        }
        if !where_.is_empty() {
            where_.push_str(&format!("; {offenders} packets made memory in run {run}"));
        }
    }
    // It really played: every STATE was taken, moved the mouse and pressed keys, the pings were answered.
    assert!(engine.connected());
    assert!(key_mouse.0.load(Ordering::Relaxed) > 3000, "injected {}", key_mouse.0.load(Ordering::Relaxed));
    assert!(pad.frames.load(Ordering::Relaxed) > 3000, "pad frames {}", pad.frames.load(Ordering::Relaxed));
    assert!(sender.0.load(Ordering::Relaxed) >= 30, "sent {}", sender.0.load(Ordering::Relaxed));
    assert!(engine.snapshot().counters.states >= 6000, "states {}", engine.snapshot().counters.states);
    (made, where_)
}

#[test]
fn the_play_path_makes_no_new_memory() {
    let (made, at) = run_and_measure(false, output_mode::XBOX360);
    assert!(made == 0, "{made} B made while playing; first at {at}");
}

#[test]
fn the_play_path_makes_no_new_memory_with_diagnostics_on() {
    let (made, at) = run_and_measure(true, output_mode::XBOX360);
    assert!(made == 0, "{made} B made while playing; first at {at}");
}

#[test]
fn the_play_path_makes_no_new_memory_with_a_dualsense() {
    let (made, at) = run_and_measure(false, output_mode::DUALSENSE);
    assert!(made == 0, "{made} B made while playing; first at {at}");
}

/// The counter itself: it sees memory made on this thread while counting, and nothing else.
#[test]
fn the_counter_counts() {
    MADE.with(|m| m.set((0, 0)));
    COUNTING.with(|c| c.set(true));
    let v = std::hint::black_box(vec![0u8; 100]);
    COUNTING.with(|c| c.set(false));
    let w = std::hint::black_box(vec![0u8; 100]);
    assert_eq!((1, 100), MADE.with(|m| m.get()));
    drop((v, w));
}
