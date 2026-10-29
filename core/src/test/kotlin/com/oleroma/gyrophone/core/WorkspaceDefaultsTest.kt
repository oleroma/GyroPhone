package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.DirField
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.KeyTiming
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.SurfaceField
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDefaultsTest {
    private val defaults = WorkspaceDefaults.builtin.copy(
        longPressMs = 600,
        gyro = GyroSettings(sensitivity = 30f),
        touchpad = TouchpadSettings(sensitivity = 3f),
    )

    @Test
    fun oldWorkspaceKeepsWhatDiffersFromBuiltin() {
        val w = Workspace("a", "A", doubleTapMs = 250, gyro = GyroSettings(mode = GyroMode.ALWAYS))
        val r = WorkspaceDefaults.apply(w, defaults)
        assertEquals(setOf(WorkspaceDefaults.DOUBLE_TAP, WorkspaceDefaults.gyroKey("mode")), r.ownSettings)
        assertEquals(250, r.doubleTapMs)
        assertEquals(GyroMode.ALWAYS, r.gyro.mode)
        // The rest follows the defaults.
        assertEquals(600, r.longPressMs)
        assertEquals(30f, r.gyro.sensitivity)
        assertEquals(3f, r.touchpad.sensitivity)
    }

    @Test
    fun editMarksOwnAndResetFollowsAgain() {
        val set = Profile("p", "P", listOf(Workspace("a", "A", ownSettings = emptySet())), defaults = defaults)
        val resolved = WorkspaceMappings.resolve(set)
        assertEquals(600, resolved.workspaces[0].longPressMs)

        val edited = WorkspaceDefaults.markOwn(resolved, resolved.updateWorkspace(0) { it.copy(longPressMs = 300) })
        assertEquals(setOf(WorkspaceDefaults.LONG_PRESS), edited.workspaces[0].ownSettings)
        // A new default no longer reaches it.
        val again = WorkspaceMappings.resolve(edited.copy(defaults = defaults.copy(longPressMs = 900)))
        assertEquals(300, again.workspaces[0].longPressMs)

        val reset = WorkspaceDefaults.markOwn(again, again.updateWorkspace(0) { WorkspaceDefaults.reset(it, WorkspaceDefaults.LONG_PRESS, again.defaults) })
        assertEquals(emptySet<String>(), reset.workspaces[0].ownSettings)
        assertEquals(900, WorkspaceMappings.resolve(reset).workspaces[0].longPressMs)
    }

    @Test
    fun ownSettingsSurviveJson() {
        val set = Profile("p", "P", listOf(Workspace("a", "A", ownSettings = setOf(WorkspaceDefaults.HAPTICS))), defaults = defaults)
        val back = ProfileJson.json.decodeFromString(Profile.serializer(), ProfileJson.json.encodeToString(Profile.serializer(), set))
        assertEquals(set, back)
    }

    @Test
    fun buttonSurfaceOwnsOnlyWhatChanged() {
        val pad = TouchpadSettings(holdDp = 30f)
        val own = SurfaceSettings()
        val was = pad.with(own)
        val now = was.copy(swipeDp = 55f, pies = List(4) { 120f })
        val edited = SurfaceField.edited(own, was, now)
        assertEquals(55f, edited.swipeDp)
        assertEquals(List(4) { 120f }, edited.pies)
        assertNull(edited.holdDp)
        assertTrue(SurfaceField.PIES.isOwn(edited))
        assertFalse(SurfaceField.HOLD.isOwn(edited))

        // Back to classic pies over a touchpad with its own pies: the button keeps classic.
        val classic = SurfaceField.edited(edited, pad.copy(pies = List(4) { 60f }).with(edited), pad.with(edited).copy(pies = null))
        assertNull(pad.copy(pies = List(4) { 60f }).with(classic).pies)
        assertEquals(HoldMode.FROM_START, pad.with(SurfaceField.dropAll(classic)).holdMode)
    }

    @Test
    fun keyTimingsComeFromThePinnedPreset() {
        val preset = DevicePreset("d", "D").withTiming(96) { it.copy(longPressMs = 700) }
        assertEquals(listOf(KeyTiming(96, longPressMs = 700)), preset.timings)
        val set = Profile("p", "P", listOf(Workspace("a", "A", devicePreset = "d")), devicePresets = listOf(preset))
        val resolved = WorkspaceMappings.resolve(set)
        assertEquals(preset.timings, resolved.workspaces[0].keyTimings)
        assertEquals(emptyList<KeyTiming>(), WorkspaceMappings.store(resolved).workspaces[0].keyTimings)
        // No own time left: the key is dropped.
        assertEquals(emptyList<KeyTiming>(), preset.withTiming(96) { it.copy(longPressMs = null) }.timings)
    }

    @Test
    fun sharedValueHidesTheDefaultsDirectionValueUntilReset() {
        val d = defaults.copy(touchpad = TouchpadSettings(swipeDp = 20f).withDir(Direction.UP) { it.copy(swipeDp = 60f) })
        val ws = WorkspaceDefaults.apply(Workspace("a", "A", ownSettings = emptySet()), d)
        assertEquals(60f, ws.touchpad.swipeDpOf(Direction.UP))

        // The workspace sets the shared swipe distance: Up uses it too.
        val edited = WorkspaceDefaults.markOwn(ws, ws.copy(touchpad = ws.touchpad.copy(swipeDp = 30f)))
        assertEquals(30f, edited.touchpad.swipeDpOf(Direction.UP))
        assertEquals(30f, WorkspaceDefaults.apply(edited, d).touchpad.swipeDpOf(Direction.UP))

        // Its own Up distance wins over its shared one.
        val up = WorkspaceDefaults.markOwn(edited, edited.copy(touchpad = edited.touchpad.withDir(Direction.UP) { it.copy(swipeDp = 45f) }))
        assertTrue(WorkspaceDefaults.dirKey(Direction.UP, DirField.SWIPE) in up.ownSettings!!)
        assertEquals(45f, WorkspaceDefaults.apply(up, d).touchpad.swipeDpOf(Direction.UP))

        // Back to the defaults' shared value: their Up value comes back.
        val reset = WorkspaceDefaults.reset(edited, WorkspaceDefaults.touchpadKey(SurfaceField.SWIPE), d)
        assertEquals(60f, reset.touchpad.swipeDpOf(Direction.UP))
        assertEquals(20f, reset.touchpad.swipeDpOf(Direction.LEFT))
    }
}
