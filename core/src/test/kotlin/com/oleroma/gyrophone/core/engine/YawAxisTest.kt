package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.YawAxis
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class YawAxisTest {
    private val mouse = GyroMouse()

    // The phone's frame vector (right, up, toward the player) of a motion.
    private fun vec(m: GyroMouse.Motion) = floatArrayOf(m.pitch, -m.yaw, -m.roll)
    private fun motion(r: Float, u: Float, t: Float) = GyroMouse.Motion(yaw = -u, pitch = r, roll = -t)

    @Test
    fun screenZIsToday() {
        val m = motion(1f, 2f, 3f)
        assertEquals(m, mouse.withYawAxis(m, YawAxis.SCREEN_Z))
    }

    @Test
    fun screenYTurnsTheVerticalIntoYaw() {
        // Upright, turning left and right is a turn about the screen's vertical: shown yaw (stored roll).
        val turn = mouse.withYawAxis(motion(0f, 1f, 0f), YawAxis.SCREEN_Y)
        assertEquals(0f, turn.yaw, 0f); assertEquals(0f, turn.pitch, 0f); assertEquals(-1f, turn.roll, 0f)
        // The same sign a flat phone turning about its through-screen axis (pointing up) gets.
        assertEquals(-1f, motion(0f, 0f, 1f).roll, 0f)
        // Through the screen becomes shown roll (stored yaw); across stays pitch.
        val through = mouse.withYawAxis(motion(0f, 0f, 1f), YawAxis.SCREEN_Y)
        assertEquals(-1f, through.yaw, 0f); assertEquals(0f, through.roll, 0f)
        assertEquals(1f, mouse.withYawAxis(motion(1f, 0f, 0f), YawAxis.SCREEN_Y).pitch, 0f)
    }

    @Test
    fun screenXTurnsTheHorizontalIntoYaw() {
        val turn = mouse.withYawAxis(motion(1f, 0f, 0f), YawAxis.SCREEN_X)
        assertEquals(0f, turn.yaw, 0f); assertEquals(0f, turn.pitch, 0f); assertEquals(-1f, turn.roll, 0f)
        assertEquals(1f, mouse.withYawAxis(motion(0f, 1f, 0f), YawAxis.SCREEN_X).let { -it.yaw }, 0f)
    }

    @Test
    fun eachAxisIsARotation() {
        // A rotation keeps the frame right-handed: the images of right and up cross to the image of toward.
        for (ax in YawAxis.entries) {
            val r = vec(mouse.withYawAxis(motion(1f, 0f, 0f), ax))
            val u = vec(mouse.withYawAxis(motion(0f, 1f, 0f), ax))
            val t = vec(mouse.withYawAxis(motion(0f, 0f, 1f), ax))
            val c = floatArrayOf(r[1] * u[2] - r[2] * u[1], r[2] * u[0] - r[0] * u[2], r[0] * u[1] - r[1] * u[0])
            assertArrayEquals("$ax", t, c, 0f)
        }
    }

    @Test
    fun setGripPicksTheAxisPointingUp() {
        assertEquals(YawAxis.SCREEN_Z, YawAxis.nearest(floatArrayOf(0.1f, 0.3f, 0.9f)))
        assertEquals(YawAxis.SCREEN_Y, YawAxis.nearest(floatArrayOf(0.1f, 0.9f, 0.4f)))
        assertEquals(YawAxis.SCREEN_Y, YawAxis.nearest(floatArrayOf(0.1f, -0.9f, 0.4f)))
        assertEquals(YawAxis.SCREEN_X, YawAxis.nearest(floatArrayOf(-0.95f, 0.1f, 0.2f)))
    }
}
