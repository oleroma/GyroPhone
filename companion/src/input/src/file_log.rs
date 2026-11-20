//! The log file (FileLog.cs): append-only, in %LOCALAPPDATA%\GyroPhone\logs. `write` only queues the line (and
//! wakes the writer), so it never waits on the disk: safe to call from gp-rx. The writer thread (gp-log) does
//! the file work. Callers log counters and lifecycle events only, never keys.
//!
//! The companion, the input process and the watcher write the same file, so it is opened only for the line, and
//! a lock shared by all of them (a named mutex on Windows) lets one write at a time.

use std::collections::VecDeque;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};

const MAX_QUEUED: usize = 1024;

/// The lock every process writing the file shares: runs `f` while holding it (or after waiting half a second
/// for it in vain: a line is better than none).
pub trait AppendLock: Send + Sync {
    fn with(&self, f: &mut dyn FnMut());
}

/// No other process writes the file.
pub struct NoAppendLock;

impl AppendLock for NoAppendLock {
    fn with(&self, f: &mut dyn FnMut()) {
        f()
    }
}

struct Inner {
    path: PathBuf,
    max_bytes: u64,
    queue: Mutex<VecDeque<String>>,
    wake: Condvar,
    closing: AtomicBool,
    last_error: Mutex<Option<String>>,
    /// The local time as "yyyy-MM-dd HH:mm:ss.fff".
    now: Box<dyn Fn() -> String + Send + Sync>,
    lock: Box<dyn AppendLock>,
}

pub struct FileLog {
    inner: Arc<Inner>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

/// The name of the lock for `path`, the same in every process of the user's session: the C# side's
/// `Local\GyroPhoneLog-` + the first 8 bytes of SHA-256 of the upper-case path, as hex.
pub fn lock_name(path: &Path) -> String {
    // Upper case the way .NET's ToUpperInvariant does it: letter by letter, never one letter into two.
    let upper: String = path
        .to_string_lossy()
        .chars()
        .map(|c| {
            let mut u = c.to_uppercase();
            match (u.next(), u.next()) {
                (Some(one), None) => one,
                _ => c,
            }
        })
        .collect();
    let hash = crate::crypto::sha256(&[upper.as_bytes()]);
    let mut name = String::from(r"Local\GyroPhoneLog-");
    for b in &hash[..8] {
        name.push_str(&format!("{b:02X}"));
    }
    name
}

impl FileLog {
    pub fn new(
        directory: &Path,
        file_name: &str,
        max_bytes: u64,
        now: Box<dyn Fn() -> String + Send + Sync>,
        lock: Box<dyn AppendLock>,
    ) -> FileLog {
        let inner = Arc::new(Inner {
            path: directory.join(file_name),
            max_bytes,
            queue: Mutex::new(VecDeque::with_capacity(64)),
            wake: Condvar::new(),
            closing: AtomicBool::new(false),
            last_error: Mutex::new(None),
            now,
            lock,
        });
        let for_thread = inner.clone();
        // Normal priority, not below: a writer starved by a busy PC would only let lines pile up.
        let thread = std::thread::Builder::new().name("gp-log".to_string()).spawn(move || for_thread.run()).ok();
        FileLog { inner, thread: Mutex::new(thread) }
    }

    pub fn path(&self) -> &Path {
        &self.inner.path
    }

    /// Why the last write failed, or None after a good one.
    pub fn last_error(&self) -> Option<String> {
        self.inner.last_error.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn write(&self, message: &str) {
        if self.inner.closing.load(Ordering::Acquire) {
            return; // late messages during exit are dropped
        }
        let line = format!("{} {message}", (self.inner.now)());
        let mut q = self.inner.queue.lock().unwrap_or_else(|e| e.into_inner());
        // Dropped when the disk stalls, rather than holding up a hot thread.
        if q.len() >= MAX_QUEUED {
            return;
        }
        q.push_back(line);
        drop(q);
        self.inner.wake.notify_one();
    }

    /// Writes what is queued and stops the writer (waiting up to a second for it).
    pub fn close(&self) {
        self.inner.closing.store(true, Ordering::Release);
        {
            let _q = self.inner.queue.lock().unwrap_or_else(|e| e.into_inner());
            self.inner.wake.notify_all();
        }
        let t = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(t) = t {
            let start = std::time::Instant::now();
            while !t.is_finished() && start.elapsed() < std::time::Duration::from_millis(1000) {
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
            if t.is_finished() {
                let _ = t.join();
            }
        }
    }
}

impl Drop for FileLog {
    fn drop(&mut self) {
        self.close();
    }
}

impl Inner {
    fn set_error(&self, e: Option<&std::io::Error>) {
        *self.last_error.lock().unwrap_or_else(|e| e.into_inner()) = e.map(|e| format!("{:?}: {e}", e.kind()));
    }

    fn run(&self) {
        let prepared = self.path.parent().map(std::fs::create_dir_all).unwrap_or(Ok(()));
        match prepared {
            Ok(()) => {
                if std::fs::metadata(&self.path).is_ok_and(|m| m.is_file() && m.len() > self.max_bytes) {
                    self.rotate();
                }
            }
            Err(e) => self.set_error(Some(&e)),
        }
        loop {
            let closing = self.closing.load(Ordering::Acquire);
            loop {
                let line = self.queue.lock().unwrap_or_else(|e| e.into_inner()).pop_front();
                let Some(line) = line else { break };
                self.write_line(&line);
            }
            if closing {
                return;
            }
            let q = self.queue.lock().unwrap_or_else(|e| e.into_inner());
            let _q = self.wake.wait_while(q, |q| q.is_empty() && !self.closing.load(Ordering::Acquire)).unwrap_or_else(|e| e.into_inner());
        }
    }

    fn write_line(&self, line: &str) {
        let mut result = Ok(0);
        self.lock.with(&mut || result = self.append(line));
        match result {
            Ok(len) => {
                if len > self.max_bytes {
                    self.rotate();
                }
                self.set_error(None);
            }
            Err(e) => self.set_error(Some(&e)),
        }
    }

    /// Appends one line and returns the file's length.
    fn append(&self, line: &str) -> std::io::Result<u64> {
        let mut f = std::fs::OpenOptions::new().append(true).create(true).open(&self.path)?;
        let mut text = String::with_capacity(line.len() + 2);
        text.push_str(line);
        text.push_str(if cfg!(windows) { "\r\n" } else { "\n" });
        f.write_all(text.as_bytes())?;
        Ok(f.metadata()?.len())
    }

    fn rotate(&self) {
        let mut old = self.path.clone().into_os_string();
        old.push(".old");
        // Open in another program: tried again after the next line.
        let _ = std::fs::rename(&self.path, old);
    }
}
