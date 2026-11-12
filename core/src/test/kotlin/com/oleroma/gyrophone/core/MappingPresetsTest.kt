package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingPreset
import com.oleroma.gyrophone.core.model.MappingPresets
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PresetLevel
import com.oleroma.gyrophone.core.model.StickZones
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.How
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingPresetsTest {
    private val a = TouchControl("a", TouchKind.BUTTON, "A", 0.5f, 0.5f)
    private val b = TouchControl("b", TouchKind.BUTTON, "B", 0.2f, 0.5f)
    private fun tap(id: String) = com.oleroma.gyrophone.core.model.BindableInput("Tap", listOf(InputSource.TouchTap(id)))

    @Test fun cardPresetLandsOnTheSameCardOfAnotherButton() {
        val toggle = MapOptions(how = How.TOGGLE)
        val m = listOf(Mapping(InputSource.TouchTap("a"), OutputTarget.Key(0x04), options = toggle))
        val chips = MappingPresets.chipsOf(m, listOf(tap("a")))
        assertEquals(InputSource.TouchTap(""), chips.single().slot)
        val p = MappingPreset("p1", "Tap A", PresetLevel.CARD, MappingPresets.kindOf(tap("a")), chips)
        assertEquals(p.kind, MappingPresets.kindOf(tap("b")))
        // Added beside what b's tap does; a second time changes nothing.
        val start = listOf(Mapping(InputSource.TouchTap("b"), OutputTarget.Key(0x05)))
        val once = MappingPresets.place(start, p.chips, listOf(tap("b")), replace = false)
        assertEquals(listOf(OutputTarget.Key(0x05), OutputTarget.Key(0x04)), Bindings.targets(once, tap("b")))
        assertEquals(toggle, Bindings.options(once, tap("b"), OutputTarget.Key(0x04)))
        assertEquals(once, MappingPresets.place(once, p.chips, listOf(tap("b")), replace = false))
        // Replace: only the preset's.
        val replaced = MappingPresets.place(start, p.chips, listOf(tap("b")), replace = true)
        assertEquals(listOf(OutputTarget.Key(0x04)), Bindings.targets(replaced, tap("b")))
    }

    @Test fun physicalSlotsUseTheInputsPlace() {
        assertEquals(InputSource.Key(0), MappingPresets.slot(InputSource.Key(96)))
        assertEquals(InputSource.Key(2), MappingPresets.slot(InputSource.Key(99), index = 2))
        // A stick card fits any stick's same card.
        val s1 = TouchControl("s1", TouchKind.STICK, "", 0f, 0f)
        val s2 = TouchControl("s2", TouchKind.STICK, "", 0f, 0f)
        assertEquals(MappingPresets.kindOf(Bindings.touchInputs(s1)[0]), MappingPresets.kindOf(Bindings.touchInputs(s2)[0]))
        assertFalse(MappingPresets.kindOf(Bindings.touchInputs(s1)[0]) == MappingPresets.kindOf(Bindings.touchInputs(s1)[1]))
    }

    @Test fun settingsAreWrittenOnlyWhereTheyChangeSomething() {
        val w = Workspace(id = "w", name = "w", longPressMs = 300)
        val keys = setOf("longPressMs")
        // The workspace's time already: nothing written, so no override shows.
        assertEquals(null, MappingPresets.applySettings(b, a.copy(longPressMs = 300), keys, w).longPressMs)
        assertEquals(450, MappingPresets.applySettings(b, a.copy(longPressMs = 450), keys, w).longPressMs)
        // The preset follows the workspace: so does the target again.
        assertEquals(null, MappingPresets.applySettings(b.copy(longPressMs = 450), a, keys, w).longPressMs)
        // Only the keys given.
        val stick = TouchControl("s", TouchKind.STICK, "", 0f, 0f, deadzone = 0.2f, zones = StickZones.EIGHT)
        val other = TouchControl("t", TouchKind.STICK, "", 0f, 0f)
        val dz = MappingPresets.applySettings(other, stick, setOf("deadzone"), w)
        assertEquals(0.2f, dz.deadzone)
        assertEquals(StickZones.OVERLAP, dz.zones)
        assertTrue(MappingPresets.hasSettings(stick, setOf("deadzone")))
        assertFalse(MappingPresets.hasSettings(other, setOf("deadzone", "zones")))
    }

    @Test fun presetsRoundTripAsJson() {
        val p = MappingPreset(
            "p1", "x", PresetLevel.CARD, InputSource.TouchTap(""),
            listOf(com.oleroma.gyrophone.core.model.PresetChip(InputSource.TouchTap(""), OutputTarget.Key(4))), a.copy(longPressMs = 200),
        )
        assertEquals(listOf(p), MappingPresets.decode(MappingPresets.encode(listOf(p))))
    }
}
