package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateFrameTest {
    private val totals = MouseTotals()

    @Test fun modifierByteIsBitmapByte28() {
        val s = HidState(keyboard = KeyboardState(modifiers = 0x83, keys = listOf(0x1A, 0x04)))
        val f = StateFrame().apply { fill(s, totals, false) }
        assertEquals(0x83, f.keyBitmap[28].toInt() and 0xFF)
        assertEquals(0x83, f.modifiers)
        assertTrue(f.isKeyHeld(HidUsage.LEFT_CTRL))
        assertTrue(f.isKeyHeld(HidUsage.LEFT_SHIFT))
        assertTrue(f.isKeyHeld(HidUsage.RIGHT_GUI))
        assertEquals(listOf(0x04, 0x1A), f.heldKeys())
        val plain = ByteArray(Wire.STATE_PLAIN)
        WireCodec.encodeStatePlain(f, plain)
        assertEquals(0x83, plain[22 + 28].toInt() and 0xFF)
    }

    @Test fun fillCopiesPadAndClamps() {
        val g = GamepadState(buttons = 0x8003, hat = Hat.UP_RIGHT, lx = -40000, ly = 5, rx = 7, ry = -7, lt = 2000, rt = 3)
        totals.add(3, 4, 5 * 120)
        val f = StateFrame().apply { fill(HidState(gamepad = g, mouse = MouseState(buttons = 0x11)), totals, true) }
        assertEquals(StateFlags.GYRO_ACTIVE, f.flags)
        assertEquals(0x8003, f.buttons)
        assertEquals(Hat.UP_RIGHT, f.hat)
        assertEquals(-32767, f.lx)
        assertEquals(1023, f.lt)
        assertEquals(0x11, f.mouseButtons)
        assertEquals(3, f.mouseTotalX); assertEquals(4, f.mouseTotalY); assertEquals(5, f.wheelTotal)
        assertEquals(PacketType.STATE, WireCodec.stateType(f))
        // A host that takes STATE_HIRES gets the wheel in 1/120 notch.
        val h = StateFrame().apply { fill(HidState(), totals, false, hires = true) }
        assertEquals(600, h.wheelTotal)
        assertEquals(PacketType.STATE_HIRES, WireCodec.stateType(h))
    }

    /** Same bytes as WireCodecTests.StateHiresLayout in the companion. */
    @Test fun stateHiresLayout() {
        val f = StateFrame().apply {
            phoneTimeUs = 0x01020304; flags = 1; buttons = 0x8001; hat = 2; mouseButtons = 1
            lx = -1; ly = 2; rx = -3; ry = 4; lt = 5; rt = 1023
            mouseTotalX = 10; mouseTotalY = -20; wheelTotal = 300; hwheelTotal = -45; hires = true
            setKey(0x04)
            keyBitmap[StateFrame.MODIFIER_BYTE] = 0x04
        }
        val plain = ByteArray(Wire.STATE_HIRES_PLAIN)
        assertEquals(Wire.STATE_HIRES_PLAIN, WireCodec.encodeStatePlain(f, plain))
        assertEquals("04030201010001800201ffff0200fdff04000500ff0310000000000000000000000000000000000000000000000000000000040000000a000000ecffffff2c010000d3ffffff", plain.hex())
        val back = WireCodec.decodeSealedPlain(PacketType.STATE_HIRES, plain, 0, plain.size) as StateFrame
        assertTrue(back.hires)
        assertEquals(300, back.wheelTotal); assertEquals(-45, back.hwheelTotal)
        assertEquals(Wire.STATE_HIRES_PLAIN + Wire.HEADER + Wire.GCM_TAG, Wire.sizeOf(PacketType.STATE_HIRES))
    }

    @Test fun reservedUsagesAreNeverSet() {
        val f = StateFrame().apply { fill(HidState(keyboard = KeyboardState(keys = listOf(0, 1, 2, 3))), totals, false) }
        assertTrue(f.keyBitmap.all { it.toInt() == 0 })
    }

    @Test fun discreteEqualsIgnoresTimeFlagsAndTotals() {
        val a = StateFrame().apply { fill(HidState(keyboard = KeyboardState(keys = listOf(0x2C))), totals, false) }
        val b = StateFrame().apply { copyFrom(a) }
        b.phoneTimeUs = 99; b.flags = 7; b.mouseTotalX = 1000
        assertTrue(a.discreteEquals(b))
        assertFalse(a.totalsEqual(b))
        b.setKey(0x04)
        assertFalse(a.discreteEquals(b))
        b.copyFrom(a)
        b.hat = Hat.DOWN
        assertFalse(a.discreteEquals(b))
    }

    @Test fun neutralizeKeepsTotals() {
        totals.add(9, 9, 9)
        val f = StateFrame().apply { fill(HidState(gamepad = GamepadState(buttons = 1), keyboard = KeyboardState(1, listOf(4))), totals, false) }
        f.neutralize()
        assertTrue(f.discreteEquals(StateFrame()))
        assertEquals(9, f.mouseTotalX)
    }
}
