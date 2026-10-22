package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingProfilesTest {
    private val a = OutputTarget.Pad(GamepadButton.A)
    private val b = OutputTarget.Pad(GamepadButton.B)
    private fun touch(id: String, t: OutputTarget) = Mapping(InputSource.Touch(id), t)
    private fun key(code: Int, t: OutputTarget) = Mapping(InputSource.Key(code), t)
    private fun button(id: String) = TouchControl(id, TouchKind.BUTTON, id, 0.5f, 0.5f)
    private fun pad(id: String, mappings: List<Mapping>) = AttachedPad(id, mappings = mappings)

    private val ws1 = Workspace("ws1", "Main", listOf(button("btn1"), button("btn2")),
        mappings = listOf(touch("btn1", a), touch("btn2", b)), pads = listOf(pad("pad1", listOf(key(AndroidKey.BUTTON_A, a)))))
    private val ws2 = Workspace("ws2", "Copy", listOf(button("btn1"), button("btn2")),
        mappings = listOf(touch("btn1", a), touch("btn2", b)), pads = listOf(pad("pad1", listOf(key(AndroidKey.BUTTON_A, a)))))
    private val ws3 = Workspace("ws3", "Other", listOf(button("btn1")),
        mappings = listOf(touch("btn1", b)), pads = listOf(pad("pad1", listOf(key(AndroidKey.BUTTON_A, b))), pad("pad2", listOf(key(AndroidKey.BUTTON_B, b)))))
    private val set = Profile("user.workspaces", "Workspaces", listOf(ws1, ws2, ws3))

    @Test fun migrateSharesEqualWorkspacesAndKeepsEveryMapping() {
        val m = MappingProfiles.migrate(set, emptyList())
        assertEquals(listOf("Main", "Other"), m.profiles.map { it.name })
        assertEquals(listOf("map1", "map1", "map2"), m.set.workspaces.map { it.mappingProfileId })
        assertTrue(m.set.workspaces.all { w -> w.mappings.isEmpty() && w.pads.all { it.mappings.isEmpty() } })
        // The second pad of "Other" maps differently: kept as a pad preset.
        assertEquals(listOf("Other · ${AttachedPad.ANY_NAME}"), m.presets.map { it.name })
        // Resolving gives back what each workspace had.
        val resolved = MappingProfiles.resolve(m.set, m.profiles)
        for ((old, new) in set.workspaces.zip(resolved.workspaces)) {
            assertEquals(old.mappings.toSet(), new.mappings.toSet())
            assertEquals(old.pads.first().mappings, new.pads.first().mappings)
        }
    }

    @Test fun migrateSkipsPadMappingsAPresetAlreadyHas() {
        val m = MappingProfiles.migrate(set, listOf(PadPreset("user.pad1", "Mine", listOf(key(AndroidKey.BUTTON_B, b)))))
        assertEquals(emptyList<PadPreset>(), m.presets)
    }

    @Test fun absorbTakesTouchAndControllerEditsIntoTheWorkspacesProfile() {
        val m = MappingProfiles.migrate(set, emptyList())
        val before = MappingProfiles.resolve(m.set, m.profiles)
        val after = before.updateWorkspace(0) { w ->
            w.copy(mappings = listOf(touch("btn1", b), touch("btn2", b)), pads = w.pads.map { it.copy(mappings = listOf(key(AndroidKey.BUTTON_X, a))) })
        }
        val profiles = MappingProfiles.absorb(m.profiles, before, after)
        assertEquals(setOf(touch("btn1", b), touch("btn2", b)), profiles[0].touch.toSet())
        assertEquals(listOf(key(AndroidKey.BUTTON_X, a)), profiles[0].controller)
        assertEquals(m.profiles[1], profiles[1])
        // ws2 shares the profile, so it changes too; ws3 does not.
        val again = MappingProfiles.resolve(MappingProfiles.strip(after), profiles)
        assertEquals(setOf(touch("btn1", b), touch("btn2", b)), again.workspaces[1].mappings.toSet())
        assertEquals(listOf(touch("btn1", b)), again.workspaces[2].mappings)
    }

    @Test fun absorbKeepsMappingsOfADeletedControlAndIgnoresANewPad() {
        val m = MappingProfiles.migrate(set, emptyList())
        val before = MappingProfiles.resolve(m.set, m.profiles)
        val after = before.updateWorkspace(0) { w ->
            w.copy(touchControls = listOf(button("btn1")), mappings = listOf(touch("btn1", a)), pads = w.pads + pad("pad9", listOf(key(AndroidKey.BUTTON_Y, a))))
        }
        assertEquals(m.profiles, MappingProfiles.absorb(m.profiles, before, after))
    }

    @Test fun aNewWorkspaceWithoutProfileUsesTheFirst() {
        val profiles = listOf(MappingProfile("map1", "A", touch = listOf(touch("btn1", a))), MappingProfile("map2", "B"))
        val w = Workspace("ws9", "New", listOf(button("btn1")))
        val resolved = MappingProfiles.resolve(Profile("s", "s", listOf(w)), profiles)
        assertEquals("map1", resolved.workspaces[0].mappingProfileId)
        assertEquals(listOf(touch("btn1", a)), resolved.workspaces[0].mappings)
    }

    @Test fun builtinWorkspacesEachGetAProfile() {
        val m = MappingProfiles.migrate(BuiltinProfiles.default, emptyList())
        assertEquals(BuiltinProfiles.default.workspaces.map { it.name }, m.profiles.map { it.name })
        assertEquals(m.profiles.map { it.id }, m.set.workspaces.map { it.mappingProfileId })
    }

    @Test fun keyboardsUseTheKeyboardMappingsAnyPadsOrNot() {
        val kb = PadDevice(4, 0x1234, 1, "BT Keyboard", keyboard = true)
        val profile = MappingProfile("map1", "A", keyboard = listOf(key(AndroidKey.BUTTON_A, OutputTarget.Key(0x04))))
        val set = MappingProfiles.resolve(Profile("s", "s", listOf(Workspace("ws1", "Main"))), listOf(profile))
        val engine = MappingEngine(set)
        assertTrue(engine.setKey(kb, AndroidKey.BUTTON_A, true))
        assertEquals(listOf(0x04), engine.snapshot().keyboard.keys)
    }

    @Test fun adoptGivesAnAddedPresetItsOwnProfileAndABlankOneTheFallback() {
        val profiles = listOf(MappingProfile("map1", "A", touch = listOf(touch("btn1", a))))
        val preset = Workspace("ws2", "Preset", listOf(button("btn1")), mappings = listOf(touch("btn1", b)))
        val blank = Workspace("ws3", "Blank")
        val after = Profile("s", "s", listOf(Workspace("ws1", "Main", mappingProfileId = "map1"), preset, blank))
        val (set, all) = MappingProfiles.adopt(profiles, after, "map1")
        assertEquals(listOf("map1", "map2", "map1"), set.workspaces.map { it.mappingProfileId })
        assertEquals(listOf(touch("btn1", b)), all[1].touch)
        assertEquals(profiles[0], all[0])
    }

    @Test fun encodeRoundTrips() {
        val list = MappingProfiles.migrate(set, emptyList()).profiles
        assertEquals(list, MappingProfiles.decode(MappingProfiles.encode(list)))
    }
}
