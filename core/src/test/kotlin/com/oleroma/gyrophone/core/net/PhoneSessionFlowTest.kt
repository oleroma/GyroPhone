package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class PhoneSessionFlowTest {
    private val phoneId = ByteArray(16) { it.toByte() }
    private val hostId = ByteArray(16) { (0x10 + it).toByte() }
    private val pairKey = ByteArray(32) { 0x5A }
    private val pcAddr = InetSocketAddress("192.168.1.20", Wire.PORT)
    private val bcast = listOf(InetSocketAddress("255.255.255.255", Wire.PORT), InetSocketAddress("192.168.1.255", Wire.PORT))
    private val buf = ByteArray(Wire.MAX_DATAGRAM)

    private fun flow(addr: InetSocketAddress? = pcAddr) =
        PhoneSessionFlow(phoneId, PairedHost(hostId, "PC", pairKey, addr), { bcast }, random = CountingRandom())

    private fun sends(out: List<PhoneSessionFlow.Out>) = out.filterIsInstance<PhoneSessionFlow.Out.Send>()
    private fun hellos(out: List<PhoneSessionFlow.Out>): List<Hello> = sends(out).mapNotNull {
        val p = WireCodec.decodePlain(it.bytes, it.bytes.size)
        if (p is Hello) {
            assertTrue(Crypto.verifyTrailingMac(pairKey, it.bytes, it.bytes.size))
            p
        } else null
    }
    private fun discovers(out: List<PhoneSessionFlow.Out>) =
        sends(out).filter { WireCodec.decodePlain(it.bytes, it.bytes.size) is Discover }

    private fun welcome(nP: ByteArray, sid: Int, key: ByteArray = pairKey): ByteArray {
        val n = WireCodec.encodeWelcome(
            Welcome(sid, hostId, nP, ByteArray(16) { 0x33 }, OutputMode.XBOX360, HostFlags.VIGEM_OK, 20), key, buf,
        )
        return buf.copyOf(n)
    }

    private fun PhoneSessionFlow.deliver(bytes: ByteArray, now: Long, from: InetSocketAddress = pcAddr) =
        onDatagram(bytes, bytes.size, from, now)

    private fun hostSealed(p: WirePacket, s: LiveSession, seq: Int): ByteArray {
        val keys = SessionKeys.derive(pairKey, lastNp!!, ByteArray(16) { 0x33 }, s.sessionId)
        val plain = ByteArray(Wire.MAX_DATAGRAM)
        val n = WireCodec.encodeSealedPlain(p, plain)
        val header = ByteArray(12).also { WireCodec.writeHeader(it, WireCodec.sealedTypeOf(p), s.sessionId, seq) }
        val out = ByteArray(Wire.MAX_DATAGRAM)
        return out.copyOf(SessionCipher(keys.kHP, Wire.DIR_H2P).seal(header, plain, n, out))
    }

    private var lastNp: ByteArray? = null

    private fun goLive(f: PhoneSessionFlow, sid: Int = 0x11223344, now: Long = 0): LiveSession {
        val h = hellos(f.connect(now)).single()
        lastNp = h.nP
        val out = f.deliver(welcome(h.nP, sid), now + 5)
        val live = out.filterIsInstance<PhoneSessionFlow.Out.Live>().single().session!!
        assertEquals(SessionStatus.LIVE, f.status)
        return live
    }

    @Test fun helloRetryAndDiscoveryCadence() {
        val f = flow()
        val helloTimes = ArrayList<Long>()
        val discoverTimes = ArrayList<Long>()
        val nonces = HashSet<String>()
        var t = 0L
        var out = f.connect(0)
        while (true) {
            hellos(out).forEach { helloTimes.add(t); nonces.add(it.nP.hex()) }
            if (discovers(out).isNotEmpty()) { assertEquals(2, discovers(out).size); discoverTimes.add(t) }
            t += 10
            if (t > 3000) break
            out = f.onTick(t)
        }
        assertEquals(listOf(0L, 100, 200, 400, 800, 1800, 2800), helloTimes)
        assertEquals(listOf(500L, 1500, 2500), discoverTimes)
        assertEquals(1, nonces.size) // one nP per connect attempt
        assertEquals(SessionStatus.DISCOVERING, f.status)
    }

    @Test fun unknownAddressDiscoversFirstThenHellosTheAnnouncer() {
        val f = flow(addr = null)
        val out = f.connect(0)
        assertTrue(hellos(out).isEmpty())
        assertEquals(2, discovers(out).size)
        val announcer = InetSocketAddress("10.0.0.7", Wire.PORT)
        val n = WireCodec.encodeAnnounce(Announce(1, hostId, AnnounceFlags.PAIRED_WITH_YOU, "PC"), buf)
        val reply = f.deliver(buf.copyOf(n), 30, announcer)
        assertEquals(PhoneSessionFlow.Out.HostAddress(announcer), reply.filterIsInstance<PhoneSessionFlow.Out.HostAddress>().single())
        assertEquals(announcer, sends(reply).single().addr)
        assertEquals(1, hellos(reply).size)
        // ANNOUNCEs of other PCs are surfaced but do not redirect HELLO.
        val m = WireCodec.encodeAnnounce(Announce(1, ByteArray(16), 0, "Other"), buf)
        val other = f.deliver(buf.copyOf(m), 40, InetSocketAddress("10.0.0.9", Wire.PORT))
        assertTrue(sends(other).isEmpty())
        assertEquals(announcer, f.hostAddr)
    }

    @Test fun welcomeGoesLiveWithDerivedKeys() {
        val f = flow()
        val live = goLive(f)
        assertEquals(0x11223344, live.sessionId)
        val expected = SessionKeys.derive(pairKey, lastNp!!, ByteArray(16) { 0x33 }, 0x11223344)
        assertArrayEquals(expected.kPH, live.kPH)
        assertEquals(OutputMode.XBOX360, live.outputMode)
        assertTrue(hellos(f.onTick(1000)).isEmpty()) // retries stopped
    }

    @Test fun welcomeWithMismatchedEchoOrBadMacIsIgnored() {
        val f = flow()
        val h = hellos(f.connect(0)).single()
        val wrongEcho = h.nP.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(f.deliver(welcome(wrongEcho, 5), 10).isEmpty())
        assertTrue(f.deliver(welcome(h.nP, 5, key = ByteArray(32)), 10).isEmpty())
        val tampered = welcome(h.nP, 5).also { it[61] = 0 }
        assertTrue(f.deliver(tampered, 10).isEmpty())
        assertNull(f.live)
        assertNotNull(f.deliver(welcome(h.nP, 5), 10).filterIsInstance<PhoneSessionFlow.Out.Live>().singleOrNull())
    }

    @Test fun errorIsAdvisory() {
        val f = flow()
        val live = goLive(f)
        val n = WireCodec.encodeError(ErrorPacket(ErrorCode.NOT_PAIRED), buf)
        val out = f.deliver(buf.copyOf(n), 100)
        assertTrue(out.none { it is PhoneSessionFlow.Out.Live })
        assertSame(live, f.live)
        assertEquals(SessionStatus.LIVE, f.status)

        // SESSION_UNKNOWN: a new HELLO with a fresh nP while the old session keeps sending.
        val m = WireCodec.encodeError(ErrorPacket(ErrorCode.SESSION_UNKNOWN), buf)
        val retry = hellos(f.deliver(buf.copyOf(m), 200)).single()
        assertFalse(retry.nP.contentEquals(lastNp!!))
        assertSame(live, f.live)
    }

    @Test fun notPairedIsReportedWhileConnecting() {
        val f = flow()
        f.connect(0)
        val n = WireCodec.encodeError(ErrorPacket(ErrorCode.NOT_PAIRED), buf)
        f.deliver(buf.copyOf(n), 10)
        assertEquals(SessionStatus.NOT_PAIRED, f.status)
        assertTrue(f.isRunning) // the key is kept and retries go on
    }

    @Test fun notPairedStaysThroughDiscoveryTicks() {
        val f = flow()
        f.connect(0)
        val n = WireCodec.encodeError(ErrorPacket(ErrorCode.NOT_PAIRED), buf)
        f.deliver(buf.copyOf(n), 10)
        var t = 10L
        while (t < 2600) { t += 10; f.onTick(t) }
        assertEquals(SessionStatus.NOT_PAIRED, f.status)
    }

    @Test fun rekeyRunsANewHelloAndSwapsSessions() {
        val f = flow()
        val old = goLive(f)
        old.rekeyRequested = true
        val h = hellos(f.onTick(50)).single()
        assertFalse(h.nP.contentEquals(lastNp!!))
        assertSame(old, f.live)
        lastNp = h.nP
        val next = f.deliver(welcome(h.nP, 0x55667788), 60).filterIsInstance<PhoneSessionFlow.Out.Live>().single().session!!
        assertNotEquals(old.sessionId, next.sessionId)
        assertFalse(next.rekeyRequested)
    }

    @Test fun reconnectAfterNetworkChangeRunsAFreshHelloAndKeepsTheLiveSession() {
        val f = flow()
        val old = goLive(f)
        val h = hellos(f.reconnect(100)).single()
        assertFalse(h.nP.contentEquals(lastNp!!))
        assertSame(old, f.live)
        assertEquals(SessionStatus.LIVE, f.status)
        lastNp = h.nP
        val next = f.deliver(welcome(h.nP, 0x55667788), 110).filterIsInstance<PhoneSessionFlow.Out.Live>().single().session!!
        assertEquals(0x55667788, next.sessionId)
        assertTrue(flow().reconnect(0).isEmpty()) // not running: nothing to do
    }

    @Test fun movingToUsbHellosThereWithoutRememberingTheAddress() {
        val f = flow()
        val old = goLive(f)
        val usb = InetSocketAddress("192.168.42.129", Wire.PORT)
        val out = f.moveTo(usb, 100)
        val send = sends(out).single()
        assertEquals(usb, send.addr)
        assertTrue(out.none { it is PhoneSessionFlow.Out.HostAddress })
        assertSame(old, f.live)
        lastNp = hellos(out).single().nP
        val next = f.deliver(welcome(lastNp!!, 0x0A0B0C0D), 105, from = usb)
        assertTrue(next.none { it is PhoneSessionFlow.Out.HostAddress })
        assertEquals(usb, next.filterIsInstance<PhoneSessionFlow.Out.Live>().single().session!!.hostAddr)
    }

    @Test fun movingBackWithNoAddressDiscoversAtOnce() {
        val f = flow()
        goLive(f)
        val out = f.moveTo(null, 100)
        assertTrue(hellos(out).isEmpty())
        assertEquals(bcast.size, discovers(out).size)
    }

    @Test fun hostSilentStartsANewHelloAndStatusRecovers() {
        val f = flow()
        val live = goLive(f)
        val status = hostSealed(HostStatus(1, 1000, 0, -1, -1, 0, 1, 1), live, 1)
        val got = f.deliver(status, 2000).filterIsInstance<PhoneSessionFlow.Out.Received>().single()
        assertTrue(got.packet is HostStatus)
        assertTrue(f.onTick(4999).isEmpty())
        val out = f.onTick(5000)
        assertEquals(SessionStatus.HOST_SILENT, f.status)
        assertEquals(1, hellos(out).size)
        assertSame(live, f.live)
        f.deliver(hostSealed(Pong(1, 2, 3), live, 2), 5100)
        assertEquals(SessionStatus.LIVE, f.status)
        assertTrue(hellos(f.onTick(5200)).isEmpty())
    }

    @Test fun sealedReplayAndForeignSessionAreDropped() {
        val f = flow()
        val live = goLive(f)
        val pong = hostSealed(Pong(1, 2, 3), live, 7)
        assertEquals(1, f.deliver(pong, 10).count { it is PhoneSessionFlow.Out.Received })
        assertTrue(f.deliver(pong, 11).none { it is PhoneSessionFlow.Out.Received })
        val other = hostSealed(Pong(1, 2, 3), live, 8).also { it[4] = 0 } // sid changed
        assertTrue(f.deliver(other, 12).isEmpty())
    }

    @Test fun authenticatedPacketsMoveTheEndpoint() {
        val f = flow()
        val live = goLive(f)
        val moved = InetSocketAddress("192.168.1.21", Wire.PORT)
        val out = f.deliver(hostSealed(Rumble(1, 2), live, 1), 10, moved)
        assertEquals(moved, live.hostAddr)
        assertTrue(out.contains(PhoneSessionFlow.Out.HostAddress(moved)))
    }

    @Test fun hostByeReplacedEndsTheSession() {
        val f = flow()
        val live = goLive(f)
        val out = f.deliver(hostSealed(HostBye(HostByeReason.REPLACED), live, 1), 10)
        assertTrue(out.contains(PhoneSessionFlow.Out.Live(null)))
        assertEquals(SessionStatus.REPLACED, f.status)
        assertFalse(f.isRunning)
    }

    @Test fun hostByeExitKeepsReconnecting() {
        val f = flow()
        val live = goLive(f)
        val out = f.deliver(hostSealed(HostBye(HostByeReason.EXIT), live, 1), 10)
        assertEquals(SessionStatus.HOST_EXITED, f.status)
        assertTrue(out.contains(PhoneSessionFlow.Out.Live(null)))
        assertEquals(1, hellos(f.onTick(20)).size)
    }

    @Test fun helloOffsets() {
        assertEquals(listOf(0L, 100, 200, 400, 800, 1800, 2800), (0..6).map { PhoneSessionFlow.helloOffsetMs(it) })
    }
}
