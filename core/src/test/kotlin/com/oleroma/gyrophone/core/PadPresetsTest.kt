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
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertSame
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

    @Test fun aNamedPresetWinsOverAnEqualOne() {
        // A user preset saved from the standard mapping has the same mappings as Standard.
        val copy = PadPreset("user.pad1", "My standard", pad.mappings)
        val loaded = PadPresets.apply(pad, copy)
        assertEquals("user.pad1", loaded.presetId)
        assertEquals("My standard", PadPresets.label(loaded, listOf(copy)))
        // Unnamed pads still resolve by their mappings.
        assertEquals(PadPresets.STANDARD, PadPresets.applied(pad, listOf(copy))?.id)
        // Once the mappings differ from the named preset, it no longer applies.
        val edited = loaded.copy(mappings = loaded.mappings.drop(1))
        assertNull(PadPresets.applied(edited, listOf(copy)))
    }

    @Test fun retargetUpdatesOnlyPadsNamingThePreset() {
        val racing = PadPreset("user.pad1", "Racing", listOf(Mapping(InputSource.Key(96), OutputTarget.Key(44))))
        val named = PadPresets.apply(pad.copy(id = "pad1"), racing)
        val lookalike = pad.copy(id = "pad2", mappings = racing.mappings)
        val profile = Profile(
            id = "user.p", name = "P",
            workspaces = listOf(Workspace("ws1", "A", pads = listOf(named, lookalike)), Workspace("ws2", "B", pads = listOf(pad))),
        )
        assertSame(profile, PadPresets.retarget(profile, racing))
        val changed = racing.copy(mappings = listOf(Mapping(InputSource.Key(97), OutputTarget.Key(45))))
        val out = PadPresets.retarget(profile, changed)
        assertEquals(changed.mappings, out.workspaces[0].pads[0].mappings)
        assertEquals(racing.mappings, out.workspaces[0].pads[1].mappings)
        assertSame(profile.workspaces[1], out.workspaces[1])
    }
}
