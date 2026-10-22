package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceSet
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSetTest {
    @Test fun mergeKeepsActiveFirstAndEveryOtherWorkspace() {
        val active = Profile("user.a", "Shooter", listOf(Workspace("ws1", "Main"), Workspace("ws2", "Map")), wrapWorkspaces = true)
        val other = Profile("user.b", "Racing", listOf(Workspace("ws1", "Drive")))
        val merged = WorkspaceSet.merge(active, listOf(other))
        assertEquals(WorkspaceSet.ID, merged.id)
        assertEquals(listOf("Main", "Map", "Drive · Racing"), merged.workspaces.map { it.name })
        assertEquals(3, merged.workspaces.map { it.id }.toSet().size)
        assertEquals(true, merged.wrapWorkspaces)
        assertEquals(active.workspaces, merged.workspaces.take(2))
    }

    @Test fun aBuiltinActiveProfileBecomesTheSet() {
        val merged = WorkspaceSet.merge(BuiltinProfiles.default, emptyList())
        assertEquals(BuiltinProfiles.default.workspaces, merged.workspaces)
        assertEquals(WorkspaceSet.NAME, merged.name)
    }
}
