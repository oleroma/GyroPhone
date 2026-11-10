package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.ProfileExport
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileExportTest {
    private val stored = WorkspaceMappings.store(BuiltinProfiles.newProfile("p", "P"))

    @Test fun workspaceFileComesBackAsANewWorkspace() {
        val wasd = stored.workspaces.indexOfFirst { it.name == "WASD" }
        val text = ProfileExport.workspace(stored, wasd)
        assertTrue(text.contains("\"format\": \"gyrophone\""))
        val r = ProfileExport.read(text)
        assertEquals(ProfileExport.WORKSPACE, r.kind)
        val after = ProfileExport.addWorkspace(stored, r)
        assertEquals(stored.workspaces.size + 1, after.workspaces.size)
        val added = after.workspaces.last()
        assertEquals("WASD 2", added.name)
        assertFalse(added.id in stored.workspaces.map { it.id })
        assertEquals(stored.workspaces[wasd].touchControls, added.touchControls)
        // Its controller preset came along, under an id of its own.
        assertNotEquals(null, added.devicePreset)
        assertEquals(stored.devicePresets.size + 1, after.devicePresets.size)
        // Same Defaults on both sides: nothing became its own.
        assertEquals(WorkspaceDefaults.own(stored.workspaces[wasd]), WorkspaceDefaults.own(added))
        assertEquals(stored.workspaces[wasd].mappings.toSet(), added.mappings.toSet())
    }

    @Test fun defaultsThatDifferAreKeptAsItsOwn() {
        val text = ProfileExport.workspace(stored, 0)
        val r = ProfileExport.read(text)
        // The other phone's Defaults: a longer long press, and gyro yaw on the mouse instead.
        val defaults = stored.defaults.copy(
            longPressMs = stored.defaults.longPressMs + 200,
            mappings = listOf(Mapping(InputSource.Gyro(MotionAxis.YAW), OutputTarget.MouseAxis(MouseMotion.MOVE_X))),
        )
        val other = stored.copy(defaults = defaults)
        val added = ProfileExport.addWorkspace(other, r).workspaces.last()
        assertTrue(WorkspaceDefaults.LONG_PRESS in WorkspaceDefaults.own(added))
        assertEquals(stored.workspaces[0].longPressMs, WorkspaceDefaults.apply(added, defaults).longPressMs)
        // Yaw did nothing where it came from: cleared, so it still does nothing.
        assertTrue(added.mappings.any { it.source == InputSource.Gyro(MotionAxis.YAW) && it.target == OutputTarget.NoOutput && it.set == MappingSet.BASE })
    }

    @Test fun everythingRoundTrips() {
        val r = ProfileExport.read(ProfileExport.everything(stored, emptyList(), emptyList()))
        assertEquals(ProfileExport.EVERYTHING, r.kind)
        assertEquals(stored, r.profile)
    }

    @Test fun otherFilesAreRefused() {
        for (t in listOf("hello", "{}", "{\"format\":\"gyrophone\",\"version\":99,\"kind\":\"workspace\"}")) {
            try {
                ProfileExport.read(t)
                fail(t)
            } catch (_: ProfileExport.NotAnExport) {
            }
        }
        assertEquals("a_b.gyrophone.json", ProfileExport.fileName("a/b"))
    }
}
