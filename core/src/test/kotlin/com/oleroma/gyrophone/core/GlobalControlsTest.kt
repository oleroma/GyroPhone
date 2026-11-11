package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GlobalControls
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalControlsTest {
    private fun button(id: String, x: Float = 0.5f) = TouchControl(id, TouchKind.BUTTON, id, x, 0.5f)

    private fun two(): Profile = Profile(
        id = "p", name = "p",
        workspaces = listOf(
            Workspace(id = "ws1", name = "A", touchControls = listOf(button("b1"))),
            Workspace(id = "ws2", name = "B", touchControls = listOf(button("b1", 0.2f))),
        ),
        navButtons = true, corners = emptyList(),
    )

    @Test
    fun corners_become_global_arrows_in_every_workspace() {
        val p = NavCorners.toButtons(two().copy(navButtons = false, corners = NavCorners.defaults()))
        assertTrue(p.navButtons)
        assertTrue(p.corners.isEmpty())
        for (w in p.workspaces) {
            val prev = w.touchControls.single { it.id == NavCorners.PREV }
            assertTrue(prev.global)
            assertEquals(TouchKind.BUTTON, prev.kind)
            assertTrue(Mapping(InputSource.TouchTap(NavCorners.PREV), OutputTarget.PreviousWorkspace) in w.mappings)
            assertTrue(Mapping(InputSource.TouchLongPress(NavCorners.NEXT), OutputTarget.Fullscreen) in w.mappings)
        }
        // Saved and read back: no corners come back.
        val back = ProfileJson.normalize(p)
        assertTrue(back.corners.isEmpty())
    }

    @Test
    fun make_global_copies_and_keeps_other_workspaces_own_control_with_that_id() {
        val p = two().updateWorkspace(0) { it.copy(touchControls = it.touchControls + button("g")) }
        val g = GlobalControls.makeGlobal(p, 0, "g")
        assertTrue(g.workspaces.all { w -> w.touchControls.any { it.id == "g" && it.global } })
        val own = GlobalControls.makeGlobal(p, 0, "b1")
        assertEquals(0.2f, own.workspaces[1].touchControls.single { it.id == "b1" }.x)
        assertFalse(own.workspaces[1].touchControls.single { it.id == "b1" }.global)
    }

    @Test
    fun a_change_moves_with_it_and_a_delete_deletes_everywhere() {
        val p = GlobalControls.makeGlobal(
            two().updateWorkspace(0) { it.copy(touchControls = it.touchControls + button("g")) }, 0, "g",
        )
        val moved = p.updateWorkspace(1) { w -> w.copy(touchControls = w.touchControls.map { if (it.id == "g") it.copy(x = 0.9f) else it }) }
        val synced = GlobalControls.sync(p, moved)
        assertEquals(0.9f, synced.workspaces[0].touchControls.single { it.id == "g" }.x)
        val mapped = synced.updateWorkspace(0) { it.copy(mappings = it.mappings + Mapping(InputSource.Touch("g"), OutputTarget.Fullscreen)) }
        val m2 = GlobalControls.sync(synced, mapped)
        assertTrue(Mapping(InputSource.Touch("g"), OutputTarget.Fullscreen) in m2.workspaces[1].mappings)
        val deleted = GlobalControls.sync(m2, m2.updateWorkspace(1) { Bindings.removeTouchControl(it, "g") })
        assertTrue(deleted.workspaces.none { w -> w.touchControls.any { it.id == "g" } })
        // A new workspace gets it.
        val added = m2.copy(workspaces = m2.workspaces + Workspace(id = "ws3", name = "C"))
        assertTrue(GlobalControls.sync(m2, added).workspaces[2].touchControls.any { it.id == "g" })
        // Make local: every copy stays, each its own.
        val local = GlobalControls.makeLocal(m2, "g")
        assertTrue(local.workspaces.all { w -> w.touchControls.single { it.id == "g" }.let { !it.global } })
    }
}
