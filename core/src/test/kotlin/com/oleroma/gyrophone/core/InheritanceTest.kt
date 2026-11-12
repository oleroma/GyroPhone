package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.layered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch mappings passing down built-in → Defaults → workspace → button. */
class InheritanceTest {
    private val left = OutputTarget.Mouse(MouseButton.LEFT)
    private val right = OutputTarget.Mouse(MouseButton.RIGHT)
    private val key = OutputTarget.Key(0x04)

    private fun pad(n: Int, i: SurfaceInput, t: OutputTarget, set: Int = 0) = Mapping(InputSource.Surface(n, i), t, set)
    private fun btn(id: String, n: Int, i: SurfaceInput, t: OutputTarget, set: Int = 0) = Mapping(InputSource.Surface(n, i, id), t, set)

    private fun targetOf(ms: List<Mapping>, s: InputSource) = ms.filter { it.source == s }.map { it.target }

    @Test fun defaultsFillWhatTheWorkspaceDoesNotMap() {
        val d = WorkspaceDefaults.builtin.copy(mappings = listOf(pad(2, SurfaceInput.TAP, left)))
        val w = Workspace("w", "w", mappings = listOf(pad(1, SurfaceInput.TAP_DRAG, right)))
        val out = Inheritance.workspace(w, d)
        assertEquals(listOf(right), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP_DRAG)))
        assertEquals(listOf(left), targetOf(out, InputSource.Surface(2, SurfaceInput.TAP))) // the Defaults' own
        // Nothing built in for what neither maps.
        assertEquals(emptyList<OutputTarget>(), targetOf(out, InputSource.Surface(1, SurfaceInput.MOTION_X)))
    }

    @Test fun aClearedInputStaysCleared() {
        val w = Workspace("w", "w", mappings = listOf(pad(1, SurfaceInput.MOTION_X, OutputTarget.NoOutput)))
        val out = Inheritance.workspace(w, WorkspaceDefaults.builtin)
        assertEquals(listOf<OutputTarget>(OutputTarget.NoOutput), targetOf(out, InputSource.Surface(1, SurfaceInput.MOTION_X)))
    }

    @Test fun aButtonsHoldIsItsPress() {
        val m = Inheritance.forButton(pad(1, SurfaceInput.HOLD, key), "b")
        assertEquals(InputSource.Touch("b"), m.source)
        assertEquals(InputSource.Surface(2, SurfaceInput.HOLD, "b"), Inheritance.forButton(pad(2, SurfaceInput.HOLD, key), "b").source)
    }

    @Test fun buttonsTakeOnlyWhatTheyDoNotMap() {
        val inUse = listOf(
            pad(1, SurfaceInput.TAP, left),
            pad(2, SurfaceInput.TAP, right),
            btn("b", 1, SurfaceInput.TAP, key),
        )
        // Chip by chip: its own beside the touchpad's.
        val out = Inheritance.toButtons(inUse, listOf("b"))
        assertEquals(listOf<OutputTarget>(key, left), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        assertEquals(listOf<OutputTarget>(right), targetOf(out, InputSource.Surface(2, SurfaceInput.TAP, "b")))
        // An Off stops that one chip; a copy of its own replaces it.
        val off = Inheritance.toButtons(inUse + btn("b", 1, SurfaceInput.TAP, OutputTarget.Off(left)), listOf("b"))
        assertEquals(listOf<OutputTarget>(key, OutputTarget.Off(left)), targetOf(off, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        val copy = Inheritance.toButtons(inUse + btn("b", 1, SurfaceInput.TAP, left), listOf("b"))
        assertEquals(listOf<OutputTarget>(key, left), targetOf(copy, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        // What the touchpad runs for a finger on it, and what it must not.
        assertEquals(setOf(inUse[0]), Inheritance.passedOn(inUse + btn("b", 1, SurfaceInput.TAP, OutputTarget.Off(left)), "b"))
        assertEquals(null, Inheritance.passedOn(listOf(pad(1, SurfaceInput.TAP, left), btn("b", 1, SurfaceInput.TAP, OutputTarget.NoOutput)), "b"))
    }

    @Test fun aButtonsBaseOffStopsTheWorkspacesLayerChip() {
        val all = listOf(
            pad(1, SurfaceInput.TAP, left),
            pad(1, SurfaceInput.TAP, right, set = 1),
            btn("b", 1, SurfaceInput.TAP, key),
        )
        // Layer 1 laid over Base.
        val out = Inheritance.toButtons(layered(all, 0, listOf(1), keepNothing = true), listOf("b"))
        assertEquals(listOf<OutputTarget>(key, right), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        val off = Inheritance.toButtons(layered(all + btn("b", 1, SurfaceInput.TAP, OutputTarget.Off(right)), 0, listOf(1), keepNothing = true), listOf("b"))
        assertEquals(listOf<OutputTarget>(key, OutputTarget.Off(right)), targetOf(off, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        assertEquals(listOf<OutputTarget>(right), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP)))
    }

    @Test fun theEditorShowsTheLayersInheritedMappings() {
        val touchpad = listOf(pad(1, SurfaceInput.TAP, left), pad(1, SurfaceInput.TAP, right, set = 1), pad(2, SurfaceInput.TAP, right))
        val own = listOf(btn("b", 2, SurfaceInput.TAP, key))
        val inBase = Inheritance.inherited(touchpad, own, "b", 0)
        assertEquals(listOf(btn("b", 1, SurfaceInput.TAP, left), btn("b", 2, SurfaceInput.TAP, right)), inBase)
        val inLayer = Inheritance.inherited(touchpad, own, "b", 1)
        assertEquals(listOf(btn("b", 2, SurfaceInput.TAP, right), btn("b", 1, SurfaceInput.TAP, right)).toSet(), inLayer.map { it.copy(set = 0) }.toSet())
        val cleared = Inheritance.inherited(touchpad, own + btn("b", 1, SurfaceInput.TAP, OutputTarget.NoOutput), "b", 1)
        assertTrue(cleared.none { (it.source as InputSource.Surface).fingers == 1 })
    }

    @Test fun anOffOfAChipItMapsGoes() {
        val ms = listOf(btn("b", 1, SurfaceInput.TAP, OutputTarget.Off(key)), btn("b", 1, SurfaceInput.TAP, key), btn("b", 2, SurfaceInput.TAP, OutputTarget.NoOutput))
        assertEquals(listOf(btn("b", 1, SurfaceInput.TAP, key), btn("b", 2, SurfaceInput.TAP, OutputTarget.NoOutput)), Inheritance.tidy(ms))
    }

    @Test fun anEmptyButtonClearsEverythingItWouldTake() {
        val touchpad = listOf(pad(1, SurfaceInput.HOLD, left), pad(2, SurfaceInput.TAP, right), pad(2, SurfaceInput.TAP, left, set = 1))
        val empty = Inheritance.cleared(touchpad, "b")
        assertEquals(setOf(InputSource.Touch("b"), InputSource.Surface(2, SurfaceInput.HOLD, "b")), empty.mapTo(HashSet()) { it.source })
        assertTrue(empty.all { it.target == OutputTarget.NoOutput && it.set == 0 })
        assertTrue(Inheritance.toButtons(touchpad.filter { it.set == 0 } + empty, listOf("b")).none { m ->
            (m.source as? InputSource.Surface)?.id == "b" && m.target != OutputTarget.NoOutput
        })
    }

    @Test fun mappingOneTriggerLeavesTheOtherChipsInherited() {
        val release = InputSource.Surface(1, SurfaceInput.HOLD_UP, on = com.oleroma.gyrophone.core.model.Trigger.RELEASE)
        // The Defaults map On swipe and Quick swipe, the workspace only Release: none of the Defaults' are left.
        val d = WorkspaceDefaults.builtin.copy(mappings = listOf(pad(1, SurfaceInput.HOLD_UP, key), pad(1, SurfaceInput.SWIPE_UP, right)))
        val w = Workspace("w", "w", mappings = listOf(Mapping(release, left)))
        val out = Inheritance.workspace(w, d)
        assertEquals(listOf<OutputTarget>(key), targetOf(out, InputSource.Surface(1, SurfaceInput.HOLD_UP)))
        assertEquals(listOf<OutputTarget>(right), targetOf(out, InputSource.Surface(1, SurfaceInput.SWIPE_UP)))
        assertEquals(listOf<OutputTarget>(left), targetOf(out, release))
        // Saved before: mapping Release took none of the Defaults' Swipe up; converted, it still takes none.
        val old = Inheritance.chipByChip(w, d)
        val conv = Inheritance.workspace(old, d)
        assertEquals(emptyList<OutputTarget>(), targetOf(conv, InputSource.Surface(1, SurfaceInput.HOLD_UP)).filterNot { it is OutputTarget.Off })
        assertEquals(emptyList<OutputTarget>(), targetOf(conv, InputSource.Surface(1, SurfaceInput.SWIPE_UP)).filterNot { it is OutputTarget.Off })
        assertEquals(listOf<OutputTarget>(left), targetOf(conv, release))
        assertEquals(old, Inheritance.chipByChip(old, d))
        // A button that maps only Release on Swipe up still takes the touchpad's Swipe up.
        val inUse = listOf(pad(1, SurfaceInput.HOLD_UP, key), Mapping(release.copy(id = "b"), left))
        val buttons = Inheritance.toButtons(inUse, listOf("b"))
        assertEquals(listOf<OutputTarget>(key), targetOf(buttons, InputSource.Surface(1, SurfaceInput.HOLD_UP, "b")))
        assertEquals(listOf<OutputTarget>(left), targetOf(buttons, release.copy(id = "b")))
    }

    @Test fun aClearedActionStaysClearedBesideItsOwnChips() {
        val ms = listOf(Mapping(InputSource.Touch("b"), OutputTarget.NoOutput), btn("b", 1, SurfaceInput.TAP, key))
        assertEquals(ms, Inheritance.tidy(ms))
    }

    @Test fun oldButtonsKeepTakingNothingOfWhatTheyMapped() {
        // Saved before: a button mapping its tap took none of the touchpad's tap chips.
        val w = Workspace(
            "w", "w",
            mappings = listOf(pad(1, SurfaceInput.TAP, left), pad(1, SurfaceInput.TAP, right), pad(2, SurfaceInput.TAP, right), btn("b", 1, SurfaceInput.TAP, key)),
        )
        val c = Inheritance.chipByChip(w, WorkspaceDefaults.builtin.copy(mappings = emptyList()))
        assertTrue(c.chipInherit)
        val out = Inheritance.toButtons(c.mappings, listOf("b")).filterNot { it.target.let { t -> t is OutputTarget.Off } }
        assertEquals(listOf<OutputTarget>(key), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        assertEquals(listOf<OutputTarget>(right), targetOf(out, InputSource.Surface(2, SurfaceInput.TAP, "b")))
    }
}
