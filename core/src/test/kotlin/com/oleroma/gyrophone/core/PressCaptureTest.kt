package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PressCombo
import com.oleroma.gyrophone.core.model.PressTargets
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.PcPress
import com.oleroma.gyrophone.core.net.PcPresses
import com.oleroma.gyrophone.core.net.CaptureListen
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.net.WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressCaptureTest {
    @Test fun aComboEndsOnceEverythingIsLetGo() {
        val c = PressCombo()
        c.press("ctrl", OutputTarget.Key(0xE0))
        c.press("c", OutputTarget.Key(0x06))
        assertFalse(c.release("c"))
        assertTrue(c.holding)
        assertTrue(c.release("ctrl"))
        assertEquals(listOf(OutputTarget.Key(0xE0), OutputTarget.Key(0x06)), c.targets)
    }

    @Test fun aKeyWithNothingToMapAddsNothing() {
        val c = PressCombo()
        c.press("odd", null)
        assertFalse(c.release("odd"))
        assertFalse(c.release("never pressed"))
        c.press("a", OutputTarget.Key(0x04))
        c.press("a", OutputTarget.Key(0x04))
        assertTrue(c.release("a"))
        assertEquals(listOf(OutputTarget.Key(0x04)), c.targets)
    }

    @Test fun phoneKeysMapByDevice() {
        assertEquals(OutputTarget.Pad(GamepadButton.A), PressTargets.ofPhoneKey(AndroidKey.BUTTON_A, pad = true))
        assertEquals(OutputTarget.Pad(GamepadButton.DPAD_UP), PressTargets.ofPhoneKey(AndroidKey.DPAD_UP, pad = true))
        // A keyboard's up arrow shares the D-pad's code.
        assertEquals(OutputTarget.Key(0x52), PressTargets.ofPhoneKey(AndroidKey.DPAD_UP, pad = false))
        assertEquals(OutputTarget.PadAxis(GamepadAxis.LT), PressTargets.ofPhoneKey(AndroidKey.BUTTON_L2, pad = true))
        assertEquals(OutputTarget.Mouse(MouseButton.BACK), PressTargets.ofPhoneKey(AndroidKey.BACK, pad = false, mouse = true))
        assertEquals(OutputTarget.Key(0x04), PressTargets.ofPhoneKey(29, pad = false))
    }

    @Test fun phoneAxesMapToThePads() {
        assertEquals(OutputTarget.PadAxis(GamepadAxis.LX, -1f), PressTargets.ofPhoneAxis(AndroidAxis.X, false, zStick = true))
        assertEquals(OutputTarget.PadAxis(GamepadAxis.RY), PressTargets.ofPhoneAxis(AndroidAxis.RZ, true, zStick = true))
        assertNull(PressTargets.ofPhoneAxis(AndroidAxis.RZ, true, zStick = false))
        assertEquals(OutputTarget.PadAxis(GamepadAxis.RT), PressTargets.ofPhoneAxis(AndroidAxis.GAS, true, zStick = false))
        assertEquals(OutputTarget.Pad(GamepadButton.DPAD_LEFT), PressTargets.ofPhoneAxis(AndroidAxis.HAT_X, false, zStick = true))
    }

    @Test fun pcPressesMap() {
        assertEquals(OutputTarget.Key(0x2C), PressTargets.ofPc(PcPress(PcPress.KEY, 0x2C)))
        assertEquals(OutputTarget.Mouse(MouseButton.FORWARD), PressTargets.ofPc(PcPress(PcPress.MOUSE, 4)))
        assertEquals(OutputTarget.Wheel(-1), PressTargets.ofPc(PcPress(PcPress.WHEEL, 0, -1)))
        assertEquals(OutputTarget.Pad(GamepadButton.Y), PressTargets.ofPc(PcPress(PcPress.PAD_BUTTON, 0x8000)))
        assertEquals(OutputTarget.PadAxis(GamepadAxis.RY, -1f), PressTargets.ofPc(PcPress(PcPress.PAD_AXIS, 3, -1)))
        assertEquals(OutputTarget.PadAxis(GamepadAxis.LT), PressTargets.ofPc(PcPress(PcPress.PAD_AXIS, 4, 1)))
        assertNull(PressTargets.ofPc(PcPress(PcPress.PAD_BUTTON, 0x0800)))
    }

    @Test fun captureMessagesRoundTrip() {
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        assertEquals(4, WireCodec.encodeSealedPlain(CaptureListen(1), buf, 0))
        assertEquals(CaptureListen(1), WireCodec.decodeSealedPlain(PacketType.CAPTURE, buf, 0, 4))
        val held = PcPresses(listOf(PcPress(PcPress.KEY, 0x04), PcPress(PcPress.PAD_AXIS, 1, -1), PcPress(PcPress.WHEEL, 0, 1)))
        assertEquals(64, WireCodec.encodeSealedPlain(held, buf, 0))
        // The same bytes the PC sends (PressCaptureTests.CapturedRoundTrips).
        assertEquals("03000100040005ff01000301", buf.copyOfRange(0, 12).joinToString("") { "%02x".format(it) })
        assertEquals(held, WireCodec.decodeSealedPlain(PacketType.CAPTURED, buf, 0, 64))
    }
}
