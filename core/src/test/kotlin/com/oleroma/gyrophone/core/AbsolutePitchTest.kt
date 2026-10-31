package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.AbsolutePitch
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Absolute pitch: phone pitch against gravity sets the camera's, re-synced at the poles and by Set level. */
class AbsolutePitchTest {
    /** 10 counts per camera degree, limit 89°, 1:1. */
    private val cpd = 10f
    private val limit = 89f

    /** Holds the phone at [deg] (up +) and lets the pose settle on gravity. */
    private fun AbsolutePitch.hold(deg: Float) {
        val a = Math.toRadians(deg.toDouble())
        // World up in the phone's frame (right, up, toward the player): pitched up, up leans away from the player.
        setUp(0f, cos(a).toFloat(), -sin(a).toFloat(), 1f)
        rotate(0f, 0f, 0f, 10f)
    }

    private fun AbsolutePitch.at(deg: Float, gain: Float = 1f, lim: Float = limit): Int {
        hold(deg)
        return step(gain, cpd, lim)
    }

    @Test fun thePoseFollowsGravity() {
        val a = AbsolutePitch()
        a.hold(30f)
        assertEquals(30f, a.pitchDeg, 0.1f)
        a.hold(-45f)
        assertEquals(-45f, a.pitchDeg, 0.1f)
    }

    @Test fun nothingMovesOnStartThenTheCameraFollowsThePhone() {
        val a = AbsolutePitch()
        assertEquals(0, a.at(20f))
        assertEquals(20f, a.cameraDeg, 0.01f)
        // Up 10°: 100 counts up, which is HID -100 (down +).
        assertEquals(-100, a.at(30f))
        assertEquals(50, a.at(25f))
    }

    @Test fun gainScalesThePhonesTilt() {
        val a = AbsolutePitch()
        assertEquals(0, a.at(0f, gain = 2f))
        assertEquals(-200, a.at(10f, gain = 2f))
        assertEquals(20f, a.cameraDeg, 0.1f)
    }

    @Test fun pastThePoleTheCameraIsPushedOnceThenReSyncedThere() {
        // Limit 60°, so the phone (which reads at most 90° up) can go past it.
        val a = AbsolutePitch()
        a.at(50f, lim = 60f)
        val push = (AbsolutePitch.PUSH_DEG * cpd).toInt()
        // Whatever the game did with earlier counts, the camera ends pinned at its pole.
        assertEquals(-(100 + push), a.at(70f, lim = 60f))
        assertEquals(60f, a.cameraDeg, 0.01f)
        // Still past: no more pushing.
        assertEquals(0, a.at(80f, lim = 60f))
        // Back below the limit: from the pole exactly.
        assertEquals(200, a.at(40f, lim = 60f))
        assertEquals(40f, a.cameraDeg, 0.1f)
    }

    @Test fun thePushIsSentAgainAfterComingBack() {
        val a = AbsolutePitch()
        a.at(-50f, lim = 60f)
        val push = (AbsolutePitch.PUSH_DEG * cpd).toInt()
        assertEquals(100 + push, a.at(-70f, lim = 60f))
        assertEquals(-200, a.at(-40f, lim = 60f))
        assertEquals(200 + push, a.at(-70f, lim = 60f))
    }

    @Test fun setLevelSnapsTheCameraToThePhone() {
        val a = AbsolutePitch()
        a.at(5f) // drifted: the camera looks level while the phone is at 5°
        a.setLevel()
        assertEquals(-50, a.at(5f))
        assertEquals(5f, a.cameraDeg, 0.1f)
    }

    @Test fun resetForgetsTheCamera() {
        val a = AbsolutePitch()
        a.at(10f)
        a.reset()
        assertFalse(a.running)
        assertEquals(0, a.at(40f))
        assertTrue(a.running)
    }

    @Test fun absoluteYIsTheOnlyMouseYAndSetLevelCounts() {
        val e = MappingEngine(single(Workspace(
            "w", "w",
            touchControls = listOf(TouchControl("lvl", TouchKind.BUTTON, "lvl", 0.5f, 0.5f)),
            mappings = listOf(Mapping(InputSource.Touch("lvl"), OutputTarget.SetLevel)),
        )))
        e.addMouseMotion(3, 7)
        assertEquals(7, e.snapshot().mouse.dy)
        e.absoluteY = true
        e.addMouseMotion(3, 7)
        e.addAbsoluteY(-12)
        val m = e.snapshot().mouse
        assertEquals(3, m.dx)
        assertEquals(-12, m.dy)
        val before = e.levelPresses
        e.setTouch("lvl", true)
        e.snapshot()
        e.setTouch("lvl", false)
        e.snapshot()
        assertEquals(before + 1, e.levelPresses)
    }

    @Test fun absolutePitchIsTheWorkspacesOnlyNotALayers() {
        assertTrue(WorkspaceDefaults.gyroKey("absolutePitch") in WorkspaceDefaults.keys)
        assertFalse(WorkspaceDefaults.gyroKey("absolutePitch") in WorkspaceDefaults.layerKeys)
        assertFalse(WorkspaceDefaults.gyroKey("pitchLimitDeg") in WorkspaceDefaults.layerKeys)
        assertEquals(89f, GyroSettings().pitchLimitDeg)
    }
}
