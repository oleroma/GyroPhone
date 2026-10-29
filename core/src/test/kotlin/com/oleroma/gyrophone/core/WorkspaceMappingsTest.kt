package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.DevicePresets
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceEdits
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.isTouchpad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMappingsTest {
    private val a = OutputTarget.Pad(GamepadButton.A)
    private val b = OutputTarget.Pad(GamepadButton.B)
    private val x = OutputTarget.Pad(GamepadButton.X)
    private fun touch(id: String, t: OutputTarget, set: Int = 0) = Mapping(InputSource.Touch(id), t, set)
    private fun key(code: Int, t: OutputTarget) = Mapping(InputSource.Key(code), t)
    private fun button(id: String, label: String = id) = TouchControl(id, TouchKind.BUTTON, label, 0.5f, 0.5f)
    private fun Workspace.controls() = mappings.filterNot { it.source.isTouchpad }

    private val ws1 = Workspace(
        "ws1", "Main", listOf(button("btn1", "Jump"), button("btn2", "Fire")),
        mappings = listOf(touch("btn1", a), touch("btn2", b)),
        pads = listOf(AttachedPad("pad1", mappings = listOf(key(AndroidKey.BUTTON_A, a)))),
    )
    private val ws2 = Workspace(
        "ws2", "Other", listOf(button("btn1", "Fire"), button("btn9", "Crouch")),
        mappings = listOf(touch("btn1", x), touch("btn9", x)),
        pads = listOf(AttachedPad("pad1", mappings = listOf(key(AndroidKey.BUTTON_A, b)))),
    )
    private val set = Profile("user.workspaces", "Workspaces", listOf(ws1, ws2))

    /** The set as the app keeps it: migrated, stored, resolved. */
    private fun live(): Profile {
        val m = MappingProfiles.migrate(set)
        return WorkspaceMappings.resolve(WorkspaceMappings.store(WorkspaceMappings.fromProfiles(m.set, m.profiles)))
    }

    @Test fun movingOutOfProfilesKeepsWhatEveryWorkspaceDid() {
        val resolved = live()
        for ((old, new) in set.workspaces.zip(resolved.workspaces)) {
            assertEquals(old.mappings.toSet(), new.controls().toSet())
            assertEquals(null, new.mappingProfileId)
        }
    }

    @Test fun workspacesSharingAProfileGetTheirOwnCopies() {
        val same = set.copy(workspaces = listOf(ws1, ws1.copy(id = "ws2", name = "Copy")))
        val m = MappingProfiles.migrate(same)
        assertEquals(1, m.profiles.size)
        val before = WorkspaceMappings.resolve(WorkspaceMappings.fromProfiles(m.set, m.profiles))
        val after = before.updateWorkspace(0) { w -> w.copy(mappings = listOf(touch("btn1", x))) }
        val settled = WorkspaceMappings.resolve(WorkspaceMappings.store(WorkspaceMappings.settle(before, after, null)))
        assertEquals(listOf(touch("btn1", x)), settled.workspaces[0].controls())
        // The other workspace is untouched.
        assertEquals(before.workspaces[1], settled.workspaces[1])
    }

    @Test fun workspacesPinningAPresetGetItsDevicesAndOthersNone() {
        val preset = DevicePresets.standard("preset1", "Preset 1").copy(controller = listOf(key(AndroidKey.BUTTON_A, x)))
        val stored = WorkspaceMappings.store(live())
        val pinned = DevicePresets.pin(DevicePresets.put(stored, preset), "preset1", setOf("ws2"))
        val resolved = WorkspaceMappings.resolve(pinned)
        assertTrue(resolved.workspaces[0].pads.isEmpty())
        assertTrue(resolved.workspaces[0].keyboard.isEmpty())
        assertEquals(listOf(listOf(key(AndroidKey.BUTTON_A, x))), resolved.workspaces[1].pads.map { it.mappings })
        assertEquals(preset.keyboard, resolved.workspaces[1].keyboard)
        assertEquals(preset.mouse, resolved.workspaces[1].mouse)
        // Stored, the device mappings stay in the preset only.
        val again = WorkspaceMappings.store(resolved)
        assertTrue(again.workspaces.all { it.pads.isEmpty() && it.keyboard.isEmpty() && it.mouse.isEmpty() })
        assertEquals(listOf(preset), again.devicePresets)
        // Removing the preset unpins it.
        val removed = DevicePresets.remove(pinned, "preset1")
        assertTrue(removed.workspaces.all { it.devicePreset == null })
    }

    @Test fun aBlankNewWorkspaceStartsWithTheCurrentOnesTouchpadAndNoPreset() {
        val before = live()
        val (after, _) = WorkspaceEdits.add(before, Workspace("", "New"))
        val settled = WorkspaceMappings.resolve(WorkspaceMappings.settle(before, after, before.workspaces[1]))
        val fresh = settled.workspaces.last()
        assertEquals(null, fresh.devicePreset)
        assertTrue(fresh.pads.isEmpty())
        assertTrue(fresh.controls().isEmpty())
    }

    @Test fun aBuiltinWorkspaceBringsItsPadMappingsAsAPresetItUses() {
        val before = live()
        val builtin = com.oleroma.gyrophone.core.model.BuiltinProfiles.wasdWorkspace
        val (after, _) = WorkspaceEdits.add(before, builtin)
        val settled = WorkspaceMappings.resolve(WorkspaceMappings.store(WorkspaceMappings.settle(before, after, before.workspaces[0])))
        val added = settled.workspaces.last()
        val preset = settled.devicePresets.single()
        assertEquals(preset.id, added.devicePreset)
        assertEquals(builtin.pads.first().mappings, preset.controller)
        assertEquals(listOf(preset.controller), added.pads.map { it.mappings })
    }

    @Test fun duplicateCopiesMappingsThatThenChangeOnTheirOwn() {
        val before = live()
        val (dup, at) = WorkspaceEdits.duplicate(before, 0)
        val withCopy = WorkspaceMappings.resolve(WorkspaceMappings.settle(before, dup, before.workspaces[0]))
        assertEquals(before.workspaces[0].controls(), withCopy.workspaces[at].controls())
        val edited = withCopy.updateWorkspace(at) { it.copy(mappings = listOf(touch("btn1", x))) }
        val settled = WorkspaceMappings.settle(withCopy, edited, null)
        assertEquals(before.workspaces[0].controls(), settled.workspaces[0].controls())
    }

    @Test fun copyWithButtonsBringsTheOtherLayout() {
        val resolved = live()
        val c = WorkspaceMappings.copy(resolved.workspaces[1], resolved.workspaces[0], withButtons = true)
        assertEquals(resolved.workspaces[0].touchControls, c.workspace.touchControls)
        assertEquals(resolved.workspaces[0].controls().toSet(), c.workspace.controls().toSet())
        assertEquals("ws2", c.workspace.id)
        assertTrue(c.unmatched.isEmpty())
    }

    @Test fun copyMappingsOnlyMatchesButtonsByName() {
        val resolved = live()
        val c = WorkspaceMappings.copy(resolved.workspaces[1], resolved.workspaces[0], withButtons = false)
        // ws2's btn1 is "Fire", ws1's "Fire" is btn2 (mapped to B); "Crouch" has no match and keeps X.
        assertEquals(resolved.workspaces[1].touchControls, c.workspace.touchControls)
        assertEquals(setOf(touch("btn1", b), touch("btn9", x)), c.workspace.controls().toSet())
        assertEquals(listOf("btn9"), c.unmatched.map { it.id })
    }

    @Test fun libraryButtonsKeepTheirMappingsAndLayers() {
        val w = ws1.copy(
            mappings = ws1.mappings + touch("btn1", b, set = 2),
            sets = listOf(MappingSet(2, "Aim")),
        )
        val lib = ButtonLibrary.save(w, w.touchControls[0], emptyList())
        assertEquals("lib1", lib.id)
        assertEquals("Jump", ButtonLibrary.name(lib))
        val added = ButtonLibrary.addTo(ws2, lib, "btn10") { it }
        assertEquals("btn10", added.touchControls.last().id)
        assertEquals(setOf(touch("btn10", a), touch("btn10", b, set = 2)), added.mappings.filter {
            MappingProfiles.touchId(it.source) == "btn10"
        }.toSet())
        assertEquals(listOf(MappingSet(2, "Aim")), added.sets)
    }
}
