package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroPose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GyroPoseTest {
    private fun apply(pose: GyroPose, v: FloatArray): FloatArray {
        val m = FloatArray(9)
        pose.matrix(m)
        return FloatArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }
    }

    /** 90°: in many small steps, as the sensor gives them. */
    private fun turn(pose: GyroPose, yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f) {
        repeat(800) { pose.rotate(yaw, pitch, roll, 1f / 800) }
    }

    private val top = floatArrayOf(0f, 1f, 0f)
    private val right = floatArrayOf(1f, 0f, 0f)
    private val screen = floatArrayOf(0f, 0f, 1f)

    @Test fun startsFacingThePlayer() {
        assertArrayEquals(screen, apply(GyroPose(), screen), 1e-6f)
    }

    @Test fun pitchingUpBringsTheTopTowardThePlayer() {
        val p = GyroPose()
        turn(p, pitch = 90f)
        assertArrayEquals(screen, apply(p, top), 1e-3f)
    }

    @Test fun yawingRightTurnsTheScreenLeft() {
        val p = GyroPose()
        turn(p, yaw = 90f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f), apply(p, screen), 1e-3f)
    }

    @Test fun clockwiseRollTurnsTheTopRight() {
        val p = GyroPose()
        turn(p, roll = 90f)
        assertArrayEquals(right, apply(p, top), 1e-3f)
    }

    @Test fun turnsAreAboutThePhonesOwnAxes() {
        // Rolled 90° clockwise, pitching up still brings the phone's own top toward the player (not its right edge).
        val p = GyroPose()
        turn(p, roll = 90f)
        turn(p, pitch = 90f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), apply(p, top), 1e-3f)
    }

    @Test fun resetGoesBackToNeutral() {
        val p = GyroPose()
        turn(p, yaw = 40f, pitch = 70f, roll = 20f)
        val v = p.version
        p.reset()
        assertEquals(true, p.version != v)
        assertArrayEquals(screen, apply(p, screen), 1e-6f)
        assertArrayEquals(top, apply(p, top), 1e-6f)
    }
}

class GyroPoseGravityTest {
    private fun up(p: GyroPose): FloatArray {
        val m = FloatArray(9)
        p.matrix(m)
        return floatArrayOf(m[3], m[4], m[5]) // world up in the phone's frame
    }

    @Test fun tiltSettlesToGravityWithoutGyro() {
        val p = GyroPose()
        // Lying flat, screen up: up is the phone's +z.
        p.setUp(0f, 0f, 9.8f, 9.8f)
        repeat(2000) { p.fuse(0f, 0f, 0f, 0.005f, 0.5f) }
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), up(p), 1e-2f)
    }

    @Test fun shakesAreNotTakenForGravity() {
        val p = GyroPose()
        p.setUp(0f, 30f, 0f, 9.8f)
        repeat(200) { p.fuse(0f, 0f, 0f, 0.005f, 0.5f) }
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), up(p), 1e-6f)
    }

    @Test fun headingEasesBackToFacing() {
        val p = GyroPose()
        p.setUp(0f, 9.8f, 0f, 9.8f)
        repeat(200) { p.fuse(90f, 0f, 0f, 0.005f, 0.5f) } // 90° right in 1 s
        repeat(2000) { p.fuse(0f, 0f, 0f, 0.005f, 0.5f) }
        val m = FloatArray(9)
        p.matrix(m)
        assertEquals(1f, m[8], 1e-2f) // screen faces the player again
    }
}
