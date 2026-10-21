package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PadPresetsTest {
    private val pad = AttachedPad(id = "pad1", mappings = KnownLayouts.familyMappings(FaceLayout.XBOX))

    @Test fun builtinPadsShowTheirPreset() {
        assertEquals(PadPresets.STANDARD, PadPresets.applied(pad, emptyList())?.id)
        val wasd = BuiltinProfiles.wasdWorkspace.pads.single()
        assertEquals(PadPresets.KBM, PadPresets.applied(wasd, emptyList())?.id)
        // Standard follows the family: a Nintendo pad with Nintendo mappings is still Standard.
        val nin = AttachedPad(id = "n", family = FaceLayout.NINTENDO, mappings = KnownLayouts.familyMappings(FaceLayout.NINTENDO))
        assertEquals(PadPresets.STANDARD, PadPresets.applied(nin, emptyList())?.id)
    }

    @Test fun editingMakesItCustomAndOrderDoesNotMatter() {
        assertEquals(PadPresets.STANDARD, PadPresets.applied(pad.copy(mappings = pad.mappings.reversed()), emptyList())?.id)
        val edited = pad.copy(mappings = pad.mappings.drop(1) + Mapping(InputSource.Key(96), OutputTarget.Key(44)))
        assertNull(PadPresets.applied(edited, emptyList()))
        assertEquals(PadPresets.CUSTOM_NAME, PadPresets.label(edited, emptyList()))
    }

    @Test fun userPresetsApplyAndRoundTrip() {
        val mine = PadPreset(PadPresets.newId(emptyList()), "Racing", listOf(Mapping(InputSource.Key(96), OutputTarget.Key(44))))
        val applied = PadPresets.apply(pad, mine)
        assertEquals("Racing", PadPresets.label(applied, listOf(mine)))
        assertEquals(listOf(mine), PadPresets.decode(PadPresets.encode(listOf(mine))))
        assertNull(PadPresets.decode("not json"))
        assertEquals("user.pad2", PadPresets.newId(listOf("user.pad1")))
    }
}
