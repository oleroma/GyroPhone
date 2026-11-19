//! GyroPhone's PC input process, in Rust: the phone link (UDP and the USB cable), packet checking,
//! pairing, sessions, the engine that turns the phone's state into keys, mouse and pad state, and the
//! outputs. It does the same job as the C# input process (`GyroPhoneCompanion.exe --input`) and talks
//! to the companion the same way, so either can run behind the Performance tab's switch.
//!
//! The wire format is `docs/spec/protocol.md`; every byte layout here must match `Wire.kt` on the
//! phone and `GyroPhone.Protocol` in the companion. The shared vectors in
//! `core/src/test/resources/protocol/v1` are checked by `tests/`.

#![forbid(unsafe_op_in_unsafe_fn)]

pub mod codec;
pub mod control;
pub mod crypto;
pub mod delivery;
pub mod engine;
pub mod file_log;
pub mod gyro;
pub mod held_marker;
pub mod host;
pub mod key_files;
pub mod le;
pub mod link_stats;
pub mod mapping;
pub mod motion;
pub mod mouse;
pub mod pairing;
pub mod pc_setup;
pub mod pipe;
pub mod ports;
pub mod press;
pub mod rate_limiter;
pub mod recorder;
pub mod reports;
pub mod session;
pub mod settings;
pub mod shared;
pub mod state_frame;
pub mod sys;
pub mod udp;
pub mod usb_bridge;
pub mod usb_framing;
pub mod usb_link;
pub mod watchdog;
#[cfg(windows)]
pub mod win;
pub mod wire;

/// The process's entry point; returns its exit code.
pub fn run() -> i32 {
    0
}
