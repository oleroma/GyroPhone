//! Where key file pairing lives on the PC: `%APPDATA%\GyroPhone\host.key` (the private scalar, wrapped by the key
//! protector), `host.pub` next to it, and `authorized_phones\*.pub`. The same files the C# input process keeps.

use crate::crypto::key_pairing::{self, PubKeyFile};
use crate::crypto::P256;
use crate::ports::KeyProtector;
use crate::settings::{locked_retry_delay_ms, utc_now};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// A phone key from authorized_phones: the file it came from and what it holds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AuthorizedPhone {
    pub file: PathBuf,
    pub key: PubKeyFile,
}

/// Why the PC's key can't be had.
#[derive(Debug)]
pub enum KeyError {
    /// host.key stayed held by another program (or the folder can't be written): try again later. Nothing was
    /// replaced.
    Io(std::io::Error),
}

pub struct KeyFiles {
    directory: PathBuf,
    locked_wait_ms: u64,
}

/// What a scan of authorized_phones saw.
pub struct Scan {
    pub phones: Vec<AuthorizedPhone>,
    /// For the Status window: "2 of 3 .pub file(s) valid (x.pub: not a key file)".
    pub summary: String,
    /// The .pub files that could not be opened.
    pub unreadable: Vec<PathBuf>,
}

fn file_name_of(p: &Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

/// File names compared as Windows does: without regard to case.
fn same_file(a: &Path, b: &Path) -> bool {
    a.to_string_lossy().to_lowercase() == b.to_string_lossy().to_lowercase()
}

fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut tmp = path.as_os_str().to_os_string();
    tmp.push(".tmp");
    let tmp = PathBuf::from(tmp);
    {
        use std::io::Write;
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path)
}

impl KeyFiles {
    pub fn new(directory: PathBuf, locked_wait_ms: u64) -> KeyFiles {
        KeyFiles { directory, locked_wait_ms }
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }

    pub fn host_key_path(&self) -> PathBuf {
        self.directory.join("host.key")
    }

    pub fn host_pub_path(&self) -> PathBuf {
        self.directory.join("host.pub")
    }

    pub fn authorized_directory(&self) -> PathBuf {
        self.directory.join("authorized_phones")
    }

    /// The host's static key: loaded from host.key, or created on first start. host.pub is rewritten when it is
    /// missing or differs (for example after a rename of the PC). An error when host.key stays locked; only a key
    /// whose content cannot be used is moved aside and replaced.
    pub fn load_or_create_host_key(
        &self,
        protector: &dyn KeyProtector,
        host_name: &str,
        log: &dyn Fn(&str),
    ) -> Result<P256, KeyError> {
        std::fs::create_dir_all(&self.directory).map_err(KeyError::Io)?;
        std::fs::create_dir_all(self.authorized_directory()).map_err(KeyError::Io)?;
        let path = self.host_key_path();
        let mut key: Option<P256> = None;
        if let Some(blob) = self.read_locked(&path).map_err(KeyError::Io)? {
            let scalar = protector.unprotect(&blob).map(zeroize::Zeroizing::new);
            key = scalar.as_ref().and_then(|d| P256::from_private_scalar(d));
            if key.is_none() {
                // Never overwrite a key we cannot read (another Windows user's protected blob, say).
                let stamp: String = utc_now().chars().filter(|c| c.is_ascii_digit()).take(14).collect();
                let mut bad = path.as_os_str().to_os_string();
                bad.push(format!(".bad-{stamp}"));
                let _ = std::fs::rename(&path, PathBuf::from(bad));
                log("host key unreadable; moved aside, making a new one");
            }
        }
        let key = match key {
            Some(k) => k,
            None => {
                let k = P256::generate();
                let d = k.export_private_scalar();
                write_atomic(&path, &protector.protect(&d[..])).map_err(KeyError::Io)?;
                log(&format!("created host key {}", key_pairing::fingerprint(k.public_key())));
                k
            }
        };
        let line = PubKeyFile { public_key: *key.public_key(), name: host_name.to_string() }.format();
        let pub_path = self.host_pub_path();
        if std::fs::read_to_string(&pub_path).ok().as_deref() != Some(line.as_str()) {
            if let Err(e) = write_atomic(&pub_path, line.as_bytes()) {
                log(&format!("could not write host.pub: {:?}", e.kind()));
            }
        }
        Ok(key)
    }

    /// Reads a file another program may hold for a moment (a scanner or backup tool at logon); None when it isn't
    /// there. An error when it stays locked: a key that is only locked must never be replaced.
    fn read_locked(&self, path: &Path) -> std::io::Result<Option<Vec<u8>>> {
        let started = Instant::now();
        let mut attempt = 1;
        loop {
            match std::fs::read(path) {
                Ok(b) => return Ok(Some(b)),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
                Err(e) => {
                    if started.elapsed().as_millis() as u64 >= self.locked_wait_ms {
                        return Err(e);
                    }
                    std::thread::sleep(Duration::from_millis(locked_retry_delay_ms(attempt)));
                    attempt += 1;
                }
            }
        }
    }

    /// Every valid .pub in authorized_phones, in file name order. Unreadable files are skipped (and named).
    pub fn read_authorized(&self, log: &dyn Fn(&str)) -> std::io::Result<Scan> {
        let dir = self.authorized_directory();
        let entries = match std::fs::read_dir(&dir) {
            Ok(e) => e,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Scan { phones: Vec::new(), summary: "folder missing".to_string(), unreadable: Vec::new() })
            }
            Err(e) => return Err(e),
        };
        let mut files: Vec<PathBuf> = Vec::new();
        for entry in entries {
            let path = entry?.path();
            let is_pub = path.extension().map(|x| x.to_string_lossy().eq_ignore_ascii_case("pub")).unwrap_or(false);
            if is_pub && !path.is_dir() {
                files.push(path);
            }
        }
        files.sort_by_key(|p| p.to_string_lossy().to_lowercase());
        let mut phones = Vec::new();
        let mut problems = Vec::new();
        let mut unreadable = Vec::new();
        for f in &files {
            let name = file_name_of(f);
            let bytes = match std::fs::read(f) {
                Ok(b) => b,
                Err(e) => {
                    // Being written, or held by a scanner: not a sign it was removed.
                    problems.push(format!("{name}: {:?}", e.kind()));
                    unreadable.push(f.clone());
                    continue;
                }
            };
            match std::str::from_utf8(&bytes).ok().and_then(PubKeyFile::parse) {
                Some(key) => phones.push(AuthorizedPhone { file: f.clone(), key }),
                None => {
                    problems.push(format!("{name}: not a key file"));
                    log(&format!("ignoring {name}: not a GyroPhone key file"));
                }
            }
        }
        let mut summary = format!("{} of {} .pub file(s) valid", phones.len(), files.len());
        if !problems.is_empty() {
            summary.push_str(&format!(" ({})", problems.join("; ")));
        }
        Ok(Scan { phones, summary, unreadable })
    }

    /// Writes `key` to authorized_phones (QR code pairing) and returns its path. A file already holding the same
    /// key is reused; a name taken by another key gets " 2", " 3", ...
    pub fn add_authorized(&self, key: &PubKeyFile) -> std::io::Result<PathBuf> {
        let dir = self.authorized_directory();
        std::fs::create_dir_all(&dir)?;
        for a in self.read_authorized(&|_| {})?.phones {
            if a.key.public_key == key.public_key {
                return Ok(a.file);
            }
        }
        let base = if key.name.is_empty() { "Phone" } else { key.name.as_str() };
        let mut path = dir.join(key_pairing::file_name(base));
        let mut n = 2u32;
        while path.exists() {
            path = dir.join(key_pairing::file_name(&format!("{base} {n}")));
            n += 1;
            if n > 10_000 {
                return Err(std::io::Error::other("no free key file name"));
            }
        }
        write_atomic(&path, key.format().as_bytes())?;
        Ok(path)
    }
}

/// The phones of authorized_phones as last read. `reload` reads the folder again: adding a file authorizes that
/// phone at once, deleting it revokes it, and a file that cannot be opened for a moment keeps its phone. Whoever
/// watches the folder (a change notification on Windows) calls `reload` shortly after any change, and again a
/// second later while `retry_wanted` says a file was held.
pub struct AuthorizedPhones {
    last: Vec<AuthorizedPhone>,
    retries: u32,
    /// What the last read of the folder saw, or why it failed.
    pub last_scan: String,
}

impl Default for AuthorizedPhones {
    fn default() -> Self {
        Self::new()
    }
}

impl AuthorizedPhones {
    pub const DEBOUNCE_MS: u64 = 300;
    pub const RETRY_MS: u64 = 1000;
    const MAX_RETRIES: u32 = 30;

    pub fn new() -> AuthorizedPhones {
        AuthorizedPhones { last: Vec::new(), retries: 0, last_scan: "not read yet".to_string() }
    }

    /// Reads the folder; the list to hand to the engine (None when the scan failed: the phones of the last good
    /// one stay), and whether to read again in `RETRY_MS` because a file was held.
    pub fn reload(&mut self, files: &KeyFiles, log: &dyn Fn(&str)) -> (Option<Vec<AuthorizedPhone>>, bool) {
        match files.read_authorized(log) {
            Ok(scan) => {
                let mut list = scan.phones;
                // A file held by another program (a scanner, a backup) keeps the phone it had; read again soon,
                // since no change event may follow.
                for f in &scan.unreadable {
                    if let Some(kept) = self.last.iter().find(|a| same_file(&a.file, f)) {
                        list.push(kept.clone());
                    }
                }
                list.sort_by_key(|a| a.file.to_string_lossy().to_lowercase());
                let retry = if scan.unreadable.is_empty() {
                    self.retries = 0;
                    false
                } else {
                    self.retries += 1;
                    self.retries <= Self::MAX_RETRIES
                };
                self.last = list.clone();
                self.last_scan = scan.summary;
                (Some(list), retry)
            }
            Err(e) => {
                // A scan that fails keeps the phones from the last good one, and says why.
                self.last_scan = format!("failed: {:?}: {e}", e.kind());
                log(&format!("reading authorized_phones failed: {:?}: {e}", e.kind()));
                (None, false)
            }
        }
    }
}
