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

class GyroTraceTest {
    @Test fun bucketsAverageAndKeepRatchets() {
        val t = com.oleroma.gyrophone.core.engine.GyroTrace(rateHz = 100, maxSeconds = 1)
        // 10 ms bucket from two 5 ms samples.
        t.add(10f, 0f, 0f, 0.005f, false)
        t.add(30f, 0f, 0f, 0.005f, true)
        val y = FloatArray(4); val p = FloatArray(4); val r = FloatArray(4); val h = BooleanArray(4)
        assertEquals(1, t.read(4, y, p, r, h))
        assertEquals(20f, y[0], 1e-3f)
        assertEquals(true, h[0])
    }

    @Test fun keepsTheNewestOldestFirst() {
        val t = com.oleroma.gyrophone.core.engine.GyroTrace(rateHz = 100, maxSeconds = 1)
        for (i in 0 until 150) t.add(i.toFloat(), 0f, 0f, 0.01f, false)
        val y = FloatArray(3); val p = FloatArray(3); val r = FloatArray(3); val h = BooleanArray(3)
        assertEquals(3, t.read(3, y, p, r, h))
        assertArrayEquals(floatArrayOf(147f, 148f, 149f), y, 1e-3f)
    }
}

class GyroSwayTest {
    @Test fun swingsThenSettles() {
        val s = com.oleroma.gyrophone.core.engine.GyroSway()
        repeat(100) { s.update(100f, 0f, 0f, 0.001f, 0.3f) } // 10° in 0.1 s
        assertEquals(true, s.x > 8f)
        repeat(3000) { s.update(0f, 0f, 0f, 0.001f, 0.3f) }
        assertEquals(0f, s.x, 0.01f)
    }
}

class GyroDisplayTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test fun displayControlRoundTripsAndHasNoInputs() {
        val d = com.oleroma.gyrophone.core.model.GyroDisplay(com.oleroma.gyrophone.core.model.GyroDisplayKind.HORIZON)
            .withColor(com.oleroma.gyrophone.core.model.GyroDisplayColor.HORIZON_SKY, 0x80112233)
        val c = com.oleroma.gyrophone.core.model.TouchControl(
            "disp1", com.oleroma.gyrophone.core.model.TouchKind.DISPLAY, "", 0.5f, 0.5f, display = d,
        )
        val back = json.decodeFromString(
            com.oleroma.gyrophone.core.model.TouchControl.serializer(),
            json.encodeToString(com.oleroma.gyrophone.core.model.TouchControl.serializer(), c),
        )
        assertEquals(c, back)
        assertEquals(0x80112233, back.display!!.color(com.oleroma.gyrophone.core.model.GyroDisplayColor.HORIZON_SKY))
        assertEquals(true, com.oleroma.gyrophone.core.model.Bindings.touchInputs(c).isEmpty())
    }

    @Test fun ratchetPressResetsTheGyroPhoneOnlyWhenSetTo() {
        val kind = com.oleroma.gyrophone.core.model.GyroDisplayKind.PHONE
        val on = com.oleroma.gyrophone.core.engine.GyroDisplayFeed("a", com.oleroma.gyrophone.core.model.GyroDisplay(kind))
        val off = com.oleroma.gyrophone.core.engine.GyroDisplayFeed("b", com.oleroma.gyrophone.core.model.GyroDisplay(kind, resetOnRatchet = false))
        val m = FloatArray(9)
        for (f in listOf(on, off)) {
            repeat(100) { f.feed(90f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
            f.feed(0f, 0f, 0f, 0.01f, 7, pressed = true, held = true)
        }
        on.pose.matrix(m); assertEquals(1f, m[8], 1e-4f)
        off.pose.matrix(m); assertEquals(0f, m[8], 1e-2f)
    }

    private fun feed(d: com.oleroma.gyrophone.core.model.GyroDisplay) = com.oleroma.gyrophone.core.engine.GyroDisplayFeed("f", d)
    private val ball = com.oleroma.gyrophone.core.model.GyroDisplayKind.BALL

    @Test fun easesBackOnceStillAfterTheDelay() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, ease = true, easeDelayMs = 200, easeSeconds = 0.5f))
        val m = FloatArray(9)
        repeat(50) { f.feed(0f, 90f, 0f, 0.01f, 7, pressed = false, held = false) } // 45° pitch
        repeat(10) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // still, inside the delay
        f.pose.matrix(m); assertEquals(0.707f, m[8], 1e-2f)
        repeat(100) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        f.pose.matrix(m); assertEquals(1f, m[8], 1e-3f)
    }

    @Test fun easesBackBySpeedAfterTheRatchetIsLetGo() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, ease = true, easeAfterRatchet = true, easeDelayMs = 0, easeBySpeed = true, easeSpeed = 90))
        val m = FloatArray(9)
        repeat(100) { f.feed(90f, 0f, 0f, 0.01f, 7, pressed = false, held = true) } // 90° yaw, held
        f.pose.matrix(m); assertEquals(0f, m[0], 1e-2f)
        repeat(50) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // half a second at 90°/s: 45° back
        assertEquals(45f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 1f)
    }

    @Test fun headingOnlyResetKeepsTheTilt() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, resetHeadingOnly = true))
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // 30° right
        assertEquals(30f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 0.5f)
        f.feed(0f, 0f, 0f, 0.01f, 7, pressed = true, held = true)
        assertEquals(0f, f.pose.heading(), 1e-3f)
    }

    @Test fun compassHoldsTheHeadingToNorth() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, gravity = true, compass = true))
        // Upright, facing the player; north is to the phone's right (and dips down): the player faces west.
        repeat(2000) {
            f.up(0f, 9.8f, 0f, 9.8f)
            f.north(20f, -40f, 0f)
            f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false)
        }
        assertEquals(-90f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 2f)
    }

    @Test fun trackballHoldsStillAgainstTheWorldUntilARatchetStopsIt() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, trackball = true))
        val m = FloatArray(9)
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // phone 30° right
        // The ball turned 30° the other way: its right side went back.
        f.pose.matrix(m); assertEquals(-0.5f, m[6], 1e-3f)
        val yawOff = 7 and com.oleroma.gyrophone.core.engine.MappingEngine.gyroBit(com.oleroma.gyrophone.core.model.MotionAxis.YAW).inv()
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, yawOff, pressed = true, held = true) } // yaw paused: no change, no reset
        f.pose.matrix(m); assertEquals(-0.5f, m[6], 1e-3f)
    }
}
