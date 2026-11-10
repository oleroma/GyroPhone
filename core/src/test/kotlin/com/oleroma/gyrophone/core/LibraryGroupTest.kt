package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupTest {
    @Test
    fun savedGroupAddsAsANewGroupKeepingItsShape() {
        val a = TouchControl("btn1", TouchKind.BUTTON, "A", 0.2f, 0.3f, group = "g1")
        val b = TouchControl("btn2", TouchKind.BUTTON, "B", 0.4f, 0.5f, group = "g1")
        val w = Workspace(
            id = "w", name = "W", touchControls = listOf(a, b),
            mappings = listOf(
                Mapping(InputSource.Touch("btn1"), OutputTarget.Key(4)),
                Mapping(InputSource.Touch("btn2"), OutputTarget.Key(5)),
            ),
        )
        val saved = ButtonLibrary.saveGroup(w, listOf(a, b), emptyList())
        assertTrue(ButtonLibrary.isGroup(saved))
        assertEquals("Group of 2", ButtonLibrary.name(saved))
        // Added to a workspace whose own control is "btn2": member ids swap, mappings follow each member.
        val target = Workspace(id = "t", name = "T", touchControls = listOf(TouchControl("btn9", TouchKind.BUTTON, "", 0f, 0f, group = "g1")))
        val out = ButtonLibrary.addGroupTo(target, saved, listOf("btn2", "btn1")) { it.copy(x = 0.5f, y = 0.5f) }
        val n1 = out.touchControls.first { it.id == "btn2" }
        val n2 = out.touchControls.first { it.id == "btn1" }
        assertEquals("A", n1.label)
        assertEquals(0.7f, n2.x, 1e-5f)
        assertEquals(0.7f, n2.y, 1e-5f)
        assertEquals(n1.group, n2.group)
        assertTrue(n1.group != null && n1.group != "g1")
        val keyOf = out.mappings.associate { MappingProfiles.touchId(it.source) to it.target }
        assertEquals(OutputTarget.Key(4), keyOf["btn2"])
        assertEquals(OutputTarget.Key(5), keyOf["btn1"])
    }
}
