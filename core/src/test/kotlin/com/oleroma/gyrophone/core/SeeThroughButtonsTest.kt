package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buttons pass the touchpad's chips they take on to the touchpad itself. */
class SeeThroughButtonsTest {
    private val MS = 1_000_000L
    private val FRAME = 8_333_333L
    private val left = OutputTarget.Mouse(MouseButton.LEFT)

    private fun engine(vararg own: Mapping, now: () -> Long): MappingEngine = MappingEngine(single(Workspace(
        id = "w", name = "w",
        touchControls = listOf(TouchControl("b", TouchKind.BUTTON, "b", 0.5f, 0.5f)),
        mappings = listOf(
            Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
            Mapping(InputSource.Surface(1, SurfaceInput.MOTION_Y), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
            Mapping(InputSource.Surface(1, SurfaceInput.TAP), left),
        ) + own,
    )).noTouchpadDefaults(), now).apply { touchpadPxPerDp = 3f; touchpadMinPulseNanos = 40 * MS }

    /** A quick still tap on "b", then frames for a while; every mouse button the snapshots showed. */
    private fun tap(e: MappingEngine, now: LongArray): Int {
        var buttons = 0
        e.buttonDown(1, "b", 500f, 500f)
        now[0] += 50 * MS
        buttons = buttons or e.snapshot().mouse.buttons
        e.buttonUp(1)
        repeat(60) { now[0] += FRAME; buttons = buttons or e.snapshot().mouse.buttons }
        return buttons
    }

    @Test fun aButtonWithNothingOfItsOwnIsTheTouchpad() {
        val now = longArrayOf(0)
        val e = engine { now[0] }
        e.buttonDown(1, "b", 500f, 500f)
        // Never shown pressed: it does nothing itself.
        assertFalse("b" in e.pressedTouches())
        var dx = 0
        var x = 500f
        for (i in 1..20) { now[0] += FRAME; x += 12f; e.buttonMove(1, x, 500f); dx += e.snapshot().mouse.dx }
        e.buttonUp(1)
        assertTrue(dx > 50) // the touchpad's Move moves the cursor
        now[0] += 500 * MS; e.snapshot()
        assertEquals(1 shl MouseButton.LEFT.bit, tap(e, now) and (1 shl MouseButton.LEFT.bit)) // and its tap clicks
    }

    @Test fun anOverriddenChipNeverFiresFromTheButton() {
        val now = longArrayOf(0)
        val key = OutputTarget.Key(0x04)
        val e = engine(
            Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), key),
            Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), OutputTarget.Off(left)),
            now = { now[0] },
        )
        assertEquals(0, tap(e, now) and (1 shl MouseButton.LEFT.bit))
        // The touchpad's tap still clicks from the background afterwards.
        now[0] += 500 * MS
        e.touchpadDown(7, 1500f, 500f); now[0] += 50 * MS; e.snapshot(); e.touchpadUp(7)
        var buttons = 0
        repeat(60) { now[0] += FRAME; buttons = buttons or e.snapshot().mouse.buttons }
        assertEquals(1 shl MouseButton.LEFT.bit, buttons and (1 shl MouseButton.LEFT.bit))
    }

    @Test fun aButtonThatOverridesEverythingKeepsItsFingers() {
        val now = longArrayOf(0)
        val e = engine(
            Mapping(InputSource.Touch("b"), OutputTarget.Key(0x04)),
            Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X, "b"), OutputTarget.Off(OutputTarget.MouseAxis(MouseMotion.MOVE_X))),
            Mapping(InputSource.Surface(1, SurfaceInput.MOTION_Y, "b"), OutputTarget.Off(OutputTarget.MouseAxis(MouseMotion.MOVE_Y))),
            Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), OutputTarget.Off(left)),
            now = { now[0] },
        )
        e.buttonDown(1, "b", 500f, 500f)
        assertTrue("b" in e.pressedTouches())
        var dx = 0
        var x = 500f
        for (i in 1..20) { now[0] += FRAME; x += 12f; e.buttonMove(1, x, 500f); dx += e.snapshot().mouse.dx }
        assertEquals(0, dx)
    }
}
