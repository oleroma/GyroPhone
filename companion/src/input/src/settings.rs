//! The input process's settings (`%APPDATA%\GyroPhone\settings.json`), the same file the C# input process keeps:
//! either can read what the other saved. Everything this side doesn't use (the companion's old overlay settings,
//! fields a newer build adds) is kept as it was found, so switching between the two loses nothing.
//!
//! Loading and saving follow the C# `SettingsStore`: an atomic save keeping a `.bak`, a corrupt file renamed to
//! `.bad-<time>` with the `.bak` used instead, and a file another program keeps locked never replaced.

use crate::crypto::{base64_decode, base64_encode};
use crate::wire::{self, output_mode};
use serde_json::{Map, Value};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const CURRENT_VERSION: i64 = 2;
pub const MIN_KEEP_MINUTES: i32 = 1;
pub const DEFAULT_KEEP_MINUTES: i32 = 3;
/// The longest a Diagnostics save covers (the Keep slider's top).
pub const MAX_KEEP_MINUTES: i32 = 30;
/// The "Catch-up time" choices, ms.
pub const CATCH_UP_CHOICES: [i32; 4] = [0, 8, 16, 32];

/// The nearest of the choices (an edited file may hold anything); the first of two equally near.
pub fn clamp_catch_up_ms(ms: i32) -> i32 {
    let mut best = CATCH_UP_CHOICES[0];
    for c in CATCH_UP_CHOICES {
        if (c as i64 - ms as i64).abs() < (best as i64 - ms as i64).abs() {
            best = c;
        }
    }
    best
}

/// A phone paired by code.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairedPhone {
    pub phone_id: Vec<u8>,
    pub name: String,
    /// The pair key wrapped by the key protector.
    pub protected_key: Vec<u8>,
    /// UTC times as the file holds them (ISO 8601).
    pub paired_utc: String,
    pub last_seen_utc: String,
}

/// What a never-seen time is written as (C#'s DateTime.MinValue).
pub const NEVER_UTC: &str = "0001-01-01T00:00:00";

/// The settings this side uses, and the rest of the file as it was.
#[derive(Clone, Debug)]
pub struct HostSettings {
    pub host_id: [u8; wire::ID_SIZE],
    pub host_name: String,
    pub output_mode: u8,
    pub start_with_windows: bool,
    pub usb_direct: bool,
    pub paired_phones: Vec<PairedPhone>,
    pub measure_buttons: bool,
    pub diagnostics: bool,
    pub diagnostics_minutes: i32,
    pub use_input_driver: bool,
    pub press_key_listen: bool,
    pub press_key_notice: bool,
    pub catch_up_ms: i32,
    /// The whole file as read; saving puts the fields above back into it.
    rest: Map<String, Value>,
}

/// Equal when they would save the same file content (whatever the order of its fields).
impl PartialEq for HostSettings {
    fn eq(&self, other: &HostSettings) -> bool {
        let value = |s: &HostSettings| serde_json::from_str::<Value>(&s.serialize()).ok();
        value(self) == value(other)
    }
}

/// Why a file's content can't be used.
#[derive(Debug, PartialEq, Eq)]
pub struct Corrupt(pub &'static str);

fn output_mode_name(mode: u8) -> &'static str {
    match mode {
        output_mode::XBOX360 => "Xbox360",
        output_mode::DUALSHOCK4 => "DualShock4",
        output_mode::DUALSENSE => "DualSense",
        _ => "None",
    }
}

fn get_bool(m: &Map<String, Value>, key: &str, default: bool) -> Result<bool, Corrupt> {
    match m.get(key) {
        None => Ok(default),
        Some(Value::Bool(b)) => Ok(*b),
        Some(_) => Err(Corrupt("a switch is not true or false")),
    }
}

fn get_int(m: &Map<String, Value>, key: &str, default: i32) -> Result<i32, Corrupt> {
    match m.get(key) {
        None => Ok(default),
        Some(v) => v.as_i64().and_then(|n| i32::try_from(n).ok()).ok_or(Corrupt("a number is not a whole number")),
    }
}

fn get_string(m: &Map<String, Value>, key: &str) -> Result<String, Corrupt> {
    match m.get(key) {
        None | Some(Value::Null) => Ok(String::new()),
        Some(Value::String(s)) => Ok(s.clone()),
        Some(_) => Err(Corrupt("a text is not a text")),
    }
}

fn get_bytes(m: &Map<String, Value>, key: &str) -> Result<Vec<u8>, Corrupt> {
    match m.get(key) {
        None | Some(Value::Null) => Ok(Vec::new()),
        Some(Value::String(s)) => base64_decode(s).ok_or(Corrupt("bytes are not base64")),
        Some(_) => Err(Corrupt("bytes are not a text")),
    }
}

impl HostSettings {
    /// New settings: a random id, no paired phones.
    pub fn create_default(host_name: &str) -> HostSettings {
        let mut id = [0u8; wire::ID_SIZE];
        crate::crypto::random(&mut id);
        HostSettings {
            host_id: id,
            host_name: host_name.to_string(),
            output_mode: output_mode::XBOX360,
            start_with_windows: false,
            usb_direct: false,
            paired_phones: Vec::new(),
            measure_buttons: false,
            diagnostics: false,
            diagnostics_minutes: DEFAULT_KEEP_MINUTES,
            use_input_driver: false,
            press_key_listen: true,
            press_key_notice: true,
            catch_up_ms: (crate::mouse::MouseCatchUp::DEFAULT_MAX_US / 1000) as i32,
            rest: Map::new(),
        }
    }

    /// Reads a settings file's text. `migrated` comes back true when an older file was brought up to date.
    pub fn parse(text: &str, default_host_name: &str) -> Result<(HostSettings, bool), Corrupt> {
        // A byte order mark some editors put first is not part of the JSON.
        let text = text.strip_prefix('\u{feff}').unwrap_or(text);
        let value: Value = serde_json::from_str(text).map_err(|_| Corrupt("not JSON"))?;
        let Value::Object(m) = value else {
            return Err(Corrupt("not a JSON object"));
        };
        let host_id: [u8; wire::ID_SIZE] =
            get_bytes(&m, "HostId")?.try_into().map_err(|_| Corrupt("missing hostId"))?;
        let mut host_name = get_string(&m, "HostName")?;
        if host_name.is_empty() {
            host_name = default_host_name.to_string();
        }
        let output_mode = match m.get("OutputMode") {
            None => output_mode::XBOX360,
            Some(Value::String(s)) => match s.to_ascii_lowercase().as_str() {
                "none" => output_mode::NONE,
                "xbox360" => output_mode::XBOX360,
                "dualshock4" => output_mode::DUALSHOCK4,
                "dualsense" => output_mode::DUALSENSE,
                _ => return Err(Corrupt("unknown output")),
            },
            // A number is taken too, as the C# reader does; one past the known outputs means no pad.
            Some(v) => match v.as_i64() {
                Some(n @ 0..=3) => n as u8,
                Some(_) => output_mode::NONE,
                None => return Err(Corrupt("unknown output")),
            },
        };
        let mut paired_phones = Vec::new();
        match m.get("PairedPhones") {
            None | Some(Value::Null) => {}
            Some(Value::Array(list)) => {
                for entry in list {
                    let Value::Object(p) = entry else {
                        return Err(Corrupt("a paired phone is not an object"));
                    };
                    let stamp = |key: &str| -> Result<String, Corrupt> {
                        let s = get_string(p, key)?;
                        Ok(if s.is_empty() { NEVER_UTC.to_string() } else { s })
                    };
                    paired_phones.push(PairedPhone {
                        phone_id: get_bytes(p, "PhoneId")?,
                        name: get_string(p, "Name")?,
                        protected_key: get_bytes(p, "ProtectedKey")?,
                        paired_utc: stamp("PairedUtc")?,
                        last_seen_utc: stamp("LastSeenUtc")?,
                    });
                }
            }
            Some(_) => return Err(Corrupt("paired phones are not a list")),
        }
        let version = match m.get("Version") {
            None => CURRENT_VERSION,
            Some(v) => v.as_i64().ok_or(Corrupt("version is not a number"))?,
        };
        let mut s = HostSettings {
            host_id,
            host_name,
            output_mode,
            start_with_windows: get_bool(&m, "StartWithWindows", false)?,
            usb_direct: get_bool(&m, "UsbDirect", false)?,
            paired_phones,
            measure_buttons: get_bool(&m, "MeasureButtons", false)?,
            diagnostics: get_bool(&m, "Diagnostics", false)?,
            diagnostics_minutes: get_int(&m, "DiagnosticsMinutes", DEFAULT_KEEP_MINUTES)?,
            use_input_driver: get_bool(&m, "UseInputDriver", false)?,
            press_key_listen: get_bool(&m, "PressKeyListen", true)?,
            press_key_notice: get_bool(&m, "PressKeyNotice", true)?,
            catch_up_ms: get_int(&m, "CatchUpMs", (crate::mouse::MouseCatchUp::DEFAULT_MAX_US / 1000) as i32)?,
            rest: m,
        };
        let mut migrated = false;
        if version < 2 {
            // Logs and metrics run only when switched on.
            s.diagnostics = false;
            migrated = true;
        }
        s.normalize();
        Ok((s, migrated))
    }

    /// Brings hand-edited values into range.
    pub fn normalize(&mut self) {
        self.catch_up_ms = clamp_catch_up_ms(self.catch_up_ms);
        self.diagnostics_minutes = self.diagnostics_minutes.clamp(MIN_KEEP_MINUTES, MAX_KEEP_MINUTES);
    }

    /// The file's text: what this side uses, and everything else as it was read.
    pub fn serialize(&self) -> String {
        let mut m = self.rest.clone();
        let mut set = |key: &str, v: Value| {
            m.insert(key.to_string(), v);
        };
        set("Version", Value::from(CURRENT_VERSION));
        set("HostId", Value::from(base64_encode(&self.host_id, false)));
        set("HostName", Value::from(self.host_name.clone()));
        set("OutputMode", Value::from(output_mode_name(self.output_mode)));
        set("StartWithWindows", Value::from(self.start_with_windows));
        set("UsbDirect", Value::from(self.usb_direct));
        let phones: Vec<Value> = self
            .paired_phones
            .iter()
            .map(|p| {
                let mut o = Map::new();
                o.insert("PhoneId".into(), Value::from(base64_encode(&p.phone_id, false)));
                o.insert("Name".into(), Value::from(p.name.clone()));
                o.insert("ProtectedKey".into(), Value::from(base64_encode(&p.protected_key, false)));
                o.insert("PairedUtc".into(), Value::from(p.paired_utc.clone()));
                o.insert("LastSeenUtc".into(), Value::from(p.last_seen_utc.clone()));
                Value::Object(o)
            })
            .collect();
        set("PairedPhones", Value::Array(phones));
        set("MeasureButtons", Value::from(self.measure_buttons));
        set("Diagnostics", Value::from(self.diagnostics));
        set("DiagnosticsMinutes", Value::from(self.diagnostics_minutes));
        set("UseInputDriver", Value::from(self.use_input_driver));
        set("PressKeyListen", Value::from(self.press_key_listen));
        set("PressKeyNotice", Value::from(self.press_key_notice));
        set("CatchUpMs", Value::from(self.catch_up_ms));
        serde_json::to_string_pretty(&Value::Object(m)).expect("settings to JSON")
    }
}

/// The UTC time now as the settings file writes it: `2026-11-19T19:30:45.1234567Z`.
pub fn utc_now() -> String {
    let since = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO);
    format_utc(since.as_secs(), since.subsec_nanos())
}

/// Seconds and nanoseconds since 1970 as an ISO 8601 UTC time with 100 ns steps, as .NET writes it.
pub fn format_utc(secs: u64, nanos: u32) -> String {
    let secs = secs.min(253_402_300_799); // the last second of year 9999
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    // Days since 1970 to a date (Howard Hinnant's civil_from_days).
    let z = days + 719_468;
    let era = z / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + if month <= 2 { 1 } else { 0 };
    let mut text = format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    );
    let ticks = (nanos.min(999_999_999) / 100) as u64;
    if ticks != 0 {
        let frac = format!("{ticks:07}");
        text.push('.');
        text.push_str(frac.trim_end_matches('0'));
    }
    text.push('Z');
    text
}

/// Why the settings can't be loaded at all.
#[derive(Debug)]
pub enum LoadError {
    /// A file that is there stayed held by another program: nothing was replaced.
    Locked(String),
}

/// How long a locked file is waited for by default (a start by hand: the user can try again).
pub const DEFAULT_LOCKED_WAIT_MS: u64 = 3000;
/// The wait for a start by the watcher or an update, right after login or a copy.
pub const LONG_LOCKED_WAIT_MS: u64 = 15_000;

/// The pause before attempt `attempt` + 1 at a locked file: 100 ms, growing to 1 s.
pub fn locked_retry_delay_ms(attempt: u32) -> u64 {
    (100 * attempt as u64).min(1000)
}

pub struct SettingsStore {
    path: PathBuf,
    locked_wait_ms: u64,
    write_lock: std::sync::Mutex<()>,
}

enum ReadResult {
    Ok(Box<HostSettings>, bool),
    Missing,
    Corrupt,
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut s = path.as_os_str().to_os_string();
    s.push(suffix);
    PathBuf::from(s)
}

impl SettingsStore {
    pub fn new(path: PathBuf, locked_wait_ms: u64) -> SettingsStore {
        SettingsStore { path, locked_wait_ms, write_lock: std::sync::Mutex::new(()) }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Loads settings, falling back to the `.bak` copy, then to (saved) defaults, when the file is missing or
    /// corrupt. An error when a file that is there stays locked, so its pairings are never overwritten.
    pub fn load(&self, default_host_name: &str, log: &dyn Fn(&str)) -> Result<HostSettings, LoadError> {
        let mut found = false;
        match self.read(&self.path, default_host_name, log)? {
            ReadResult::Ok(s, migrated) => {
                if migrated {
                    self.try_save(&s, log);
                }
                return Ok(*s);
            }
            ReadResult::Corrupt => {
                found = true;
                let stamp = utc_now().chars().filter(|c| c.is_ascii_digit()).take(14).collect::<String>();
                let _ = std::fs::rename(&self.path, with_suffix(&self.path, &format!(".bad-{stamp}")));
            }
            ReadResult::Missing => {}
        }
        let bak = with_suffix(&self.path, ".bak");
        match self.read(&bak, default_host_name, log)? {
            ReadResult::Ok(s, _) => {
                log("settings restored from the .bak copy");
                self.try_save(&s, log);
                return Ok(*s);
            }
            ReadResult::Corrupt => found = true,
            ReadResult::Missing => {}
        }
        if found {
            log("settings unusable; using defaults");
        }
        let d = HostSettings::create_default(default_host_name);
        self.try_save(&d, log);
        Ok(d)
    }

    /// One settings file, validated.
    fn read(&self, path: &Path, default_host_name: &str, log: &dyn Fn(&str)) -> Result<ReadResult, LoadError> {
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let started = Instant::now();
        let mut attempt = 1;
        loop {
            match std::fs::read(path) {
                Ok(bytes) => {
                    let parsed = std::str::from_utf8(&bytes)
                        .map_err(|_| Corrupt("not UTF-8"))
                        .and_then(|text| HostSettings::parse(text, default_host_name));
                    return Ok(match parsed {
                        Ok((s, migrated)) => ReadResult::Ok(Box::new(s), migrated),
                        Err(Corrupt(why)) => {
                            log(&format!("{name} corrupt ({why})"));
                            ReadResult::Corrupt
                        }
                    });
                }
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(ReadResult::Missing),
                Err(e) => {
                    // A scanner or backup tool can hold the file for a moment, e.g. right after boot.
                    if started.elapsed().as_millis() as u64 >= self.locked_wait_ms {
                        // Not corrupt, only locked: falling back would save defaults or the older .bak over it.
                        log(&format!("{name} unreadable ({:?})", e.kind()));
                        return Err(LoadError::Locked(format!("{name} is held by another program")));
                    }
                    std::thread::sleep(Duration::from_millis(locked_retry_delay_ms(attempt)));
                    attempt += 1;
                }
            }
        }
    }

    fn try_save(&self, s: &HostSettings, log: &dyn Fn(&str)) {
        if let Err(e) = self.write_atomic(&s.serialize()) {
            log(&format!("settings save failed: {:?}", e.kind()));
        }
    }

    pub fn save(&self, s: &HostSettings) -> std::io::Result<()> {
        self.write_atomic(&s.serialize())
    }

    /// Writes `.tmp` and flushes it to the disk, keeps the file there was as `.bak`, then puts the new one in its
    /// place in one step: a crash or power cut leaves the old file or the new one, never a half-written one.
    pub fn write_atomic(&self, json: &str) -> std::io::Result<()> {
        let _guard = self.write_lock.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(dir) = self.path.parent() {
            if !dir.as_os_str().is_empty() {
                std::fs::create_dir_all(dir)?;
            }
        }
        let tmp = with_suffix(&self.path, ".tmp");
        {
            let mut f = std::fs::File::create(&tmp)?;
            f.write_all(json.as_bytes())?;
            f.sync_all()?;
        }
        if self.path.exists() {
            // The copy may be cut short by a crash; the file itself is still whole then, and it is read first.
            std::fs::copy(&self.path, with_suffix(&self.path, ".bak"))?;
        }
        std::fs::rename(&tmp, &self.path)
    }
}
