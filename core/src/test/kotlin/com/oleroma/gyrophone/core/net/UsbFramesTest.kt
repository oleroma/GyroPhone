package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbFramesTest {
    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(UsbFrames.maxSize(payload.size))
        val n = UsbFrames.encode(payload, 0, payload.size, out)
        return out.copyOf(n)
    }

    private fun deframe(stream: ByteArray, chunk: Int = stream.size): List<ByteArray> {
        val d = UsbDeframer()
        val got = ArrayList<ByteArray>()
        var i = 0
        while (i < stream.size) {
            val n = minOf(chunk, stream.size - i)
            d.feed(stream.copyOfRange(i, i + n), n) { b, off, len -> got.add(b.copyOfRange(off, off + len)) }
            i += n
        }
        return got
    }

    @Test fun roundTripsSeveralDatagramsInOneRead() {
        val a = ByteArray(40) { it.toByte() }
        val b = ByteArray(200) { (it * 3).toByte() }
        val got = deframe(frame(a) + frame(b))
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
    }

    @Test fun survivesByteByByteReads() {
        val a = ByteArray(Wire.MAX_DATAGRAM) { (it xor 0x47).toByte() }
        val got = deframe(frame(a) + frame(a), chunk = 1)
        assertEquals(2, got.size)
        assertArrayEquals(a, got[1])
    }

    @Test fun padsFramesThatFillWholePackets() {
        assertEquals(65, frame(ByteArray(61)).size)
        assertEquals(63, frame(ByteArray(60)).size)
        val p = ByteArray(125) { 1 }
        val got = deframe(frame(p) + frame(p))
        assertEquals(2, got.size)
    }

    @Test fun skipsGarbageAndBadLengths() {
        val a = byteArrayOf(9, 8, 7)
        val junk = byteArrayOf(1, 2, UsbFrames.MAGIC.toByte(), 0, 0, UsbFrames.MAGIC.toByte(), 0xFF.toByte(), 0x7F)
        val got = deframe(junk + frame(a))
        assertEquals(1, got.size)
        assertArrayEquals(a, got[0])
    }

    @Test fun matchesTheCompanionByteForByte() {
        // Same vector as the companion's UsbFramingTests: 0x47, length LE, bytes.
        assertArrayEquals(byteArrayOf(0x47, 3, 0, 9, 8, 7), frame(byteArrayOf(9, 8, 7)))
    }
}
