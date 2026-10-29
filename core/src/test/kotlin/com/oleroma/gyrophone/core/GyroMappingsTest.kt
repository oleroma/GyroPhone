package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gyro axes as mappable inputs: the workspace's, the Defaults', and a held button's. */
class GyroMappingsTest {
    private fun gyro(a: MotionAxis, t: OutputTarget, id: String = "") = Mapping(InputSource.Gyro(a, id), t)
    private val moveX = OutputTarget.MouseAxis(MouseMotion.MOVE_X)
    private val wheel = OutputTarget.MouseAxis(MouseMotion.WHEEL)
    private val lx = OutputTarget.PadAxis(GamepadAxis.LX)

    private fun engine(vararg ms: Mapping) = MappingEngine(single(Workspace(
        "w", "w",
        touchControls = listOf("a", "b").map { TouchControl(it, TouchKind.BUTTON, it, 0.5f, 0.5f) },
        mappings = ms.toList(),
    )))

    @Test fun noGyroMappingKeepsTheSettingsAxes() {
        val e = engine()
        assertFalse(e.gyroMapped)
        assertTrue(e.gyroRoutes().isEmpty())
    }

    @Test fun theWorkspaceTakesTheDefaultsGyroMappings() {
        val p = single(Workspace("w", "w")).copy(defaults = WorkspaceDefaults.builtin.copy(mappings = listOf(gyro(MotionAxis.YAW, moveX))))
        val e = MappingEngine(p)
        assertEquals(listOf(gyro(MotionAxis.YAW, moveX)), e.gyroRoutes())
    }

    @Test fun aHeldButtonTakesItsAxesOverLastPressedWins() {
        val e = engine(
            gyro(MotionAxis.YAW, moveX), gyro(MotionAxis.PITCH, wheel),
            gyro(MotionAxis.YAW, lx, "a"), gyro(MotionAxis.YAW, OutputTarget.NoOutput, "b"),
        )
        assertEquals(setOf(gyro(MotionAxis.YAW, moveX), gyro(MotionAxis.PITCH, wheel)), e.gyroRoutes().toSet())
        e.setTouch("a", true)
        assertEquals(setOf(gyro(MotionAxis.YAW, lx, "a"), gyro(MotionAxis.PITCH, wheel)), e.gyroRoutes().toSet())
        e.setTouch("b", true) // cleared: yaw does nothing while it is the last one held
        assertEquals(listOf(gyro(MotionAxis.PITCH, wheel)), e.gyroRoutes())
        e.setTouch("b", false)
        assertEquals(setOf(gyro(MotionAxis.YAW, lx, "a"), gyro(MotionAxis.PITCH, wheel)), e.gyroRoutes().toSet())
        e.setTouch("a", false)
        assertEquals(setOf(gyro(MotionAxis.YAW, moveX), gyro(MotionAxis.PITCH, wheel)), e.gyroRoutes().toSet())
    }

    @Test fun routingSendsMouseScrollAndSticks() {
        val g = GyroMouse(GyroSettings(orientation = GyroOrientation.LANDSCAPE_RIGHT, sensitivity = 10f))
        // Landscape right: yaw = x, pitch = y (rad/s).
        val rad = (Math.PI / 180).toFloat()
        val routes = listOf(
            gyro(MotionAxis.YAW, moveX),
            gyro(MotionAxis.PITCH, wheel),
            gyro(MotionAxis.YAW, lx),
            gyro(MotionAxis.PITCH, OutputTarget.PadAxis(GamepadAxis.LY)),
        )
        val r = g.route(45f * rad, 30f * rad, 0f, 0.5f, routes, moveX = true, moveY = true)
        assertEquals(225, r.dx) // 45°/s × 0.5 s × 10
        assertEquals(MouseState.WHEEL_UNIT, r.wheel) // 15° up: one notch up
        assertEquals(0.5f, r.pad[GamepadAxis.LX.ordinal], 1e-4f)
        assertEquals(-1f / 3f, r.pad[GamepadAxis.LY.ordinal], 1e-4f) // pitching up pushes the stick up
        assertEquals(0, g.route(45f * rad, 0f, 0f, 0.5f, routes, moveX = false, moveY = true).dx) // ratchet on X
    }
}
