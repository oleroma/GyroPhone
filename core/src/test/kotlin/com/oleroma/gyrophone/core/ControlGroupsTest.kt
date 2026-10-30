package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.ControlGroups
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlGroupsTest {
    private fun ws(vararg ids: String) =
        Workspace(id = "w", name = "W", touchControls = ids.map { TouchControl(it, TouchKind.BUTTON, it, 0.5f, 0.5f) })

    @Test
    fun groupAndUngroup() {
        val w = ControlGroups.group(ws("a", "b", "c"), listOf("a", "b"))
        assertEquals(setOf("a", "b"), ControlGroups.unitOf(w.touchControls, "a"))
        assertEquals(setOf("c"), ControlGroups.unitOf(w.touchControls, "c"))
        assertEquals(2, ControlGroups.units(w.touchControls, listOf("a", "b", "c")))
        assertTrue(ControlGroups.anyGrouped(w.touchControls, listOf("b")))
        val u = ControlGroups.ungroup(w, listOf("b"))
        assertTrue(u.touchControls.all { it.group == null })
    }

    @Test
    fun groupingAGroupMergesIt() {
        val w = ControlGroups.group(ws("a", "b", "c", "d"), listOf("a", "b"))
        val g = w.touchControls.first { it.id == "a" }.group
        val m = ControlGroups.group(w, listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), ControlGroups.unitOf(m.touchControls, "a"))
        assertFalse(m.touchControls.first { it.id == "a" }.group == g)
        assertNull(m.touchControls.first { it.id == "d" }.group)
    }

    @Test
    fun oneControlIsNoGroup() {
        val w = ws("a", "b")
        assertEquals(w, ControlGroups.group(w, listOf("a")))
        val grouped = ControlGroups.group(w, listOf("a", "b"))
        val left = ControlGroups.tidy(grouped.copy(touchControls = grouped.touchControls.filter { it.id == "a" }))
        assertNull(left.touchControls.single().group)
    }

    @Test
    fun groupIsSaved() {
        val w = ControlGroups.group(ws("a", "b"), listOf("a", "b"))
        val text = ProfileJson.json.encodeToString(Workspace.serializer(), w)
        assertEquals(w, ProfileJson.json.decodeFromString(Workspace.serializer(), text))
    }
}
