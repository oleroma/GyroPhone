package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
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
    )).noBuiltinGyro())

    /** Without the built-in roll and pitch mouse mappings. */
    private fun com.oleroma.gyrophone.core.model.Profile.noBuiltinGyro() = copy(defaults = defaults.copy(
        mappings = listOf(gyro(MotionAxis.ROLL, OutputTarget.NoOutput), gyro(MotionAxis.PITCH, OutputTarget.NoOutput)),
    ))

    @Test fun gyroMovesTheMouseByRollAndPitchUnlessMappedOtherwise() {
        val moveY = OutputTarget.MouseAxis(MouseMotion.MOVE_Y)
        val e = MappingEngine(single(Workspace("w", "w")))
        assertEquals(listOf(gyro(MotionAxis.ROLL, moveX), gyro(MotionAxis.PITCH, moveY)), e.gyroRoutes())
        // Mapping pitch to something else takes it over; roll still moves the mouse.
        val w = MappingEngine(single(Workspace("w", "w", mappings = listOf(gyro(MotionAxis.PITCH, wheel)))))
        assertEquals(setOf(gyro(MotionAxis.PITCH, wheel), gyro(MotionAxis.ROLL, moveX)), w.gyroRoutes().toSet())
        assertTrue(engine().gyroRoutes().isEmpty())
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
        val g = GyroMouse(GyroSettings(sensitivity = GyroSettings.DEFAULT_SENSITIVITY))
        g.displayRotation = 270
        // Landscape right: yaw = x, pitch = y (rad/s).
        val rad = (Math.PI / 180).toFloat()
        val routes = listOf(
            gyro(MotionAxis.YAW, moveX),
            gyro(MotionAxis.PITCH, wheel),
            gyro(MotionAxis.YAW, lx),
            gyro(MotionAxis.PITCH, OutputTarget.PadAxis(GamepadAxis.LY)),
        )
        val r = g.route(45f * rad, 30f * rad, 0f, 0.5f, routes)
        assertEquals(360, r.dx) // 45°/s × 0.5 s × 16
        assertEquals(MouseState.WHEEL_UNIT, r.wheel) // 15° up: one notch up
        assertEquals(0.5f, r.pad[GamepadAxis.LX.ordinal], 1e-4f)
        assertEquals(-1f / 3f, r.pad[GamepadAxis.LY.ordinal], 1e-4f) // pitching up pushes the stick up
        assertEquals(0, g.route(45f * rad, 0f, 0f, 0.5f, routes, MappingEngine.ALL_GYRO and MappingEngine.gyroBit(MotionAxis.YAW).inv()).dx) // yaw ratcheted
    }

    @Test fun aHeldButtonsSensitivityIsTheGyrosLastPressedWinsAndLayersOverride() {
        val ws = Workspace(
            "w", "w",
            touchControls = listOf(
                TouchControl("aim", TouchKind.BUTTON, "aim", 0.5f, 0.5f, gyro = listOf(ButtonGyro(sensitivity = 4f), ButtonGyro(1, sensitivity = 2f))),
                TouchControl("roll", TouchKind.BUTTON, "roll", 0.5f, 0.5f, gyro = listOf(ButtonGyro(roll = 40f))),
                TouchControl("plain", TouchKind.BUTTON, "plain", 0.5f, 0.5f),
            ),
            gyro = GyroSettings(sensitivity = 10f, yawSensitivity = 12f),
            sets = listOf(MappingSet(1)),
            mappings = listOf(Mapping(InputSource.Touch("plain"), OutputTarget.Layer(1, whileHeld = true))),
        )
        val e = MappingEngine(single(ws))
        assertEquals(12f, e.gyroSettings.sensitivityOf(MotionAxis.YAW))
        e.setTouch("aim", true)
        // The button's own Sensitivity beats the workspace's per-axis one.
        assertEquals(4f, e.gyroSettings.sensitivityOf(MotionAxis.YAW))
        assertEquals(4f, e.gyroSettings.sensitivityOf(MotionAxis.ROLL))
        e.setTouch("roll", true)
        // Last pressed wins: roll its own, the rest the workspace's.
        assertEquals(40f, e.gyroSettings.sensitivityOf(MotionAxis.ROLL))
        assertEquals(12f, e.gyroSettings.sensitivityOf(MotionAxis.YAW))
        assertEquals(10f, e.gyroSettings.sensitivityOf(MotionAxis.PITCH))
        e.setTouch("roll", false)
        e.setTouch("plain", true) // layer 1: aim's layer value
        assertEquals(2f, e.gyroSettings.sensitivityOf(MotionAxis.PITCH))
        e.setTouch("plain", false)
        e.setTouch("aim", false)
        assertEquals(10f, e.gyroSettings.sensitivityOf(MotionAxis.PITCH))
    }

    @Test fun aButtonsGyroSettingsGoWhenEmpty() {
        val c = TouchControl("a", TouchKind.BUTTON, "a", 0.5f, 0.5f).withGyro(1) { it.copy(yaw = 3f) }
        assertEquals(listOf(ButtonGyro(1, yaw = 3f)), c.gyro)
        assertEquals(3f, c.gyroIn(listOf(MappingSet.BASE, 1)).yaw)
        assertEquals(null, c.gyroIn(listOf(MappingSet.BASE)).yaw)
        assertTrue(c.withGyro(1) { it.copy(yaw = null) }.gyro.isEmpty())
    }
}
