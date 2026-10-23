package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.net.MouseTotals
import com.oleroma.gyrophone.core.net.StateFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical-device paths of the engine: key edges, axis batches and cancels, triggers, keyboard output. */
class EngineDevicesTest {
    private val xbox = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val A = 1 shl GamepadButton.A.bit

    private fun key(code: Int, target: OutputTarget) = Mapping(InputSource.Key(code), target)
    private fun axis(a: Int, target: OutputTarget) = Mapping(InputSource.Axis(a), target)

    private fun engine(vararg mappings: Mapping, gyro: GyroSettings = GyroSettings()) = MappingEngine(
        single(
            Workspace(
                id = "w", name = "w", gyro = gyro,
                pads = listOf(AttachedPad(id = "xb", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13), mappings = mappings.toList())),
            ),
        ),
    )

    private fun standard() = engine(*KnownLayouts.standardControllerMappings().toTypedArray())

    // ---- Every key edge reaches a snapshot ----

    @Test fun tapBetweenSnapshotsShowsOnce() {
        val e = engine(key(AndroidKey.BUTTON_A, OutputTarget.Pad(GamepadButton.A)))
        e.snapshot()
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        e.setKey(xbox, AndroidKey.BUTTON_A, false)
        assertEquals(A, e.snapshot().gamepad.buttons)
        assertEquals(0, e.snapshot().gamepad.buttons)
    }

    @Test fun releaseAndRepressBetweenSnapshotsShowsTheGap() {
        val e = engine(key(AndroidKey.BUTTON_A, OutputTarget.Pad(GamepadButton.A)))
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        assertEquals(A, e.snapshot().gamepad.buttons)
        e.setKey(xbox, AndroidKey.BUTTON_A, false)
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        assertEquals(0, e.snapshot().gamepad.buttons)
        assertEquals(A, e.snapshot().gamepad.buttons)
        assertEquals(A, e.snapshot().gamepad.buttons)
    }

    @Test fun heldAndReleasedKeysAreNotStretched() {
        val e = engine(key(AndroidKey.BUTTON_A, OutputTarget.Pad(GamepadButton.A)))
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        assertEquals(A, e.snapshot().gamepad.buttons)
        assertEquals(A, e.snapshot().gamepad.buttons)
        e.setKey(xbox, AndroidKey.BUTTON_A, false)
        assertEquals(0, e.snapshot().gamepad.buttons)
        // A reset drops a pending tap too.
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        e.setKey(xbox, AndroidKey.BUTTON_A, false)
        e.reset()
        assertEquals(0, e.snapshot().gamepad.buttons)
    }

    @Test fun keyboardOutputTapShowsOnce() {
        val e = engine(key(AndroidKey.BUTTON_B, OutputTarget.Key(0x2C)))
        e.setKey(xbox, AndroidKey.BUTTON_B, true)
        e.setKey(xbox, AndroidKey.BUTTON_B, false)
        assertEquals(listOf(0x2C), e.snapshot().keyboard.keys)
        assertEquals(emptyList<Int>(), e.snapshot().keyboard.keys)
    }

    @Test fun legacyAnyDeviceTapShowsOnce() {
        val e = engine(key(AndroidKey.BUTTON_A, OutputTarget.Pad(GamepadButton.A)))
        e.setKey(AndroidKey.BUTTON_A, true)
        e.setKey(AndroidKey.BUTTON_A, false)
        assertEquals(A, e.snapshot().gamepad.buttons)
        assertEquals(0, e.snapshot().gamepad.buttons)
    }

    @Test fun toggleFiresOnRealEdgesOnly() {
        val e = engine(key(AndroidKey.BUTTON_X, OutputTarget.GyroToggle), gyro = GyroSettings(mode = GyroMode.OFF))
        e.snapshot()
        e.setKey(xbox, AndroidKey.BUTTON_X, true)
        e.setKey(xbox, AndroidKey.BUTTON_X, false)
        assertTrue(e.gyroActive)
        e.snapshot()
        e.snapshot()
        assertTrue(e.gyroActive)
    }

    // ---- Axis batches and cancels ----

    @Test fun clearAxesZeroesAxesAndKeepsKeys() {
        val e = standard()
        var changes = 0
        e.setAxis(xbox, AndroidAxis.X, 0.8f)
        e.setAxis(xbox, AndroidAxis.RTRIGGER, 0.5f)
        e.setKey(xbox, AndroidKey.BUTTON_A, true)
        assertTrue(e.snapshot().gamepad.lx > 0)
        e.onChange = Runnable { changes++ }
        e.clearAxes(xbox.id)
        assertEquals(1, changes)
        val s = e.snapshot()
        assertEquals(0, s.gamepad.lx)
        assertEquals(0, s.gamepad.rt)
        assertEquals(A, s.gamepad.buttons)
        // Nothing left to clear, or an unknown device: no wake.
        e.clearAxes(xbox.id)
        e.clearAxes(99)
        assertEquals(1, changes)
    }

    @Test fun setAxesAppliesABatchWithOneWake() {
        val e = standard()
        var changes = 0
        e.onChange = Runnable { changes++ }
        val ids = intArrayOf(AndroidAxis.X, AndroidAxis.Y, AndroidAxis.Z, AndroidAxis.RZ, AndroidAxis.RTRIGGER)
        val values = floatArrayOf(1f, -0.5f, 0.25f, -1f, 0.5f)
        e.setAxes(xbox, ids, values, ids.size)
        assertEquals(1, changes)
        val g = e.snapshot().gamepad
        assertEquals(32767, g.lx)
        assertEquals(-16383, g.ly)
        assertEquals(8191, g.rx)
        assertEquals(-32767, g.ry)
        assertEquals(511, g.rt)
        // Same values again: nothing changed, no wake.
        e.setAxes(xbox, ids, values, ids.size)
        assertEquals(1, changes)
        // Only the first n entries count.
        values[0] = 0f; values[4] = 0f
        e.setAxes(xbox, ids, values, 1)
        assertEquals(2, changes)
        val h = e.snapshot().gamepad
        assertEquals(0, h.lx)
        assertEquals(511, h.rt)
    }

    @Test fun setAxesFiresAWheelOncePerEngagement() {
        val e = engine(
            axis(AndroidAxis.HAT_Y, OutputTarget.Wheel(1)),
            Mapping(InputSource.Axis(AndroidAxis.RZ, AxisHalf.POSITIVE), OutputTarget.Wheel(1)),
        )
        e.setAxes(xbox, intArrayOf(AndroidAxis.HAT_Y, AndroidAxis.RZ), floatArrayOf(1f, 1f), 2)
        assertEquals(120, e.snapshot().mouse.wheel)
        e.setAxes(xbox, intArrayOf(AndroidAxis.HAT_Y, AndroidAxis.RZ), floatArrayOf(1f, 0.9f), 2)
        assertEquals(0, e.snapshot().mouse.wheel)
    }

    // ---- Analog triggers ----

    @Test fun analogTriggerSurvivesDigitalL2() {
        val e = standard()
        val pad = xbox.copy(analogLeftTrigger = true, analogRightTrigger = true)
        e.setAxis(pad, AndroidAxis.LTRIGGER, 0.3f)
        e.setKey(pad, AndroidKey.BUTTON_L2, true)
        e.setAxis(pad, AndroidAxis.GAS, 0.5f)
        e.setKey(pad, AndroidKey.BUTTON_R2, true)
        val g = e.snapshot().gamepad
        assertEquals(306, g.lt)
        assertEquals(511, g.rt)
    }

    @Test fun digitalTriggerStillPullsFully() {
        val e = standard()
        e.setKey(xbox, AndroidKey.BUTTON_L2, true)
        assertEquals(1023, e.snapshot().gamepad.lt)
        // Only the side with an analog axis prefers it.
        val e2 = standard()
        val leftOnly = xbox.copy(analogLeftTrigger = true)
        e2.setKey(leftOnly, AndroidKey.BUTTON_L2, true)
        e2.setKey(leftOnly, AndroidKey.BUTTON_R2, true)
        val g = e2.snapshot().gamepad
        assertEquals(0, g.lt)
        assertEquals(1023, g.rt)
    }

    @Test fun otherKeysBoundToATriggerStillPull() {
        val e = engine(*(KnownLayouts.standardControllerMappings() + key(AndroidKey.BUTTON_R1, OutputTarget.PadAxis(GamepadAxis.RT))).toTypedArray())
        val pad = xbox.copy(analogLeftTrigger = true, analogRightTrigger = true)
        e.setKey(pad, AndroidKey.BUTTON_R1, true)
        assertEquals(1023, e.snapshot().gamepad.rt)
    }

    @Test fun swappedTriggerBindingKeepsTheAxis() {
        // The left trigger group bound to RT: the L2 key still duplicates the left axis.
        val e = engine(
            axis(AndroidAxis.LTRIGGER, OutputTarget.PadAxis(GamepadAxis.RT)),
            key(AndroidKey.BUTTON_L2, OutputTarget.PadAxis(GamepadAxis.RT)),
        )
        val pad = xbox.copy(analogLeftTrigger = true)
        e.setAxis(pad, AndroidAxis.LTRIGGER, 0.3f)
        e.setKey(pad, AndroidKey.BUTTON_L2, true)
        val g = e.snapshot().gamepad
        assertEquals(306, g.rt)
        assertEquals(0, g.lt)
    }

    @Test fun l2KeyAloneStillPullsWhenItsAxisIsUnbound() {
        val e = engine(key(AndroidKey.BUTTON_L2, OutputTarget.PadAxis(GamepadAxis.LT)))
        e.setKey(xbox.copy(analogLeftTrigger = true), AndroidKey.BUTTON_L2, true)
        assertEquals(1023, e.snapshot().gamepad.lt)
    }

    @Test fun l2MappedElsewhereIsUnaffected() {
        val e = engine(key(AndroidKey.BUTTON_L2, OutputTarget.Pad(GamepadButton.A)))
        e.setKey(xbox.copy(analogLeftTrigger = true), AndroidKey.BUTTON_L2, true)
        assertEquals(A, e.snapshot().gamepad.buttons)
    }

    // ---- RX / RY ----

    @Test fun rxRyArePolledAndMappable() {
        assertTrue(AndroidAxis.RX in AndroidAxis.polled)
        assertTrue(AndroidAxis.RY in AndroidAxis.polled)
        assertEquals(12, AndroidAxis.RX)
        assertEquals(13, AndroidAxis.RY)
        val e = engine(axis(AndroidAxis.RX, OutputTarget.PadAxis(GamepadAxis.RX)), axis(AndroidAxis.RY, OutputTarget.PadAxis(GamepadAxis.RY)))
        e.setAxes(xbox, intArrayOf(AndroidAxis.RX, AndroidAxis.RY), floatArrayOf(1f, -1f), 2)
        val g = e.snapshot().gamepad
        assertEquals(32767, g.rx)
        assertEquals(-32767, g.ry)
    }

    @Test fun learnComparesAgainstTheRestingValue() {
        // A trigger on a centred axis rests at -1: not learned until it moves.
        assertNull(AndroidAxis.learnedHalf(AndroidAxis.Z, -1f, rest = -1f))
        assertNull(AndroidAxis.learnedHalf(AndroidAxis.Z, -0.5f, rest = -1f))
        assertEquals(AxisHalf.POSITIVE, AndroidAxis.learnedHalf(AndroidAxis.Z, -0.2f, rest = -1f))
        assertEquals(AxisHalf.NEGATIVE, AndroidAxis.learnedHalf(AndroidAxis.X, -0.7f, rest = 0f))
        assertEquals(AxisHalf.POSITIVE, AndroidAxis.learnedHalf(AndroidAxis.X, 0.7f, rest = 0f))
        assertEquals(AxisHalf.FULL, AndroidAxis.learnedHalf(AndroidAxis.LTRIGGER, 0.7f, rest = 0f))
        assertNull(AndroidAxis.learnedHalf(AndroidAxis.X, 0.5f, rest = 0f))
    }

    // ---- Flat region ----

    @Test fun flatRegionRescalesWithoutAJump() {
        val flat = 0.125f
        assertEquals(0f, AndroidAxis.deadzone(AndroidAxis.X, 0.1f, flat), 0f)
        assertEquals(0f, AndroidAxis.deadzone(AndroidAxis.X, -0.125f, flat), 0f)
        assertEquals(0.001f / 0.875f, AndroidAxis.deadzone(AndroidAxis.X, 0.126f, flat), 1e-5f)
        assertEquals(1f, AndroidAxis.deadzone(AndroidAxis.X, 1f, flat), 1e-6f)
        assertEquals(-1f, AndroidAxis.deadzone(AndroidAxis.Y, -1f, flat), 1e-6f)
        assertEquals(-0.5f, AndroidAxis.deadzone(AndroidAxis.Y, -(0.125f + 0.4375f), flat), 1e-6f)
        assertEquals(0.5f, AndroidAxis.deadzone(AndroidAxis.RTRIGGER, 0.5625f, flat), 1e-6f)
        assertEquals(0.3f, AndroidAxis.deadzone(AndroidAxis.X, 0.3f, 0f), 0f)
        // Hat axes are -1 / 0 / 1: only the rest noise goes.
        assertEquals(1f, AndroidAxis.deadzone(AndroidAxis.HAT_X, 1f, 0.2f), 0f)
        assertEquals(0f, AndroidAxis.deadzone(AndroidAxis.HAT_Y, 0.1f, 0.2f), 0f)
    }

    // ---- Keyboard output beyond six keys ----

    @Test fun sevenHeldKeysReachTheWifiFrame() {
        val codes = intArrayOf(
            AndroidKey.BUTTON_A, AndroidKey.BUTTON_B, AndroidKey.BUTTON_X, AndroidKey.BUTTON_Y,
            AndroidKey.BUTTON_L1, AndroidKey.BUTTON_R1, AndroidKey.BUTTON_START,
        )
        val e = engine(*codes.mapIndexed { i, c -> key(c, OutputTarget.Key(4 + i)) }.toTypedArray(), key(AndroidKey.BUTTON_SELECT, OutputTarget.Key(HidUsage.LEFT_SHIFT)))
        for (c in codes) e.setKey(xbox, c, true)
        e.setKey(xbox, AndroidKey.BUTTON_SELECT, true)
        val s = e.snapshot()
        // The Bluetooth report keeps its six keys in press order.
        assertEquals((4..9).toList(), s.keyboard.keys)
        assertEquals((4..10).toList(), s.allKeys)
        val f = StateFrame().apply { fill(s, MouseTotals(), false) }
        assertEquals((4..10).toList(), f.heldKeys())
        assertTrue(f.isKeyHeld(HidUsage.LEFT_SHIFT))
        // Six or fewer: the same list.
        e.setKey(xbox, AndroidKey.BUTTON_START, false)
        val t = e.snapshot()
        assertTrue(t.allKeys === t.keyboard.keys)
        assertFalse(StateFrame().apply { fill(t, MouseTotals(), false) }.isKeyHeld(10))
    }
}
