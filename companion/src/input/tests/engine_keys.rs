//! Key file and QR code pairing through the engine: ports of the engine tests in the C# KeyPairingTests and
//! QrPairingTests.

mod common;

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use common::harness::{Harness, Setup};
use common::phone::FakePhone;
use common::{arr, bytes, hex, range_n, text, vectors};
use gp_input::codec::{self, Discover};
use gp_input::crypto::key_pairing::{self, PubKeyFile};
use gp_input::crypto::{qr_pairing, P256};
use gp_input::engine::EnrollSave;
use gp_input::key_files::AuthorizedPhone;
use gp_input::wire::{self, announce_flags, enroll_status, packet, KeyId};
use serde_json::Value;

fn k() -> Value {
    vectors("key-v1.json")
}

fn host_key() -> P256 {
    P256::from_private_scalar(&bytes(&k(), "hostPrivD")).unwrap()
}

fn keyed(host_key: Option<P256>) -> Harness {
    Harness::with(Setup { pair_default: false, host_key, ..Setup::default() })
}

fn key_phone() -> FakePhone {
    FakePhone::paired(arr(&k(), "keyPhoneId"), "OnePlus 15", arr(&k(), "pairKey"), "192.168.1.50:40000")
}

fn phone_file(name: &str) -> PubKeyFile {
    PubKeyFile { public_key: arr(&k(), "pkP"), name: name.to_owned() }
}

fn authorized(file: &str, name: &str) -> Vec<AuthorizedPhone> {
    vec![AuthorizedPhone { file: PathBuf::from(file), key: phone_file(name) }]
}

fn discover(d: &Discover) -> [u8; wire::DISCOVER_SIZE] {
    let mut b = [0u8; wire::DISCOVER_SIZE];
    codec::encode_discover(d, &mut b);
    b
}

fn announce_flags_of(h: &Harness, phone: &FakePhone, d: &Discover) -> u16 {
    codec::decode_announce(&h.send(phone, &discover(d)).unwrap()).unwrap().flags
}

fn announce_flags_for(h: &Harness, phone: &FakePhone, host_key_id: Option<KeyId>) -> u16 {
    let d = Discover {
        req_id: 9,
        phone_id: range_n(0x70),
        flags: 0,
        host_key_id: host_key_id.unwrap_or_else(|| arr(&k(), "hostKeyId")),
        phone_key_id: arr(&k(), "phoneKeyId"),
    };
    announce_flags_of(h, phone, &d)
}

const BOTH: u16 = announce_flags::KEY_MATCH | announce_flags::KNOWS_YOUR_KEY;

#[test]
fn authorized_phone_connects_with_its_key() {
    let h = keyed(Some(host_key()));
    let mut phone = key_phone();

    // Unknown until the file appears.
    let hello = phone.hello(None);
    assert_eq!(packet::ERROR, h.send(&phone, &hello).unwrap()[3]);
    assert_eq!(0, announce_flags_for(&h, &phone, None) & announce_flags::KNOWS_YOUR_KEY);

    h.engine.set_authorized_phones(authorized("/keys/OnePlus 15.pub", "OnePlus 15"));
    assert_eq!(BOTH, announce_flags_for(&h, &phone, None) & BOTH);
    h.connect(&mut phone);
    let s = h.engine.snapshot();
    assert!(s.connected);
    assert_eq!(Some("OnePlus 15".to_string()), s.phone_name);
    assert_eq!(1, s.paired_phones.len());
    assert!(s.paired_phones[0].by_key);
    assert_eq!(Some("/keys/OnePlus 15.pub".to_string()), s.paired_phones[0].key_file);
    assert_eq!(Some(text(&k(), "hostFingerprint").to_string()), s.host_key_fingerprint);
    assert!(h.engine.settings().paired_phones.is_empty()); // key pairings are never stored in settings

    // The same list again (a rename, a touch) keeps the session.
    h.engine.set_authorized_phones(authorized("/keys/renamed.pub", "OnePlus 15"));
    assert!(h.engine.snapshot().connected);

    // Deleting the file revokes the phone at once.
    h.sender.clear();
    h.engine.set_authorized_phones(Vec::new());
    assert!(!h.engine.snapshot().connected);
    assert!(!h.opened(&mut phone, packet::HOST_BYE).is_empty());
    let hello = phone.hello(None);
    assert_eq!(packet::ERROR, h.send(&phone, &hello).unwrap()[3]);
}

#[test]
fn host_key_read_later_turns_key_pairing_on() {
    // host.key was held by another program at start-up: the engine starts without it.
    let h = keyed(None);
    h.engine.set_authorized_phones(authorized("p.pub", "p"));
    assert!(h.engine.snapshot().host_key_fingerprint.is_none());
    assert!(h.engine.snapshot().paired_phones.is_empty());
    // The background retry reads it.
    let key = host_key();
    let public = *key.public_key();
    assert!(h.engine.set_host_key(key));
    assert_eq!(Some(text(&k(), "hostFingerprint").to_string()), h.engine.snapshot().host_key_fingerprint);
    assert_eq!(Some(public), h.engine.host_public_key());
    h.engine.set_authorized_phones(authorized("p.pub", "p"));
    let s = h.engine.snapshot();
    assert_eq!(1, s.paired_phones.len());
    assert!(s.paired_phones[0].by_key);
    let phone = key_phone();
    assert_eq!(BOTH, announce_flags_for(&h, &phone, None) & BOTH);
    // A second key never replaces the first.
    assert!(!h.engine.set_host_key(P256::generate()));
    assert_eq!(Some(text(&k(), "hostFingerprint").to_string()), h.engine.snapshot().host_key_fingerprint);
}

#[test]
fn one_sided_keys_are_reported() {
    let h = keyed(Some(host_key()));
    let phone = key_phone();
    // The phone trusts this PC, the PC does not know the phone.
    assert_eq!(announce_flags::KEY_MATCH, announce_flags_for(&h, &phone, None) & BOTH);
    // The PC knows the phone, the phone trusts some other PC.
    h.engine.set_authorized_phones(authorized("p.pub", "p"));
    assert_eq!(announce_flags::KNOWS_YOUR_KEY, announce_flags_for(&h, &phone, Some([0; 8])) & BOTH);
}

#[test]
fn own_key_session_is_not_busy() {
    let h = keyed(Some(host_key()));
    h.engine.set_authorized_phones(authorized("p.pub", "p"));
    let mut phone = key_phone();
    h.connect(&mut phone);
    assert!(h.engine.snapshot().connected);

    // The app's own DISCOVER: store phone id plus its key id. Not busy for the phone that holds the session.
    assert_eq!(0, announce_flags_for(&h, &phone, None) & announce_flags::BUSY);
    // The session flow's DISCOVER names the key phone id itself.
    let own = Discover { req_id: 3, phone_id: arr(&k(), "keyPhoneId"), flags: 0, host_key_id: [0; 8], phone_key_id: [0; 8] };
    assert_eq!(0, announce_flags_of(&h, &phone, &own) & announce_flags::BUSY);
    // Another phone (another key id, or none) still sees BUSY.
    let other = Discover { req_id: 4, phone_id: range_n(0x70), flags: 0, host_key_id: arr(&k(), "hostKeyId"), phone_key_id: range_n(0x01) };
    assert_ne!(0, announce_flags_of(&h, &phone, &other) & announce_flags::BUSY);
    let plain = FakePhone::new(range_n(0x70), "x", "192.168.1.50:40000").discover(1);
    assert_ne!(0, codec::decode_announce(&h.send(&phone, &plain).unwrap()).unwrap().flags & announce_flags::BUSY);
}

#[test]
fn key_phone_skips_the_first_run_pairing_window() {
    let h = keyed(Some(P256::generate()));
    h.engine.set_authorized_phones(authorized("p.pub", "p"));
    h.engine.start(h.now());
    assert_eq!(0, h.engine.snapshot().pairing_window_s);
}

#[test]
fn a_pc_with_no_phone_opens_the_first_run_pairing_window() {
    let h = keyed(None);
    h.engine.start(h.now());
    assert!(h.engine.snapshot().pairing_window_s > 0);
}

// ---- QR code pairing ----

fn req_id() -> u32 {
    vectors("qr-v1.json")["reqId"].as_u64().unwrap() as u32
}

fn enroll(token: &[u8], pk_p: &[u8; wire::PUBLIC_KEY_SIZE]) -> Vec<u8> {
    let mut b = vec![0u8; wire::KEY_ENROLL_SIZE];
    codec::encode_key_enroll(req_id(), token, pk_p, "OnePlus 15", &mut b);
    b
}

fn ack_status(ack: Option<Vec<u8>>) -> u8 {
    let ack = ack.expect("an ack");
    assert_eq!(packet::KEY_ENROLL_ACK, ack[3]);
    assert_eq!(wire::KEY_ENROLL_ACK_SIZE, ack.len());
    ack[24]
}

fn pk_p() -> [u8; wire::PUBLIC_KEY_SIZE] {
    arr(&k(), "pkP")
}

fn saves_as(path: Option<&'static str>) -> EnrollSave {
    Arc::new(move |_| Ok(path.map(PathBuf::from)))
}

#[test]
fn scanned_phone_is_authorized_and_connects() {
    let h = keyed(Some(host_key()));
    let mut phone = key_phone();
    let saved = Arc::new(Mutex::new(Vec::<PubKeyFile>::new()));
    let s2 = saved.clone();
    let save: EnrollSave = Arc::new(move |key| {
        s2.lock().unwrap().push(key.clone());
        Ok(Some(PathBuf::from(format!("/keys/{}", key_pairing::file_name(&key.name)))))
    });

    // A token this PC never issued (another PC's QR code, sent by broadcast): silence.
    assert!(h.send(&phone, &enroll(&[0; 16], &pk_p())).is_none());

    let token = h.engine.open_qr_pairing(save).unwrap();
    assert_eq!(wire::QR_PAIRING_S as i32, h.engine.snapshot().qr_pairing_s);

    // A wrong mac (someone who guessed the token id) gets nothing.
    let mut forged = enroll(&token, &pk_p());
    forged[125] ^= 1;
    assert!(h.send(&phone, &forged).is_none());
    assert!(saved.lock().unwrap().is_empty());

    let ack = h.send(&phone, &enroll(&token, &pk_p())).unwrap();
    assert_eq!(enroll_status::OK, ack_status(Some(ack.clone())));
    assert_eq!(hex(&qr_pairing::ack_mac(&token, &ack[..25], &pk_p())), hex(&ack[25..41]));
    assert_eq!(1, saved.lock().unwrap().len());
    let key = saved.lock().unwrap()[0].clone();
    assert_eq!("OnePlus 15", key.name);
    assert_eq!(Some("OnePlus 15".to_string()), h.engine.snapshot().qr_enrolled_phone);

    // A retransmission is acked again without a second file.
    assert_eq!(enroll_status::OK, ack_status(h.send(&phone, &enroll(&token, &pk_p()))));
    assert_eq!(1, saved.lock().unwrap().len());

    // Another phone with the same QR code: USED.
    let other = P256::generate();
    assert_eq!(enroll_status::USED, ack_status(h.send(&phone, &enroll(&token, other.public_key()))));

    // The phone is authorized at once (before the folder watcher sees the file) and connects by key.
    h.connect(&mut phone);
    let s = h.engine.snapshot();
    assert!(s.connected);
    assert_eq!(1, s.paired_phones.len());
    assert!(s.paired_phones[0].by_key);

    // The folder watcher's next list (with the file) keeps the session.
    h.engine.set_authorized_phones(vec![AuthorizedPhone { file: PathBuf::from("/keys/OnePlus 15.pub"), key }]);
    assert!(h.engine.snapshot().connected);
}

#[test]
fn key_is_saved_off_the_input_thread() {
    let h = Harness::with(Setup { pair_default: false, host_key: Some(host_key()), inline_workers: false, ..Setup::default() });
    let mut phone = key_phone();
    let entered = Arc::new(AtomicU32::new(0));
    let saves = Arc::new(AtomicU32::new(0));
    let go = Arc::new(AtomicBool::new(false));
    let me = std::thread::current().id();
    let (e2, s2, g2) = (entered.clone(), saves.clone(), go.clone());
    let save: EnrollSave = Arc::new(move |key| {
        assert_ne!(me, std::thread::current().id());
        e2.fetch_add(1, Ordering::SeqCst);
        while !g2.load(Ordering::SeqCst) {
            std::thread::sleep(std::time::Duration::from_millis(1)); // the disk taking its time
        }
        s2.fetch_add(1, Ordering::SeqCst);
        Ok(Some(PathBuf::from(format!("/keys/{}", key_pairing::file_name(&key.name)))))
    });
    let token = h.engine.open_qr_pairing(save).unwrap();

    // The save is handed off; the phone hears nothing yet, and its resends start no second save.
    assert!(h.send(&phone, &enroll(&token, &pk_p())).is_none());
    h.until("the save starts", || entered.load(Ordering::SeqCst) == 1);
    assert!(h.send(&phone, &enroll(&token, &pk_p())).is_none());
    assert_eq!(0, saves.load(Ordering::SeqCst));
    assert!(h.engine.snapshot().qr_enrolled_phone.is_none());
    go.store(true, Ordering::SeqCst);
    h.until("the phone is enrolled", || h.engine.snapshot().qr_enrolled_phone.is_some());
    assert_eq!(1, saves.load(Ordering::SeqCst));
    assert_eq!(Some("OnePlus 15".to_string()), h.engine.snapshot().qr_enrolled_phone);
    // The next resend is acked.
    assert_eq!(enroll_status::OK, ack_status(h.send(&phone, &enroll(&token, &pk_p()))));
    assert_eq!(1, entered.load(Ordering::SeqCst));
    h.connect(&mut phone);
    h.until("connected", || h.engine.snapshot().connected);
}

#[test]
fn a_failed_save_is_tried_again_on_the_next_resend() {
    let h = keyed(Some(P256::generate()));
    let phone = h.new_phone();
    let fail = Arc::new(AtomicBool::new(true));
    let f2 = fail.clone();
    let save: EnrollSave = Arc::new(move |_| if f2.load(Ordering::SeqCst) { Err("busy".to_string()) } else { Ok(Some(PathBuf::from("x.pub"))) });
    let token = h.engine.open_qr_pairing(save).unwrap();
    assert!(h.send(&phone, &enroll(&token, &pk_p())).is_none());
    fail.store(false, Ordering::SeqCst);
    assert_eq!(enroll_status::OK, ack_status(h.send(&phone, &enroll(&token, &pk_p()))));
}

#[test]
fn qr_code_expires_and_closes() {
    let h = keyed(Some(P256::generate()));
    let phone = h.new_phone();
    let token = h.engine.open_qr_pairing(saves_as(Some("x.pub"))).unwrap();
    h.clock.advance_ms(wire::QR_PAIRING_S * 1000);
    assert_eq!(0, h.engine.snapshot().qr_pairing_s);
    assert_eq!(enroll_status::EXPIRED, ack_status(h.send(&phone, &enroll(&token, &pk_p()))));

    // The phone keeps its own copy of the token; closing zeroes the engine's.
    let token = h.engine.open_qr_pairing(saves_as(None)).unwrap(); // saving fails: no answer, nothing authorized
    assert!(h.send(&phone, &enroll(&token, &pk_p())).is_none());
    assert!(h.engine.snapshot().paired_phones.is_empty());
    h.engine.close_qr_pairing();
    assert_eq!(enroll_status::EXPIRED, ack_status(h.send(&phone, &enroll(&token, &pk_p()))));

    // A newer QR code: the earlier token stays EXPIRED, a token this PC never issued gets nothing.
    assert!(h.engine.open_qr_pairing(saves_as(Some("x.pub"))).is_some());
    assert_eq!(enroll_status::EXPIRED, ack_status(h.send(&phone, &enroll(&token, &pk_p()))));
    assert!(h.send(&phone, &enroll(&range_n::<16>(0x30), &pk_p())).is_none());

    // Without a host key this PC issued no QR code, so a broadcast KEY_ENROLL gets no NO_KEY.
    let no_key = keyed(None);
    assert!(no_key.engine.open_qr_pairing(saves_as(Some("x.pub"))).is_none());
    assert!(no_key.send(&phone, &enroll(&[0; 16], &pk_p())).is_none());
}
