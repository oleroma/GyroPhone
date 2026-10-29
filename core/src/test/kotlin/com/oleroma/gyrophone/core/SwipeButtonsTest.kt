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
import com.oleroma.gyrophone.core.model.SurfaceInput
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
    private val A = 0x04;
    private val W = 0x1A

    private fun engine(
        mappings: List<Mapping>,
        sensX: Float = 1f,
        sensY: Float = 1f,
        clickThrough: Boolean = false,
        now: () -> Long = { 0L },
    ): MappingEngine {
        val b = TouchControl(
            "b", TouchKind.BUTTON, "", 0.5f, 0.5f,
            clickThrough = clickThrough, swipe = true, swipeSensX = sensX, swipeSensY = sensY,
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
        e.buttonMove(1, 100f, 124f) // 24 dp down at the touchpad's scroll speed: one notch up
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
        val e = engine(
            listOf(
                Mapping(swipe(StickAxis.Y, AxisHalf.NEGATIVE), OutputTarget.Key(W)),
                Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), OutputTarget.Key(A)),
            ), sensY = 1f
        )
        e.buttonDown(1, "b", 100f, 200f)
        e.buttonMove(1, 100f, 185f) // 15 of the 20 dp to half
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
        e.buttonMove(1, 100f, 150f)
        assertEquals(listOf(W), e.snapshot().keyboard.keys)
        e.buttonUp(1)
        // A swipe is not a tap: the tap mapping never fires.
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
    }

    @Test
    fun swipeButtonHoldsWhileSwiping() {
        val e = engine(listOf(Mapping(InputSource.Touch("b"), OutputTarget.Key(A))))
        e.buttonDown(1, "b", 100f, 100f)
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
        e.buttonMove(1, 160f, 100f) // swiping keeps it held
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
        e.buttonUp(1)
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
    }

    @Test
    fun stillTapTaps() {
        var t = 0L
        val e = engine(listOf(Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), OutputTarget.Key(A))), now = { t })
        e.touchpadMinPulseNanos = 10 * MS
        e.buttonDown(1, "b", 100f, 100f)
        t += 50 * MS
        e.buttonMove(1, 102f, 101f)
        e.buttonUp(1)
        var seen = false
        repeat(10) { seen = seen || A in e.snapshot().keyboard.keys; t += 5 * MS }
        assertTrue(seen)
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
        // Press (one finger's hold), outside, zone, then the other surface inputs for 1..4 fingers.
        assertEquals(3 - 1 + (1..4).sumOf { Bindings.surfaceInputs(it).size }, Bindings.touchInputs(b).size)
        assertEquals(7, Bindings.touchInputs(b.copy(clickThrough = true)).size)
        assertEquals(7, Bindings.touchInputs(b.copy(swipe = false)).size)
    }

    @Test
    fun mouseMotionOnlyInTheSwipePicker() {
        fun has(groups: List<Pair<String, List<OutputTarget>>>) =
            groups.any { g -> g.second.any { it is OutputTarget.MouseAxis } }
        assertTrue(has(Describe.pickerGroups(analog = true, swipe = true)))
        assertFalse(has(Describe.pickerGroups(analog = true, swipe = false)))
        assertFalse(has(Describe.pickerGroups(analog = false, swipe = false)))
    }

    @Test
    fun gyroHasItsOwnYSensitivity() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f, sensitivityY = 0f))
        val rad = (90.0 * Math.PI / 180.0).toFloat()
        var dx = 0;
        var dy = 0
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

    @Test
    fun swipeModeMovesGesturesToTheSurface() {
        fun m(s: InputSource, k: Int = A) = Mapping(s, OutputTarget.Key(k))
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true)
        val w = Workspace(
            id = "w", name = "w", touchControls = listOf(b),
            mappings = listOf(
                m(InputSource.TouchTap("b")),
                m(InputSource.Surface(1, SurfaceInput.TAP, "b")), // the same twice: kept once
                m(InputSource.TouchLongPress("b"), W),
                m(InputSource.Surface(1, SurfaceInput.HOLD, "b"), W),
                m(InputSource.Surface(2, SurfaceInput.HOLD, "b"), W),
            ),
        )
        val swiped = Bindings.matchSwipeModes(w)
        assertEquals(
            listOf(
                m(InputSource.Surface(1, SurfaceInput.TAP, "b")),
                m(InputSource.Surface(1, SurfaceInput.LONG_PRESS, "b"), W),
                m(InputSource.Touch("b"), W),
                m(InputSource.Surface(2, SurfaceInput.HOLD, "b"), W),
            ),
            swiped.mappings,
        )
        assertTrue(Bindings.matchSwipeModes(swiped) === swiped)
        // Out of swipe mode the taps go back; the hold stays the press.
        val plain = Bindings.matchSwipeModes(swiped.copy(touchControls = listOf(b.copy(swipe = false))))
        assertEquals(
            listOf(
                m(InputSource.TouchTap("b")),
                m(InputSource.TouchLongPress("b"), W),
                m(InputSource.Touch("b"), W),
                m(InputSource.Surface(2, SurfaceInput.HOLD, "b"), W),
            ),
            plain.mappings,
        )
    }

    @Test
    fun pressOnlyOnATapBecomesATap() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true, swipeTapOnRelease = true)
        val w = Workspace(
            id = "w", name = "w", touchControls = listOf(b),
            mappings = listOf(Mapping(InputSource.Touch("b"), OutputTarget.Key(A), set = 1)),
        )
        val out = Bindings.matchSwipeModes(w)
        assertEquals(listOf(Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), OutputTarget.Key(A), set = 1)), out.mappings)
        assertFalse(out.touchControls.single().swipeTapOnRelease)
    }

    @Test
    fun swipeButtonsOfferOneHold() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true)
        val one = Bindings.swipeInputs(b, 1)
        assertEquals(listOf(InputSource.Touch("b")), one.filter { it.label == "Hold" }.map { it.sources.first() })
        assertTrue(one.none { (it.sources.first() as? InputSource.Surface)?.input == SurfaceInput.HOLD })
        assertEquals(InputSource.Surface(2, SurfaceInput.HOLD, "b"), Bindings.swipeInputs(b, 2).first { it.label == "Hold" }.sources.first())
    }
}
