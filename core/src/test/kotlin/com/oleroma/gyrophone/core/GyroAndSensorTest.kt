package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.model.GyroAxes
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.sensor.DirectReportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class GyroAndSensorTest {
    @Test fun fastTurnMovesMouseAndKeepsRemainder() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f, tighteningThreshold = 0f, smoothingThreshold = 0f))
        // 90 deg/s yaw around -X (phone turned right in landscape) for 1 s at 1 kHz -> 900 counts.
        val rad = (90.0 * Math.PI / 180.0).toFloat()
        var total = 0
        repeat(1000) { total += g.update(-rad, 0f, 0f, 0.001f).dx }
        assertEquals(900, total)
    }

    @Test fun portraitUsesItsOwnAxesAndLandscapeKeepsTheMainOnes() {
        val base = GyroSettings(sensitivity = 10f, tighteningThreshold = 0f, smoothingThreshold = 0f)
        val s = base.withAxes(portrait = true, GyroAxes(mouseX = MotionAxis.ROLL, invertX = true))
        val g = GyroMouse(s)
        val rad = (90.0 * Math.PI / 180.0).toFloat()
        // Portrait: roll clockwise is -z, inverted -> cursor left.
        g.displayRotation = 0
        var total = 0
        repeat(1000) { total += g.update(0f, 0f, -rad, 0.001f).dx }
        assertEquals(-900, total)
        // Landscape still uses yaw for X, unaffected by the portrait mapping.
        g.displayRotation = 90; g.reset(); total = 0
        repeat(1000) { total += g.update(-rad, 0f, 0f, 0.001f).dx }
        assertEquals(900, total)
    }

    @Test fun portraitFallsBackToLandscapeAxesWhenUnset() {
        val s = GyroSettings(mouseX = MotionAxis.ROLL, invertY = true)
        assertNull(s.portrait)
        assertEquals(s.landscapeAxes, s.axesFor(portrait = true))
        val edited = s.withAxes(portrait = false, s.landscapeAxes.copy(mouseY = MotionAxis.YAW))
        assertEquals(MotionAxis.YAW, edited.mouseY)
        assertNull(edited.portrait)
    }

    @Test fun verticalRatioScalesOnlyVertical() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f, verticalRatio = 0.5f))
        val rad = (90.0 * Math.PI / 180.0).toFloat()
        var dx = 0; var dy = 0
        // Landscape left: yaw right = -x, pitch up = -y (cursor up = negative HID y).
        repeat(1000) { val c = g.update(-rad, -rad, 0f, 0.001f); dx += c.dx; dy += c.dy }
        assertEquals(900, dx)
        assertEquals(-450, dy)
    }

    @Test fun tinyJitterIsTightened() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f, tighteningThreshold = 1.5f))
        var total = 0
        repeat(1000) { total += g.update(0.002f, 0f, 0f, 0.001f).dx } // ~0.11 deg/s noise
        assertTrue(total == 0)
    }

    @Test fun smoothingDoesNotReplayAFlickAfterThePhoneStops() {
        // Optional smoothing at 4 deg/s, sensitivity 16: a 100 ms flick at 200 deg/s, then still.
        val rad = (200.0 * Math.PI / 180.0).toFloat()
        for (dt in listOf(1f / 800, 1f / 200, 1f / 50)) {
            val g = GyroMouse(GyroSettings(sensitivity = 16f, smoothingThreshold = 4f))
            val n = (0.1f / dt).roundToInt()
            var flick = 0; var tail = 0
            repeat(n) { flick += g.update(-rad, 0f, 0f, dt).dx }
            repeat(2 * n + 16) { tail += g.update(0f, 0f, 0f, dt).dx }
            assertEquals("flick at dt=$dt", 320, flick)
            assertEquals("tail at dt=$dt", 0, tail)
        }
    }

    @Test fun smoothingKeepsTheTotalOfSlowMotion() {
        // 1 deg/s is fully smoothed at a 4 deg/s threshold: it arrives spread out, but all of it.
        val rad = (1.0 * Math.PI / 180.0).toFloat()
        val g = GyroMouse(GyroSettings(sensitivity = 1000f, smoothingThreshold = 4f))
        var total = 0
        repeat(1000) { total += g.update(-rad, 0f, 0f, 0.001f).dx }
        repeat(16) { total += g.update(0f, 0f, 0f, 0.001f).dx }
        assertEquals(1000, total)
    }

    @Test fun rawPathLeavesTheSmoothingWindowAlone() {
        // With smoothing off, motion must not be remembered: turning smoothing on later at rest moves nothing.
        val rad = (200.0 * Math.PI / 180.0).toFloat()
        val g = GyroMouse(GyroSettings(sensitivity = 16f, smoothingThreshold = 0f))
        repeat(100) { g.update(-rad, 0f, 0f, 0.001f) }
        g.settings = g.settings.copy(smoothingThreshold = 4f)
        var tail = 0
        repeat(16) { tail += g.update(0f, 0f, 0f, 0.001f).dx }
        assertEquals(0, tail)
    }

    @Test fun directChannelParserReturnsNewEventsInOrder() {
        val n = 4
        val buf = ByteBuffer.allocate(n * DirectReportParser.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fun put(slot: Int, counter: Int, x: Float) {
            val b = slot * DirectReportParser.EVENT_SIZE
            buf.putInt(b, DirectReportParser.EVENT_SIZE); buf.putInt(b + 4, 7); buf.putInt(b + 8, 4)
            buf.putInt(b + 12, counter); buf.putLong(b + 16, counter * 1000L); buf.putFloat(b + 24, x)
        }
        put(0, 5, 0.5f); put(1, 2, 0.2f); put(2, 3, 0.3f); put(3, 4, 0.4f)
        val p = DirectReportParser(n)
        assertEquals(listOf(2L, 3L, 4L, 5L), p.parse(buf.array(), 7).map { it.counter })
        put(1, 6, 0.6f)
        val next = p.parse(buf.array(), 7)
        assertEquals(1, next.size)
        assertEquals(0.6f, next[0].x)
    }
}

class GyroOrientationTest {
    private val rad = (90.0 * Math.PI / 180.0).toFloat()
    private fun direct(s: GyroSettings) = s.copy(sensitivity = 10f, tighteningThreshold = 0f, smoothingThreshold = 0f)

    private fun run(g: GyroMouse, x: Float, y: Float, z: Float): Pair<Int, Int> {
        var dx = 0; var dy = 0
        repeat(100) { val c = g.update(x, y, z, 0.001f); dx += c.dx; dy += c.dy }
        return dx to dy
    }

    @Test fun landscapeLeftAimUpMovesCursorUp() {
        val g = GyroMouse(direct(GyroSettings(orientation = GyroOrientation.LANDSCAPE_LEFT)))
        // Nose up in landscape-left is negative rotation about device Y.
        assertEquals(0 to -90, run(g, 0f, -rad, 0f))
    }

    @Test fun autoFollowsDisplayRotation() {
        val g = GyroMouse(direct(GyroSettings()))
        g.displayRotation = 270
        // Turning right with the top on the right is positive rotation about device X.
        assertEquals(90 to 0, run(g, rad, 0f, 0f))
    }

    @Test fun rollCanDriveHorizontalAndAxesInvert() {
        val g = GyroMouse(direct(GyroSettings(orientation = GyroOrientation.LANDSCAPE_LEFT,
            mouseX = MotionAxis.ROLL, mouseY = MotionAxis.NONE)))
        // Clockwise roll as seen by the player is negative rotation about device Z.
        assertEquals(90 to 0, run(g, 0f, 0f, -rad))
        g.settings = g.settings.copy(invertX = true)
        assertEquals(-90 to 0, run(g, 0f, 0f, -rad))
    }

    @Test fun flatYawIsRotationAboutScreenNormal() {
        val g = GyroMouse(direct(GyroSettings(orientation = GyroOrientation.FLAT)))
        assertEquals(90 to 0, run(g, 0f, 0f, -rad))
    }
}
