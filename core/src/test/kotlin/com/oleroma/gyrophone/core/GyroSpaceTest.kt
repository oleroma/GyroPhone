package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.model.GyroSpace
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Gyro space: yaw, pitch and roll about the phone's axes, the world's, or JoyShockMapper's player space. */
class GyroSpaceTest {
    private val g = GyroMouse()
    private val upright = floatArrayOf(0f, 1f, 0f)
    private val flat = floatArrayOf(0f, 0f, 1f) // screen up: up is out of the screen

    private fun assertMotion(yaw: Float, pitch: Float, roll: Float, m: GyroMouse.Motion) {
        assertEquals(yaw, m.yaw, 0.01f); assertEquals(pitch, m.pitch, 0.01f); assertEquals(roll, m.roll, 0.01f)
    }

    @Test fun localAndUprightAreThePhonesAxes() {
        val m = GyroMouse.Motion(10f, 20f, 30f)
        assertMotion(10f, 20f, 30f, g.inSpace(m, GyroSpace.LOCAL, flat))
        assertMotion(10f, 20f, 30f, g.inSpace(m, GyroSpace.WORLD, upright))
        assertMotion(10f, 20f, 30f, g.inSpace(m, GyroSpace.WORLD, null))
    }

    @Test fun worldYawIsTurningAroundGravity() {
        // Lying flat, turning left and right is the phone's roll.
        assertMotion(10f, 0f, 0f, g.inSpace(GyroMouse.Motion(0f, 0f, 10f), GyroSpace.WORLD, flat))
        // Tilted back 45°: a turn around the vertical is half yaw, half roll on the phone.
        val a = Math.toRadians(45.0)
        val up = floatArrayOf(0f, cos(a).toFloat(), sin(a).toFloat())
        val k = (10 / sqrt(2.0)).toFloat()
        val w = g.inSpace(GyroMouse.Motion(k, 0f, k), GyroSpace.WORLD, up)
        assertMotion(10f, 0f, 0f, w)
        // Pitch stays pitch: the right axis is flat.
        assertEquals(5f, g.inSpace(GyroMouse.Motion(0f, 5f, 0f), GyroSpace.WORLD, up).pitch, 0.01f)
    }

    @Test fun playerYawBlendsYawAndRollButNeverFasterThanBoth() {
        assertMotion(10f, 3f, 10f, g.inSpace(GyroMouse.Motion(0f, 3f, 10f), GyroSpace.PLAYER, flat))
        val a = Math.toRadians(45.0)
        val up = floatArrayOf(0f, cos(a).toFloat(), sin(a).toFloat())
        // Only phone yaw while tilted 45°: world part 7.07, eased ×1.41 to ~10, capped at 10.
        assertEquals(9.97f, g.inSpace(GyroMouse.Motion(10f, 0f, 0f), GyroSpace.PLAYER, up).yaw, 0.05f)
    }
}
