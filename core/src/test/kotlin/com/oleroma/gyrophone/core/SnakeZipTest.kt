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

    @Test fun aStoppedBeamZipsTheTailIntoTheHeadBySpeed() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(stops = true, ratchet = false, bySpeed = true, seconds = 0.4f, speed = 100f)
        swept(t, zip)
        assertEquals(60f, t.strokes.behindNewest(0), 0.6f)
        // Still for under the stop time: nothing yet.
        still(t, zip, 605, 700)
        assertEquals(60f, t.strokes.behindNewest(0), 0.6f)
        // Then the tail runs in at 100°/s: about 30° gone after another 0.3 s.
        still(t, zip, 705, 1050)
        val left = t.strokes.behindNewest(0)
        assertTrue("left $left", left in 20f..35f)
        // And it is all gone in time, the head kept.
        still(t, zip, 1055, 2000)
        assertEquals(0f, t.strokes.behindNewest(0), 1e-4f)
        assertEquals(1, t.strokes.count)
    }

    @Test fun byTimeTheWholeSnakeGoesInThatLong() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(stops = true, ratchet = false, bySpeed = false, seconds = 0.2f, speed = 360f)
        swept(t, zip)
        // Starts 150 ms after the last point; 0.2 s later it is gone.
        still(t, zip, 605, 760)
        assertTrue(t.strokes.behindNewest(0) > 50f)
        still(t, zip, 765, 980)
        assertEquals(0f, t.strokes.behindNewest(0), 1e-4f)
    }

    @Test fun itGrowsAgainFromTheHeadAsTheBeamMoves() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(stops = true, ratchet = false, bySpeed = true, seconds = 0.4f, speed = 1000f)
        swept(t, zip)
        still(t, zip, 605, 1000)
        assertEquals(1, t.strokes.count)
        // Moving on: the line grows from where the head was, with no line back to the old tail.
        for (ms in 1005L..1200L step 5) {
            val a = Math.toRadians(60.0 + (ms - 1000) / 10.0).toFloat()
            t.add(sin(a), 0f, -cos(a), ms, draw = false, held = false, snakeDeg = 180f, zip = zip)
        }
        assertEquals(20f, t.strokes.behindNewest(0), 0.6f)
    }

    @Test fun onRatchetZipsOnAPressOnly() {
        val t = LaserTrace()
        val zip = LaserTrace.Zip(stops = false, ratchet = true, bySpeed = true, seconds = 0.4f, speed = 200f)
        swept(t, zip)
        // Still without a press: it stays.
        still(t, zip, 605, 1000)
        assertEquals(60f, t.strokes.behindNewest(0), 0.6f)
        // A press (held) runs it in.
        still(t, zip, 1005, 1400, held = true)
        assertEquals(0f, t.strokes.behindNewest(0), 1e-4f)
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
