package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

class PairingMathTest {
    private val phoneId = ByteArray(16) { it.toByte() }
    private val hostId = ByteArray(16) { (0x10 + it).toByte() }

    private class Side(val kp: KeyPair, val nonce: ByteArray) {
        val pk: ByteArray = P256.encodePublic(kp.public)
    }

    private fun th(p: ByteArray, h: ByteArray, nP: ByteArray, nH: ByteArray, attempt: Int = 7) =
        PairingMath.th(phoneId, hostId, p, h, nP, nH, attempt)

    @Test fun phoneAndHostAgree() {
        val phone = Side(P256.generate(), Crypto.randomBytes(16))
        val host = Side(P256.generate(), Crypto.randomBytes(16))
        val zP = P256.ecdh(phone.kp.private, P256.decodePublic(host.pk)!!)
        val zH = P256.ecdh(host.kp.private, P256.decodePublic(phone.pk)!!)
        assertArrayEquals(zP, zH)
        val t = th(phone.pk, host.pk, phone.nonce, host.nonce)
        val kP = PairingMath.pairKey(zP, t)
        val kH = PairingMath.pairKey(zH, t)
        assertArrayEquals(kP, kH)
        assertTrue(Crypto.ctEquals(PairingMath.confirmP(kP, t), PairingMath.confirmP(kH, t)))
        assertEquals(6, PairingMath.sas(t).length)
    }

    @Test fun tamperedCommitIsRejected() {
        val pk = P256.encodePublic(P256.generate().public)
        val nP = Crypto.randomBytes(16)
        val commit = PairingMath.commit(pk, nP)
        val other = nP.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(Crypto.ctEquals(commit, PairingMath.commit(pk, other)))
        val bad = commit.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertFalse(Crypto.ctEquals(bad, PairingMath.commit(pk, nP)))
    }

    @Test fun wrongConfirmFails() {
        val t = Crypto.randomBytes(32)
        val k = Crypto.randomBytes(32)
        val wrongKey = k.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(Crypto.ctEquals(PairingMath.confirmP(k, t), PairingMath.confirmP(wrongKey, t)))
        // The two directions never collide, so a reflected confirmP is not a confirmH.
        assertFalse(Crypto.ctEquals(PairingMath.confirmP(k, t), PairingMath.confirmH(k, t)))
    }

    @Test fun invalidPublicKeysAreRejected() {
        val pk = P256.encodePublic(P256.generate().public)
        assertNull(P256.decodePublic(pk.copyOf(64)))
        assertNull(P256.decodePublic(pk.copyOf().also { it[0] = 2 }))
        assertNull(P256.decodePublic(pk.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() })) // off the curve
        assertNull(P256.decodePublic(ByteArray(65).also { it[0] = 4 }))
    }

    /**
     * A man in the middle runs one exchange with each side. Commit/reveal fixes his keys before
     * he sees nP, so the two SAS values are independent; over 1000 seeds they must differ
     * (expected collisions: 1000 / 10^6).
     */
    @Test fun mitmGivesSasMismatch() {
        val rnd = java.util.Random(42)
        fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        val phone = P256.encodePublic(P256.generate().public)
        val host = P256.encodePublic(P256.generate().public)
        val mallory = P256.encodePublic(P256.generate().public)
        var mismatches = 0
        repeat(1000) {
            val nP = bytes(16); val nH = bytes(16); val nM1 = bytes(16); val nM2 = bytes(16)
            val attempt = rnd.nextInt()
            // Phone <-> Mallory (posing as host), Mallory (posing as phone) <-> Host.
            val sasPhone = PairingMath.sas(th(phone, mallory, nP, nM1, attempt))
            val sasHost = PairingMath.sas(th(mallory, host, nM2, nH, attempt))
            if (sasPhone != sasHost) mismatches++
        }
        assertTrue("mismatches=$mismatches", mismatches >= 999)
    }

    @Test fun hkdfMatchesRfc5869Case1() {
        val okm = Hkdf.derive(
            hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"), 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.hex(),
        )
    }

    @Test fun sasFormatting() {
        assertEquals("000 042", PairingMath.formatSas("000042"))
    }
}
