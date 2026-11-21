//! What the companion asks of the input process over the pipe (IInputControl in InputControl.cs), and the shapes
//! of what it gets back: the same method names, arguments and JSON as the C# input process, so the companion
//! can't tell which of the two answers.

use serde_json::{json, Map, Value};

use crate::crypto::{base64_decode, base64_encode};
use crate::engine::{ButtonLatency, HostSnapshot, PairedPhoneInfo};
use crate::pipe::float_value;
use crate::settings::{HostSettings, NEVER_UTC};
use crate::wire::output_mode;

/// The names both processes use in the user's Windows session (SplitNames).
pub mod names {
    /// The shared memory.
    pub const REGION: &str = r"Local\GyroPhoneShared";
    /// Set by the input process when it put something in the overlay ring.
    pub const OVERLAY_WAKE: &str = r"Local\GyroPhoneOverlayWake";
    /// The input process's single-instance lock.
    pub const INPUT_MUTEX: &str = r"Local\GyroPhoneInput";

    /// The pipe a companion connects to: one per user and Windows session.
    pub fn pipe(user: &str, session_id: u32) -> String {
        format!("GyroPhoneInput.{user}.{session_id}")
    }
}

/// The input process's state for the tray and the windows, pushed as event "status" when it changes (and once a
/// second).
#[derive(Clone, Debug, PartialEq)]
pub struct InputStatus {
    pub snapshot: HostSnapshot,
    pub settings: InputSettingsView,
    /// The UDP server: Running, PortInUse, Failed or Stopped.
    pub server: &'static str,
    pub bind_retrying: bool,
    pub bind_wait_long: bool,
    pub key_error: Option<String>,
    pub key_retrying: bool,
    pub pad_error: Option<String>,
    pub input_driver_ready: bool,
    pub input_driver_installed: bool,
    pub usb_enabled: bool,
    pub usb_state: String,
    pub usb_detail: String,
    pub authorized_scan: Option<String>,
    pub send_input_short_writes: i64,
    pub rx_exceptions: i64,
    pub buttons: Option<ButtonLatency>,
    pub elevated: bool,
    pub keys_folder: String,
    pub log_error: Option<String>,
    pub diagnostics_saved: i32,
}

/// The input process's own settings (settings.json) as the windows show them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InputSettingsView {
    pub host_name: String,
    pub output_mode: u8,
    pub start_with_windows: bool,
    pub usb_direct: bool,
    pub measure_buttons: bool,
    pub diagnostics: bool,
    pub diagnostics_minutes: i32,
    pub use_input_driver: bool,
    pub press_key_listen: bool,
    pub press_key_notice: bool,
    pub catch_up_ms: i32,
}

impl InputSettingsView {
    pub fn of(s: &HostSettings) -> InputSettingsView {
        InputSettingsView {
            host_name: s.host_name.clone(),
            output_mode: s.output_mode,
            start_with_windows: s.start_with_windows,
            usb_direct: s.usb_direct,
            measure_buttons: s.measure_buttons,
            diagnostics: s.diagnostics,
            diagnostics_minutes: s.diagnostics_minutes,
            use_input_driver: s.use_input_driver,
            press_key_listen: s.press_key_listen,
            press_key_notice: s.press_key_notice,
            catch_up_ms: s.catch_up_ms,
        }
    }
}

/// What the input process tells a companion that attaches.
#[derive(Clone, Debug, PartialEq)]
pub struct InputHello {
    pub version: String,
    pub pid: u32,
    pub shared_name: String,
    pub layout_version: i32,
    pub overlay_wake_name: String,
    pub status: InputStatus,
}

/// The delivery probe's numbers for a running Delivery test (times in ms).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DeliveryNumbers {
    pub n: i64,
    pub p50: f64,
    pub p99: f64,
    pub max: f64,
    pub slow: i64,
    pub unmatched: i64,
    pub merged: i64,
    pub stray: i64,
}

/// The companion's calls. The input process answers each on its pipe thread, never on the input path. Every call
/// returns at once: anything slow shows in the next status.
pub trait InputControl: Send + Sync {
    /// The companion attached.
    fn attach(&self, companion_pid: i32) -> InputHello;
    /// The engine's state now.
    fn snapshot(&self) -> HostSnapshot;
    /// Tray Exit (or an update): release everything, say goodbye to the phone, save and exit.
    fn exit(&self);

    fn set_output_mode(&self, mode: u8);
    fn set_paused(&self, paused: bool);
    /// Session lock or sleep: release-all, inputs held back until unlocked.
    fn set_locked(&self, locked: bool);
    /// Looks for the gamepad driver again; "" when found, else why not.
    fn retry_driver(&self) -> String;
    fn set_catch_up_ms(&self, ms: i32);
    fn set_use_input_driver(&self, on: bool);

    fn open_pairing_window(&self);
    fn answer_pair(&self, attempt_id: u32, allow: bool);
    /// A one-time QR pairing token, or None without a PC key.
    fn open_qr_pairing(&self) -> Option<Vec<u8>>;
    fn close_qr_pairing(&self);
    /// This PC's public key (None without one), for the QR code.
    fn host_public_key(&self) -> Option<Vec<u8>>;
    /// Forgets a code-paired phone, or deletes a key-paired phone's key file.
    fn forget(&self, phone: &PairedPhoneInfo);

    fn set_usb_direct(&self, on: bool);
    fn take_usb_from_adb(&self);

    fn set_start_with_windows(&self, on: bool);
    fn set_diagnostics(&self, on: bool);
    fn set_diagnostics_minutes(&self, minutes: i32);
    fn save_diagnostics(&self);
    fn set_press_key_listen(&self, on: bool);
    fn set_press_key_notice(&self, on: bool);

    fn set_measure_buttons(&self, on: bool);
    fn set_link_watch(&self, on: bool);
    fn set_delivery_test(&self, on: bool, counting: bool);
    fn delivery_test_numbers(&self) -> Option<DeliveryNumbers>;

    fn set_overlay(&self, on: bool, compass: bool, samples: bool, keys: bool);
}

// =====================================================================================
// calls
// =====================================================================================

/// The methods the companion may call, as it names them.
pub const METHODS: [&str; 28] = [
    "Attach",
    "Snapshot",
    "Exit",
    "SetOutputMode",
    "SetPaused",
    "SetLocked",
    "RetryDriver",
    "SetCatchUpMs",
    "SetUseInputDriver",
    "OpenPairingWindow",
    "AnswerPair",
    "OpenQrPairing",
    "CloseQrPairing",
    "HostPublicKey",
    "Forget",
    "SetUsbDirect",
    "TakeUsbFromAdb",
    "SetStartWithWindows",
    "SetDiagnostics",
    "SetDiagnosticsMinutes",
    "SaveDiagnostics",
    "SetPressKeyListen",
    "SetPressKeyNotice",
    "SetMeasureButtons",
    "SetLinkWatch",
    "SetDeliveryTest",
    "DeliveryTestNumbers",
    "SetOverlay",
];

/// An argument that is missing takes its type's default (false, 0), as the C# side's does.
fn arg_bool(args: &[Value], at: usize) -> Result<bool, String> {
    match args.get(at) {
        None => Ok(false),
        Some(Value::Bool(b)) => Ok(*b),
        Some(_) => Err(format!("JsonException: argument {at} is not true or false")),
    }
}

fn arg_whole(args: &[Value], at: usize, min: i64, max: i64) -> Result<i64, String> {
    match args.get(at) {
        None => Ok(0),
        Some(Value::Number(n)) => match n.as_i64() {
            Some(v) if v >= min && v <= max => Ok(v),
            _ => Err(format!("JsonException: argument {at} is not a whole number in range")),
        },
        Some(_) => Err(format!("JsonException: argument {at} is not a number")),
    }
}

fn arg_i32(args: &[Value], at: usize) -> Result<i32, String> {
    arg_whole(args, at, i32::MIN as i64, i32::MAX as i64).map(|v| v as i32)
}

fn arg_u32(args: &[Value], at: usize) -> Result<u32, String> {
    arg_whole(args, at, 0, u32::MAX as i64).map(|v| v as u32)
}

/// A field by name, whatever its letters' case (the C# side reads names that way).
fn field<'a>(m: &'a Map<String, Value>, name: &str) -> Option<&'a Value> {
    m.get(name).or_else(|| m.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v))
}

fn phone_from_json(v: Option<&Value>) -> Result<PairedPhoneInfo, String> {
    let Some(Value::Object(m)) = v else {
        return Err("NullReferenceException: no phone given".to_string());
    };
    let text = |name: &str| -> Result<Option<String>, String> {
        match field(m, name) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::String(s)) => Ok(Some(s.clone())),
            Some(_) => Err(format!("JsonException: {name} is not a text")),
        }
    };
    let phone_id = match text("phoneId")? {
        None => Vec::new(),
        Some(s) => base64_decode(&s).ok_or_else(|| "JsonException: phoneId is not base64".to_string())?,
    };
    let by_key = match field(m, "byKey") {
        None => false,
        Some(Value::Bool(b)) => *b,
        Some(_) => return Err("JsonException: byKey is not true or false".to_string()),
    };
    Ok(PairedPhoneInfo {
        phone_id,
        name: text("name")?.unwrap_or_default(),
        last_seen_utc: text("lastSeenUtc")?.unwrap_or_else(|| NEVER_UTC.to_string()),
        by_key,
        key_file: text("keyFile")?,
        fingerprint: text("fingerprint")?,
    })
}

fn bytes_value(b: Option<Vec<u8>>) -> Value {
    b.map(|b| Value::String(base64_encode(&b, false))).unwrap_or(Value::Null)
}

/// Answers the companion's call `method` by calling `c`: the result as JSON (null for a call that returns
/// nothing), or why it failed.
pub fn dispatch(c: &dyn InputControl, method: &str, args: &[Value]) -> Result<Value, String> {
    match method {
        "Attach" => Ok(hello_json(&c.attach(arg_i32(args, 0)?))),
        "Snapshot" => Ok(snapshot_json(&c.snapshot())),
        "Exit" => Ok(void(c.exit())),
        "SetOutputMode" => {
            let mode = arg_whole(args, 0, output_mode::NONE as i64, output_mode::DUALSENSE as i64)?;
            Ok(void(c.set_output_mode(mode as u8)))
        }
        "SetPaused" => Ok(void(c.set_paused(arg_bool(args, 0)?))),
        "SetLocked" => Ok(void(c.set_locked(arg_bool(args, 0)?))),
        "RetryDriver" => Ok(Value::String(c.retry_driver())),
        "SetCatchUpMs" => Ok(void(c.set_catch_up_ms(arg_i32(args, 0)?))),
        "SetUseInputDriver" => Ok(void(c.set_use_input_driver(arg_bool(args, 0)?))),
        "OpenPairingWindow" => Ok(void(c.open_pairing_window())),
        "AnswerPair" => {
            let (id, allow) = (arg_u32(args, 0)?, arg_bool(args, 1)?);
            Ok(void(c.answer_pair(id, allow)))
        }
        "OpenQrPairing" => Ok(bytes_value(c.open_qr_pairing())),
        "CloseQrPairing" => Ok(void(c.close_qr_pairing())),
        "HostPublicKey" => Ok(bytes_value(c.host_public_key())),
        "Forget" => Ok(void(c.forget(&phone_from_json(args.first())?))),
        "SetUsbDirect" => Ok(void(c.set_usb_direct(arg_bool(args, 0)?))),
        "TakeUsbFromAdb" => Ok(void(c.take_usb_from_adb())),
        "SetStartWithWindows" => Ok(void(c.set_start_with_windows(arg_bool(args, 0)?))),
        "SetDiagnostics" => Ok(void(c.set_diagnostics(arg_bool(args, 0)?))),
        "SetDiagnosticsMinutes" => Ok(void(c.set_diagnostics_minutes(arg_i32(args, 0)?))),
        "SaveDiagnostics" => Ok(void(c.save_diagnostics())),
        "SetPressKeyListen" => Ok(void(c.set_press_key_listen(arg_bool(args, 0)?))),
        "SetPressKeyNotice" => Ok(void(c.set_press_key_notice(arg_bool(args, 0)?))),
        "SetMeasureButtons" => Ok(void(c.set_measure_buttons(arg_bool(args, 0)?))),
        "SetLinkWatch" => Ok(void(c.set_link_watch(arg_bool(args, 0)?))),
        "SetDeliveryTest" => {
            let (on, counting) = (arg_bool(args, 0)?, arg_bool(args, 1)?);
            Ok(void(c.set_delivery_test(on, counting)))
        }
        "DeliveryTestNumbers" => Ok(c.delivery_test_numbers().map(|n| delivery_json(&n)).unwrap_or(Value::Null)),
        "SetOverlay" => {
            let (on, compass, samples, keys) = (arg_bool(args, 0)?, arg_bool(args, 1)?, arg_bool(args, 2)?, arg_bool(args, 3)?);
            Ok(void(c.set_overlay(on, compass, samples, keys)))
        }
        _ => Err(format!("MissingMethodException: Method 'IInputControl.{method}' not found.")),
    }
}

fn void(_: ()) -> Value {
    Value::Null
}

// =====================================================================================
// shapes
// =====================================================================================

fn opt_text(s: &Option<String>) -> Value {
    s.as_ref().map(|s| Value::String(s.clone())).unwrap_or(Value::Null)
}

pub fn phone_json(p: &PairedPhoneInfo) -> Value {
    json!({
        "phoneId": base64_encode(&p.phone_id, false),
        "name": p.name,
        "lastSeenUtc": if p.last_seen_utc.is_empty() { NEVER_UTC } else { p.last_seen_utc.as_str() },
        "byKey": p.by_key,
        "keyFile": opt_text(&p.key_file),
        "fingerprint": opt_text(&p.fingerprint),
    })
}

pub fn snapshot_json(s: &HostSnapshot) -> Value {
    let pair_prompt = match &s.pair_prompt {
        None => Value::Null,
        Some((id, name, sas)) => json!({ "item1": id, "item2": name, "item3": sas }),
    };
    let phone_link = match &s.phone_link {
        None => Value::Null,
        Some(l) => json!({
            "clockOffsetUs": l.clock_offset_us,
            "rttMinUs": l.rtt_min_us,
            "sendRateHz": l.send_rate_hz,
            "rssiDbm": l.rssi_dbm,
            "band": l.band,
            "linkMbps": l.link_mbps,
            "freqMhz": l.freq_mhz,
            "flags": l.flags,
            "path": l.path,
        }),
    };
    let c = &s.counters;
    json!({
        "phoneName": opt_text(&s.phone_name),
        "connected": s.connected,
        "silent": s.silent,
        "outputMode": s.output_mode,
        "driverOk": s.driver_ok,
        "padPlugged": s.pad_plugged,
        "paused": s.paused,
        "elevatedForeground": s.elevated_foreground,
        "networkPublic": s.network_public,
        "pairingWindowS": s.pairing_window_s,
        "pairPrompt": pair_prompt,
        "rxPerSec": s.rx_per_sec,
        "lossPermille": s.loss_permille,
        "latP50Us": s.lat_p50_us,
        "latP99Us": s.lat_p99_us,
        "jitterUs": s.jitter_us,
        "phoneLink": phone_link,
        "anyDatagramSeen": s.any_datagram_seen,
        "pairedPhones": s.paired_phones.iter().map(phone_json).collect::<Vec<_>>(),
        "counters": {
            "datagrams": c.datagrams,
            "dropped": c.dropped,
            "badAuth": c.bad_auth,
            "states": c.states,
            "injections": c.injections,
            "unknownUsages": c.unknown_usages,
            "errors": c.errors,
            "exceptions": c.exceptions,
        },
        "hostKeyFingerprint": opt_text(&s.host_key_fingerprint),
        "qrPairingS": s.qr_pairing_s,
        "qrEnrolledPhone": opt_text(&s.qr_enrolled_phone),
        "pcController": opt_text(&s.pc_controller),
        "dualSenseOk": s.dual_sense_ok,
        "capturing": s.capturing,
        "phoneAddress": opt_text(&s.phone_address),
        "outputDriverOk": s.output_driver_ok(),
    })
}

pub fn settings_json(s: &InputSettingsView) -> Value {
    json!({
        "hostName": s.host_name,
        "outputMode": s.output_mode,
        "startWithWindows": s.start_with_windows,
        "usbDirect": s.usb_direct,
        "measureButtons": s.measure_buttons,
        "diagnostics": s.diagnostics,
        "diagnosticsMinutes": s.diagnostics_minutes,
        "useInputDriver": s.use_input_driver,
        "pressKeyListen": s.press_key_listen,
        "pressKeyNotice": s.press_key_notice,
        "catchUpMs": s.catch_up_ms,
    })
}

pub fn status_json(s: &InputStatus) -> Value {
    let buttons = match &s.buttons {
        None => Value::Null,
        Some(b) => json!({ "totalUs": b.total_us, "phoneUs": b.phone_us, "count": b.count, "lastUs": b.last_us }),
    };
    json!({
        "snapshot": snapshot_json(&s.snapshot),
        "settings": settings_json(&s.settings),
        "server": s.server,
        "bindRetrying": s.bind_retrying,
        "bindWaitLong": s.bind_wait_long,
        "keyError": opt_text(&s.key_error),
        "keyRetrying": s.key_retrying,
        "padError": opt_text(&s.pad_error),
        "inputDriverReady": s.input_driver_ready,
        "inputDriverInstalled": s.input_driver_installed,
        "usbEnabled": s.usb_enabled,
        "usbState": s.usb_state,
        "usbDetail": s.usb_detail,
        "authorizedScan": opt_text(&s.authorized_scan),
        "sendInputShortWrites": s.send_input_short_writes,
        "rxExceptions": s.rx_exceptions,
        "buttons": buttons,
        "elevated": s.elevated,
        "keysFolder": s.keys_folder,
        "logError": opt_text(&s.log_error),
        "diagnosticsSaved": s.diagnostics_saved,
    })
}

pub fn hello_json(h: &InputHello) -> Value {
    json!({
        "version": h.version,
        "pid": h.pid,
        "sharedName": h.shared_name,
        "layoutVersion": h.layout_version,
        "overlayWakeName": h.overlay_wake_name,
        "status": status_json(&h.status),
    })
}

pub fn delivery_json(n: &DeliveryNumbers) -> Value {
    json!({
        "n": n.n,
        "p50": float_value(n.p50),
        "p99": float_value(n.p99),
        "max": float_value(n.max),
        "slow": n.slow,
        "unmatched": n.unmatched,
        "merged": n.merged,
        "stray": n.stray,
        "slowPer1000": float_value(if n.n == 0 { 0.0 } else { n.slow as f64 * 1000.0 / n.n as f64 }),
    })
}
