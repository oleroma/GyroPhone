package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerSettingsTest {
    private val base = Workspace(
        "w", "w",
        gyro = GyroSettings(mode = GyroMode.OFF, sensitivity = 20f),
        touchpad = TouchpadSettings(sensitivity = 2f),
        longPressMs = 400,
        sets = listOf(MappingSet(1, "Aim"), MappingSet(2, "Scroll")),
    )

    /** [w] with layer [id]'s settings edited by [change], as the settings page does. */
    private fun edit(w: Workspace, id: Int, change: (Workspace) -> Workspace): Workspace =
        w.copy(sets = w.sets.map { s ->
            if (s.id != id) s else {
                val before = WorkspaceDefaults.overlay(w, s)
                WorkspaceDefaults.editLayer(s, before, change(before))
            }
        })

    @Test
    fun aLayerSetsOnlyWhatWasChangedAndFollowsBaseForTheRest() {
        val w = edit(base, 1) { it.copy(gyro = it.gyro.copy(mode = GyroMode.ALWAYS, sensitivity = 8f)) }
        val aim = w.sets.first { it.id == 1 }
        assertEquals(setOf(WorkspaceDefaults.gyroKey("mode"), WorkspaceDefaults.gyroKey("sensitivity")), WorkspaceDefaults.layerOwn(aim))

        // Base changes later still reach what the layer does not set.
        val later = w.copy(touchpad = w.touchpad.copy(sensitivity = 5f), gyro = w.gyro.copy(sensitivity = 30f))
        val inAim = WorkspaceDefaults.layered(later, 1, emptyList())
        assertEquals(GyroMode.ALWAYS, inAim.gyro.mode)
        assertEquals(8f, inAim.gyro.sensitivity)
        assertEquals(5f, inAim.touchpad.sensitivity)
        assertEquals(30f, WorkspaceDefaults.layered(later, 0, emptyList()).gyro.sensitivity)
    }

    @Test
    fun overrideLayersStackTopmostWins() {
        var w = edit(base, 1) { it.copy(longPressMs = 600, touchpad = it.touchpad.copy(sensitivity = 3f)) }
        w = edit(w, 2) { it.copy(longPressMs = 900) }
        val both = WorkspaceDefaults.layered(w, 0, listOf(1, 2))
        assertEquals(900, both.longPressMs)
        assertEquals(3f, both.touchpad.sensitivity)
        assertEquals(600, WorkspaceDefaults.layered(w, 0, listOf(2, 1)).longPressMs)
    }

    @Test
    fun resetFollowsBaseAgainAndClearsAnEmptyLayer() {
        val w = edit(base, 1) { it.copy(longPressMs = 600) }
        val aim = WorkspaceDefaults.resetLayer(w.sets.first { it.id == 1 }, WorkspaceDefaults.LONG_PRESS)
        assertNull(aim.settings)
        assertEquals(400, WorkspaceDefaults.overlay(base, aim).longPressMs)
    }

    @Test
    fun theGyroFiltersAreNeverALayers() {
        val w = edit(base, 1) { it.copy(gyro = it.gyro.copy(smoothingThreshold = 3f)) }
        assertNull(w.sets.first { it.id == 1 }.settings)
    }

    @Test
    fun layerSettingsSurviveSaving() {
        val w = edit(base, 1) { it.copy(gyro = it.gyro.copy(mode = GyroMode.ALWAYS)) }
        val back = ProfileJson.decode(ProfileJson.encode(single(w))).workspaces[0]
        assertEquals(GyroMode.ALWAYS, WorkspaceDefaults.layered(back, 1, emptyList()).gyro.mode)
    }

    @Test
    fun theEngineUsesTheSettingsOfTheLayerInUse() {
        val w = edit(base, 1) { it.copy(gyro = it.gyro.copy(mode = GyroMode.ALWAYS, sensitivity = 8f)) }.copy(
            mappings = listOf(Mapping(InputSource.Touch("aim"), OutputTarget.Layer(1, whileHeld = true))),
        )
        val e = MappingEngine(single(w))
        assertFalse(e.gyroActive)
        assertEquals(20f, e.gyroSettings.sensitivity)
        e.setTouch("aim", true)
        assertTrue(e.gyroActive)
        assertEquals(8f, e.gyroSettings.sensitivity)
        e.setTouch("aim", false)
        assertFalse(e.gyroActive)
        assertEquals(20f, e.gyroSettings.sensitivity)
    }
}
