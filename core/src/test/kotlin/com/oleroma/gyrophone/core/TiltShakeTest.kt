package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.TiltShake
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Tilt and shake from the accelerometer, and as mapped inputs. */
class TiltShakeTest {
    private val G = 9.81f
    private val MS = 1_000_000L

    /** Gravity in portrait, upright, turned [deg] clockwise in the screen's plane. */
    private fun upright(deg: Double) = floatArrayOf((-G * sin(Math.toRadians(deg))).toFloat(), (G * cos(Math.toRadians(deg))).toFloat(), 0f)

    @Test fun sidewaysTiltIsTheTurnFromUpright() {
        val t = TiltShake().apply { displayRotation = 0 }
        var g = upright(0.0)
        t.update(g[0], g[1], g[2], 0)
        assertEquals(0f, t.x, 1e-3f)
        g = upright(15.0)
        repeat(60) { t.update(g[0], g[1], g[2], it * 20 * MS) }
        assertEquals(0.5f, t.x, 0.02f)
        g = upright(-90.0)
        repeat(60) { t.update(g[0], g[1], g[2], it * 20 * MS) }
        assertEquals(-1f, t.x, 1e-3f)
    }

    @Test fun forwardIsLeaningFromWhereItStarted() {
        val t = TiltShake().apply { displayRotation = 0 }
        fun lean(deg: Double) = floatArrayOf(0f, (G * cos(Math.toRadians(deg))).toFloat(), (G * sin(Math.toRadians(deg))).toFloat())
        var g = lean(40.0) // held leaning back 40°: the rest point
        t.update(g[0], g[1], g[2], 0)
        assertEquals(0f, t.y, 1e-3f)
        g = lean(55.0) // top edge 15° further away: half forward
        repeat(60) { t.update(g[0], g[1], g[2], it * 20 * MS) }
        assertEquals(-0.5f, t.y, 0.02f)
    }

    @Test fun eachTiltAxisHasItsOwnRangeAndDeadzoneOrTheShared() {
        val t = TiltShake().apply {
            displayRotation = 0
            settings = com.oleroma.gyrophone.core.model.GyroSettings(tiltRangeDeg = 60f, tiltXDeadzoneDeg = 10f)
        }
        var g = upright(0.0)
        t.update(g[0], g[1], g[2], 0)
        g = upright(8.0) // inside the deadzone
        repeat(60) { t.update(g[0], g[1], g[2], it * 20 * MS) }
        assertEquals(0f, t.x, 1e-3f)
        g = upright(35.0) // (35 - 10) / (60 - 10)
        repeat(60) { t.update(g[0], g[1], g[2], it * 20 * MS) }
        assertEquals(0.5f, t.x, 0.02f)
    }

    @Test fun aJoltIsOneShake() {
        val t = TiltShake().apply { displayRotation = 0 }
        val g = upright(0.0)
        t.update(g[0], g[1], g[2], 0)
        assertTrue(t.update(25f, g[1], 0f, 100 * MS))
        assertFalse(t.update(-25f, g[1], 0f, 200 * MS)) // the same shake
        assertTrue(t.update(25f, g[1], 0f, 900 * MS))
    }

    @Test fun smoothingGoesByTimeNotBySample() {
        // The same 40 ms after a 30° turn reads the same at 50 Hz and at 200 Hz.
        fun after(stepMs: Long): Float {
            val t = TiltShake().apply { displayRotation = 0 }
            var g = upright(0.0)
            t.update(g[0], g[1], g[2], 0)
            g = upright(30.0)
            var ts = 0L
            while (ts < 40 * MS) { ts += stepMs * MS; t.update(g[0], g[1], g[2], ts) }
            return t.x
        }
        assertEquals(after(20), after(5), 0.01f)
    }

    @Test fun tiltFollowsWithinAFewTensOfMilliseconds() {
        // 200 Hz: after one time constant most of a full turn has come through, and it settles in 0.2 s.
        val t = TiltShake().apply { displayRotation = 0 }
        var g = upright(0.0)
        t.update(g[0], g[1], g[2], 0)
        g = upright(30.0)
        var ts = 0L
        while (ts < 35 * MS) { ts += 5 * MS; t.update(g[0], g[1], g[2], ts) }
        assertTrue("x ${t.x}", t.x > 0.55f)
        while (ts < 200 * MS) { ts += 5 * MS; t.update(g[0], g[1], g[2], ts) }
        assertEquals(1f, t.x, 0.02f)
    }

    @Test fun aShakeNeedsTheSameJoltAtAnyRate() {
        // Today's 50 Hz filter saw 80% of a jump from gravity: 17 m/s² is under 14 after it, 18.5 over.
        for (stepMs in longArrayOf(20, 5)) {
            val t = TiltShake().apply { displayRotation = 0 }
            val g = upright(0.0)
            var ts = 0L
            repeat(20) { t.update(g[0], g[1], g[2], ts); ts += stepMs * MS }
            assertFalse(t.update(17f, g[1], 0f, ts))
            ts += stepMs * MS
            // Back at rest well after the first jump, a bigger one.
            repeat(200) { t.update(g[0], g[1], g[2], ts); ts += stepMs * MS }
            assertTrue(t.update(18.5f, g[1], 0f, ts))
        }
    }

    /** Gravity with the screen leaning back [back]° from upright, turned [deg] clockwise in its plane. */
    private fun held(deg: Double, back: Double): FloatArray {
        val r = Math.toRadians(deg); val b = Math.toRadians(back)
        val inPlane = G * cos(b)
        return floatArrayOf((-inPlane * sin(r)).toFloat(), (inPlane * cos(r)).toFloat(), (G * sin(b)).toFloat())
    }

    /**
     * Turns the phone clockwise about the axis through its screen at [dps] for [ms], gyro at 1 kHz and
     * the accelerometer at 200 Hz reading [gravity] (deg turned so far), from [startMs].
     */
    private fun turn(t: TiltShake, startMs: Long, ms: Long, dps: Double, gravity: (Double) -> FloatArray): Long {
        var now = startMs
        var deg = 0.0
        while (now < startMs + ms) {
            now += 1
            deg += dps / 1000
            t.gyro(0f, 0f, (-Math.toRadians(dps)).toFloat(), now * MS)
            if (now % 5 == 0L) { val g = gravity(deg); t.update(g[0], g[1], g[2], now * MS) }
        }
        return now
    }

    @Test fun withTheGyroSidewaysTiltFollowsAtOnce() {
        // Turning 30° in 100 ms reads the whole 30° at the end; the accelerometer alone would lag behind.
        val t = TiltShake().apply { displayRotation = 0 }
        val g = upright(0.0)
        t.update(g[0], g[1], g[2], 0)
        t.gyro(0f, 0f, 0f, 0)
        turn(t, 0, 100, 300.0) { upright(it) }
        assertEquals(1f, t.x, 0.02f)
    }

    @Test fun aTapOnTheScreenDoesNotJerkTheWheel() {
        val t = TiltShake().apply { displayRotation = 0 }
        val g = upright(10.0)
        t.update(g[0], g[1], g[2], 0)
        t.gyro(0f, 0f, 0f, 0)
        var now = turn(t, 0, 200, 0.0) { upright(10.0) }
        val before = t.x
        // A jolt sideways from a thumb: the accelerometer jumps, the phone doesn't turn.
        now += 5
        t.update(g[0] - 12f, g[1], g[2], now * MS)
        t.gyro(0f, 0f, 0f, now * MS)
        assertEquals(before, t.x, 0.01f)
    }

    @Test fun onTheLapTheGyroSteersAndGravityCanNotTell() {
        // Screen up, 5° from flat: turning 20° clockwise steers 20°, though gravity barely moves.
        val t = TiltShake().apply { displayRotation = 0 }
        var g = held(0.0, 85.0)
        t.update(g[0], g[1], g[2], 0)
        t.gyro(0f, 0f, 0f, 0)
        assertEquals(0f, t.x, 1e-3f)
        val now = turn(t, 0, 200, 100.0) { held(it, 85.0) }
        assertEquals(20f / 30f, t.x, 0.03f)
        // Held still there, it stays.
        g = held(20.0, 85.0)
        turn(t, now, 1000, 0.0) { g }
        assertEquals(20f / 30f, t.x, 0.03f)
    }

    @Test fun lyingFlatItStartsStraightAndSetOrientationStraightens() {
        val t = TiltShake().apply { displayRotation = 0 }
        val g = held(40.0, 89.0)
        t.update(g[0], g[1], g[2], 0)
        assertEquals(0f, t.x, 1e-3f)
        t.gyro(0f, 0f, 0f, 0)
        turn(t, 0, 100, 100.0) { held(40.0 + it, 89.0) }
        assertTrue(t.x > 0.2f)
        t.straighten()
        assertEquals(0f, t.x, 1e-3f)
    }

    @Test fun setOrientationLeavesAnUprightWheelToGravity() {
        val t = TiltShake().apply { displayRotation = 0 }
        val g = upright(15.0)
        t.update(g[0], g[1], g[2], 0)
        t.straighten()
        assertEquals(0.5f, t.x, 0.02f)
    }

    @Test fun lyingFlatTheCompassHoldsStraightAgainstDrift() {
        val t = TiltShake().apply {
            displayRotation = 0
            settings = com.oleroma.gyrophone.core.model.GyroSettings(tiltCompass = true)
        }
        val g = held(0.0, 89.0)
        t.update(g[0], g[1], g[2], 0)
        t.gyro(0f, 0f, 0f, 0)
        // North straight ahead along the screen's up, level.
        t.north(0f, 20f, 0f)
        // The gyro drifts 1°/s clockwise while the phone lies still: the compass keeps it straight.
        var now = 0L
        repeat(10_000) {
            now += 1
            t.gyro(0f, 0f, (-Math.toRadians(1.0)).toFloat(), now * MS)
            if (now % 5 == 0L) t.update(g[0], g[1], g[2], now * MS)
            if (now % 20 == 0L) t.north(0f, 20f, 0f)
        }
        assertEquals(0f, t.x, 0.06f)
    }

    @Test fun lyingFlatWithoutTheGyroTiltHoldsStill() {
        // Gravity's turn is noise lying flat: without the gyro the wheel doesn't spin with it.
        val t = TiltShake().apply { displayRotation = 0 }
        var g = upright(10.0)
        t.update(g[0], g[1], g[2], 0)
        var ts = 0L
        g = held(10.0, 89.5)
        repeat(100) { ts += 5 * MS; t.update(g[0], g[1], g[2], ts) }
        val held = t.x
        g = held(120.0, 89.5)
        repeat(100) { ts += 5 * MS; t.update(g[0], g[1], g[2], ts) }
        assertEquals(held, t.x, 1e-3f)
    }

    @Test fun mappedTiltAndShake() {
        var now = 0L
        val ws = Workspace(
            "w", "w",
            touchControls = listOf(TouchControl("b", TouchKind.BUTTON, "b", 0.5f, 0.5f)),
            mappings = listOf(
                Mapping(InputSource.Tilt(StickAxis.X), OutputTarget.PadAxis(GamepadAxis.LX)),
                Mapping(InputSource.Tilt(StickAxis.Y, AxisHalf.NEGATIVE), OutputTarget.Key(0x1A)),
                Mapping(InputSource.Shake(), OutputTarget.Key(0x2C)),
                Mapping(InputSource.Tilt(StickAxis.X, AxisHalf.POSITIVE, "b"), OutputTarget.Key(0x07)),
            ),
        )
        val e = MappingEngine(single(ws)) { now }
        assertTrue(e.motionMapped)
        e.setTilt(0.5f, -0.8f)
        var s = e.snapshot()
        assertEquals(0.5f, s.gamepad.lx / 32767f, 0.01f)
        assertEquals(listOf(0x1A), s.keyboard.keys)
        e.setTouch("b", true)
        assertEquals(setOf(0x1A, 0x07), e.snapshot().keyboard.keys.toSet()) // the button's tilt only while it is held
        e.setTouch("b", false)
        e.setTilt(0f, 0f)
        e.shake()
        now = 500 * MS // a late snapshot still sees the shake once
        assertEquals(listOf(0x2C), e.snapshot().keyboard.keys)
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
        assertFalse(MappingEngine(single(Workspace("w", "w"))).motionMapped)
    }

    @Test fun aShakeHoldingALayerLetsGoAfterItsPulse() {
        var now = 0L
        val ws = Workspace(
            "w", "w", sets = listOf(com.oleroma.gyrophone.core.model.MappingSet(1)),
            mappings = listOf(Mapping(InputSource.Shake(), OutputTarget.Layer(1, whileHeld = true))),
        )
        val e = MappingEngine(single(ws)) { now }
        e.shake()
        e.snapshot()
        assertEquals(1, e.layers.set)
        now = MappingEngine.SHAKE_PULSE_NS + MS
        e.snapshot()
        assertEquals(0, e.layers.set)
    }

    @Test fun twoQuickShakesAreTwoPresses() {
        var now = 0L
        val ws = Workspace("w", "w", mappings = listOf(Mapping(InputSource.Shake(), OutputTarget.Wheel(1))))
        val e = MappingEngine(single(ws)) { now }
        e.shake()
        var wheel = e.snapshot().mouse.wheel
        now = 50 * MS // within the first one's pulse
        e.shake()
        wheel += e.snapshot().mouse.wheel
        assertEquals(2 * com.oleroma.gyrophone.core.hid.MouseState.WHEEL_UNIT, wheel)
    }
}
