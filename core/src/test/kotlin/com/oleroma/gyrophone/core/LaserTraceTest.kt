package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.LaserTrace
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserTraceTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test fun beamPointsOutOfThePhonesBackIntoTheScreen() {
        val b = FloatArray(3)
        LaserTrace.beam(identity, b)
        assertEquals(-1f, b[2], 0f)
        // Straight ahead is longitude 0 on the equator.
        assertEquals(LaserTrace.ROWS / 2, LaserTrace.rowOf(0.001f))
        assertEquals(LaserTrace.COLS / 2, LaserTrace.colOf(0.001f))
        val tick = FloatArray(3)
        assertTrue(LaserTrace.rollTick(identity, b, tick))
        assertEquals(1f, tick[1], 1e-6f) // the phone's top points up
    }

    @Test fun viewSeesTheFarSideAboveAndRightOfTheCentre() {
        val p = FloatArray(3)
        LaserTrace.view(0f, 0f, -1f, p)
        assertTrue(p[0] > 0f && p[1] > 0f && p[2] < 0f)
        // The top pole leans toward the viewer: seen from above.
        LaserTrace.view(0f, 1f, 0f, p)
        assertTrue(p[2] > 0f)
        assertFalse(LaserTrace.CELL_FRONT[(LaserTrace.ROWS / 2) * LaserTrace.COLS + LaserTrace.COLS / 2])
        val (front, back) = LaserTrace.GRID
        assertTrue(front.isNotEmpty() && back.isNotEmpty())
        for (l in front + back) for (i in l.indices step 2) assertTrue(l[i] * l[i] + l[i + 1] * l[i + 1] <= 1.0001f)
    }

    @Test fun paintCoversTheBrushOnlyAndClears() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 1000, 500, draw = true, brushDeg = 4f, held = false)
        val first = t.painted
        // About π·2² / 2² ≈ 13 cells of 2° round a 4° brush on the equator.
        assertTrue("painted $first", first in 9..20)
        t.add(0f, 0f, -1f, 1010, 500, draw = true, brushDeg = 4f, held = false)
        assertEquals(first, t.painted)
        t.clear()
        assertEquals(0, t.painted)
        assertEquals(0, t.trailCount)
    }

    @Test fun aFastSwingPaintsAContinuousStroke() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, 0, draw = true, brushDeg = 2f, held = false)
        // 60° to the right in one frame.
        val a = Math.toRadians(60.0)
        t.add(sin(a).toFloat(), 0f, -cos(a).toFloat(), 16, 0, draw = true, brushDeg = 2f, held = false)
        val row = LaserTrace.ROWS / 2
        var gaps = 0
        for (c in LaserTrace.COLS / 2..LaserTrace.COLS / 2 + 29) if (t.levelOf(row, c, 16, 0) == 0) gaps++
        assertEquals(0, gaps)
    }

    @Test fun heldRatchetNeitherTrailsNorPaintsAndBreaksTheLine() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, 500, draw = true, brushDeg = 4f, held = false)
        val painted = t.painted
        t.add(1f, 0f, 0f, 10, 500, draw = true, brushDeg = 4f, held = true)
        assertEquals(painted, t.painted)
        assertEquals(1, t.trailCount)
        // Let go elsewhere: no stroke drawn across, the trail starts a new line.
        t.add(1f, 0f, 0f, 20, 500, draw = true, brushDeg = 4f, held = false)
        assertEquals(2, t.trailCount)
        assertTrue(t.trailGap(1))
        assertEquals(0, t.levelOf(LaserTrace.ROWS / 2, LaserTrace.COLS / 2 + 22, 20, 0))
    }

    @Test fun trailAndPaintFade() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, 500, draw = true, brushDeg = 4f, held = false)
        val r = LaserTrace.ROWS / 2
        val c = LaserTrace.COLS / 2
        assertEquals(LaserTrace.LEVELS, t.levelOf(r, c, 0, 5000))
        assertEquals(LaserTrace.LEVELS / 2, t.levelOf(r, c, 2500, 5000))
        t.expire(600, 500, 5000)
        assertEquals(0, t.trailCount)
        assertTrue(t.fading(5000))
        t.expire(5001, 500, 5000)
        assertEquals(0, t.painted)
        assertFalse(t.fading(5000))
    }

    @Test fun runsMergeNeighbouringCells() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, 0, draw = true, brushDeg = 6f, held = false)
        var runs = 0
        var cells = 0
        t.forEachRun(0, 0, { r, c -> LaserTrace.CELL_FRONT[r * LaserTrace.COLS + c] }) { _, c0, c1, level, _ ->
            runs++
            cells += c1 - c0 + 1
            assertEquals(LaserTrace.LEVELS, level)
        }
        assertEquals(t.painted, cells)
        assertTrue("runs $runs", runs <= 7)
    }
}
