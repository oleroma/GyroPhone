//! The log file: lines in order with their time, rotation, and a disk that can't be written.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

use gp_input::file_log::{lock_name, AppendLock, FileLog, NoAppendLock};

fn dir() -> PathBuf {
    static N: AtomicU32 = AtomicU32::new(0);
    let d = std::env::temp_dir().join(format!("gp-log-{}-{}", std::process::id(), N.fetch_add(1, Ordering::SeqCst)));
    let _ = std::fs::remove_dir_all(&d);
    d
}

fn log_in(d: &PathBuf, max: u64) -> FileLog {
    FileLog::new(d, "companion.log", max, Box::new(|| "2026-11-19 21:00:00.123".to_string()), Box::new(NoAppendLock))
}

fn lines(p: &std::path::Path) -> Vec<String> {
    std::fs::read_to_string(p).unwrap().lines().map(str::to_string).collect()
}

#[test]
fn lines_are_written_in_order_with_their_time() {
    let d = dir();
    let log = log_in(&d.join("deeper").join("logs"), 1_000_000);
    for i in 0..300 {
        log.write(&format!("line {i}"));
    }
    log.close();
    let got = lines(log.path());
    assert_eq!(300, got.len());
    assert_eq!("2026-11-19 21:00:00.123 line 0", got[0]);
    assert_eq!("2026-11-19 21:00:00.123 line 299", got[299]);
    assert_eq!(None, log.last_error());
    // After close nothing more is written, and nothing waits.
    log.write("late");
    assert_eq!(300, lines(log.path()).len());
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn a_long_file_is_moved_aside_and_a_new_one_started() {
    let d = dir();
    let log = log_in(&d, 200);
    for i in 0..10 {
        log.write(&format!("a line that is fairly long, number {i}"));
    }
    log.close();
    let old = d.join("companion.log.old");
    assert!(old.exists());
    assert!(std::fs::metadata(log.path()).map(|m| m.len()).unwrap_or(0) <= 400);
    let all = lines(&old).len() + if log.path().exists() { lines(log.path()).len() } else { 0 };
    assert!(all >= 4 && all <= 10, "{all} lines kept");
    // A file already too long at the start is moved aside first.
    let d2 = dir();
    std::fs::create_dir_all(&d2).unwrap();
    std::fs::write(d2.join("companion.log"), "x".repeat(500)).unwrap();
    let log = log_in(&d2, 200);
    log.write("fresh");
    log.close();
    assert_eq!(vec!["2026-11-19 21:00:00.123 fresh".to_string()], lines(log.path()));
    assert_eq!(500, std::fs::metadata(d2.join("companion.log.old")).unwrap().len());
    let _ = std::fs::remove_dir_all(&d);
    let _ = std::fs::remove_dir_all(&d2);
}

#[test]
fn a_file_that_cannot_be_written_is_reported_and_costs_the_caller_nothing() {
    let d = dir();
    std::fs::create_dir_all(&d).unwrap();
    // A folder where the file should be.
    std::fs::create_dir_all(d.join("companion.log")).unwrap();
    let log = log_in(&d, 1000);
    let start = std::time::Instant::now();
    for i in 0..5000 {
        log.write(&format!("line {i}"));
    }
    assert!(start.elapsed() < std::time::Duration::from_secs(2));
    log.close();
    assert!(log.last_error().is_some());
    let _ = std::fs::remove_dir_all(&d);
}

#[test]
fn every_line_is_written_under_the_shared_lock() {
    struct Counting(Arc<AtomicU32>);
    impl AppendLock for Counting {
        fn with(&self, f: &mut dyn FnMut()) {
            self.0.fetch_add(1, Ordering::SeqCst);
            f()
        }
    }
    let d = dir();
    let n = Arc::new(AtomicU32::new(0));
    let log = FileLog::new(&d, "input.log", 1000, Box::new(String::new), Box::new(Counting(n.clone())));
    log.write("a");
    log.write("b");
    log.close();
    assert_eq!(2, n.load(Ordering::SeqCst));
    assert_eq!(vec![" a".to_string(), " b".to_string()], lines(log.path()));
    let _ = std::fs::remove_dir_all(&d);
}

/// The lock's name must be the one the C# FileLog makes for the same path, or the two write over each other:
/// "Local\GyroPhoneLog-" + hex of the first 8 bytes of SHA-256 of the upper-cased path.
#[test]
fn the_locks_name_is_the_csharp_sides() {
    // SHA-256("C:\USERS\U\APPDATA\LOCAL\GYROPHONE\LOGS\COMPANION.LOG"), checked with sha256sum.
    let name = lock_name(std::path::Path::new(r"C:\Users\u\AppData\Local\GyroPhone\logs\companion.log"));
    assert_eq!(r"Local\GyroPhoneLog-3A7A3AAC52E7CDF2", name);
    assert_eq!(name, lock_name(std::path::Path::new(r"c:\users\U\appdata\local\gyrophone\LOGS\companion.log")), "case never matters");
    // ß has no single upper-case letter: .NET's ToUpperInvariant leaves it, and so must this.
    assert_ne!(lock_name(std::path::Path::new("straße")), lock_name(std::path::Path::new("STRASSE")));
}
