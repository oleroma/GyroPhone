package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.DevicePresets
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InertiaCurve
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.WorkspaceSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick paths of the workspace defaults and of resolving (one JSON pass per gyro settings,
 * whole-workspace checks, reusing resolved workspaces) give what the key-by-key way gave.
 */
class SettingsFastPathTest {
    private val defaults = WorkspaceDefaults.builtin.copy(
        longPressMs = 640,
        gyro = GyroSettings(
            mode = GyroMode.ALWAYS, sensitivity = 22f, pitchScale = 0.8f, inertia = true, glideMs = 200f,
            yawCurve = InertiaCurve.SMOOTH, accel = GyroCurve.OFF, padYaw = MotionAxis.YAW, gameName = "Game",
        ),
        touchpad = TouchpadSettings(sensitivity = 2.5f),
    )

    private val workspaces = listOf(
        Workspace("a", "A", ownSettings = emptySet()),
        Workspace("b", "B", gyro = GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE, yawSensitivity = 9f), ownSettings = setOf("gyro.mode", "gyro.yawSensitivity")),
        Workspace("c", "C", doubleTapMs = 210, gyro = GyroSettings(rollScale = 1.5f, tiltRangeDeg = 40f)),
        Workspace("d", "D", gyro = GyroSettings(sensitivity = 5f), ownSettings = setOf("gyro.sensitivity", WorkspaceDefaults.LONG_PRESS)),
    )

    private val old = OldWorkspaceDefaults

    /** Workspaces old and new, owning little or much, with and without layer settings. */
    private fun samples(): List<Workspace> {
        val builtin = WorkspaceMappings.resolve(WorkspaceSet.merge(BuiltinProfiles.default, emptyList())).workspaces
        val layered = workspaces[1].copy(
            sets = listOf(
                MappingSet(1, settings = WorkspaceDefaults.builtin.copy(gyro = GyroSettings(sensitivity = 3f, yawScale = 2f), ownSettings = setOf("gyro.sensitivity", "gyro.yawScale"))),
                MappingSet(2, settings = WorkspaceDefaults.builtin.copy(longPressMs = 50, touchpad = TouchpadSettings(sensitivity = 9f), ownSettings = setOf(WorkspaceDefaults.LONG_PRESS, WorkspaceDefaults.touchpadKey(com.oleroma.gyrophone.core.model.SurfaceField.entries.first())))),
            ),
        )
        return workspaces + builtin + layered
    }

    @Test
    fun everyKeyGivesWhatTheRoundTripGave() {
        for (w in samples()) for (k in WorkspaceDefaults.keys) {
            assertEquals(k, old.take(k, w, defaults), WorkspaceDefaults.take(k, w, defaults))
            assertEquals(k, old.take(k, defaults, w), WorkspaceDefaults.take(k, defaults, w))
            assertEquals(k, old.same(k, w, defaults), WorkspaceDefaults.same(k, w, defaults))
            assertEquals(k, old.reset(w, k, defaults), WorkspaceDefaults.reset(w, k, defaults))
        }
    }

    @Test
    fun wholeWorkspacePassesGiveWhatTheyGave() {
        for (w in samples()) {
            assertEquals(w.id, old.apply(w, defaults), WorkspaceDefaults.apply(w, defaults))
            assertEquals(w.id, old.resetAll(w, defaults), WorkspaceDefaults.resetAll(w, defaults))
            assertEquals(w.id, old.differing(w, defaults), WorkspaceDefaults.differing(w, defaults))
            assertEquals(w.id, old.own(w), WorkspaceDefaults.own(w))
            for (s in w.sets) {
                assertEquals(w.id, old.overlay(w, s), WorkspaceDefaults.overlay(w, s))
                val after = w.copy(gyro = w.gyro.copy(sensitivity = 40f, yawMode = GyroMode.ALWAYS), longPressMs = 300)
                assertEquals(w.id, old.editLayer(s, w, after), WorkspaceDefaults.editLayer(s, w, after))
            }
            assertEquals(w.id, old.layered(w, 1, listOf(2)), WorkspaceDefaults.layered(w, 1, listOf(2)))
            val edits = listOf<(Workspace) -> Workspace>(
                { it.copy(longPressMs = 77) },
                { it.copy(gyro = it.gyro.copy(glideMs = 90f, pitchScale = 1.2f)) },
                { it.copy(touchpad = TouchpadSettings(sensitivity = 7f)) },
                { it.copy(name = "Moved") },
            )
            for (e in edits) assertEquals(w.id, old.markOwn(w, e(w)), WorkspaceDefaults.markOwn(w, e(w)))
        }
    }

    @Test
    fun applyLeavesMatchingGyroSettingsAsTheyAre() {
        val w = Workspace("a", "A", gyro = defaults.gyro, ownSettings = emptySet())
        assertSame(defaults.gyro, WorkspaceDefaults.apply(w, defaults).gyro)
    }

    @Test
    fun layerSettingsGiveWhatKeyByKeyGave() {
        val base = WorkspaceDefaults.apply(workspaces[1], defaults)
        val after = base.copy(gyro = base.gyro.copy(sensitivity = 40f, yawMode = GyroMode.ALWAYS), longPressMs = 300)
        val set = WorkspaceDefaults.editLayer(MappingSet(1), base, after)
        assertEquals(setOf("gyro.sensitivity", "gyro.yawMode", WorkspaceDefaults.LONG_PRESS), set.settings!!.ownSettings)
        val layer = WorkspaceDefaults.keys.fold(Workspace("", "")) { r, k -> old.take(k, r, after) }
        assertEquals(layer.gyro, set.settings!!.gyro)
        val over = WorkspaceDefaults.overlay(base, set)
        assertEquals(40f, over.gyro.sensitivity)
        assertEquals(GyroMode.ALWAYS, over.gyro.yawMode)
        assertEquals(300, over.longPressMs)
        // Its own sensitivity hides the workspace's own yaw value: yaw follows the shared one.
        assertEquals(null, over.gyro.yawSensitivity)
        assertEquals(base.gyro.mode, over.gyro.mode)
    }

    @Test
    fun markOwnReturnsAtOnceWhenOnlyTheLayoutChanged() {
        val resolved = WorkspaceMappings.resolve(Profile("p", "P", workspaces, defaults = defaults))
        val w = resolved.workspaces[0]
        val moved = w.copy(touchControls = w.touchControls + com.oleroma.gyrophone.core.model.TouchControl("x", com.oleroma.gyrophone.core.model.TouchKind.BUTTON, "", 0.2f, 0.3f))
        assertSame(moved, WorkspaceDefaults.markOwn(w, moved))
        // A setting changed still becomes the workspace's own.
        val changed = WorkspaceDefaults.markOwn(w, moved.copy(gyro = moved.gyro.copy(glideMs = 90f)))
        assertEquals(setOf("gyro.glideMs"), changed.ownSettings)
    }

    @Test
    fun markOwnOfAProfileLeavesUntouchedWorkspacesAlone() {
        val resolved = WorkspaceMappings.resolve(Profile("p", "P", workspaces, defaults = defaults))
        val edited = resolved.updateWorkspace(2) { it.copy(longPressMs = 111) }
        val marked = WorkspaceDefaults.markOwn(resolved, edited)
        for (i in resolved.workspaces.indices) if (i != 2) assertSame(resolved.workspaces[i], marked.workspaces[i])
        assertTrue(WorkspaceDefaults.LONG_PRESS in marked.workspaces[2].ownSettings!!)
    }

    @Test
    fun resolveReusesOnlyWhatDidNotChange() {
        val stored = WorkspaceMappings.store(WorkspaceSet.merge(BuiltinProfiles.default, emptyList())).let { s ->
            s.copy(defaults = defaults, workspaces = s.workspaces + workspaces)
        }
        val first = WorkspaceMappings.resolve(stored)
        assertEquals(first, WorkspaceMappings.resolve(stored, null, null))

        // One workspace edited: the rest are the very ones resolved before.
        val edited = stored.copy(workspaces = stored.workspaces.mapIndexed { i, w -> if (i == 1) w.copy(longPressMs = 123, ownSettings = setOf(WorkspaceDefaults.LONG_PRESS)) else w })
        val again = WorkspaceMappings.resolve(edited, stored, first)
        assertEquals(WorkspaceMappings.resolve(edited), again)
        for (i in again.workspaces.indices) if (i != 1) assertSame(first.workspaces[i], again.workspaces[i])

        // Defaults changed: everything is worked out again.
        val newDefaults = edited.copy(defaults = defaults.copy(longPressMs = 999))
        assertEquals(WorkspaceMappings.resolve(newDefaults), WorkspaceMappings.resolve(newDefaults, edited, again))

        // A preset changed, a workspace added, one removed, the order changed.
        val withPreset = DevicePresets.put(edited, DevicePresets.standard("px", "PX"))
        assertEquals(WorkspaceMappings.resolve(withPreset), WorkspaceMappings.resolve(withPreset, edited, again))
        val reordered = edited.copy(workspaces = (edited.workspaces.drop(1) + Workspace("new", "New")).reversed())
        assertEquals(WorkspaceMappings.resolve(reordered), WorkspaceMappings.resolve(reordered, edited, again))
    }

    @Test
    fun swipeInputsSameOnlyWhileMatchingHasNothingNew() {
        val w = WorkspaceMappings.resolve(Profile("p", "P", BuiltinProfiles.default.workspaces.take(1), defaults = defaults)).workspaces[0]
        val c = w.touchControls.first { it.kind == com.oleroma.gyrophone.core.model.TouchKind.BUTTON }
        val moved = w.copy(touchControls = w.touchControls.map { if (it.id == c.id) it.copy(x = it.x + 0.01f) else it })
        assertTrue(Bindings.sameSwipeInputs(w, moved))
        assertEquals(Bindings.matchSwipeModes(moved), moved)
        val swiping = w.copy(touchControls = w.touchControls.map { if (it.id == c.id) it.copy(swipeTapOnRelease = true) else it })
        assertFalse(Bindings.sameSwipeInputs(w, swiping))
        assertFalse(Bindings.sameSwipeInputs(w, w.copy(mappings = w.mappings.drop(1))))
    }
}
