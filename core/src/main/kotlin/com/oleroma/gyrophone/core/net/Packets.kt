package com.oleroma.gyrophone.core.net

/**
 * Decoded wire packets. u32 fields hold the raw 32-bit pattern in an Int (compare with
 * `toLong() and 0xFFFFFFFF` where the unsigned value matters). Byte arrays are fixed-size fields
 * (ids and nonces 16 bytes, public keys 65, hashes 32).
 */
sealed interface WirePacket

/**
 * [hostKeyId] names the PC key this phone trusts and [phoneKeyId] this phone's own key (both
 * [KeyPairing.keyId], all zero when absent). Hosts without key pairing ignore them.
 */
data class Discover(
    val reqId: Int,
    val phoneId: ByteArray,
    val flags: Int = 0,
    val hostKeyId: ByteArray = ByteArray(Wire.KEY_ID_SIZE),
    val phoneKeyId: ByteArray = ByteArray(Wire.KEY_ID_SIZE),
) : WirePacket

data class Announce(val reqId: Int, val hostId: ByteArray, val flags: Int, val name: String) : WirePacket

data class PairStart(val phoneId: ByteArray, val pkP: ByteArray, val commit: ByteArray, val name: String) : WirePacket

data class PairHost(
    val attemptId: Int,
    val hostId: ByteArray,
    val pkH: ByteArray,
    val nH: ByteArray,
    val name: String,
) : WirePacket

data class PairReveal(val attemptId: Int, val phoneId: ByteArray, val nP: ByteArray) : WirePacket

data class PairAck(val attemptId: Int, val status: Int) : WirePacket

data class PairConfirm(val attemptId: Int, val phoneId: ByteArray, val confirmP: ByteArray) : WirePacket

data class PairDone(val attemptId: Int, val status: Int, val confirmH: ByteArray = ByteArray(Wire.HASH_SIZE)) : WirePacket

/** KEY_ENROLL: [mac] is [QrPairing.enrollMac] under the QR code's token. */
data class KeyEnroll(val reqId: Int, val tokenId: ByteArray, val pkP: ByteArray, val name: String, val mac: ByteArray) : WirePacket

/** KEY_ENROLL_ACK: [mac] is [QrPairing.ackMac] (all zero for [EnrollStatus.EXPIRED] and [EnrollStatus.NO_KEY]). */
data class KeyEnrollAck(val reqId: Int, val tokenId: ByteArray, val status: Int, val mac: ByteArray) : WirePacket

/** HELLO without its mac; the mac is written by the encoder and checked with [Crypto.verifyTrailingMac]. */
data class Hello(val phoneId: ByteArray, val nP: ByteArray, val caps: Int, val maxRateHz: Int) : WirePacket

/** WELCOME without its mac; [sessionId] travels in the header. */
data class Welcome(
    val sessionId: Int,
    val hostId: ByteArray,
    val nP: ByteArray,
    val nH: ByteArray,
    val outputMode: Int,
    val hostFlags: Int,
    val keepaliveMs: Int,
) : WirePacket

data class ErrorPacket(val code: Int, val maxVersion: Int = Wire.VERSION) : WirePacket

// Sealed session plaintexts. STATE is the mutable [StateFrame].

data class Ping(val t1: Long) : WirePacket

data class Pong(val t1: Long, val t2: Long, val t3: Long) : WirePacket

data class LinkInfo(
    val clockOffsetUs: Long,
    val rttMinUs: Int,
    val sendRateHz: Int,
    val rssiDbm: Int,
    val band: Int,
    val linkMbps: Int,
    val freqMhz: Int,
    val flags: Int,
    /** How the session's packets travel ([LinkPath]); [LinkPath.UNKNOWN] from older phones. */
    val path: Int = LinkPath.UNKNOWN,
) : WirePacket

/** LINK_INFO's path: the way this session's packets go from the phone to the PC. */
object LinkPath {
    const val UNKNOWN = 0
    /** The phone's Wi-Fi client (a router, or the PC's own hotspot). */
    const val WIFI = 1
    /** The phone's own hotspot, with the PC joined to it. */
    const val HOTSPOT = 2
    const val USB_TETHER = 3
    const val USB_DIRECT = 4
    const val ETHERNET = 5
}

data class Bye(val reason: Int) : WirePacket

data class HostStatus(
    val lastSeq: Int,
    val rxPerSec: Int,
    val lossPermille: Int,
    /** 0xFFFFFFFF (-1) = unknown. */
    val latP50Us: Int,
    val latP99Us: Int,
    val jitterUs: Int,
    val outputMode: Int,
    val flags: Int,
    /** [HostStatusFlags2]. */
    val flags2: Int = 0,
) : WirePacket

/**
 * When the phone saw the input behind a press or release: [eventTimeUs], and the time of the
 * STATE that first carries it, [stateTimeUs] (both the low 32 bits of the phone's
 * elapsed-realtime µs, as STATE's time).
 */
data class PressTime(val stateTimeUs: Int, val eventTimeUs: Int) : WirePacket

/** The PC's latency tests the phone takes part in ([TestModeFlags]). */
data class TestMode(val flags: Int) : WirePacket

/** The phone's "Press a key" screen is open or closed ([CaptureFlags]). */
data class CaptureListen(val flags: Int) : WirePacket

/**
 * One input held on the PC while the phone listens (CAPTURED): a [KEY] by HID usage, a [MOUSE]
 * button (0 left, 1 right, 2 middle, 3 back, 4 forward), a [WHEEL] notch (code 0, [arg] +1 up,
 * -1 down), a [PAD_BUTTON] by its XInput bit, or a [PAD_AXIS] pushed past half way (code 0..5:
 * left x, y, right x, y, left, right trigger; y down; [arg] the way, +1 or -1).
 */
data class PcPress(val kind: Int, val code: Int, val arg: Int = 0) {
    companion object {
        const val KEY = 1
        const val MOUSE = 2
        const val WHEEL = 3
        const val PAD_BUTTON = 4
        const val PAD_AXIS = 5
    }
}

/** What is held on the PC now (CAPTURED), at most [Wire.CAPTURED_MAX]. */
data class PcPresses(val held: List<PcPress>) : WirePacket

/**
 * PC → phone vibration. [pattern] [Pattern.MOTORS]: the game's rumble, [large] and [small] motor
 * levels (0..255) held until the next RUMBLE. [Pattern.BUZZ]: a fixed notice buzz (a Performance
 * record ended), motor levels unused.
 */
data class Rumble(val large: Int, val small: Int, val pattern: Int = Pattern.MOTORS) : WirePacket {
    object Pattern {
        const val MOTORS = 0
        const val BUZZ = 1
    }
}

/**
 * What each key of [USAGES] types on the PC's current keyboard layout: one UTF-16 character each
 * (0 = none), as Windows maps the key's scan code there. [layout]: the layout's id, for logs.
 */
class KeyLabels(val layout: Int, val chars: IntArray) : WirePacket {
    /** The labels by usage: the character, upper case; keys that type nothing printable are left out. */
    fun labels(): Map<Int, String> = buildMap {
        USAGES.forEachIndexed { i, u ->
            val c = chars.getOrElse(i) { 0 }
            if (c > 0x20 && c != 0x7F && c !in 0xD800..0xDFFF) put(u, c.toChar().uppercase())
        }
    }

    override fun equals(other: Any?) = other is KeyLabels && layout == other.layout && chars.contentEquals(other.chars)
    override fun hashCode() = 31 * layout + chars.contentHashCode()

    companion object {
        /** The character keys, in wire order: letters, digits, punctuation (no Non-US #, which is \\ on Windows), Non-US \\. */
        val USAGES: List<Int> = (0x04..0x27) + (0x2D..0x31) + (0x33..0x38) + 0x64
    }
}

data class HostBye(val reason: Int) : WirePacket

/** Reserved in v1: the host does not send it yet. */
data class Foreground(val exe: String) : WirePacket
