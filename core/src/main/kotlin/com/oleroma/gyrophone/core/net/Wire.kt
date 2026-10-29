package com.oleroma.gyrophone.core.net

/**
 * Constants of the GyroPhone Wi-Fi wire protocol v1. Every value here must match `Wire.cs` in the
 * Windows companion; the golden vectors in `protocol/v1/wire-v1.json` pin both sides.
 */
object Wire {
    const val PORT = 47800
    const val VERSION = 1
    const val MAGIC0: Byte = 0x47 // 'G'
    const val MAGIC1: Byte = 0x50 // 'P'
    const val HEADER = 12
    const val GCM_TAG = 16
    const val HELLO_MAC = 16
    const val MAX_DATAGRAM = 256

    const val DISCOVER_SIZE = 64
    const val ANNOUNCE_SIZE = 64
    const val PAIR_START_SIZE = 160
    const val PAIR_HOST_SIZE = 145
    const val PAIR_REVEAL_SIZE = 48
    const val PAIR_ACK_SIZE = 17
    const val PAIR_CONFIRM_SIZE = 64
    const val PAIR_DONE_SIZE = 49
    const val HELLO_SIZE = 68
    const val WELCOME_SIZE = 80
    const val ERROR_SIZE = 16
    /** QR code pairing: the phone sends its key, MACed with the QR's one-time token. */
    const val KEY_ENROLL_SIZE = 137
    const val KEY_ENROLL_ACK_SIZE = 41

    const val STATE_PLAIN = 66
    /** STATE_HIRES: STATE with the wheel total in 1/120 notch, plus a horizontal wheel total. */
    const val STATE_HIRES_PLAIN = 70
    const val PING_PLAIN = 8
    const val LINK_INFO_PLAIN = 22
    const val BYE_PLAIN = 4
    const val PONG_PLAIN = 24
    const val HOST_STATUS_PLAIN = 22
    const val RUMBLE_PLAIN = 4
    const val FOREGROUND_PLAIN = 66

    /** Sealed packet size for a plaintext of [plain] bytes. */
    fun sealedSize(plain: Int): Int = HEADER + plain + GCM_TAG

    val RATES_HZ = listOf(250, 500, 1000)
    const val DEFAULT_RATE_HZ = 1000
    const val KEEPALIVE_MS = 20
    val REPEAT_OFFSETS_MS = listOf(2, 6)
    const val RELEASE_TIMEOUT_MS = 250
    const val SESSION_TIMEOUT_MS = 10_000
    const val PENDING_SESSION_MS = 2000
    const val WATCHDOG_TICK_MS = 50
    const val PING_INTERVAL_MS = 1000
    const val STATUS_INTERVAL_MS = 1000
    const val HOST_SILENT_MS = 3000
    /** HELLO retries for one nP; after the last entry they continue every [HELLO_RETRY_LATER_MS]. */
    val HELLO_RETRY_MS = listOf(0, 100, 200, 400, 800)
    const val HELLO_RETRY_LATER_MS = 1000
    const val DISCOVERY_FALLBACK_MS = 500
    const val DISCOVER_INTERVAL_MS = 1000
    const val HOST_EXPIRY_MS = 3000
    const val PAIRING_WINDOW_S = 120
    const val PAIR_RETX_MS = 250
    const val PAIR_CONFIRM_RETX_MS = 500
    const val PAIR_COOLDOWN_MS = 5000
    const val PAIR_MAX_FAILS = 3
    const val PAIR_USER_TIMEOUT_S = 60
    /** How long a QR code shown by the PC stays valid. */
    const val QR_PAIRING_S = 300
    const val QR_TOKEN_SIZE = 16
    const val QR_RETX_MS = 250
    /** The phone gives up a QR enrolment that got no answer after this long. */
    const val QR_GIVE_UP_MS = 10_000
    const val MAX_MOUSE_DELTA = 16384
    const val MAX_WHEEL_DELTA = 64
    /** [MAX_WHEEL_DELTA] in STATE_HIRES units (1/120 notch). */
    const val MAX_WHEEL_DELTA_HIRES = MAX_WHEEL_DELTA * 120
    /** P->H seq at which the phone runs a new HELLO (unsigned, so compare as Long). */
    const val REKEY_SEQ = 0xF0000000L
    const val ERROR_RATE_PER_IP = 10
    const val ERROR_RATE_GLOBAL = 50
    /** CS6 -> 802.11 UP6 -> WMM AC_VO. */
    const val DSCP_TRAFFIC_CLASS = 0xC0

    /** Nonce direction words of sealed packets. */
    const val DIR_P2H = 1
    const val DIR_H2P = 2

    const val ID_SIZE = 16
    const val NONCE_SIZE = 16
    const val PUBLIC_KEY_SIZE = 65
    const val HASH_SIZE = 32
    /** [KeyPairing.keyId]: the first 8 bytes of SHA-256 of a static public key. */
    const val KEY_ID_SIZE = 8
    const val ANNOUNCE_NAME_MAX = 29
    const val PAIR_NAME_MAX = 31
    const val FOREGROUND_EXE_MAX = 64

    /** Exact datagram size of [type], or -1 if the type is unknown. */
    fun sizeOf(type: Int): Int = when (type) {
        PacketType.DISCOVER -> DISCOVER_SIZE
        PacketType.ANNOUNCE -> ANNOUNCE_SIZE
        PacketType.PAIR_START -> PAIR_START_SIZE
        PacketType.PAIR_HOST -> PAIR_HOST_SIZE
        PacketType.PAIR_REVEAL -> PAIR_REVEAL_SIZE
        PacketType.PAIR_ACK -> PAIR_ACK_SIZE
        PacketType.PAIR_CONFIRM -> PAIR_CONFIRM_SIZE
        PacketType.PAIR_DONE -> PAIR_DONE_SIZE
        PacketType.HELLO -> HELLO_SIZE
        PacketType.WELCOME -> WELCOME_SIZE
        PacketType.ERROR -> ERROR_SIZE
        PacketType.KEY_ENROLL -> KEY_ENROLL_SIZE
        PacketType.KEY_ENROLL_ACK -> KEY_ENROLL_ACK_SIZE
        else -> plainSizeOf(type).let { if (it < 0) -1 else sealedSize(it) }
    }

    /** Plaintext size of a sealed [type], or -1 if it is not a sealed type. */
    fun plainSizeOf(type: Int): Int = when (type) {
        PacketType.STATE -> STATE_PLAIN
        PacketType.STATE_HIRES -> STATE_HIRES_PLAIN
        PacketType.PING -> PING_PLAIN
        PacketType.LINK_INFO -> LINK_INFO_PLAIN
        PacketType.BYE -> BYE_PLAIN
        PacketType.PONG -> PONG_PLAIN
        PacketType.HOST_STATUS -> HOST_STATUS_PLAIN
        PacketType.RUMBLE -> RUMBLE_PLAIN
        PacketType.FOREGROUND -> FOREGROUND_PLAIN
        PacketType.HOST_BYE -> BYE_PLAIN
        else -> -1
    }
}

object PacketType {
    const val DISCOVER = 0x01
    const val ANNOUNCE = 0x02
    const val PAIR_START = 0x03
    const val PAIR_HOST = 0x04
    const val PAIR_REVEAL = 0x05
    const val PAIR_ACK = 0x06
    const val PAIR_CONFIRM = 0x07
    const val PAIR_DONE = 0x08
    /** Phone to PC: this phone's key, for the PC whose QR code it scanned. */
    const val KEY_ENROLL = 0x09
    const val KEY_ENROLL_ACK = 0x0A
    const val HELLO = 0x10
    const val WELCOME = 0x11
    const val STATE = 0x20
    const val PING = 0x21
    const val LINK_INFO = 0x22
    /** Sent instead of STATE when WELCOME has [HostFlags.HIRES_WHEEL]. */
    const val STATE_HIRES = 0x23
    const val BYE = 0x2F
    const val PONG = 0x30
    const val HOST_STATUS = 0x31
    const val RUMBLE = 0x32
    const val FOREGROUND = 0x33
    const val HOST_BYE = 0x3F
    const val ERROR = 0x7F

    /** Sealed (AES-GCM) session packets are types 0x20..0x3F. */
    fun isSealed(type: Int): Boolean = type in 0x20..0x3F

    /** Phone-to-host sealed types are 0x20..0x2F, host-to-phone 0x30..0x3F. */
    fun isPhoneToHost(type: Int): Boolean = type in 0x20..0x2F
}

/** DISCOVER flags. */
object DiscoverFlags {
    const val WANT_PAIRING = 1
}

/** ANNOUNCE flags. */
object AnnounceFlags {
    const val PAIRING_OPEN = 1
    const val VIGEM_OK = 2
    const val PAIRED_WITH_YOU = 4
    const val BUSY = 8
    /** DISCOVER's hostKeyId is this PC's key (key file pairing). */
    const val KEY_MATCH = 16
    /** DISCOVER's phoneKeyId is in this PC's authorized_phones folder. */
    const val KNOWS_YOUR_KEY = 32
}

/** PAIR_ACK status. */
object AckStatus {
    const val OK = 0
    const val BAD_COMMIT = 1
    const val CLOSED = 2
    const val BUSY = 3
    const val COOLDOWN = 4
    const val UNKNOWN_ATTEMPT = 5
}

/** PAIR_DONE status. */
object DoneStatus {
    const val ACCEPTED = 0
    const val DENIED = 1
    const val TIMEOUT = 2
    const val BAD_CONFIRM = 3
    const val PENDING = 4
}

/** KEY_ENROLL_ACK status. */
object EnrollStatus {
    const val OK = 0
    /** No QR code with that token is open (closed, expired or never shown). Not MACed. */
    const val EXPIRED = 1
    /** Another phone already used this QR code. */
    const val USED = 2
    /** The PC has no key of its own. Not MACed. */
    const val NO_KEY = 3
}

/** HELLO caps. */
object Caps {
    const val RUMBLE = 1
    const val FOREGROUND = 2
}

/** WELCOME / HOST_STATUS output mode. */
object OutputMode {
    const val NONE = 0
    const val XBOX360 = 1
    const val DUALSHOCK4 = 2
}

/** WELCOME hostFlags. */
object HostFlags {
    const val VIGEM_OK = 1
    const val FOREGROUND_SUPPORTED = 2
    /** The host accepts STATE_HIRES (smooth vertical and horizontal wheel). */
    const val HIRES_WHEEL = 4
}

/** STATE flags. */
object StateFlags {
    const val GYRO_ACTIVE = 1
    const val REPEAT = 2
    const val KEEPALIVE = 4
}

/** LINK_INFO flags. */
object LinkInfoFlags {
    const val LOW_LATENCY_LOCK_HELD = 1

    /** The phone sends over USB tethering (the Wi-Fi fields are then not this link's). */
    const val USB = 2

    /** With [USB]: the cable runs in accessory mode (direct USB), not tethering. */
    const val USB_DIRECT = 4
}

/** HOST_STATUS flags. */
object HostStatusFlags {
    const val VIGEM_OK = 1
    const val PAD_PLUGGED = 2
    const val INPUT_PAUSED = 4
    const val ELEVATED_FOREGROUND = 8
    const val NETWORK_PUBLIC = 16
}

/** BYE reason (phone). */
object ByeReason {
    const val USER = 0
    const val TRANSPORT_SWITCH = 1
    const val SHUTDOWN = 2
    const val REKEY = 3
}

/** HOST_BYE reason. */
object HostByeReason {
    const val EXIT = 0
    const val FORGOTTEN = 1
    const val REPLACED = 2
}

/** ERROR code. */
object ErrorCode {
    const val VERSION = 1
    const val NOT_PAIRED = 2
    const val SESSION_UNKNOWN = 3
}
