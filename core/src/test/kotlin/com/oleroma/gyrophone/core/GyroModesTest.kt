package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroAxes
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroModesTest {
    private val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")

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
            gyro = GyroSettings(mode = mode, touchpadRatchet = touchpadRatchet),
        )
        return MappingEngine(single(ws))
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
        on.touchpadDown(1, 10f, 10f)
        assertFalse(on.gyroActive)
        on.touchpadUp(1)
        assertTrue(on.gyroActive)

        val off = engine(GyroMode.OFF, touchpadRatchet = true)
        off.touchpadDown(1, 10f, 10f)
        assertTrue(off.gyroActive)
        off.touchpadUp(1)
        assertFalse(off.gyroActive)

        val plain = engine(GyroMode.ALWAYS)
        plain.touchpadDown(1, 10f, 10f)
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
        e.setGyro(e.workspace.gyro.toggledMode())
        assertEquals(GyroMode.OFF, e.workspace.gyro.mode)
        assertFalse(e.gyroActive)
        assertTrue(e.snapshot().gamepad.buttons != 0)
        e.setTouch("r", true)
        assertTrue(e.gyroActive)
        // The new mode is the profile's too, so returning to the workspace keeps it.
        e.workspaceIndex = 0
        assertEquals(GyroMode.OFF, e.workspace.gyro.mode)
    }

    @Test fun cornerTogglesBetweenAlwaysOnAndOff() {
        assertEquals(GyroMode.OFF, GyroSettings(mode = GyroMode.ALWAYS).toggledMode().mode)
        assertEquals(GyroMode.ALWAYS, GyroSettings(mode = GyroMode.OFF).toggledMode().mode)
        assertEquals(GyroMode.ALWAYS, GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE).toggledMode().mode)
    }

    @Test fun presetsHaveRollAndPitchGyroAxesForBothOrientations() {
        for (w in BuiltinProfiles.presets + BuiltinProfiles.blankWorkspace()) {
            assertNotNull("${w.name} portrait axes", w.gyro.portrait)
            assertEquals(w.gyro.landscapeAxes, w.gyro.axesFor(portrait = true))
            assertEquals(MotionAxis.ROLL, w.gyro.axesFor(portrait = true).mouseX)
            assertEquals(MotionAxis.PITCH, w.gyro.axesFor(portrait = true).mouseY)
            assertEquals(MotionAxis.ROLL, w.gyro.landscapeAxes.mouseX)
        }
    }

    @Test fun loadingSpellsOutPortraitAxesAndAddsTheGyroCorner() {
        val custom = GyroSettings(mode = GyroMode.ALWAYS, mouseX = MotionAxis.ROLL, invertY = true)
        val old = Profile(
            id = "p", name = "P",
            workspaces = listOf(Workspace(id = "w", name = "w", gyro = custom)),
            corners = NavCorners.defaults().filter { it.id != NavCorners.GYRO },
        )
        val p = ProfileJson.decode(ProfileJson.encode(old))
        val g = p.workspaces.single().gyro
        assertEquals(GyroAxes(mouseX = MotionAxis.ROLL, invertY = true), g.portrait)
        assertEquals(custom.landscapeAxes, g.landscapeAxes)
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT, NavCorners.GYRO), p.corners.map { it.id })
        assertTrue(p.gyroCorner)
        // Saved again it reads back the same.
        assertEquals(p, ProfileJson.decode(ProfileJson.encode(p)))
    }

    @Test fun hiddenGyroCornerIsNotShown() {
        val p = Profile(id = "p", name = "P", workspaces = listOf(Workspace(id = "w", name = "w")), gyroCorner = false)
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT), p.shownCorners.map { it.id })
    }
}
