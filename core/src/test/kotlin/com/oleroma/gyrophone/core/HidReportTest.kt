package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HidReportTest {
    @Test fun descriptorCollectionsBalance() {
        val b = HidDescriptor.bytes.map { it.toInt() and 0xFF }
        // Walk short items and count Collection / End Collection.
        var i = 0; var depth = 0
        while (i < b.size) {
            val prefix = b[i]
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            if (prefix and 0xFC == 0xA0) depth++
            if (prefix == 0xC0) depth--
            i += 1 + size
        }
        assertEquals(b.size, i)
        assertEquals(0, depth)
    }

    @Test fun descriptorReportSizesMatchEncoders() {
        val b = HidDescriptor.bytes.map { it.toInt() and 0xFF }
        val input = HashMap<Int, Int>(); val feature = HashMap<Int, Int>()
        var i = 0; var id = 0; var size = 0; var count = 0
        while (i < b.size) {
            val prefix = b[i]
            val n = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            val v = if (n >= 1) b[i + 1] else 0
            when (prefix) {
                0x85 -> id = v
                0x75 -> size = v
                0x95 -> count = v
                0x81 -> input[id] = (input[id] ?: 0) + size * count
                0xB1 -> feature[id] = (feature[id] ?: 0) + size * count
            }
            i += 1 + n
        }
        assertEquals(HidDescriptor.GAMEPAD_REPORT_SIZE * 8, input[HidDescriptor.REPORT_ID_GAMEPAD])
        assertEquals(HidDescriptor.KEYBOARD_REPORT_SIZE * 8, input[HidDescriptor.REPORT_ID_KEYBOARD])
        assertEquals(HidDescriptor.MOUSE_REPORT_SIZE * 8, input[HidDescriptor.REPORT_ID_MOUSE])
        assertEquals(HidDescriptor.MOUSE_FEATURE_SIZE * 8, feature[HidDescriptor.REPORT_ID_MOUSE_FEATURE])
    }

    @Test fun gamepadEncoding() {
        val r = GamepadState(buttons = 0x8001, hat = Hat.LEFT, lx = -32767, ly = 32767, rt = 1023).encode()
        assertEquals(HidDescriptor.GAMEPAD_REPORT_SIZE, r.size)
        assertEquals(0x01.toByte(), r[0]); assertEquals(0x80.toByte(), r[1])
        assertEquals(Hat.LEFT.toByte(), r[2])
        assertEquals(0x01.toByte(), r[3]); assertEquals(0x80.toByte(), r[4])
        assertEquals(0xFF.toByte(), r[5]); assertEquals(0x7F.toByte(), r[6])
        assertEquals(0xFF.toByte(), r[13]); assertEquals(0x03.toByte(), r[14])
    }

    @Test fun keyboardAndMouseEncoding() {
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0x05, 0, 0, 0, 0),
            KeyboardState(modifiers = 0x02, keys = listOf(0x04, 0x05)).encode())
        assertArrayEquals(byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0x02, 0x00, 0x78, 0x00, 0xFF.toByte(), 0xFF.toByte()),
            MouseState.report(buttons = 1, dx = -1, dy = 2, wheel = 120, pan = -1))
    }

    @Test fun resolutionMultiplierBits() {
        assertEquals(false, HidDescriptor.wheelHiRes(0))
        assertEquals(true, HidDescriptor.wheelHiRes(0x01))
        assertEquals(false, HidDescriptor.panHiRes(0x01))
        assertEquals(true, HidDescriptor.panHiRes(0x04))
    }

    @Test fun hatFromDpad() {
        assertEquals(Hat.CENTER, Hat.fromDpad(false, false, false, false))
        assertEquals(Hat.UP_RIGHT, Hat.fromDpad(true, false, false, true))
        assertEquals(Hat.CENTER, Hat.fromDpad(true, true, false, false))
    }
}
