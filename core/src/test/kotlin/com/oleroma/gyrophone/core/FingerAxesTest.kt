package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.FingerAxisSettings
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerAxesTest {
    private fun engine(
        mappings: List<Mapping>,
        fingers: List<FingerAxisSettings> = emptyList(),
        shape: TouchShape = TouchShape.SQUARE,
    ): MappingEngine {
        val b = TouchControl("aim", TouchKind.BUTTON, "Aim", 0.5f, 0.5f, shape = shape, fingers = fingers)
        val w = Workspace("w", "w", touchControls = listOf(b), mappings = mappings, gyro = GyroSettings(sensitivity = 16f))
        return MappingEngine(single(w))
    }

    @Test
    fun fingerYDrivesTriggerThroughDeadzonesAndRange() {
        val e = engine(
            listOf(Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.PadAxis(GamepadAxis.RT), range = AxisRange(0.1f, 0.9f))),
            listOf(FingerAxisSettings(FingerAxis.Y, start = 0.2f, end = 0.8f)),
        )
        assertEquals(0, e.snapshot().gamepad.rt)
        e.setFinger("aim", 0.5f, 0.1f) // below the start deadzone: range start
        assertEquals(0.1f, e.snapshot().gamepad.rt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
        e.setFinger("aim", 0.5f, 0.5f) // halfway
        assertEquals(0.5f, e.snapshot().gamepad.rt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
        e.setFinger("aim", 0.5f, 1.4f) // off the top: the edge
        assertEquals(0.9f, e.snapshot().gamepad.rt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
        e.fingerUp("aim")
        assertEquals(0, e.snapshot().gamepad.rt)
    }

    @Test
    fun lockedKeepsWhereTheFingerLanded() {
        val e = engine(
            listOf(Mapping(InputSource.Finger("aim", FingerAxis.X), OutputTarget.PadAxis(GamepadAxis.LT))),
            listOf(FingerAxisSettings(FingerAxis.X, locked = true)),
        )
        e.setFinger("aim", 0.25f, 0.5f)
        e.setFinger("aim", 0.9f, 0.5f)
        assertEquals(0.25f, e.snapshot().gamepad.lt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
    }

    @Test
    fun gyroSensitivityFollowsTheFingerAndGoesBackOnLift() {
        val e = engine(listOf(Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.GyroSens(), range = AxisRange(8f, 24f))))
        assertEquals(16f, e.gyroSettings.sensitivity, 0f)
        e.setFinger("aim", 0.5f, 0.25f)
        assertEquals(12f, e.gyroSettings.sensitivity, 0.01f)
        e.setFinger("aim", 0.5f, 1f)
        assertEquals(24f, e.gyroSettings.sensitivity, 0.01f)
        e.fingerUp("aim")
        assertEquals(16f, e.gyroSettings.sensitivity, 0f)
    }

    @Test
    fun reversedRangeAndOneAxis() {
        val e = engine(listOf(Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.GyroSens(MotionAxis.PITCH), range = AxisRange(20f, 10f))))
        e.setFinger("aim", 0.5f, 1f)
        assertEquals(10f, e.gyroSettings.pitchSensitivity!!, 0.01f)
        assertNull(e.gyroSettings.yawSensitivity)
    }

    @Test
    fun roundButtonDistanceAndAngle() {
        val e = engine(
            listOf(
                Mapping(InputSource.Finger("aim", FingerAxis.DISTANCE), OutputTarget.PadAxis(GamepadAxis.LT)),
                Mapping(InputSource.Finger("aim", FingerAxis.ANGLE), OutputTarget.PadAxis(GamepadAxis.RT)),
            ),
            shape = TouchShape.CIRCLE,
        )
        e.setFinger("aim", 1f, 0.5f) // right edge: the rim, a quarter turn from the top
        val s = e.snapshot().gamepad
        assertEquals(1f, s.lt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
        assertEquals(0.25f, s.rt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
    }

    @Test
    fun swipeAxisChipGrowsThroughItsZone() {
        // One zone, from the hold distance (20 dp) for 20 dp more.
        val w = Workspace(
            "w", "w",
            mappings = listOf(Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP), OutputTarget.PadAxis(GamepadAxis.LT), range = AxisRange(0.2f, 1f))),
        )
        var now = 0L
        val e = MappingEngine(single(w)) { now }
        e.touchpadDown(1, 100f, 200f); e.snapshot()
        now += 10_000_000; e.touchpadMove(1, 100f, 190f)
        assertEquals(0, e.snapshot().gamepad.lt) // not at the hold distance yet
        now += 10_000_000; e.touchpadMove(1, 100f, 170f)
        assertEquals(0.6f, e.snapshot().gamepad.lt / GamepadState.TRIGGER_MAX.toFloat(), 0.02f)
        now += 10_000_000; e.touchpadMove(1, 100f, 100f)
        assertEquals(1f, e.snapshot().gamepad.lt / GamepadState.TRIGGER_MAX.toFloat(), 0.02f)
    }

    @Test
    fun buttonFingersAreMeasuredOnTheButton() {
        val b = TouchControl("aim", TouchKind.BUTTON, "Aim", 0.5f, 0.5f, size = 0.2f, shape = TouchShape.SQUARE)
        val w = Workspace(
            "w", "w", touchControls = listOf(b),
            mappings = listOf(Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.PadAxis(GamepadAxis.LT))),
        )
        val e = MappingEngine(single(w))
        e.setPadArea(1000f, 500f, portrait = false)
        // The button is 100 px square around (500, 250): 210 px down is 90 % up it.
        e.buttonDown(1, "aim", 500f, 210f)
        assertEquals(0.9f, e.snapshot().gamepad.lt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
        e.buttonDown(2, "aim", 500f, 290f) // a second finger does not count
        assertEquals(0.9f, e.snapshot().gamepad.lt / GamepadState.TRIGGER_MAX.toFloat(), 0.01f)
        e.buttonMove(1, 500f, 400f) // slid off the bottom: the edge
        assertEquals(0, e.snapshot().gamepad.lt)
        e.buttonUp(1)
        e.snapshot()
        assertEquals(0, e.snapshot().gamepad.lt)
    }

    @Test
    fun heldPositionChipsShowWhileTheFingerIsDown() {
        val e = engine(
            listOf(
                Mapping(InputSource.Finger("aim", FingerAxis.X), OutputTarget.PadAxis(GamepadAxis.LT)),
                Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.PadAxis(GamepadAxis.RT)),
            ),
        )
        val inUse = ArrayList<Mapping>()
        val values = FloatArray(8)
        e.snapshot()
        assertEquals(0, e.valuesShown(inUse, values))
        e.setFinger("aim", 0.25f, 0.75f)
        e.snapshot()
        assertEquals(2, e.valuesShown(inUse, values))
        for (i in inUse.indices) {
            assertEquals(e.fingerValueNow(inUse[i].source as InputSource.Finger)!!, values[2 * i], 1e-6f)
        }
        e.fingerUp("aim")
        e.snapshot()
        assertEquals(0, e.valuesShown(inUse, values))
    }
}
