package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.SurfaceField
import com.oleroma.gyrophone.core.model.MoveField
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Move X / Y settings of their own over the shared ones, down the levels. */
class MoveSettingsTest {
    private val defaults = WorkspaceDefaults.builtin.copy(
        touchpad = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.Y2) { it.copy(scrollSpeed = 2.5f) },
    )

    @Test fun aWorkspaceTakesTheDefaultsMoveSettings() {
        val w = WorkspaceDefaults.apply(Workspace("w", "w", ownSettings = emptySet()), defaults)
        assertEquals(2.5f, w.touchpad.feel(2).scrollSpeedY)
        assertEquals(2f, w.touchpad.feel(1).sensitivityY)
    }

    @Test fun aSharedValueSetHereHidesTheDefaultsMoveValues() {
        val key = WorkspaceDefaults.touchpadKey(SurfaceField.SCROLL_SPEED)
        val own = Workspace("w", "w", touchpad = TouchpadSettings(scrollSpeed = 1.2f), ownSettings = setOf(key))
        val w = WorkspaceDefaults.apply(own, defaults)
        assertEquals(1.2f, w.touchpad.feel(2).scrollSpeedY)
    }

    @Test fun aMoveSetsItsOwnOverTheDefaults() {
        val key = WorkspaceDefaults.moveKey(MoveSlot.X1, MoveField.SENSITIVITY)
        assertTrue(key in WorkspaceDefaults.keys)
        val before = WorkspaceDefaults.apply(Workspace("w", "w", ownSettings = emptySet()), defaults)
        val after = before.copy(touchpad = before.touchpad.withMove(MoveSlot.X1) { it.copy(sensitivity = 4f) })
        val marked = WorkspaceDefaults.markOwn(before, after)
        assertTrue(key in WorkspaceDefaults.own(marked))
        assertEquals(4f, marked.touchpad.feel(1).sensitivityX)
        assertEquals(2f, WorkspaceDefaults.reset(marked, key, defaults).touchpad.feel(1).sensitivityX)
    }
}
