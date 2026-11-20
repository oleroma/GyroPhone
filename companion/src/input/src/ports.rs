//! What the engine needs from the outside: the clock, the outputs, the readers of this PC's own inputs, the UI's
//! notifications. Windows implements them; tests fake them. Every method takes `&self`: the engine calls them
//! under its lock, its pad thread and the watchdog outside it.

use crate::codec::PcPress;
use crate::mapping::{Ds4Frame, InjectEvent, X360Frame};
use crate::reports::Ds4Motion;
use crate::wire::output_mode;
use std::net::SocketAddr;
use std::sync::Arc;

/// Monotonic microsecond clock.
pub trait Clock: Send + Sync {
    fn now_us(&self) -> i64;
    /// Milliseconds on the clock the companion stamps its own events with (Windows: GetTickCount64), for the
    /// overlay packets' receive time.
    fn tick_ms(&self) -> i64 {
        self.now_us() / 1000
    }
    /// The clock's own count now (Windows: QueryPerformanceCounter, which the companion's Delivery test reads
    /// too); `ticks_to_us` turns it into `now_us`'s time.
    fn now_ticks(&self) -> i64 {
        self.now_us()
    }
    fn ticks_to_us(&self, ticks: i64) -> i64 {
        ticks
    }
}

/// Notifications for the companion. Called under the engine lock from the rx or watchdog thread, so
/// implementations only hand over to their own thread and must never call back into the engine.
pub trait HostEvents: Send + Sync {
    /// Session, pairing window, paired list, output or flags changed; re-read the engine's snapshot.
    fn state_changed(&self);
    /// Counters and lifecycle messages. Never keys.
    fn log(&self, message: &str);
}

/// No-op events.
pub struct NullHostEvents;

impl HostEvents for NullHostEvents {
    fn state_changed(&self) {}
    fn log(&self, _message: &str) {}
}

/// Asks the user to allow a pairing. `show` is called under the engine lock on the rx thread and must return
/// immediately; the answer comes back later through the engine's `answer_pair`.
pub trait PairPrompt: Send + Sync {
    fn show(&self, attempt_id: u32, phone_name: &str, sas_display: &str);
    /// The attempt ended (answered, timed out, failed or cancelled): close any open prompt.
    fn close(&self, attempt_id: u32);
}

/// Keyboard and mouse output: SendInput, or the optional GyroPhone input driver. One call per flush, only with
/// events.
pub trait KeyMouseSink: Send + Sync {
    /// False when the events didn't all arrive: Windows took fewer than given (an elevated window or the lock
    /// screen is in front), or the input driver's device went away (the next call goes through SendInput).
    fn inject(&self, events: &[InjectEvent]) -> bool;
    /// Where the last inject went, for the logs: "SendInput" or "input driver".
    fn route(&self) -> &'static str {
        "SendInput"
    }
}

/// Reads this PC's keyboard and mouse while the phone's "Press a key" screen listens (Windows: raw input). Only
/// reads: nothing is ever held back from Windows or the game, and input other programs inject (ours too) is
/// skipped.
pub trait KeyMouseListener: Send + Sync {
    /// Starts reading (idempotent).
    fn start(&self);
    /// Stops reading and forgets what was held (idempotent).
    fn stop(&self);
    /// Writes the keys (by HID usage) and mouse buttons held now, those pressed and let go since the last call,
    /// and a wheel press for each way the wheel turned since then, into `into`; returns how many.
    fn read(&self, into: &mut [PcPress]) -> usize;
}

/// No keyboard or mouse listener (tests).
pub struct NullKeyMouseListener;

impl KeyMouseListener for NullKeyMouseListener {
    fn start(&self) {}
    fn stop(&self) {}
    fn read(&self, _into: &mut [PcPress]) -> usize {
        0
    }
}

/// Why a driver call failed: a short name for the log (as the C# input logs the exception's type).
pub type SinkError = &'static str;

/// `PadSink::xinput_slot` while our pad is plugged but Windows hasn't given it a slot yet.
pub const SLOT_UNKNOWN: i32 = -2;

/// Virtual game pad: ViGEm for Xbox 360 and DualShock 4, the optional GyroPhone pad driver for the DualSense.
/// Submit is called under the engine lock and must not block. Plug and unplug block in the driver, so the engine
/// calls them on its pad thread outside the lock, never while it submits.
pub trait PadSink: Send + Sync {
    /// The ViGEmBus driver is present and usable (Xbox 360 and DualShock 4).
    fn driver_ok(&self) -> bool;
    /// The GyroPhone pad driver is installed (DualSense).
    fn dual_sense_ok(&self) -> bool {
        false
    }
    /// A pad of this type can be plugged: its driver works.
    fn can_plug(&self, mode: u8) -> bool {
        match mode {
            output_mode::DUALSENSE => self.dual_sense_ok(),
            output_mode::XBOX360 | output_mode::DUALSHOCK4 => self.driver_ok(),
            _ => false,
        }
    }
    /// Currently plugged pad type, or `output_mode::NONE`.
    fn plugged(&self) -> u8;
    /// Plugs a pad of the given type (unplugging any other). Ok(false) when it could not; Err when the driver
    /// call failed.
    fn plug(&self, mode: u8) -> Result<bool, SinkError>;
    fn unplug(&self) -> Result<(), SinkError>;
    /// Err: the report did not reach the driver (its handle is gone after a driver reset, say).
    fn submit_x360(&self, frame: &X360Frame) -> Result<(), SinkError>;
    /// The DualShock 4's (or DualSense's) whole report: buttons, sticks and triggers with its gyro and
    /// accelerometer.
    fn submit_ds4(&self, frame: &Ds4Frame, motion: &Ds4Motion) -> Result<(), SinkError>;
    /// Who to tell, from the driver's thread, when the game changes rumble (large, small motor). None: nobody.
    fn set_rumble_handler(&self, handler: Option<Arc<dyn Fn(u8, u8) + Send + Sync>>);
    /// The XInput slot (0..3) our plugged Xbox 360 pad took, -1 when none is plugged, `SLOT_UNKNOWN` while it is
    /// plugged but Windows hasn't given it a slot yet. May block briefly: not under the engine lock.
    fn xinput_slot(&self) -> i32 {
        -1
    }
}

/// Pad sink used when the ViGEmBus driver is missing: nothing is plugged, nothing is sent.
pub struct NullPadSink;

impl PadSink for NullPadSink {
    fn driver_ok(&self) -> bool {
        false
    }
    fn plugged(&self) -> u8 {
        output_mode::NONE
    }
    fn plug(&self, _mode: u8) -> Result<bool, SinkError> {
        Ok(false)
    }
    fn unplug(&self) -> Result<(), SinkError> {
        Ok(())
    }
    fn submit_x360(&self, _frame: &X360Frame) -> Result<(), SinkError> {
        Ok(())
    }
    fn submit_ds4(&self, _frame: &Ds4Frame, _motion: &Ds4Motion) -> Result<(), SinkError> {
        Ok(())
    }
    fn set_rumble_handler(&self, _handler: Option<Arc<dyn Fn(u8, u8) + Send + Sync>>) {}
}

/// The places a PC controller's buttons have (the overlay frame's pad bit order).
pub const PC_PAD_PLACES: usize = 18;

/// A controller plugged into this PC, read for "Use PC controller for gyro ratchet" (XInput on Windows). Reads run
/// on the implementation's own thread; `read` only copies the latest state.
pub trait PcPad: Send + Sync {
    /// Starts reading (idempotent), skipping the XInput slot `own_slot` returns (our own virtual pad, -1 = none).
    fn start(&self, own_slot: Arc<dyn Fn() -> i32 + Send + Sync>);
    /// Stops reading (idempotent).
    fn stop(&self);
    /// The latest state: each place's press count into `presses` and the buttons held by place. None when no
    /// controller is read.
    fn read(&self, presses: &mut [u32; PC_PAD_PLACES]) -> Option<u32>;
    /// The latest sticks and triggers: left x, y, right x, y (-1..1, y down), left and right trigger (0..1).
    /// False (and zeros) when no controller is read.
    fn read_axes(&self, axes: &mut [f32; 6]) -> bool;
    /// The latest held buttons and axes from a copy the reader's own thread publishes: never waits on a lock.
    /// None when no controller is read.
    fn peek(&self, axes: &mut [f32; 6]) -> Option<u32>;
    /// The buttons held by place at host time `us` (the engine's clock), from a history of the last changes: what
    /// the PC gyro ratchets each sample by. 0 when no controller was read then.
    fn held_at(&self, us: i64) -> u32;
    /// What is read, for the main window: "Controller 2", or None when none is.
    fn name(&self) -> Option<String>;
}

/// No PC controller (tests).
pub struct NullPcPad;

impl PcPad for NullPcPad {
    fn start(&self, _own_slot: Arc<dyn Fn() -> i32 + Send + Sync>) {}
    fn stop(&self) {}
    fn read(&self, _presses: &mut [u32; PC_PAD_PLACES]) -> Option<u32> {
        None
    }
    fn read_axes(&self, axes: &mut [f32; 6]) -> bool {
        axes.fill(0.0);
        false
    }
    fn peek(&self, axes: &mut [f32; 6]) -> Option<u32> {
        axes.fill(0.0);
        None
    }
    fn held_at(&self, _us: i64) -> u32 {
        0
    }
    fn name(&self) -> Option<String> {
        None
    }
}

/// Protects pairing keys at rest (DPAPI for the current user on Windows, plain in tests).
pub trait KeyProtector: Send + Sync {
    fn protect(&self, plain: &[u8]) -> Vec<u8>;
    /// None when the blob cannot be unprotected (e.g. another user's profile).
    fn unprotect(&self, blob: &[u8]) -> Option<Vec<u8>>;
}

/// Where a datagram came from or goes to. The USB cable link uses a made-up loopback-like address of its own.
pub type Endpoint = SocketAddr;

/// Time since the clock was made, from the system's steady clock (Windows: QueryPerformanceCounter).
pub struct MonotonicClock(std::time::Instant);

impl MonotonicClock {
    pub fn new() -> MonotonicClock {
        MonotonicClock(std::time::Instant::now())
    }
}

impl Default for MonotonicClock {
    fn default() -> Self {
        MonotonicClock::new()
    }
}

impl Clock for MonotonicClock {
    fn now_us(&self) -> i64 {
        self.0.elapsed().as_micros() as i64
    }
}
