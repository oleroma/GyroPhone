package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KeyMode
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEngineTest {
    private val MS = 1_000_000L

    private val xbox = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val xbox2 = PadDevice(id = 2, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val sense = PadDevice(id = 3, vendorId = 0x054C, productId = 0x0CE6, name = "DualSense Wireless Controller")
    private val keyboard = PadDevice(id = 4, vendorId = 0x1234, productId = 0x0001, name = "BT Keyboard")

    private fun key(code: Int, target: OutputTarget) = Mapping(InputSource.Key(code), target)
    private fun axis(a: Int, target: OutputTarget) = Mapping(InputSource.Axis(a), target)
    private fun touch(id: String, target: OutputTarget) = Mapping(InputSource.Touch(id), target)

    private val xboxPad = AttachedPad(
        id = "xb", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13), name = "Xbox",
        mappings = listOf(
            key(AndroidKey.BUTTON_A, OutputTarget.Pad(GamepadButton.A)),
            key(AndroidKey.BUTTON_B, OutputTarget.Wheel(1)),
            axis(AndroidAxis.X, OutputTarget.PadAxis(GamepadAxis.LX)),
            axis(AndroidAxis.RTRIGGER, OutputTarget.PadAxis(GamepadAxis.RT)),
        ),
    )
    private val sensePad = AttachedPad(
        id = "ds", match = DeviceMatch(nameContains = "DualSense"), name = "DualSense",
        mappings = listOf(
            key(AndroidKey.BUTTON_A, OutputTarget.Pad(GamepadButton.B)),
            key(AndroidKey.BUTTON_B, OutputTarget.Wheel(1)),
            axis(AndroidAxis.X, OutputTarget.PadAxis(GamepadAxis.LX)),
            axis(AndroidAxis.RTRIGGER, OutputTarget.PadAxis(GamepadAxis.RT)),
            key(AndroidKey.BUTTON_X, OutputTarget.GyroPause),
        ),
    )

    private fun ws(id: String, pads: List<AttachedPad>, mappings: List<Mapping> = emptyList(), gyro: GyroSettings = GyroSettings()) =
        Workspace(id = id, name = id, mappings = mappings, pads = pads, gyro = gyro)

    private fun twoPads() = single(ws("w", listOf(xboxPad, sensePad)))

    @Test fun unattachedDeviceDoesNothing() {
        val e = MappingEngine(single(ws("w", listOf(xboxPad))))
        assertFalse(e.setKey(sense, AndroidKey.BUTTON_A, true))
        e.setAxis(sense, AndroidAxis.X, 1f)
        assertFalse(e.setKey(keyboard, AndroidKey.BUTTON_A, true))
        val s = e.snapshot()
        assertEquals(0, s.gamepad.buttons)
        assertEquals(0, s.gamepad.lx)
        // The attached model works, and an unmapped key of it is not consumed.
        assertTrue(e.setKey(xbox, AndroidKey.BUTTON_A, true))
        assertFalse(e.setKey(xbox, AndroidKey.BUTTON_Y, true))
        assertEquals(1 shl GamepadButton.A.bit, e.snapshot().gamepad.buttons)
    }

    @Test fun eachPadUsesItsOwnMappings() {
        val e = MappingEngine(twoPads())
        e.setKey(sense, AndroidKey.BUTTON_A, true)
        assertEquals(1 shl GamepadButton.B.bit, e.snapshot().gamepad.buttons)
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        assertEquals((1 shl GamepadButton.A.bit) or (1 shl GamepadButton.B.bit), e.snapshot().gamepad.buttons)
    }

    @Test fun anyGamepadPadTakesEveryDevice() {
        val any = AttachedPad(id = "any", mappings = listOf(key(AndroidKey.BUTTON_A, OutputTarget.Key(0x04))))
        val e = MappingEngine(single(ws("w", listOf(any))))
        assertTrue(e.setKey(keyboard, AndroidKey.BUTTON_A, true))
        assertEquals(listOf(0x04), e.snapshot().keyboard.keys)
    }

    @Test fun axesTakeTheLargestDeflectionAcrossPads() {
        val e = MappingEngine(twoPads())
        e.setAxis(xbox, AndroidAxis.X, -0.3f)
        e.setAxis(sense, AndroidAxis.X, 0.8f)
        assertEquals((0.8f * 32767).toInt(), e.snapshot().gamepad.lx)
        e.setAxis(xbox, AndroidAxis.X, -0.9f)
        assertEquals((-0.9f * 32767).toInt(), e.snapshot().gamepad.lx)
        // Not a sum: two pads at full deflection stay at full, not clipped from 2.
        e.setAxis(xbox, AndroidAxis.X, 0.5f); e.setAxis(sense, AndroidAxis.X, 0.5f)
        assertEquals((0.5f * 32767).toInt(), e.snapshot().gamepad.lx)
        // Triggers: the larger.
        e.setAxis(xbox, AndroidAxis.RTRIGGER, 0.25f); e.setAxis(sense, AndroidAxis.RTRIGGER, 0.75f)
        assertEquals((0.75f * 1023).toInt(), e.snapshot().gamepad.rt)
    }

    @Test fun twoDevicesOfOneModelAreSeparateSources() {
        val e = MappingEngine(single(ws("w", listOf(xboxPad))))
        e.setAxis(xbox, AndroidAxis.X, 0.4f)
        e.setAxis(xbox2, AndroidAxis.X, -0.6f)
        assertEquals((-0.6f * 32767).toInt(), e.snapshot().gamepad.lx)
        e.setKey(xbox2, AndroidKey.BUTTON_A, true)
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        e.setKey(xbox2, AndroidKey.BUTTON_A, false)
        assertEquals(1 shl GamepadButton.A.bit, e.snapshot().gamepad.buttons) // still held by the first
    }

    @Test fun touchStickIsASourceToo() {
        val p = single(ws("w", listOf(xboxPad), mappings = listOf(
            Mapping(InputSource.TouchStick("ls", StickAxis.X), OutputTarget.PadAxis(GamepadAxis.LX)),
        )))
        val e = MappingEngine(p)
        e.setAxis(xbox, AndroidAxis.X, 0.5f)
        e.setTouchStick("ls", -0.7f, 0f)
        assertEquals((-0.7f * 32767).toInt(), e.snapshot().gamepad.lx)
        e.setTouchStick("ls", 0.2f, 0f)
        assertEquals((0.5f * 32767).toInt(), e.snapshot().gamepad.lx)
    }

    @Test fun legacySourceActsOnEveryPad() {
        val e = MappingEngine(twoPads())
        assertTrue(e.setKey(AndroidKey.BUTTON_A, true))
        assertEquals((1 shl GamepadButton.A.bit) or (1 shl GamepadButton.B.bit), e.snapshot().gamepad.buttons)
        assertFalse(e.setKey(AndroidKey.BUTTON_Y, true))
        e.setAxis(AndroidAxis.X, 0.6f)
        assertEquals((0.6f * 32767).toInt(), e.snapshot().gamepad.lx)
    }

    @Test fun deviceRemovedReleasesItsInput() {
        val e = MappingEngine(twoPads())
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        e.setAxis(xbox, AndroidAxis.X, 1f)
        e.setKey(sense, AndroidKey.BUTTON_A, true)
        var calls = 0
        e.onChange = Runnable { calls++ }
        e.deviceRemoved(xbox.id)
        assertEquals(1, calls)
        val g = e.snapshot().gamepad
        assertEquals(1 shl GamepadButton.B.bit, g.buttons)
        assertEquals(0, g.lx)
        assertEquals(0f, e.sourceValue(InputSource.Axis(AndroidAxis.X)), 0f)
    }

    @Test fun switchingWorkspaceReleasesAndReroutes() {
        val p = Profile(id = "p", name = "p", workspaces = listOf(
            ws("one", listOf(xboxPad), mappings = listOf(touch("t", OutputTarget.Key(0x04)))),
            ws("two", listOf(sensePad)),
        ))
        val e = MappingEngine(p)
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        e.setTouch("t", true)
        e.setTouchStick("s", 1f, 1f)
        assertEquals(1 shl GamepadButton.A.bit, e.snapshot().gamepad.buttons)
        var calls = 0
        e.onChange = Runnable { calls++ }
        e.workspaceIndex = 1
        assertEquals(1, calls)
        assertEquals("two", e.workspace.id)
        val s = e.snapshot()
        assertEquals(0, s.gamepad.buttons)
        assertTrue(s.keyboard.keys.isEmpty())
        assertTrue(e.pressedTouches().isEmpty())
        // The Xbox pad is not attached here; the DualSense is.
        assertFalse(e.setKey(xbox, AndroidKey.BUTTON_A, true))
        assertTrue(e.setKey(sense, AndroidKey.BUTTON_A, true))
        assertEquals(1 shl GamepadButton.B.bit, e.snapshot().gamepad.buttons)
        // Back: what was held is released again, and the Xbox pad acts.
        e.workspaceIndex = 0
        assertEquals(0, e.snapshot().gamepad.buttons)
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        assertEquals(1 shl GamepadButton.A.bit, e.snapshot().gamepad.buttons)
        // Out of range clamps.
        e.workspaceIndex = 7
        assertEquals("two", e.workspace.id)
    }

    @Test fun profileChangeReleasesEverything() {
        val e = MappingEngine(twoPads())
        e.setKey(sense, AndroidKey.BUTTON_A, true)
        e.profile = twoPads()
        assertEquals(0, e.snapshot().gamepad.buttons)
    }

    @Test fun eventTargetsFireOncePerEngagementAcrossGroups() {
        val p = single(ws("w", listOf(xboxPad, sensePad), mappings = listOf(touch("wh", OutputTarget.Wheel(1)))))
        val e = MappingEngine(p)
        e.setKey(xbox, AndroidKey.BUTTON_B, true)
        e.setKey(sense, AndroidKey.BUTTON_B, true)
        e.setTouch("wh", true)
        assertEquals(120, e.snapshot().mouse.wheel)
        e.setKey(xbox, AndroidKey.BUTTON_B, false)
        e.setKey(sense, AndroidKey.BUTTON_B, false)
        e.setTouch("wh", false)
        e.setKey(sense, AndroidKey.BUTTON_B, true)
        assertEquals(120, e.snapshot().mouse.wheel)
    }

    @Test fun gyroToggleAndPauseFromAnyGroup() {
        val toggle = AttachedPad(id = "a", mappings = listOf(key(AndroidKey.BUTTON_Y, OutputTarget.GyroToggle)))
        val p = single(ws("w", listOf(toggle), mappings = listOf(touch("g", OutputTarget.GyroToggle)),
            gyro = GyroSettings(mode = GyroMode.OFF)))
        val e = MappingEngine(p)
        e.setKey(xbox, AndroidKey.BUTTON_Y, true)
        assertTrue(e.gyroActive)
        e.setTouch("g", true) // each toggle mapping flips on its own
        assertFalse(e.gyroActive)
        e.setKey(xbox, AndroidKey.BUTTON_Y, false); e.setTouch("g", false)
        e.setTouch("g", true)
        assertTrue(e.gyroActive)

        val paused = MappingEngine(single(ws("w", listOf(sensePad), gyro = GyroSettings(mode = GyroMode.ALWAYS))))
        assertTrue(paused.gyroActive)
        paused.setKey(sense, AndroidKey.BUTTON_X, true)
        assertFalse(paused.gyroActive)
        paused.setKey(sense, AndroidKey.BUTTON_X, false)
        assertTrue(paused.gyroActive)
    }

    @Test fun gyroFollowsTheWorkspace() {
        val p = Profile(id = "p", name = "p", workspaces = listOf(
            ws("one", emptyList(), gyro = GyroSettings(mode = GyroMode.ALWAYS)),
            ws("two", emptyList(), gyro = GyroSettings(mode = GyroMode.OFF)),
        ))
        val e = MappingEngine(p)
        assertTrue(e.gyroActive)
        e.workspaceIndex = 1
        assertFalse(e.gyroActive)
    }

    @Test fun sourceValueSeesEveryDevice() {
        val e = MappingEngine(single(ws("w", listOf(xboxPad))))
        e.setKey(sense, AndroidKey.BUTTON_A, true) // unattached: no output, but visible for the editor
        assertEquals(1f, e.sourceValue(InputSource.Key(AndroidKey.BUTTON_A)), 0f)
        assertEquals(0, e.snapshot().gamepad.buttons)
        e.setAxis(xbox, AndroidAxis.X, -0.4f)
        assertEquals(-0.4f, e.sourceValue(InputSource.Axis(AndroidAxis.X)), 1e-6f)
    }

    // ---- Touch buttons ----

    private val A = 0x04
    private val B = 0x05

    /**
     * Buttons "a", "b", "shift", "ctrl" and "wh" pressing what they name, over a touchpad whose
     * mappings are all cleared, so the buttons take nothing from it; [extra] adds mappings.
     */
    private fun buttons(extra: List<Mapping> = emptyList(), now: () -> Long): MappingEngine {
        val p = single(Workspace(
            id = "w", name = "w",
            touchControls = listOf("a", "b", "shift", "ctrl", "wh").map {
                TouchControl(it, TouchKind.BUTTON, it, 0.5f, 0.5f)
            },
            mappings = listOf(
                touch("a", OutputTarget.Key(A)),
                touch("b", OutputTarget.Key(B)),
                touch("shift", OutputTarget.Key(HidUsage.LEFT_SHIFT)),
                touch("ctrl", OutputTarget.Key(HidUsage.LEFT_CTRL)),
                touch("wh", OutputTarget.Wheel(-1)),
            ) + extra,
        )).noTouchpadDefaults()
        return MappingEngine(p, now).apply { touchpadPxPerDp = 2f; touchpadMinPulseNanos = 40 * MS }
    }

    private fun keys(e: MappingEngine) = e.snapshot().keyboard.keys
    private fun mods(e: MappingEngine) = e.snapshot().keyboard.modifiers

    @Test fun plainButtonsPressAtOnce() {
        var now = 0L
        val e = buttons { now }
        e.buttonDown(1, "a", 100f, 100f)
        assertEquals(listOf(A), keys(e))
        assertEquals(setOf("a"), e.pressedTouches())
        e.buttonMove(1, 400f, 100f) // moving does not matter
        now = 1000 * MS
        assertEquals(listOf(A), keys(e))
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    @Test fun buttonsTakeTheWorkspacesTouchpadMappings() {
        var now = 0L
        val p = single(Workspace(
            id = "w", name = "w",
            touchControls = listOf(TouchControl("a", TouchKind.BUTTON, "a", 0.5f, 0.5f)),
            mappings = listOf(touch("a", OutputTarget.Key(A))),
        ))
        val e = MappingEngine(p) { now }.apply { touchpadPxPerDp = 2f; touchpadMinPulseNanos = 40 * MS }
        e.buttonDown(1, "a", 100f, 100f)
        assertEquals(listOf(A), keys(e)) // its own press
        var dx = 0
        for (i in 1..3) { now = i * 10 * MS; e.buttonMove(1, 100f + 30f * i, 100f); dx += e.snapshot().mouse.dx }
        val s = e.snapshot()
        assertTrue(dx > 0) // the Defaults' one-finger Move
        assertEquals(listOf(A), s.keyboard.keys)
        now = 100 * MS
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    @Test fun aClearedInputIsNotTaken() {
        var now = 0L
        val p = single(Workspace(
            id = "w", name = "w",
            touchControls = listOf(TouchControl("a", TouchKind.BUTTON, "a", 0.5f, 0.5f)),
            mappings = listOf(
                touch("a", OutputTarget.Key(A)),
                Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X, "a"), OutputTarget.NoOutput),
            ),
        ))
        val e = MappingEngine(p) { now }.apply { touchpadPxPerDp = 2f }
        e.buttonDown(1, "a", 100f, 100f)
        var dx = 0
        for (i in 1..3) { now = i * 10 * MS; e.buttonMove(1, 100f + 30f * i, 100f); dx += e.snapshot().mouse.dx }
        val s = e.snapshot()
        assertEquals(0, dx)
        assertEquals(listOf(A), s.keyboard.keys)
    }

    @Test fun tapMappedButtonPulsesOnLift() {
        var now = 0L
        val e = buttons(listOf(Mapping(InputSource.Surface(1, SurfaceInput.TAP, "b"), OutputTarget.Key(A)))) { now }
        e.buttonDown(1, "b", 100f, 100f)
        assertEquals(listOf(B), keys(e))
        now = 50 * MS
        e.buttonUp(1)
        now = 200 * MS // even a late snapshot sees the tap once
        assertEquals(listOf(A), keys(e))
        now = 300 * MS
        assertTrue(keys(e).isEmpty())
    }

    @Test fun twoButtonFingersAtOnce() {
        var now = 0L
        val e = buttons { now }
        e.buttonDown(1, "a", 100f, 100f)
        e.buttonDown(2, "b", 300f, 100f)
        assertEquals(setOf(A, B), keys(e).toSet())
        now = 50 * MS
        e.buttonUp(2)
        assertEquals(listOf(A), keys(e))
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    @Test fun unknownFingersAreIgnoredAndWorkspaceChangeForgetsFingers() {
        var now = 0L
        val e = buttons { now }
        e.buttonMove(9, 1f, 1f); e.buttonUp(9)
        e.buttonDown(1, "a", 0f, 0f)
        e.workspaceIndex = 0
        assertTrue(keys(e).isEmpty())
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    // ---- Key modes ----

    private fun modes(now: () -> Long) = buttons(listOf(
        touch("ns", OutputTarget.Key(HidUsage.LEFT_SHIFT, KeyMode.NEXT_KEY)),
        touch("nc", OutputTarget.Key(HidUsage.LEFT_CTRL, KeyMode.NEXT_KEY)),
        touch("ts", OutputTarget.Key(HidUsage.LEFT_SHIFT, KeyMode.TOGGLE)),
    ), now)

    private fun press(e: MappingEngine, id: String) {
        e.setTouch(id, true); e.snapshot(); e.setTouch(id, false); e.snapshot()
    }

    @Test fun nextKeyAppliesToTheNextKeyOnly() {
        var now = 0L
        val e = modes { now }
        press(e, "ns")
        assertEquals(0x02, mods(e))
        e.setTouch("a", true)
        val s = e.snapshot()
        assertEquals(0x02, s.keyboard.modifiers)
        assertEquals(listOf(A), s.keyboard.keys)
        assertEquals(0x02, mods(e)) // held with the key
        e.setTouch("a", false)
        e.snapshot()
        assertEquals(0, mods(e))
        e.setTouch("b", true)
        assertEquals(0, mods(e)) // the key after that is plain
    }

    @Test fun twoNextKeysBothApplyAndPressingAgainLetsGo() {
        var now = 0L
        val e = modes { now }
        press(e, "nc"); press(e, "ns")
        assertEquals(0x03, mods(e))
        press(e, "nc")
        assertEquals(0x02, mods(e))
        press(e, "a")
        assertEquals(0, mods(e))
    }

    @Test fun toggleHoldsUntilPressedAgain() {
        var now = 0L
        val e = modes { now }
        press(e, "ts")
        press(e, "a"); press(e, "b")
        assertEquals(0x02, mods(e))
        press(e, "ts")
        assertEquals(0, mods(e))
    }

    @Test fun workspaceChangeLetsKeyModesGo() {
        var now = 0L
        val e = modes { now }
        press(e, "ts"); press(e, "nc")
        assertEquals(0x03, mods(e))
        e.workspaceIndex = 0
        assertEquals(0, mods(e))
    }
}
