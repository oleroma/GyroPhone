package com.oleroma.gyrophone.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroRateWindowTest {
    private val t0 = 5_000_000_000_000L

    /** Feeds [seconds] of samples at [hz] from [start]; returns the next timestamp and the windows that closed. */
    private fun GyroRateWindow.feed(start: Long, hz: Int, seconds: Int): Pair<Long, Int> {
        val step = 1_000_000_000L / hz
        var ts = start; var closed = 0
        repeat(hz * seconds) { if (add(ts)) closed++; ts += step }
        return ts to closed
    }

    @Test fun firstSampleOnlyOpensTheWindow() {
        val w = GyroRateWindow()
        assertFalse(w.add(t0))
        assertEquals(0, w.rateHz)
        assertFalse(w.capped)
    }

    @Test fun measuresTheRateOverOneSecondOfSensorTime() {
        val w = GyroRateWindow()
        val (_, closed) = w.feed(t0, 800, 3)
        assertEquals(2, closed)
        assertEquals(800, w.rateHz)
        assertFalse(w.capped)
    }

    @Test fun twoSlowWindowsInARowMeanCapped() {
        val w = GyroRateWindow()
        var (ts, _) = w.feed(t0, 50, 2)
        assertEquals(50, w.rateHz)
        assertFalse(w.capped) // one slow window so far
        ts = w.feed(ts, 50, 1).first
        assertTrue(w.capped)
    }

    @Test fun oneSlowWindowIsForgivenByAFastOne() {
        val w = GyroRateWindow()
        var ts = w.feed(t0, 800, 2).first
        ts = w.feed(ts, 100, 1).first   // a HAL hiccup
        ts = w.feed(ts, 800, 1).first
        ts = w.feed(ts, 100, 1).first
        assertFalse(w.capped)
    }

    @Test fun fastEnoughRatesNeverCap() {
        val w = GyroRateWindow()
        w.feed(t0, GyroRateWindow.CAPPED_BELOW_HZ, 5)
        assertFalse(w.capped)
    }

    @Test fun resetStartsOver() {
        val w = GyroRateWindow()
        val ts = w.feed(t0, 50, 3).first
        assertTrue(w.capped)
        w.reset()
        assertFalse(w.capped)
        assertEquals(0, w.rateHz)
        assertFalse(w.add(ts + 10_000_000_000L)) // no window across the gap
    }
}
