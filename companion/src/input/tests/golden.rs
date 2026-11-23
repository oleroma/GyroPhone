//! Every value in wire-v1.json, key-v1.json and qr-v1.json must be reproduced byte for byte
//! (the same checks as the companion's GoldenVectorTests, KeyPairingTests and QrPairingTests).

mod common;
use common::*;

use gp_input::codec::{self, *};
use gp_input::crypto::{self, key_pairing, pairing_math, qr_pairing, SessionCipher, P256};
use gp_input::state_frame::StateFrame;
use gp_input::wire::{self, packet};
use serde_json::Value;

fn wire_vectors() -> Value {
    vectors("wire-v1.json")
}

#[test]
fn pairing_math_matches() {
    let root = wire_vectors();
    let p = &root["pairing"];
    let phone = P256::from_private_scalar(&bytes(p, "phonePrivD")).unwrap();
    let host = P256::from_private_scalar(&bytes(p, "hostPrivD")).unwrap();
    assert_eq!(text(p, "pkP"), hex(phone.public_key()));
    assert_eq!(text(p, "pkH"), hex(host.public_key()));
    let z = phone.ecdh(host.public_key()).unwrap();
    assert_eq!(text(p, "ecdhZ"), hex(&z[..]));
    assert_eq!(text(p, "ecdhZ"), hex(&host.ecdh(phone.public_key()).unwrap()[..]));
    assert_eq!(phone.export_private_scalar()[..], bytes(p, "phonePrivD")[..]);

    assert_eq!(text(p, "commit"), hex(&pairing_math::commit(phone.public_key(), &bytes(p, "nP"))));
    let th = pairing_math::th(
        &bytes(p, "phoneId"),
        &bytes(p, "hostId"),
        phone.public_key(),
        host.public_key(),
        &bytes(p, "nP"),
        &bytes(p, "nH"),
        num(p, "attemptId") as u32,
    );
    assert_eq!(text(p, "th"), hex(&th));
    assert_eq!(text(p, "sas"), pairing_math::sas_digits(pairing_math::sas(&th)));
    assert_eq!("400 588", pairing_math::sas_display(pairing_math::sas(&th)));
    let pair_key = pairing_math::pair_key(&z[..], &th);
    assert_eq!(text(p, "pairKey"), hex(&pair_key[..]));
    assert_eq!(text(p, "confirmP"), hex(&pairing_math::confirm_p(&pair_key[..], &th)));
    assert_eq!(text(p, "confirmH"), hex(&pairing_math::confirm_h(&pair_key[..], &th)));
}

#[test]
fn session_keys_match() {
    let root = wire_vectors();
    let s = &root["session"];
    let (k_ph, k_hp) = crypto::session_keys(&bytes(s, "pairKey"), &bytes(s, "nP"), &bytes(s, "nH"), num(s, "sessionId") as u32);
    assert_eq!(text(s, "kPH"), hex(&k_ph[..]));
    assert_eq!(text(s, "kHP"), hex(&k_hp[..]));
    assert_eq!(text(s, "okm"), hex(&k_ph[..]) + &hex(&k_hp[..]));
}

fn frame_from_fields(f: &Value) -> StateFrame {
    let mut fr = StateFrame {
        phone_time_us: num(f, "phoneTimeUs") as u32,
        flags: num(f, "flags") as u16,
        buttons: num(f, "buttons") as u16,
        hat: num(f, "hat") as u8,
        mouse_buttons: num(f, "mouseButtons") as u8,
        lx: num(f, "lx") as i16,
        ly: num(f, "ly") as i16,
        rx: num(f, "rx") as i16,
        ry: num(f, "ry") as i16,
        lt: num(f, "lt") as u16,
        rt: num(f, "rt") as u16,
        mouse_total_x: num(f, "mouseTotalX") as i32,
        mouse_total_y: num(f, "mouseTotalY") as i32,
        wheel_total: num(f, "wheelTotal") as i32,
        ..StateFrame::default()
    };
    for k in f["keys"].as_array().unwrap() {
        fr.set_key(k.as_u64().unwrap() as u32, true);
    }
    fr.keys[gp_input::state_frame::MODIFIER_BYTE] |= num(f, "modifiers") as u8;
    fr
}

#[test]
fn every_packet_encodes_and_decodes() {
    let root = wire_vectors();
    let s = &root["session"];
    let pair_key = bytes(s, "pairKey");
    let mut seen = 0;
    for v in root["packets"].as_array().unwrap() {
        let name = text(v, "name");
        let f = &v["fields"];
        let want = text(v, "hex");
        let raw = unhex(want);
        assert_eq!(num(v, "len") as usize, raw.len(), "{name}");
        assert_eq!(packet::size(raw[3]), Some(raw.len()), "{name}");
        let mut buf = [0xAAu8; wire::MAX_DATAGRAM];
        seen += 1;
        match name {
            "DISCOVER" => {
                let p = Discover { req_id: num(f, "reqId") as u32, phone_id: arr(f, "phoneId"), flags: num(f, "flags") as u16, host_key_id: [0; 8], phone_key_id: [0; 8] };
                let n = encode_discover(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_discover(&raw), Some(p));
            }
            "ANNOUNCE" => {
                let p = Announce { req_id: num(f, "reqId") as u32, host_id: arr(f, "hostId"), flags: num(f, "flags") as u16, name: text(f, "name").into() };
                let n = encode_announce(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_announce(&raw), Some(p));
            }
            "PAIR_START" => {
                let p = PairStart { phone_id: arr(f, "phoneId"), pk_p: arr(f, "pkP"), commit: arr(f, "commit"), name: text(f, "name").into() };
                let n = encode_pair_start(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_pair_start(&raw), Some(p));
            }
            "PAIR_HOST" => {
                let p = PairHost { attempt_id: num(f, "attemptId") as u32, host_id: arr(f, "hostId"), pk_h: arr(f, "pkH"), n_h: arr(f, "nH"), name: text(f, "name").into() };
                let n = encode_pair_host(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_pair_host(&raw), Some(p));
            }
            "PAIR_REVEAL" => {
                let p = PairReveal { attempt_id: num(f, "attemptId") as u32, phone_id: arr(f, "phoneId"), n_p: arr(f, "nP") };
                let n = encode_pair_reveal(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_pair_reveal(&raw), Some(p));
            }
            "PAIR_ACK" => {
                let p = PairAck { attempt_id: num(f, "attemptId") as u32, status: num(f, "status") as u8 };
                let n = encode_pair_ack(p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_pair_ack(&raw), Some(p));
            }
            "PAIR_CONFIRM" => {
                let p = PairConfirm { attempt_id: num(f, "attemptId") as u32, phone_id: arr(f, "phoneId"), confirm_p: arr(f, "confirmP") };
                let n = encode_pair_confirm(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_pair_confirm(&raw), Some(p));
            }
            "PAIR_DONE" => {
                let p = PairDone { attempt_id: num(f, "attemptId") as u32, status: num(f, "status") as u8, confirm_h: arr(f, "confirmH") };
                let n = encode_pair_done(&p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_pair_done(&raw), Some(p));
            }
            "HELLO" => {
                let p = Hello { phone_id: arr(f, "phoneId"), n_p: arr(f, "nP"), caps: num(f, "caps") as u32, max_rate_hz: num(f, "maxRateHz") as u16 };
                let n = encode_hello(&p, &pair_key, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_hello(&raw), Some(p));
                assert!(verify_mac(&pair_key, &raw));
            }
            "WELCOME" => {
                let p = Welcome {
                    session_id: num(f, "sessionId") as u32,
                    host_id: arr(f, "hostId"),
                    n_p: arr(f, "nP"),
                    n_h: arr(f, "nH"),
                    output_mode: num(f, "outputMode") as u8,
                    host_flags: num(f, "hostFlags") as u8,
                    keepalive_ms: num(f, "keepaliveMs") as u16,
                };
                let n = encode_welcome(&p, &pair_key, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_welcome(&raw), Some(p));
                assert!(verify_mac(&pair_key, &raw));
            }
            "ERROR" => {
                let p = ErrorPacket { code: num(f, "code") as u16, max_version: num(f, "maxVersion") as u8 };
                let n = encode_error(p, &mut buf);
                assert_eq!(want, hex(&buf[..n]));
                assert_eq!(decode_error(&raw), Some(p));
            }
            _ => sealed_packet(s, v, f, &raw),
        }
    }
    assert_eq!(seen, 21);
}

fn sealed_packet(s: &Value, v: &Value, f: &Value, raw: &[u8]) {
    let name = text(v, "name");
    let p2h = text(v, "dir") == "P2H";
    let dir = if p2h { wire::DIR_PHONE_TO_HOST } else { wire::DIR_HOST_TO_PHONE };
    let key: [u8; 16] = arr(s, text(v, "key"));
    let sid = num(v, "sessionId") as u32;
    let seq = num(v, "seq") as u32;
    assert_eq!(if p2h { "kPH" } else { "kHP" }, text(v, "key"));
    assert_eq!(p2h, packet::is_phone_to_host_sealed(raw[3]));
    assert!(packet::is_sealed(raw[3]));

    let mut plain = vec![0u8; packet::plain_size(raw[3]).unwrap()];
    if name.starts_with("STATE") {
        frame_from_fields(f).encode(&mut plain);
    } else {
        match name {
            "PING" => drop(encode_ping(num(f, "t1") as u64, &mut plain)),
            "LINK_INFO" => drop(encode_link_info(
                &LinkInfo {
                    clock_offset_us: num(f, "clockOffsetUs"),
                    rtt_min_us: num(f, "rttMinUs") as u32,
                    send_rate_hz: num(f, "sendRateHz") as u16,
                    rssi_dbm: num(f, "rssiDbm") as i8,
                    band: num(f, "band") as u8,
                    link_mbps: num(f, "linkMbps") as u16,
                    freq_mhz: num(f, "freqMhz") as u16,
                    flags: num(f, "flags") as u8,
                    path: 0,
                },
                &mut plain,
            )),
            "BYE" | "HOST_BYE" => drop(encode_byte4(num(f, "reason") as u8, &mut plain)),
            "PONG" => drop(encode_pong(Pong { t1: num(f, "t1") as u64, t2: num(f, "t2") as u64, t3: num(f, "t3") as u64 }, &mut plain)),
            "HOST_STATUS" => drop(encode_host_status(
                &HostStatus {
                    last_seq: num(f, "lastSeq") as u32,
                    rx_per_sec: num(f, "rxPerSec") as u16,
                    loss_permille: num(f, "lossPermille") as u16,
                    lat_p50_us: num(f, "latP50Us") as u32,
                    lat_p99_us: num(f, "latP99Us") as u32,
                    jitter_us: num(f, "jitterUs") as u32,
                    output_mode: num(f, "outputMode") as u8,
                    flags: num(f, "flags") as u8,
                    flags2: num(f, "flags2") as u8,
                },
                &mut plain,
            )),
            "RUMBLE" => drop(encode_rumble(Rumble { large: num(f, "large") as u8, small: num(f, "small") as u8, pattern: 0 }, &mut plain)),
            other => panic!("no encoder for {other}"),
        }
    }
    assert_eq!(text(v, "plaintext"), hex(&plain), "{name}");

    let cipher = SessionCipher::new(&key, dir);
    let mut sealed = vec![0u8; raw.len()];
    assert_eq!(cipher.seal(raw[3], sid, seq, &plain, &mut sealed), Some(raw.len()));
    assert_eq!(text(v, "hex"), hex(&sealed), "{name}");
    // Too small a buffer is refused, never written past.
    assert_eq!(cipher.seal(raw[3], sid, seq, &plain, &mut sealed[..raw.len() - 1]), None);

    let mut opened = vec![0u8; plain.len()];
    assert_eq!(cipher.open(raw, &mut opened), Some(plain.len()));
    assert_eq!(plain, opened);

    match name {
        n if n.starts_with("STATE") => {
            let mut fr = StateFrame::default();
            fr.decode(&opened);
            assert_eq!(fr, frame_from_fields(f));
            let mut again = vec![0u8; wire::STATE_PLAIN];
            fr.encode(&mut again);
            assert_eq!(plain, again);
            assert_eq!(num(f, "modifiers") as u8, fr.modifiers());
        }
        "PING" => assert_eq!(num(f, "t1") as u64, decode_ping(&opened)),
        "LINK_INFO" => {
            let li = decode_link_info(&opened);
            assert_eq!((num(f, "clockOffsetUs"), num(f, "rssiDbm"), num(f, "freqMhz")), (li.clock_offset_us, li.rssi_dbm as i64, li.freq_mhz as i64));
        }
        "PONG" => assert_eq!(num(f, "t3") as u64, decode_pong(&opened).t3),
        "HOST_STATUS" => assert_eq!(num(f, "jitterUs") as u32, decode_host_status(&opened).jitter_us),
        "RUMBLE" => assert_eq!(Rumble { large: 255, small: 64, pattern: 0 }, decode_rumble(&opened)),
        _ => assert_eq!(num(f, "reason") as u8, opened[0]),
    }
}

#[test]
fn invalid_packets_are_rejected() {
    let root = wire_vectors();
    let s = &root["session"];
    let opener = SessionCipher::new(&arr(s, "kPH"), wire::DIR_PHONE_TO_HOST);
    let mut plain = [0u8; wire::MAX_DATAGRAM];
    let invalid = root["invalid"].as_array().unwrap();
    assert_eq!(invalid.len(), 8);
    for inv in invalid {
        let raw = bytes(inv, "hex");
        let frame_ok = raw.len() >= wire::HEADER && raw[0] == wire::MAGIC0 && raw[1] == wire::MAGIC1 && raw[2] == wire::VERSION && packet::size(raw[3]) == Some(raw.len());
        let accepted = frame_ok && if raw[3] == packet::HELLO { verify_mac(&bytes(s, "pairKey"), &raw) } else { opener.open(&raw, &mut plain).is_some() };
        assert!(!accepted, "{}", text(inv, "name"));
        assert!(!codec::check_frame(&raw, raw[3]) || !accepted);
    }
}

#[test]
fn key_file_pairing_matches() {
    let k = vectors("key-v1.json");
    let phone = P256::from_private_scalar(&bytes(&k, "phonePrivD")).unwrap();
    let host = P256::from_private_scalar(&bytes(&k, "hostPrivD")).unwrap();
    assert_eq!(text(&k, "pkP"), hex(phone.public_key()));
    assert_eq!(text(&k, "pkH"), hex(host.public_key()));
    let ss = host.ecdh(phone.public_key()).unwrap();
    assert_eq!(text(&k, "ss"), hex(&ss[..]));
    assert_eq!(text(&k, "salt"), hex(&key_pairing::salt(phone.public_key(), host.public_key())));
    assert_eq!(text(&k, "pairKey"), hex(&key_pairing::pair_key(&ss[..], phone.public_key(), host.public_key())[..]));
    assert_eq!(text(&k, "keyPhoneId"), hex(&key_pairing::key_phone_id(phone.public_key(), host.public_key())));
    assert_eq!(text(&k, "phoneKeyId"), hex(&key_pairing::key_id(phone.public_key())));
    assert_eq!(text(&k, "hostKeyId"), hex(&key_pairing::key_id(host.public_key())));
    assert_eq!(text(&k, "phoneFingerprint"), key_pairing::fingerprint(phone.public_key()));
    assert_eq!(text(&k, "hostFingerprint"), key_pairing::fingerprint(host.public_key()));
    let (id, key) = key_pairing::host_secrets(&host, phone.public_key()).unwrap();
    assert_eq!(text(&k, "keyPhoneId"), hex(&id));
    assert_eq!(text(&k, "pairKey"), hex(&key[..]));

    let file = key_pairing::PubKeyFile { public_key: *phone.public_key(), name: "OnePlus 15".into() };
    assert_eq!(format!("{}\n", text(&k, "phoneFile")), file.format());
    assert_eq!(key_pairing::PubKeyFile::parse(text(&k, "phoneFile")), Some(file));
    let host_file = key_pairing::PubKeyFile::parse(&format!("\r\n  {}  \r\n", text(&k, "hostFile"))).unwrap();
    assert_eq!(host_file.public_key, *host.public_key());
    assert_eq!(host_file.name, "OLEX");

    let d = &k["discover"];
    let p = Discover { req_id: num(d, "reqId") as u32, phone_id: arr(d, "phoneId"), flags: num(d, "flags") as u16, host_key_id: arr(d, "hostKeyId"), phone_key_id: arr(d, "phoneKeyId") };
    let mut buf = [0u8; wire::MAX_DATAGRAM];
    let n = encode_discover(&p, &mut buf);
    assert_eq!(text(d, "hex"), hex(&buf[..n]));
    assert_eq!(decode_discover(&bytes(d, "hex")), Some(p));
}

#[test]
fn key_files_refuse_bad_input() {
    let k = vectors("key-v1.json");
    let good = text(&k, "phoneFile");
    assert!(key_pairing::PubKeyFile::parse("").is_none());
    assert!(key_pairing::PubKeyFile::parse("ssh-ed25519 AAAA x").is_none());
    assert!(key_pairing::PubKeyFile::parse("gyrophone-p256").is_none());
    assert!(key_pairing::PubKeyFile::parse("gyrophone-p256 !!!! x").is_none());
    // A point off the curve: the last base64 digit before the padding changed.
    let bad = good.replace("gaE=", "gaI=");
    assert_ne!(bad, good);
    assert!(key_pairing::PubKeyFile::parse(&bad).is_none());
    assert!(!P256::is_valid_public(&[4u8; 65]));
    assert!(!P256::is_valid_public(&[0u8; 65]));
    assert!(!P256::is_valid_public(&[4u8; 64]));
    assert!(P256::from_private_scalar(&[0u8; 32]).is_none());
    assert!(P256::from_private_scalar(&[0xFFu8; 32]).is_none());
    assert_eq!(key_pairing::file_name("  Olex's: phone?. "), "Olex_s_ phone_.pub");
    assert_eq!(key_pairing::file_name("///"), "___.pub");
    assert_eq!(key_pairing::file_name(" . "), "key.pub");
}

#[test]
fn qr_pairing_matches() {
    let k = vectors("key-v1.json");
    let q = vectors("qr-v1.json");
    let token = bytes(&q, "token");
    let pk_p: [u8; 65] = arr(&k, "pkP");
    let pk_h = bytes(&k, "pkH");
    let req_id = num(&q, "reqId") as u32;
    assert_eq!(text(&q, "tokenId"), hex(&qr_pairing::token_id(&token)));
    let addresses: Vec<String> = q["addresses"].as_array().unwrap().iter().map(|a| a.as_str().unwrap().to_string()).collect();
    assert_eq!(text(&q, "payload"), qr_pairing::format(&pk_h, &token, text(&q, "hostName"), &addresses, num(&q, "port") as u16));

    let mut buf = [0u8; wire::MAX_DATAGRAM];
    let n = encode_key_enroll(req_id, &token, &pk_p, text(&q, "phoneName"), &mut buf);
    assert_eq!(text(&q, "enroll"), hex(&buf[..n]));
    let enroll = bytes(&q, "enroll");
    let d = decode_key_enroll(&enroll).unwrap();
    assert_eq!((d.req_id, d.name.as_str()), (req_id, text(&q, "phoneName")));
    assert_eq!(d.pk_p, pk_p);
    assert_eq!(hex(&d.token_id), text(&q, "tokenId"));
    assert!(qr_pairing::check_enroll(&enroll, &token));
    let mut other = token.clone();
    other[0] ^= 1;
    assert!(!qr_pairing::check_enroll(&enroll, &other));
    let mut tampered = enroll.clone();
    tampered[30] ^= 1;
    assert!(!qr_pairing::check_enroll(&tampered, &token));

    let token_id = qr_pairing::token_id(&token);
    let n = encode_key_enroll_ack(req_id, &token_id, wire::enroll_status::OK, Some(&token), &pk_p, &mut buf);
    assert_eq!(text(&q, "ackOk"), hex(&buf[..n]));
    let n = encode_key_enroll_ack(req_id, &token_id, wire::enroll_status::USED, Some(&token), &pk_p, &mut buf);
    assert_eq!(text(&q, "ackUsed"), hex(&buf[..n]));
    let n = encode_key_enroll_ack(req_id, &token_id, wire::enroll_status::EXPIRED, None, &pk_p, &mut buf);
    assert_eq!(text(&q, "ackExpired"), hex(&buf[..n]));
}

#[test]
fn names_are_cut_on_a_character_and_read_back() {
    let mut b = [0u8; 64];
    // 29 bytes max: "ü" is two bytes, so 15 of them (30 bytes) are cut to 14.
    codec::write_name(&mut b, 0, &"ü".repeat(15), wire::ANNOUNCE_NAME_MAX);
    assert_eq!(b[0], 28);
    assert_eq!(codec::read_name(&b, 0, wire::ANNOUNCE_NAME_MAX).unwrap(), "ü".repeat(14));
    // A length byte past the field is refused.
    b[0] = 30;
    assert!(codec::read_name(&b, 0, wire::ANNOUNCE_NAME_MAX).is_none());
    // Bytes that are not UTF-8 never fail or panic.
    b[0] = 2;
    b[1] = 0xFF;
    b[2] = 0xFE;
    assert_eq!(codec::read_name(&b, 0, wire::ANNOUNCE_NAME_MAX).unwrap(), "\u{FFFD}\u{FFFD}");
}

#[test]
fn base64_round_trips_and_refuses_junk() {
    for n in 0..70usize {
        let data: Vec<u8> = (0..n).map(|i| (i * 37 + 11) as u8).collect();
        let s = crypto::base64_encode(&data, false);
        assert_eq!(crypto::base64_decode(&s).as_deref(), Some(&data[..]));
        assert!(!crypto::base64_encode(&data, true).contains(['=', '+', '/']));
    }
    for bad in ["A", "AB", "ABC", "A===", "=AAA", "AA=A", "AAA\n", "AA==AAAA", "AAA*"] {
        assert!(crypto::base64_decode(bad).is_none(), "{bad}");
    }
}

/// logfile-v1.json: the key the phone's Diagnostics file is fetched under (the companion fetches it, LogFileWire).
#[test]
fn log_file_key_matches_vector() {
    let v = vectors("logfile-v1.json");
    let k_ph: [u8; 16] = arr(&v, "kPH");
    let key = crypto::log_file_key(&k_ph, num(&v, "sessionId") as u32);
    assert_eq!(hex(&key), v["logKey"].as_str().unwrap());
}
