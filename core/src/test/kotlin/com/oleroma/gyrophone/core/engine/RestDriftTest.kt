package com.oleroma.gyrophone.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestDriftTest {
    private val ms = 1_000_000L

    /** Feeds [seconds] of samples at 1 kHz from [t0]; returns whether any ended a still stretch. */
    private fun RestDrift.run(t0: Long, seconds: Float, sample: (Int) -> FloatArray): Boolean {
        var done = false
        for (i in 0 until (seconds * 1000).toInt()) {
            val s = sample(i)
            if (add(s[0], s[1], s[2], t0 + i * ms)) done = true
        }
        return done
    }

    @Test
    fun aStillPhoneGivesItsDrift() {
        val r = RestDrift()
        assertTrue(r.run(0, 3.1f) { i -> floatArrayOf(0.004f + if (i % 2 == 0) 0.005f else -0.005f, -0.002f, 0.001f) })
        assertEquals(0.004f, r.x, 1e-4f)
        assertEquals(-0.002f, r.y, 1e-5f)
        assertEquals(0.001f, r.z, 1e-5f)
    }

    @Test
    fun aHandHeldPhoneNeverCounts() {
        val r = RestDrift()
        // Hand tremor: 0.02 rad/s peak to peak.
        assertFalse(r.run(0, 10f) { i -> floatArrayOf(if (i % 50 < 25) 0.01f else -0.01f, 0f, 0f) })
    }

    @Test
    fun aMoveStartsTheStretchAgain() {
        val r = RestDrift()
        assertFalse(r.run(0, 2.5f) { floatArrayOf(0f, 0f, 0f) })
        assertFalse(r.add(1f, 0f, 0f, 2500 * ms))
        // 2.5 s more of stillness is not yet 3 s after the move.
        assertFalse(r.run(2501 * ms, 2.4f) { floatArrayOf(0f, 0f, 0f) })
        assertTrue(r.run(4901 * ms, 1f) { floatArrayOf(0f, 0f, 0f) })
    }

    @Test
    fun aSteadyTurnIsNotDrift() {
        val r = RestDrift()
        // 0.2 rad/s with no noise: a turntable, not drift.
        assertFalse(r.run(0, 4f) { floatArrayOf(0.2f, 0f, 0f) })
    }

    @Test
    fun aPauseStartsOver() {
        val r = RestDrift()
        assertFalse(r.run(0, 2f) { floatArrayOf(0f, 0f, 0f) })
        assertFalse(r.run(5000 * ms, 2f) { floatArrayOf(0f, 0f, 0f) })
    }
}
