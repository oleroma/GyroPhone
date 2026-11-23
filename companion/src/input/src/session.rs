//! Sessions (SPEC 1.6): one pending or active session's keys, seq counters and endpoint
//! (`HostSession`), and the table of pending and active ones (`SessionManager`). Ports of
//! HostSession.cs and SessionManager.cs.

use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::codec::{self, Hello, Welcome};
use crate::crypto::{self, SessionCipher};
use crate::le;
use crate::wire::{self, Id, Nonce};

/// Sends one UDP datagram from the host port. Implemented by the UDP server; faked in tests.
pub trait DatagramSender: Send + Sync {
    fn send(&self, datagram: &[u8], to: SocketAddr);
}

/// Where random bytes come from: the system's generator, or a fixed stream in tests.
pub type RandomFn = Box<dyn FnMut(&mut [u8]) + Send>;

pub fn system_random() -> RandomFn {
    Box::new(crypto::random)
}

/// The sending half of a session: seq counter, cipher and the phone's endpoint. Every host to
/// phone packet goes out under its lock, which covers seq + 1, seal and send, so packets leave in
/// seq order whichever thread sends them.
struct SessionTx {
    cipher: SessionCipher,
    seq: u32,
    endpoint: SocketAddr,
    buf: [u8; wire::MAX_DATAGRAM],
    closed: bool,
}

/// A handle for sending to one session from any thread. Sends nothing once the session is closed.
#[derive(Clone)]
pub struct SessionSender {
    session_id: u32,
    tx: Arc<Mutex<SessionTx>>,
}

impl SessionSender {
    fn lock(&self) -> MutexGuard<'_, SessionTx> {
        self.tx.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn session_id(&self) -> u32 {
        self.session_id
    }

    pub fn endpoint(&self) -> SocketAddr {
        self.lock().endpoint
    }

    pub fn send_sealed(&self, ptype: u8, plain: &[u8], sender: Option<&dyn DatagramSender>) {
        let Some(sender) = sender else { return };
        let mut guard = self.lock();
        let tx = &mut *guard;
        if tx.closed {
            return;
        }
        // The seq must never repeat under one key: a session that somehow sent 2^32 - 1 packets
        // (49 days at 1000 a second) stops sending instead; the phone then reconnects.
        let Some(seq) = tx.seq.checked_add(1) else { return };
        if let Some(n) = tx.cipher.seal(ptype, self.session_id, seq, plain, &mut tx.buf) {
            tx.seq = seq;
            sender.send(&tx.buf[..n], tx.endpoint);
        }
    }

    pub fn send_raw(&self, datagram: &[u8], sender: Option<&dyn DatagramSender>) {
        let Some(sender) = sender else { return };
        let tx = self.lock();
        if !tx.closed {
            sender.send(datagram, tx.endpoint);
        }
    }

    fn close(&self) {
        self.lock().closed = true;
    }
}

/// One pending or active session. The receive state belongs to whoever owns the session (the
/// engine); sends go through `sender()`.
pub struct HostSession {
    pub session_id: u32,
    pub phone_id: Id,
    pub phone_name: String,
    pub n_p: Nonce,
    pub n_h: Nonce,
    /// Cached WELCOME, resent for a retransmitted HELLO with the same nP.
    pub welcome: [u8; wire::WELCOME_SIZE],
    pub created_us: i64,
    pub last_rx_us: i64,
    pub promoted: bool,
    highest_rx_seq: u32,
    rx: SessionCipher,
    /// The key the phone's Diagnostics file is fetched under (`crypto::log_file_key`).
    log_key: [u8; 16],
    /// The receive side's copy of the endpoint, so the unchanged case needs no lock.
    endpoint: SocketAddr,
    tx: SessionSender,
}

impl HostSession {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        session_id: u32,
        phone_id: Id,
        phone_name: String,
        n_p: Nonce,
        n_h: Nonce,
        k_ph: &[u8; 16],
        k_hp: &[u8; 16],
        welcome: [u8; wire::WELCOME_SIZE],
        endpoint: SocketAddr,
        created_us: i64,
    ) -> HostSession {
        HostSession {
            session_id,
            phone_id,
            phone_name,
            n_p,
            n_h,
            welcome,
            created_us,
            last_rx_us: created_us,
            promoted: false,
            highest_rx_seq: 0,
            rx: SessionCipher::new(k_ph, wire::DIR_PHONE_TO_HOST),
            log_key: crypto::log_file_key(k_ph, session_id),
            endpoint,
            tx: SessionSender {
                session_id,
                tx: Arc::new(Mutex::new(SessionTx {
                    cipher: SessionCipher::new(k_hp, wire::DIR_HOST_TO_PHONE),
                    seq: 0,
                    endpoint,
                    buf: [0; wire::MAX_DATAGRAM],
                    closed: false,
                })),
            },
        }
    }

    pub fn highest_rx_seq(&self) -> u32 {
        self.highest_rx_seq
    }

    pub fn endpoint(&self) -> SocketAddr {
        self.endpoint
    }

    pub fn log_key(&self) -> [u8; 16] {
        self.log_key
    }

    /// Whether a datagram came from the session's current endpoint.
    #[inline]
    pub fn is_endpoint(&self, from: SocketAddr) -> bool {
        self.endpoint == from
    }

    /// Follows the source of the latest authenticated packet. The unchanged case takes no lock, so
    /// a send in progress never delays a received packet.
    pub fn update_endpoint(&mut self, from: SocketAddr) {
        if self.endpoint == from {
            return;
        }
        self.endpoint = from;
        self.tx.lock().endpoint = from;
    }

    /// Validation steps 6 and 7: seq > highest (cheap, first), then GCM open; highest is committed
    /// only on success. Returns the plaintext length.
    pub fn try_open(&mut self, packet: &[u8], plain: &mut [u8]) -> Option<usize> {
        if packet.len() < wire::HEADER + wire::GCM_TAG {
            return None;
        }
        let seq = le::u32(packet, 8);
        if seq <= self.highest_rx_seq {
            return None;
        }
        let n = self.rx.open(packet, plain)?;
        self.highest_rx_seq = seq;
        Some(n)
    }

    /// The sending half, for this thread or another.
    pub fn sender(&self) -> &SessionSender {
        &self.tx
    }

    pub fn send_sealed(&self, ptype: u8, plain: &[u8], sender: Option<&dyn DatagramSender>) {
        self.tx.send_sealed(ptype, plain, sender);
    }

    pub fn send_raw(&self, datagram: &[u8], sender: Option<&dyn DatagramSender>) {
        self.tx.send_raw(datagram, sender);
    }

    #[inline]
    pub fn same_phone(&self, phone_id: &[u8]) -> bool {
        self.phone_id[..] == *phone_id
    }
}

impl Drop for HostSession {
    /// A session that is gone sends nothing more, whoever still holds its sender. The keys are
    /// wiped when the last holder lets go.
    fn drop(&mut self) {
        self.tx.close();
    }
}

/// Pending and active sessions (SPEC 1.6). At most one pending session per phone, expiring after
/// PENDING_SESSION_MS; one active session, replaced only by promotion on the first valid STATE.
/// Holds no I/O; the engine performs the side effects.
pub struct SessionManager {
    pending: Vec<HostSession>,
    active: Option<HostSession>,
    random: RandomFn,
    // HELLO nonces already answered (authenticated ones only, so only the phones' own), each with
    // the order it was first seen in. A HELLO carries no freshness, so a replayed one must not
    // replace a newer pending session.
    seen_np: HashMap<u128, u64>,
    seen_order: VecDeque<u128>,
    seen_count: u64,
}

pub const SEEN_NP_MAX: usize = 4096;

fn np_key(n_p: &Nonce) -> u128 {
    u128::from_le_bytes(*n_p)
}

impl SessionManager {
    pub fn new(random: RandomFn) -> SessionManager {
        SessionManager {
            pending: Vec::new(),
            active: None,
            random,
            seen_np: HashMap::with_capacity(SEEN_NP_MAX),
            seen_order: VecDeque::with_capacity(SEEN_NP_MAX),
            seen_count: 0,
        }
    }

    pub fn active(&self) -> Option<&HostSession> {
        self.active.as_ref()
    }

    pub fn active_mut(&mut self) -> Option<&mut HostSession> {
        self.active.as_mut()
    }

    pub fn pending(&self) -> &[HostSession] {
        &self.pending
    }

    pub fn is_active(&self, session_id: u32) -> bool {
        self.active.as_ref().is_some_and(|a| a.session_id == session_id)
    }

    /// Active or pending session with this id.
    pub fn find(&self, session_id: u32) -> Option<&HostSession> {
        match &self.active {
            Some(a) if a.session_id == session_id => Some(a),
            _ => self.pending.iter().find(|p| p.session_id == session_id),
        }
    }

    pub fn find_mut(&mut self, session_id: u32) -> Option<&mut HostSession> {
        match &mut self.active {
            Some(a) if a.session_id == session_id => Some(a),
            _ => self.pending.iter_mut().find(|p| p.session_id == session_id),
        }
    }

    /// The session a retransmitted HELLO (same phone, same nP) belongs to, if any.
    pub fn find_by_hello(&self, phone_id: &Id, n_p: &Nonce) -> Option<&HostSession> {
        self.pending
            .iter()
            .find(|p| p.phone_id == *phone_id && p.n_p == *n_p)
            .or_else(|| self.active.as_ref().filter(|a| a.phone_id == *phone_id && a.n_p == *n_p))
    }

    /// Creates the pending session for a valid HELLO (replacing that phone's previous pending one)
    /// and its WELCOME; returns its session id. None for a HELLO whose nP was first seen before
    /// that of the phone's pending session: a replay (anyone on the network can resend a captured
    /// HELLO) never replaces the phone's newer attempt. A seen nP with nothing newer pending still
    /// gets a session, for a phone whose WELCOME was lost for longer than PENDING_SESSION_MS and
    /// keeps resending the same HELLO.
    #[allow(clippy::too_many_arguments)]
    pub fn create_pending(
        &mut self,
        hello: &Hello,
        phone_name: &str,
        pair_key: &[u8],
        host_id: &Id,
        output_mode: u8,
        host_flags: u8,
        from: SocketAddr,
        now_us: i64,
    ) -> Option<u32> {
        let np = np_key(&hello.n_p);
        if let Some(&order) = self.seen_np.get(&np) {
            let newer_pending = self.pending.iter().any(|p| {
                p.phone_id == hello.phone_id && self.seen_np.get(&np_key(&p.n_p)).is_some_and(|&p_order| p_order > order)
            });
            if newer_pending {
                return None;
            }
        } else {
            if self.seen_order.len() >= SEEN_NP_MAX {
                if let Some(old) = self.seen_order.pop_front() {
                    self.seen_np.remove(&old);
                }
            }
            self.seen_count += 1;
            self.seen_np.insert(np, self.seen_count);
            self.seen_order.push_back(np);
        }
        self.remove_pending(&hello.phone_id);
        let sid = loop {
            let mut b = [0u8; 4];
            (self.random)(&mut b);
            let sid = u32::from_le_bytes(b);
            if sid != 0 && self.find(sid).is_none() {
                break sid;
            }
        };
        let mut n_h: Nonce = [0; wire::NONCE_SIZE];
        (self.random)(&mut n_h);
        let mut welcome = [0u8; wire::WELCOME_SIZE];
        codec::encode_welcome(
            &Welcome {
                session_id: sid,
                host_id: *host_id,
                n_p: hello.n_p,
                n_h,
                output_mode,
                host_flags,
                keepalive_ms: wire::KEEPALIVE_MS,
            },
            pair_key,
            &mut welcome,
        );
        let (k_ph, k_hp) = crypto::session_keys(pair_key, &hello.n_p, &n_h, sid);
        self.pending.push(HostSession::new(
            sid,
            hello.phone_id,
            phone_name.to_owned(),
            hello.n_p,
            n_h,
            &k_ph,
            &k_hp,
            welcome,
            from,
            now_us,
        ));
        Some(sid)
    }

    /// Makes a pending session active. Returns the replaced session, if any, still able to send
    /// (its HOST_BYE) until it is dropped. None also when no such pending session exists, in which
    /// case nothing changes.
    pub fn promote(&mut self, session_id: u32) -> Option<HostSession> {
        let at = self.pending.iter().position(|p| p.session_id == session_id)?;
        let mut s = self.pending.remove(at);
        s.promoted = true;
        self.active.replace(s)
    }

    /// Drops the active session.
    pub fn drop_active(&mut self) {
        self.active = None;
    }

    /// Takes the active session out without closing it yet, so a last packet can still go to it.
    pub fn take_active(&mut self) -> Option<HostSession> {
        self.active.take()
    }

    pub fn remove(&mut self, session_id: u32) {
        if self.is_active(session_id) {
            self.active = None;
        } else {
            self.pending.retain(|p| p.session_id != session_id);
        }
    }

    pub fn remove_pending(&mut self, phone_id: &Id) {
        self.pending.retain(|p| p.phone_id != *phone_id);
    }

    pub fn expire_pending(&mut self, now_us: i64) -> usize {
        let before = self.pending.len();
        self.pending.retain(|p| now_us.saturating_sub(p.created_us) <= wire::PENDING_SESSION_MS * 1000);
        before - self.pending.len()
    }
}
