package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.HapticSink
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.HapticEdge
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buzz chips ([Mapping.buzz]) and a button's own haptic feel and edge. */
class ButtonBuzzTest {
    private val wsFeel = HapticFeel(system = false, strength = 100, lengthMs = 20)
    private val ownFeel = HapticFeel(system = false, strength = 250, lengthMs = 40)
    private val played = ArrayList<HapticFeel>()

    private fun engine(b: TouchControl, vararg ms: Mapping): MappingEngine {
        val w = Workspace(id = "w", name = "w", touchControls = listOf(b), mappings = ms.toList(), haptics = wsFeel)
        val e = MappingEngine(single(w)) { 0L }
        e.hapticSink = HapticSink { ticks, _, _ -> played.addAll(ticks) }
        return e
    }

    private fun button(feel: HapticFeel? = null, edge: HapticEdge? = null) =
        TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, haptics = feel, hapticEdge = edge)

    @Test
    fun buzzChipTicksOnPressWithTheWorkspaceFeel() {
        val e = engine(button(), Mapping(InputSource.Touch("b"), OutputTarget.Pad(GamepadButton.A), buzz = true))
        e.snapshot()
        e.setTouch("b", true)
        e.snapshot()
        assertEquals(listOf(wsFeel), played)
        e.snapshot()
        assertEquals(1, played.size) // once per press
        e.setTouch("b", false)
        e.snapshot()
        assertEquals(1, played.size) // the workspace's edge: press only
    }

    @Test
    fun chipWithoutBuzzStaysQuiet() {
        val e = engine(button(), Mapping(InputSource.Touch("b"), OutputTarget.Pad(GamepadButton.A)))
        e.setTouch("b", true)
        e.snapshot()
        assertTrue(played.isEmpty())
    }

    @Test
    fun buttonFeelAndEdgeWin() {
        val e = engine(
            button(ownFeel, HapticEdge.BOTH),
            Mapping(InputSource.Touch("b"), OutputTarget.Pad(GamepadButton.A), buzz = true),
            Mapping(InputSource.Touch("b"), OutputTarget.Haptic()),
        )
        e.setTouch("b", true)
        e.snapshot()
        assertEquals(listOf(ownFeel, ownFeel), played) // the Buzz chip and the haptic tick, both in the button's feel
        e.setTouch("b", false)
        e.snapshot()
        assertEquals(4, played.size) // and both on release too
    }

    @Test
    fun cardSwitchSetsEveryChipButHaptics() {
        val card = listOf(BindableInput("Hold", listOf(InputSource.Touch("b"))), BindableInput("Tap", listOf(InputSource.TouchTap("b"))))
        val ms = listOf(
            Mapping(InputSource.Touch("b"), OutputTarget.Pad(GamepadButton.A)),
            Mapping(InputSource.TouchTap("b"), OutputTarget.Pad(GamepadButton.B)),
            Mapping(InputSource.Touch("b"), OutputTarget.Haptic()),
        )
        assertFalse(Bindings.buzzes(ms, card))
        val on = Bindings.setBuzz(ms, card, true)
        assertTrue(Bindings.buzzes(on, card))
        assertEquals(listOf(true, true, false), on.map { it.buzz })
        // Kept when the card's chips are set again.
        val again = Bindings.setTargets(on, card[0], listOf(OutputTarget.Pad(GamepadButton.A), OutputTarget.Pad(GamepadButton.X)))
        assertTrue(again.first { it.target == OutputTarget.Pad(GamepadButton.A) }.buzz)
        assertFalse(Bindings.setBuzz(on, card, false).any { it.buzz })
    }
}
