package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.OverridingChildren
import com.oleroma.gyrophone.core.model.ChildOverride
import com.oleroma.gyrophone.core.model.SliderOverrides
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val TOGGLE = MapOptions(how = How.TOGGLE)

class SetValueTest {
    private var now = 0L

    private fun engine(mappings: List<Mapping>): MappingEngine {
        val b = TouchControl("aim", TouchKind.BUTTON, "Aim", 0.5f, 0.5f, shape = TouchShape.SQUARE)
        val w = Workspace("w", "w", touchControls = listOf(b), mappings = mappings, gyro = GyroSettings(sensitivity = 16f))
        return MappingEngine(single(w)) { now }
    }

    private fun MappingEngine.rt(): Float = snapshot().gamepad.rt / GamepadState.TRIGGER_MAX.toFloat()

    @Test
    fun positionWithSetValueKeepsTheTriggerUntilLetGo() {
        val e = engine(
            listOf(
                Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.PadAxis(GamepadAxis.RT), options = MapOptions(how = How.TOGGLE, endsOnLetGo = true)),
                Mapping(InputSource.Touch("other"), OutputTarget.LetGo),
            ),
        )
        e.setFinger("aim", 0.5f, 0.6f)
        assertEquals(0.6f, e.rt(), 0.01f)
        e.fingerUp("aim")
        assertEquals(0.6f, e.rt(), 0.01f) // kept after the lift
        e.setFinger("aim", 0.5f, 0.3f) // a tap sets it at once
        e.fingerUp("aim")
        assertEquals(0.3f, e.rt(), 0.01f)
        e.setTouch("other", true); e.snapshot(); e.setTouch("other", false)
        assertEquals(0f, e.rt(), 0.001f)
    }

    @Test
    fun timerEndsAKeptValue() {
        val e = engine(listOf(Mapping(InputSource.Finger("aim", FingerAxis.X), OutputTarget.PadAxis(GamepadAxis.RT), options = MapOptions(how = How.TOGGLE, endsAfterMs = 100))))
        e.setFinger("aim", 0.5f, 0.5f); e.rt()
        e.fingerUp("aim")
        assertEquals(0.5f, e.rt(), 0.01f)
        now += 150_000_000
        assertEquals(0f, e.rt(), 0.001f)
    }

    @Test
    fun moveSlidesATriggerByItsPerDpAndATapDoesNothing() {
        val m = Mapping(InputSource.Surface(1, SurfaceInput.MOTION_Y), OutputTarget.PadAxis(GamepadAxis.RT), perDp = 0.01f, options = TOGGLE)
        val e = MappingEngine(single(Workspace("w", "w", mappings = listOf(m)))) { now }
        e.touchpadDown(1, 100f, 300f); e.rt()
        now += 10_000_000; e.touchpadMove(1, 100f, 280f)
        now += 10_000_000; e.touchpadMove(1, 100f, 260f)
        assertEquals(0.4f, e.rt(), 0.02f) // 40 dp up at 1 % per dp
        e.touchpadUp(1); e.snapshot()
        assertEquals(0.4f, e.rt(), 0.02f) // kept
        // A tap moves nothing.
        now += 500_000_000; e.touchpadDown(2, 50f, 50f); e.snapshot(); now += 30_000_000; e.touchpadUp(2)
        assertEquals(0.4f, e.rt(), 0.02f)
        // The next slide nudges it on from there.
        now += 500_000_000; e.touchpadDown(3, 100f, 300f); e.rt()
        now += 10_000_000; e.touchpadMove(3, 100f, 290f)
        now += 10_000_000; e.touchpadMove(3, 100f, 280f)
        assertEquals(0.6f, e.rt(), 0.02f)
    }

    @Test
    fun moveWithoutSetValueStartsAtTheRangeStartAndRestsOnLift() {
        val m = Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X), OutputTarget.PadAxis(GamepadAxis.RT), range = AxisRange(0.2f, 1f), perDp = 0.01f)
        val e = MappingEngine(single(Workspace("w", "w", mappings = listOf(m)))) { now }
        e.touchpadDown(1, 100f, 300f); e.rt()
        now += 10_000_000; e.touchpadMove(1, 120f, 300f)
        now += 10_000_000; e.touchpadMove(1, 140f, 300f)
        assertEquals(0.6f, e.rt(), 0.02f)
        e.touchpadUp(1)
        assertEquals(0f, e.rt(), 0.001f)
    }

    @Test
    fun toggleSensitivityWithNoEndIsHandedOnToBeSaved() {
        val e = engine(listOf(Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.GyroSens(), range = AxisRange(8f, 24f), options = TOGGLE)))
        val commits = ArrayList<MappingEngine.SetCommit>()
        e.onSetValue = { commits.add(it) }
        e.setFinger("aim", 0.5f, 0.25f)
        assertEquals(12f, e.gyroSettings.sensitivity, 0.01f)
        e.fingerUp("aim")
        assertEquals(12f, e.gyroSettings.sensitivity, 0.01f) // kept until the setting comes back
        assertEquals(1, commits.size)
        assertEquals(12f, commits[0].value, 0.01f)
    }

    @Test
    fun aHeldChipOnPositionKeepsNothing() {
        val e = engine(listOf(Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.PadAxis(GamepadAxis.RT))))
        e.setFinger("aim", 0.5f, 0.6f)
        assertEquals(0.6f, e.rt(), 0.01f)
        e.fingerUp("aim")
        assertEquals(0f, e.rt(), 0.001f)
    }

    @Test
    fun aSavedOverrideLiesOverTheSettingsWithoutChangingThem() {
        val b = TouchControl("aim", TouchKind.BUTTON, "Aim", 0.5f, 0.5f, shape = TouchShape.SQUARE)
        val w = Workspace("w", "w", touchControls = listOf(b), mappings = listOf(chip), gyro = GyroSettings(sensitivity = 16f))
        val p = SliderOverrides.put(single(w), 0, chip, 12f)
        assertEquals(16f, p.workspace(0).gyro.sensitivity, 0f) // the setting stays
        val e = MappingEngine(p) { now }
        assertEquals(12f, e.gyroSettings.sensitivity, 0.01f)
    }
}

class SliderOverridesTest {
    private val sens = WorkspaceDefaults.gyroKey("sensitivity")

    @Test
    fun theNewestOfASensitivityWinsAndOlderAreCleared() {
        val aim = TouchControl("aim", TouchKind.BUTTON, "Aim", 0.5f, 0.5f)
        val zoom = TouchControl("zoom", TouchKind.BUTTON, "Zoom", 0.5f, 0.5f)
        var w = Workspace("w", "Apex", touchControls = listOf(aim, zoom))
        val yaw = Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.GyroSens(com.oleroma.gyrophone.core.model.MotionAxis.YAW), options = TOGGLE)
        w = SliderOverrides.put(w, yaw, 10f)
        w = SliderOverrides.put(w, chip, 12f)
        w = SliderOverrides.put(w, chip.copy(source = InputSource.Finger("zoom", FingerAxis.Y)), 14f)
        assertEquals(1, w.sliders.size)
        assertEquals("zoom", w.sliders[0].by)
        // Listed as the workspace's child; under the Defaults while the workspace follows.
        assertEquals(listOf(ChildOverride("Zoom slider", "14")), OverridingChildren.ofWorkspace(w, listOf(sens)))
        assertEquals(listOf(ChildOverride("Apex › Zoom slider", "14")), OverridingChildren.ofDefaults(single(w), listOf(sens)))
        // Reset children clears it.
        assertTrue(OverridingChildren.resetWorkspace(w, listOf(sens)).sliders.isEmpty())
    }
}

private val chip = Mapping(InputSource.Finger("aim", FingerAxis.Y), OutputTarget.GyroSens(), range = AxisRange(8f, 24f), options = TOGGLE)
