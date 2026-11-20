//! settings.json: the same file the C# input process keeps. Ports of the C# SettingsAndStatsTests' store tests.

mod common;

use common::{range, rng};
use gp_input::settings::*;
use gp_input::wire::output_mode;
use std::path::PathBuf;

struct Dir(PathBuf);

impl Dir {
    fn new(name: &str) -> Dir {
        let p = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("gp-settings-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&p);
        Dir(p)
    }
    fn store(&self) -> SettingsStore {
        SettingsStore::new(self.0.join("settings.json"), 300)
    }
    fn bad_files(&self) -> usize {
        std::fs::read_dir(&self.0)
            .unwrap()
            .filter(|e| e.as_ref().unwrap().file_name().to_string_lossy().starts_with("settings.json.bad-"))
            .count()
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn quiet(_: &str) {}

fn phone(from: u8, name: &str, key: &[u8]) -> PairedPhone {
    PairedPhone {
        phone_id: range(from, 16),
        name: name.to_string(),
        protected_key: key.to_vec(),
        paired_utc: utc_now(),
        last_seen_utc: NEVER_UTC.to_string(),
    }
}

#[test]
fn settings_round_trip_and_atomic_save() {
    let d = Dir::new("roundtrip");
    let store = d.store();
    let mut s = store.load("PC", &quiet).unwrap();
    s.output_mode = output_mode::DUALSHOCK4;
    s.paired_phones.push(phone(0, "OnePlus 15", &[1, 2, 3]));
    store.save(&s).unwrap();
    store.save(&s).unwrap();
    assert!(d.0.join("settings.json.bak").exists());
    assert!(!d.0.join("settings.json.tmp").exists());
    let text = std::fs::read_to_string(store.path()).unwrap();
    assert!(text.contains("\"OutputMode\": \"DualShock4\""));
    let back = d.store().load("other", &quiet).unwrap();
    assert_eq!(back, s);
    assert_eq!(back.host_name, "PC");
    assert_eq!(back.paired_phones[0].protected_key, [1, 2, 3]);
}

/// A file as the C# input process writes it (escapes and all), with the companion's old fields in it.
const CSHARP_FILE: &str = r#"{
  "Version": 2,
  "HostId": "EBESExQVFhcYGRobHB0eHw==",
  "HostName": "DESKTOP-OLEX",
  "OutputMode": "DualSense",
  "StartWithWindows": true,
  "TrayClick": "PairNewPhone",
  "UsbDirect": true,
  "PairedPhones": [
    {
      "PhoneId": "AAECAwQFBgcICQoLDA0ODw==",
      "Name": "OnePlus 15",
      "ProtectedKey": "AQID\u002B/8=",
      "PairedUtc": "2026-11-01T10:20:30.1234567Z",
      "LastSeenUtc": "2026-11-19T19:30:45.5Z"
    }
  ],
  "Overlays": [ { "Id": "abc", "Kind": "Phone", "Colors": [], "X": 12.5 } ],
  "OverlayPresets": [],
  "OverlaysHidden": true,
  "MeasureButtons": true,
  "Diagnostics": true,
  "DiagnosticsMinutes": 7,
  "UseInputDriver": true,
  "PressKeyListen": false,
  "PressKeyNotice": false,
  "CatchUpMs": 32,
  "OverlayShortcuts": { "LevelAll": "Ctrl\u002BAlt\u002BR", "HideAll": "", "Edit": null },
  "OverlayRestFade": { "On": false, "FadeTo": 0.3 },
  "SomethingANewerBuildAdds": [1, 2, { "a": null }]
}"#;

#[test]
fn a_csharp_file_is_read_and_everything_else_in_it_is_kept() {
    let (s, migrated) = HostSettings::parse(CSHARP_FILE, "other").unwrap();
    assert!(!migrated);
    assert_eq!(s.host_id.to_vec(), range(0x10, 16));
    assert_eq!(s.host_name, "DESKTOP-OLEX");
    assert_eq!(s.output_mode, output_mode::DUALSENSE);
    assert!(s.start_with_windows && s.usb_direct && s.measure_buttons && s.diagnostics && s.use_input_driver);
    assert!(!s.press_key_listen && !s.press_key_notice);
    assert_eq!((s.diagnostics_minutes, s.catch_up_ms), (7, 32));
    let p = &s.paired_phones[0];
    assert_eq!(p.phone_id, range(0, 16));
    assert_eq!(p.protected_key, [1, 2, 3, 0xFB, 0xFF]);
    assert_eq!(p.paired_utc, "2026-11-01T10:20:30.1234567Z");
    assert_eq!(p.last_seen_utc, "2026-11-19T19:30:45.5Z");

    // Saved again: the same JSON value, field for field, in the same order.
    let before: serde_json::Value = serde_json::from_str(CSHARP_FILE).unwrap();
    let text = s.serialize();
    let after: serde_json::Value = serde_json::from_str(&text).unwrap();
    assert_eq!(before, after);
    let keys = |v: &serde_json::Value| v.as_object().unwrap().keys().cloned().collect::<Vec<_>>();
    assert_eq!(keys(&before), keys(&after));

    // A change shows, and nothing else moves.
    let mut changed = s.clone();
    changed.catch_up_ms = 8;
    changed.paired_phones.clear();
    let v: serde_json::Value = serde_json::from_str(&changed.serialize()).unwrap();
    assert_eq!(v["CatchUpMs"], 8);
    assert_eq!(v["PairedPhones"].as_array().unwrap().len(), 0);
    assert_eq!(v["Overlays"], before["Overlays"]);
    assert_eq!(v["TrayClick"], "PairNewPhone");
    assert_eq!(v["SomethingANewerBuildAdds"], before["SomethingANewerBuildAdds"]);
}

#[test]
fn a_new_file_has_the_csharp_defaults() {
    let s = HostSettings::create_default("PC");
    let v: serde_json::Value = serde_json::from_str(&s.serialize()).unwrap();
    assert_eq!(v["Version"], 2);
    assert_eq!(v["OutputMode"], "Xbox360");
    assert_eq!(v["PressKeyListen"], true);
    assert_eq!(v["PressKeyNotice"], true);
    assert_eq!(v["CatchUpMs"], 16);
    assert_eq!(v["DiagnosticsMinutes"], 3);
    assert_eq!(v["Diagnostics"], false);
    assert_eq!(v["UseInputDriver"], false);
    assert_ne!(HostSettings::create_default("PC").host_id, s.host_id);
}

#[test]
fn hand_edited_values_come_into_range_and_an_old_file_starts_with_diagnostics_off() {
    let text = CSHARP_FILE
        .replace("\"Version\": 2", "\"Version\": 1")
        .replace("\"CatchUpMs\": 32", "\"CatchUpMs\": 11")
        .replace("\"DiagnosticsMinutes\": 7", "\"DiagnosticsMinutes\": 500")
        .replace("\"HostName\": \"DESKTOP-OLEX\"", "\"HostName\": \"\"")
        .replace("\"OutputMode\": \"DualSense\"", "\"OutputMode\": 2");
    let (s, migrated) = HostSettings::parse(&text, "fallback").unwrap();
    assert!(migrated);
    assert!(!s.diagnostics);
    assert_eq!((s.catch_up_ms, s.diagnostics_minutes), (8, 30));
    assert_eq!(s.host_name, "fallback");
    assert_eq!(s.output_mode, output_mode::DUALSHOCK4);
    assert_eq!(clamp_catch_up_ms(-5), 0);
    assert_eq!(clamp_catch_up_ms(4), 0); // equally near two: the first, as C#'s MinBy
    assert_eq!(clamp_catch_up_ms(12), 8);
    assert_eq!(clamp_catch_up_ms(i32::MAX), 32);
    assert_eq!(clamp_catch_up_ms(i32::MIN), 0);
}

#[test]
fn a_file_without_a_host_id_or_with_wrong_kinds_is_corrupt() {
    for bad in [
        "",
        "{ broken",
        "[]",
        "null",
        "{}",
        r#"{"HostId": "AAEC"}"#,
        r#"{"HostId": 5}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "CatchUpMs": "8"}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "CatchUpMs": 8.5}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "Diagnostics": 1}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "OutputMode": "Sideways"}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "PairedPhones": {}}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "PairedPhones": [7]}"#,
        r#"{"HostId": "EBESExQVFhcYGRobHB0eHw==", "PairedPhones": [{"PhoneId": "!!"}]}"#,
    ] {
        assert!(HostSettings::parse(bad, "PC").is_err(), "{bad}");
    }
    // The least a file needs.
    let (s, _) = HostSettings::parse(r#"{"HostId": "EBESExQVFhcYGRobHB0eHw=="}"#, "PC").unwrap();
    assert_eq!((s.host_name.as_str(), s.output_mode, s.catch_up_ms), ("PC", output_mode::XBOX360, 16));
    // A byte order mark in front is fine.
    assert!(HostSettings::parse("\u{feff}{\"HostId\": \"EBESExQVFhcYGRobHB0eHw==\"}", "PC").is_ok());
}

#[test]
fn any_text_is_read_without_a_panic() {
    let mut next = rng(0xC0FFEE);
    let pieces = [
        "{", "}", "[", "]", ":", ",", "\"", "\"HostId\"", "\"EBESExQVFhcYGRobHB0eHw==\"", "\"PairedPhones\"", "null",
        "true", "1e999", "-0", "99999999999999999999", "\"\\u0000\"", "\"Version\"", "\"CatchUpMs\"", "\u{feff}", " ",
        "\"OutputMode\"", "\"PhoneId\"", "\"=\"",
    ];
    for _ in 0..50_000 {
        let n = next() % 24;
        let text: String = (0..n).map(|_| pieces[next() as usize % pieces.len()]).collect();
        if let Ok((s, _)) = HostSettings::parse(&text, "PC") {
            let again = HostSettings::parse(&s.serialize(), "PC").unwrap().0;
            assert_eq!(again, s);
        }
    }
}

#[test]
fn corrupt_settings_moved_aside_and_defaults_used() {
    let d = Dir::new("corrupt");
    std::fs::create_dir_all(&d.0).unwrap();
    std::fs::write(d.0.join("settings.json"), "{ broken").unwrap();
    let s = d.store().load("PC", &quiet).unwrap();
    assert!(s.paired_phones.is_empty());
    assert_eq!(d.bad_files(), 1);
    assert!(d.0.join("settings.json").exists());
}

#[test]
fn torn_settings_come_back_from_the_backup() {
    let d = Dir::new("torn");
    let store = d.store();
    let mut s = store.load("PC", &quiet).unwrap();
    s.paired_phones.push(phone(0, "A", &[1]));
    store.save(&s).unwrap();
    s.paired_phones.push(phone(16, "B", &[2]));
    store.save(&s).unwrap(); // .bak now holds the copy with phone A only
    // A power cut after the rename but before the data: right size, all zeros.
    let len = std::fs::metadata(store.path()).unwrap().len() as usize;
    std::fs::write(store.path(), vec![0u8; len]).unwrap();
    let log = std::sync::Mutex::new(Vec::new());
    let back = d.store().load("other", &|m| log.lock().unwrap().push(m.to_string())).unwrap();
    assert_eq!(back.host_id, s.host_id);
    assert_eq!(back.paired_phones.len(), 1);
    assert_eq!(back.paired_phones[0].name, "A");
    assert_eq!(d.bad_files(), 1);
    assert!(log.lock().unwrap().iter().any(|m| m == "settings restored from the .bak copy"));
    assert_eq!(d.store().load("other", &quiet).unwrap().host_id, s.host_id); // and saved back in place
}

#[test]
fn missing_settings_come_back_from_the_backup() {
    let d = Dir::new("missing");
    let store = d.store();
    let s = store.load("PC", &quiet).unwrap();
    store.save(&s).unwrap();
    std::fs::remove_file(store.path()).unwrap();
    assert_eq!(d.store().load("other", &quiet).unwrap().host_id, s.host_id);
}

#[test]
fn settings_that_cannot_be_read_are_never_replaced() {
    // What a file held by another program looks like here: something at the path that can't be read as a file.
    let d = Dir::new("locked");
    std::fs::create_dir_all(d.0.join("settings.json")).unwrap();
    std::fs::write(d.0.join("settings.json.bak"), CSHARP_FILE).unwrap();
    let started = std::time::Instant::now();
    assert!(matches!(d.store().load("other", &quiet), Err(LoadError::Locked(_))));
    assert!(started.elapsed().as_millis() >= 300);
    assert!(d.0.join("settings.json").is_dir());
    assert_eq!(std::fs::read_to_string(d.0.join("settings.json.bak")).unwrap(), CSHARP_FILE);
    assert_eq!(d.bad_files(), 0);
    assert_eq!((locked_retry_delay_ms(1), locked_retry_delay_ms(5), locked_retry_delay_ms(50)), (100, 500, 1000));
}

#[test]
fn times_are_written_as_dotnet_writes_them() {
    assert_eq!(format_utc(0, 0), "1970-01-01T00:00:00Z");
    assert_eq!(format_utc(1_795_203_045, 123_456_789), "2026-11-20T19:30:45.1234567Z");
    assert_eq!(format_utc(1_795_203_045, 500_000_000), "2026-11-20T19:30:45.5Z");
    assert_eq!(format_utc(951_782_400, 99), "2000-02-29T00:00:00Z");
    assert_eq!(format_utc(4_107_542_399, 999_999_999), "2100-02-28T23:59:59.9999999Z");
    assert_eq!(format_utc(u64::MAX, u32::MAX), "9999-12-31T23:59:59.9999999Z");
    let now = utc_now();
    assert!(now.starts_with("20") && now.ends_with('Z') && now.len() >= 20);
}
