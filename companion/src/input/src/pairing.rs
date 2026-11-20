//! Host side of commit/reveal pairing with an explicit Allow on the PC (SPEC 1.5). One attempt at a
//! time (a new start from the same phone replaces its own), only while the pairing window is open.
//! Every message is idempotent per attemptId: a retransmit gets the cached reply. Replies are
//! written into the caller's buffer; the return value is the reply length (0 = stay silent).
//! Port of PairingManager.cs.
//!
//! What the C# does through callbacks (show or close the prompt, store a new pairing) comes out
//! here as `PairEvent`s, in the same order, for the engine to act on after each call.

use zeroize::Zeroizing;

use crate::codec::{self, PairAck, PairConfirm, PairDone, PairHost, PairReveal, PairStart};
use crate::crypto::{self, pairing_math, P256};
use crate::rate_limiter::RateLimiter;
use crate::session::RandomFn;
use crate::wire::{self, pair_ack, pair_done, Hash, Id, Nonce, PublicKey};

const WINDOW_US: i64 = wire::PAIRING_WINDOW_S * 1_000_000;
const COOLDOWN_US: i64 = wire::PAIR_COOLDOWN_MS * 1000;
const USER_TIMEOUT_US: i64 = wire::PAIR_USER_TIMEOUT_S * 1_000_000;
const ATTEMPT_TIMEOUT_US: i64 = wire::PAIRING_WINDOW_S * 1_000_000;
/// The phone reveals as soon as PAIR_HOST arrives (and resends every PAIR_RETX_MS), so an attempt
/// still unrevealed after this is stale: a lost phone, or a LAN peer holding the slot. The next
/// start replaces it.
pub const REVEAL_TIMEOUT_US: i64 = 2_000_000;

pub type KeyFn = Box<dyn FnMut() -> P256 + Send>;

/// What the engine must do after a call, in order.
pub enum PairEvent {
    /// Ask the user to allow this pairing. The answer comes back through `answer`.
    Show { attempt_id: u32, phone_name: String, sas: String },
    /// The attempt ended (answered, timed out, failed or cancelled): close any open prompt.
    Close { attempt_id: u32 },
    /// A pairing was accepted: store it.
    Paired { phone_id: Id, phone_name: String, pair_key: Zeroizing<[u8; 32]> },
}

struct Attempt {
    attempt_id: u32,
    phone_id: Id,
    phone_name: String,
    pk_p: PublicKey,
    commit: Hash,
    host_id: Id,
    key: Option<P256>,
    pk_h: PublicKey,
    n_h: Nonce,
    host_reply: [u8; wire::PAIR_HOST_SIZE],
    started_us: i64,
    /// When an attempt still unrevealed by then goes stale (REVEAL_TIMEOUT_US after its phone's
    /// first start).
    reveal_by_us: i64,
    revealed: bool,
    th: Hash,
    pair_key: Zeroizing<[u8; 32]>,
    sas_display: String,
    prompt_deadline_us: i64,
    user_answer: Option<bool>,
    confirm_verified: bool,
}

impl Attempt {
    fn same_start(&self, m: &PairStart) -> bool {
        self.phone_id == m.phone_id && self.commit == m.commit
    }
}

struct Finished {
    attempt_id: u32,
    phone_id: Id,
    ack: Option<[u8; wire::PAIR_ACK_SIZE]>,
    done: Option<[u8; wire::PAIR_DONE_SIZE]>,
}

pub struct PairingManager {
    /// Each new attempt costs a P-256 key generation: a few per second per address at most.
    new_attempts: RateLimiter,
    random: RandomFn,
    new_key: KeyFn,
    window_until_us: Option<i64>,
    cooldown_until_us: Option<i64>,
    failures: u32,
    attempt: Option<Attempt>,
    finished: Option<Finished>,
    events: Vec<PairEvent>,
}

impl PairingManager {
    pub fn new(random: RandomFn, new_key: KeyFn) -> PairingManager {
        PairingManager {
            new_attempts: RateLimiter::new(2, 8),
            random,
            new_key,
            window_until_us: None,
            cooldown_until_us: None,
            failures: 0,
            attempt: None,
            finished: None,
            events: Vec::new(),
        }
    }

    /// The events of the calls since the last drain, oldest first.
    pub fn drain_events(&mut self) -> std::vec::Drain<'_, PairEvent> {
        self.events.drain(..)
    }

    pub fn failures(&self) -> u32 {
        self.failures
    }

    pub fn is_open(&self, now_us: i64) -> bool {
        self.window_until_us.is_some_and(|until| now_us < until)
    }

    /// The window is open or an attempt runs: its timers want the watchdog's every tick.
    pub fn busy(&self, now_us: i64) -> bool {
        self.attempt.is_some() || self.is_open(now_us)
    }

    /// Seconds left in the pairing window (0 when closed).
    pub fn window_remaining_s(&self, now_us: i64) -> i32 {
        match self.window_until_us {
            Some(until) if now_us < until => ((until - now_us + 999_999) / 1_000_000) as i32,
            _ => 0,
        }
    }

    /// The attempt currently waiting for the user, if any: (attemptId, phone name, SAS display).
    pub fn prompt_info(&self) -> Option<(u32, &str, &str)> {
        match &self.attempt {
            Some(a) if a.revealed && a.user_answer.is_none() => Some((a.attempt_id, &a.phone_name, &a.sas_display)),
            _ => None,
        }
    }

    /// "Pair new phone…": opens the window for PAIRING_WINDOW_S and resets the failure count.
    pub fn open(&mut self, now_us: i64) {
        self.window_until_us = Some(now_us.saturating_add(WINDOW_US));
        self.failures = 0;
        self.cooldown_until_us = None;
    }

    pub fn close(&mut self) {
        self.window_until_us = None;
        if self.attempt.is_some() {
            self.end(0, false, None, None);
        }
    }

    // ---- PAIR_START ----

    /// `from_ip`: the sender's address key (`rate_limiter::ipv4_key`), for the new-attempt rate limit.
    pub fn on_start(&mut self, m: &PairStart, host_id: &Id, host_name: &str, now_us: i64, reply: &mut [u8], from_ip: u32) -> usize {
        if self.attempt.as_ref().is_some_and(|a| !a.revealed && now_us >= a.reveal_by_us) {
            self.end(now_us, false, None, None);
        }
        let mut charged = false;
        let mut reveal_by_us = now_us.saturating_add(REVEAL_TIMEOUT_US);
        if let Some(a) = &self.attempt {
            if a.same_start(m) {
                return copy(&a.host_reply, reply);
            }
            if a.phone_id != m.phone_id {
                // A fresh unrevealed attempt ends in a moment either way: stay silent so that phone
                // resends instead of giving up on BUSY.
                return if a.revealed { ack(0, pair_ack::BUSY, reply) } else { 0 };
            }
            if !P256::is_valid_public(&m.pk_p) {
                return 0;
            }
            let (revealed, old_reveal_by) = (a.revealed, a.reveal_by_us);
            if !self.new_attempts.try_acquire(from_ip, now_us) {
                return 0; // the phone resends
            }
            charged = true;
            // The same phone started over (cancelled, or its app restarted): the new attempt replaces
            // its old one. The phone id travels in clear, so replacing a revealed attempt counts
            // toward the failure limit (without a cooldown): a LAN peer cannot keep swapping the
            // code on the PC.
            if revealed {
                self.failures += 1;
                if self.failures >= wire::PAIR_MAX_FAILS {
                    self.window_until_us = None;
                }
            } else {
                // Replacing an unrevealed attempt keeps its reveal deadline, so a LAN peer that keeps
                // restarting with new commits cannot hold the PC past it while another phone waits.
                reveal_by_us = old_reveal_by;
            }
            self.end(now_us, false, None, None);
        }
        if !self.is_open(now_us) {
            return ack(0, pair_ack::CLOSED, reply);
        }
        if self.cooldown_until_us.is_some_and(|until| now_us < until) {
            return ack(0, pair_ack::COOLDOWN, reply);
        }
        if !P256::is_valid_public(&m.pk_p) {
            return 0;
        }
        if !charged && !self.new_attempts.try_acquire(from_ip, now_us) {
            return 0; // the phone resends
        }

        let finished_id = self.finished.as_ref().map(|f| f.attempt_id);
        let id = loop {
            let mut b = [0u8; 4];
            (self.random)(&mut b);
            let id = u32::from_le_bytes(b);
            if id != 0 && Some(id) != finished_id {
                break id;
            }
        };
        let key = (self.new_key)();
        let pk_h = *key.public_key();
        let mut n_h: Nonce = [0; wire::NONCE_SIZE];
        (self.random)(&mut n_h);
        let mut host_reply = [0u8; wire::PAIR_HOST_SIZE];
        codec::encode_pair_host(
            &PairHost { attempt_id: id, host_id: *host_id, pk_h, n_h, name: host_name.to_owned() },
            &mut host_reply,
        );
        self.attempt = Some(Attempt {
            attempt_id: id,
            phone_id: m.phone_id,
            phone_name: m.name.clone(),
            pk_p: m.pk_p,
            commit: m.commit,
            host_id: *host_id,
            key: Some(key),
            pk_h,
            n_h,
            host_reply,
            started_us: now_us,
            reveal_by_us,
            revealed: false,
            th: [0; wire::HASH_SIZE],
            pair_key: Zeroizing::new([0; 32]),
            sas_display: String::new(),
            prompt_deadline_us: 0,
            user_answer: None,
            confirm_verified: false,
        });
        self.finished = None;
        copy(&host_reply, reply)
    }

    // ---- PAIR_REVEAL ----

    pub fn on_reveal(&mut self, m: &PairReveal, now_us: i64, reply: &mut [u8]) -> usize {
        let Some(a) = self.attempt.as_mut().filter(|a| a.attempt_id == m.attempt_id && a.phone_id == m.phone_id) else {
            if let Some(f) = &self.finished {
                if f.attempt_id == m.attempt_id {
                    if let Some(cached) = &f.ack {
                        return copy(cached, reply);
                    }
                }
            }
            return ack(m.attempt_id, pair_ack::UNKNOWN_ATTEMPT, reply);
        };
        if a.revealed {
            return ack(a.attempt_id, pair_ack::OK, reply);
        }

        let expect = pairing_math::commit(&a.pk_p, &m.n_p);
        if !crypto::ct_eq(&expect, &a.commit) {
            let mut cached = [0u8; wire::PAIR_ACK_SIZE];
            codec::encode_pair_ack(PairAck { attempt_id: a.attempt_id, status: pair_ack::BAD_COMMIT }, &mut cached);
            self.fail(now_us);
            self.end(now_us, false, Some(cached), None);
            return copy(&cached, reply);
        }

        let z = a.key.take().and_then(|key| key.ecdh(&a.pk_p));
        let Some(z) = z else {
            self.end(now_us, true, None, None);
            return 0;
        };
        a.th = pairing_math::th(&a.phone_id, &a.host_id, &a.pk_p, &a.pk_h, &m.n_p, &a.n_h, a.attempt_id);
        a.sas_display = pairing_math::sas_display(pairing_math::sas(&a.th));
        a.pair_key = pairing_math::pair_key(&z[..], &a.th);
        a.revealed = true;
        a.prompt_deadline_us = now_us.saturating_add(USER_TIMEOUT_US);
        let id = a.attempt_id;
        self.events.push(PairEvent::Show { attempt_id: id, phone_name: a.phone_name.clone(), sas: a.sas_display.clone() });
        ack(id, pair_ack::OK, reply)
    }

    // ---- PAIR_CONFIRM ----

    pub fn on_confirm(&mut self, m: &PairConfirm, now_us: i64, reply: &mut [u8]) -> usize {
        let Some(a) = self
            .attempt
            .as_mut()
            .filter(|a| a.attempt_id == m.attempt_id && a.phone_id == m.phone_id && a.revealed)
        else {
            if let Some(f) = &self.finished {
                if f.attempt_id == m.attempt_id && f.phone_id == m.phone_id {
                    if let Some(cached) = &f.done {
                        return copy(cached, reply);
                    }
                }
            }
            return ack(m.attempt_id, pair_ack::UNKNOWN_ATTEMPT, reply);
        };

        let expect = pairing_math::confirm_p(&a.pair_key[..], &a.th);
        if !crypto::ct_eq(&expect, &m.confirm_p) {
            self.fail(now_us);
            let d = self.finish(pair_done::BAD_CONFIRM, now_us, false, None);
            return copy(&d, reply);
        }
        a.confirm_verified = true;
        match a.user_answer {
            None => done(a.attempt_id, pair_done::PENDING, None, reply),
            Some(true) => {
                let d = self.accept(now_us);
                copy(&d, reply)
            }
            Some(false) => {
                let d = self.finish(pair_done::DENIED, now_us, true, None);
                copy(&d, reply)
            }
        }
    }

    // ---- user answer and timers ----

    /// The PC user's Allow/Deny. Ignored for a stale attemptId. Returns true when the pairing was
    /// stored (a `Paired` event is waiting).
    pub fn answer(&mut self, attempt_id: u32, allow: bool, now_us: i64) -> bool {
        let Some(a) = self
            .attempt
            .as_mut()
            .filter(|a| a.attempt_id == attempt_id && a.revealed && a.user_answer.is_none())
        else {
            return false;
        };
        a.user_answer = Some(allow);
        if !allow {
            self.finish(pair_done::DENIED, now_us, true, None);
            return false;
        }
        // Allow before CONFIRM: wait for a valid confirmP. Allow after it: store now.
        if a.confirm_verified {
            self.accept(now_us);
            return true;
        }
        false
    }

    /// Prompt and attempt timeouts. Returns true when something changed.
    pub fn tick(&mut self, now_us: i64) -> bool {
        let Some(a) = &self.attempt else { return false };
        if a.revealed && a.user_answer.is_none() && now_us >= a.prompt_deadline_us {
            self.finish(pair_done::TIMEOUT, now_us, true, None);
            return true;
        }
        let stale = if a.revealed { now_us.saturating_sub(a.started_us) >= ATTEMPT_TIMEOUT_US } else { now_us >= a.reveal_by_us };
        if stale {
            self.end(now_us, false, None, None);
            return true;
        }
        false
    }

    /// Stores the current attempt's pairing and finishes it as ACCEPTED.
    fn accept(&mut self, now_us: i64) -> [u8; wire::PAIR_DONE_SIZE] {
        let mut confirm_h = None;
        if let Some(a) = &self.attempt {
            confirm_h = Some(pairing_math::confirm_h(&a.pair_key[..], &a.th));
            self.events.push(PairEvent::Paired {
                phone_id: a.phone_id,
                phone_name: a.phone_name.clone(),
                pair_key: a.pair_key.clone(),
            });
        }
        let d = self.finish(pair_done::ACCEPTED, now_us, false, confirm_h);
        // Success: close the window and start fresh next time.
        self.window_until_us = None;
        self.failures = 0;
        d
    }

    /// Ends the current attempt with a PAIR_DONE that later retransmits get again.
    fn finish(&mut self, status: u8, now_us: i64, cooldown: bool, confirm_h: Option<Hash>) -> [u8; wire::PAIR_DONE_SIZE] {
        let mut d = [0u8; wire::PAIR_DONE_SIZE];
        let attempt_id = self.attempt.as_ref().map_or(0, |a| a.attempt_id);
        codec::encode_pair_done(
            &PairDone { attempt_id, status, confirm_h: confirm_h.unwrap_or([0; wire::HASH_SIZE]) },
            &mut d,
        );
        self.end(now_us, cooldown, None, Some(d));
        d
    }

    fn fail(&mut self, now_us: i64) {
        self.failures += 1;
        self.cooldown_until_us = Some(now_us.saturating_add(COOLDOWN_US));
        if self.failures >= wire::PAIR_MAX_FAILS {
            self.window_until_us = None;
        }
    }

    fn end(&mut self, now_us: i64, cooldown: bool, ack: Option<[u8; wire::PAIR_ACK_SIZE]>, done: Option<[u8; wire::PAIR_DONE_SIZE]>) {
        let Some(a) = self.attempt.take() else { return };
        if cooldown {
            let until = now_us.saturating_add(COOLDOWN_US);
            self.cooldown_until_us = Some(self.cooldown_until_us.map_or(until, |c| c.max(until)));
        }
        self.finished = Some(Finished { attempt_id: a.attempt_id, phone_id: a.phone_id, ack, done });
        if a.revealed {
            self.events.push(PairEvent::Close { attempt_id: a.attempt_id });
        }
        // Dropping the attempt wipes its private key and pair key.
    }
}

fn ack(attempt_id: u32, status: u8, reply: &mut [u8]) -> usize {
    if reply.len() < wire::PAIR_ACK_SIZE {
        return 0;
    }
    codec::encode_pair_ack(PairAck { attempt_id, status }, reply)
}

fn done(attempt_id: u32, status: u8, confirm_h: Option<Hash>, reply: &mut [u8]) -> usize {
    if reply.len() < wire::PAIR_DONE_SIZE {
        return 0;
    }
    codec::encode_pair_done(&PairDone { attempt_id, status, confirm_h: confirm_h.unwrap_or([0; wire::HASH_SIZE]) }, reply)
}

fn copy(src: &[u8], reply: &mut [u8]) -> usize {
    if reply.len() < src.len() {
        return 0;
    }
    reply[..src.len()].copy_from_slice(src);
    src.len()
}
