package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.LaserTrace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
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

    @Test fun sphereTurnsKeepsTheBeamAhead() {
        // The phone turned 90° right: its back points to the right.
        val m = floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f)
        val b = FloatArray(3)
        LaserTrace.beam(m, b)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), b, 1e-6f)
        val p = FloatArray(3)
        LaserTrace.view(b[0], b[1], b[2], p, axon = false, turned = m)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), p, 1e-6f) // still ahead
        LaserTrace.view(0f, 0f, -1f, p, axon = false, turned = m)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f), p, 1e-6f) // what was ahead went left
        val (front, back) = LaserTrace.grid(false, m)
        assertTrue(front.isNotEmpty() && back.isNotEmpty())
    }

    @Test fun rifleShowsItsNearFacesFarthestFirst() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val faces = LaserTrace.rifle(id, axon = true, turned = null)
        assertEquals(30, faces.size) // 10 boxes, 3 faces of each toward the viewer
        for (i in 1 until faces.size) assertTrue(faces[i - 1].depth <= faces[i].depth)
        for (f in faces) assertTrue(f.light in 0f..1f)
    }

    @Test fun aTrackballFacesTheWayPicked() {
        val want = arrayOf(floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f))
        val b = FloatArray(3)
        for (k in 0..5) {
            val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            LaserTrace.faces(m, k)
            LaserTrace.beam(m, b)
            assertArrayEquals(want[k], b, 1e-6f)
        }
    }

    @Test fun aimsWithTheRollAxis() {
        val b = FloatArray(3)
        val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        LaserTrace.aim(m)
        LaserTrace.beam(m, b)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), b, 1e-6f) // at rest into the screen
        // A yaw turn (through the screen) of 90° clockwise: the top edge goes right.
        val yaw = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        LaserTrace.aim(yaw)
        LaserTrace.beam(yaw, b)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), b, 1e-6f)
        // Pitch up 90° (the top comes toward the player): the beam points up.
        val pitch = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        LaserTrace.aim(pitch)
        LaserTrace.beam(pitch, b)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), b, 1e-6f)
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

    private val normalMap = longArrayOf(0xFFFF8080, 0xFF008080, 0xFF80FF80, 0xFF800080, 0xFF8080FF, 0xFF808000)

    @Test fun wayColoursBlendAsANormalMap() {
        fun rgb(d: FloatArray) = LaserTrace.directionColor(d, normalMap).let { intArrayOf((it shr 16 and 0xFF).toInt(), (it shr 8 and 0xFF).toInt(), (it and 0xFF).toInt()) }
        assertArrayEquals(intArrayOf(255, 128, 128), rgb(floatArrayOf(1f, 0f, 0f)))
        assertArrayEquals(intArrayOf(0, 128, 128), rgb(floatArrayOf(-1f, 0f, 0f)))
        assertArrayEquals(intArrayOf(128, 128, 0), rgb(floatArrayOf(0f, 0f, -1f)))
        val s = (Math.sqrt(0.5)).toFloat()
        // A slant: 0.5 + 0.5·d each.
        val c = rgb(floatArrayOf(s, -s, 0f))
        assertEquals(128 + 0.5 * s * 255, c[0].toDouble(), 2.0)
        assertEquals(128 - 0.5 * s * 255, c[1].toDouble(), 2.0)
        assertEquals(128, c[2])
        assertEquals(0xFF, (LaserTrace.directionColor(floatArrayOf(1f, 0f, 0f), normalMap) shr 24).toInt())
    }

    @Test fun aStrokesWayEvensOutWithinItsLine() {
        val t = LaserTrace()
        val rad = (Math.PI / 180).toFloat()
        // Right along the equator, a wobble up in the middle.
        for (k in 0..8) {
            val lon = k * rad
            val lat = if (k == 4) 1f * rad else 0f
            val p = FloatArray(3)
            LaserTrace.dir(lat, lon, p)
            t.add(p[0], p[1], p[2], k * 10L, draw = true, held = false, smooth = true)
        }
        val raw = FloatArray(3)
        t.strokes.direction(4, 0, raw)
        val smooth = FloatArray(3)
        t.strokes.direction(4, LaserTrace.SMOOTH_REACH, smooth)
        assertTrue(raw[1] > 0.3f)
        assertTrue(smooth[1] < raw[1] / 3f)
        assertTrue(smooth[0] > 0.9f)
    }

    @Test fun theBeamsWayStaysWhenItStops() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = false, held = false)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), t.moving, 0f)
        t.add(0f, 0.05f, -1f, 10, draw = false, held = false, smooth = false)
        assertTrue(t.moving[1] > 0.99f)
        t.add(0f, 0.05f, -1f, 20, draw = false, held = false, smooth = false)
        assertTrue(t.moving[1] > 0.99f)
        // Smoothed, a sudden turn right takes a moment.
        t.add(0.05f, 0.05f, -1f, 30, draw = false, held = false, smooth = true)
        assertTrue(t.moving[0] > 0.1f && t.moving[1] > 0.1f)
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

    @Test fun aCrosshairLiesOnTheSphereWhereTheBeamLands() {
        val rad = (Math.PI / 180).toFloat()
        // Straight (beam into the screen), the phone rolled 90°: its up now points sideways.
        val m = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val b = FloatArray(3)
        LaserTrace.beam(m, b)
        run {
            val ch = LaserTrace.crosshair(m, b, 0, 10 * rad, 2 * rad, dot = true)
            assertEquals(2, ch.lines.size)
            for (l in ch.lines) for (i in l.indices step 3) {
                // Facing the centre: every point on the plane touching the sphere there.
                assertEquals(1f, l[i] * b[0] + l[i + 1] * b[1] + l[i + 2] * b[2], 1e-4f)
            }
            // Its second arm runs along the pose's up: sideways now.
            val up = ch.lines[1]
            val dx = up[3] - up[0]
            val dy = up[4] - up[1]
            assertEquals(0f, dy, 1e-4f)
            assertEquals(20 * rad, kotlin.math.abs(dx), 1e-4f)
            assertNotNull(ch.dot)
        }
        // Each shape: a T has three arms, a dot only its dot.
        assertEquals(3, LaserTrace.crosshair(m, b, 5, 0.2f, 0.05f, dot = false).lines.size)
        val dot = LaserTrace.crosshair(m, b, 4, 0.2f, 0.05f, dot = false)
        assertEquals(0, dot.lines.size)
        assertNotNull(dot.dot)
    }

    @Test fun firingPaintsInItsOwnColoursAndCanBeTheOnlyPaint() {
        val t = LaserTrace()
        fun aim(deg: Int, fire: Boolean, only: Boolean = false) {
            val a = Math.toRadians(deg.toDouble()).toFloat()
            t.add(kotlin.math.sin(a), 0f, -kotlin.math.cos(a), deg.toLong(), draw = true, held = false, fire = fire, fireOnly = only)
        }
        aim(0, false); aim(2, false); aim(4, true); aim(6, true)
        assertEquals(4, t.strokes.count)
        assertFalse(t.strokes.fire(1))
        assertTrue(t.strokes.fire(2))
        // Only while firing: aiming adds nothing and the next shot starts a new line.
        aim(8, false, only = true); aim(10, false, only = true)
        assertEquals(4, t.strokes.count)
        aim(12, true, only = true)
        assertEquals(5, t.strokes.count)
        assertTrue(t.strokes.gap(4))
    }

    @Test fun fireIsHeldWhileItsOutputIsSent() {
        val F = com.oleroma.gyrophone.core.engine.LaserFire
        val none = com.oleroma.gyrophone.core.hid.HidState()
        assertFalse(F.held(F.DEFAULT, none))
        assertTrue(F.held(F.DEFAULT, com.oleroma.gyrophone.core.hid.HidState(mouse = com.oleroma.gyrophone.core.hid.MouseState(buttons = 1))))
        assertTrue(F.held(F.KEY + 0x2C, com.oleroma.gyrophone.core.hid.HidState(keyboard = com.oleroma.gyrophone.core.hid.KeyboardState(keys = listOf(0x2C)))))
        assertTrue(F.held(F.KEY + 0xE1, com.oleroma.gyrophone.core.hid.HidState(keyboard = com.oleroma.gyrophone.core.hid.KeyboardState(modifiers = 2))))
        assertTrue(F.held(F.PAD + 5, com.oleroma.gyrophone.core.hid.HidState(gamepad = com.oleroma.gyrophone.core.hid.GamepadState(buttons = 32))))
        assertTrue(F.held(F.RT, com.oleroma.gyrophone.core.hid.HidState(gamepad = com.oleroma.gyrophone.core.hid.GamepadState(rt = 900))))
        assertFalse(F.held(F.LT, com.oleroma.gyrophone.core.hid.HidState(gamepad = com.oleroma.gyrophone.core.hid.GamepadState(rt = 900))))
    }

    /** Ry(a)·Rx(b)·Rz(c), row-major. */
    private fun gimbal(a: Float, b: Float, c: Float): FloatArray {
        fun mul(x: FloatArray, y: FloatArray) = FloatArray(9) { k -> val i = k / 3; val j = k % 3; x[i * 3] * y[j] + x[i * 3 + 1] * y[3 + j] + x[i * 3 + 2] * y[6 + j] }
        val ry = floatArrayOf(kotlin.math.cos(a), 0f, kotlin.math.sin(a), 0f, 1f, 0f, -kotlin.math.sin(a), 0f, kotlin.math.cos(a))
        val rx = floatArrayOf(1f, 0f, 0f, 0f, kotlin.math.cos(b), -kotlin.math.sin(b), 0f, kotlin.math.sin(b), kotlin.math.cos(b))
        val rz = floatArrayOf(kotlin.math.cos(c), -kotlin.math.sin(c), 0f, kotlin.math.sin(c), kotlin.math.cos(c), 0f, 0f, 0f, 1f)
        return mul(mul(ry, rx), rz)
    }

    @Test fun gimbalRingsTurnWithTheLasersAxes() {
        val m = gimbal(0.7f, 0.4f, 0.3f)
        for (i in 0..2) {
            val rho = LaserTrace.GIMBAL_RADII[i]
            val pts = LaserTrace.gimbalRing(i, m, 64)
            // Its axis: the laser's Y for yaw, X for pitch, Z for roll (columns of its pose).
            val c = intArrayOf(1, 0, 2)[i]
            for (j in pts.indices step 3) {
                assertEquals(rho, kotlin.math.sqrt(pts[j] * pts[j] + pts[j + 1] * pts[j + 1] + pts[j + 2] * pts[j + 2]), 1e-4f)
                assertEquals(0f, pts[j] * m[c] + pts[j + 1] * m[3 + c] + pts[j + 2] * m[6 + c], 1e-4f)
            }
        }
    }
}
