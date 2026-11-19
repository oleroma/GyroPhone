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
pub mod crypto;
pub mod le;
pub mod state_frame;
pub mod wire;

/// The process's entry point; returns its exit code.
pub fn run() -> i32 {
    0
}
