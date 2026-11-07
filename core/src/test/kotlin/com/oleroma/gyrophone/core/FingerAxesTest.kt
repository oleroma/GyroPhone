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
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
