//! Encoders and decoders for every v1 packet except STATE (see `state_frame`). Encoders write into
//! the caller's buffer and return the length written; decoders return None on any rule violation
//! (wrong size, magic, version, type, or a name length past its field). Nothing here trusts a length
//! or an offset that came from the network: every packet has one exact size, checked first.

use crate::crypto::{self, qr_pairing};
use crate::le;
use crate::wire::{self, packet, Hash, Id, KeyId, Nonce, PublicKey};

// ---- header ----

pub fn write_header(b: &mut [u8], ptype: u8, session_id: u32, seq: u32) {
    b[0] = wire::MAGIC0;
    b[1] = wire::MAGIC1;
    b[2] = wire::VERSION;
    b[3] = ptype;
    le::put_u32(b, 4, session_id);
    le::put_u32(b, 8, seq);
}

/// Header checks for a packet of an expected type: magic, version, type and exact size.
pub fn check_frame(b: &[u8], ptype: u8) -> bool {
    b.len() >= wire::HEADER
        && b[0] == wire::MAGIC0
        && b[1] == wire::MAGIC1
        && b[2] == wire::VERSION
        && b[3] == ptype
        && packet::size(ptype) == Some(b.len())
}

// ---- strings ----

/// Writes u8 len + UTF-8 bytes zero-padded to `max`, truncating on a code point boundary.
pub fn write_name(b: &mut [u8], off: usize, name: &str, max: usize) {
    let field = &mut b[off + 1..off + 1 + max];
    field.fill(0);
    let bytes = name.as_bytes();
    let mut n = bytes.len().min(max);
    if n < bytes.len() {
        while n > 0 && (bytes[n] & 0xC0) == 0x80 {
            n -= 1;
        }
    }
    field[..n].copy_from_slice(&bytes[..n]);
    b[off] = n as u8;
}

/// Reads a name field; None when its length byte is past the field. Bytes that are not UTF-8 become
/// U+FFFD, as .NET's decoder makes them.
pub fn read_name(b: &[u8], off: usize, max: usize) -> Option<String> {
    let len = b[off] as usize;
    if len > max {
        return None;
    }
    Some(String::from_utf8_lossy(&b[off + 1..off + 1 + len]).into_owned())
}

fn get<const N: usize>(b: &[u8], off: usize) -> [u8; N] {
    b[off..off + N].try_into().unwrap()
}

/// Clears the packet's bytes and writes its header (session id and seq zero).
fn start(b: &mut [u8], ptype: u8) -> &mut [u8] {
    let size = packet::size(ptype).expect("a known packet type");
    let p = &mut b[..size];
    p.fill(0);
    write_header(p, ptype, 0, 0);
    p
}

// ---- discovery ----

/// `host_key_id` names the host key the phone trusts and `phone_key_id` the phone's own key (all zero when absent).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Discover {
    pub req_id: u32,
    pub phone_id: Id,
    pub flags: u16,
    pub host_key_id: KeyId,
    pub phone_key_id: KeyId,
}

pub fn encode_discover(p: &Discover, b: &mut [u8]) -> usize {
    let o = start(b, packet::DISCOVER);
    le::put_u32(o, 12, p.req_id);
    o[16..32].copy_from_slice(&p.phone_id);
    le::put_u16(o, 32, p.flags);
    o[34..42].copy_from_slice(&p.host_key_id);
    o[42..50].copy_from_slice(&p.phone_key_id);
    o.len()
}

pub fn decode_discover(b: &[u8]) -> Option<Discover> {
    if !check_frame(b, packet::DISCOVER) {
        return None;
    }
    Some(Discover { req_id: le::u32(b, 12), phone_id: get(b, 16), flags: le::u16(b, 32), host_key_id: get(b, 34), phone_key_id: get(b, 42) })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Announce {
    pub req_id: u32,
    pub host_id: Id,
    pub flags: u16,
    pub name: String,
}

pub fn encode_announce(p: &Announce, b: &mut [u8]) -> usize {
    let o = start(b, packet::ANNOUNCE);
    le::put_u32(o, 12, p.req_id);
    o[16..32].copy_from_slice(&p.host_id);
    le::put_u16(o, 32, p.flags);
    write_name(o, 34, &p.name, wire::ANNOUNCE_NAME_MAX);
    o.len()
}

pub fn decode_announce(b: &[u8]) -> Option<Announce> {
    if !check_frame(b, packet::ANNOUNCE) {
        return None;
    }
    Some(Announce { req_id: le::u32(b, 12), host_id: get(b, 16), flags: le::u16(b, 32), name: read_name(b, 34, wire::ANNOUNCE_NAME_MAX)? })
}

// ---- QR code pairing ----

/// KEY_ENROLL; `mac` is `qr_pairing::enroll_mac` under the QR code's token.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyEnroll {
    pub req_id: u32,
    pub token_id: KeyId,
    pub pk_p: PublicKey,
    pub name: String,
    pub mac: qr_pairing::QrMac,
}

pub fn decode_key_enroll(b: &[u8]) -> Option<KeyEnroll> {
    if !check_frame(b, packet::KEY_ENROLL) {
        return None;
    }
    Some(KeyEnroll { req_id: le::u32(b, 12), token_id: get(b, 16), pk_p: get(b, 24), name: read_name(b, 89, wire::PAIR_NAME_MAX)?, mac: get(b, 121) })
}

/// KEY_ENROLL with its mac under `token` (the phone's side; for tests).
pub fn encode_key_enroll(req_id: u32, token: &[u8], pk_p: &PublicKey, name: &str, b: &mut [u8]) -> usize {
    let o = start(b, packet::KEY_ENROLL);
    le::put_u32(o, 12, req_id);
    o[16..24].copy_from_slice(&qr_pairing::token_id(token));
    o[24..89].copy_from_slice(pk_p);
    write_name(o, 89, name, wire::PAIR_NAME_MAX);
    let mac = qr_pairing::enroll_mac(token, o);
    o[121..137].copy_from_slice(&mac);
    o.len()
}

/// KEY_ENROLL_ACK; without a token (EXPIRED, NO_KEY) the mac stays zero.
pub fn encode_key_enroll_ack(req_id: u32, token_id: &KeyId, status: u8, token: Option<&[u8]>, pk_p: &[u8], b: &mut [u8]) -> usize {
    let o = start(b, packet::KEY_ENROLL_ACK);
    le::put_u32(o, 12, req_id);
    o[16..24].copy_from_slice(token_id);
    o[24] = status;
    if let Some(token) = token {
        let mac = qr_pairing::ack_mac(token, o, pk_p);
        o[25..41].copy_from_slice(&mac);
    }
    o.len()
}

// ---- code pairing ----

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairStart {
    pub phone_id: Id,
    pub pk_p: PublicKey,
    pub commit: Hash,
    pub name: String,
}

pub fn encode_pair_start(p: &PairStart, b: &mut [u8]) -> usize {
    let o = start(b, packet::PAIR_START);
    o[12..28].copy_from_slice(&p.phone_id);
    o[28..93].copy_from_slice(&p.pk_p);
    o[93..125].copy_from_slice(&p.commit);
    write_name(o, 125, &p.name, wire::PAIR_NAME_MAX);
    o.len()
}

pub fn decode_pair_start(b: &[u8]) -> Option<PairStart> {
    if !check_frame(b, packet::PAIR_START) {
        return None;
    }
    Some(PairStart { phone_id: get(b, 12), pk_p: get(b, 28), commit: get(b, 93), name: read_name(b, 125, wire::PAIR_NAME_MAX)? })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairHost {
    pub attempt_id: u32,
    pub host_id: Id,
    pub pk_h: PublicKey,
    pub n_h: Nonce,
    pub name: String,
}

pub fn encode_pair_host(p: &PairHost, b: &mut [u8]) -> usize {
    let o = start(b, packet::PAIR_HOST);
    le::put_u32(o, 12, p.attempt_id);
    o[16..32].copy_from_slice(&p.host_id);
    o[32..97].copy_from_slice(&p.pk_h);
    o[97..113].copy_from_slice(&p.n_h);
    write_name(o, 113, &p.name, wire::PAIR_NAME_MAX);
    o.len()
}

pub fn decode_pair_host(b: &[u8]) -> Option<PairHost> {
    if !check_frame(b, packet::PAIR_HOST) {
        return None;
    }
    Some(PairHost { attempt_id: le::u32(b, 12), host_id: get(b, 16), pk_h: get(b, 32), n_h: get(b, 97), name: read_name(b, 113, wire::PAIR_NAME_MAX)? })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairReveal {
    pub attempt_id: u32,
    pub phone_id: Id,
    pub n_p: Nonce,
}

pub fn encode_pair_reveal(p: &PairReveal, b: &mut [u8]) -> usize {
    let o = start(b, packet::PAIR_REVEAL);
    le::put_u32(o, 12, p.attempt_id);
    o[16..32].copy_from_slice(&p.phone_id);
    o[32..48].copy_from_slice(&p.n_p);
    o.len()
}

pub fn decode_pair_reveal(b: &[u8]) -> Option<PairReveal> {
    if !check_frame(b, packet::PAIR_REVEAL) {
        return None;
    }
    Some(PairReveal { attempt_id: le::u32(b, 12), phone_id: get(b, 16), n_p: get(b, 32) })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PairAck {
    pub attempt_id: u32,
    pub status: u8,
}

pub fn encode_pair_ack(p: PairAck, b: &mut [u8]) -> usize {
    let o = start(b, packet::PAIR_ACK);
    le::put_u32(o, 12, p.attempt_id);
    o[16] = p.status;
    o.len()
}

pub fn decode_pair_ack(b: &[u8]) -> Option<PairAck> {
    if !check_frame(b, packet::PAIR_ACK) {
        return None;
    }
    Some(PairAck { attempt_id: le::u32(b, 12), status: b[16] })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairConfirm {
    pub attempt_id: u32,
    pub phone_id: Id,
    pub confirm_p: Hash,
}

pub fn encode_pair_confirm(p: &PairConfirm, b: &mut [u8]) -> usize {
    let o = start(b, packet::PAIR_CONFIRM);
    le::put_u32(o, 12, p.attempt_id);
    o[16..32].copy_from_slice(&p.phone_id);
    o[32..64].copy_from_slice(&p.confirm_p);
    o.len()
}

pub fn decode_pair_confirm(b: &[u8]) -> Option<PairConfirm> {
    if !check_frame(b, packet::PAIR_CONFIRM) {
        return None;
    }
    Some(PairConfirm { attempt_id: le::u32(b, 12), phone_id: get(b, 16), confirm_p: get(b, 32) })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairDone {
    pub attempt_id: u32,
    pub status: u8,
    pub confirm_h: Hash,
}

pub fn encode_pair_done(p: &PairDone, b: &mut [u8]) -> usize {
    let o = start(b, packet::PAIR_DONE);
    le::put_u32(o, 12, p.attempt_id);
    o[16] = p.status;
    o[17..49].copy_from_slice(&p.confirm_h);
    o.len()
}

pub fn decode_pair_done(b: &[u8]) -> Option<PairDone> {
    if !check_frame(b, packet::PAIR_DONE) {
        return None;
    }
    Some(PairDone { attempt_id: le::u32(b, 12), status: b[16], confirm_h: get(b, 17) })
}

// ---- session handshake ----

/// HELLO without its mac; the mac is written on encode and checked separately (`verify_mac`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Hello {
    pub phone_id: Id,
    pub n_p: Nonce,
    pub caps: u32,
    pub max_rate_hz: u16,
}

pub fn encode_hello(p: &Hello, pair_key: &[u8], b: &mut [u8]) -> usize {
    let o = start(b, packet::HELLO);
    o[12..28].copy_from_slice(&p.phone_id);
    o[28..44].copy_from_slice(&p.n_p);
    le::put_u32(o, 44, p.caps);
    le::put_u16(o, 48, p.max_rate_hz);
    write_mac(pair_key, o, 52);
    o.len()
}

/// Decodes the HELLO fields. The mac is not checked here; see `verify_mac`.
pub fn decode_hello(b: &[u8]) -> Option<Hello> {
    if !check_frame(b, packet::HELLO) {
        return None;
    }
    Some(Hello { phone_id: get(b, 12), n_p: get(b, 28), caps: le::u32(b, 44), max_rate_hz: le::u16(b, 48) })
}

/// WELCOME without its mac.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Welcome {
    pub session_id: u32,
    pub host_id: Id,
    pub n_p: Nonce,
    pub n_h: Nonce,
    pub output_mode: u8,
    pub host_flags: u8,
    pub keepalive_ms: u16,
}

pub fn encode_welcome(p: &Welcome, pair_key: &[u8], b: &mut [u8]) -> usize {
    let o = start(b, packet::WELCOME);
    le::put_u32(o, 4, p.session_id);
    o[12..28].copy_from_slice(&p.host_id);
    o[28..44].copy_from_slice(&p.n_p);
    o[44..60].copy_from_slice(&p.n_h);
    o[60] = p.output_mode;
    o[61] = p.host_flags;
    le::put_u16(o, 62, p.keepalive_ms);
    write_mac(pair_key, o, 64);
    o.len()
}

pub fn decode_welcome(b: &[u8]) -> Option<Welcome> {
    if !check_frame(b, packet::WELCOME) {
        return None;
    }
    Some(Welcome {
        session_id: le::u32(b, 4),
        host_id: get(b, 12),
        n_p: get(b, 28),
        n_h: get(b, 44),
        output_mode: b[60],
        host_flags: b[61],
        keepalive_ms: le::u16(b, 62),
    })
}

/// mac16 = HMAC(pairKey, bytes[0..macOff))[0..16], written at `mac_off`.
pub fn write_mac(pair_key: &[u8], packet: &mut [u8], mac_off: usize) {
    let full = crypto::hmac_sha256(pair_key, &[&packet[..mac_off]]);
    packet[mac_off..mac_off + wire::HELLO_MAC].copy_from_slice(&full[..wire::HELLO_MAC]);
}

/// Constant-time check of a HELLO (mac at 52) or WELCOME (mac at 64).
pub fn verify_mac(pair_key: &[u8], packet: &[u8]) -> bool {
    let Some(mac_off) = packet.len().checked_sub(wire::HELLO_MAC) else { return false };
    if mac_off < wire::HEADER {
        return false;
    }
    let full = crypto::hmac_sha256(pair_key, &[&packet[..mac_off]]);
    crypto::ct_eq(&full[..wire::HELLO_MAC], &packet[mac_off..])
}

// ---- error ----

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ErrorPacket {
    pub code: u16,
    pub max_version: u8,
}

pub fn encode_error(p: ErrorPacket, b: &mut [u8]) -> usize {
    let o = start(b, packet::ERROR);
    le::put_u16(o, 12, p.code);
    o[14] = p.max_version;
    o.len()
}

/// ERROR is accepted with any version byte so a v1 phone can read a newer host's VERSION error.
pub fn decode_error(b: &[u8]) -> Option<ErrorPacket> {
    if b.len() != wire::ERROR_SIZE || b[0] != wire::MAGIC0 || b[1] != wire::MAGIC1 || b[3] != packet::ERROR {
        return None;
    }
    Some(ErrorPacket { code: le::u16(b, 12), max_version: b[14] })
}

// ---- sealed plaintexts (the caller has checked the plaintext's size by its type) ----

pub fn encode_ping(t1: u64, b: &mut [u8]) -> usize {
    le::put_u64(b, 0, t1);
    wire::PING_PLAIN
}

pub fn decode_ping(b: &[u8]) -> u64 {
    le::u64(b, 0)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Pong {
    pub t1: u64,
    pub t2: u64,
    pub t3: u64,
}

pub fn encode_pong(p: Pong, b: &mut [u8]) -> usize {
    le::put_u64(b, 0, p.t1);
    le::put_u64(b, 8, p.t2);
    le::put_u64(b, 16, p.t3);
    wire::PONG_PLAIN
}

pub fn decode_pong(b: &[u8]) -> Pong {
    Pong { t1: le::u64(b, 0), t2: le::u64(b, 8), t3: le::u64(b, 16) }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct LinkInfo {
    pub clock_offset_us: i64,
    pub rtt_min_us: u32,
    pub send_rate_hz: u16,
    pub rssi_dbm: i8,
    pub band: u8,
    pub link_mbps: u16,
    pub freq_mhz: u16,
    pub flags: u8,
    pub path: u8,
}

pub fn encode_link_info(p: &LinkInfo, b: &mut [u8]) -> usize {
    le::put_i64(b, 0, p.clock_offset_us);
    le::put_u32(b, 8, p.rtt_min_us);
    le::put_u16(b, 12, p.send_rate_hz);
    b[14] = p.rssi_dbm as u8;
    b[15] = p.band;
    le::put_u16(b, 16, p.link_mbps);
    le::put_u16(b, 18, p.freq_mhz);
    b[20] = p.flags;
    b[21] = p.path;
    wire::LINK_INFO_PLAIN
}

pub fn decode_link_info(b: &[u8]) -> LinkInfo {
    LinkInfo {
        clock_offset_us: le::i64(b, 0),
        rtt_min_us: le::u32(b, 8),
        send_rate_hz: le::u16(b, 12),
        rssi_dbm: b[14] as i8,
        band: b[15],
        link_mbps: le::u16(b, 16),
        freq_mhz: le::u16(b, 18),
        flags: b[20],
        path: b[21],
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HostStatus {
    pub last_seq: u32,
    pub rx_per_sec: u16,
    pub loss_permille: u16,
    pub lat_p50_us: u32,
    pub lat_p99_us: u32,
    pub jitter_us: u32,
    pub output_mode: u8,
    pub flags: u8,
    pub flags2: u8,
}

pub fn encode_host_status(p: &HostStatus, b: &mut [u8]) -> usize {
    le::put_u32(b, 0, p.last_seq);
    le::put_u16(b, 4, p.rx_per_sec);
    le::put_u16(b, 6, p.loss_permille);
    le::put_u32(b, 8, p.lat_p50_us);
    le::put_u32(b, 12, p.lat_p99_us);
    le::put_u32(b, 16, p.jitter_us);
    b[20] = p.output_mode;
    b[21] = p.flags;
    b[22] = p.flags2;
    wire::HOST_STATUS_PLAIN
}

pub fn decode_host_status(b: &[u8]) -> HostStatus {
    HostStatus {
        last_seq: le::u32(b, 0),
        rx_per_sec: le::u16(b, 4),
        loss_permille: le::u16(b, 6),
        lat_p50_us: le::u32(b, 8),
        lat_p99_us: le::u32(b, 12),
        jitter_us: le::u32(b, 16),
        output_mode: b[20],
        flags: b[21],
        flags2: b[22],
    }
}

/// BYE, HOST_BYE, TEST_MODE, CAPTURE and KEEP_AWAKE: one byte, then three zero bytes.
pub fn encode_byte4(v: u8, b: &mut [u8]) -> usize {
    b[..4].fill(0);
    b[0] = v;
    4
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rumble {
    pub large: u8,
    pub small: u8,
    pub pattern: u8,
}

pub fn encode_rumble(p: Rumble, b: &mut [u8]) -> usize {
    b[0] = p.large;
    b[1] = p.small;
    b[2] = p.pattern;
    b[3] = 0;
    wire::RUMBLE_PLAIN
}

pub fn decode_rumble(b: &[u8]) -> Rumble {
    Rumble { large: b[0], small: b[1], pattern: b[2] }
}

/// When the phone saw the input behind a press or release, and the time of the STATE that first
/// carries it (both the low 32 bits of the phone's µs clock).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PressTime {
    pub state_time_us: u32,
    pub event_time_us: u32,
}

pub fn encode_press_time(p: PressTime, b: &mut [u8]) -> usize {
    le::put_u32(b, 0, p.state_time_us);
    le::put_u32(b, 4, p.event_time_us);
    wire::PRESS_TIME_PLAIN
}

pub fn decode_press_time(b: &[u8]) -> PressTime {
    PressTime { state_time_us: le::u32(b, 0), event_time_us: le::u32(b, 4) }
}

/// The phone's side of the link over one second (times in µs).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct PhoneStats {
    pub time_us: u32,
    pub states: u16,
    pub packets: u16,
    pub failed: u16,
    pub slow_sends: u16,
    pub send_total_us: u32,
    pub send_max_us: u32,
    pub wake_late_max_us: u32,
    pub state_gap_max_us: u32,
    pub phone_tx_packets: u32,
}

pub fn encode_phone_stats(p: &PhoneStats, b: &mut [u8]) -> usize {
    le::put_u32(b, 0, p.time_us);
    le::put_u16(b, 4, p.states);
    le::put_u16(b, 6, p.packets);
    le::put_u16(b, 8, p.failed);
    le::put_u16(b, 10, p.slow_sends);
    le::put_u32(b, 12, p.send_total_us);
    le::put_u32(b, 16, p.send_max_us);
    le::put_u32(b, 20, p.wake_late_max_us);
    le::put_u32(b, 24, p.state_gap_max_us);
    le::put_u32(b, 28, p.phone_tx_packets);
    wire::PHONE_STATS_PLAIN
}

pub fn decode_phone_stats(b: &[u8]) -> PhoneStats {
    PhoneStats {
        time_us: le::u32(b, 0),
        states: le::u16(b, 4),
        packets: le::u16(b, 6),
        failed: le::u16(b, 8),
        slow_sends: le::u16(b, 10),
        send_total_us: le::u32(b, 12),
        send_max_us: le::u32(b, 16),
        wake_late_max_us: le::u32(b, 20),
        state_gap_max_us: le::u32(b, 24),
        phone_tx_packets: le::u32(b, 28),
    }
}

/// One input held on the PC while the phone listens (CAPTURED): a key by HID usage, a mouse button
/// (0 left, 1 right, 2 middle, 3 back, 4 forward), a wheel notch (code 0, `arg` +1 up / -1 down), a
/// controller button by its XInput bit, or a controller axis pushed past half way (code 0..5; `arg`
/// the way, +1 or -1).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct PcPress {
    pub kind: u8,
    pub code: u16,
    pub arg: i8,
}

pub mod pc_press {
    pub const KEY: u8 = 1;
    pub const MOUSE: u8 = 2;
    pub const WHEEL: u8 = 3;
    pub const PAD_BUTTON: u8 = 4;
    pub const PAD_AXIS: u8 = 5;
}

/// CAPTURED: the first `CAPTURED_MAX` of `held`.
pub fn encode_captured(held: &[PcPress], b: &mut [u8]) -> usize {
    b[..wire::CAPTURED_PLAIN].fill(0);
    let n = held.len().min(wire::CAPTURED_MAX);
    b[0] = n as u8;
    for (i, h) in held[..n].iter().enumerate() {
        let o = 2 + 4 * i;
        b[o] = h.kind;
        b[o + 1] = h.arg as u8;
        le::put_u16(b, o + 2, h.code);
    }
    wire::CAPTURED_PLAIN
}

/// The held inputs of a CAPTURED into `into`; returns how many.
pub fn decode_captured(b: &[u8], into: &mut [PcPress]) -> usize {
    let n = (b[0] as usize).min(wire::CAPTURED_MAX).min(into.len());
    for (i, slot) in into[..n].iter_mut().enumerate() {
        let o = 2 + 4 * i;
        *slot = PcPress { kind: b[o], code: le::u16(b, o + 2), arg: b[o + 1] as i8 };
    }
    n
}

/// KEY_LABELS: what each key of `wire::key_label_usages` types on the PC's keyboard layout, one
/// UTF-16 unit each (0 = none).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyLabels {
    pub layout: u32,
    pub chars: [u16; wire::KEY_LABEL_USAGES],
}

pub fn encode_key_labels(p: &KeyLabels, b: &mut [u8]) -> usize {
    le::put_u32(b, 0, p.layout);
    for (i, c) in p.chars.iter().enumerate() {
        le::put_u16(b, 4 + 2 * i, *c);
    }
    wire::KEY_LABELS_PLAIN
}

pub fn decode_key_labels(b: &[u8]) -> KeyLabels {
    let mut chars = [0u16; wire::KEY_LABEL_USAGES];
    for (i, c) in chars.iter_mut().enumerate() {
        *c = le::u16(b, 4 + 2 * i);
    }
    KeyLabels { layout: le::u32(b, 0), chars }
}
