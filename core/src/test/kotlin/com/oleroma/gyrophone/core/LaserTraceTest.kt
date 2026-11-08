package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.LaserTrace
import org.junit.Assert.assertArrayEquals
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
        val tick = FloatArray(3)
        assertTrue(LaserTrace.rollTick(identity, b, tick))
        assertEquals(1f, tick[1], 1e-6f)
    }

    @Test fun viewSeesTheFarSideAboveAndRightOfTheCentre() {
        val p = FloatArray(3)
        LaserTrace.view(0f, 0f, -1f, p)
        assertTrue(p[0] > 0f && p[1] > 0f && p[2] < 0f)
        LaserTrace.view(0f, 1f, 0f, p)
        assertTrue(p[2] > 0f)
        val (front, back) = LaserTrace.GRID
        assertTrue(front.isNotEmpty() && back.isNotEmpty())
        for (l in front + back) for (i in l.indices step 2) assertTrue(l[i] * l[i] + l[i + 1] * l[i + 1] <= 1.0001f)
        val m = identity.copyOf()
        LaserTrace.axonometric(m)
        assertArrayEquals(LaserTrace.VIEW, m, 1e-6f)
    }

    @Test fun behindGoesFromAheadToTurnedRound() {
        assertEquals(0f, LaserTrace.behind(-1f), 0f)
        assertEquals(0.5f, LaserTrace.behind(0f), 0f)
        assertEquals(1f, LaserTrace.behind(1f), 0f)
    }

    @Test fun faceOnSeesTheBeamStraightInAsADot() {
        val p = FloatArray(3)
        LaserTrace.view(0f, 0f, -1f, p, axon = false)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), p, 0f)
        val (front, back) = LaserTrace.grid(false)
        assertTrue(front.isNotEmpty() && back.isNotEmpty())
        for (l in front + back) for (i in l.indices step 2) assertTrue(l[i] * l[i] + l[i + 1] * l[i + 1] <= 1.0001f)
    }

    @Test fun strokesKeepAPointOnlyWhenTheBeamMoves() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = true, held = false)
        t.add(0f, 0f, -1f, 10, draw = true, held = false)
        assertEquals(1, t.strokes.count)
        t.add(0.1f, 0f, -1f, 20, draw = true, held = false)
        assertEquals(2, t.strokes.count)
        assertTrue(t.strokes.gap(0))
        assertFalse(t.strokes.gap(1))
    }

    @Test fun drawOffOrClearWipesTheStrokes() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = true, held = false)
        t.add(1f, 0f, 0f, 10, draw = true, held = false)
        assertEquals(2, t.strokes.count)
        val v = t.version
        t.add(1f, 0f, 0f, 20, draw = false, held = false)
        assertEquals(0, t.strokes.count)
        assertTrue(t.version > v)
        t.add(0f, 0f, -1f, 30, draw = true, held = false)
        t.clear()
        assertEquals(0, t.strokes.count)
    }

    @Test fun heldRatchetDoesntPaintAndBreaksTheLine() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = true, held = false)
        t.add(1f, 0f, 0f, 5, draw = true, held = true)
        assertEquals(1, t.strokes.count) // kept without Clear on ratchet
        t.add(1f, 0f, 0f, 6, draw = true, held = false)
        t.add(1f, 0f, 0f, 10, draw = true, held = true, clearOnPress = true)
        assertEquals(0, t.strokes.count) // a ratchet press clears the drawing
        t.add(1f, 0f, 0f, 20, draw = true, held = false)
        assertEquals(1, t.strokes.count)
        assertTrue(t.strokes.gap(0))
    }

    @Test fun strokesFade() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = true, held = false)
        assertEquals(1f, LaserTrace.strokeLife(0, 1, 0, 0, 5000), 0f)
        assertEquals(0.5f, LaserTrace.strokeLife(0, 1, 0, 2500, 5000), 1e-3f)
        t.expire(600, 5000)
        assertTrue(t.fading(5000))
        t.expire(5001, 5000)
        assertEquals(0, t.strokes.count)
        assertFalse(t.fading(5000))
        assertEquals(0f, LaserTrace.strokeLife(0, 3, 0, 0, 0), 0f)
        assertEquals(1f, LaserTrace.strokeLife(2, 3, 0, 0, 0), 0f)
    }

    @Test fun ringDropsTheOldestWhenFull() {
        val r = LaserTrace.Ring(3)
        for (i in 0 until 5) r.add(i.toFloat(), 0f, 0f, i.toLong(), false)
        assertEquals(3, r.count)
        val p = FloatArray(3)
        r.point(0, p)
        assertEquals(2f, p[0], 0f)
        assertTrue(r.gap(0))
    }
}

class CameraTurnTest {
    @Test fun turnsLikeAGameCameraNeverRolling() {
        val p = com.oleroma.gyrophone.core.engine.GyroPose()
        p.cameraTurn(30f, 20f)
        p.cameraTurn(15f, -5f)
        val m = FloatArray(9)
        p.matrix(m)
        val b = FloatArray(3)
        LaserTrace.beam(m, b)
        assertEquals(45.0, Math.toDegrees(kotlin.math.atan2(b[0], -b[2]).toDouble()), 1e-3)
        assertEquals(15.0, Math.toDegrees(kotlin.math.asin(b[1].toDouble())), 1e-3)
        // Its right stays level: no roll.
        assertEquals(0f, m[3], 1e-5f)
        // Pitch stops short of straight up.
        p.cameraTurn(0f, 500f)
        p.matrix(m)
        LaserTrace.beam(m, b)
        assertEquals(89.0, Math.toDegrees(kotlin.math.asin(b[1].toDouble())), 1e-2)
    }

    @Test fun cameraMapFollowsTheRoutes() {
        val gs = com.oleroma.gyrophone.core.model.GyroSettings()
        assertEquals(com.oleroma.gyrophone.core.engine.GyroDisplayFeed.DEFAULT_CAMERA_MAP,
            com.oleroma.gyrophone.core.engine.GyroDisplayFeed.cameraMapOf(emptyList(), gs))
    }
}
