package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCodecTest {
    private val id = ByteArray(16) { it.toByte() }
    private val buf = ByteArray(Wire.MAX_DATAGRAM)

    @Test fun sizesMatchTheTable() {
        assertEquals(94, Wire.sizeOf(PacketType.STATE))
        assertEquals(36, Wire.sizeOf(PacketType.PING))
        assertEquals(50, Wire.sizeOf(PacketType.LINK_INFO))
        assertEquals(32, Wire.sizeOf(PacketType.BYE))
        assertEquals(52, Wire.sizeOf(PacketType.PONG))
        assertEquals(50, Wire.sizeOf(PacketType.HOST_STATUS))
        assertEquals(32, Wire.sizeOf(PacketType.RUMBLE))
        assertEquals(94, Wire.sizeOf(PacketType.FOREGROUND))
        assertEquals(32, Wire.sizeOf(PacketType.HOST_BYE))
        assertEquals(-1, Wire.sizeOf(0x40))
        for (t in 0..0xFF) assertTrue(Wire.sizeOf(t) <= Wire.MAX_DATAGRAM)
    }

    @Test fun announceRoundTrip() {
        val n = WireCodec.encodeAnnounce(Announce(-1, id, AnnounceFlags.PAIRED_WITH_YOU, "PC"), buf)
        val a = WireCodec.decodePlain(buf, n) as Announce
        assertEquals(-1, a.reqId)
        assertArrayEquals(id, a.hostId)
        assertEquals(AnnounceFlags.PAIRED_WITH_YOU, a.flags)
        assertEquals("PC", a.name)
    }

    @Test fun pairDoneRoundTripAndZeroConfirmUnlessAccepted() {
        val h = ByteArray(32) { 7 }
        var n = WireCodec.encodePairDone(PairDone(5, DoneStatus.DENIED, h), buf)
        assertArrayEquals(ByteArray(32), (WireCodec.decodePlain(buf, n) as PairDone).confirmH)
        n = WireCodec.encodePairDone(PairDone(5, DoneStatus.ACCEPTED, h), buf)
        val d = WireCodec.decodePlain(buf, n) as PairDone
        assertEquals(5, d.attemptId)
        assertArrayEquals(h, d.confirmH)
    }

    @Test fun shortOversizeAndWrongTypeAreRejected() {
        val n = WireCodec.encodeDiscover(Discover(1, id), buf)
        assertNull(WireCodec.decodePlain(buf, n - 1))
        assertNull(WireCodec.decodePlain(buf, n + 1))
        assertNull(WireCodec.decodePlain(buf, 11))
        assertEquals(WireCodec.INVALID, WireCodec.classify(buf, 0))
        buf[3] = 0x40 // unknown type
        assertNull(WireCodec.decodePlain(buf, n))
        buf[3] = PacketType.DISCOVER.toByte(); buf[2] = 2
        assertEquals(WireCodec.BAD_VERSION, WireCodec.classify(buf, n))
        buf[2] = 1; buf[0] = 0
        assertEquals(WireCodec.INVALID, WireCodec.classify(buf, n))
    }

    @Test fun sealedTypesAreNotDecodedAsPlain() {
        WireCodec.writeHeader(buf, PacketType.STATE, 1, 1)
        assertEquals(PacketType.STATE, WireCodec.classify(buf, 94))
        assertNull(WireCodec.decodePlain(buf, 94))
    }

    @Test fun nameLengthOverrunIsRejected() {
        var n = WireCodec.encodeAnnounce(Announce(1, id, 0, "x"), buf)
        buf[34] = 30 // > 29
        assertNull(WireCodec.decodePlain(buf, n))
        n = WireCodec.encodePairStart(PairStart(id, ByteArray(65), ByteArray(32), "x"), buf)
        buf[125] = 32 // > 31
        assertNull(WireCodec.decodePlain(buf, n))
        n = WireCodec.encodePairHost(PairHost(1, id, ByteArray(65), id, "x"), buf)
        buf[113] = 0xFF.toByte()
        assertNull(WireCodec.decodePlain(buf, n))
    }

    @Test fun namesTruncateOnCodePointBoundary() {
        // 14 x "é" (2 bytes each) = 28 bytes + "€" (3 bytes) would be 31 > 29: the euro sign is dropped whole.
        val name = "é".repeat(14) + "€"
        val n = WireCodec.encodeAnnounce(Announce(1, id, 0, name), buf)
        assertEquals(28, buf[34].toInt())
        assertEquals("é".repeat(14), (WireCodec.decodePlain(buf, n) as Announce).name)
        assertEquals(31, WireCodec.utf8Truncated("a".repeat(40), 31).size)
        assertEquals("😀".toByteArray().size, WireCodec.utf8Truncated("😀😀", 5).size)
    }

    @Test fun sealedPlainRoundTrips() {
        val packets = listOf(
            Ping(Long.MAX_VALUE), Pong(1, 2, -1), Bye(ByeReason.REKEY), HostBye(HostByeReason.EXIT),
            Rumble(255, 0), Foreground("game.exe"),
            LinkInfo(-1, 100, 1000, -90, 6, 2402, 5955, 1),
            HostStatus(7, 1000, 0, -1, -1, 0, 2, 31),
        )
        for (p in packets) {
            val n = WireCodec.encodeSealedPlain(p, buf, 3)
            assertEquals(Wire.plainSizeOf(WireCodec.sealedTypeOf(p)), n)
            assertEquals(p, WireCodec.decodeSealedPlain(WireCodec.sealedTypeOf(p), buf, 3, n))
        }
        assertNull(WireCodec.decodeSealedPlain(PacketType.PING, buf, 0, 7))
    }

    @Test fun stateRoundTripKeepsEveryField() {
        val f = StateFrame().apply {
            phoneTimeUs = -16; flags = 7; buttons = 0xFFFF; hat = 3; mouseButtons = 0x1F
            lx = -32767; ly = 32767; rx = 1; ry = -1; lt = 1023; rt = 0
            setKey(0x04); setKey(0x73); keyBitmap[28] = 0x81.toByte()
            mouseTotalX = Int.MIN_VALUE; mouseTotalY = Int.MAX_VALUE; wheelTotal = -1
        }
        val n = WireCodec.encodeStatePlain(f, buf, 12)
        assertEquals(66, n)
        val g = WireCodec.decodeStatePlain(buf, 12)
        assertTrue(f.discreteEquals(g) && f.totalsEqual(g))
        assertEquals(f.phoneTimeUs, g.phoneTimeUs)
        assertEquals(f.flags, g.flags)
    }

    @Test fun littleEndianHelpers() {
        val b = ByteArray(8)
        b.putU32(0, 0xCAFEBABEL)
        assertEquals("bebafeca", b.hex(4))
        assertEquals(0xCAFEBABEL, b.getU32(0))
        b.putI16(0, -2)
        assertEquals(-2, b.getI16(0))
        assertEquals(0xFFFE, b.getU16(0))
        b.putU64(0, -2L)
        assertEquals(-2L, b.getU64(0))
    }
}
