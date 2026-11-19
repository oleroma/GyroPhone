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

pub mod fakes {
    //! Stand-ins for this PC's own inputs. Ports of the C# tests' FakeKeyListener and FakePcPad.

    use gp_input::codec::PcPress;
    use gp_input::ports::{KeyMouseListener, PcPad, PC_PAD_PLACES};
    use std::sync::{Arc, Mutex};

    #[derive(Default)]
    pub struct KeyListenerState {
        pub held: Vec<PcPress>,
        pub once: Vec<PcPress>,
        pub starts: u32,
        pub stops: u32,
        pub running: bool,
    }

    #[derive(Default)]
    pub struct FakeKeyListener(pub Mutex<KeyListenerState>);

    impl FakeKeyListener {
        pub fn hold(&self, p: PcPress) {
            self.0.lock().unwrap().held.push(p);
        }
        pub fn once(&self, p: PcPress) {
            self.0.lock().unwrap().once.push(p);
        }
        pub fn running(&self) -> bool {
            self.0.lock().unwrap().running
        }
    }

    impl KeyMouseListener for FakeKeyListener {
        fn start(&self) {
            let mut s = self.0.lock().unwrap();
            s.starts += 1;
            s.running = true;
        }
        fn stop(&self) {
            let mut s = self.0.lock().unwrap();
            s.stops += 1;
            s.running = false;
        }
        fn read(&self, into: &mut [PcPress]) -> usize {
            let mut s = self.0.lock().unwrap();
            let mut n = 0;
            for p in s.held.iter().chain(s.once.iter()) {
                into[n] = *p;
                n += 1;
            }
            s.once.clear();
            n
        }
    }

    pub struct PcPadState {
        pub connected: bool,
        pub held: u32,
        pub presses: [u32; PC_PAD_PLACES],
        pub axes: [f32; 6],
        pub running: bool,
    }

    pub struct FakePcPad(pub Mutex<PcPadState>);

    impl Default for FakePcPad {
        fn default() -> Self {
            FakePcPad(Mutex::new(PcPadState {
                connected: true,
                held: 0,
                presses: [0; PC_PAD_PLACES],
                axes: [0.0; 6],
                running: false,
            }))
        }
    }

    impl FakePcPad {
        pub fn set(&self, f: impl FnOnce(&mut PcPadState)) {
            f(&mut self.0.lock().unwrap())
        }
        pub fn running(&self) -> bool {
            self.0.lock().unwrap().running
        }
    }

    impl PcPad for FakePcPad {
        fn start(&self, _own_slot: Arc<dyn Fn() -> i32 + Send + Sync>) {
            self.0.lock().unwrap().running = true;
        }
        fn stop(&self) {
            self.0.lock().unwrap().running = false;
        }
        fn read(&self, presses: &mut [u32; PC_PAD_PLACES]) -> Option<u32> {
            let s = self.0.lock().unwrap();
            *presses = s.presses;
            s.connected.then_some(s.held)
        }
        fn read_axes(&self, axes: &mut [f32; 6]) -> bool {
            let s = self.0.lock().unwrap();
            *axes = s.axes;
            s.connected
        }
        fn peek(&self, axes: &mut [f32; 6]) -> Option<u32> {
            let s = self.0.lock().unwrap();
            *axes = s.axes;
            s.connected.then_some(s.held)
        }
        fn held_at(&self, _us: i64) -> u32 {
            self.0.lock().unwrap().held
        }
        fn name(&self) -> Option<String> {
            let s = self.0.lock().unwrap();
            s.connected.then(|| "Controller 1".to_string())
        }
    }
}

pub mod harness {
    //! The engine with every port faked: a port of the C# tests' Harness and its fakes.

    use std::collections::HashSet;
    use std::net::SocketAddr;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::{Arc, Mutex};

    use gp_input::crypto::P256;
    use gp_input::engine::{EngineHooks, EngineOptions, HostEngine, PhoneSecond, TraceKind};
    use gp_input::mapping::{self, Ds4Frame, InjectEvent, InjectKind, X360Frame};
    use gp_input::pairing::KeyFn;
    use gp_input::ports::{Clock, HostEvents, KeyMouseSink, KeyProtector, PadSink, PairPrompt, SinkError};
    use gp_input::recorder::RecorderOut;
    use gp_input::reports::Ds4Motion;
    use gp_input::session::{DatagramSender, RandomFn};
    use gp_input::settings::{HostSettings, PairedPhone, NEVER_UTC};
    use gp_input::shared::SharedRegion;
    use gp_input::wire::{self, output_mode, Id};

    use super::phone::FakePhone;

    pub struct FakeClock(pub AtomicI64);

    impl FakeClock {
        pub fn now(&self) -> i64 {
            self.0.load(Ordering::SeqCst)
        }
        pub fn advance_ms(&self, ms: i64) {
            self.0.fetch_add(ms * 1000, Ordering::SeqCst);
        }
        pub fn set(&self, us: i64) {
            self.0.store(us, Ordering::SeqCst);
        }
        pub fn advance_us(&self, us: i64) {
            self.0.fetch_add(us, Ordering::SeqCst);
        }
    }

    impl Clock for FakeClock {
        fn now_us(&self) -> i64 {
            self.now()
        }
    }

    #[derive(Default)]
    pub struct FakeSender(pub Mutex<Vec<(Vec<u8>, SocketAddr)>>);

    impl FakeSender {
        pub fn sent(&self) -> Vec<(Vec<u8>, SocketAddr)> {
            self.0.lock().unwrap().clone()
        }
        pub fn count(&self) -> usize {
            self.0.lock().unwrap().len()
        }
        pub fn to(&self, a: SocketAddr) -> Vec<Vec<u8>> {
            self.0.lock().unwrap().iter().filter(|s| s.1 == a).map(|s| s.0.clone()).collect()
        }
        pub fn last(&self) -> Option<Vec<u8>> {
            self.0.lock().unwrap().last().map(|s| s.0.clone())
        }
        pub fn at(&self, i: usize) -> Vec<u8> {
            self.0.lock().unwrap()[i].0.clone()
        }
        pub fn clear(&self) {
            self.0.lock().unwrap().clear();
        }
    }

    impl DatagramSender for FakeSender {
        fn send(&self, datagram: &[u8], to: SocketAddr) {
            self.0.lock().unwrap().push((datagram.to_vec(), to));
        }
    }

    pub struct PadState {
        pub driver_ok: bool,
        pub dual_sense_ok: bool,
        pub plugged: u8,
        pub x360: Vec<X360Frame>,
        pub ds4: Vec<Ds4Frame>,
        pub ds4_motion: Vec<Ds4Motion>,
        pub plugs: u32,
        pub unplugs: u32,
        pub plug_fails: bool,
        pub fail_with: Option<SinkError>,
        pub lose_on_fail: bool,
        pub handler: Option<Arc<dyn Fn(u8, u8) + Send + Sync>>,
    }

    pub struct FakePad(pub Mutex<PadState>, pub Gate);

    /// Closed, it holds every `plug` at its start (the driver taking its time) until it is opened.
    #[derive(Default)]
    pub struct Gate {
        closed: Mutex<bool>,
        opened: std::sync::Condvar,
        waiting: AtomicI64,
    }

    impl Gate {
        pub fn close(&self) {
            *self.closed.lock().unwrap() = true;
        }
        pub fn open(&self) {
            *self.closed.lock().unwrap() = false;
            self.opened.notify_all();
        }
        /// How many callers are held at the gate now.
        pub fn waiting(&self) -> i64 {
            self.waiting.load(Ordering::SeqCst)
        }
        fn pass(&self) {
            let mut closed = self.closed.lock().unwrap();
            if !*closed {
                return;
            }
            self.waiting.fetch_add(1, Ordering::SeqCst);
            while *closed {
                closed = self.opened.wait(closed).unwrap();
            }
            self.waiting.fetch_sub(1, Ordering::SeqCst);
        }
    }

    impl Default for FakePad {
        fn default() -> Self {
            FakePad::new()
        }
    }

    impl FakePad {
        pub fn new() -> FakePad {
            FakePad(Mutex::new(PadState {
                driver_ok: true,
                dual_sense_ok: true,
                plugged: output_mode::NONE,
                x360: Vec::new(),
                ds4: Vec::new(),
                ds4_motion: Vec::new(),
                plugs: 0,
                unplugs: 0,
                plug_fails: false,
                fail_with: None,
                lose_on_fail: false,
                handler: None,
            }), Gate::default())
        }
    }

    impl FakePad {
        pub fn set(&self, f: impl FnOnce(&mut PadState)) {
            f(&mut self.0.lock().unwrap())
        }
        pub fn plugged_now(&self) -> u8 {
            self.0.lock().unwrap().plugged
        }
        pub fn x360(&self) -> Vec<X360Frame> {
            self.0.lock().unwrap().x360.clone()
        }
        pub fn ds4(&self) -> Vec<Ds4Frame> {
            self.0.lock().unwrap().ds4.clone()
        }
        pub fn ds4_motion(&self) -> Vec<Ds4Motion> {
            self.0.lock().unwrap().ds4_motion.clone()
        }
        pub fn plugs(&self) -> u32 {
            self.0.lock().unwrap().plugs
        }
        pub fn unplugs(&self) -> u32 {
            self.0.lock().unwrap().unplugs
        }
        pub fn raise_rumble(&self, large: u8, small: u8) {
            let h = self.0.lock().unwrap().handler.clone();
            if let Some(h) = h {
                h(large, small);
            }
        }
        fn fail(s: &mut PadState) -> Result<(), SinkError> {
            match s.fail_with {
                None => Ok(()),
                Some(e) => {
                    if s.lose_on_fail {
                        s.plugged = output_mode::NONE;
                    }
                    Err(e)
                }
            }
        }
    }

    impl PadSink for FakePad {
        fn driver_ok(&self) -> bool {
            self.0.lock().unwrap().driver_ok
        }
        fn dual_sense_ok(&self) -> bool {
            self.0.lock().unwrap().dual_sense_ok
        }
        fn plugged(&self) -> u8 {
            self.0.lock().unwrap().plugged
        }
        fn plug(&self, mode: u8) -> Result<bool, SinkError> {
            self.1.pass();
            let mut s = self.0.lock().unwrap();
            s.plugs += 1;
            if s.plug_fails {
                s.plugged = output_mode::NONE;
                return Ok(false);
            }
            s.plugged = mode;
            Ok(true)
        }
        fn unplug(&self) -> Result<(), SinkError> {
            let mut s = self.0.lock().unwrap();
            if s.plugged != output_mode::NONE {
                s.unplugs += 1;
            }
            s.plugged = output_mode::NONE;
            Ok(())
        }
        fn submit_x360(&self, frame: &X360Frame) -> Result<(), SinkError> {
            let mut s = self.0.lock().unwrap();
            Self::fail(&mut s)?;
            s.x360.push(*frame);
            Ok(())
        }
        fn submit_ds4(&self, frame: &Ds4Frame, motion: &Ds4Motion) -> Result<(), SinkError> {
            let mut s = self.0.lock().unwrap();
            Self::fail(&mut s)?;
            s.ds4.push(*frame);
            s.ds4_motion.push(*motion);
            Ok(())
        }
        fn set_rumble_handler(&self, handler: Option<Arc<dyn Fn(u8, u8) + Send + Sync>>) {
            self.0.lock().unwrap().handler = handler;
        }
    }

    #[derive(Default)]
    pub struct KeyMouseState {
        pub calls: Vec<Vec<InjectEvent>>,
        pub held_keys: HashSet<u16>,
        pub held_buttons: HashSet<String>,
        pub mouse_x: i64,
        pub mouse_y: i64,
        pub wheel: i64,
        pub hwheel: i64,
        pub dropping: bool,
    }

    #[derive(Default)]
    pub struct FakeKeyMouse {
        pub s: Mutex<KeyMouseState>,
        pub on_inject: Mutex<Option<Box<dyn Fn() + Send + Sync>>>,
    }

    impl FakeKeyMouse {
        pub fn calls(&self) -> usize {
            self.s.lock().unwrap().calls.len()
        }
        pub fn all(&self) -> Vec<InjectEvent> {
            self.s.lock().unwrap().calls.iter().flatten().copied().collect()
        }
        pub fn call(&self, i: usize) -> Vec<InjectEvent> {
            self.s.lock().unwrap().calls[i].clone()
        }
        pub fn holds_key(&self, id: u16) -> bool {
            self.s.lock().unwrap().held_keys.contains(&id)
        }
        pub fn holds_button(&self, name: &str) -> bool {
            self.s.lock().unwrap().held_buttons.contains(name)
        }
        pub fn nothing_held(&self) -> bool {
            let s = self.s.lock().unwrap();
            s.held_keys.is_empty() && s.held_buttons.is_empty()
        }
        pub fn mouse_x(&self) -> i64 {
            self.s.lock().unwrap().mouse_x
        }
        pub fn mouse_y(&self) -> i64 {
            self.s.lock().unwrap().mouse_y
        }
        pub fn wheel(&self) -> i64 {
            self.s.lock().unwrap().wheel
        }
        pub fn hwheel(&self) -> i64 {
            self.s.lock().unwrap().hwheel
        }
        pub fn set_dropping(&self, on: bool) {
            self.s.lock().unwrap().dropping = on;
        }
        fn button(s: &mut KeyMouseState, e: &InjectEvent, down: u32, up: u32, name: String) {
            if e.flags & down != 0 {
                s.held_buttons.insert(name.clone());
            }
            if e.flags & up != 0 {
                s.held_buttons.remove(&name);
            }
        }
    }

    impl KeyMouseSink for FakeKeyMouse {
        fn inject(&self, events: &[InjectEvent]) -> bool {
            if let Some(f) = &*self.on_inject.lock().unwrap() {
                f();
            }
            let mut s = self.s.lock().unwrap();
            s.calls.push(events.to_vec());
            if s.dropping {
                return false;
            }
            for e in events {
                if e.kind == InjectKind::Key {
                    let id = if e.vk != 0 { 0x8000 | e.vk } else { e.scan | if e.flags & mapping::KEY_EXTENDED != 0 { 0x100 } else { 0 } };
                    if e.is_key_up() {
                        s.held_keys.remove(&id);
                    } else {
                        s.held_keys.insert(id);
                    }
                    continue;
                }
                if e.flags & mapping::MOUSE_MOVE != 0 {
                    s.mouse_x += e.dx as i64;
                    s.mouse_y += e.dy as i64;
                }
                if e.flags & mapping::MOUSE_WHEEL != 0 {
                    s.wheel += e.mouse_data as i64;
                }
                if e.flags & mapping::MOUSE_HWHEEL != 0 {
                    s.hwheel += e.mouse_data as i64;
                }
                Self::button(&mut s, e, mapping::MOUSE_LEFT_DOWN, mapping::MOUSE_LEFT_UP, "L".into());
                Self::button(&mut s, e, mapping::MOUSE_RIGHT_DOWN, mapping::MOUSE_RIGHT_UP, "R".into());
                Self::button(&mut s, e, mapping::MOUSE_MIDDLE_DOWN, mapping::MOUSE_MIDDLE_UP, "M".into());
                Self::button(&mut s, e, mapping::MOUSE_X_DOWN, mapping::MOUSE_X_UP, format!("X{}", e.mouse_data));
            }
            true
        }
    }

    #[derive(Default)]
    pub struct FakePrompt {
        pub shown: Mutex<Vec<(u32, String, String)>>,
        pub closed: Mutex<Vec<u32>>,
    }

    impl PairPrompt for FakePrompt {
        fn show(&self, attempt_id: u32, phone_name: &str, sas_display: &str) {
            self.shown.lock().unwrap().push((attempt_id, phone_name.to_owned(), sas_display.to_owned()));
        }
        fn close(&self, attempt_id: u32) {
            self.closed.lock().unwrap().push(attempt_id);
        }
    }

    pub struct PlainProtector;

    impl KeyProtector for PlainProtector {
        fn protect(&self, plain: &[u8]) -> Vec<u8> {
            plain.to_vec()
        }
        fn unprotect(&self, blob: &[u8]) -> Option<Vec<u8>> {
            Some(blob.to_vec())
        }
    }

    #[derive(Default)]
    pub struct RecordingEvents {
        pub logs: Mutex<Vec<String>>,
        pub changes: AtomicI64,
    }

    impl RecordingEvents {
        pub fn logs(&self) -> Vec<String> {
            self.logs.lock().unwrap().clone()
        }
        pub fn changes(&self) -> i64 {
            self.changes.load(Ordering::SeqCst)
        }
        pub fn logged(&self, starts_with: &str) -> usize {
            self.logs.lock().unwrap().iter().filter(|l| l.starts_with(starts_with)).count()
        }
    }

    impl HostEvents for RecordingEvents {
        fn state_changed(&self) {
            self.changes.fetch_add(1, Ordering::SeqCst);
        }
        fn log(&self, message: &str) {
            self.logs.lock().unwrap().push(message.to_owned());
        }
    }

    /// Keeps what the engine hands out through its hooks.
    #[derive(Default)]
    pub struct RecordingHooks {
        pub lines: Mutex<Vec<(i64, TraceKind, String)>>,
        pub recorder: Mutex<Vec<RecorderOut>>,
        pub wakes: AtomicI64,
        pub saves: AtomicI64,
        pub diagnostics: Mutex<Vec<(bool, std::thread::ThreadId)>>,
        pub phone_seconds: Mutex<Vec<PhoneSecond>>,
        pub moves: Mutex<Vec<(i64, i32, i32)>>,
        /// Run in `recorder`, before its lines are kept.
        pub on_recorder: Mutex<Option<Box<dyn Fn() + Send + Sync>>>,
    }

    impl RecordingHooks {
        pub fn recorder_lines(&self) -> Vec<String> {
            self.recorder.lock().unwrap().iter().filter_map(|o| if let RecorderOut::Line(l) = o { Some(l.clone()) } else { None }).collect()
        }
        pub fn debug_lines(&self) -> Vec<(TraceKind, String)> {
            self.lines.lock().unwrap().iter().map(|l| (l.1, l.2.clone())).collect()
        }
        pub fn saves(&self) -> i64 {
            self.saves.load(Ordering::SeqCst)
        }
        pub fn wakes(&self) -> i64 {
            self.wakes.load(Ordering::SeqCst)
        }
    }

    impl EngineHooks for RecordingHooks {
        fn debug_line(&self, us: i64, kind: TraceKind, text: &str) {
            self.lines.lock().unwrap().push((us, kind, text.to_owned()));
        }
        fn recorder(&self, out: &[RecorderOut]) {
            if let Some(f) = &*self.on_recorder.lock().unwrap() {
                f();
            }
            self.recorder.lock().unwrap().extend(out.iter().cloned());
        }
        fn overlay_wake(&self) {
            self.wakes.fetch_add(1, Ordering::SeqCst);
        }
        fn debug_save_requested(&self) {
            self.saves.fetch_add(1, Ordering::SeqCst);
        }
        fn diagnostics_changed(&self, on: bool) {
            self.diagnostics.lock().unwrap().push((on, std::thread::current().id()));
        }
        fn phone_second(&self, s: &PhoneSecond) {
            self.phone_seconds.lock().unwrap().push(*s);
        }
        fn move_sent(&self, t0_us: i64, dx: i32, dy: i32) {
            self.moves.lock().unwrap().push((t0_us, dx, dy));
        }
    }

    pub const PHONE1_ID: Id = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

    pub fn phone1_key() -> [u8; 32] {
        super::range_n(0x40)
    }

    pub fn paired(id: &[u8], name: &str, key: &[u8]) -> PairedPhone {
        PairedPhone {
            phone_id: id.to_vec(),
            name: name.to_owned(),
            protected_key: key.to_vec(),
            paired_utc: NEVER_UTC.to_owned(),
            last_seen_utc: NEVER_UTC.to_owned(),
        }
    }

    /// What a harness is made with; `Default` is the C# Harness's defaults.
    pub struct Setup {
        pub pair_default: bool,
        pub mode: u8,
        pub host_key: Option<P256>,
        pub session_random: Option<RandomFn>,
        pub pairing_random: Option<RandomFn>,
        pub new_pair_key: Option<KeyFn>,
        pub inline_workers: bool,
        pub settings: Option<Box<dyn FnOnce(&mut HostSettings)>>,
        pub shared: Option<SharedRegion>,
    }

    impl Default for Setup {
        fn default() -> Self {
            Setup {
                pair_default: true,
                mode: output_mode::XBOX360,
                host_key: None,
                session_random: None,
                pairing_random: None,
                new_pair_key: None,
                inline_workers: true,
                settings: None,
                shared: None,
            }
        }
    }

    pub struct Harness {
        pub clock: Arc<FakeClock>,
        pub sender: Arc<FakeSender>,
        pub pad: Arc<FakePad>,
        pub key_mouse: Arc<FakeKeyMouse>,
        pub prompt: Arc<FakePrompt>,
        pub events: Arc<RecordingEvents>,
        pub hooks: Arc<RecordingHooks>,
        pub engine: Arc<HostEngine>,
    }

    impl Default for Harness {
        fn default() -> Self {
            Harness::new()
        }
    }

    impl Harness {
        pub fn new() -> Harness {
            Harness::with(Setup::default())
        }

        pub fn mode(mode: u8) -> Harness {
            Harness::with(Setup { mode, ..Setup::default() })
        }

        pub fn with(setup: Setup) -> Harness {
            let mut settings = HostSettings::create_default("DESKTOP-OLEX");
            settings.host_id = super::range_n(0x10);
            settings.output_mode = setup.mode;
            if setup.pair_default {
                settings.paired_phones.push(paired(&PHONE1_ID, "OnePlus 15", &phone1_key()));
            }
            if let Some(f) = setup.settings {
                f(&mut settings);
            }
            let clock = Arc::new(FakeClock(AtomicI64::new(1_000_000_000)));
            let sender = Arc::new(FakeSender::default());
            let pad = Arc::new(FakePad::default());
            let key_mouse = Arc::new(FakeKeyMouse::default());
            let prompt = Arc::new(FakePrompt::default());
            let events = Arc::new(RecordingEvents::default());
            let hooks = Arc::new(RecordingHooks::default());
            let engine = HostEngine::new(
                settings,
                pad.clone(),
                key_mouse.clone(),
                Arc::new(PlainProtector),
                EngineOptions {
                    clock: clock.clone(),
                    events: events.clone(),
                    prompt: prompt.clone(),
                    hooks: hooks.clone(),
                    session_random: setup.session_random,
                    pairing_random: setup.pairing_random,
                    new_pair_key: setup.new_pair_key,
                    host_key: setup.host_key,
                    shared: setup.shared,
                    inline_workers: setup.inline_workers,
                },
            );
            engine.attach_sender(sender.clone());
            Harness { clock, sender, pad, key_mouse, prompt, events, hooks, engine }
        }

        pub fn now(&self) -> i64 {
            self.clock.now()
        }

        pub fn new_phone(&self) -> FakePhone {
            self.phone_at("192.168.1.50:40000")
        }

        pub fn phone_at(&self, addr: &str) -> FakePhone {
            FakePhone::paired(PHONE1_ID, "OnePlus 15", phone1_key(), addr)
        }

        /// One datagram and the flush after it; returns the first thing sent because of it.
        pub fn send(&self, phone: &FakePhone, d: &[u8]) -> Option<Vec<u8>> {
            let before = self.sender.count();
            self.engine.on_datagram(d, phone.addr, self.now());
            self.engine.flush(self.now());
            if self.sender.count() > before {
                Some(self.sender.at(before))
            } else {
                None
            }
        }

        /// One datagram of a burst: no flush yet.
        pub fn burst(&self, phone: &FakePhone, d: &[u8]) {
            self.engine.on_datagram(d, phone.addr, self.now());
        }

        pub fn flush(&self) {
            self.engine.flush(self.now());
        }

        pub fn connect(&self, phone: &mut FakePhone) {
            let hello = phone.hello(None);
            let w = self.send(phone, &hello).expect("welcome");
            assert!(phone.on_welcome(&w));
            let s = phone.state(|_| {});
            self.send(phone, &s);
        }

        /// Waits (up to 5 s) for something another thread does.
        pub fn until(&self, what: &str, done: impl Fn() -> bool) {
            let start = std::time::Instant::now();
            while !done() {
                assert!(start.elapsed().as_secs() < 5, "never happened: {what}");
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }

        pub fn tick(&self, advance_ms: i64) {
            self.clock.advance_ms(advance_ms);
            self.engine.on_tick(self.now());
        }

        /// A sealed packet from the phone, handled and flushed.
        pub fn sealed(&self, phone: &mut FakePhone, ptype: u8, plain: &[u8]) -> Option<Vec<u8>> {
            let d = phone.sealed(ptype, plain);
            self.send(phone, &d)
        }

        /// The packets of one type the phone can open among everything sent, as plaintexts.
        pub fn opened(&self, phone: &mut FakePhone, ptype: u8) -> Vec<Vec<u8>> {
            let mut out = Vec::new();
            for (d, _) in self.sender.sent() {
                if let Some((t, p)) = phone.try_open(&d) {
                    if t == ptype {
                        out.push(p);
                    }
                }
            }
            out
        }
    }

    /// True when `f` ends within `ms` on a thread of its own.
    pub fn finishes(ms: u64, f: impl FnOnce() + Send + 'static) -> bool {
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            f();
            let _ = tx.send(());
        });
        rx.recv_timeout(std::time::Duration::from_millis(ms)).is_ok()
    }

    pub const _USES: usize = wire::HEADER;
}
