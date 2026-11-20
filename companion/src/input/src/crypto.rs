//! The link's cryptography (docs/spec/protocol.md 1.5 to 1.7), the same values as `Crypto/*.cs` and
//! the phone's Kotlin: P-256 ECDH, SHA-256, HMAC-SHA256, HKDF-SHA256 and AES-128-GCM. Checked byte for
//! byte against the shared vectors (`tests/golden.rs`).

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::Aes128Gcm;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, Zeroizing};

use crate::le;
use crate::wire::{self, Hash, Id, KeyId, PublicKey};

type HmacSha256 = Hmac<Sha256>;

/// Fills `out` from the system's random source. A machine without one cannot make keys or session ids
/// safely, so there is nothing to fall back to: this aborts the process (the companion starts it again).
pub fn random(out: &mut [u8]) {
    getrandom::getrandom(out).expect("the system's random source failed");
}

/// Constant-time equality of two byte strings of the same length (false when the lengths differ).
#[inline]
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && bool::from(a.ct_eq(b))
}

pub fn sha256(parts: &[&[u8]]) -> Hash {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

pub fn hmac_sha256(key: &[u8], parts: &[&[u8]]) -> Hash {
    let mut m = <HmacSha256 as Mac>::new_from_slice(key).expect("HMAC takes a key of any length");
    for p in parts {
        m.update(p);
    }
    m.finalize().into_bytes().into()
}

/// HKDF-SHA256 (extract with `salt`, expand with `info`) into `okm` (at most 255 x 32 bytes).
pub fn hkdf_sha256(ikm: &[u8], salt: &[u8], info: &[u8], okm: &mut [u8]) {
    hkdf::Hkdf::<Sha256>::new(Some(salt), ikm).expand(info, okm).expect("HKDF output too long");
}

// =====================================================================================
// P-256
// =====================================================================================

/// A P-256 key pair: ephemeral for code pairing, static for key file pairing. Public keys travel as SEC1
/// uncompressed points (04 || X || Y, 65 bytes); the shared secret Z is the 32-byte X coordinate.
/// The private scalar is wiped when this is dropped.
pub struct P256 {
    secret: p256::SecretKey,
    public: PublicKey,
}

impl P256 {
    pub fn generate() -> P256 {
        loop {
            let mut d = Zeroizing::new([0u8; 32]);
            random(&mut d[..]);
            // Rejects zero and anything at or above the group order (about 2^-32 of all values): draw again.
            if let Some(k) = P256::from_private_scalar(&d[..]) {
                return k;
            }
        }
    }

    /// The key of a 32-byte big-endian private scalar; None when it is not a valid one.
    pub fn from_private_scalar(d: &[u8]) -> Option<P256> {
        if d.len() != 32 {
            return None;
        }
        let secret = p256::SecretKey::from_slice(d).ok()?;
        let point = p256::EncodedPoint::from(secret.public_key());
        let public: PublicKey = point.as_bytes().try_into().ok()?;
        Some(P256 { secret, public })
    }

    /// The 32-byte big-endian private scalar, for storing a static key (wrap it before it touches disk).
    pub fn export_private_scalar(&self) -> Zeroizing<[u8; 32]> {
        let mut o = Zeroizing::new([0u8; 32]);
        o.copy_from_slice(&self.secret.to_bytes());
        o
    }

    pub fn public_key(&self) -> &PublicKey {
        &self.public
    }

    /// Z = X coordinate of d * peer; None when `peer` is not a valid uncompressed point on the curve.
    pub fn ecdh(&self, peer: &[u8]) -> Option<Zeroizing<[u8; 32]>> {
        let peer = decode_public(peer)?;
        let shared = p256::ecdh::diffie_hellman(self.secret.to_nonzero_scalar(), peer.as_affine());
        let mut z = Zeroizing::new([0u8; 32]);
        z.copy_from_slice(shared.raw_secret_bytes());
        Some(z)
    }

    /// True when the bytes are a well-formed uncompressed point on P-256.
    pub fn is_valid_public(public: &[u8]) -> bool {
        decode_public(public).is_some()
    }
}

fn decode_public(public: &[u8]) -> Option<p256::PublicKey> {
    if public.len() != wire::PUBLIC_KEY_SIZE || public[0] != 0x04 {
        return None;
    }
    // Checks that the point lies on the curve (the identity has no uncompressed form).
    p256::PublicKey::from_sec1_bytes(public).ok()
}

// =====================================================================================
// code pairing (commit / reveal with a number to compare)
// =====================================================================================

pub mod pairing_math {
    use super::*;

    /// commit = H("GP-commit-v1" || pkP || nP)
    pub fn commit(pk_p: &[u8], n_p: &[u8]) -> Hash {
        sha256(&[b"GP-commit-v1", pk_p, n_p])
    }

    /// th = H("GP-pair-v1" || phoneId || hostId || pkP || pkH || nP || nH || u32le(attemptId))
    pub fn th(phone_id: &[u8], host_id: &[u8], pk_p: &[u8], pk_h: &[u8], n_p: &[u8], n_h: &[u8], attempt_id: u32) -> Hash {
        sha256(&[b"GP-pair-v1", phone_id, host_id, pk_p, pk_h, n_p, n_h, &attempt_id.to_le_bytes()])
    }

    /// sas = u32le(H("GP-sas-v1" || th)[0..4]) mod 1_000_000
    pub fn sas(th: &[u8]) -> u32 {
        le::u32(&sha256(&[b"GP-sas-v1", th]), 0) % 1_000_000
    }

    /// Six zero-padded digits, e.g. "400588".
    pub fn sas_digits(sas: u32) -> String {
        format!("{sas:06}")
    }

    /// Display form with a space after three digits, e.g. "400 588".
    pub fn sas_display(sas: u32) -> String {
        let s = sas_digits(sas);
        format!("{} {}", &s[..3], &s[3..])
    }

    /// pairKey = HKDF(ikm = Z, salt = th, info = "GP-pair-key-v1", L = 32)
    pub fn pair_key(z: &[u8], th: &[u8]) -> Zeroizing<[u8; 32]> {
        let mut o = Zeroizing::new([0u8; 32]);
        hkdf_sha256(z, th, b"GP-pair-key-v1", &mut o[..]);
        o
    }

    pub fn confirm_p(pair_key: &[u8], th: &[u8]) -> Hash {
        hmac_sha256(pair_key, &[b"GP-confirm-P", th])
    }

    pub fn confirm_h(pair_key: &[u8], th: &[u8]) -> Hash {
        hmac_sha256(pair_key, &[b"GP-confirm-H", th])
    }
}

// =====================================================================================
// key file pairing
// =====================================================================================

pub mod key_pairing {
    use super::*;

    pub const TAG: &str = "gyrophone-p256";

    /// First 8 bytes of SHA-256 of a public key: what DISCOVER carries.
    pub fn key_id(public_key: &[u8]) -> KeyId {
        sha256(&[public_key])[..wire::KEY_ID_SIZE].try_into().unwrap()
    }

    /// Short display form, e.g. "SHA256:3f9a…7c21".
    pub fn fingerprint(public_key: &[u8]) -> String {
        let h = sha256(&[public_key]);
        format!("SHA256:{:02x}{:02x}…{:02x}{:02x}", h[0], h[1], h[30], h[31])
    }

    pub fn salt(pk_p: &[u8], pk_h: &[u8]) -> Hash {
        sha256(&[b"GP-key-v1", pk_p, pk_h])
    }

    pub fn pair_key(ss: &[u8], pk_p: &[u8], pk_h: &[u8]) -> Zeroizing<[u8; 32]> {
        let mut o = Zeroizing::new([0u8; 32]);
        hkdf_sha256(ss, &salt(pk_p, pk_h), b"GP-key-pair-v1", &mut o[..]);
        o
    }

    pub fn key_phone_id(pk_p: &[u8], pk_h: &[u8]) -> Id {
        sha256(&[b"GP-key-phone-id-v1", pk_p, pk_h])[..wire::ID_SIZE].try_into().unwrap()
    }

    /// Host side: (keyPhoneId, pairKey) for an authorized phone key; None when the phone key is not a valid point.
    pub fn host_secrets(host: &P256, phone_key: &[u8]) -> Option<(Id, Zeroizing<[u8; 32]>)> {
        let ss = host.ecdh(phone_key)?;
        Some((key_phone_id(phone_key, host.public_key()), pair_key(&ss[..], phone_key, host.public_key())))
    }

    /// A file name for a key named `name`: letters, digits, space, dot, dash and underscore.
    pub fn file_name(name: &str) -> String {
        let cleaned: String = name
            .chars()
            .map(|c| if c.is_alphanumeric() || matches!(c, ' ' | '.' | '_' | '-') { c } else { '_' })
            .collect();
        let mut clean = cleaned.trim().trim_matches('.').to_string();
        if clean.is_empty() {
            clean = "key".to_string();
        }
        // The C# cuts at 48 UTF-16 units; cut at 48 characters, never inside one.
        let cut: String = clean.chars().take(48).collect();
        format!("{cut}.pub")
    }

    /// One .pub file: `gyrophone-p256 <base64 SEC1 key> <name>`.
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct PubKeyFile {
        pub public_key: PublicKey,
        pub name: String,
    }

    impl PubKeyFile {
        pub fn fingerprint(&self) -> String {
            fingerprint(&self.public_key)
        }

        /// The file's single line, with a trailing newline.
        pub fn format(&self) -> String {
            format!("{TAG} {} {}\n", base64_encode(&self.public_key, false), self.name)
        }

        /// Parses a key file (first non-blank line); None unless it holds a valid P-256 point.
        pub fn parse(text: &str) -> Option<PubKeyFile> {
            let line = text.split('\n').map(str::trim).find(|l| !l.is_empty())?;
            let mut parts = line.splitn(2, char::is_whitespace);
            if parts.next()? != TAG {
                return None;
            }
            let rest = parts.next()?.trim_start();
            let (b64, name) = match rest.find(char::is_whitespace) {
                Some(i) => (&rest[..i], rest[i..].trim()),
                None => (rest, ""),
            };
            let key = base64_decode(b64)?;
            if !P256::is_valid_public(&key) {
                return None;
            }
            Some(PubKeyFile { public_key: key.as_slice().try_into().ok()?, name: name.to_string() })
        }
    }
}

// =====================================================================================
// QR code pairing
// =====================================================================================

pub mod qr_pairing {
    use super::*;

    pub const PREFIX: &str = "gyrophone://pair?";
    pub const MAC_SIZE: usize = 16;
    pub type QrMac = [u8; MAC_SIZE];

    /// tokenId = SHA-256("GP-qr-id-v1" || token)[0..8]
    pub fn token_id(token: &[u8]) -> KeyId {
        sha256(&[b"GP-qr-id-v1", token])[..wire::KEY_ID_SIZE].try_into().unwrap()
    }

    /// The mac over a KEY_ENROLL's first 121 bytes.
    pub fn enroll_mac(token: &[u8], head: &[u8]) -> QrMac {
        hmac_sha256(token, &[b"GP-qr-enroll-v1", &head[..121]])[..MAC_SIZE].try_into().unwrap()
    }

    /// The mac over a KEY_ENROLL_ACK's first 25 bytes, for the phone key `pk_p`.
    pub fn ack_mac(token: &[u8], head: &[u8], pk_p: &[u8]) -> QrMac {
        hmac_sha256(token, &[b"GP-qr-ack-v1", &head[..25], pk_p])[..MAC_SIZE].try_into().unwrap()
    }

    /// True when a KEY_ENROLL's mac is right for `token`.
    pub fn check_enroll(packet: &[u8], token: &[u8]) -> bool {
        packet.len() == wire::KEY_ENROLL_SIZE && ct_eq(&enroll_mac(token, packet), &packet[121..121 + MAC_SIZE])
    }

    /// A fresh one-time token.
    pub fn new_token() -> [u8; wire::QR_TOKEN_SIZE] {
        let mut t = [0u8; wire::QR_TOKEN_SIZE];
        random(&mut t);
        t
    }

    /// The QR code's text: `gyrophone://pair?v=1&n=<name>&k=<pkH>&t=<token>&a=<ip,ip>&p=<port>`.
    pub fn format(host_key: &[u8], token: &[u8], name: &str, addresses: &[String], port: u16) -> String {
        format!(
            "{PREFIX}v=1&n={}&k={}&t={}&a={}&p={}",
            percent_encode(name),
            base64_encode(host_key, true),
            base64_encode(token, true),
            addresses.join(","),
            port
        )
    }

    /// Percent-encodes all but RFC 3986's unreserved characters, as .NET's Uri.EscapeDataString does.
    fn percent_encode(s: &str) -> String {
        let mut o = String::with_capacity(s.len());
        for &b in s.as_bytes() {
            if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
                o.push(b as char);
            } else {
                o.push_str(&format!("%{b:02X}"));
            }
        }
        o
    }
}

// =====================================================================================
// sessions
// =====================================================================================

/// Per-session AES-128 keys: okm = HKDF(pairKey, salt = nP || nH, info = "GP-session-v1" || u32le(sid), 32);
/// kPH = okm[0..16], kHP = okm[16..32].
pub fn session_keys(pair_key: &[u8], n_p: &[u8], n_h: &[u8], session_id: u32) -> (Zeroizing<[u8; 16]>, Zeroizing<[u8; 16]>) {
    let mut salt = [0u8; 2 * wire::NONCE_SIZE];
    salt[..wire::NONCE_SIZE].copy_from_slice(n_p);
    salt[wire::NONCE_SIZE..].copy_from_slice(n_h);
    let mut info = [0u8; 13 + 4];
    info[..13].copy_from_slice(b"GP-session-v1");
    info[13..].copy_from_slice(&session_id.to_le_bytes());
    let mut okm = Zeroizing::new([0u8; 32]);
    hkdf_sha256(pair_key, &salt, &info, &mut okm[..]);
    let mut k_ph = Zeroizing::new([0u8; 16]);
    let mut k_hp = Zeroizing::new([0u8; 16]);
    k_ph.copy_from_slice(&okm[..16]);
    k_hp.copy_from_slice(&okm[16..]);
    (k_ph, k_hp)
}

/// AES-128-GCM for sealed packets: packet = header(12) || ciphertext || tag(16), AAD = header,
/// nonce = u32le(dir) || u32le(sessionId) || u32le(seq). Makes nothing after construction; the key
/// schedule is wiped on drop.
pub struct SessionCipher {
    aes: Aes128Gcm,
    dir: u32,
}

impl SessionCipher {
    pub fn new(key: &[u8; 16], dir: u32) -> SessionCipher {
        SessionCipher { aes: Aes128Gcm::new(key.into()), dir }
    }

    fn nonce(&self, session_id: u32, seq: u32) -> [u8; 12] {
        let mut n = [0u8; 12];
        n[..4].copy_from_slice(&self.dir.to_le_bytes());
        n[4..8].copy_from_slice(&session_id.to_le_bytes());
        n[8..].copy_from_slice(&seq.to_le_bytes());
        n
    }

    /// Writes the header, then encrypts `plain` behind it. Returns the packet length, or None when
    /// `packet` is too short for it.
    pub fn seal(&self, ptype: u8, session_id: u32, seq: u32, plain: &[u8], packet: &mut [u8]) -> Option<usize> {
        let len = wire::HEADER + plain.len() + wire::GCM_TAG;
        if packet.len() < len {
            return None;
        }
        crate::codec::write_header(packet, ptype, session_id, seq);
        let (head, rest) = packet.split_at_mut(wire::HEADER);
        let (body, tag) = rest.split_at_mut(plain.len());
        body.copy_from_slice(plain);
        let nonce = self.nonce(session_id, seq);
        let t = self.aes.encrypt_in_place_detached((&nonce).into(), head, body).ok()?;
        tag[..wire::GCM_TAG].copy_from_slice(&t);
        Some(len)
    }

    /// Verifies and decrypts a whole sealed packet into `plain`; returns the plaintext length. None
    /// (and `plain` cleared) on any tag failure.
    pub fn open(&self, packet: &[u8], plain: &mut [u8]) -> Option<usize> {
        let n = packet.len().checked_sub(wire::HEADER + wire::GCM_TAG)?;
        if plain.len() < n {
            return None;
        }
        let nonce = self.nonce(le::u32(packet, 4), le::u32(packet, 8));
        let out = &mut plain[..n];
        out.copy_from_slice(&packet[wire::HEADER..wire::HEADER + n]);
        let tag = &packet[wire::HEADER + n..];
        match self.aes.decrypt_in_place_detached((&nonce).into(), &packet[..wire::HEADER], out, tag.into()) {
            Ok(()) => Some(n),
            Err(_) => {
                out.zeroize();
                None
            }
        }
    }
}

// =====================================================================================
// base64 (key files and the QR code)
// =====================================================================================

const B64_STD: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const B64_URL: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// Standard base64 with padding, or (`url`) the URL alphabet without padding.
pub fn base64_encode(data: &[u8], url: bool) -> String {
    let abc = if url { B64_URL } else { B64_STD };
    let mut o = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = (b[0] as u32) << 16 | (b[1] as u32) << 8 | b[2] as u32;
        o.push(abc[(n >> 18) as usize & 63] as char);
        o.push(abc[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            o.push(abc[(n >> 6) as usize & 63] as char);
        } else if !url {
            o.push('=');
        }
        if chunk.len() > 2 {
            o.push(abc[n as usize & 63] as char);
        } else if !url {
            o.push('=');
        }
    }
    o
}

/// Standard base64 with its padding (as .NET's Convert.FromBase64String takes it); None for anything else.
pub fn base64_decode(text: &str) -> Option<Vec<u8>> {
    let t = text.as_bytes();
    if t.len() % 4 != 0 {
        return None;
    }
    let mut o = Vec::with_capacity(t.len() / 4 * 3);
    for (i, q) in t.chunks(4).enumerate() {
        let last = i + 1 == t.len() / 4;
        let mut n = 0u32;
        let mut pad = 0;
        for (j, &c) in q.iter().enumerate() {
            let v = match c {
                b'A'..=b'Z' => c - b'A',
                b'a'..=b'z' => c - b'a' + 26,
                b'0'..=b'9' => c - b'0' + 52,
                b'+' => 62,
                b'/' => 63,
                b'=' if last && j >= 2 => {
                    pad += 1;
                    0
                }
                _ => return None,
            };
            if pad > 0 && c != b'=' {
                return None; // data after padding
            }
            n = n << 6 | v as u32;
        }
        o.push((n >> 16) as u8);
        if pad < 2 {
            o.push((n >> 8) as u8);
        }
        if pad < 1 {
            o.push(n as u8);
        }
    }
    Some(o)
}
