package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.hid.HidUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire v1 golden vectors shared with the Windows companion (protocol/v1/wire-v1.json). */
class GoldenVectorsTest {
    private val root: JsonObject = run {
        val text = javaClass.getResourceAsStream("/protocol/v1/wire-v1.json")!!.readBytes().toString(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject
    }
    private val pairing = root["pairing"]!!.jsonObject
    private val session = root["session"]!!.jsonObject
    private val packets = root["packets"]!!.jsonArray.map { it.jsonObject }
    private val pairKey = hex(session.str("pairKey"))
    private val keys = SessionKeys.derive(pairKey, hex(session.str("nP")), hex(session.str("nH")), session.int("sessionId"))

    @Test fun pairingMath() {
        val kpP = P256.fromPrivateScalar(hex(pairing.str("phonePrivD")))
        val kpH = P256.fromPrivateScalar(hex(pairing.str("hostPrivD")))
        val pkP = P256.encodePublic(kpP.public)
        val pkH = P256.encodePublic(kpH.public)
        assertEquals(pairing.str("pkP"), pkP.hex())
        assertEquals(pairing.str("pkH"), pkH.hex())
        val z = P256.ecdh(kpP.private, P256.decodePublic(pkH)!!)
        assertEquals(pairing.str("ecdhZ"), z.hex())
        assertEquals(pairing.str("ecdhZ"), P256.ecdh(kpH.private, P256.decodePublic(pkP)!!).hex())

        val nP = hex(pairing.str("nP"))
        assertEquals(pairing.str("commit"), PairingMath.commit(pkP, nP).hex())
        val th = PairingMath.th(
            hex(pairing.str("phoneId")), hex(pairing.str("hostId")), pkP, pkH, nP, hex(pairing.str("nH")),
            pairing.int("attemptId"),
        )
        assertEquals(pairing.str("th"), th.hex())
        assertEquals(pairing.str("sas"), PairingMath.sas(th))
        assertEquals("400 588", PairingMath.formatSas(PairingMath.sas(th)))
        val pk = PairingMath.pairKey(z, th)
        assertEquals(pairing.str("pairKey"), pk.hex())
        assertEquals(pairing.str("confirmP"), PairingMath.confirmP(pk, th).hex())
        assertEquals(pairing.str("confirmH"), PairingMath.confirmH(pk, th).hex())
    }

    @Test fun sessionKeys() {
        assertEquals(session.str("kPH"), keys.kPH.hex())
        assertEquals(session.str("kHP"), keys.kHP.hex())
        assertEquals(session.str("okm"), (keys.kPH + keys.kHP).hex())
    }

    @Test fun everyUnsealedPacketEncodesAndDecodes() {
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        var checked = 0
        for (v in packets.filter { "nonce" !in it }) {
            val name = v.str("name")
            val f = v["fields"]!!.jsonObject
            val p = unsealedFromFields(name, f)
            val len = when (p) {
                is Hello -> WireCodec.encodeHello(p, pairKey, buf)
                is Welcome -> WireCodec.encodeWelcome(p, pairKey, buf)
                else -> encodePlain(p, buf)
            }
            assertEquals(name, v.int("len"), len)
            assertEquals(name, v.str("hex"), buf.hex(len))

            val wire = hex(v.str("hex"))
            val decoded = WireCodec.decodePlain(wire, wire.size)
            assertNotNull(name, decoded)
            assertPacketEquals(name, p, decoded!!)
            if (p is Hello || p is Welcome) assertTrue(name, Crypto.verifyTrailingMac(pairKey, wire, wire.size))
            checked++
        }
        assertEquals(11, checked)
    }

    @Test fun everySealedPacketSealsAndOpens() {
        val plain = ByteArray(Wire.MAX_DATAGRAM)
        val out = ByteArray(Wire.MAX_DATAGRAM)
        var checked = 0
        for (v in packets.filter { "nonce" in it }) {
            val name = v.str("name")
            val f = v["fields"]!!.jsonObject
            val p2h = v.str("dir") == "P2H"
            val key = if (p2h) keys.kPH else keys.kHP
            assertEquals(name, session.str(v.str("key")), key.hex())
            val dir = if (p2h) Wire.DIR_P2H else Wire.DIR_H2P
            val sid = v.int("sessionId")
            val seq = v.int("seq")

            val nonce = ByteArray(12).also { it.putU32(0, dir); it.putU32(4, sid); it.putU32(8, seq) }
            assertEquals(name, v.str("nonce"), nonce.hex())

            val p = sealedFromFields(name, f)
            val plainLen = WireCodec.encodeSealedPlain(p, plain)
            assertEquals(name, v.str("plaintext"), plain.hex(plainLen))

            val header = ByteArray(Wire.HEADER)
            WireCodec.writeHeader(header, WireCodec.sealedTypeOf(p), sid, seq)
            val len = SessionCipher(key, dir).seal(header, plain, plainLen, out)
            assertEquals(name, v.int("len"), len)
            assertEquals(name, v.str("hex"), out.hex(len))

            val wire = hex(v.str("hex"))
            val type = WireCodec.classify(wire, wire.size)
            assertEquals(name, WireCodec.sealedTypeOf(p), type)
            val opened = ByteArray(Wire.MAX_DATAGRAM)
            assertTrue(name, SessionCipher(key, dir).open(wire, wire.size, opened))
            assertEquals(name, v.str("plaintext"), opened.hex(plainLen))
            val decoded = WireCodec.decodeSealedPlain(type, opened, 0, plainLen)
            assertNotNull(name, decoded)
            assertPacketEquals(name, p, decoded!!)
            checked++
        }
        assertEquals(10, checked)
    }

    @Test fun inlineSpecVectors() {
        // V1/V2 from the spec text, as a guard that the JSON and spec agree.
        val hello = packets.first { it.str("name") == "HELLO" }.str("hex")
        assertTrue(hello.endsWith("2a1e9e32ae2781bcad7aa6787bf6ca0c"))
        val error = packets.first { it.str("name") == "ERROR" }.str("hex")
        assertEquals("4750017f000000000000000002000100", error)
    }

    @Test fun invalidPacketsAreRejected() {
        val invalid = root["invalid"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, invalid.size)
        val plain = ByteArray(Wire.MAX_DATAGRAM)
        for (v in invalid) {
            val name = v.str("name")
            val wire = hex(v.str("hex"))
            assertFalse(name, acceptedFromPhone(wire, plain))
        }
        // Sanity: the untampered STATE_busy is accepted by the same receiver.
        val good = hex(packets.first { it.str("name") == "STATE_busy" }.str("hex"))
        assertTrue(acceptedFromPhone(good, plain))
    }

    @Test fun keyboardUsagesMatchHidUsageNames() {
        val usages = root["keyboardUsages"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(116, usages.size)
        assertEquals(HidUsage.names.keys.sorted(), usages)
    }

    @Test fun phoneAcceptsHostSealedVectorsInOrder() {
        val wireSession = LiveSession(
            session.int("sessionId"), hex(pairing.str("hostId")), "PC", keys.kPH, keys.kHP,
            java.net.InetSocketAddress("127.0.0.1", Wire.PORT), 1, 1, 20,
        )
        val h2p = packets.filter { it["dir"]?.jsonPrimitive?.content == "H2P" }
        for (v in h2p) {
            val wire = hex(v.str("hex"))
            assertNotNull(v.str("name"), wireSession.openFromHost(wire, wire.size))
        }
        // Replays are dropped (seq not above the highest accepted).
        val first = hex(h2p.first().str("hex"))
        assertNull(wireSession.openFromHost(first, first.size))
    }

    /** A host-side receiver for P->H traffic (validation order of section 1.7). */
    private fun acceptedFromPhone(wire: ByteArray, plain: ByteArray): Boolean {
        val type = WireCodec.classify(wire, wire.size)
        if (type < 0) return false
        if (type == PacketType.HELLO) return Crypto.verifyTrailingMac(pairKey, wire, wire.size)
        if (!PacketType.isSealed(type) || !PacketType.isPhoneToHost(type)) return WireCodec.decodePlain(wire, wire.size) != null
        if (WireCodec.headerSessionId(wire) != session.int("sessionId")) return false
        return SessionCipher(keys.kPH, Wire.DIR_P2H).open(wire, wire.size, plain)
    }

    private fun encodePlain(p: WirePacket, out: ByteArray): Int = when (p) {
        is Discover -> WireCodec.encodeDiscover(p, out)
        is Announce -> WireCodec.encodeAnnounce(p, out)
        is PairStart -> WireCodec.encodePairStart(p, out)
        is PairHost -> WireCodec.encodePairHost(p, out)
        is PairReveal -> WireCodec.encodePairReveal(p, out)
        is PairAck -> WireCodec.encodePairAck(p, out)
        is PairConfirm -> WireCodec.encodePairConfirm(p, out)
        is PairDone -> WireCodec.encodePairDone(p, out)
        is ErrorPacket -> WireCodec.encodeError(p, out)
        else -> error("not unsealed: $p")
    }

    private fun unsealedFromFields(name: String, f: JsonObject): WirePacket = when (name) {
        "DISCOVER" -> Discover(f.u32("reqId"), f.bytes("phoneId"), f.int("flags"))
        "ANNOUNCE" -> Announce(f.u32("reqId"), f.bytes("hostId"), f.int("flags"), f.str("name"))
        "PAIR_START" -> PairStart(f.bytes("phoneId"), f.bytes("pkP"), f.bytes("commit"), f.str("name"))
        "PAIR_HOST" -> PairHost(f.u32("attemptId"), f.bytes("hostId"), f.bytes("pkH"), f.bytes("nH"), f.str("name"))
        "PAIR_REVEAL" -> PairReveal(f.u32("attemptId"), f.bytes("phoneId"), f.bytes("nP"))
        "PAIR_ACK" -> PairAck(f.u32("attemptId"), f.int("status"))
        "PAIR_CONFIRM" -> PairConfirm(f.u32("attemptId"), f.bytes("phoneId"), f.bytes("confirmP"))
        "PAIR_DONE" -> PairDone(f.u32("attemptId"), f.int("status"), f.bytes("confirmH"))
        "HELLO" -> Hello(f.bytes("phoneId"), f.bytes("nP"), f.int("caps"), f.int("maxRateHz"))
        "WELCOME" -> Welcome(
            f.u32("sessionId"), f.bytes("hostId"), f.bytes("nP"), f.bytes("nH"),
            f.int("outputMode"), f.int("hostFlags"), f.int("keepaliveMs"),
        )
        "ERROR" -> ErrorPacket(f.int("code"), f.int("maxVersion"))
        else -> error("unknown vector $name")
    }

    private fun sealedFromFields(name: String, f: JsonObject): WirePacket = when {
        name.startsWith("STATE") -> StateFrame().apply {
            phoneTimeUs = f.u32("phoneTimeUs")
            flags = f.int("flags")
            buttons = f.int("buttons")
            hat = f.int("hat")
            mouseButtons = f.int("mouseButtons")
            lx = f.int("lx"); ly = f.int("ly"); rx = f.int("rx"); ry = f.int("ry")
            lt = f.int("lt"); rt = f.int("rt")
            for (k in f["keys"]!!.jsonArray) setKey(k.jsonPrimitive.int)
            keyBitmap[StateFrame.MODIFIER_BYTE] = f.int("modifiers").toByte()
            mouseTotalX = f.u32("mouseTotalX"); mouseTotalY = f.u32("mouseTotalY"); wheelTotal = f.u32("wheelTotal")
        }
        name == "PING" -> Ping(f.long("t1"))
        name == "PONG" -> Pong(f.long("t1"), f.long("t2"), f.long("t3"))
        name == "LINK_INFO" -> LinkInfo(
            f.long("clockOffsetUs"), f.u32("rttMinUs"), f.int("sendRateHz"), f.int("rssiDbm"), f.int("band"),
            f.int("linkMbps"), f.int("freqMhz"), f.int("flags"),
        )
        name == "BYE" -> Bye(f.int("reason"))
        name == "HOST_BYE" -> HostBye(f.int("reason"))
        name == "HOST_STATUS" -> HostStatus(
            f.u32("lastSeq"), f.int("rxPerSec"), f.int("lossPermille"), f.u32("latP50Us"), f.u32("latP99Us"),
            f.u32("jitterUs"), f.int("outputMode"), f.int("flags"),
        )
        name == "RUMBLE" -> Rumble(f.int("large"), f.int("small"))
        else -> error("unknown vector $name")
    }

    /** Field-by-field comparison (data classes with ByteArray fields compare arrays by identity). */
    private fun assertPacketEquals(name: String, expected: WirePacket, actual: WirePacket) {
        assertEquals(name, expected.javaClass, actual.javaClass)
        if (expected is StateFrame) {
            val a = actual as StateFrame
            assertTrue(name, expected.discreteEquals(a) && expected.totalsEqual(a))
            assertEquals(name, expected.phoneTimeUs, a.phoneTimeUs)
            assertEquals(name, expected.flags, a.flags)
            return
        }
        for (m in expected.javaClass.methods.filter { it.name.startsWith("component") }) {
            val e = m.invoke(expected)
            val a = m.invoke(actual)
            if (e is ByteArray) assertArrayEquals("$name.${m.name}", e, a as ByteArray) else assertEquals("$name.${m.name}", e, a)
        }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.long.toInt()
    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
    /** A u32 field as its 32-bit pattern. */
    private fun JsonObject.u32(k: String) = this[k]!!.jsonPrimitive.long.toInt()
    private fun JsonObject.bytes(k: String) = hex(str(k))
}
