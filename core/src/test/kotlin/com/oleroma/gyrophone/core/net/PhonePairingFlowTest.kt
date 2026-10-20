package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class PhonePairingFlowTest {
    private val phoneId = ByteArray(16) { it.toByte() }
    private val hostId = ByteArray(16) { (0x10 + it).toByte() }
    private val addr = InetSocketAddress("192.168.1.20", Wire.PORT)
    private val attemptId = 0x01020304

    /** The PC side of one attempt, computed with the same math. */
    private inner class FakeHost {
        val kp = P256.fromPrivateScalar(ByteArray(32) { 0x22 })
        val pkH = P256.encodePublic(kp.public)
        val nH = ByteArray(16) { (0xB0 + it).toByte() }
        lateinit var start: PairStart
        lateinit var th: ByteArray
        lateinit var pairKey: ByteArray

        fun host(start: PairStart): PairHost {
            this.start = start
            return PairHost(attemptId, hostId, pkH, nH, "DESKTOP-OLEX")
        }

        fun reveal(r: PairReveal): String {
            assertTrue(Crypto.ctEquals(start.commit, PairingMath.commit(start.pkP, r.nP)))
            th = PairingMath.th(phoneId, hostId, start.pkP, pkH, r.nP, nH, attemptId)
            pairKey = PairingMath.pairKey(P256.ecdh(kp.private, P256.decodePublic(start.pkP)!!), th)
            return PairingMath.sas(th)
        }

        fun done(c: PairConfirm): PairDone {
            assertTrue(Crypto.ctEquals(c.confirmP, PairingMath.confirmP(pairKey, th)))
            return PairDone(attemptId, DoneStatus.ACCEPTED, PairingMath.confirmH(pairKey, th))
        }
    }

    private fun flow() = PhonePairingFlow(
        phoneId, "OnePlus 15", addr, CountingRandom(), { P256.fromPrivateScalar(ByteArray(32) { 0x11 }) },
    )

    private fun sent(out: List<PhonePairingFlow.Out>): List<WirePacket> =
        out.filterIsInstance<PhonePairingFlow.Out.Send>().map {
            assertEquals(addr, it.addr)
            WireCodec.decodePlain(it.bytes, it.bytes.size)!!
        }

    /** Runs START -> HOST -> REVEAL -> ACK OK and returns the SAS both sides computed. */
    private fun toAwaitUser(f: PhonePairingFlow, h: FakeHost): String {
        val start = sent(f.start(0)).single() as PairStart
        assertEquals("OnePlus 15", start.name)
        val reveal = sent(f.onPacket(h.host(start), 10)).single() as PairReveal
        val sas = h.reveal(reveal)
        assertEquals(PairState.GotHost(sas, "DESKTOP-OLEX"), f.state)
        f.onPacket(PairAck(attemptId, AckStatus.OK), 20)
        assertEquals(PairState.AwaitUser(sas, "DESKTOP-OLEX"), f.state)
        return sas
    }

    @Test fun happyPathStoresPairingOnlyAfterConfirmH() {
        val f = flow(); val h = FakeHost()
        val sas = toAwaitUser(f, h)
        assertTrue(sent(f.onTick(5000)).isEmpty()) // nothing to repeat while the user decides
        val confirm = sent(f.userConfirmed(6000)).single() as PairConfirm
        assertEquals(PairState.Confirming(sas, "DESKTOP-OLEX", false), f.state)
        f.onPacket(PairDone(attemptId, DoneStatus.PENDING), 6010)
        assertEquals(PairState.Confirming(sas, "DESKTOP-OLEX", true), f.state)
        f.onPacket(h.done(confirm), 7000)
        val done = f.state as PairState.Done
        assertArrayEquals(h.pairKey, done.host.pairKey)
        assertArrayEquals(hostId, done.host.hostId)
        assertEquals("DESKTOP-OLEX", done.host.name)
        assertEquals(addr, done.host.addr)
    }

    @Test fun retransmitCadence() {
        val f = flow(); val h = FakeHost()
        val start = sent(f.start(0)).single() as PairStart
        assertTrue(sent(f.onTick(249)).isEmpty())
        assertTrue(sent(f.onTick(250)).single() is PairStart)
        assertTrue(sent(f.onTick(499)).isEmpty())
        assertTrue(sent(f.onTick(500)).single() is PairStart)
        sent(f.onPacket(h.host(start), 600)).single() as PairReveal
        assertTrue(sent(f.onTick(849)).isEmpty())
        assertTrue(sent(f.onTick(850)).single() is PairReveal)
        f.onPacket(PairAck(attemptId, AckStatus.OK), 900)
        sent(f.userConfirmed(1000)).single() as PairConfirm
        assertTrue(sent(f.onTick(1250)).isEmpty())
        assertTrue(sent(f.onTick(1500)).single() is PairConfirm)
        f.onPacket(PairDone(attemptId, DoneStatus.PENDING), 1600)
        assertTrue(sent(f.onTick(2000)).single() is PairConfirm)
    }

    @Test fun confirmTappedBeforeAckGoesStraightToConfirming() {
        val f = flow(); val h = FakeHost()
        val start = sent(f.start(0)).single() as PairStart
        h.reveal(sent(f.onPacket(h.host(start), 10)).single() as PairReveal)
        assertTrue(f.userConfirmed(15).isEmpty())
        val c = sent(f.onPacket(PairAck(attemptId, AckStatus.OK), 20)).single() as PairConfirm
        f.onPacket(h.done(c), 30)
        assertTrue(f.state is PairState.Done)
    }

    @Test fun denied() {
        val f = flow(); toAwaitUser(f, FakeHost())
        f.userConfirmed(30)
        f.onPacket(PairDone(attemptId, DoneStatus.DENIED), 40)
        assertEquals(PairState.Failed(PairFailure.DENIED), f.state)
        assertTrue(f.onTick(10_000).isEmpty())
    }

    @Test fun badCommit() {
        val f = flow(); val h = FakeHost()
        val start = sent(f.start(0)).single() as PairStart
        f.onPacket(h.host(start), 10)
        f.onPacket(PairAck(attemptId, AckStatus.BAD_COMMIT), 20)
        assertEquals(PairState.Failed(PairFailure.BAD_COMMIT), f.state)
    }

    @Test fun closedWindowRefusesStart() {
        val f = flow()
        f.start(0)
        f.onPacket(PairAck(0, AckStatus.CLOSED), 5)
        assertEquals(PairState.Failed(PairFailure.CLOSED), f.state)
    }

    @Test fun ackForAnotherAttemptIsIgnored() {
        val f = flow(); val h = FakeHost()
        val start = sent(f.start(0)).single() as PairStart
        f.onPacket(h.host(start), 10)
        f.onPacket(PairAck(attemptId + 1, AckStatus.BAD_COMMIT), 20)
        assertTrue(f.state is PairState.GotHost)
    }

    @Test fun forgedConfirmHFails() {
        val f = flow(); toAwaitUser(f, FakeHost())
        f.userConfirmed(30)
        f.onPacket(PairDone(attemptId, DoneStatus.ACCEPTED, ByteArray(32) { 1 }), 40)
        assertEquals(PairState.Failed(PairFailure.HOST_CONFIRM_MISMATCH), f.state)
    }

    @Test fun givesUpAfterTheWindowAndCancels() {
        val f = flow()
        f.start(0)
        f.onTick(119_999)
        assertEquals(PairState.Started, f.state)
        f.onTick(120_000)
        assertEquals(PairState.Failed(PairFailure.GAVE_UP), f.state)

        val g = flow()
        g.start(0)
        g.cancel()
        assertEquals(PairState.Failed(PairFailure.CANCELLED), g.state)
    }
}
