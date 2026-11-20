//! GyroPhone's PC input process, in Rust: the phone link (UDP and the USB cable), packet checking,
//! pairing, sessions, the engine that turns the phone's state into keys, mouse and pad state, and the
//! outputs. It does the same job as the C# input process (`GyroPhoneCompanion.exe --input`) and talks
//! to the companion the same way, so either can run behind the Performance tab's switch. It is built as
//! a library (gp_input.dll) that rides inside the companion's exe, which loads it and calls
//! `gp_input_run` when started with `--input --rust`.
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
pub mod stall_watch;
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

/// What the companion's exe calls to be the input process (GyroPhoneCompanion.exe --input --rust): runs until
/// the companion says Exit and returns the process's exit code. `version` is `version_len` UTF-16 units, the
/// companion's own version, which it checks an input process's against; `long_wait` is not 0 when the
/// watcher or an update started it.
///
/// # Safety
/// `version` must point at `version_len` readable UTF-16 units (or be null with a length of 0).
#[cfg(windows)]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gp_input_run(version: *const u16, version_len: u32, long_wait: i32) -> i32 {
    let version = if version.is_null() || version_len == 0 || version_len > 256 {
        String::from("dev")
    } else {
        // SAFETY: the caller's promise; the length is bounded above.
        String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(version, version_len as usize) })
    };
    win::run::run(&version, long_wait != 0)
}

/// The number the companion checks before it calls `gp_input_run`: raised when that function's arguments or
/// meaning change.
#[cfg(windows)]
#[unsafe(no_mangle)]
pub extern "C" fn gp_input_abi() -> u32 {
    1
}
