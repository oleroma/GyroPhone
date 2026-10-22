package com.oleroma.gyrophone.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** QR code pairing vectors shared with the Windows companion (protocol/v1/qr-v1.json). */
class QrPairingTest {
    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResourceAsStream("/protocol/v1/$name")!!.readBytes().toString(Charsets.UTF_8)).jsonObject

    private val k = load("key-v1.json")
    private val v = load("qr-v1.json")
    private val pkP = hex(k.str("pkP"))
    private val pkH = hex(k.str("pkH"))
    private val token = hex(v.str("token"))
    private val reqId = v["reqId"]!!.jsonPrimitive.long.toInt()
    private val addresses = v["addresses"]!!.jsonArray.map { it.jsonPrimitive.content }
    private val bcast = InetSocketAddress(InetAddress.getByName("192.168.1.255"), Wire.PORT)

    private fun invite() = QrInvite(pkH, token, v.str("hostName"), addresses, Wire.PORT)
    private fun flow() = QrEnrollFlow(invite(), pkP, v.str("phoneName"), { listOf(bcast) }, reqId)

    @Test fun payloadMatchesVector() {
        assertEquals(v.str("payload"), QrPairing.format(invite()))
        val p = QrPairing.parse(v.str("payload"))!!
        assertArrayEquals(pkH, p.hostKey)
        assertArrayEquals(token, p.token)
        assertEquals(v.str("hostName"), p.name)
        assertEquals(addresses, p.addresses)
        assertEquals(Wire.PORT, p.port)
        assertEquals(v.str("tokenId"), QrPairing.tokenId(token).hex())
    }

    @Test fun badPayloadsAreRejected() {
        val good = v.str("payload")
        assertNull(QrPairing.parse("https://example.com"))
        assertNull(QrPairing.parse(good.replace("v=1", "v=2")))
        assertNull(QrPairing.parse(good.replace("&t=", "&t=AA")))
        assertNull(QrPairing.parse(good.replace("&k=B", "&k=C")))
        assertNull(QrPairing.parse(good.replace("%C3%BC", "%C")))
        // Unknown fields, a missing port and junk addresses are fine.
        val loose = QrPairing.parse(good.replace("&p=47800", "&x=1").replace("10.0.0.5", "pc.local"))
        assertNotNull(loose)
        assertEquals(listOf("192.168.1.20"), loose!!.addresses)
        assertEquals(Wire.PORT, loose.port)
    }

    @Test fun enrollMatchesVector() {
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        val n = WireCodec.encodeKeyEnroll(reqId, token, pkP, v.str("phoneName"), buf)
        assertEquals(v.str("enroll"), buf.copyOf(n).hex())
        val d = WireCodec.decodePlain(buf, n) as KeyEnroll
        assertEquals(v.str("phoneName"), d.name)
        assertArrayEquals(pkP, d.pkP)

        val ack = ByteArray(Wire.MAX_DATAGRAM)
        val m = WireCodec.encodeKeyEnrollAck(reqId, QrPairing.tokenId(token), EnrollStatus.OK, token, pkP, ack)
        assertEquals(v.str("ackOk"), ack.copyOf(m).hex())
        WireCodec.encodeKeyEnrollAck(reqId, QrPairing.tokenId(token), EnrollStatus.EXPIRED, null, null, ack)
        assertEquals(v.str("ackExpired"), ack.copyOf(m).hex())
    }

    @Test fun flowSendsToTheQrAddressesAndBroadcastUntilAcked() {
        val f = flow()
        val first = f.start(1000).filterIsInstance<QrEnrollFlow.Out.Send>()
        assertEquals(listOf("192.168.1.20", "10.0.0.5", "192.168.1.255"), first.map { it.addr.address.hostAddress })
        assertEquals(v.str("enroll"), first[0].bytes.hex())
        assertTrue(f.onTick(1100).isEmpty())
        assertEquals(3, f.onTick(1000 + Wire.QR_RETX_MS.toLong()).size)

        // A stranger's ACK (wrong mac) and an old request are ignored.
        val forged = hex(v.str("ackOk")).also { it[30] = (it[30] + 1).toByte() }
        assertTrue(f.onDatagram(forged, forged.size).isEmpty())
        val ok = hex(v.str("ackOk"))
        assertTrue(QrEnrollFlow(invite(), pkP, "x", { emptyList() }, reqId + 1).onDatagram(ok, ok.size).isEmpty())

        assertEquals(listOf(QrEnrollFlow.Out.Done(EnrollResult.OK)), f.onDatagram(ok, ok.size))
        assertTrue(f.finished)
        assertTrue(f.onTick(5000).isEmpty())
    }

    @Test fun flowReportsRefusalsAndSilence() {
        val used = hex(v.str("ackUsed"))
        assertEquals(listOf(QrEnrollFlow.Out.Done(EnrollResult.USED)), flow().apply { start(0) }.onDatagram(used, used.size))
        val expired = hex(v.str("ackExpired"))
        assertEquals(listOf(QrEnrollFlow.Out.Done(EnrollResult.EXPIRED)), flow().apply { start(0) }.onDatagram(expired, expired.size))
        val f = flow()
        f.start(0)
        assertEquals(listOf(QrEnrollFlow.Out.Done(EnrollResult.NO_ANSWER)), f.onTick(Wire.QR_GIVE_UP_MS.toLong()))
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
}
