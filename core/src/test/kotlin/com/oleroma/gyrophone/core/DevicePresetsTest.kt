package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.DevicePresets
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device presets: pinned by workspaces, mapping any pad, a keyboard and a mouse. */
class DevicePresetsTest {
    private val pad = PadDevice(id = 1, vendorId = 0x1234, productId = 1, name = "Some pad")
    private val keyboard = PadDevice(id = 2, vendorId = 0, productId = 0, name = "Keyboard", keyboard = true)
    private val mouse = PadDevice(id = 3, vendorId = 0, productId = 0, name = "Mouse", mouse = true)
    private val A = 1 shl GamepadButton.A.bit

    private fun engine(pinned: Boolean, change: (com.oleroma.gyrophone.core.model.DevicePreset) -> com.oleroma.gyrophone.core.model.DevicePreset = { it }): MappingEngine {
        val preset = change(DevicePresets.standard("preset1", "Preset 1"))
        val set = Profile(
            "t", "t", listOf(Workspace("w", "w", devicePreset = if (pinned) "preset1" else null)),
            devicePresets = listOf(preset),
        )
        return MappingEngine(WorkspaceMappings.resolve(set))
    }

    @Test fun anyPadUsesThePinnedPreset() {
        val e = engine(pinned = true)
        e.setKey(pad, AndroidKey.BUTTON_A, true)
        assertEquals(A, e.snapshot().gamepad.buttons)
    }

    @Test fun withNoPresetDevicesDoNothing() {
        val e = engine(pinned = false)
        assertEquals(false, e.setKey(pad, AndroidKey.BUTTON_A, true))
        e.setKey(keyboard, 29, true)
        e.setKey(mouse, AndroidKey.MOUSE_LEFT, true)
        e.mouseMove(mouse, 10f, 0f)
        val s = e.snapshot()
        assertEquals(0, s.gamepad.buttons)
        assertTrue(s.keyboard.keys.isEmpty())
        assertEquals(MouseState(), s.mouse)
    }

    @Test fun theStandardKeyboardTypesTheSameKeys() {
        val e = engine(pinned = true)
        e.setKey(keyboard, 29, true) // A
        assertEquals(listOf(0x04), e.snapshot().keyboard.keys)
    }

    @Test fun mouseButtonsWheelAndMovementGoThrough() {
        val e = engine(pinned = true)
        e.setKey(mouse, AndroidKey.MOUSE_RIGHT, true)
        e.setKey(mouse, AndroidKey.MOUSE_WHEEL_UP, true)
        e.setKey(mouse, AndroidKey.MOUSE_WHEEL_UP, false)
        e.mouseMove(mouse, 3.5f, -2f)
        e.mouseMove(mouse, 0.5f, 0f)
        val s = e.snapshot().mouse
        assertEquals(1 shl MouseButton.RIGHT.bit, s.buttons)
        assertEquals(MouseState.WHEEL_UNIT, s.wheel)
        assertEquals(4, s.dx)
        assertEquals(-2, s.dy)
    }

    @Test fun mouseMovementCanScrollAndButtonsHaveGestures() {
        val e = engine(pinned = true) { p ->
            p.copy(
                mouse = listOf(
                    Mapping(InputSource.Axis(AndroidAxis.MOUSE_Y), OutputTarget.MouseAxis(MouseMotion.WHEEL)),
                    Mapping(InputSource.KeyGesture(AndroidKey.MOUSE_BACK, ButtonGesture.LONG_PRESS), OutputTarget.Pad(GamepadButton.A)),
                ),
            )
        }
        e.mouseMove(mouse, 0f, -MappingEngine.MOUSE_COUNTS_PER_NOTCH)
        assertEquals(MouseState.WHEEL_UNIT, e.snapshot().mouse.wheel)
        e.setKey(mouse, AndroidKey.MOUSE_BACK, true)
        assertEquals(0, e.snapshot().gamepad.buttons)
    }

    @Test fun everyDrawnKeyIsMappedByTheStandardKeyboard() {
        val drawn = PhysicalLayouts.keyboardRows.flatten().mapNotNull { it.code }
        val mapped = DevicePresets.standardKeyboard().map { (it.source as InputSource.Key).keyCode }.toSet()
        assertEquals(drawn.toSet(), mapped)
        for (row in PhysicalLayouts.keyboardRows) {
            assertTrue(row.sumOf { it.width.toDouble() } <= PhysicalLayouts.KEYBOARD_UNITS + 0.001)
        }
    }
}
