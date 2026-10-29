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
        val out = Inheritance.toButtons(inUse, listOf("b"))
        assertEquals(listOf<OutputTarget>(key), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        assertEquals(listOf<OutputTarget>(right), targetOf(out, InputSource.Surface(2, SurfaceInput.TAP, "b")))
    }

    @Test fun aButtonsBaseMappingBeatsTheWorkspacesLayer() {
        val all = listOf(
            pad(1, SurfaceInput.TAP, left),
            pad(1, SurfaceInput.TAP, right, set = 1),
            btn("b", 1, SurfaceInput.TAP, key),
        )
        // Layer 1 laid over Base.
        val out = Inheritance.toButtons(layered(all, 0, listOf(1), keepNothing = true), listOf("b"))
        assertEquals(listOf<OutputTarget>(key), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP, "b")))
        assertEquals(listOf<OutputTarget>(right), targetOf(out, InputSource.Surface(1, SurfaceInput.TAP)))
    }

    @Test fun theEditorShowsTheLayersInheritedMappings() {
        val touchpad = listOf(pad(1, SurfaceInput.TAP, left), pad(1, SurfaceInput.TAP, right, set = 1), pad(2, SurfaceInput.TAP, right))
        val own = listOf(btn("b", 2, SurfaceInput.TAP, key))
        val inBase = Inheritance.inherited(touchpad, own, "b", 0)
        assertEquals(listOf(btn("b", 1, SurfaceInput.TAP, left)), inBase)
        val inLayer = Inheritance.inherited(touchpad, own, "b", 1)
        assertEquals(listOf(btn("b", 1, SurfaceInput.TAP, right)), inLayer.map { it.copy(set = 0) })
        assertTrue(Inheritance.inherited(touchpad, own + btn("b", 1, SurfaceInput.TAP, OutputTarget.NoOutput), "b", 1).isEmpty())
    }

    @Test fun mappingAClearedInputReplacesTheClear() {
        val ms = listOf(btn("b", 1, SurfaceInput.TAP, OutputTarget.NoOutput), btn("b", 1, SurfaceInput.TAP, key), btn("b", 2, SurfaceInput.TAP, OutputTarget.NoOutput))
        assertEquals(listOf(btn("b", 1, SurfaceInput.TAP, key), btn("b", 2, SurfaceInput.TAP, OutputTarget.NoOutput)), Inheritance.tidy(ms))
    }

    @Test fun anEmptyButtonClearsEverythingItWouldTake() {
        val touchpad = listOf(pad(1, SurfaceInput.HOLD, left), pad(2, SurfaceInput.TAP, right), pad(2, SurfaceInput.TAP, left, set = 1))
        val empty = Inheritance.cleared(touchpad, "b")
        assertEquals(setOf(InputSource.Touch("b"), InputSource.Surface(2, SurfaceInput.TAP, "b")), empty.mapTo(HashSet()) { it.source })
        assertTrue(empty.all { it.target == OutputTarget.NoOutput && it.set == 0 })
        assertTrue(Inheritance.toButtons(touchpad.filter { it.set == 0 } + empty, listOf("b")).none { m ->
            (m.source as? InputSource.Surface)?.id == "b" && m.target != OutputTarget.NoOutput
        })
    }
}
