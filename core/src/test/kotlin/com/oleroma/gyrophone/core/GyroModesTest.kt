package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroModesTest {
    private val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")

    private var now = 0L

    /** Lets the touchpad's queued input changes show: a later frame's snapshot. */
    private fun MappingEngine.frame() { now += 100_000_000L; snapshot() }

    private fun engine(mode: GyroMode, touchpadRatchet: Boolean = false): MappingEngine {
        val ws = Workspace(
            id = "w", name = "w",
            mappings = listOf(
                Mapping(InputSource.Touch("r"), OutputTarget.GyroPause),
                Mapping(InputSource.Touch("a"), OutputTarget.Pad(GamepadButton.A)),
            ),
            pads = listOf(AttachedPad(id = "p", mappings = listOf(
                Mapping(InputSource.Key(AndroidKey.BUTTON_L1), OutputTarget.GyroPause),
                Mapping(InputSource.Key(AndroidKey.BUTTON_Y), OutputTarget.GyroToggle),
            ))),
            gyro = GyroSettings(mode = mode),
        ).let { w ->
            // A touchpad ratchet is the workspace's one-finger hold mapped to Ratchet.
            if (!touchpadRatchet) w else w.copy(mappings = w.mappings + Mapping(InputSource.Surface(1, SurfaceInput.HOLD), OutputTarget.GyroPause))
        }
        return MappingEngine(single(ws)) { now }
    }

    @Test fun ratchetPausesAlwaysOn() {
        val e = engine(GyroMode.ALWAYS)
        assertTrue(e.gyroActive)
        e.setTouch("r", true)
        assertFalse(e.gyroActive)
        e.setTouch("r", false)
        assertTrue(e.gyroActive)
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        assertFalse(e.gyroActive)
    }

    @Test fun ratchetEngagesAlwaysOff() {
        val e = engine(GyroMode.OFF)
        assertFalse(e.gyroActive)
        e.setTouch("r", true)
        assertTrue(e.gyroActive)
        e.setTouch("r", false)
        assertFalse(e.gyroActive)
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        assertTrue(e.gyroActive)
    }

    @Test fun touchpadIsARatchetInBothModesWhenOn() {
        val on = engine(GyroMode.ALWAYS, touchpadRatchet = true)
        on.touchpadDown(1, 10f, 10f); on.frame()
        assertFalse(on.gyroActive)
        on.touchpadUp(1); on.frame()
        assertTrue(on.gyroActive)

        val off = engine(GyroMode.OFF, touchpadRatchet = true)
        off.touchpadDown(1, 10f, 10f); off.frame()
        assertTrue(off.gyroActive)
        off.touchpadUp(1); off.frame()
        assertFalse(off.gyroActive)

        val plain = engine(GyroMode.ALWAYS)
        plain.touchpadDown(1, 10f, 10f); plain.frame()
        assertTrue(plain.gyroActive)
    }

    @Test fun togglePressFlipsAlwaysOnAndTheRatchetWithIt() {
        val e = engine(GyroMode.ALWAYS)
        e.setKey(pad, AndroidKey.BUTTON_Y, true); e.setKey(pad, AndroidKey.BUTTON_Y, false)
        assertFalse(e.gyroActive)
        e.setTouch("r", true)
        assertTrue(e.gyroActive)
    }

    @Test fun setGyroSwitchesModeAndKeepsHeldInputs() {
        val e = engine(GyroMode.ALWAYS)
        e.setTouch("a", true)
        e.setGyro(e.workspace.gyro.copy(mode = GyroMode.OFF))
        assertEquals(GyroMode.OFF, e.workspace.gyro.mode)
        assertFalse(e.gyroActive)
        assertTrue(e.snapshot().gamepad.buttons != 0)
        e.setTouch("r", true)
        assertTrue(e.gyroActive)
        // The new mode is the profile's too, so returning to the workspace keeps it.
        e.workspaceIndex = 0
        assertEquals(GyroMode.OFF, e.workspace.gyro.mode)
    }

    @Test fun gyroOffIgnoresRatchetsAndToggle() {
        val e = engine(GyroMode.SENSOR_OFF, touchpadRatchet = true)
        assertFalse(e.gyroActive)
        e.setTouch("r", true)
        assertFalse(e.gyroActive)
        e.setKey(pad, AndroidKey.BUTTON_Y, true); e.setKey(pad, AndroidKey.BUTTON_Y, false)
        assertFalse(e.gyroActive)
        e.touchpadDown(1, 10f, 10f)
        assertFalse(e.gyroActive)
    }

    @Test fun oldHoldOrToggleLoadsAsRatchetEnables() {
        val old = Profile(id = "p", name = "P", workspaces = listOf(Workspace(id = "w", name = "w",
            gyro = GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE))))
        assertEquals(GyroMode.OFF, ProfileJson.decode(ProfileJson.encode(old)).workspaces.single().gyro.mode)
        assertEquals(GyroMode.OFF, GyroSettings().mode)
    }

    @Test fun buttonsTakeTheTouchpadRatchetUnlessTheyMapTheirHold() {
        val ws = Workspace(
            id = "w", name = "w",
            touchControls = listOf(
                TouchControl("plain", TouchKind.BUTTON, "p", 0.2f, 0.5f),
                TouchControl("rat", TouchKind.BUTTON, "r", 0.8f, 0.5f),
            ),
            mappings = listOf(
                Mapping(InputSource.Surface(1, SurfaceInput.HOLD), OutputTarget.GyroPause),
                Mapping(InputSource.Touch("plain"), OutputTarget.Pad(GamepadButton.A)),
            ),
            gyro = GyroSettings(mode = GyroMode.ALWAYS),
        )
        val e = MappingEngine(single(ws)) { now }
        e.buttonDown(1, "plain", 10f, 10f)
        assertTrue(e.gyroActive)
        now += 100_000_000L
        e.buttonUp(1)
        e.buttonDown(2, "rat", 10f, 10f)
        assertFalse(e.gyroActive)
        now += 100_000_000L // past the shortest press, so the lift releases at once
        e.buttonUp(2)
        assertTrue(e.gyroActive)
        e.touchpadDown(3, 10f, 10f); e.frame()
        assertFalse(e.gyroActive)
    }

    @Test fun gyroCornerBecomesToggleButtons() {
        val corner = TouchControl(NavCorners.GYRO, TouchKind.FULLSCREEN, "G", 0.9f, 0.06f)
        val p = Profile(
            id = "p", name = "P",
            workspaces = listOf(Workspace(id = "w1", name = "1"), Workspace(id = "w2", name = "2")),
            corners = NavCorners.defaults() + corner,
        )
        val q = NavCorners.gyroCornerToButtons(ProfileJson.decode(ProfileJson.encode(p)))
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT), q.corners.map { it.id })
        for (w in q.workspaces) {
            val b = w.touchControls.single { it.id == NavCorners.GYRO_BUTTON }
            assertEquals(TouchKind.BUTTON, b.kind)
            assertEquals(0.9f, b.x)
            assertEquals(listOf(OutputTarget.GyroToggle), w.mappings.filter { it.source == InputSource.Touch(NavCorners.GYRO_BUTTON) }.map { it.target })
        }
        // A hidden corner just goes.
        val hidden = NavCorners.gyroCornerToButtons(p.copy(gyroCorner = false))
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT), hidden.corners.map { it.id })
        assertTrue(hidden.workspaces.all { it.touchControls.isEmpty() })
    }

    @Test fun gyroMovesTheMouseByRollAndPitchFromTheBuiltInMappings() {
        val gyro = Inheritance.builtin.filter { it.source is InputSource.Gyro }.associate { (it.source as InputSource.Gyro).axis to it.target }
        assertEquals(mapOf(
            MotionAxis.ROLL to OutputTarget.MouseAxis(MouseMotion.MOVE_X),
            MotionAxis.PITCH to OutputTarget.MouseAxis(MouseMotion.MOVE_Y),
        ), gyro)
    }

    @Test fun loadingKeepsTheGyroSettings() {
        val custom = GyroSettings(mode = GyroMode.ALWAYS, sensitivity = 20f, rollSensitivity = 8f)
        val old = Profile(
            id = "p", name = "P",
            workspaces = listOf(Workspace(id = "w", name = "w", gyro = custom)),
        )
        val p = ProfileJson.decode(ProfileJson.encode(old))
        assertEquals(custom, p.workspaces.single().gyro)
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT), p.corners.map { it.id })
        // Saved again it reads back the same.
        assertEquals(p, ProfileJson.decode(ProfileJson.encode(p)))
    }

    @Test fun anOldGyroCornerIsNeverShown() {
        val corner = TouchControl(NavCorners.GYRO, TouchKind.FULLSCREEN, "G", 0.9f, 0.06f)
        val p = Profile(id = "p", name = "P", workspaces = listOf(Workspace(id = "w", name = "w")), corners = NavCorners.defaults() + corner)
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT), p.shownCorners.map { it.id })
    }
}
