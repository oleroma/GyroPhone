package com.oleroma.gyrophone.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Key file pairing vectors shared with the Windows companion (protocol/v1/key-v1.json). */
class KeyPairingTest {
    private val v: JsonObject = run {
        val text = javaClass.getResourceAsStream("/protocol/v1/key-v1.json")!!.readBytes().toString(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject
    }
    private val phone = SoftwareStaticKey(P256.fromPrivateScalar(hex(v.str("phonePrivD"))))
    private val host = SoftwareStaticKey(P256.fromPrivateScalar(hex(v.str("hostPrivD"))))

    @Test fun keyMathMatchesVectors() {
        assertEquals(v.str("pkP"), phone.publicKey.hex())
        assertEquals(v.str("pkH"), host.publicKey.hex())
        val ss = phone.agree(P256.decodePublic(host.publicKey)!!)
        assertEquals(v.str("ss"), ss.hex())
        assertEquals(v.str("ss"), host.agree(P256.decodePublic(phone.publicKey)!!).hex())
        assertEquals(v.str("salt"), KeyPairing.salt(phone.publicKey, host.publicKey).hex())
        assertEquals(v.str("pairKey"), KeyPairing.pairKey(ss, phone.publicKey, host.publicKey).hex())
        assertEquals(v.str("keyPhoneId"), KeyPairing.keyPhoneId(phone.publicKey, host.publicKey).hex())
        assertEquals(v.str("phoneKeyId"), KeyPairing.keyId(phone.publicKey).hex())
        assertEquals(v.str("hostKeyId"), KeyPairing.keyId(host.publicKey).hex())
        assertEquals(v.str("phoneFingerprint"), KeyPairing.fingerprint(phone.publicKey))
        assertEquals(v.str("hostFingerprint"), KeyPairing.fingerprint(host.publicKey))

        val (id, key) = KeyPairing.phoneSecrets(phone, host.publicKey)!!
        assertEquals(v.str("keyPhoneId"), id.hex())
        assertEquals(v.str("pairKey"), key.hex())
    }

    @Test fun keyFilesRoundTrip() {
        assertEquals(v.str("phoneFile") + "\n", PubKeyFile(phone.publicKey, "OnePlus 15").format())
        val parsed = PubKeyFile.parse(v.str("hostFile"))!!
        assertArrayEquals(host.publicKey, parsed.publicKey)
        assertEquals("OLEX", parsed.name)
        // Windows line endings, a leading blank line and no name are fine.
        val crlf = PubKeyFile.parse("\r\n" + v.str("phoneFile").substringBeforeLast(' ').substringBeforeLast(' ') + "\r\n")
        assertNotNull(crlf)
        assertEquals("", crlf!!.name)
    }

    @Test fun badKeyFilesAreRejected() {
        assertNull(PubKeyFile.parse(""))
        assertNull(PubKeyFile.parse("ssh-ed25519 AAAA me"))
        assertNull(PubKeyFile.parse("gyrophone-p256 not-base64!! x"))
        // A point that is not on the curve.
        val off = phone.publicKey.copyOf().also { it[64] = (it[64] + 1).toByte() }
        assertNull(PubKeyFile.parse(PubKeyFile(off, "x").format()))
    }

    @Test fun discoverCarriesKeyIds() {
        val d = v["discover"]!!.jsonObject
        val p = Discover(d.long("reqId").toInt(), hex(d.str("phoneId")), 0, hex(d.str("hostKeyId")), hex(d.str("phoneKeyId")))
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        val n = WireCodec.encodeDiscover(p, buf)
        assertEquals(d.str("hex"), buf.hex(n))
        val back = WireCodec.decodePlain(buf, n) as Discover
        assertEquals(d.str("hostKeyId"), back.hostKeyId.hex())
        assertEquals(d.str("phoneKeyId"), back.phoneKeyId.hex())
    }

    @Test fun announceFlagsGiveTheStatus() {
        assertEquals(KeyStatus.READY, KeyPairing.status(AnnounceFlags.KEY_MATCH or AnnounceFlags.KNOWS_YOUR_KEY or AnnounceFlags.VIGEM_OK))
        assertEquals(KeyStatus.PC_LACKS_PHONE, KeyPairing.status(AnnounceFlags.KEY_MATCH))
        assertEquals(KeyStatus.PHONE_LACKS_PC, KeyPairing.status(AnnounceFlags.KNOWS_YOUR_KEY))
        assertEquals(KeyStatus.NONE, KeyPairing.status(AnnounceFlags.PAIRED_WITH_YOU))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("OnePlus 15.pub", KeyPairing.fileName("OnePlus 15"))
        assertEquals("a_b_c.pub", KeyPairing.fileName("a/b\\c"))
        assertEquals("key.pub", KeyPairing.fileName(".."))
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
}
