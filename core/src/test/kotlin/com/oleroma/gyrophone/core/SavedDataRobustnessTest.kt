package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.DirSettings
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.GlobalControls
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.GyroSpace
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.LibraryButton
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MoveSettings
import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.NamedSens
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.ProfileExport
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.SliderOverride
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved data from other builds or hand edits that this build must still read and run with. */
class SavedDataRobustnessTest {
    @Test fun moreDirectionAndMoveEntriesThanThisBuildKnowsAreIgnored() {
        val s = TouchpadSettings(
            dirs = List(Direction.entries.size + 1) { DirSettings(swipeDp = 10f + it) },
            moves = List(MoveSlot.entries.size + 2) { MoveSettings(sensitivity = 2f) },
        )
        val w = s.with(SurfaceSettings())
        assertEquals(13f, w.swipeDpOf(Direction.LEFT), 0f)
        assertEquals(Direction.entries.size, w.dirs!!.size)
        assertEquals(MoveSlot.entries.size, w.moves!!.size)
    }

    @Test fun anUnknownItemInEveryKindOfListLeavesOnlyThatItemOut() {
        val w = Workspace(
            id = "w", name = "w",
            phone = listOf(Mapping(InputSource.Key(24), OutputTarget.GyroSens(named = 1)), Mapping(InputSource.Key(25), OutputTarget.Key(5))),
            sliders = listOf(SliderOverride(OutputTarget.GyroSens(), 2f)),
        )
        val text = ProfileJson.encode(single(w))
        // A newer build's target in the volume keys' list and in a slider.
        val newer = text.replace("\"type\": \"gyro_sens\"", "\"type\": \"newer_target\"")
        assertNotEquals(text, newer)
        assertTrue(runCatching { ProfileJson.decode(newer) }.isFailure)
        val read = ProfileJson.decodeLenient(newer)!!
        assertEquals(2, read.dropped)
        val back = read.profile.workspaces.single()
        assertEquals(listOf(w.phone[1]), back.phone)
        assertTrue(back.sliders.isEmpty())
    }

    @Test fun anUnreadableLibraryButtonIsLeftOutNotTheLibrary() {
        val c = TouchControl("a", TouchKind.BUTTON, "A", 0.5f, 0.5f)
        val ok = LibraryButton("lib1", c, listOf(Mapping(InputSource.Touch("a"), OutputTarget.Key(4))))
        val bad = LibraryButton("lib2", c, listOf(Mapping(InputSource.Touch("a"), OutputTarget.GyroSens())))
        val text = ButtonLibrary.encode(listOf(ok, bad)).replace("\"type\": \"gyro_sens\"", "\"type\": \"newer_target\"")
        // Whole: unreadable, so the file is never saved over.
        assertNull(ButtonLibrary.decode(text))
        assertEquals(listOf(ok), ButtonLibrary.decodeLenient(text))
        assertNull(ButtonLibrary.decodeLenient("{}"))
        // An everything export from a newer build keeps the library buttons this build can read.
        val everything = ProfileExport.everything(single(Workspace("w", "W")), listOf(ok, bad), emptyList())
            .replace("\"type\": \"gyro_sens\"", "\"type\": \"newer_target\"")
        assertEquals(listOf(ok), ProfileExport.read(everything).library)
    }

    @Test fun anImportedWorkspacesGlobalControlsBecomeItsOwn() {
        val stored = WorkspaceMappings.store(BuiltinProfiles.newProfile("p", "P"))
        val wasd = stored.workspaces.indexOfFirst { it.name == "WASD" }
        val src = stored.workspaces[wasd]
        val id = src.touchControls.first().id
        val withGlobal = stored.copy(workspaces = stored.workspaces.mapIndexed { i, w ->
            if (i == wasd) w.copy(touchControls = w.touchControls.map { if (it.id == id) it.copy(global = true) else it }) else w
        })
        val r = ProfileExport.read(ProfileExport.workspace(withGlobal, wasd))
        val after = ProfileExport.addWorkspace(stored, r)
        assertTrue(after.workspaces.last().touchControls.none { it.global })
        // The next edit's sync spreads nothing into the workspaces that were there.
        assertEquals(after.workspaces, GlobalControls.sync(after, after).workspaces)
    }

    @Test fun aLayersCopyOfASpaceItDoesNotOwnNeedsNoAccelerometer() {
        val spaced = GyroSettings(gyroSpace = GyroSpace.WORLD, yawSpace = GyroSpace.WORLD)
        fun layer(own: Set<String>) = Workspace(
            "w", "W",
            sets = listOf(MappingSet(1, settings = Workspace("", "", gyro = spaced, ownSettings = own))),
        )
        assertFalse(layer(setOf(WorkspaceDefaults.gyroKey("sensitivity"))).gyroSpaced)
        assertTrue(layer(setOf(WorkspaceDefaults.gyroKey("yawSpace"))).gyroSpaced)
    }

    @Test fun aDeletedNamedSensitivitysIdIsNeverGivenAgain() {
        var w = Workspace("w", "W")
        val (a, one) = NamedSens.add(w, touch = false)
        val (b, two) = NamedSens.add(a, touch = false)
        w = NamedSens.delete(b, two)
        val (_, three) = NamedSens.add(w, touch = false)
        assertEquals(1, one)
        assertEquals(2, two)
        assertEquals(3, three)
        // A workspace saved before the counter still counts on from its highest id.
        assertEquals(2, NamedSens.add(Workspace("w", "W", namedSens = listOf(NamedSens(1, "Sens 1"))), false).second)
        // Deleting its highest id before any add here still keeps that id from coming back.
        val old = Workspace("w", "W", namedSens = listOf(NamedSens(1, "Sens 1"), NamedSens(2, "Sens 2")))
        assertEquals(3, NamedSens.add(NamedSens.delete(old, 2), false).second)
    }

    @Test fun unsetHoldZonesShareOneDefault() {
        val s = TouchpadSettings()
        assertSame(HoldZone.DEFAULT, s.holdZone(Direction.UP, 2))
        assertSame(HoldZone.DEFAULT, s.holdZoneAt(4, 1))
    }
}
