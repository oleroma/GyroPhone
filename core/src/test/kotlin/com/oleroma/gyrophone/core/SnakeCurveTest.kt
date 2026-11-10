package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.LaserTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SnakeCurveTest {
    private fun at(deg: Float, up: Float = 0f): FloatArray = FloatArray(3).also {
        LaserTrace.dir(Math.toRadians(up.toDouble()).toFloat(), Math.toRadians(deg.toDouble()).toFloat(), it)
    }

    private fun curve(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, t: Float) =
        FloatArray(3).also { LaserTrace.curve(p0, 0, p1, 0, p2, 0, p3, 0, t, it) }

    private fun near(a: FloatArray, b: FloatArray, tol: Float = 1e-4f) {
        for (k in 0..2) assertEquals(b[k], a[k], tol)
    }

    @Test fun passesThroughItsPointsAtUnitLength() {
        val p = listOf(at(0f), at(5f, 3f), at(12f, 2f), at(15f, -4f))
        near(curve(p[0], p[1], p[2], p[3], 0f), p[1])
        near(curve(p[0], p[1], p[2], p[3], 1f), p[2])
        val m = curve(p[0], p[1], p[2], p[3], 0.5f)
        assertEquals(1f, sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]), 1e-5f)
    }

    @Test fun aStraightRunStaysOnItsLine() {
        // Points along the equator, unevenly spaced: the curve between them stays on the equator.
        val p = listOf(at(0f), at(1f), at(9f), at(10f))
        for (i in 1..9) {
            val q = curve(p[0], p[1], p[2], p[3], i / 10f)
            assertEquals(0f, q[1], 1e-4f)
        }
    }

    @Test fun aCornerIsRounded() {
        // A right-angled turn: the curve between the corner's points bulges outward past the chord, no overshoot.
        val p0 = at(0f); val p1 = at(8f); val p2 = at(8f, 8f); val p3 = at(8f, 16f)
        val q = curve(p0, p1, p2, p3, 0.5f)
        val lon = Math.toDegrees(kotlin.math.atan2(q[0].toDouble(), -q[2].toDouble())).toFloat()
        val lonChord = Math.toDegrees(kotlin.math.atan2((p1[0] + p2[0]).toDouble(), -(p1[2] + p2[2]).toDouble())).toFloat()
        assertTrue("bulges outward ($lon vs $lonChord)", kotlin.math.abs(lon - lonChord) > 0.3f)
        assertTrue("no wild overshoot ($lon)", kotlin.math.abs(lon - 8f) < 3f)
    }

    @Test fun lineEndsUseTheEndAsNeighbour() {
        val a = at(0f); val b = at(10f)
        val q = curve(a, a, b, b, 0.5f)
        near(q, at(5f), 1e-3f)
    }
}
