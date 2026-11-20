//! Wire protocol v1 constants (docs/spec/protocol.md). Must stay identical to `Wire.kt` on the phone
//! and `Wire.cs` in the companion.

pub const PORT: u16 = 47800;
pub const VERSION: u8 = 1;
pub const HEADER: usize = 12;
pub const GCM_TAG: usize = 16;
pub const HELLO_MAC: usize = 16;
/// The largest datagram either side sends or takes.
pub const MAX_DATAGRAM: usize = 512;

pub const MAGIC0: u8 = 0x47; // 'G'
pub const MAGIC1: u8 = 0x50; // 'P'

pub const DISCOVER_SIZE: usize = 64;
pub const ANNOUNCE_SIZE: usize = 64;
pub const PAIR_START_SIZE: usize = 160;
pub const PAIR_HOST_SIZE: usize = 145;
pub const PAIR_REVEAL_SIZE: usize = 48;
pub const PAIR_ACK_SIZE: usize = 17;
pub const PAIR_CONFIRM_SIZE: usize = 64;
pub const PAIR_DONE_SIZE: usize = 49;
pub const HELLO_SIZE: usize = 68;
pub const WELCOME_SIZE: usize = 80;
pub const ERROR_SIZE: usize = 16;
/// LINK_TEST_CTL: u16 seconds@12 (0 = stop), 2 reserved bytes; only from this PC (loopback).
pub const LINK_TEST_CTL_SIZE: usize = 16;
/// LOG_CTL: u8 what@12, u8 value@13, 2 reserved bytes; only from this PC (loopback).
pub const LOG_CTL_SIZE: usize = 16;
pub const LOG_CTL_DIAGNOSTICS: u8 = 1;
pub const LOG_CTL_SAVE: u8 = 2;
pub const LOG_CTL_KEEP: u8 = 3;
pub const KEY_ENROLL_SIZE: usize = 137;
pub const KEY_ENROLL_ACK_SIZE: usize = 41;

pub const STATE_PLAIN: usize = 66;
/// STATE_HIRES: STATE with the wheel total in 1/120 notch plus a horizontal wheel total.
pub const STATE_HIRES_PLAIN: usize = 70;
/// The STATE_GYRO tail (the phone's raw gyro samples and settings).
pub const PC_GYRO_TAIL_SIZE: usize = 334;
/// The gyro config block inside the tail, and PAD_GYRO_SET's.
pub const PC_GYRO_CONFIG_SIZE: usize = 182;
pub const STATE_GYRO_PLAIN: usize = STATE_HIRES_PLAIN + PC_GYRO_TAIL_SIZE;
pub const PAD_GYRO_MAX_BUTTONS: usize = 18;
/// PAD_GYRO: u8 flags, u8 toggle epoch, u8 count, u8 0, 18 x (u8 place, u8 kind).
pub const PAD_GYRO_PLAIN: usize = 4 + PAD_GYRO_MAX_BUTTONS * 2;
/// PAD_GYRO_SET: u8 place, u8 0, a gyro config block.
pub const PAD_GYRO_SET_PLAIN: usize = 2 + PC_GYRO_CONFIG_SIZE;
pub const PING_PLAIN: usize = 8;
pub const LINK_INFO_PLAIN: usize = 22;
pub const BYE_PLAIN: usize = 4;
pub const PONG_PLAIN: usize = 24;
pub const HOST_STATUS_PLAIN: usize = 23;
pub const RUMBLE_PLAIN: usize = 4;
/// The character keys KEY_LABELS names, in wire order.
pub const KEY_LABEL_USAGES: usize = 48;
/// KEY_LABELS: u32 layout id, then a u16 character per key.
pub const KEY_LABELS_PLAIN: usize = 4 + 2 * KEY_LABEL_USAGES;
pub const FOREGROUND_PLAIN: usize = 66;
pub const OVERLAY_PLAIN: usize = 152;
pub const OVERLAY_LAYOUT_PLAIN: usize = 200;
pub const OVERLAY_SAMPLES_PLAIN: usize = 264;
pub const OVERLAY_FRAMES_PLAIN: usize = OVERLAY_LAYOUT_PLAIN;
pub const MOTION_MAX_SAMPLES: usize = 8;
pub const MOTION_PLAIN: usize = 8 + MOTION_MAX_SAMPLES * 14;
pub const PRESS_TIME_PLAIN: usize = 8;
pub const PHONE_STATS_PLAIN: usize = 32;
pub const TEST_MODE_PLAIN: usize = 4;
pub const CAPTURE_PLAIN: usize = 4;
pub const CAPTURED_PLAIN: usize = 64;
pub const CAPTURED_MAX: usize = 15;
pub const CAPTURE_TIMEOUT_MS: i64 = 3000;

pub const KEEPALIVE_MS: u16 = 20;
pub const RELEASE_TIMEOUT_MS: i64 = 250;
pub const SESSION_TIMEOUT_MS: i64 = 10000;
pub const PENDING_SESSION_MS: i64 = 2000;
pub const WATCHDOG_TICK_MS: i64 = 50;
pub const KEEP_AWAKE_MS: i64 = 10;
pub const KEEP_AWAKE_PLAIN: usize = 4;
pub const PING_INTERVAL_MS: i64 = 1000;
pub const STATUS_INTERVAL_MS: i64 = 1000;
pub const HOST_SILENT_MS: i64 = 3000;

pub const PAIRING_WINDOW_S: i64 = 120;
pub const PAIR_COOLDOWN_MS: i64 = 5000;
pub const PAIR_MAX_FAILS: u32 = 3;
pub const PAIR_USER_TIMEOUT_S: i64 = 60;
pub const QR_PAIRING_S: i64 = 300;
pub const QR_TOKEN_SIZE: usize = 16;

pub const MAX_MOUSE_DELTA: i32 = 16384;
pub const MAX_WHEEL_DELTA: i32 = 64;
pub const MAX_WHEEL_DELTA_HIRES: i32 = MAX_WHEEL_DELTA * 120;
pub const REKEY_SEQ: u32 = 0xF000_0000;

pub const ERROR_RATE_PER_IP: u32 = 10;
pub const ERROR_RATE_GLOBAL: u32 = 50;
pub const DSCP_TRAFFIC_CLASS: i32 = 0xC0;

pub const ID_SIZE: usize = 16;
pub const NONCE_SIZE: usize = 16;
pub const PUBLIC_KEY_SIZE: usize = 65;
pub const HASH_SIZE: usize = 32;
pub const KEY_ID_SIZE: usize = 8;
pub const ANNOUNCE_NAME_MAX: usize = 29;
pub const PAIR_NAME_MAX: usize = 31;
pub const FOREGROUND_NAME_MAX: usize = 64;

/// Direction values in the GCM nonce.
pub const DIR_PHONE_TO_HOST: u32 = 1;
pub const DIR_HOST_TO_PHONE: u32 = 2;

pub type Id = [u8; ID_SIZE];
pub type Nonce = [u8; NONCE_SIZE];
pub type PublicKey = [u8; PUBLIC_KEY_SIZE];
pub type Hash = [u8; HASH_SIZE];
pub type KeyId = [u8; KEY_ID_SIZE];

/// Packet type byte (header offset 3).
pub mod packet {
    use super::*;

    pub const DISCOVER: u8 = 0x01;
    pub const ANNOUNCE: u8 = 0x02;
    pub const PAIR_START: u8 = 0x03;
    pub const PAIR_HOST: u8 = 0x04;
    pub const PAIR_REVEAL: u8 = 0x05;
    pub const PAIR_ACK: u8 = 0x06;
    pub const PAIR_CONFIRM: u8 = 0x07;
    pub const PAIR_DONE: u8 = 0x08;
    pub const KEY_ENROLL: u8 = 0x09;
    pub const KEY_ENROLL_ACK: u8 = 0x0A;
    pub const HELLO: u8 = 0x10;
    pub const WELCOME: u8 = 0x11;
    pub const STATE: u8 = 0x20;
    pub const PING: u8 = 0x21;
    pub const LINK_INFO: u8 = 0x22;
    pub const STATE_HIRES: u8 = 0x23;
    pub const OVERLAY: u8 = 0x24;
    pub const OVERLAY_LAYOUT: u8 = 0x25;
    pub const STATE_GYRO: u8 = 0x26;
    pub const PAD_GYRO: u8 = 0x27;
    pub const PAD_GYRO_SET: u8 = 0x28;
    pub const MOTION: u8 = 0x29;
    pub const PRESS_TIME: u8 = 0x2A;
    pub const CAPTURE: u8 = 0x2B;
    pub const OVERLAY_SAMPLES: u8 = 0x2C;
    pub const PHONE_STATS: u8 = 0x2D;
    pub const OVERLAY_FRAMES: u8 = 0x2E;
    pub const BYE: u8 = 0x2F;
    pub const PONG: u8 = 0x30;
    pub const HOST_STATUS: u8 = 0x31;
    pub const RUMBLE: u8 = 0x32;
    pub const FOREGROUND: u8 = 0x33;
    pub const KEY_LABELS: u8 = 0x34;
    pub const TEST_MODE: u8 = 0x35;
    pub const KEEP_AWAKE: u8 = 0x36;
    pub const CAPTURED: u8 = 0x37;
    pub const HOST_BYE: u8 = 0x3F;
    pub const LOG_CTL: u8 = 0x7D;
    pub const LINK_TEST_CTL: u8 = 0x7E;
    pub const ERROR: u8 = 0x7F;

    /// Sealed (AES-GCM) packets are types 0x20..=0x3F.
    #[inline]
    pub fn is_sealed(t: u8) -> bool {
        (0x20..=0x3F).contains(&t)
    }

    /// Sealed packets travelling phone to host (key kPH, dir 1).
    #[inline]
    pub fn is_phone_to_host_sealed(t: u8) -> bool {
        (0x20..=0x2F).contains(&t)
    }

    /// Exact datagram size of a known type.
    pub fn size(t: u8) -> Option<usize> {
        Some(match t {
            DISCOVER => DISCOVER_SIZE,
            ANNOUNCE => ANNOUNCE_SIZE,
            PAIR_START => PAIR_START_SIZE,
            PAIR_HOST => PAIR_HOST_SIZE,
            PAIR_REVEAL => PAIR_REVEAL_SIZE,
            PAIR_ACK => PAIR_ACK_SIZE,
            PAIR_CONFIRM => PAIR_CONFIRM_SIZE,
            PAIR_DONE => PAIR_DONE_SIZE,
            HELLO => HELLO_SIZE,
            WELCOME => WELCOME_SIZE,
            ERROR => ERROR_SIZE,
            LINK_TEST_CTL => LINK_TEST_CTL_SIZE,
            LOG_CTL => LOG_CTL_SIZE,
            KEY_ENROLL => KEY_ENROLL_SIZE,
            KEY_ENROLL_ACK => KEY_ENROLL_ACK_SIZE,
            _ => HEADER + plain_size(t)? + GCM_TAG,
        })
    }

    /// Plaintext size of a sealed type.
    pub fn plain_size(t: u8) -> Option<usize> {
        Some(match t {
            STATE => STATE_PLAIN,
            STATE_HIRES => STATE_HIRES_PLAIN,
            STATE_GYRO => STATE_GYRO_PLAIN,
            PAD_GYRO => PAD_GYRO_PLAIN,
            PAD_GYRO_SET => PAD_GYRO_SET_PLAIN,
            MOTION => MOTION_PLAIN,
            PING => PING_PLAIN,
            LINK_INFO => LINK_INFO_PLAIN,
            BYE => BYE_PLAIN,
            PONG => PONG_PLAIN,
            HOST_STATUS => HOST_STATUS_PLAIN,
            RUMBLE => RUMBLE_PLAIN,
            FOREGROUND => FOREGROUND_PLAIN,
            KEY_LABELS => KEY_LABELS_PLAIN,
            OVERLAY => OVERLAY_PLAIN,
            OVERLAY_LAYOUT => OVERLAY_LAYOUT_PLAIN,
            OVERLAY_SAMPLES => OVERLAY_SAMPLES_PLAIN,
            OVERLAY_FRAMES => OVERLAY_FRAMES_PLAIN,
            PRESS_TIME => PRESS_TIME_PLAIN,
            PHONE_STATS => PHONE_STATS_PLAIN,
            TEST_MODE => TEST_MODE_PLAIN,
            CAPTURE => CAPTURE_PLAIN,
            CAPTURED => CAPTURED_PLAIN,
            HOST_BYE => BYE_PLAIN,
            // KEEP_AWAKE has no size in the C# table either: the phone drops it by type.
            _ => return None,
        })
    }
}

pub mod discover_flags {
    pub const WANT_PAIRING: u16 = 1 << 0;
}

pub mod announce_flags {
    pub const PAIRING_OPEN: u16 = 1 << 0;
    pub const VIGEM_OK: u16 = 1 << 1;
    pub const PAIRED_WITH_YOU: u16 = 1 << 2;
    pub const BUSY: u16 = 1 << 3;
    /// DISCOVER's hostKeyId is this host's key (key file pairing).
    pub const KEY_MATCH: u16 = 1 << 4;
    /// DISCOVER's phoneKeyId is in authorized_phones.
    pub const KNOWS_YOUR_KEY: u16 = 1 << 5;
}

pub mod pair_ack {
    pub const OK: u8 = 0;
    pub const BAD_COMMIT: u8 = 1;
    pub const CLOSED: u8 = 2;
    pub const BUSY: u8 = 3;
    pub const COOLDOWN: u8 = 4;
    pub const UNKNOWN_ATTEMPT: u8 = 5;
}

pub mod pair_done {
    pub const ACCEPTED: u8 = 0;
    pub const DENIED: u8 = 1;
    pub const TIMEOUT: u8 = 2;
    pub const BAD_CONFIRM: u8 = 3;
    pub const PENDING: u8 = 4;
}

pub mod enroll_status {
    pub const OK: u8 = 0;
    /// No open QR code has that token. Not MACed: the host does not know the token.
    pub const EXPIRED: u8 = 1;
    /// Another phone already used this QR code.
    pub const USED: u8 = 2;
    /// The host has no key of its own. Not MACed.
    pub const NO_KEY: u8 = 3;
}

pub mod hello_caps {
    pub const RUMBLE: u32 = 1 << 0;
    pub const FOREGROUND: u32 = 1 << 1;
}

pub mod welcome_flags {
    pub const VIGEM_OK: u8 = 1 << 0;
    pub const FOREGROUND_SUPPORTED: u8 = 1 << 1;
    pub const HIRES_WHEEL: u8 = 1 << 2;
    // Bit 3 (the old share-rewriting gyro gate) is retired and never set.
    pub const PC_GYRO: u8 = 1 << 4;
    pub const MOTION: u8 = 1 << 5;
}

/// Output device the host exposes: the wire outputMode byte.
pub mod output_mode {
    pub const NONE: u8 = 0;
    pub const XBOX360: u8 = 1;
    pub const DUALSHOCK4: u8 = 2;
    pub const DUALSENSE: u8 = 3;
}

pub mod state_flags {
    pub const GYRO_ACTIVE: u16 = 1 << 0;
    pub const REPEAT: u16 = 1 << 1;
    pub const KEEPALIVE: u16 = 1 << 2;
    /// A Link test packet: fake input to count, never to inject.
    pub const LINK_TEST: u16 = 1 << 3;
}

pub mod link_info_flags {
    pub const LOW_LATENCY_LOCK_HELD: u8 = 1 << 0;
    pub const USB: u8 = 1 << 1;
    pub const USB_DIRECT: u8 = 1 << 2;
    pub const DEBUG_TRACE: u8 = 1 << 3;
    pub const DEBUG_SAVE_MASK: u8 = 0x70;
    pub const DEBUG_SAVE_SHIFT: u32 = 4;
    pub const ETHERNET: u8 = 0x80;
}

pub mod link_path {
    pub const UNKNOWN: u8 = 0;
    pub const WIFI: u8 = 1;
    pub const HOTSPOT: u8 = 2;
    pub const USB_TETHER: u8 = 3;
    pub const USB_DIRECT: u8 = 4;
    pub const ETHERNET: u8 = 5;
}

pub mod capture_flags {
    pub const ON: u8 = 1 << 0;
}

pub mod test_mode_flags {
    pub const PRESS_TIMES: u8 = 1 << 0;
    pub const LINK_TEST: u8 = 1 << 2;
    pub const PHONE_STATS: u8 = 1 << 3;
    pub const DIAG_SAVE_MASK: u8 = 0x70;
    pub const DIAG_SAVE_SHIFT: u32 = 4;
    pub const OVERLAY_KEYS: u8 = 0x80;
}

pub mod host_status_flags {
    pub const VIGEM_OK: u8 = 1 << 0;
    pub const PAD_PLUGGED: u8 = 1 << 1;
    pub const INPUT_PAUSED: u8 = 1 << 2;
    pub const ELEVATED_FOREGROUND: u8 = 1 << 3;
    pub const NETWORK_PUBLIC: u8 = 1 << 4;
    pub const OVERLAY: u8 = 1 << 5;
    pub const OVERLAY_COMPASS: u8 = 1 << 6;
    pub const OVERLAY_TOTALS: u8 = 1 << 7;
}

/// HOST_STATUS's second flags byte (byte 22).
pub mod host_status_flags2 {
    pub const USB_DIRECT: u8 = 1 << 0;
    pub const PC_WIFI: u8 = 1 << 1;
    pub const PC_WIFI_24: u8 = 1 << 2;
    pub const PC_WIFI_WEAK: u8 = 1 << 3;
    pub const PC_ON_BATTERY: u8 = 1 << 4;
    pub const PC_POWER_SAVING: u8 = 1 << 5;
    pub const OVERLAY_SAMPLES: u8 = 1 << 6;
    pub const PC_LOGGING: u8 = 1 << 7;
    pub const SETUP_MASK: u8 = PC_WIFI | PC_WIFI_24 | PC_WIFI_WEAK | PC_ON_BATTERY | PC_POWER_SAVING;
}

pub mod bye_reason {
    pub const USER: u8 = 0;
    pub const TRANSPORT_SWITCH: u8 = 1;
    pub const SHUTDOWN: u8 = 2;
    pub const REKEY: u8 = 3;
}

pub mod host_bye_reason {
    pub const EXIT: u8 = 0;
    pub const FORGOTTEN: u8 = 1;
    pub const REPLACED: u8 = 2;
}

pub mod error_code {
    pub const VERSION: u16 = 1;
    pub const NOT_PAIRED: u16 = 2;
    pub const SESSION_UNKNOWN: u16 = 3;
}

pub mod rumble_pattern {
    pub const MOTORS: u8 = 0;
    pub const BUZZ: u8 = 1;
}

/// HOST_STATUS's "not known" for a latency.
pub const STATUS_UNKNOWN: u32 = 0xFFFF_FFFF;

/// The character keys of KEY_LABELS, in wire order: letters, digits, punctuation (no Non-US #), Non-US \.
pub const fn key_label_usages() -> [u8; KEY_LABEL_USAGES] {
    let mut u = [0u8; KEY_LABEL_USAGES];
    let mut n = 0;
    let mut i = 0x04;
    while i <= 0x27 {
        u[n] = i;
        n += 1;
        i += 1;
    }
    i = 0x2D;
    while i <= 0x31 {
        u[n] = i;
        n += 1;
        i += 1;
    }
    i = 0x33;
    while i <= 0x38 {
        u[n] = i;
        n += 1;
        i += 1;
    }
    u[n] = 0x64;
    assert!(n + 1 == KEY_LABEL_USAGES);
    u
}
