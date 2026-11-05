# Protocol and pairing

The phone ↔ companion protocol, version 1, over UDP port **47800** (Wi-Fi, USB tethering, or the direct cable bridged to loopback). Bluetooth uses plain HID instead ([Connection](phone-connection.md)).

Reference code: `core/.../net/Wire.kt`, `WireCodec.kt`, `Crypto.kt`, `SessionCipher.kt`, `PhonePairingFlow.kt`, `PhoneSessionFlow.kt`, `KeyPairing.kt`, `QrPairing.kt`, `PcGyroFeed.kt`, `OverlayFeed.kt` on the phone; `companion/src/GyroPhone.Protocol/` (`Wire.cs`, `WireCodec.cs`, `Packets.cs`, `Crypto/`) and `GyroPhone.Host/HostEngine.cs`, `PairingManager.cs`, `SessionManager.cs` on the PC. Shared test vectors are in `core/src/test/resources/protocol/v1/`; both sides must pass them. A change to the wire format changes both sides and the vectors.

## Packets

All integers little-endian. Every packet starts with a 12-byte **header**: `'G' 'P'`, version `1`, type (u8), `sid` (u32, 0 before a session), `seq` (u32). Datagrams are at most 512 bytes; a packet whose length isn't its type's exact size is dropped.

**Unsealed** packets (discovery, pairing, session setup) are sent in the clear; **sealed** ones are AES-128-GCM with the header as AAD: `header ‖ ciphertext ‖ 16-byte tag`, so a sealed packet is 12 + plaintext + 16 bytes.

| Type | Code | Dir | Size | Fields (offset) |
|---|---|---|---|---|
| DISCOVER | 0x01 | P→H, broadcast | 64 | reqId@12, phoneId@16 (16), flags@32, hostKeyId@34 (8), phoneKeyId@42 (8) |
| ANNOUNCE | 0x02 | H→P | 64 | reqId@12, hostId@16 (16), flags@32, name@34 (u8 length + ≤29 bytes UTF-8) |
| PAIR_START | 0x03 | P→H | 160 | phoneId@12, pkP@28 (65), commit@93 (32), name@125 |
| PAIR_HOST | 0x04 | H→P | 145 | attemptId@12, hostId@16, pkH@32 (65), nH@97 (16), name@113 |
| PAIR_REVEAL | 0x05 | P→H | 48 | attemptId@12, phoneId@16, nP@32 (16) |
| PAIR_ACK | 0x06 | H→P | 17 | attemptId@12, status@16 |
| PAIR_CONFIRM | 0x07 | P→H | 64 | attemptId@12, phoneId@16, confirmP@32 (32) |
| PAIR_DONE | 0x08 | H→P | 49 | attemptId@12, status@16, confirmH@17 (32) |
| KEY_ENROLL | 0x09 | P→H, broadcast | 137 | reqId@12, tokenId@16 (8), pkP@24 (65), name@89, mac@121 (16) |
| KEY_ENROLL_ACK | 0x0A | H→P | 41 | reqId@12, tokenId@16, status@24, mac@25 (16) |
| HELLO | 0x10 | P→H | 68 | phoneId@12, nP@28 (16), caps@44 (u32), maxRateHz@48 (u32), mac@52 (16) |
| WELCOME | 0x11 | H→P | 80 | sid in header; hostId@12, nP@28, nH@44, outputMode@60, hostFlags@61, keepaliveMs@62 (u16), mac@64 (16) |
| ERROR | 0x7F | H→P | 16 | code@12 (u16), maxVersion@14 |

Sealed plaintext sizes:

| Type | Code | Dir | Plaintext | Contents |
|---|---|---|---|---|
| STATE | 0x20 | P→H | 66 | time u32 (phone ms), flags u16, buttons u16, hat u8, mouseButtons u8, lx ly rx ry i16, lt rt u16 (0..1023), keyBitmap[32]@22 (HID usages; bitmap byte 28 = the modifiers 0xE0..0xE7), mouseTotalX i32@54, mouseTotalY i32@58, wheelTotal i32@62 |
| STATE_HIRES | 0x23 | P→H | 70 | STATE + hwheelTotal i32@66; wheels in 1/120 notch |
| STATE_GYRO | 0x26 | P→H | 355 | STATE_HIRES + 285-byte gyro tail (below) |
| PING | 0x21 | P→H | 8 | t1 u64: phone send time (µs) |
| PONG | 0x30 | H→P | 24 | t1 (echoed), t2 host receive, t3 host send, u64 each: the phone works out the clock offset and round trip |
| LINK_INFO | 0x22 | P→H | 22 | clock offset i64, rttMin u32, rate u16, rssi i8, band, link Mbps u16, MHz u16, flags |
| HOST_STATUS | 0x31 | H→P | 22 | lastSeq, rx/s, loss ‰, latency p50, p99, jitter, output mode, flags |
| PAD_GYRO | 0x27 | P→H | 4 + 2 × buttons | ratchet/toggle buttons by pad place, toggle epoch |
| PAD_GYRO_SET | 0x28 | P→H | 183 | u8 place, u8 0, a 181-byte gyro config block |
| OVERLAY | 0x24 | P→H | 152 | pose input: raw motion, ratchet, non-gyro mouse, touches, presses (`OverlayFeed`) |
| OVERLAY_LAYOUT | 0x25 | P→H | 200 | the workspace layout, in chunks (`OverlayFeed`) |
| RUMBLE | 0x32 | H→P | 4 | large u8, small u8 motor (the phone ignores it today) |
| FOREGROUND | 0x33 | H→P | 66 | reserved, never sent |
| BYE | 0x2F | P→H | 4 | reason: USER, TRANSPORT_SWITCH, SHUTDOWN, REKEY |
| HOST_BYE | 0x3F | H→P | 4 | reason: EXIT, FORGOTTEN, REPLACED |

STATE's mouse and wheel values are **cumulative wrapping totals**, so a lost packet loses no motion; the PC injects the difference (each step capped at 16384). STATE flags: GYRO_ACTIVE 1, REPEAT 2, KEEPALIVE 4.

**Gyro tail** (STATE_GYRO): flags, count, seq u16, time u32, 8 samples × 9 bytes, 3 × i64 per-axis running totals, then the 181-byte config block: sensitivities f32 × 3, glide f32 × 3, glide bits, route count, tightening, smoothing, 12 routes × 6 bytes @34, 3 curves × 25 bytes @106. Layout in `PcGyroFeed.encodeTail` / `PcGyroTail.cs`.

### Flags and codes

- **Announce flags**: PAIRING_OPEN 1, VIGEM_OK 2, PAIRED_WITH_YOU 4, BUSY 8, KEY_MATCH 16, KNOWS_YOUR_KEY 32.
- **Host flags** (WELCOME): VIGEM 1, FOREGROUND 2, HIRES_WHEEL 4, PC_GYRO 16. The host sends VIGEM, HIRES_WHEEL and PC_GYRO.
- **Caps** (HELLO): RUMBLE 1, FOREGROUND 2. **Output modes**: 0 none, 1 Xbox 360, 2 DualShock 4.
- **LINK_INFO flags**: LOW_LATENCY 1, USB 2, USB_DIRECT 4, DEBUG_TRACE 8, a debug save counter in 0x70.
- **HOST_STATUS flags**: VIGEM 1, PAD_PLUGGED 2, PAUSED 4, ELEVATED_FG 8, NET_PUBLIC 16, OVERLAY 32, OVERLAY_COMPASS 64, OVERLAY_TOTALS 128.
- **PAIR_ACK**: OK 0, BAD_COMMIT 1, CLOSED 2, BUSY 3, COOLDOWN 4, UNKNOWN_ATTEMPT 5. **PAIR_DONE**: ACCEPTED 0, DENIED 1, TIMEOUT 2, BAD_CONFIRM 3, PENDING 4. **KEY_ENROLL_ACK**: OK 0, EXPIRED 1, USED 2, NO_KEY 3.
- **ERROR**: VERSION 1, NOT_PAIRED 2, SESSION_UNKNOWN 3. Sent only for requests of at least 16 bytes, at most 10 per second per IP and 50 overall.

## Discovery

The phone broadcasts DISCOVER (255.255.255.255 and the subnet broadcast) once a second while looking, carrying its phone id and, for key pairing, its key id and the key id of the PC it is looking for. Each companion answers with ANNOUNCE (name, flags). A found PC is dropped 3 s after its last answer. With a known PC the phone sends HELLO to its last address first and falls back to DISCOVER after 500 ms. The watcher also listens for DISCOVER ([Companion](pc-companion.md#watcher)).

## Code pairing

An ECDH P-256 exchange with a commitment and a 6-digit code (SAS) both people compare. The PC's pairing window is open for **120 s** (Pair new phone, or the first run with nothing paired).

1. Phone → PAIR_START: its ephemeral public key pkP and `commit = SHA256("GP-commit-v1" ‖ pkP ‖ nP)`.
2. PC → PAIR_HOST: its attemptId, hostId, ephemeral pkH and nonce nH.
3. Phone → PAIR_REVEAL: nP. PC checks the commit → PAIR_ACK.
4. Both compute `th = SHA256("GP-pair-v1" ‖ phoneId ‖ hostId ‖ pkP ‖ pkH ‖ nP ‖ nH ‖ u32le attemptId)` and show `SAS = u32le(SHA256("GP-sas-v1" ‖ th)[0..4]) mod 1 000 000`, zero-padded, as six digits. The person taps Confirm on the phone and Allow on the PC (60 s to answer).
5. `pairKey = HKDF-SHA256(z = ECDH, salt th, "GP-pair-key-v1", 32)`. Phone → PAIR_CONFIRM with `confirmP = HMAC(pairKey, "GP-confirm-P" ‖ th)`; PC → PAIR_DONE with `confirmH = HMAC(pairKey, "GP-confirm-H" ‖ th)` (PENDING while the person hasn't answered).

Retransmits every 250 ms (confirm 500 ms). After 3 failures the PC refuses for 5 s (COOLDOWN). Both sides then store pairKey (phone: wrapped by the Android Keystore; PC: DPAPI).

## Key pairing

Each side has a **static P-256 key**: the phone's in the Android Keystore, the PC's in `host.key`. Public keys are files `gyrophone-p256 <base64 uncompressed point> <name>` (`phone.pub`, `host.pub`); a key's **keyId** is `SHA256(pk)[0..8]`. When each side trusts the other's key (by file swap, import, or QR):

- `ss = ECDH(own private, other public)`;
- `pairKey = HKDF(ss, salt SHA256("GP-key-v1" ‖ pkP ‖ pkH), "GP-key-pair-v1", 32)`;
- the session's phone id is `SHA256("GP-key-phone-id-v1" ‖ pkP ‖ pkH)[0..16]`.

ANNOUNCE KEY_MATCH / KNOWS_YOUR_KEY tell the phone the PC holds its key, so it can connect at once (automatically while on screen, if Connect automatically is on).

## QR pairing

The PC's pairing window shows `gyrophone://pair?v=1&n=<name>&k=<host public key>&t=<token>&a=<addresses>&p=<port>` (base64url values; up to four IPv4 addresses). The token is one-time, valid **300 s** (renewed while the window is open).

1. The phone trusts the PC key from the QR and sends KEY_ENROLL (to each address and as broadcast): `tokenId = SHA256("GP-qr-id-v1" ‖ token)[0..8]`, its public key pkP, its name, and `mac = HMAC(token, "GP-qr-enroll-v1" ‖ packet[0..121])[0..16]`.
2. The PC holding that token saves the phone key to `authorized_phones` and answers KEY_ENROLL_ACK with `mac = HMAC(token, "GP-qr-ack-v1" ‖ packet[0..25] ‖ pkP)[0..16]`. A PC that never issued the token doesn't answer at all, so it can't spoil a real pairing. Repeats of the same key are acked again.
3. Retransmit every 250 ms; the phone gives up after 10 s. Then key pairing connects.

## Sessions

1. Phone → **HELLO**: phoneId (code-paired id or key phone id), fresh nonce nP, caps, max rate, `mac = HMAC(pairKey, packet[0..52])[0..16]`. Sent at 0, 100, 200, 400, 800 ms, then every second.
2. PC: a bad mac gets **no reply**; an unknown phone gets ERROR NOT_PAIRED; a retransmitted HELLO gets the cached WELCOME. Otherwise it makes a **pending** session (2 s) and answers **WELCOME** with the new sid, both nonces, output mode, host flags, keepalive and its own mac.
3. Session keys: `HKDF(pairKey, salt nP ‖ nH, "GP-session-v1" ‖ u32le sid, 32)`; bytes 0..16 = **kPH** (phone → host), 16..32 = **kHP**.
4. Nonce for AES-GCM: `u32le direction (P→H 1, H→P 2) ‖ u32le sid ‖ u32le seq`. Each direction counts seq up from 1; a packet is accepted only with seq above the highest seen (replay protection). The phone rekeys (BYE REKEY, new HELLO) before seq reaches 0xF0000000.
5. The first valid STATE **promotes** the pending session to the active one; a different phone's session then gets HOST_BYE REPLACED.

**While live**: STATE as described in [Connection](phone-connection.md) (on change, ≤ report rate, +2/+6 ms repeats after a discrete change, keepalive every 20 ms); PING and LINK_INFO each second; HOST_STATUS from the PC each second. The phone calls the PC silent after **3 s** without anything and reconnects; the PC releases everything after **250 ms** without a STATE and drops the session after **10 s**. BYE / HOST_BYE end it at once.

## USB framing

USB tethering carries the UDP packets unchanged. The direct cable (Android Open Accessory) is a byte stream, so each datagram is framed as `0x47, u16le length, bytes`, plus one pad byte when a frame's size is a multiple of 64 (so no transfer ends on a full USB packet) (`core/.../net/UsbFrames.kt`, `GyroPhone.Protocol/UsbFraming.cs`). Both ends bridge it to a loopback UDP socket, so the protocol above is unchanged.

## Key files

See the reference list at the top.
