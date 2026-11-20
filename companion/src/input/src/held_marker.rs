//! Crash safety for injected input: while anything is held, %LOCALAPPDATA%\GyroPhone\held.json lists it. Written
//! or deleted by the watchdog's writer thread (at most twice a second) when the engine's held-set version
//! changes, and once more on exit; on the next start the listed keys and buttons are released and the file is
//! deleted. The file is the C# input process's (`{"Keys":[26],"MouseButtons":1}`), so whichever input process
//! starts next lets go of what the other left held. Port of HeldKeysMarker.cs.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use crate::mapping::input_diff::{self, MAX_RELEASE_EVENTS};
use crate::mapping::InjectEvent;
use crate::ports::KeyMouseSink;
use crate::state_frame::KEY_BITMAP_SIZE;

pub struct HeldKeysMarker {
    path: PathBuf,
    /// The held-set version last written; None before the first write.
    written: Mutex<Option<i32>>,
}

impl HeldKeysMarker {
    pub fn new(path: PathBuf) -> HeldKeysMarker {
        HeldKeysMarker { path, written: Mutex::new(None) }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Writes the held set (or deletes the file when nothing is held) if the version changed. A write that fails
    /// is tried again on the next call.
    pub fn update(&self, version: i32, keys: &[u8; KEY_BITMAP_SIZE], mouse_buttons: u8) {
        let mut written = self.written.lock().unwrap_or_else(|e| e.into_inner());
        if *written == Some(version) {
            return;
        }
        let done = if !input_diff::any_held(keys, mouse_buttons) {
            match std::fs::remove_file(&self.path) {
                Ok(()) => true,
                Err(e) => e.kind() == std::io::ErrorKind::NotFound,
            }
        } else {
            if let Some(dir) = self.path.parent().filter(|d| !d.as_os_str().is_empty()) {
                let _ = std::fs::create_dir_all(dir);
            }
            std::fs::write(&self.path, format(keys, mouse_buttons)).is_ok()
        };
        if done {
            *written = Some(version);
        }
    }

    /// Releases whatever a previous run left held, then deletes the marker. Returns the event count. Never fails
    /// for a marker it can't read or delete, so a bad file can't stop the start-up.
    pub fn recover(path: &Path, sink: &dyn KeyMouseSink) -> usize {
        let text = match std::fs::read(path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return 0,
            // Locked by a scanner or backup tool: start anyway; the next start tries again.
            Err(_) => {
                let _ = std::fs::remove_file(path);
                return 0;
            }
        };
        let mut n = 0;
        if let Some((mut keys, mut mouse)) = parse(&text) {
            let mut events = [InjectEvent::default(); MAX_RELEASE_EVENTS];
            n = input_diff::release_all(&mut keys, &mut mouse, &mut events);
            if n > 0 {
                sink.inject(&events[..n]);
            }
        }
        let _ = std::fs::remove_file(path);
        n
    }
}

/// `{"Keys":[4,26],"MouseButtons":1}`: the HID usages held, ascending, and the mouse button bits.
pub fn format(keys: &[u8; KEY_BITMAP_SIZE], mouse_buttons: u8) -> String {
    let mut s = String::from("{\"Keys\":[");
    let mut first = true;
    for u in 0..KEY_BITMAP_SIZE * 8 {
        if keys[u >> 3] & (1 << (u & 7)) != 0 {
            if !first {
                s.push(',');
            }
            first = false;
            s.push_str(&u.to_string());
        }
    }
    s.push_str("],\"MouseButtons\":");
    s.push_str(&mouse_buttons.to_string());
    s.push('}');
    s
}

/// The held set of a marker file; None for anything that is not one. Numbers out of range are skipped, as the C#
/// reader does.
pub fn parse(text: &[u8]) -> Option<([u8; KEY_BITMAP_SIZE], u8)> {
    let v: serde_json::Value = serde_json::from_slice(text).ok()?;
    let o = v.as_object()?;
    let mut keys = [0u8; KEY_BITMAP_SIZE];
    match o.get("Keys") {
        None | Some(serde_json::Value::Null) => {}
        Some(list) => {
            for u in list.as_array()? {
                // The C# file holds ints: anything else makes the whole file unreadable there too.
                let u = u.as_i64()?;
                if (0..256).contains(&u) {
                    keys[(u >> 3) as usize] |= 1 << (u & 7);
                }
            }
        }
    }
    let mouse = match o.get("MouseButtons") {
        None => 0,
        Some(m) => (m.as_i64()? & 0x1F) as u8,
    };
    Some((keys, mouse))
}
