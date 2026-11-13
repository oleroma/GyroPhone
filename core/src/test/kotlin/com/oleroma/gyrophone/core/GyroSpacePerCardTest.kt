package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.GyroSpace
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Each gyro card (yaw, pitch, roll) measured in its own space, following the workspace's unless set. */
class GyroSpacePerCardTest {
    private val g = GyroMouse()
    private val yaw = MotionAxis.ROLL // stored roll is the shown yaw

    @Test fun anUnsetCardFollowsTheWorkspace() {
        val s = GyroSettings(gyroSpace = GyroSpace.WORLD)
        assertEquals(GyroSpace.WORLD, s.spaceOf(MotionAxis.PITCH))
        assertEquals(GyroSpace.PLAYER, s.withSpace(yaw, GyroSpace.PLAYER).spaceOf(yaw))
        assertEquals(GyroSpace.WORLD, s.withSpace(yaw, GyroSpace.PLAYER).spaceOf(MotionAxis.PITCH))
    }

    @Test fun eachAxisComesFromItsOwnSpace() {
        // Tilted back 45°: world yaw differs from local yaw.
        val a = Math.toRadians(45.0)
        val up = floatArrayOf(0f, cos(a).toFloat(), sin(a).toFloat())
        val m = GyroMouse.Motion(3f, 5f, 7f)
        val world = g.inSpace(m, GyroSpace.WORLD, up)
        // World yaw, local pitch and roll.
        val r = g.inSpaces(m, GyroSettings(rollSpace = GyroSpace.WORLD), up)
        assertEquals(world.roll, r.roll, 1e-4f)
        assertEquals(m.pitch, r.pitch, 1e-4f)
        assertEquals(m.yaw, r.yaw, 1e-4f)
        // All the same: the space's own motion.
        val all = g.inSpaces(m, GyroSettings(gyroSpace = GyroSpace.WORLD), up)
        assertEquals(world, all)
    }

    @Test fun titlesNameTheSpace() {
        assertEquals("World yaw", GyroSpace.WORLD.title(yaw))
        assertEquals("Player yaw", GyroSpace.PLAYER.title(yaw))
        assertEquals("World pitch", GyroSpace.WORLD.title(MotionAxis.PITCH))
        assertEquals("Local pitch", GyroSpace.PLAYER.title(MotionAxis.PITCH))
        assertEquals("Local yaw", GyroSpace.LOCAL.title(yaw))
        assertEquals(listOf(GyroSpace.LOCAL, GyroSpace.WORLD), GyroSpace.choicesFor(MotionAxis.PITCH))
        assertEquals(GyroSpace.entries, GyroSpace.choicesFor(yaw))
    }

    @Test fun aHeldButtonsOwnSpaceLiesOverTheWorkspaces() {
        val ws = GyroSettings(gyroSpace = GyroSpace.WORLD)
        val held = ButtonGyro(rollSpace = GyroSpace.PLAYER)
        assertTrue(held.anyGyro)
        val over = held.over(ws)
        assertEquals(GyroSpace.PLAYER, over.spaceOf(yaw))
        assertEquals(GyroSpace.WORLD, over.spaceOf(MotionAxis.PITCH))
        assertEquals(GyroSpace.PLAYER, ButtonGyro().under(held).rollSpace)
    }

    @Test fun theAccelerometerIsNeededWhenAnyCardIsSpaced() {
        assertFalse(Workspace("w", "W").gyroSpaced)
        assertTrue(Workspace("w", "W", gyro = GyroSettings(pitchSpace = GyroSpace.WORLD)).gyroSpaced)
        val b = TouchControl("aim", TouchKind.BUTTON, "aim", 0.5f, 0.5f, gyro = listOf(ButtonGyro(rollSpace = GyroSpace.PLAYER)))
        assertTrue(Workspace("w", "W", touchControls = listOf(b)).gyroSpaced)
        assertFalse(Workspace("w", "W", gyro = GyroSettings(pitchSpace = GyroSpace.LOCAL)).gyroSpaced)
    }
}
