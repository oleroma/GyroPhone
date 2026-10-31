package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.TriggerReset
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerResetTest {
    @Test
    fun keepsLayoutAndNamesOnly() {
        val c = TouchControl("b", TouchKind.BUTTON, "Fire", 0.3f, 0.4f, 0.2f, TouchShape.SQUARE, aspect = 1.5f,
            longPressMs = 700, followLayers = true, group = "g1")
        val w = Workspace(id = "w1", name = "Shooter", touchControls = listOf(c),
            mappings = listOf(Mapping(InputSource.Touch("b"), OutputTarget.Key(4))), sets = listOf(MappingSet(1)),
            longPressMs = 900)
        val old = Profile(id = "set", name = "Mine", workspaces = listOf(w), wrapWorkspaces = true)
        val r = TriggerReset.apply(old, BuiltinProfiles.default)
        assertEquals("set", r.id)
        assertEquals(listOf("w1"), r.workspaces.map { it.id })
        val rw = r.workspaces.single()
        assertEquals("Shooter", rw.name)
        assertTrue(rw.mappings.isEmpty() && rw.sets.isEmpty())
        assertEquals(Workspace.DEFAULT_LONG_PRESS_MS, rw.longPressMs)
        val rc = rw.touchControls.single()
        assertEquals(c.copy(longPressMs = null, followLayers = false), rc)
        assertEquals(false, r.wrapWorkspaces)
    }
}
