//! Shared by the test files: the golden vectors of core/src/test/resources/protocol/v1 and hex helpers.
#![allow(dead_code)]

use serde_json::Value;

pub fn vectors(name: &str) -> Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../core/src/test/resources/protocol/v1/");
    let text = std::fs::read_to_string(format!("{path}{name}")).expect("vector file");
    serde_json::from_str(&text).expect("vector json")
}

pub fn unhex(s: &str) -> Vec<u8> {
    assert!(s.len() % 2 == 0, "odd hex");
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex")).collect()
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn bytes(v: &Value, prop: &str) -> Vec<u8> {
    unhex(v[prop].as_str().unwrap_or_else(|| panic!("no hex {prop}")))
}

pub fn arr<const N: usize>(v: &Value, prop: &str) -> [u8; N] {
    bytes(v, prop).try_into().unwrap_or_else(|_| panic!("{prop} is not {N} bytes"))
}

pub fn num(v: &Value, prop: &str) -> i64 {
    v[prop].as_i64().unwrap_or_else(|| panic!("no number {prop}"))
}

pub fn text<'a>(v: &'a Value, prop: &str) -> &'a str {
    v[prop].as_str().unwrap_or_else(|| panic!("no string {prop}"))
}

pub fn range(from: u8, count: usize) -> Vec<u8> {
    (0..count).map(|i| from.wrapping_add(i as u8)).collect()
}

pub fn range_n<const N: usize>(from: u8) -> [u8; N] {
    let mut a = [0u8; N];
    for (i, b) in a.iter_mut().enumerate() {
        *b = from.wrapping_add(i as u8);
    }
    a
}

/// A small fixed-seed generator for the hammering tests.
pub fn rng(seed: u64) -> impl FnMut() -> u64 {
    let mut s = seed;
    move || {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        s
    }
}

pub mod phone {
    //! A phone in a test: builds requests with the library and checks replies like the real app.
    //! Port of the C# tests' FakePhone.

    use std::net::SocketAddr;

    use gp_input::codec::{self, Discover, Hello, PairConfirm, PairReveal, PairStart};
    use gp_input::crypto::{self, pairing_math, SessionCipher, P256};
    use gp_input::le;
    use gp_input::state_frame::StateFrame;
    use gp_input::wire::{self, Hash, Id, Nonce, PublicKey};

    pub struct FakePhone {
        pub phone_id: Id,
        pub name: String,
        pub addr: SocketAddr,
        pub pair_key: Option<[u8; 32]>,
        pub attempt_id: u32,
        pub sas: Option<String>,
        pub session_id: u32,
        pub hello_nonce: Nonce,
        pub seq: u32,
        pub rx_highest: u32,
        pub frame: StateFrame,
        pub welcome_host_flags: u8,
        tx: Option<SessionCipher>,
        rx: Option<SessionCipher>,
        key: Option<P256>,
        pk_h: PublicKey,
        th: Hash,
        n_p_pair: Nonce,
    }

    fn random<const N: usize>() -> [u8; N] {
        let mut b = [0u8; N];
        crypto::random(&mut b);
        b
    }

    impl FakePhone {
        pub fn new(phone_id: Id, name: &str, addr: &str) -> FakePhone {
            FakePhone {
                phone_id,
                name: name.to_owned(),
                addr: addr.parse().expect("address"),
                pair_key: None,
                attempt_id: 0,
                sas: None,
                session_id: 0,
                hello_nonce: [0; 16],
                seq: 0,
                rx_highest: 0,
                frame: StateFrame::default(),
                welcome_host_flags: 0,
                tx: None,
                rx: None,
                key: None,
                pk_h: [0; wire::PUBLIC_KEY_SIZE],
                th: [0; 32],
                n_p_pair: [0; 16],
            }
        }

        pub fn paired(phone_id: Id, name: &str, pair_key: [u8; 32], addr: &str) -> FakePhone {
            let mut p = FakePhone::new(phone_id, name, addr);
            p.pair_key = Some(pair_key);
            p
        }

        pub fn discover(&self, req_id: u32) -> Vec<u8> {
            let mut b = vec![0u8; wire::DISCOVER_SIZE];
            codec::encode_discover(
                &Discover { req_id, phone_id: self.phone_id, flags: 0, host_key_id: [0; 8], phone_key_id: [0; 8] },
                &mut b,
            );
            b
        }

        /// A new key and commit each call, as when the phone starts over.
        pub fn pair_start(&mut self) -> Vec<u8> {
            let key = P256::generate();
            self.n_p_pair = random();
            let m = PairStart {
                phone_id: self.phone_id,
                pk_p: *key.public_key(),
                commit: pairing_math::commit(key.public_key(), &self.n_p_pair),
                name: self.name.clone(),
            };
            self.key = Some(key);
            let mut b = vec![0u8; wire::PAIR_START_SIZE];
            codec::encode_pair_start(&m, &mut b);
            b
        }

        pub fn on_pair_host(&mut self, d: &[u8]) -> bool {
            let Some(h) = codec::decode_pair_host(d) else { return false };
            let Some(key) = &self.key else { return false };
            self.attempt_id = h.attempt_id;
            self.pk_h = h.pk_h;
            self.th = pairing_math::th(&self.phone_id, &h.host_id, key.public_key(), &h.pk_h, &self.n_p_pair, &h.n_h, h.attempt_id);
            self.sas = Some(pairing_math::sas_display(pairing_math::sas(&self.th)));
            true
        }

        pub fn pair_reveal(&self, n_p: Option<Nonce>) -> Vec<u8> {
            let mut b = vec![0u8; wire::PAIR_REVEAL_SIZE];
            codec::encode_pair_reveal(
                &PairReveal { attempt_id: self.attempt_id, phone_id: self.phone_id, n_p: n_p.unwrap_or(self.n_p_pair) },
                &mut b,
            );
            b
        }

        fn derived_pair_key(&self) -> [u8; 32] {
            let z = self.key.as_ref().expect("key").ecdh(&self.pk_h).expect("ecdh");
            *pairing_math::pair_key(&z[..], &self.th)
        }

        pub fn pair_confirm(&self, corrupt: bool) -> Vec<u8> {
            let mut c = pairing_math::confirm_p(&self.derived_pair_key(), &self.th);
            if corrupt {
                c[0] ^= 1;
            }
            let mut b = vec![0u8; wire::PAIR_CONFIRM_SIZE];
            codec::encode_pair_confirm(&PairConfirm { attempt_id: self.attempt_id, phone_id: self.phone_id, confirm_p: c }, &mut b);
            b
        }

        /// Verifies an ACCEPTED PAIR_DONE's confirmH and stores the pairKey. Returns (ok, status).
        pub fn on_pair_done(&mut self, d: &[u8]) -> (bool, u8) {
            let Some(done) = codec::decode_pair_done(d) else { return (false, 0xFF) };
            if done.status != wire::pair_done::ACCEPTED {
                return (false, done.status);
            }
            let pk = self.derived_pair_key();
            if pairing_math::confirm_h(&pk, &self.th) != done.confirm_h {
                return (false, done.status);
            }
            self.pair_key = Some(pk);
            (true, done.status)
        }

        pub fn hello(&mut self, n_p: Option<Nonce>) -> Vec<u8> {
            self.hello_nonce = n_p.unwrap_or_else(random);
            let mut b = vec![0u8; wire::HELLO_SIZE];
            codec::encode_hello(
                &Hello { phone_id: self.phone_id, n_p: self.hello_nonce, caps: wire::hello_caps::RUMBLE, max_rate_hz: 1000 },
                &self.pair_key.expect("paired"),
                &mut b,
            );
            b
        }

        pub fn on_welcome(&mut self, d: &[u8]) -> bool {
            let key = self.pair_key.expect("paired");
            let Some(w) = codec::decode_welcome(d) else { return false };
            if !codec::verify_mac(&key, d) || w.n_p != self.hello_nonce {
                return false;
            }
            self.session_id = w.session_id;
            self.welcome_host_flags = w.host_flags;
            let (k_ph, k_hp) = crypto::session_keys(&key, &self.hello_nonce, &w.n_h, w.session_id);
            self.tx = Some(SessionCipher::new(&k_ph, wire::DIR_PHONE_TO_HOST));
            self.rx = Some(SessionCipher::new(&k_hp, wire::DIR_HOST_TO_PHONE));
            self.seq = 0;
            self.rx_highest = 0;
            true
        }

        pub fn sealed(&mut self, ptype: u8, plain: &[u8]) -> Vec<u8> {
            let mut b = vec![0u8; wire::HEADER + plain.len() + wire::GCM_TAG];
            self.seq = self.seq.wrapping_add(1);
            self.tx.as_ref().expect("session").seal(ptype, self.session_id, self.seq, plain, &mut b).expect("seal");
            b
        }

        pub fn state(&mut self, change: impl FnOnce(&mut StateFrame)) -> Vec<u8> {
            change(&mut self.frame);
            let mut p = [0u8; wire::STATE_HIRES_PLAIN];
            let n = self.frame.encode(&mut p);
            let t = if self.frame.hires { wire::packet::STATE_HIRES } else { wire::packet::STATE };
            self.sealed(t, &p[..n])
        }

        pub fn ping(&mut self, t1: u64) -> Vec<u8> {
            let mut p = [0u8; wire::PING_PLAIN];
            codec::encode_ping(t1, &mut p);
            self.sealed(wire::packet::PING, &p)
        }

        pub fn bye(&mut self, reason: u8) -> Vec<u8> {
            let mut p = [0u8; wire::BYE_PLAIN];
            codec::encode_byte4(reason, &mut p);
            self.sealed(wire::packet::BYE, &p)
        }

        /// Opens a sealed host to phone packet; returns its type and plaintext.
        pub fn try_open(&mut self, d: &[u8]) -> Option<(u8, Vec<u8>)> {
            let rx = self.rx.as_ref()?;
            if d.len() < wire::HEADER + wire::GCM_TAG || !wire::packet::is_sealed(d[3]) || le::u32(d, 4) != self.session_id {
                return None;
            }
            let mut p = vec![0u8; d.len() - wire::HEADER - wire::GCM_TAG];
            rx.open(d, &mut p)?;
            self.rx_highest = self.rx_highest.max(le::u32(d, 8));
            Some((d[3], p))
        }
    }
}
