package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.surfaceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe-mode buttons as touch surfaces. */
class SwipeSurfaceTest {
    private val MS = 1_000_000L
    private val Q = 0x14
    private var t = 0L

    @Test
    fun twoFingerTapOnTheButton() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true, surface = SurfaceSettings())
        val w = Workspace(id = "w", name = "w", touchControls = listOf(b),
            mappings = listOf(Mapping(InputSource.Surface(2, SurfaceInput.TAP, "b"), OutputTarget.Key(Q))))
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        e.buttonDown(1, "b", 100f, 100f); e.buttonDown(2, "b", 140f, 100f)
        t += 60 * MS; e.snapshot()
        e.buttonUp(1); e.buttonUp(2)
        var seen = false
        repeat(10) { seen = seen || Q in e.snapshot().keyboard.keys; t += 5 * MS }
        assertTrue(seen)
    }

    @Test
    fun cursorDeadzoneWorksOnASwipeButton() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true,
            surface = SurfaceSettings(sensitivity = 1f, acceleration = 0f, cursorDeadzoneDp = 20f, catchUpMs = 0))
        val w = Workspace(id = "w", name = "w", touchControls = listOf(b), mappings = listOf(
            Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X, "b"), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
        ))
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f }
        e.buttonDown(1, "b", 100f, 100f)
        t += 10 * MS; e.buttonMove(1, 110f, 100f)
        assertEquals(0, e.snapshot().mouse.dx)
        t += 10 * MS; e.buttonMove(1, 125f, 100f)
        assertEquals(25, e.snapshot().mouse.dx)
    }

    @Test
    fun overlapOrderIsByShortestSide() {
        val big = TouchControl("big", TouchKind.BUTTON, "", 0.5f, 0.5f, size = 0.4f)
        val strip = TouchControl("strip", TouchKind.BUTTON, "", 0.5f, 0.5f, size = 1.2f, shape = com.oleroma.gyrophone.core.model.TouchShape.SQUARE, aspect = 0.1f)
        val back = TouchControl("back", TouchKind.BUTTON, "", 0.5f, 0.5f, size = 0.1f, layer = com.oleroma.gyrophone.core.model.TouchLayer.BACK)
        val order = com.oleroma.gyrophone.core.model.hitOrder(listOf(big, strip, back), false).map { it.id }
        assertEquals(listOf("strip", "big", "back"), order)
        val shared = com.oleroma.gyrophone.core.model.touchedControls(
            com.oleroma.gyrophone.core.model.hitOrder(listOf(big.copy(sharesTouches = true), strip), false), 500f, 250f, 1000f, 500f, false,
        ).map { it.id }
        assertEquals(listOf("strip", "big"), shared)
    }

    @Test
    fun settingsFollowTheTouchpadUnlessSet() {
        val pad = TouchpadSettings(sensitivity = 3f, scrollSpeed = 2f)
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true, surface = SurfaceSettings(scrollSpeed = 0.5f))
        val s = b.surfaceSettings(pad)
        assertEquals(3f, s.sensitivity, 0f)
        assertEquals(0.5f, s.scrollSpeed, 0f)
        // A button from before keeps its old swipe feel.
        val old = b.copy(surface = null, swipeSensX = 2f, swipeSensY = 1f).surfaceSettings(pad)
        assertEquals(2f, old.sensitivity, 0f)
        assertEquals(0f, old.acceleration, 0f)
    }

    @Test
    fun oldSwipeMappingsBecomeSurfaceInputs() {
        val old = listOf(
            Mapping(InputSource.TouchSwipe("b", StickAxis.X), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
            Mapping(InputSource.TouchSwipe("b", StickAxis.Y, AxisHalf.NEGATIVE), OutputTarget.Key(Q)),
        )
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, swipe = true)
        val set = Profile("s", "s", listOf(Workspace("w", "w", listOf(b), mappingProfileId = "m")))
        val resolved = MappingProfiles.resolve(set, listOf(MappingProfile("m", "m", touch = old)))
        val ms = resolved.workspaces[0].mappings
        assertTrue(Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X, "b"), OutputTarget.MouseAxis(MouseMotion.MOVE_X)) in ms)
        assertTrue(Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, "b"), OutputTarget.Key(Q)) in ms)
        assertTrue(ms.none { it.source is InputSource.TouchSwipe })
        assertEquals(Bindings.upgradeSwipes(old), Bindings.upgradeSwipes(Bindings.upgradeSwipes(old)))
    }
}
