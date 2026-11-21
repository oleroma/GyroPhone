package com.oleroma.gyrophone.core.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FlightRingTest {
    private fun ByteArray.le() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun entriesAreWrittenOldestFirstAfterTheHeader() {
        val f = FlightRing(1)
        f.add(1_000, 7, 100, 1500, 30, 0, FlightRing.SAMPLE)
        f.add(2_000, 8, 200, -1, 40, 900, FlightRing.KEEPALIVE)
        f.add(3_000, 9, 300, -1, 40, 0, 0) // the newest: left out
        val out = ByteArrayOutputStream()
        assertEquals(2, f.write(out, 0, 3_500, 42))
        val b = out.toByteArray().le()
        val magic = ByteArray(8).also { b.get(it) }
        assertEquals("GPFLPHON", String(magic, Charsets.US_ASCII))
        assertEquals(FlightRing.VERSION, b.getInt(8))
        assertEquals(2L, b.getLong(16))
        assertEquals(42L, b.getLong(32))
        assertEquals(40 + 64, out.size())
        assertEquals(2_000L, b.getLong(40 + 32))
        assertEquals(8, b.getInt(40 + 32 + 8))
        assertEquals(900, b.getInt(40 + 32 + 24))
        assertEquals(FlightRing.KEEPALIVE, b.getInt(40 + 32 + 28))
        assertEquals(1, f.write(ByteArrayOutputStream(), 1_500, 3_500, 0))
    }

    @Test fun aFullRingKeepsTheNewest() {
        val f = FlightRing(1)
        val cap = f.capacity.toLong()
        for (i in 0 until cap + 10) f.add(i + 1, i.toInt(), 0, 0, 0, 0, 0)
        val out = ByteArrayOutputStream()
        assertEquals((cap - 2).toInt(), f.write(out, 0, 0, 0))
        assertEquals(12L, out.toByteArray().le().getLong(40))
    }
}
