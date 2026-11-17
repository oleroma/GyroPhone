package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.RatchetTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class RatchetTimingTest {
    private val ms = 1_000_000L

    /**
     * A swing out and back: yaw rate 400 cos(...) deg/s turning around at [apexMs]; the ratchet
     * pressed at [pressMs] and held from the first sample at or after [appliedMs]. The gyro sends
     * 1 count per sample in the swing's direction while not held.
     */
    private fun run(apexMs: Long, pressMs: Long?, appliedMs: Long, countsPer360: Float = 0f): RatchetTiming.Result {
        val rt = RatchetTiming()
        rt.enabled = true
        val start = 1_000L
        for (i in 0..1200) {
            val tMs = start + i
            val tNs = tMs * ms
            if (pressMs != null && tMs == start + pressMs) rt.inputDown(tNs)
            // Turns around at the apex: positive before, negative after.
            val phase = (tMs - (start + apexMs)).toDouble() / 400.0
            val rate = (-400.0 * kotlin.math.sin(phase * PI / 2)).toFloat()
            val held = tMs >= start + appliedMs && tMs < start + appliedMs + 400
            val sent = if (held) 0 else if (rate > 0f) 1 else if (rate < 0f) -1 else 0
            rt.sample(tNs, rate, 0f, sent, 0, held, countsPer360)
        }
        val rs = rt.results()
        assertEquals(1, rs.size)
        return rs[0]
    }

    @Test fun pressAtTheTurnWithAQuickAppIsNotLate() {
        val r = run(apexMs = 500, pressMs = 500, appliedMs = 501)
        assertTrue(r.swing)
        assertEquals(0f, r.playerMs!!, 1.5f)
        assertEquals(1f, r.appMs!!, 0.01f)
        assertTrue(r.backCounts!! <= 1)
    }

    @Test fun lateAppShowsTheBackSwing() {
        val r = run(apexMs = 500, pressMs = 500, appliedMs = 530, countsPer360 = 360f)
        assertEquals(30f, r.appMs!!, 0.01f)
        assertEquals(0f, r.playerMs!!, 1.5f)
        // About 29 samples of the way back, at 1 count (1 degree here) each.
        assertEquals(29f, r.backDeg!!, 2f)
    }

    @Test fun latePlayerPressShowsPositive() {
        val r = run(apexMs = 500, pressMs = 540, appliedMs = 541)
        assertEquals(40f, r.playerMs!!, 1.5f)
        assertEquals(1f, r.appMs!!, 0.01f)
        assertEquals(40, r.backCounts)
    }

    @Test fun earlyPressHasNoBackSwing() {
        val r = run(apexMs = 500, pressMs = 470, appliedMs = 471)
        assertEquals(-30f, r.playerMs!!, 1.5f)
        assertEquals(0, r.backCounts)
    }

    @Test fun noPressEventStillMeasuresTheSwing() {
        val r = run(apexMs = 500, pressMs = null, appliedMs = 510)
        assertTrue(r.swing)
        assertEquals(null, r.playerMs)
        assertEquals(null, r.appMs)
        assertNotNull(r.backCounts)
    }

    @Test fun holdingStillIsNoSwing() {
        val rt = RatchetTiming()
        rt.enabled = true
        for (i in 0..800) {
            val tNs = (1_000L + i) * ms
            if (i == 300) rt.inputDown(tNs)
            rt.sample(tNs, 3f * cos(i / 10.0).toFloat(), 0f, 0, 0, i in 301..500, 0f)
        }
        assertFalse(rt.results().single().swing)
    }

    @Test fun offMeasuresNothing() {
        val rt = RatchetTiming()
        for (i in 0..800) rt.sample((1_000L + i) * ms, 300f, 0f, 1, 0, i > 300, 0f)
        assertTrue(rt.results().isEmpty())
    }
}
