package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        assertEquals(3000, c.rttTypUs)
    }

    @Test fun recentBestAndTypicalRoundTrip() {
        val c = ClockSync()
        c.ping(0, offset = -5000, up = 4000, down = 1000) // rtt 5000
        c.ping(200_000, offset = -5000, up = 1000, down = 1000) // rtt 2000
        c.ping(400_000, offset = -5000, up = 2000, down = 1000) // rtt 3000
        assertEquals(2000, c.rttMinUs)
        assertEquals(3000, c.rttTypUs)
        // Eight slower pings push the best out of the recent window.
        repeat(ClockSync.RECENT_MIN) { c.ping(600_000L + it * 200_000, offset = -5000, up = 3000, down = 2000) }
        assertEquals(5000, c.rttMinUs)
    }

    @Test fun offsetDoesNotJumpWhenTheBestPingAges() {
        val c = ClockSync()
        // One lucky ping early on, then steadily slower but symmetric pings.
        c.ping(0, offset = 1_000_000, up = 300, down = 300)
        var t = 200_000L
        while (t < 60_000_000) { c.ping(t, offset = 1_000_000, up = 900, down = 900); t += 200_000 }
        assertEquals(1_000_000.0, c.offsetUs.toDouble(), 5.0)
    }

    @Test fun followsClockDrift() {
        val c = ClockSync()
        val ppm = 40e-6
        var t = 0L
        var last = 0L
        while (t < 120_000_000) {
            val off = 2_000_000 + (t * ppm).toLong()
            // A bit of asymmetric noise on most pings; every stretch has some clean ones.
            val noisy = (t / 200_000) % 5 != 0L
            c.ping(t, offset = off, up = if (noisy) 1600 else 600, down = 600)
            last = off
            t += 200_000
        }
        assertTrue("offset ${c.offsetUs} vs $last", abs(c.offsetUs - last) <= 30)
        assertEquals(40_000.0, c.driftPpb.toDouble(), 2_000.0)
    }

    @Test fun reportsOnlyMeaningfulChanges() {
        val c = ClockSync()
        assertTrue(c.ping(0, offset = 0, up = 500, down = 500))
        assertFalse(c.ping(200_000, offset = 0, up = 600, down = 600))
        assertEquals(2, c.takePings())
        assertEquals(0, c.takePings())
    }

    @Test fun negativeRttIsIgnored() {
        val c = ClockSync()
        assertFalse(c.add(100, 0, 0, 50))
        assertFalse(c.hasSample)
    }
}
