package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MOTION bytes; the same hex is decoded by the companion's Ds4MotionTests. */
class MotionFeedTest {
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test fun sizes() {
        assertEquals(120, Wire.MOTION_PLAIN)
        assertEquals(Wire.HEADER + 120 + Wire.GCM_TAG, Wire.sizeOf(PacketType.MOTION))
        assertTrue(PacketType.isPhoneToHost(PacketType.MOTION))
    }

    @Test fun vector() {
        val f = MotionFeed()
        // Three samples, then a stop: seq goes on, the next sample starts with no time before it.
        repeat(3) { f.add(1f, 1f, 1f, 100L + it) }
        assertTrue(f.still())
        assertFalse(f.still())
        f.accel(-10f / 8192f, -20f / 8192f, 1f)
        f.add(1f, 2f, 3f, 1000L)
        f.accel(0f, 0f, -8256f / 8192f)
        f.add(1f / 16f, -2f / 16f, 253f / 16f, 2000L)
        val out = ByteArray(MotionFeed.SIZE)
        f.encode(out, 0)
        assertEquals(VECTOR, out.toHex())
    }

    @Test fun slowTurnsCarryTheirRemainder() {
        val f = MotionFeed()
        // 1/32 deg/s rounds to 0 or 1 sixteenth, alternating: the turn adds up.
        repeat(8) { f.add(1f / 32f, 0f, 0f, 1000L * it) }
        val out = ByteArray(MotionFeed.SIZE)
        f.encode(out, 0)
        var sum = 0
        for (k in 0 until 8) sum += out[MotionFeed.HEADER + k * MotionFeed.SAMPLE + 2].toInt()
        assertEquals(4, sum)
    }

    @Test fun wantedNeedsBothAndTellsTheRuntime() {
        val f = MotionFeed()
        var calls = 0
        f.onWantedChange = Runnable { calls++ }
        f.hostTakes = true
        assertFalse(f.wanted)
        f.padIsDs4 = true
        assertTrue(f.wanted)
        f.padIsDs4 = true
        assertEquals(1, calls)
        f.add(1f, 0f, 0f, 0L)
        f.hostTakes = false
        assertEquals(2, calls)
        val out = ByteArray(MotionFeed.SIZE)
        f.encode(out, 0)
        assertEquals(0, out[0].toInt())
        assertEquals(0, out[1].toInt())
    }

    companion object {
        /** Also in the companion's Ds4MotionTests.Vector. */
        const val VECTOR =
            "01020500d0070000" +
                "0000100020003000f6ffecff0020" +
                "e8030100fefffd0000000000c0df" +
                "0000000000000000000000000000" + "0000000000000000000000000000" + "0000000000000000000000000000" +
                "0000000000000000000000000000" + "0000000000000000000000000000" + "0000000000000000000000000000"
    }
}
