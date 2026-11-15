package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.PhoneMove
import org.junit.Test
import org.junit.Assert.assertTrue

class PhoneMoveTest {
    private val g = PhoneMove.G
    private val out = FloatArray(3)

    /** Feeds [seconds] of readings at 200 Hz, gravity up plus [up] m/s² upward. */
    private fun PhoneMove.run(seconds: Float, up: Float, t0: Long): Long {
        var t = t0
        repeat((seconds * 200).toInt()) {
            t += 5_000_000L
            turn(0f, 0f, 0f, 0.005f)
            push(0f, g + up, 0f, g, t, null, PhoneMove.STILL_BELOW, 1f)
        }
        return t
    }

    @Test
    fun stillPhoneStaysInTheMiddle() {
        val m = PhoneMove()
        m.run(3f, 0f, 0L)
        m.shift(out)
        assertTrue(kotlin.math.abs(out[1]) < 0.001f)
    }

    @Test
    fun nudgeUpSlidesUpThenGlidesBack() {
        val m = PhoneMove()
        var t = m.run(0.5f, 0f, 0L)
        t = m.run(0.15f, 4f, t)
        t = m.run(0.15f, -4f, t)
        m.shift(out)
        assertTrue("moved up: ${out[1]}", out[1] > 0.02f)
        m.run(3f, 0f, t)
        m.shift(out)
        assertTrue("back: ${out[1]}", kotlin.math.abs(out[1]) < 0.005f)
    }

    @Test
    fun turningIsNotMoving() {
        val m = PhoneMove()
        var t = m.run(0.5f, 0f, 0L)
        // Pitch 90°/s for 1 s: up turns from +y toward the screen (the phone tips back).
        var a = 0.0
        repeat(200) {
            t += 5_000_000L
            m.turn(0f, 90f, 0f, 0.005f)
            a += Math.toRadians(90.0) * 0.005
            // Up in the phone's frame after it turns by a about its x: (0, cos a, -sin a).
            m.push(0f, (g * kotlin.math.cos(a)).toFloat(), (-g * kotlin.math.sin(a)).toFloat(), g, t, null, PhoneMove.STILL_BELOW, 10f)
        }
        m.shift(out)
        val d = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        assertTrue("turn read as a move: $d", d < 0.02f)
    }

    @Test
    fun ratchetCentres() {
        val m = PhoneMove()
        var t = m.run(0.5f, 0f, 0L)
        m.run(0.2f, 4f, t)
        m.center()
        m.shift(out)
        assertTrue(out[1] == 0f)
    }
}
