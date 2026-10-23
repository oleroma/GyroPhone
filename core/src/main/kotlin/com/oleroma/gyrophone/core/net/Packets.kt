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
) : WirePacket

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
) : WirePacket

data class Rumble(val large: Int, val small: Int) : WirePacket

data class HostBye(val reason: Int) : WirePacket

/** Reserved in v1: the host does not send it yet. */
data class Foreground(val exe: String) : WirePacket
