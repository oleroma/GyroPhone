package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.LaserTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SnakeZipTest {
    // The beam swept 60° to the right over 600 ms, a point a millisecond-step apart.
    private fun swept(t: LaserTrace, zip: LaserTrace.Zip?, until: Long = 600L) {
        for (ms in 0..until step 5) {
            val a = Math.toRadians(ms / 10.0).toFloat()
            t.add(sin(a), 0f, -cos(a), ms, draw = false, held = false, snakeDeg = 180f, zip = zip)
        }
    }

    private fun still(t: LaserTrace, zip: LaserTrace.Zip?, from: Long, to: Long, held: Boolean = false) {
        val a = Math.toRadians(60.0).toFloat()
        for (ms in from..to step 5) t.add(sin(a), 0f, -cos(a), ms, draw = false, held = held, snakeDeg = 180f, zip = zip)
    }

    @Test fun continuousTheTailAlwaysRunsTowardTheHead() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(always = true, ratchet = false, seconds = 0.4f, speed = 50f)
        // Moving at 100°/s for 0.6 s with the tail running at 50°/s: half the 60° path is left.
        swept(t, zip)
        val moving = t.strokes.behindNewest(0)
        assertTrue("moving $moving", moving in 28f..32f)
        // Still: it keeps running in, about 10° in 0.2 s, and only the head is left in time.
        still(t, zip, 605, 800)
        val left = t.strokes.behindNewest(0)
        assertTrue("left $left", left in moving - 11f..moving - 9f)
        still(t, zip, 805, 2000)
        assertEquals(0f, t.strokes.behindNewest(0), 1e-4f)
        assertEquals(1, t.strokes.count)
    }

    @Test fun onRatchetTheWholeSnakeGoesInThatLongWhileHeld() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(always = false, ratchet = true, seconds = 0.2f, speed = 360f)
        swept(t, zip)
        // Still without a press: it stays.
        still(t, zip, 605, 1000)
        assertEquals(60f, t.strokes.behindNewest(0), 0.6f)
        // Held from 1005 ms: half gone after 0.1 s, all gone after 0.2 s, the head kept.
        still(t, zip, 1005, 1105, held = true)
        val half = t.strokes.behindNewest(0)
        assertTrue("half $half", half in 26f..34f)
        still(t, zip, 1110, 1300, held = true)
        assertEquals(0f, t.strokes.behindNewest(0), 1e-4f)
        assertEquals(1, t.strokes.count)
    }

    @Test fun bothAddUp() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(always = true, ratchet = true, seconds = 1f, speed = 30f)
        swept(t, zip)
        val before = t.strokes.behindNewest(0)
        // Held for 0.5 s: 15° by the speed and half the Snake by the ratchet's time.
        still(t, zip, 605, 1105, held = true)
        val left = t.strokes.behindNewest(0)
        assertTrue("left $left of $before", left in before / 2f - 15f - 3f..before / 2f - 15f + 3f)
    }

    @Test fun itGrowsAgainFromTheHeadAsTheBeamMoves() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(always = false, ratchet = true, seconds = 0.1f, speed = 360f)
        swept(t, zip)
        still(t, zip, 605, 1000, held = true)
        assertEquals(1, t.strokes.count)
        // Moving on: the line grows from where the head was, with no line back to the old tail.
        for (ms in 1005L..1200L step 5) {
            val a = Math.toRadians(60.0 + (ms - 1000) / 10.0).toFloat()
            t.add(sin(a), 0f, -cos(a), ms, draw = false, held = false, snakeDeg = 180f, zip = zip)
        }
        assertEquals(20f, t.strokes.behindNewest(0), 0.6f)
    }

    @Test fun trimmingMovesTheTailAlongItsSpan() {
        val r = LaserTrace.Ring(16)
        r.add(0f, 0f, -1f, 0, true)
        val a = Math.toRadians(10.0).toFloat()
        r.add(sin(a), 0f, -cos(a), 10, false)
        r.trimTo(4f)
        assertEquals(4f, r.behindNewest(0), 1e-3f)
        val p = FloatArray(3)
        r.point(0, p)
        assertEquals(sin(Math.toRadians(6.0)).toFloat(), p[0], 1e-4f)
    }
}
