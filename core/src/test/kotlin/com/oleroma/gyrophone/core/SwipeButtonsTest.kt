package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buttons in swipe mode, and separate X / Y sensitivities. */
class SwipeButtonsTest {
    private val MS = 1_000_000L
    private val A = 0x04; private val W = 0x1A

    private fun engine(
        mappings: List<Mapping>,
        sensX: Float = 1f,
        sensY: Float = 1f,
        clickThrough: Boolean = false,
        tapOnRelease: Boolean = true,
        now: () -> Long = { 0L },
    ): MappingEngine {
        val b = TouchControl(
            "b", TouchKind.BUTTON, "", 0.5f, 0.5f,
            clickThrough = clickThrough, swipe = true, swipeSensX = sensX, swipeSensY = sensY,
            swipeTapOnRelease = tapOnRelease,
        )
        val p = single(Workspace(id = "w", name = "w", touchControls = listOf(b), mappings = mappings))
        return MappingEngine(p, now).apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 0 }
    }

    private fun swipe(axis: StickAxis, half: AxisHalf = AxisHalf.FULL) = InputSource.TouchSwipe("b", axis, half)

    @Test
    fun swipeXDrivesOnlyMouseX() {
        val e = engine(listOf(Mapping(swipe(StickAxis.X), OutputTarget.MouseAxis(MouseMotion.MOVE_X))), sensX = 2f)
        e.buttonDown(1, "b", 100f, 100f)
        e.buttonMove(1, 130f, 160f)
        val m = e.snapshot().mouse
        assertEquals(60, m.dx)
        assertEquals(0, m.dy)
    }

    @Test
    fun swipeYScrollsNaturally() {
        val e = engine(listOf(Mapping(swipe(StickAxis.Y), OutputTarget.MouseAxis(MouseMotion.WHEEL))), sensY = 1.5f)
        e.buttonDown(1, "b", 100f, 100f)
        e.buttonMove(1, 100f, 120f) // 20 dp down at 1.5: one notch up
        assertEquals(MouseState.WHEEL_UNIT, e.snapshot().mouse.wheel)
    }

    @Test
    fun zeroSensitivityTurnsAnAxisOff() {
        val e = engine(
            listOf(
                Mapping(swipe(StickAxis.X), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
                Mapping(swipe(StickAxis.Y), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
            ),
            sensX = 0f, sensY = 1f,
        )
        e.buttonDown(1, "b", 100f, 100f)
        e.buttonMove(1, 150f, 150f)
        val m = e.snapshot().mouse
        assertEquals(0, m.dx)
        assertEquals(50, m.dy)
    }

    @Test
    fun swipeDirectionsPressKeysPastHalfway() {
        val e = engine(listOf(
            Mapping(swipe(StickAxis.Y, AxisHalf.NEGATIVE), OutputTarget.Key(W)),
            Mapping(InputSource.Touch("b"), OutputTarget.Key(A)),
        ), sensY = 1f)
        e.buttonDown(1, "b", 100f, 200f)
        e.buttonMove(1, 100f, 180f) // 20 of the 36 dp to half
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
        e.buttonMove(1, 100f, 150f)
        assertEquals(listOf(W), e.snapshot().keyboard.keys)
        e.buttonUp(1)
        // A swipe is not a tap: the press mapping never fires.
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
    }

    @Test
    fun swipeButtonPressesAtTouchByDefault() {
        val e = engine(listOf(Mapping(InputSource.Touch("b"), OutputTarget.Key(A))), tapOnRelease = false)
        e.buttonDown(1, "b", 100f, 100f)
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
        e.buttonMove(1, 160f, 100f) // swiping keeps it held
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
        e.buttonUp(1)
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
    }

    @Test
    fun stillTapPressesTheButton() {
        var t = 0L
        val e = engine(listOf(Mapping(InputSource.Touch("b"), OutputTarget.Key(A))), now = { t })
        e.buttonDown(1, "b", 100f, 100f)
        t += 50 * MS
        e.buttonMove(1, 102f, 101f)
        e.buttonUp(1)
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
    }

    @Test
    fun swipeAsFloatingStick() {
        val e = engine(listOf(Mapping(swipe(StickAxis.X), OutputTarget.PadAxis(GamepadAxis.LX))), sensX = 1f)
        e.buttonDown(1, "b", 100f, 100f)
        e.buttonMove(1, 172f, 100f)
        assertEquals(32767, e.snapshot().gamepad.lx)
        e.buttonUp(1)
        assertEquals(0, e.snapshot().gamepad.lx)
    }

    @Test
    fun clickThroughTurnsSwipeModeOff() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true)
        assertEquals(9, Bindings.touchInputs(b).size)
        assertEquals(3, Bindings.touchInputs(b.copy(clickThrough = true)).size)
        assertEquals(3, Bindings.touchInputs(b.copy(swipe = false)).size)
    }

    @Test
    fun mouseMotionOnlyInTheSwipePicker() {
        fun has(groups: List<Pair<String, List<OutputTarget>>>) = groups.any { g -> g.second.any { it is OutputTarget.MouseAxis } }
        assertTrue(has(Describe.pickerGroups(analog = true, swipe = true)))
        assertFalse(has(Describe.pickerGroups(analog = true, swipe = false)))
        assertFalse(has(Describe.pickerGroups(analog = false, swipe = false)))
    }

    @Test
    fun gyroHasItsOwnYSensitivity() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f, sensitivityY = 0f))
        val rad = (90.0 * Math.PI / 180.0).toFloat()
        var dx = 0; var dy = 0
        repeat(1000) { val c = g.update(-rad, -rad, 0f, 0.001f); dx += c.dx; dy += c.dy }
        assertEquals(900, dx)
        assertEquals(0, dy)
    }

    @Test
    fun olderFilesKeepTheirVerticalRatio() {
        assertEquals(5f, GyroSettings(sensitivity = 10f, verticalRatio = 0.5f).verticalSensitivity)
        assertEquals(2f, TouchpadSettings(sensitivity = 2f).verticalSensitivity)
        assertEquals(0f, TouchpadSettings(sensitivity = 2f, sensitivityY = 0f).verticalSensitivity)
    }
}
