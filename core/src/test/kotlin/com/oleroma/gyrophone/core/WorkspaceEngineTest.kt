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
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
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
            gyro = GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE)))
        val e = MappingEngine(p)
        e.setKey(xbox, AndroidKey.BUTTON_Y, true)
        e.setTouch("g", true) // same engagement: no second flip
        assertTrue(e.gyroActive)
        e.setKey(xbox, AndroidKey.BUTTON_Y, false); e.setTouch("g", false)
        e.setTouch("g", true)
        assertFalse(e.gyroActive)

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

    private fun buttons(overButtons: Boolean, sticky: Boolean = false, now: () -> Long): MappingEngine {
        val p = single(Workspace(
            id = "w", name = "w", touchpadOverButtons = overButtons, stickyModifiers = sticky,
            mappings = listOf(
                touch("a", OutputTarget.Key(A)),
                touch("b", OutputTarget.Key(B)),
                touch("shift", OutputTarget.Key(HidUsage.LEFT_SHIFT)),
                touch("ctrl", OutputTarget.Key(HidUsage.LEFT_CTRL)),
                touch("wh", OutputTarget.Wheel(-1)),
            ),
        ))
        return MappingEngine(p, now).apply { touchpadPxPerDp = 2f; touchpadMinPulseNanos = 40 * MS }
    }

    private fun keys(e: MappingEngine) = e.snapshot().keyboard.keys
    private fun mods(e: MappingEngine) = e.snapshot().keyboard.modifiers

    @Test fun plainButtonsPressAtOnce() {
        var now = 0L
        val e = buttons(overButtons = false) { now }
        e.buttonDown(1, "a", 100f, 100f)
        assertEquals(listOf(A), keys(e))
        assertEquals(setOf("a"), e.pressedTouches())
        e.buttonMove(1, 400f, 100f) // moving does not matter
        now = 1000 * MS
        assertEquals(listOf(A), keys(e))
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
        assertEquals(Long.MAX_VALUE, e.touchpadDeadlineNanos())
    }

    @Test fun stillTapCommitsOnLiftAsAVisiblePulse() {
        var now = 0L
        val e = buttons(overButtons = true) { now }
        e.buttonDown(1, "a", 100f, 100f)
        assertTrue(keys(e).isEmpty())
        now = 50 * MS
        e.buttonMove(1, 115f, 100f) // 7.5 dp: inside the slop
        now = 80 * MS
        e.buttonUp(1)
        assertEquals(setOf("a"), e.pressedTouches())
        assertEquals(120 * MS, e.touchpadDeadlineNanos())
        now = 200 * MS // even a late snapshot sees the press once
        assertEquals(listOf(A), keys(e))
        assertTrue(keys(e).isEmpty())
        assertTrue(e.pressedTouches().isEmpty())
        assertEquals(Long.MAX_VALUE, e.touchpadDeadlineNanos())
    }

    @Test fun pulseLastsTheMinimumAfterBeingSeen() {
        var now = 0L
        val e = buttons(overButtons = true) { now }
        e.buttonDown(1, "a", 0f, 0f)
        now = 10 * MS; e.buttonUp(1)
        now = 11 * MS; assertEquals(listOf(A), keys(e))
        now = 30 * MS; assertEquals(listOf(A), keys(e))
        now = 50 * MS; assertTrue(keys(e).isEmpty())
    }

    @Test fun stillHoldPressesAfterTheHoldTime() {
        var now = 0L
        val e = buttons(overButtons = true) { now }
        e.buttonDown(1, "a", 100f, 100f)
        assertEquals(MappingEngine.BUTTON_HOLD_NS, e.touchpadDeadlineNanos())
        now = 299 * MS
        assertTrue(keys(e).isEmpty())
        now = 300 * MS
        assertEquals(listOf(A), keys(e)) // resolved by the snapshot itself
        now = 600 * MS
        e.buttonMove(1, 300f, 100f) // held keys stay keys
        assertEquals(listOf(A), keys(e))
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    @Test fun holdResolvesOnOtherInputToo() {
        var now = 0L
        val e = buttons(overButtons = true) { now }
        e.buttonDown(1, "wh", 100f, 100f)
        now = 350 * MS
        e.setTouch("x", true) // any input resolves the due hold and fires its event once
        assertEquals(-120, e.snapshot().mouse.wheel)
        assertEquals(setOf("wh", "x"), e.pressedTouches())
    }

    @Test fun movingFingerBecomesATouchpadFinger() {
        var now = 0L
        val e = buttons(overButtons = true) { now }
        e.buttonDown(1, "a", 100f, 100f)
        now = 20 * MS
        e.buttonMove(1, 125f, 100f) // 12.5 dp: past the slop
        assertTrue(e.pressedTouches().isEmpty())
        assertEquals(0, e.snapshot().mouse.dx) // starts from where it is: no jump
        now = 40 * MS
        e.buttonMove(1, 165f, 100f)
        val s = e.snapshot()
        assertTrue(s.mouse.dx > 0)
        assertTrue(s.keyboard.keys.isEmpty())
        now = 60 * MS
        e.buttonUp(1) // quick lift after a move: no click, no key
        now = 500 * MS
        repeat(3) {
            val t = e.snapshot()
            assertEquals(0, t.mouse.buttons)
            assertTrue(t.keyboard.keys.isEmpty())
        }
        assertEquals(Long.MAX_VALUE, e.touchpadDeadlineNanos())
    }

    @Test fun twoButtonFingersAtOnce() {
        var now = 0L
        val e = buttons(overButtons = true) { now }
        e.buttonDown(1, "a", 100f, 100f)
        e.buttonDown(2, "b", 300f, 100f)
        now = 50 * MS
        e.buttonUp(2)
        assertEquals(listOf(B), keys(e))
        now = 400 * MS
        assertEquals(listOf(A), keys(e))
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    @Test fun unknownFingersAreIgnoredAndWorkspaceChangeForgetsFingers() {
        var now = 0L
        val e = buttons(overButtons = false) { now }
        e.buttonMove(9, 1f, 1f); e.buttonUp(9)
        e.buttonDown(1, "a", 0f, 0f)
        e.workspaceIndex = 0
        assertTrue(keys(e).isEmpty())
        e.buttonUp(1)
        assertTrue(keys(e).isEmpty())
    }

    // ---- Sticky modifiers ----

    private fun tap(e: MappingEngine, pointer: Long, id: String, at: Long, set: (Long) -> Unit) {
        set(at); e.buttonDown(pointer, id, 0f, 0f)
        set(at + 50 * MS); e.buttonUp(pointer)
    }

    @Test fun tappedModifierAppliesToTheNextKeyOnly() {
        for (over in listOf(false, true)) {
            var now = 0L
            val e = buttons(overButtons = over, sticky = true) { now }
            tap(e, 1, "shift", 0, { now = it })
            assertEquals(setOf("shift"), e.latchedTouches())
            assertEquals(0x02, mods(e))
            // Next key: Shift + A, then both go. Plain buttons press on down, the others on lift.
            now = 1000 * MS
            e.buttonDown(2, "a", 0f, 0f)
            if (!over) checkShiftA(e)
            now = 1050 * MS
            e.buttonUp(2)
            if (over) checkShiftA(e)
            now = 1200 * MS
            val after = e.snapshot()
            assertEquals("over=$over", 0, after.keyboard.modifiers)
            assertTrue(after.keyboard.keys.isEmpty())
            assertTrue(e.latchedTouches().isEmpty())
            // And the key after that is plain.
            tap(e, 3, "b", 2000 * MS, { now = it })
            assertEquals(0, mods(e))
        }
    }

    private fun checkShiftA(e: MappingEngine) {
        val s = e.snapshot()
        assertEquals(0x02, s.keyboard.modifiers)
        assertEquals(listOf(A), s.keyboard.keys)
    }

    @Test fun twoLatchedModifiersBothApply() {
        var now = 0L
        val e = buttons(overButtons = true, sticky = true) { now }
        tap(e, 1, "ctrl", 0, { now = it })
        tap(e, 2, "shift", 1000 * MS, { now = it })
        assertEquals(setOf("ctrl", "shift"), e.latchedTouches())
        tap(e, 3, "a", 2000 * MS, { now = it })
        assertEquals(0x03, mods(e))
        now = 3000 * MS
        assertEquals(0, mods(e))
    }

    @Test fun doubleTapLocksUntilTappedAgain() {
        for (over in listOf(false, true)) {
            var now = 0L
            val e = buttons(overButtons = over, sticky = true) { now }
            tap(e, 1, "shift", 0, { now = it })
            tap(e, 1, "shift", 200 * MS, { now = it })
            assertEquals(setOf("shift"), e.latchedTouches())
            tap(e, 2, "a", 1000 * MS, { now = it })
            now = 2000 * MS
            assertEquals(0x02, mods(e))
            tap(e, 2, "b", 3000 * MS, { now = it })
            now = 4000 * MS
            assertEquals("over=$over", 0x02, mods(e))
            tap(e, 1, "shift", 5000 * MS, { now = it })
            assertTrue(e.latchedTouches().isEmpty())
            now = 6000 * MS
            assertEquals(0, mods(e))
        }
    }

    @Test fun slowSecondTapUnlatches() {
        var now = 0L
        val e = buttons(overButtons = false, sticky = true) { now }
        tap(e, 1, "shift", 0, { now = it })
        tap(e, 1, "shift", 1000 * MS, { now = it })
        assertTrue(e.latchedTouches().isEmpty())
        assertEquals(0, mods(e))
    }

    @Test fun heldModifierWorksAsNormalHold() {
        var now = 0L
        val e = buttons(overButtons = true, sticky = true) { now }
        e.buttonDown(1, "shift", 0f, 0f)
        now = 400 * MS
        assertEquals(0x02, mods(e))
        tap(e, 2, "a", 500 * MS, { now = it })
        assertEquals(0x02, mods(e))
        now = 700 * MS
        e.buttonUp(1)
        assertTrue(e.latchedTouches().isEmpty())
        now = 800 * MS
        assertEquals(0, mods(e))
    }

    @Test fun modifierFingerJoinsAChordBeforeTheHoldTime() {
        var now = 0L
        val e = buttons(overButtons = true, sticky = true) { now }
        e.buttonDown(1, "ctrl", 0f, 0f)
        tap(e, 2, "a", 50 * MS, { now = it }) // Ctrl still under 300 ms
        val s = e.snapshot()
        assertEquals(0x01, s.keyboard.modifiers)
        assertEquals(listOf(A), s.keyboard.keys)
        now = 200 * MS
        e.buttonUp(1)
        assertTrue(e.latchedTouches().isEmpty()) // used as a chord, not latched
        now = 300 * MS
        assertEquals(0, mods(e))
    }

    @Test fun chordWithImmediateButtonsDoesNotLatch() {
        var now = 0L
        val e = buttons(overButtons = false, sticky = true) { now }
        e.buttonDown(1, "shift", 0f, 0f)
        now = 30 * MS; e.buttonDown(2, "a", 0f, 0f)
        now = 60 * MS; e.buttonUp(2)
        now = 90 * MS; e.buttonUp(1)
        assertTrue(e.latchedTouches().isEmpty())
        assertEquals(0, mods(e))
    }

    @Test fun stickyOffMeansPlainModifiers() {
        var now = 0L
        val e = buttons(overButtons = false, sticky = false) { now }
        tap(e, 1, "shift", 0, { now = it })
        assertTrue(e.latchedTouches().isEmpty())
        assertEquals(0, mods(e))
    }

    @Test fun setTouchModifiersLatchToo() {
        var now = 0L
        val e = buttons(overButtons = false, sticky = true) { now }
        e.setTouch("shift", true); now = 50 * MS; e.setTouch("shift", false)
        assertEquals(setOf("shift"), e.latchedTouches())
        e.setTouch("a", true)
        assertEquals(0x02, mods(e))
        e.setTouch("a", false)
        assertEquals(0, mods(e))
    }

    @Test fun workspaceChangeClearsLatches() {
        var now = 0L
        val e = buttons(overButtons = false, sticky = true) { now }
        tap(e, 1, "shift", 0, { now = it })
        e.workspaceIndex = 0
        assertTrue(e.latchedTouches().isEmpty())
        assertEquals(0, mods(e))
    }

    @Test fun modifierControlNeedsOnlyModifierTargets() {
        var now = 0L
        val p = single(Workspace(
            id = "w", name = "w", stickyModifiers = true,
            mappings = listOf(
                touch("mixed", OutputTarget.Key(HidUsage.LEFT_SHIFT)),
                touch("mixed", OutputTarget.Mouse(MouseButton.LEFT)),
            ),
        ))
        val e = MappingEngine(p) { now }
        tap(e, 1, "mixed", 0, { now = it })
        assertTrue(e.latchedTouches().isEmpty())
    }
}
