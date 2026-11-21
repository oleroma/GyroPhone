//! "Use PC controller for gyro ratchet" with the gyro run on the PC: STATE_GYRO's raw samples to
//! mouse, wheel and pad, and reading the controller plugged into the PC. The same cases as the
//! companion's PcGyroTests and GyroGateTests.

use gp_input::gyro::*;
use gp_input::state_frame::StateFrame;
use gp_input::wire::{self, packet, state_flags};

const L1: usize = 4;
const NORTH: usize = 3;
const T0: i64 = 1_000_000_000;

/// The phone's side: seq, ring of the last 8, running totals.
struct Phone {
    all: Vec<(u16, i16, u8)>,
    totals: [i64; 3],
    seq: u16,
    time: u32,
    config: GyroConfig,
}

impl Phone {
    fn new() -> Phone {
        let mut config = GyroConfig::default();
        config.add_route(Route { axis: 0, target: TARGET_MOUSE_X, scale: 1.0 });
        Phone { all: vec![], totals: [0; 3], seq: 0, time: T0 as u32, config }
    }

    /// `count` samples: yaw in deg/s (x16 on the wire) over dt µs each.
    fn add(&mut self, yaw: f32, dt: u16, axes: u8, count: usize) {
        for _ in 0..count {
            let q = (yaw * 16.0).round() as i16;
            self.seq = self.seq.wrapping_add(1);
            self.time = self.time.wrapping_add(dt as u32);
            self.totals[0] = self.totals[0].wrapping_add(q as i64 * dt as i64);
            self.all.push((dt, q, axes));
        }
    }

    fn add_n(&mut self, yaw: f32, count: usize) {
        self.add(yaw, 10_000, 7, count);
    }

    fn frame_with(&self, may_move: bool, clock: bool, touch_x: i32) -> StateFrame {
        let f = StateFrame { hires: true, mouse_total_x: touch_x, ..StateFrame::default() };
        let mut t = PcGyroTail { flags: if may_move { PcGyroTail::FLAG_MAY_MOVE } else { 0 } | if clock { PcGyroTail::FLAG_CLOCK } else { 0 }, ..PcGyroTail::default() };
        let n = MAX_SAMPLES.min(self.all.len());
        t.count = n;
        t.newest_seq = self.seq;
        t.newest_time_us = self.time;
        for i in 0..n {
            let s = self.all[self.all.len() - n + i];
            t.dt_us[i] = s.0;
            t.rate[i] = [s.1, 0, 0];
            t.axes[i] = s.2;
        }
        t.totals = self.totals;
        t.config = self.config.clone();
        // Through the wire, so the codec is what the PC sees.
        let mut b = vec![0u8; wire::STATE_GYRO_PLAIN];
        f.encode(&mut b);
        t.encode(&mut b[wire::STATE_HIRES_PLAIN..]);
        let mut g = StateFrame::default();
        g.decode(&b);
        g
    }

    fn frame(&self) -> StateFrame {
        self.frame_with(true, true, 0)
    }
}

struct Rig {
    gyro: PcGyro,
    gate: GyroGate,
    cfg: PadGyroConfig,
    held_at: Option<Box<dyn Fn(i64) -> u32>>,
}

fn config() -> PadGyroConfig {
    let mut c = PadGyroConfig::default();
    c.enabled = true;
    c.kind[L1] = 7; // Ratchet
    c.kind[NORTH] = PadGyroConfig::KIND_TOGGLE;
    c
}

impl Rig {
    fn new() -> Rig {
        Rig { gyro: PcGyro::default(), gate: GyroGate::default(), cfg: config(), held_at: None }
    }

    /// Applies `f` received at the phone's newest sample time (or `now_us`); returns its mouse x total.
    fn run_at(&mut self, f: &mut StateFrame, now_us: Option<i64>) -> i32 {
        let now = now_us.unwrap_or(if f.has_pc_gyro { T0 + f.tail.newest_time_us.wrapping_sub(T0 as u32) as i64 } else { T0 });
        let held: Option<&dyn HeldAt> = self.held_at.as_ref().map(|h| h as &dyn HeldAt);
        self.gyro.apply(f, now, &self.gate, &self.cfg, held);
        f.mouse_total_x
    }

    fn run(&mut self, mut f: StateFrame) -> i32 {
        self.run_at(&mut f, None)
    }

    /// Gives `place` its own settings while held: sensitivity `sens`, through PAD_GYRO_SET.
    fn held_settings(&mut self, place: usize, sens: f32) {
        self.cfg.kind[place] |= PadGyroConfig::KIND_SETTINGS;
        let mut c = GyroConfig::default();
        c.add_route(Route { axis: 0, target: TARGET_MOUSE_X, scale: 1.0 });
        c.sensitivity[0] = sens;
        let mut b = vec![0u8; wire::PAD_GYRO_SET_PLAIN];
        b[0] = place as u8;
        c.encode(&mut b[2..], 0.0, 0.0);
        self.cfg.decode_held(&b);
    }
}

#[test]
fn each_sample_is_run_once() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 3); // 1 degree each: 16 counts
    assert_eq!(48, r.run(p.frame()));
    assert_eq!(48, r.run(p.frame())); // a repeat moves nothing more
    p.add_n(100.0, 2);
    assert_eq!(80, r.run(p.frame()));
    assert_eq!(5, r.gyro.samples());
    assert_eq!(0, r.gyro.gap_fills());
}

#[test]
fn an_older_packet_moves_nothing() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 3);
    let old = p.frame();
    p.add_n(100.0, 2);
    assert_eq!(80, r.run(p.frame()));
    assert_eq!(80, r.run(old));
    p.add_n(100.0, 1);
    assert_eq!(96, r.run(p.frame()));
}

#[test]
fn a_gap_is_filled_from_the_totals() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 3);
    r.run(p.frame());
    // 20 more, of which the packet carries the last 8: 12 lost.
    p.add_n(100.0, 20);
    assert_eq!(23 * 16, r.run(p.frame()));
    assert_eq!(1, r.gyro.gap_fills());
    assert_eq!(3 + 1 + 8, r.gyro.samples());
}

#[test]
fn a_gap_of_uneven_motion_still_adds_up_to_its_angle() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 2);
    r.run(p.frame());
    p.add(250.0, 4_000, 7, 5); // 1 degree each
    p.add(-50.0, 20_000, 7, 7); // -1 degree each
    p.add_n(100.0, 8); // 1 degree each, in the packet
    assert_eq!(8 * 16, r.run(p.frame()));
}

#[test]
fn after_a_long_silence_the_gap_is_not_filled() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 3);
    r.run(p.frame());
    p.add_n(100.0, 20);
    // 200 ms worth of samples, but a second passed on the PC: the host let go meanwhile.
    let mut f = p.frame();
    assert_eq!(48 + 8 * 16, r.run_at(&mut f, Some(T0 + 2_000_000)));
    assert_eq!(0, r.gyro.gap_fills());
}

#[test]
fn ratchet_cuts_at_the_sample_it_was_held_for() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    // Samples at T0 + 10, 20, .. 80 ms; L1 held from 35 to 65 ms takes out 40, 50 and 60.
    r.held_at = Some(Box::new(|us| if (T0 + 35_000..T0 + 65_000).contains(&us) { 1 << L1 } else { 0 }));
    p.add_n(100.0, 8);
    assert_eq!(5 * 16, r.run(p.frame()));
    assert_eq!(7, r.gyro.effective());
}

#[test]
fn a_held_buttons_own_settings_apply_at_the_sample_it_was_held_for() {
    const LT: usize = 6;
    let (mut r, mut p) = (Rig::new(), Phone::new());
    r.held_settings(LT, 4.0);
    r.held_at = Some(Box::new(|us| if (T0 + 35_000..T0 + 65_000).contains(&us) { 1 << LT } else { 0 }));
    p.add_n(100.0, 8);
    assert_eq!(5 * 16 + 3 * 4, r.run(p.frame()));
}

#[test]
fn the_last_pressed_button_with_settings_wins() {
    const A: usize = 6;
    const B: usize = 7;
    let (mut r, mut p) = (Rig::new(), Phone::new());
    r.held_settings(A, 4.0);
    r.held_settings(B, 8.0);
    // A from 5 ms on, B 25..55 ms: 10, 20 at 4; 30, 40, 50 at 8; 60, 70, 80 at 4.
    r.held_at = Some(Box::new(|us| (if us >= T0 + 5_000 { 1 << A } else { 0 }) | (if (T0 + 25_000..T0 + 55_000).contains(&us) { 1 << B } else { 0 })));
    p.add_n(100.0, 8);
    assert_eq!(5 * 4 + 3 * 8, r.run(p.frame()));
}

#[test]
fn without_the_clock_samples_are_timed_by_arrival() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 8);
    // Arrival at T0 + 1 s: the samples ran from 930 ms to 1000 ms after T0; held from 955 ms takes out the last 5.
    r.held_at = Some(Box::new(|us| if us >= T0 + 955_000 { 1 << L1 } else { 0 }));
    let mut f = p.frame_with(true, false, 0);
    assert_eq!(3 * 16, r.run_at(&mut f, Some(T0 + 1_000_000)));
    assert_eq!(0, r.gyro.effective());
}

#[test]
fn ratchet_enables_turns_axes_on() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    r.held_at = Some(Box::new(|_| 1 << L1));
    p.add(100.0, 10_000, 0, 2);
    assert_eq!(32, r.run(p.frame()));
    r.held_at = Some(Box::new(|_| 0));
    p.add(100.0, 10_000, 0, 2);
    assert_eq!(32, r.run(p.frame()));
    assert_eq!(0, r.gyro.effective());
}

#[test]
fn toggle_flips_every_axis() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    let mut presses = [0u32; PLACES];
    r.gate.update(&PcPadState { connected: true, held: 0, presses: Some(&presses) }, &r.cfg);
    presses[NORTH] += 1;
    r.gate.update(&PcPadState { connected: true, held: 0, presses: Some(&presses) }, &r.cfg);
    assert!(r.gate.toggled());
    p.add_n(100.0, 3);
    let mut f = p.frame();
    assert_eq!(0, r.run_at(&mut f, None));
    assert_eq!(0, f.flags & state_flags::GYRO_ACTIVE);
    p.add(100.0, 10_000, 0, 2);
    let mut f = p.frame();
    assert_eq!(32, r.run_at(&mut f, None));
    assert_ne!(0, f.flags & state_flags::GYRO_ACTIVE);
}

#[test]
fn nothing_moves_while_the_gyro_may_not() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 2);
    assert_eq!(32, r.run(p.frame()));
    p.add_n(100.0, 3);
    let mut f = p.frame_with(false, true, 0);
    assert_eq!(32, r.run_at(&mut f, None));
    assert!(!r.gyro.may_move());
    assert_eq!(0, f.flags & state_flags::GYRO_ACTIVE);
    // Those samples are not run later, nor taken for a gap.
    p.add_n(100.0, 1);
    assert_eq!(48, r.run(p.frame()));
    assert_eq!(0, r.gyro.gap_fills());
}

#[test]
fn seq_and_totals_wrap() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.seq = 65530;
    p.totals[0] = i64::MAX - 5_000_000;
    p.add_n(100.0, 3);
    assert_eq!(48, r.run(p.frame()));
    p.add_n(100.0, 4); // seq past 65535, totals past i64::MAX
    assert_eq!(112, r.run(p.frame()));
    p.add_n(100.0, 15); // and a gap across it
    assert_eq!(22 * 16, r.run(p.frame()));
    assert_eq!(1, r.gyro.gap_fills());
    assert!(p.seq < 100);
}

#[test]
fn frames_without_the_tail_keep_the_offset() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 2);
    assert_eq!(1032, r.run(p.frame_with(true, true, 1000)));
    let plain = StateFrame { hires: true, mouse_total_x: 1010, ..StateFrame::default() };
    assert_eq!(1042, r.run(plain));
}

#[test]
fn rebase_starts_the_seq_and_totals_again() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.add_n(100.0, 5);
    r.run(p.frame());
    r.gyro.rebase();
    let mut q = Phone::new();
    q.add_n(100.0, 2);
    assert_eq!(32, r.run(q.frame()));
    assert_eq!(0, r.gyro.gap_fills());
}

#[test]
fn a_phone_counting_again_is_a_new_base() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.seq = 30000;
    p.add_n(100.0, 5);
    assert_eq!(80, r.run(p.frame()));
    let mut q = Phone::new();
    q.add_n(100.0, 2);
    assert_eq!(112, r.run(q.frame()));
}

#[test]
fn pad_merges_like_the_phone() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.config.route_count = 0;
    p.config.add_route(Route { axis: 0, target: TARGET_PAD + 2, scale: 1.0 }); // yaw to RX
    p.config.add_route(Route { axis: 0, target: TARGET_PAD + 4, scale: 1.0 }); // and LT
    p.add_n(45.0, 1); // half of full deflection
    let mut f = p.frame();
    f.rx = 5000;
    f.lt = 100;
    r.run_at(&mut f, None);
    assert_eq!(5000 + 16383, f.rx);
    assert_eq!(100 + 511, f.lt);
    // The gyro adds to the stick, and the sum stops at full.
    p.add_n(45.0, 1);
    let mut f = p.frame();
    f.rx = -20000;
    f.lt = 800;
    r.run_at(&mut f, None);
    assert_eq!(-20000 + 16383, f.rx);
    assert_eq!(1023, f.lt);
    p.add_n(45.0, 1);
    let mut f = p.frame();
    f.rx = 30000;
    r.run_at(&mut f, None);
    assert_eq!(32767, f.rx);
    // Ratcheted off: the gyro's part goes.
    r.held_at = Some(Box::new(|_| 1 << L1));
    p.add_n(45.0, 1);
    let mut f = p.frame();
    f.rx = 5000;
    r.run_at(&mut f, None);
    assert_eq!(5000, f.rx);
    assert_eq!(0, f.lt);
}

#[test]
fn glide_runs_on_after_a_ratchet() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.config.inertia[0] = true;
    p.add_n(100.0, 8);
    let before = r.run(p.frame());
    r.held_at = Some(Box::new(|_| 1 << L1));
    p.add_n(100.0, 8);
    let after = r.run(p.frame());
    // Off, but gliding on from its speed.
    assert!(after > before + 16, "{before} -> {after}");
    assert!(after < before + 8 * 16);
}

#[test]
fn a_nan_in_the_settings_never_breaks_the_pad() {
    let (mut r, mut p) = (Rig::new(), Phone::new());
    p.config.route_count = 0;
    p.config.add_route(Route { axis: 0, target: TARGET_PAD + 2, scale: f32::NAN });
    p.config.add_route(Route { axis: 0, target: TARGET_PAD + 4, scale: f32::NAN });
    p.config.add_route(Route { axis: 0, target: TARGET_MOUSE_X, scale: f32::INFINITY });
    p.config.sensitivity[0] = f32::NAN;
    p.add_n(45.0, 3);
    let mut f = p.frame();
    f.rx = 5000;
    f.lt = 100;
    r.run_at(&mut f, None);
    assert_eq!((5000, 100), (f.rx, f.lt));
}

#[test]
fn held_history_answers_by_time() {
    let mut h = PadHeldHistory::default();
    assert_eq!(0, h.at(5));
    h.add(100, 1);
    h.add(200, 3);
    h.add(250, 3); // no change
    h.add(300, 0);
    assert_eq!(1, h.at(50)); // before anything kept: the oldest
    assert_eq!(1, h.at(100));
    assert_eq!(1, h.at(199));
    assert_eq!(3, h.at(260));
    assert_eq!(0, h.at(300));
    assert_eq!(0, h.at(10_000));
    for i in 0..PadHeldHistory::CAPACITY as i64 * 2 {
        h.add(1000 + i, (i as u32 & 1) + 1);
    }
    assert_eq!(2, h.at(1000 + PadHeldHistory::CAPACITY as i64 * 2 - 1));
    h.clear();
    assert_eq!(0, h.at(5000));
}

// ---- the wire parts, and reading the controller ----

struct Pad {
    presses: [u32; PLACES],
    held: u32,
}

impl Pad {
    fn new() -> Pad {
        Pad { presses: [0; PLACES], held: 0 }
    }
    fn press(&mut self, place: usize) {
        self.held |= 1 << place;
        self.presses[place] += 1;
    }
    fn release(&mut self, place: usize) {
        self.held &= !(1 << place);
    }
    fn state(&self) -> PcPadState<'_> {
        PcPadState { connected: true, held: self.held, presses: Some(&self.presses) }
    }
}

#[test]
fn sizes_and_round_trip() {
    assert_eq!(334, wire::PC_GYRO_TAIL_SIZE);
    assert_eq!(404, wire::STATE_GYRO_PLAIN);
    assert_eq!(Some(432), packet::size(packet::STATE_GYRO));
    assert_eq!(16, wire::welcome_flags::PC_GYRO);
    assert_eq!(40, wire::PAD_GYRO_PLAIN);

    let mut t = PcGyroTail { flags: PcGyroTail::FLAG_MAY_MOVE | PcGyroTail::FLAG_CLOCK, count: 2, newest_seq: 65535, newest_time_us: 0xFEDC_BA98, ..PcGyroTail::default() };
    t.dt_us[0] = 1000;
    t.dt_us[1] = 50000;
    t.rate[0][0] = -32768;
    t.rate[1][1] = 32767;
    t.rate[1][2] = -5;
    t.axes[0] = 7;
    t.axes[1] = 4;
    t.totals = [i64::MIN, -1, 123_456_789_012];
    t.config.sensitivity[1] = 20.5;
    t.config.glide_ms[2] = 500.0;
    t.config.inertia[0] = true;
    t.config.linear[2] = true;
    t.config.add_route(Route { axis: 2, target: TARGET_MOUSE_X, scale: -1.0 });
    t.config.add_route(Route { axis: 1, target: TARGET_PAD + 3, scale: 0.5 });
    t.up[1][2] = 16384;
    t.up[0][0] = -16384;
    t.config.space[0] = 1;
    t.config.space[2] = 2;
    t.tightening = 2.5;
    t.smoothing = 8.0;
    let mut b = vec![0u8; wire::PC_GYRO_TAIL_SIZE];
    t.encode(&mut b);
    // Layout: flags, n, seq, time, then sample 1 at 8 + 15, totals at 128, config at 152, routes at 186, the spaces byte last.
    assert_eq!(3, b[0]);
    assert_eq!(2, b[1]);
    assert_eq!(0x98, b[4]);
    assert_eq!(0x50, b[8 + 15]); // 50000 = 0xC350
    assert_eq!(4, b[8 + 15 + 8]);
    assert_eq!(0x40, b[8 + 15 + 14]); // up z = 1: 16384 = 0x4000, high byte
    assert_eq!(0x80, b[135]); // i64::MIN's top byte
    assert_eq!(1 | 32, b[176]);
    assert_eq!(2, b[177]);
    assert_eq!(2, b[186]);
    assert_eq!(TARGET_PAD + 3, b[193]);
    assert_eq!(1 | 2 << 4, b[wire::PC_GYRO_TAIL_SIZE - 1]);

    let mut d = PcGyroTail::default();
    d.decode(&b);
    assert!(d.may_move() && d.clock());
    assert_eq!(d, t);

    // In a STATE_GYRO, after the 70 STATE_HIRES bytes.
    let f = StateFrame { hires: true, mouse_total_x: 5, ..StateFrame::default() };
    let mut plain = vec![0u8; wire::STATE_GYRO_PLAIN];
    f.encode(&mut plain);
    plain[wire::STATE_HIRES_PLAIN..].copy_from_slice(&b);
    let mut g = StateFrame::default();
    g.decode(&plain);
    assert!(g.has_pc_gyro);
    assert_eq!(5, g.mouse_total_x);
    assert_eq!(65535, g.tail.newest_seq);
    let mut h = StateFrame::default();
    h.decode(&plain[..wire::STATE_HIRES_PLAIN]);
    assert!(!h.has_pc_gyro);

    let c = config();
    let mut cb = vec![0u8; wire::PAD_GYRO_PLAIN];
    c.encode(&mut cb);
    let mut c2 = PadGyroConfig::default();
    c2.decode(&cb);
    assert!(c2.enabled);
    assert_eq!(7, c2.kind[L1]);
    assert_eq!(PadGyroConfig::KIND_TOGGLE, c2.kind[NORTH]);
    assert_eq!(c2.used(), 1 << L1 | 1 << NORTH);
}

#[test]
fn routes_the_host_cannot_follow_are_left_out() {
    let mut t = PcGyroTail::default();
    t.config.add_route(Route { axis: 3, target: 0, scale: 1.0 });
    t.config.add_route(Route { axis: 0, target: TARGET_PAD + 6, scale: 1.0 });
    t.config.add_route(Route { axis: 0, target: TARGET_WHEEL, scale: 1.0 });
    let mut b = vec![0u8; wire::PC_GYRO_TAIL_SIZE];
    t.encode(&mut b);
    let mut d = PcGyroTail::default();
    d.decode(&b);
    assert_eq!(d.config.routes(), [Route { axis: 0, target: TARGET_WHEEL, scale: 1.0 }]);
}

#[test]
fn a_tail_of_any_bytes_decodes_and_runs() {
    // Every byte pattern is a tail the engine must take without a panic: counts past 8, routes past 12,
    // curve points past 6 and float fields of any bits. A cheap stand-in for the fuzz run.
    let mut seed = 0x1234_5678_9ABC_DEF0u64;
    let mut gyro = PcGyro::default();
    let (gate, cfg) = (GyroGate::default(), config());
    for round in 0..2000 {
        let mut b = vec![0u8; wire::STATE_GYRO_PLAIN];
        for x in b.iter_mut() {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            *x = if round % 3 == 0 { 0xFF } else { seed as u8 };
        }
        if round % 2 == 0 {
            b[wire::STATE_HIRES_PLAIN] |= PcGyroTail::FLAG_MAY_MOVE;
        }
        let mut f = StateFrame::default();
        f.decode(&b);
        assert!(f.tail.count <= MAX_SAMPLES && f.tail.config.route_count <= MAX_ROUTES);
        gyro.apply(&mut f, T0 + round * 1000, &gate, &cfg, None);
        assert!((-32767..=32767).contains(&f.lx) && f.lt <= 1023 && f.rt <= 1023);
    }
}

#[test]
fn held_ratchet_flips_every_axis() {
    let (mut g, c, mut pad) = (GyroGate::default(), config(), Pad::new());
    g.update(&pad.state(), &c);
    assert_eq!(7, g.effective_of(7, true));
    pad.press(L1);
    g.update(&pad.state(), &c);
    assert_eq!(7, g.flips());
    assert_eq!(0, g.effective_of(7, true));
    assert_eq!(7, g.effective_of(0, true));
    assert_eq!(1, g.ratchet_presses());
    pad.release(L1);
    g.update(&pad.state(), &c);
    assert_eq!(0, g.flips());
    assert_eq!(0, g.effective_of(7, false));
}

#[test]
fn flips_of_follows_the_config() {
    let mut c = config();
    c.kind[0] = 4; // south ratchets roll
    assert_eq!(7, GyroGate::flips_of(1 << L1, &c));
    assert_eq!(3, GyroGate::flips_of(1 << L1 | 1, &c));
    assert_eq!(0, GyroGate::flips_of(1 << NORTH, &c));
    c.enabled = false;
    assert_eq!(0, GyroGate::flips_of(1 << L1, &c));
}

#[test]
fn toggle_flips_until_the_epoch_changes() {
    let (mut g, mut c, mut pad) = (GyroGate::default(), config(), Pad::new());
    g.update(&pad.state(), &c);
    pad.press(NORTH);
    pad.release(NORTH);
    g.update(&pad.state(), &c);
    assert!(g.toggled());
    assert_eq!(0, g.effective_of(7, true));
    c.epoch += 1;
    g.update(&pad.state(), &c);
    assert!(!g.toggled());
}

#[test]
fn clear_pc_ends_the_toggle() {
    let (mut g, c, mut pad) = (GyroGate::default(), config(), Pad::new());
    g.update(&pad.state(), &c);
    pad.press(NORTH);
    pad.release(NORTH);
    g.update(&pad.state(), &c);
    assert!(g.toggled());
    g.clear_pc();
    assert!(!g.toggled());
    assert_eq!(7, g.effective_of(7, true));
    // The first read after clearing counts no press.
    g.update(&pad.state(), &c);
    assert!(!g.toggled());
}
