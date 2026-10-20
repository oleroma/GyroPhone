package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {
    /** Host clock = phone clock + [offset]; one-way delays [up] and [down], host turnaround [proc]. */
    private fun ClockSync.ping(t1: Long, offset: Long, up: Long, down: Long, proc: Long = 40): Boolean {
        val t2 = t1 + up + offset
        val t3 = t2 + proc
        val t4 = t3 - offset + down
        return add(t1, t2, t3, t4)
    }

    @Test fun symmetricPathGivesExactOffset() {
        val c = ClockSync()
        assertTrue(c.ping(1_000, offset = 5_000_000, up = 1500, down = 1500))
        assertEquals(5_000_000, c.offsetUs)
        assertEquals(3000, c.rttMinUs)
    }

    @Test fun keepsTheLowestRttSampleOfTheWindow() {
        val c = ClockSync()
        c.ping(0, offset = -5000, up = 4000, down = 1000) // rtt 5000, offset error +1500
        assertTrue(c.ping(10_000, offset = -5000, up = 1000, down = 1000)) // best
        assertFalse(c.ping(20_000, offset = -5000, up = 9000, down = 1000))
        assertEquals(-5000, c.offsetUs)
        assertEquals(2000, c.rttMinUs)
        // After 8 worse samples the best one leaves the window.
        repeat(ClockSync.WINDOW) { c.ping(30_000L + it * 10_000, offset = -5000, up = 3000, down = 2000) }
        assertEquals(5000, c.rttMinUs)
        assertEquals(-4500, c.offsetUs)
    }

    @Test fun negativeRttIsIgnored() {
        val c = ClockSync()
        assertFalse(c.add(100, 0, 0, 50))
        assertFalse(c.hasSample)
    }
}
