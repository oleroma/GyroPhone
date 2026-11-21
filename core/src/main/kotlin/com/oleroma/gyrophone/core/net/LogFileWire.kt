package com.oleroma.gyrophone.core.net

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's Diagnostics save, fetched by the PC over its own TCP connection (docs/spec/protocol.md,
 * "Diagnostics file"), apart from the input packets: the PC connects to the phone's [PORT], sends a
 * [REQUEST]-byte request with a mac under the session's log key, and gets a short head ([HEAD] bytes,
 * with a status) and, when the status is [OK], the zipped file in sealed frames:
 * `u32le len || AES-128-GCM(logKey, nonce = reqId(8) || u32le(index))` (len counts the tag). Frame 0 is the
 * file's meta (u32 raw size, u32 zipped size, str8 name), the data frames follow, each at most [FRAME_MAX]
 * bytes, and an empty frame ends it, so a cut-off transfer never passes for a whole one.
 */
object LogFileWire {
    /** TCP, on the phone; the same number as the UDP link's port. */
    const val PORT = Wire.PORT
    const val VERSION = 1
    const val KIND_NEWEST = 1
    const val REQUEST = 40
    const val HEAD = 8
    const val MAC = 16
    const val FRAME_MAX = 16 * 1024

    const val OK = 0
    /** No save new enough yet (the phone is still writing it): ask again in a moment. */
    const val NOT_YET = 1
    /** Bad mac, or not this session. */
    const val REFUSED = 2
    /** Diagnostics off on the phone, or no session. */
    const val OFF = 3

    private val MAGIC = byteArrayOf('G'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /** `HKDF(kPH, salt = none, info = "GP-log-file-v1" || u32le(sid), 16)`: never used on the input link. */
    fun logKey(kPH: ByteArray, sessionId: Int): ByteArray =
        Hkdf.derive(kPH, ByteArray(0), Crypto.ascii("GP-log-file-v1") + Crypto.u32le(sessionId), 16)

    class Request(val kind: Int, val maxAgeS: Int, val sessionId: Int, val reqId: ByteArray)

    fun encodeRequest(logKey: ByteArray, r: Request): ByteArray {
        val b = ByteArray(REQUEST)
        MAGIC.copyInto(b, 0)
        b.putU8(4, VERSION)
        b.putU8(5, r.kind)
        b.putU16(6, r.maxAgeS)
        b.putU32(8, r.sessionId)
        r.reqId.copyInto(b, 12, 0, 8)
        Crypto.hmac(logKey, b.copyOf(24)).copyInto(b, 24, 0, MAC)
        return b
    }

    /** The request in [b] when its mac is good under [logKey]; null otherwise. */
    fun decodeRequest(logKey: ByteArray, b: ByteArray): Request? {
        if (b.size < REQUEST || !b.copyOf(4).contentEquals(MAGIC) || b.getU8(4) != VERSION) return null
        val mac = Crypto.hmac(logKey, b.copyOf(24)).copyOf(MAC)
        if (!Crypto.ctEquals(mac, b.copyOfRange(24, 24 + MAC))) return null
        return Request(b.getU8(5), b.getU16(6), b.getU32(8).toInt(), b.copyOfRange(12, 20))
    }

    /** The session id a request names (read before its mac is checked, to pick the key). */
    fun requestSessionId(b: ByteArray): Int = if (b.size >= 12) b.getU32(8).toInt() else 0

    fun head(status: Int): ByteArray = ByteArray(HEAD).also {
        MAGIC.copyInto(it, 0)
        it.putU8(4, VERSION)
        it.putU8(5, status)
    }

    /** The status in a reply's head, or -1 when it isn't one. */
    fun headStatus(b: ByteArray): Int =
        if (b.size < HEAD || !b.copyOf(4).contentEquals(MAGIC) || b.getU8(4) != VERSION) -1 else b.getU8(5)

    /** Frame 0's plaintext. */
    fun meta(rawSize: Int, zipSize: Int, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val b = ByteArray(9 + n.size)
        b.putU32(0, rawSize)
        b.putU32(4, zipSize)
        b.putU8(8, n.size)
        n.copyInto(b, 9)
        return b
    }

    class Meta(val rawSize: Long, val zipSize: Long, val name: String)

    fun decodeMeta(b: ByteArray): Meta? {
        if (b.size < 9) return null
        val n = b.getU8(8)
        if (b.size < 9 + n) return null
        return Meta(b.getU32(0), b.getU32(4), String(b, 9, n, Charsets.UTF_8))
    }

    /** Seals frame [index]'s plaintext: `u32le len || ciphertext || tag`. */
    fun sealFrame(logKey: ByteArray, reqId: ByteArray, index: Int, plain: ByteArray, off: Int = 0, len: Int = plain.size): ByteArray {
        val c = cipher(Cipher.ENCRYPT_MODE, logKey, reqId, index)
        val out = ByteArray(4 + len + Wire.GCM_TAG)
        out.putU32(0, len + Wire.GCM_TAG)
        c.doFinal(plain, off, len, out, 4)
        return out
    }

    /** Opens frame [index]'s sealed bytes (without the length); null on a bad tag. */
    fun openFrame(logKey: ByteArray, reqId: ByteArray, index: Int, sealed: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, logKey, reqId, index).doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        null
    }

    private fun cipher(mode: Int, logKey: ByteArray, reqId: ByteArray, index: Int): Cipher {
        val nonce = ByteArray(12)
        reqId.copyInto(nonce, 0, 0, 8)
        nonce.putU32(8, index)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(logKey, "AES"), GCMParameterSpec(Wire.GCM_TAG * 8, nonce))
        }
    }
}
