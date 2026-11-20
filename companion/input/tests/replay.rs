//! Replay files: the same packets, ticks and calls the C# input process was given (companion/tests/replay/v1,
//! written by the C# ReplayCompareTests) go into this engine, and it must do exactly what the file says the
//! C# engine did: the same key and mouse events, pad reports, packets back to the phone and log lines, in the
//! same order. The file format is described in ReplayCompareTests.cs.

mod common;

use std::fmt::Write as _;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use common::fakes::FakePcPad;
use common::harness::{paired, FakeClock, PlainProtector};
use common::{hex, unhex};
use gp_input::crypto::P256;
use gp_input::engine::{EngineOptions, HostEngine, NullHooks};
use gp_input::mapping::{Ds4Frame, InjectEvent, X360Frame};
use gp_input::ports::{HostEvents, KeyMouseSink, PadSink, PairPrompt, SinkError};
use gp_input::reports::Ds4Motion;
use gp_input::session::{DatagramSender, RandomFn};
use gp_input::settings::HostSettings;
use gp_input::wire::output_mode;

/// Everything the engine does, as lines in the order it did it.
#[derive(Default)]
struct Lines {
    out: Mutex<Vec<String>>,
    plugged: Mutex<u8>,
    rumble: Mutex<Option<Arc<dyn Fn(u8, u8) + Send + Sync>>>,
}

impl Lines {
    fn put(&self, line: String) {
        self.out.lock().unwrap().push(format!("= {line}"));
    }
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.out.lock().unwrap())
    }
}

impl PadSink for Lines {
    fn driver_ok(&self) -> bool {
        true
    }
    fn plugged(&self) -> u8 {
        *self.plugged.lock().unwrap()
    }
    fn plug(&self, mode: u8) -> Result<bool, SinkError> {
        *self.plugged.lock().unwrap() = mode;
        self.put(format!("plug {mode}"));
        Ok(true)
    }
    fn unplug(&self) -> Result<(), SinkError> {
        let mut p = self.plugged.lock().unwrap();
        self.put(format!("unplug {}", *p));
        *p = output_mode::NONE;
        Ok(())
    }
    fn submit_x360(&self, f: &X360Frame) -> Result<(), SinkError> {
        self.put(format!("x360 {},{},{},{},{},{},{}", f.buttons, f.lx, f.ly, f.rx, f.ry, f.lt, f.rt));
        Ok(())
    }
    fn submit_ds4(&self, f: &Ds4Frame, m: &Ds4Motion) -> Result<(), SinkError> {
        self.put(format!(
            "ds4 {},{},{},{},{},{},{},{},{} {},{},{},{},{},{},{}",
            f.buttons, f.special, f.hat, f.lx, f.ly, f.rx, f.ry, f.lt, f.rt, m.clock, m.gx, m.gy, m.gz, m.ax, m.ay, m.az
        ));
        Ok(())
    }
    fn set_rumble_handler(&self, handler: Option<Arc<dyn Fn(u8, u8) + Send + Sync>>) {
        *self.rumble.lock().unwrap() = handler;
    }
}

impl KeyMouseSink for Lines {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        let mut s = String::from("inject");
        for e in events {
            let _ = write!(s, " {},{},{},{},{},{},{},{}", e.kind as u8, e.usage, e.scan, e.vk, e.flags, e.dx, e.dy, e.mouse_data);
        }
        self.put(s);
        true
    }
}

impl DatagramSender for Lines {
    fn send(&self, datagram: &[u8], to: SocketAddr) {
        self.put(format!("tx {to} {}", hex(datagram)));
    }
}

impl PairPrompt for Lines {
    fn show(&self, attempt_id: u32, phone_name: &str, sas_display: &str) {
        self.put(format!("show {attempt_id} {phone_name} {sas_display}"));
    }
    fn close(&self, attempt_id: u32) {
        self.put(format!("close {attempt_id}"));
    }
}

impl HostEvents for Lines {
    fn state_changed(&self) {}
    fn log(&self, message: &str) {
        self.put(format!("log {}", message.replace('\n', "\\n")));
    }
}

/// The engine's random bytes: a fixed stream, the same as the C# test's.
fn random(draws: Arc<AtomicU32>) -> RandomFn {
    Box::new(move |b: &mut [u8]| {
        let k = draws.fetch_add(1, Ordering::SeqCst);
        for (i, x) in b.iter_mut().enumerate() {
            *x = 0xA5 ^ (k.wrapping_mul(17).wrapping_add(i as u32 * 3)) as u8;
        }
    })
}

fn notice(line: &str) -> bool {
    line.starts_with("= log ") || line.starts_with("= show ") || line.starts_with("= close ")
}

fn flag(s: &str) -> bool {
    s == "1"
}

fn replay(name: &str) {
    let path = format!("{}/../tests/replay/v1/{name}.txt", env!("CARGO_MANIFEST_DIR"));
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{path}: {e}")).replace('\r', "");
    let lines: Vec<&str> = text.split('\n').collect();

    let out = Arc::new(Lines::default());
    let clock = Arc::new(FakeClock(std::sync::atomic::AtomicI64::new(1_000_000_000)));
    let pc_pad = Arc::new(FakePcPad::default());
    let mut settings = Some({
        let mut s = HostSettings::create_default("DESKTOP-OLEX");
        s.host_id = common::range_n(0x10);
        s
    });
    let mut engine: Option<Arc<HostEngine>> = None;
    // What the last input line made the engine do, still to be found in the file.
    let mut pending: std::collections::VecDeque<String> = Default::default();
    let mut logs: std::collections::VecDeque<String> = Default::default();
    let mut last_input = 0usize;

    for (i, line) in lines.iter().enumerate() {
        if let Some(want) = line.strip_prefix("= ") {
            // Log lines and the pairing prompt are their own stream: this engine hands them on once it has let go
            // of its lock, so where one lands among the outputs of the same step is not behaviour.
            let queue = if notice(line) { &mut logs } else { &mut pending };
            let got = queue.pop_front();
            assert!(
                got.as_deref().and_then(|g| g.strip_prefix("= ")) == Some(want),
                "{name}.txt line {}: after \"{}\" (line {})\n  C#:   {line}\n  Rust: {}",
                i + 1,
                &lines[last_input][..lines[last_input].len().min(120)],
                last_input + 1,
                got.as_deref().unwrap_or("(nothing more)")
            );
            continue;
        }
        if let Some(more) = pending.front().or(logs.front()) {
            panic!(
                "{name}.txt line {}: after \"{}\" (line {}) Rust did more than C#:\n  Rust: {more}",
                i + 1,
                &lines[last_input][..lines[last_input].len().min(120)],
                last_input + 1
            );
        }
        if line.is_empty() {
            continue;
        }
        last_input = i;
        let w: Vec<&str> = line.split(' ').collect();
        let us = |k: usize| -> i64 { w[k].parse().unwrap() };
        match w[0] {
            "mode" => settings.as_mut().unwrap().output_mode = w[1].parse().unwrap(),
            "paired" => settings.as_mut().unwrap().paired_phones.push(paired(&unhex(w[1]), &w[2].replace('_', " "), &unhex(w[3]))),
            "start" => {
                let draws = Arc::new(AtomicU32::new(0));
                clock.set(us(1));
                let e = HostEngine::new(
                    settings.take().unwrap(),
                    out.clone(),
                    out.clone(),
                    Arc::new(PlainProtector),
                    EngineOptions {
                        clock: clock.clone(),
                        events: out.clone(),
                        prompt: out.clone(),
                        hooks: Arc::new(NullHooks),
                        session_random: Some(random(draws.clone())),
                        pairing_random: Some(random(draws)),
                        new_pair_key: Some({
                            // Private key number n is the byte n + 1, then 31 bytes of 0x5A, as in the C# test.
                            let mut n = 0u8;
                            Box::new(move || {
                                n += 1;
                                let mut d = [0x5Au8; 32];
                                d[0] = n;
                                P256::from_private_scalar(&d).expect("pair key")
                            })
                        }),
                        host_key: None,
                        shared: None,
                        inline_workers: true,
                    },
                );
                e.attach_sender(out.clone());
                e.set_pc_pad(pc_pad.clone());
                e.start(us(1));
                engine = Some(e);
            }
            "rx" => {
                clock.set(us(1));
                engine.as_ref().unwrap().on_datagram(&unhex(w[3]), w[2].parse().unwrap(), us(1));
            }
            "flush" => {
                clock.set(us(1));
                engine.as_ref().unwrap().flush(us(1));
            }
            "tick" => {
                clock.set(us(1));
                engine.as_ref().unwrap().on_tick(us(1));
            }
            "rumble" => {
                let h = out.rumble.lock().unwrap().clone();
                if let Some(h) = h {
                    h(w[1].parse().unwrap(), w[2].parse().unwrap());
                }
            }
            "pcpad" => pc_pad.set(|s| {
                s.connected = flag(w[1]);
                s.held = w[2].parse().unwrap();
                for (k, v) in w[3].split(',').enumerate() {
                    s.presses[k] = v.parse().unwrap();
                }
                for (k, v) in w[4].split(',').enumerate() {
                    s.axes[k] = f32::from_bits(v.parse().unwrap());
                }
            }),
            "call" => {
                clock.set(us(1));
                let e = engine.as_ref().unwrap();
                let a = &w[3..];
                match w[2] {
                    "set_paused" => e.set_paused(flag(a[0])),
                    "set_locked" => e.set_locked(flag(a[0])),
                    "release_all" => e.release_all(),
                    "set_catch_up_ms" => e.set_catch_up_ms(a[0].parse().unwrap()),
                    "set_output_mode" => e.set_output_mode(a[0].parse().unwrap()),
                    "set_environment" => e.set_environment(flag(a[0]), flag(a[1])),
                    "set_overlay" => e.set_overlay(flag(a[0]), flag(a[1]), flag(a[2]), flag(a[3])),
                    "set_pc_setup" => e.set_pc_setup(a[0].parse().unwrap()),
                    "open_pairing_window" => e.open_pairing_window(),
                    "close_pairing_window" => e.close_pairing_window(),
                    "answer_pair" => e.answer_pair(a[0].parse().unwrap(), flag(a[1])),
                    "forget" => {
                        e.forget(&unhex(a[0]));
                    }
                    "shutdown" => e.shutdown(),
                    other => panic!("{name}.txt line {}: unknown call {other}", i + 1),
                }
            }
            other => panic!("{name}.txt line {}: unknown line {other}", i + 1),
        }
        for l in out.take() {
            if notice(&l) {
                logs.push_back(l)
            } else {
                pending.push_back(l)
            }
        }
    }
    assert!(pending.is_empty() && logs.is_empty(), "{name}.txt: at the end Rust did more than C#");
}

#[test]
fn pairing_goes_as_in_csharp() {
    replay("pairing");
}

#[test]
fn keys_and_mouse_play_as_in_csharp() {
    replay("keys-mouse");
}

#[test]
fn the_xbox_pad_plays_as_in_csharp() {
    replay("xbox");
}

#[test]
fn the_dualshock_with_motion_plays_as_in_csharp() {
    replay("ds4-motion");
}

#[test]
fn the_pc_gyro_plays_as_in_csharp() {
    replay("pc-gyro");
}

#[test]
fn a_rough_link_plays_as_in_csharp() {
    replay("rough-link");
}
