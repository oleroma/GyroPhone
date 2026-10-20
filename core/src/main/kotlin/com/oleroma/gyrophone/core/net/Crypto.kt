package com.oleroma.gyrophone.core.net

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Only JDK providers (javax.crypto, java.security), which Android has as well.

/** Shared helpers: hashing, HMAC and constant-time comparison. */
object Crypto {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun ctEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    fun u32le(v: Int): ByteArray = ByteArray(4).also { it.putU32(0, v) }

    /** `HMAC(pairKey, bytes[0..len-16))[0..16]`, the HELLO / WELCOME mac. */
    fun helloMac(pairKey: ByteArray, buf: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairKey, "HmacSHA256"))
        mac.update(buf, 0, len - Wire.HELLO_MAC)
        return mac.doFinal().copyOf(Wire.HELLO_MAC)
    }

    /** Writes the mac into the last 16 bytes of a [len]-byte HELLO or WELCOME. */
    fun writeTrailingMac(pairKey: ByteArray, buf: ByteArray, len: Int) {
        helloMac(pairKey, buf, len).copyInto(buf, len - Wire.HELLO_MAC)
    }

    /** Checks the trailing mac of a [len]-byte HELLO or WELCOME in constant time. */
    fun verifyTrailingMac(pairKey: ByteArray, buf: ByteArray, len: Int): Boolean {
        if (len < Wire.HELLO_MAC || len > buf.size) return false
        return ctEquals(helloMac(pairKey, buf, len), buf.copyOfRange(len - Wire.HELLO_MAC, len))
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    private val secureRandom by lazy { SecureRandom() }
}

/** HKDF-SHA256 (RFC 5869): extract then expand. */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = Crypto.hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = Crypto.hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }
}

/** ECDH on P-256. Public keys travel as uncompressed SEC1: `04 || X(32 BE) || Y(32 BE)`. */
object P256 {
    private val p = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val a = p - BigInteger.valueOf(3)
    private val b = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val gx = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val gy = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    private val n = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    /** A fresh ephemeral key pair from the platform provider. */
    fun generate(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    /**
     * Key pair from a fixed 32-byte private scalar, for golden vectors and deterministic tests.
     * The public point is computed with plain (not constant-time) BigInteger arithmetic, so
     * production code uses [generate].
     */
    fun fromPrivateScalar(d: ByteArray): KeyPair {
        val s = BigInteger(1, d)
        require(s.signum() > 0 && s < n) { "private scalar out of range" }
        val (x, y) = multiplyG(s)
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        val priv = kf.generatePrivate(ECPrivateKeySpec(s, params))
        return KeyPair(pub, priv)
    }

    fun encodePublic(key: java.security.PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        val out = ByteArray(Wire.PUBLIC_KEY_SIZE)
        out[0] = 0x04
        toFixed(w.affineX).copyInto(out, 1)
        toFixed(w.affineY).copyInto(out, 33)
        return out
    }

    /** Parses and validates (on the curve, coordinates in range) a SEC1 public key, or null. */
    fun decodePublic(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != Wire.PUBLIC_KEY_SIZE || bytes[0] != 0x04.toByte()) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        if (x >= p || y >= p) return null
        if (y.multiply(y).mod(p) != x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p)) return null
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        } catch (e: Exception) {
            null
        }
    }

    /** Shared secret Z: the 32-byte X coordinate of d*Q. */
    fun ecdh(priv: java.security.PrivateKey, pub: java.security.PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val z = ka.generateSecret()
        return if (z.size == 32) z else toFixed(BigInteger(1, z))
    }

    private fun toFixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    // Affine double-and-add; only used by fromPrivateScalar.
    private fun multiplyG(k: BigInteger): Pair<BigInteger, BigInteger> {
        var rx: BigInteger? = null
        var ry: BigInteger? = null
        for (i in k.bitLength() - 1 downTo 0) {
            if (rx != null) {
                val d = double(rx, ry!!)
                rx = d.first; ry = d.second
            }
            if (k.testBit(i)) {
                if (rx == null) {
                    rx = gx; ry = gy
                } else {
                    val s = add(rx, ry!!, gx, gy)
                    rx = s.first; ry = s.second
                }
            }
        }
        return rx!! to ry!!
    }

    private fun double(x: BigInteger, y: BigInteger): Pair<BigInteger, BigInteger> {
        val l = x.multiply(x).multiply(BigInteger.valueOf(3)).add(a)
            .multiply(y.shiftLeft(1).modInverse(p)).mod(p)
        val x3 = l.multiply(l).subtract(x.shiftLeft(1)).mod(p)
        val y3 = l.multiply(x.subtract(x3)).subtract(y).mod(p)
        return x3 to y3
    }

    private fun add(x1: BigInteger, y1: BigInteger, x2: BigInteger, y2: BigInteger): Pair<BigInteger, BigInteger> {
        if (x1 == x2) return if (y1 == y2) double(x1, y1) else error("point at infinity")
        val l = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p)
        val x3 = l.multiply(l).subtract(x1).subtract(x2).mod(p)
        val y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(p)
        return x3 to y3
    }
}

/** Pairing math of wire v1 section 1.5 (commit/reveal numeric comparison). */
object PairingMath {
    fun commit(pkP: ByteArray, nP: ByteArray): ByteArray = Crypto.sha256(Crypto.ascii("GP-commit-v1"), pkP, nP)

    fun th(
        phoneId: ByteArray,
        hostId: ByteArray,
        pkP: ByteArray,
        pkH: ByteArray,
        nP: ByteArray,
        nH: ByteArray,
        attemptId: Int,
    ): ByteArray = Crypto.sha256(Crypto.ascii("GP-pair-v1"), phoneId, hostId, pkP, pkH, nP, nH, Crypto.u32le(attemptId))

    /** The six-digit code, zero-padded, without the display space ("400588"). */
    fun sas(th: ByteArray): String {
        val h = Crypto.sha256(Crypto.ascii("GP-sas-v1"), th)
        return "%06d".format(h.getU32(0) % 1_000_000L)
    }

    /** "400588" -> "400 588". */
    fun formatSas(sas: String): String = if (sas.length == 6) sas.substring(0, 3) + " " + sas.substring(3) else sas

    fun pairKey(z: ByteArray, th: ByteArray): ByteArray = Hkdf.derive(z, th, Crypto.ascii("GP-pair-key-v1"), 32)

    fun confirmP(pairKey: ByteArray, th: ByteArray): ByteArray = Crypto.hmac(pairKey, Crypto.ascii("GP-confirm-P"), th)

    fun confirmH(pairKey: ByteArray, th: ByteArray): ByteArray = Crypto.hmac(pairKey, Crypto.ascii("GP-confirm-H"), th)
}

/** Per-session AES-128 keys: kPH for phone-to-host, kHP for host-to-phone. */
class SessionKeys(val kPH: ByteArray, val kHP: ByteArray) {
    companion object {
        fun derive(pairKey: ByteArray, nP: ByteArray, nH: ByteArray, sessionId: Int): SessionKeys {
            val okm = Hkdf.derive(pairKey, nP + nH, Crypto.ascii("GP-session-v1") + Crypto.u32le(sessionId), 32)
            return SessionKeys(okm.copyOfRange(0, 16), okm.copyOfRange(16, 32))
        }
    }
}
