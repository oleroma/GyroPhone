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
        totals.add(3, 4, 5)
        val f = StateFrame().apply { fill(HidState(gamepad = g, mouse = MouseState(buttons = 0x11)), totals, true) }
        assertEquals(StateFlags.GYRO_ACTIVE, f.flags)
        assertEquals(0x8003, f.buttons)
        assertEquals(Hat.UP_RIGHT, f.hat)
        assertEquals(-32767, f.lx)
        assertEquals(1023, f.lt)
        assertEquals(0x11, f.mouseButtons)
        assertEquals(3, f.mouseTotalX); assertEquals(4, f.mouseTotalY); assertEquals(5, f.wheelTotal)
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
