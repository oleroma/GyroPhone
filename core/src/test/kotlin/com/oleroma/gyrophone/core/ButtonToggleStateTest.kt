package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ControlGroups
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.ToggleRule
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Toggle button's state: its Initial state, its "Also ends on" and toggle syncs (Flip, Never both). */
class ButtonToggleStateTest {
    private val MS = 1_000_000L
    private val A = 0x04
    private val B = 0x05
    private val C = 0x06
    private val KEY_X = 99 // KEYCODE_BUTTON_X
    private var t = 0L
    private val dev = PadDevice(1, 0x45e, 0x2ea, "pad")

    private fun button(id: String, initialOn: Boolean = false, f: (TouchControl) -> TouchControl = { it }) =
        f(TouchControl(id, TouchKind.BUTTON, id, 0.5f, 0.5f, how = How.TOGGLE, initialOn = initialOn))

    private fun engine(controls: List<TouchControl>, ms: List<Mapping>, pad: List<Mapping> = emptyList()): MappingEngine {
        val w = Workspace(
            "w", "w", touchControls = controls, mappings = ms,
            pads = if (pad.isEmpty()) emptyList() else listOf(AttachedPad(id = "p", mappings = pad)),
        )
        return MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun press(id: String, usage: Int) = Mapping(InputSource.Touch(id), OutputTarget.Key(usage))
    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()
    private fun MappingEngine.tap(id: String) {
        setTouch(id, true); keys(); t += 20 * MS; setTouch(id, false); keys(); t += 20 * MS
    }

    @Test fun eachTouchTurnsItAndItsPressChipsFollow() {
        val e = engine(listOf(button("a")), listOf(press("a", A)))
        assertEquals(emptySet<Int>(), e.keys())
        e.tap("a")
        assertEquals(setOf(A), e.keys())
        assertTrue("a" in e.latchedTouches())
        e.tap("a")
        assertEquals(emptySet<Int>(), e.keys())
        assertFalse("a" in e.latchedTouches())
    }

    @Test fun initialStateOnStartsOn() {
        val e = engine(listOf(button("a", initialOn = true)), listOf(press("a", A)))
        assertEquals(setOf(A), e.keys())
        e.tap("a")
        assertEquals(emptySet<Int>(), e.keys())
        e.tap("a")
        assertEquals(setOf(A), e.keys())
    }

    @Test fun aChipsOwnHowStaysItsOwn() {
        val held = Mapping(InputSource.Touch("a"), OutputTarget.Key(B), options = MapOptions(how = How.HELD))
        val e = engine(listOf(button("a")), listOf(press("a", A), held))
        e.setTouch("a", true)
        assertEquals(setOf(A, B), e.keys())
        e.setTouch("a", false); t += 100 * MS
        assertEquals(setOf(A), e.keys()) // the Held chip let go, the button stays on
    }

    @Test fun timerTurnsItBackToItsInitialState() {
        val e = engine(listOf(button("a") { it.copy(endsAfterMs = 300) }), listOf(press("a", A)))
        e.tap("a")
        assertEquals(setOf(A), e.keys())
        t += 300 * MS
        assertEquals(emptySet<Int>(), e.keys())
        assertFalse("a" in e.latchedTouches())
    }

    @Test fun letGoTurnsItBack() {
        val e = engine(
            listOf(button("a", initialOn = true) { it.copy(endsOnLetGo = true) }, TouchControl("g", TouchKind.BUTTON, "g", 0.2f, 0.2f)),
            listOf(press("a", A), Mapping(InputSource.Touch("g"), OutputTarget.LetGo)),
        )
        e.tap("a")
        assertEquals(emptySet<Int>(), e.keys())
        e.tap("g")
        assertEquals(setOf(A), e.keys()) // back On, its Initial state
    }

    @Test fun nextInputTurnsItBackAsThatIsLetGo() {
        val e = engine(
            listOf(button("a") { it.copy(endsOnNextInput = true) }),
            listOf(press("a", A)),
            pad = listOf(Mapping(InputSource.Key(KEY_X), OutputTarget.Key(C))),
        )
        e.tap("a")
        assertEquals(setOf(A), e.keys())
        e.setKey(dev, KEY_X, true)
        assertEquals(setOf(A, C), e.keys())
        t += 20 * MS
        e.setKey(dev, KEY_X, false)
        assertEquals(emptySet<Int>(), e.keys())
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test fun sameKeystrokeTurnsItBack() {
        val e = engine(
            listOf(button("a") { it.copy(endsOnSame = true) }),
            listOf(press("a", A)),
            pad = listOf(Mapping(InputSource.Key(KEY_X), OutputTarget.Key(A))),
        )
        e.tap("a")
        assertEquals(setOf(A), e.keys())
        e.setKey(dev, KEY_X, true); e.keys(); t += 20 * MS
        e.setKey(dev, KEY_X, false); t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test fun flipTurnsThemTogetherKeepingOppositeStates() {
        val controls = listOf(button("a"), button("b", initialOn = true))
        val w = ControlGroups.syncToggles(Workspace("w", "w", touchControls = controls), listOf("a", "b"), ToggleRule.FLIP)
        val e = engine(w.touchControls, listOf(press("a", A), press("b", B)))
        assertEquals(setOf(B), e.keys())
        e.tap("a")
        assertEquals(setOf(A), e.keys())
        e.tap("b") // touching either turns both
        assertEquals(setOf(B), e.keys())
    }

    @Test fun neverBothTurnsTheOtherBack() {
        val controls = listOf(button("a"), button("b"), button("c"))
        val w = ControlGroups.syncToggles(Workspace("w", "w", touchControls = controls), listOf("a", "b", "c"), ToggleRule.NEVER_BOTH)
        val e = engine(w.touchControls, listOf(press("a", A), press("b", B), press("c", C)))
        e.tap("a")
        assertEquals(setOf(A), e.keys())
        e.tap("b")
        assertEquals(setOf(B), e.keys()) // one at a time
        e.tap("b")
        assertEquals(emptySet<Int>(), e.keys()) // both off is fine
    }

    @Test fun neverBothStartingOnNeverHasBothOff() {
        val controls = listOf(button("a", initialOn = true), button("b", initialOn = true))
        val w = ControlGroups.syncToggles(Workspace("w", "w", touchControls = controls), listOf("a", "b"), ToggleRule.NEVER_BOTH)
        val e = engine(w.touchControls, listOf(press("a", A), press("b", B)))
        assertEquals(setOf(A, B), e.keys())
        e.tap("a")
        assertEquals(setOf(B), e.keys())
        e.tap("b")
        assertEquals(setOf(A), e.keys())
    }

    @Test fun neverBothNeedsTheSameInitialState() {
        val controls = listOf(button("a"), button("b", initialOn = true))
        assertFalse(ControlGroups.canNeverBoth(controls, listOf("a", "b")))
        assertTrue(ControlGroups.canSyncToggles(controls, listOf("a", "b")))
        val w = Workspace("w", "w", touchControls = controls)
        assertEquals(w, ControlGroups.syncToggles(w, listOf("a", "b"), ToggleRule.NEVER_BOTH))
    }
}
