//! host.key, host.pub and authorized_phones. Ports of the C# KeyPairingTests' file tests.

mod common;

use common::{text, vectors};
use gp_input::crypto::key_pairing::PubKeyFile;
use gp_input::key_files::*;
use gp_input::ports::KeyProtector;
use std::path::PathBuf;

/// Keys as they are (the C# tests' PlainProtector), marked so a wrong blob shows.
struct Plain;

impl KeyProtector for Plain {
    fn protect(&self, plain: &[u8]) -> Vec<u8> {
        let mut v = b"plain:".to_vec();
        v.extend_from_slice(plain);
        v
    }
    fn unprotect(&self, blob: &[u8]) -> Option<Vec<u8>> {
        blob.strip_prefix(b"plain:").map(|b| b.to_vec())
    }
}

struct Dir(PathBuf);

impl Dir {
    fn new(name: &str) -> Dir {
        let p = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("gp-keys-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&p);
        Dir(p)
    }
    fn files(&self) -> KeyFiles {
        KeyFiles::new(self.0.clone(), 300)
    }
    fn count(&self, prefix: &str) -> usize {
        std::fs::read_dir(&self.0)
            .unwrap()
            .filter(|e| e.as_ref().unwrap().file_name().to_string_lossy().starts_with(prefix))
            .count()
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn quiet(_: &str) {}

fn phone_file() -> String {
    text(&vectors("key-v1.json"), "phoneFile").to_string()
}

#[test]
fn host_key_is_created_once_and_authorized_files_are_read() {
    let d = Dir::new("create");
    let files = d.files();
    let first = files.load_or_create_host_key(&Plain, "OLEX", &quiet).unwrap();
    let second = files.load_or_create_host_key(&Plain, "OLEX", &quiet).unwrap();
    assert_eq!(first.public_key(), second.public_key());
    let line = std::fs::read_to_string(files.host_pub_path()).unwrap();
    let public = PubKeyFile::parse(&line).unwrap();
    assert_eq!(&public.public_key, first.public_key());
    assert_eq!(public.name, "OLEX");
    // The PC renamed: host.pub follows, the key stays.
    let third = files.load_or_create_host_key(&Plain, "NEW-NAME", &quiet).unwrap();
    assert_eq!(first.public_key(), third.public_key());
    assert_eq!(PubKeyFile::parse(&std::fs::read_to_string(files.host_pub_path()).unwrap()).unwrap().name, "NEW-NAME");

    let dir = files.authorized_directory();
    std::fs::write(dir.join("OnePlus 15.pub"), phone_file() + "\r\n").unwrap();
    std::fs::write(dir.join("junk.pub"), "hello").unwrap();
    std::fs::write(dir.join("bytes.PUB"), [0xFF, 0xFE, 0x00]).unwrap();
    std::fs::write(dir.join("notes.txt"), phone_file()).unwrap();
    let scan = files.read_authorized(&quiet).unwrap();
    assert_eq!(scan.phones.len(), 1);
    assert_eq!(scan.phones[0].key.name, "OnePlus 15");
    assert_eq!(scan.summary, "1 of 3 .pub file(s) valid (bytes.PUB: not a key file; junk.pub: not a key file)");

    let mut watched = AuthorizedPhones::new();
    let (list, retry) = watched.reload(&files, &quiet);
    assert_eq!((list.unwrap().len(), retry), (1, false));
    std::fs::remove_file(dir.join("OnePlus 15.pub")).unwrap();
    assert_eq!(watched.reload(&files, &quiet).0.unwrap().len(), 0);
    assert!(watched.last_scan.starts_with("0 of 2"));
}

#[test]
fn a_host_key_that_cannot_be_read_is_never_replaced() {
    let d = Dir::new("locked");
    let files = d.files();
    // What a key held by another program looks like here: something at its path that can't be read as a file.
    std::fs::create_dir_all(files.host_key_path()).unwrap();
    let started = std::time::Instant::now();
    assert!(matches!(files.load_or_create_host_key(&Plain, "OLEX", &quiet), Err(KeyError::Io(_))));
    assert!(started.elapsed().as_millis() >= 300);
    assert!(files.host_key_path().is_dir());
    assert_eq!(d.count("host.key.bad-"), 0);
}

#[test]
fn a_host_key_whose_content_is_unusable_is_moved_aside_and_a_new_one_made() {
    for bad in [b"another user's blob".to_vec(), b"plain:short".to_vec(), [b"plain:".as_slice(), &[0u8; 32]].concat()] {
        let d = Dir::new("bad");
        let files = d.files();
        std::fs::create_dir_all(&d.0).unwrap();
        std::fs::write(files.host_key_path(), &bad).unwrap();
        let log = std::sync::Mutex::new(Vec::new());
        let key = files.load_or_create_host_key(&Plain, "OLEX", &|m| log.lock().unwrap().push(m.to_string())).unwrap();
        assert_eq!(d.count("host.key.bad-"), 1);
        assert!(log.lock().unwrap()[0].starts_with("host key unreadable"));
        // The new key is the one on the disk now.
        assert_eq!(files.load_or_create_host_key(&Plain, "OLEX", &quiet).unwrap().public_key(), key.public_key());
    }
}

#[test]
fn a_qr_paired_phone_gets_a_key_file_of_its_own() {
    let d = Dir::new("add");
    let files = d.files();
    let key = PubKeyFile::parse(&phone_file()).unwrap();
    let named = |name: &str| PubKeyFile { public_key: key.public_key, name: name.to_string() };
    let path = files.add_authorized(&named("My: Phone?")).unwrap();
    assert_eq!(path.file_name().unwrap().to_string_lossy(), "My_ Phone_.pub");
    // The same key again: the same file, whatever it is called now.
    assert_eq!(files.add_authorized(&named("Other")).unwrap(), path);
    // Another key under a taken name gets a number.
    let other = gp_input::crypto::P256::generate();
    let second = files.add_authorized(&PubKeyFile { public_key: *other.public_key(), name: "My: Phone?".into() }).unwrap();
    assert_eq!(second.file_name().unwrap().to_string_lossy(), "My_ Phone_ 2.pub");
    let nameless = gp_input::crypto::P256::generate();
    let third = files.add_authorized(&PubKeyFile { public_key: *nameless.public_key(), name: String::new() }).unwrap();
    assert_eq!(third.file_name().unwrap().to_string_lossy(), "Phone.pub");
    let scan = files.read_authorized(&quiet).unwrap();
    assert_eq!(scan.phones.len(), 3);
    assert_eq!(scan.phones[0].key.name, "My: Phone?");
}

#[test]
fn a_key_file_that_cannot_be_opened_keeps_its_phone() {
    let d = Dir::new("held");
    let files = d.files();
    let dir = files.authorized_directory();
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("OnePlus 15.pub");
    std::fs::write(&path, phone_file() + "\r\n").unwrap();
    let mut watched = AuthorizedPhones::new();
    assert_eq!(watched.reload(&files, &quiet).0.unwrap().len(), 1);

    // Held: here, a file whose reading fails (a dangling link is reported missing, so use one that loops).
    std::fs::remove_file(&path).unwrap();
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(&path, &path).unwrap();
        let (list, retry) = watched.reload(&files, &quiet);
        let list = list.unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].key.name, "OnePlus 15");
        assert!(retry);
        // It gives up asking to be read again after a while, and still keeps the phone.
        let mut retries = 1;
        while watched.reload(&files, &quiet).1 {
            retries += 1;
            assert!(retries < 100);
        }
        assert_eq!(retries, 30);
        assert_eq!(watched.reload(&files, &quiet).0.unwrap().len(), 1);
        std::fs::remove_file(&path).unwrap();
    }
    let (list, retry) = watched.reload(&files, &quiet);
    assert!(list.unwrap().is_empty());
    assert!(!retry);
}

#[test]
fn a_missing_folder_is_no_phones() {
    let d = Dir::new("none");
    let scan = d.files().read_authorized(&quiet).unwrap();
    assert!(scan.phones.is_empty());
    assert_eq!(scan.summary, "folder missing");
}
