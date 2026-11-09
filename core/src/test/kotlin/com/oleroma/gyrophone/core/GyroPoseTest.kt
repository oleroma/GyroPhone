package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroPose
import com.oleroma.gyrophone.core.model.withEase
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

    @Test fun ratchetHeldEasesTheGyroPhoneBackOnlyWhenSetTo() {
        val kind = com.oleroma.gyrophone.core.model.GyroDisplayKind.PHONE
        val on = com.oleroma.gyrophone.core.engine.GyroDisplayFeed("a", com.oleroma.gyrophone.core.model.GyroDisplay(kind, ease = true, easeDelayMs = 0))
        val off = com.oleroma.gyrophone.core.engine.GyroDisplayFeed("b", com.oleroma.gyrophone.core.model.GyroDisplay(kind, ease = false))
        val m = FloatArray(9)
        for (f in listOf(on, off)) {
            repeat(100) { f.feed(90f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
            f.feed(0f, 0f, 0f, 0.01f, 0, pressed = true, held = true)
            repeat(200) { f.feed(0f, 0f, 0f, 0.01f, 0, pressed = false, held = true) }
        }
        on.pose.matrix(m); assertEquals(1f, m[0], 1e-4f)
        off.pose.matrix(m); assertEquals(0f, m[0], 1e-2f)
    }

    @Test fun easingOnRatchetStopsWhenLetGo() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, ease = true, easeDelayMs = 0, easeBySpeed = true, easeSpeed = 90))
        repeat(100) { f.feed(90f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // 90° yaw
        f.feed(0f, 0f, 0f, 0.01f, 0, pressed = true, held = true)
        repeat(49) { f.feed(0f, 0f, 0f, 0.01f, 0, pressed = false, held = true) } // half a second at 90°/s: 45° back
        assertEquals(45f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 1.5f)
        repeat(100) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // let go: stays
        assertEquals(45f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 1.5f)
    }

    private fun feed(d: com.oleroma.gyrophone.core.model.GyroDisplay) = com.oleroma.gyrophone.core.engine.GyroDisplayFeed("f", d)
    private val ball = com.oleroma.gyrophone.core.model.GyroDisplayKind.BALL

    @Test fun easesBackOnceStillAfterTheDelay() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, ease = true, easeOnRatchet = false, easeDelayMs = 200, easeSeconds = 0.5f))
        val m = FloatArray(9)
        repeat(50) { f.feed(0f, 90f, 0f, 0.01f, 7, pressed = false, held = false) } // 45° pitch
        repeat(10) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // still, inside the delay
        f.pose.matrix(m); assertEquals(0.707f, m[8], 1e-2f)
        repeat(100) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        f.pose.matrix(m); assertEquals(1f, m[8], 1e-3f)
    }

    @Test fun headingOnlyEaseKeepsTheTilt() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, ease = true, easeHeadingOnly = true, easeDelayMs = 0, easeSeconds = 0.1f))
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // 30° right
        repeat(20) { f.feed(0f, 40f, 0f, 0.01f, 7, pressed = false, held = false) } // 8° up
        assertEquals(30f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 1f)
        f.feed(0f, 0f, 0f, 0.01f, 0, pressed = true, held = true)
        repeat(100) { f.feed(0f, 0f, 0f, 0.01f, 0, pressed = false, held = true) }
        assertEquals(0f, f.pose.heading(), 1e-3f)
        // Still tipped up: not straight.
        assertEquals(8f, Math.toDegrees(f.pose.angle().toDouble()).toFloat(), 1f)
    }

    @Test fun oldSettingsCarryOverAsThePcOverlaysDid() {
        val old = com.oleroma.gyrophone.core.model.GyroDisplay(ball, resetOnRatchet = true, resetHeadingOnly = true, levelDeg = 90).migrated()
        assertEquals(com.oleroma.gyrophone.core.model.GyroDisplay.VERSION, old.v)
        assertEquals(true, old.ease)
        assertEquals(true, old.easeOnRatchet)
        assertEquals(true, old.easeHeadingOnly)
        assertEquals(0.707f, old.rest!![0], 1e-3f)
        assertEquals(-0.707f, old.rest!![1], 1e-3f)
        val noReset = com.oleroma.gyrophone.core.model.GyroDisplay(ball, resetOnRatchet = false, ease = true, easeAfterRatchet = true).migrated()
        assertEquals(true, noReset.ease)
        assertEquals(false, noReset.easeOnRatchet)
        assertEquals(null, noReset.rest)
        val off = com.oleroma.gyrophone.core.model.GyroDisplay(ball, resetOnRatchet = false).migrated()
        assertEquals(false, off.ease)
        // A new one is current and keeps its own settings.
        val n = com.oleroma.gyrophone.core.model.GyroDisplay.create(ball)
        assertEquals(n, n.migrated())
        assertEquals(true, n.gravity)
    }

    @Test fun levelledFlatShowsFaceOn() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay.create(ball).copy(ease = false))
        // Held flat, screen up.
        repeat(300) { f.up(0f, 0f, 9.8f, 9.8f); f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        val m = FloatArray(9)
        f.shown(m); assertEquals(0f, m[8], 0.05f)
        val rest = com.oleroma.gyrophone.core.engine.GyroPose.tiltFromUp(0f, 0f, 9.8f)!!
        val tilt = f.pose.tiltOnly()
        for (i in 0..3) assertEquals(tilt[i], rest[i], 1e-2f)
        f.display = f.display.copy(rest = rest.toList())
        repeat(2) { f.up(0f, 0f, 9.8f, 9.8f); f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        f.shown(m); assertEquals(1f, m[8], 1e-3f)
    }

    @Test fun compassHoldsTheHeadingWhereItStartedNotNorth() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, gravity = true, compass = true))
        // Upright, facing the player; north is to the phone's right (and dips down): the player faces west.
        fun still(n: Int, nx: Float, nz: Float, yaw: Float = 0f) = repeat(n) {
            f.up(0f, 9.8f, 0f, 9.8f)
            f.north(nx, -40f, nz)
            f.feed(yaw, 0f, 0f, 0.01f, 7, pressed = false, held = false)
        }
        fun heading() = Math.toDegrees(f.pose.heading().toDouble()).toFloat()
        still(2000, 20f, 0f)
        assertEquals(0f, heading(), 1f)
        // A gyro drift of 5°/s while the magnetometer says nothing turned: held at the start.
        still(1000, 20f, 0f, yaw = 5f)
        assertEquals(0f, heading(), 6f)
        still(1000, 20f, 0f)
        assertEquals(0f, heading(), 1f)
        // The phone really turned 90° right: north now ahead of it (into the screen).
        repeat(100) { f.feed(90f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        still(2000, 0f, -20f)
        assertEquals(90f, heading(), 2f)
        // Aimed again there: that way is 0 now.
        f.aimHere()
        still(2000, 0f, -20f)
        assertEquals(0f, heading(), 1f)
    }

    @Test fun compassRatchetResetAimsWhereThePhoneFaces() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, gravity = true, compass = true))
        fun sample(nx: Float, nz: Float, pressed: Boolean = false) {
            f.up(0f, 9.8f, 0f, 9.8f)
            f.north(nx, -40f, nz)
            f.feed(0f, 0f, 0f, 0.01f, 7, pressed = pressed, held = pressed)
        }
        repeat(500) { sample(20f, 0f) }
        // Turned 90° left by hand (the magnetometer sees north behind it): heading -90.
        repeat(100) { f.feed(-90f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        repeat(1000) { sample(0f, 20f) }
        assertEquals(-90f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 2f)
        sample(0f, 20f, pressed = true)
        repeat(500) { sample(0f, 20f) }
        assertEquals(0f, Math.toDegrees(f.pose.heading().toDouble()).toFloat(), 1f)
    }

    @Test fun cameraTurnsWithThePhone() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.CAMERA))
        f.cameraMap = 1 or (2 shl 8) // yaw moves the mouse sideways
        val m = FloatArray(9)
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // phone 30° right
        // Turned 30° the same way, as the camera: its right side came forward.
        f.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
    }

    @Test fun cameraOnAnyPoseDisplayEasesBackOnRatchet() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(com.oleroma.gyrophone.core.model.GyroDisplayKind.HORIZON,
            turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.CAMERA, gravity = true, ease = true, easeDelayMs = 0, easeSeconds = 0.2f))
        f.cameraMap = 1 or (2 shl 8) // yaw moves the mouse sideways
        val m = FloatArray(9)
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // camera 30° right
        f.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f) // gravity is ignored: no accelerometer needed
        assertEquals(false, f.display.needsGravity)
        f.feed(0f, 0f, 0f, 0.01f, 0, pressed = true, held = true)
        repeat(100) { f.feed(0f, 0f, 0f, 0.01f, 0, pressed = false, held = true) }
        f.pose.matrix(m); assertEquals(1f, m[0], 1e-4f)
    }

    @Test fun savedTrackballsCarryOver() {
        val G = com.oleroma.gyrophone.core.model.GyroDisplay
        assertEquals(com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL, com.oleroma.gyrophone.core.model.GyroDisplay(ball, trackball = true).migrated().turnsWith)
        assertEquals(com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL, com.oleroma.gyrophone.core.model.GyroDisplay(ball, v = 2, trackball = true).migrated().turnsWith)
        assertEquals(com.oleroma.gyrophone.core.model.TurnsWith.CAMERA, com.oleroma.gyrophone.core.model.GyroDisplay(ball, v = 2, trackball = true, trackballInvert = true).migrated().turnsWith)
        assertEquals(com.oleroma.gyrophone.core.model.TurnsWith.PHONE, com.oleroma.gyrophone.core.model.GyroDisplay(ball, v = 2).migrated().turnsWith)
        assertEquals(G.VERSION, com.oleroma.gyrophone.core.model.GyroDisplay(ball, v = 2).migrated().v)
    }

    @Test fun trackballTurnsAsItsAxesSayUntilARatchetStopsIt() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL))
        val m = FloatArray(9)
        repeat(50) { f.feed(0f, 0f, 60f, 0.01f, 7, pressed = false, held = false) } // phone 30° right (the default gyro mouse's sideways turn)
        // By default as the camera: its right side came forward.
        f.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
        // Nothing on the axis through the screen: the up and down roll of the phone (stored yaw) does nothing.
        repeat(50) { f.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        f.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
        val rollOff = 7 and com.oleroma.gyrophone.core.engine.MappingEngine.gyroBit(com.oleroma.gyrophone.core.model.MotionAxis.ROLL).inv()
        repeat(50) { f.feed(0f, 0f, 60f, 0.01f, rollOff, pressed = true, held = true) } // roll paused: no change, no reset
        f.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
        // The up axis inverted: the same turn rolls it the other way.
        val inv = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL, trackballAxes = com.oleroma.gyrophone.core.model.TRACKBALL_AS_CAMERA or 0x80))
        repeat(50) { inv.feed(0f, 0f, 60f, 0.01f, 7, pressed = false, held = false) }
        inv.pose.matrix(m); assertEquals(-0.5f, m[6], 1e-3f)
        // Pitch on the up axis, nothing on the others: a yaw turn does nothing.
        val swapped = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL, trackballAxes = 2))
        repeat(50) { swapped.feed(60f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        swapped.pose.matrix(m); assertEquals(0f, m[6], 1e-3f)
        repeat(50) { swapped.feed(0f, 60f, 0f, 0.01f, 7, pressed = false, held = false) }
        swapped.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
    }

    @Test fun trackballTurnsWithOtherMouseCountsExactlyAsTheGameCamera() {
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL))
        val g = com.oleroma.gyrophone.core.model.GyroSettings(gameCountsPer360 = 8000f)
        val routes = com.oleroma.gyrophone.core.model.Bindings.gyroMouseMappings()
        val m = FloatArray(9)
        // Three whole camera turns in small swipes: back where it started.
        repeat(600) { f.camera(40, 0, routes, g) }
        f.pose.matrix(m); assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), m, 1e-3f)
        // A quarter turn more: as far as a quarter of the gyro's roll would turn it.
        f.camera(2000, 0, routes, g)
        val turned = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL))
        val rws = g.rwsOf(g.sensitivityOf(com.oleroma.gyrophone.core.model.MotionAxis.ROLL))!!
        repeat(100) { turned.feed(0f, 0f, 90f / rws, 0.01f, 7, pressed = false, held = false, gyro = g) }
        val m2 = FloatArray(9)
        f.pose.matrix(m); turned.pose.matrix(m2)
        assertArrayEquals(m2, m, 1e-3f)
        assertEquals(1f, kotlin.math.abs(m[2]), 1e-3f) // a quarter turn left or right (roll is the camera's sideways turn)
    }

    @Test fun aLockedTrackballAxisNeverTurnsAndPitchStops() {
        val tb = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL
        val m = FloatArray(9)
        fun run(axes: Int): FloatArray {
            val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = tb, trackballAxes = axes))
            repeat(100) { f.feed(0f, 0f, 90f, 0.01f, 7, pressed = false, held = false) } // 90° right
            repeat(100) { f.feed(0f, 30f, 0f, 0.01f, 7, pressed = false, held = false) } // 30° up
            repeat(100) { f.feed(0f, 0f, -90f, 0.01f, 7, pressed = false, held = false) } // 90° back left
            f.pose.matrix(m)
            return m.copyOf()
        }
        // As the camera (the default, Yaw locked): straight ahead, 30° up, its right still level.
        val cam = run(com.oleroma.gyrophone.core.model.TRACKBALL_AS_CAMERA)
        assertEquals(0f, cam[3], 1e-3f)
        assertEquals(0f, cam[2], 1e-3f)
        assertEquals(0.5f, -cam[5], 1e-3f)
        // Roll on None (no lock flag): None holds it, so the same moves leave it unrolled, as the camera.
        val none = run(3 or (2 shl 8))
        assertEquals(0f, none[3], 1e-3f)
        // Turning about its own axes: pitching up turns it up as the camera does.
        val own = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = tb, trackballOwnAxes = true))
        repeat(100) { own.feed(0f, 30f, 0f, 0.01f, 7, pressed = false, held = false) }
        own.pose.matrix(m)
        assertEquals(0.5f, -m[5], 1e-3f)
        // Pitch locked (a turntable): pitching does nothing.
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = tb, trackballAxes = 3 or ((2 or com.oleroma.gyrophone.core.model.TRACKBALL_LOCK) shl 8) or (1 shl 16)))
        repeat(100) { f.feed(0f, 60f, 0f, 0.01f, 7, pressed = false, held = false) }
        f.pose.matrix(m); assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), m, 1e-4f)
        // With Yaw locked it stops at straight up.
        val up = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = tb))
        repeat(100) { up.feed(0f, 200f, 0f, 0.01f, 7, pressed = false, held = false) }
        up.pose.matrix(m); assertEquals(kotlin.math.sin(Math.toRadians(89.0)).toFloat(), -m[5], 1e-4f)
    }

    @Test fun trackballAxesEaseBackEachOnItsOwn() {
        val G = com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL)
        val m = FloatArray(9)
        // Left and right eases back once the phone stops; up and down stays.
        val f = feed(G.withEase(0, com.oleroma.gyrophone.core.model.AxisEase(on = true, onRatchet = false, delayMs = 0, seconds = 0.2f)))
        repeat(50) { f.feed(0f, 0f, 60f, 0.01f, 7, pressed = false, held = false) } // 30° right
        repeat(50) { f.feed(0f, 60f, 0f, 0.01f, 7, pressed = false, held = false) } // 30° up
        repeat(100) { f.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) }
        f.pose.matrix(m)
        assertEquals(0f, m[2], 1e-4f)
        assertEquals(0.5f, -m[5], 1e-3f)
        // Up and down eases back while a ratchet is held, by speed; left and right stays.
        val r = feed(G.withEase(1, com.oleroma.gyrophone.core.model.AxisEase(on = true, delayMs = 0, bySpeed = true, speed = 90)))
        repeat(50) { r.feed(0f, 0f, 60f, 0.01f, 7, pressed = false, held = false) } // 30° right
        repeat(50) { r.feed(0f, 60f, 0f, 0.01f, 7, pressed = false, held = false) } // 30° up
        repeat(20) { r.feed(0f, 0f, 0f, 0.01f, 7, pressed = false, held = false) } // still, no ratchet: stays
        r.pose.matrix(m); assertEquals(0.5f, -m[5], 1e-3f)
        r.feed(0f, 0f, 0f, 0.01f, 0, pressed = true, held = true)
        repeat(50) { r.feed(0f, 0f, 0f, 0.01f, 0, pressed = false, held = true) } // 0.5 s at 90°/s: back
        r.pose.matrix(m)
        assertEquals(0f, m[5], 1e-4f)
        assertEquals(0.5f, m[6], 1e-3f)
    }

    @Test fun pitchWithoutItsStopGoesOverTheTopAndRightStaysRight() {
        val axes = com.oleroma.gyrophone.core.model.TRACKBALL_AS_CAMERA and (com.oleroma.gyrophone.core.model.TRACKBALL_STOP shl 8).inv()
        val f = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL, trackballAxes = axes))
        val m = FloatArray(9)
        repeat(100) { f.feed(0f, 120f, 0f, 0.01f, 7, pressed = false, held = false) } // 120° up: over the top
        f.pose.matrix(m)
        assertEquals(0.866f, -m[5], 1e-3f)
        assertEquals(0.5f, -m[8], 1e-3f) // the beam now points back toward you
        repeat(20) { f.feed(0f, 0f, 60f, 0.01f, 7, pressed = false, held = false) } // turn right
        f.pose.matrix(m)
        org.junit.Assert.assertTrue(-m[2] > 0.05f) // the beam went right on screen
        // A stopped Roll (left and right) turns no further than sideways.
        val side = feed(com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL,
            trackballAxes = com.oleroma.gyrophone.core.model.TRACKBALL_AS_CAMERA or com.oleroma.gyrophone.core.model.TRACKBALL_STOP))
        repeat(100) { side.feed(0f, 0f, 150f, 0.01f, 7, pressed = false, held = false) }
        side.pose.matrix(m)
        assertEquals(1f, -m[2], 1e-3f)
    }

    @Test fun trackballWithoutRwsSpinsOtherCountsAsTheGyro() {
        val d = com.oleroma.gyrophone.core.model.GyroDisplay(ball, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL, trackballRws = false, trackballSpin = 2f)
        val f = feed(d)
        val g = com.oleroma.gyrophone.core.model.GyroSettings(gameCountsPer360 = 8000f)
        val pitch = g.sensitivityOf(com.oleroma.gyrophone.core.model.MotionAxis.PITCH)
        f.camera(0, -(45 * pitch).toInt(), com.oleroma.gyrophone.core.model.Bindings.gyroMouseMappings(), g) // a 45° pitch up's counts (up is -), 2×
        val turned = feed(d)
        repeat(100) { turned.feed(0f, 4500f / 100f, 0f, 0.01f, 7, pressed = false, held = false, gyro = g) }
        val m = FloatArray(9)
        val m2 = FloatArray(9)
        f.pose.matrix(m); turned.pose.matrix(m2)
        assertArrayEquals(m2, m, 1e-3f)
    }

    @Test fun turningAboutItsOwnAxesYawsAboutItsOwnUp() {
        val p = GyroPose()
        val m = FloatArray(9)
        // Pitch up 60°, then yaw 30° about its own (tilted) up: where it faces stays square to that up.
        p.turnOwn((60.0 * Math.PI / 180).toFloat(), 0f, 0f)
        p.matrix(m)
        val up = floatArrayOf(m[1], m[4], m[7])
        p.turnOwn(0f, (30.0 * Math.PI / 180).toFloat(), 0f)
        p.matrix(m)
        // Its up is unchanged by a turn about it, and it hasn't rolled (its right stays square to that up).
        assertEquals(up[0], m[1], 1e-4f)
        assertEquals(up[1], m[4], 1e-4f)
        assertEquals(up[2], m[7], 1e-4f)
        assertEquals(0f, m[0] * up[0] + m[3] * up[1] + m[6] * up[2], 1e-4f)
    }
}
