package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.input.HidGamepad
import com.oleroma.gyrophone.core.model.AndroidAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidGamepadTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * A typical pad: 15 buttons + 1 pad bit, a hat (4 bits) + 4 pad bits, X Y Z Rz (8 bits each),
     * accelerator and brake (8 bits each). No report ids. 8 bytes a report.
     */
    private val pad = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, // Generic Desktop, Game Pad, Collection (Application)
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02, // buttons 1-15
        0x75, 0x01, 0x95, 0x01, 0x81, 0x03, // pad
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, // hat
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03, // pad
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02, // X Y Z Rz
        0x05, 0x02, 0x09, 0xC4, 0x09, 0xC5, 0x95, 0x02, 0x81, 0x02, // accelerator, brake
        0xC0,
    )

    private class Rec : HidGamepad.Sink {
        val keys = ArrayList<Pair<Int, Boolean>>()
        var axes: FloatArray? = null
        override fun key(keyCode: Int, down: Boolean) { keys += keyCode to down }
        override fun axes(values: FloatArray) { axes = values.copyOf() }
        fun axis(a: Int) = axes!![AndroidAxis.polled.indexOf(a)]
    }

    @Test fun mapsLikeAndroid() {
        val g = HidGamepad(pad)
        assertTrue(g.usable)
        assertEquals(setOf(96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 109, 108, 110, 106, 107), g.keys)
        assertEquals(
            setOf(AndroidAxis.HAT_X, AndroidAxis.HAT_Y, AndroidAxis.X, AndroidAxis.Y, AndroidAxis.Z, AndroidAxis.RZ, AndroidAxis.RTRIGGER, AndroidAxis.LTRIGGER),
            g.axes,
        )
    }

    @Test fun buttonsChangeOnce() {
        val g = HidGamepad(pad)
        val r = Rec()
        // Button 1 (A) and 11 (Select), hat centred (8 = null), sticks centred, triggers off.
        g.decode(bytes(0x01, 0x04, 0x08, 0x80, 0x80, 0x80, 0x80, 0x00, 0x00), 9, r)
        assertEquals(listOf(96 to true, 109 to true), r.keys)
        r.keys.clear()
        g.decode(bytes(0x01, 0x04, 0x08, 0x80, 0x80, 0x80, 0x80, 0x00, 0x00), 9, r)
        assertTrue(r.keys.isEmpty())
        g.decode(bytes(0x00, 0x04, 0x08, 0x80, 0x80, 0x80, 0x80, 0x00, 0x00), 9, r)
        assertEquals(listOf(96 to false), r.keys)
    }

    @Test fun axesScaleAndDeadzone() {
        val g = HidGamepad(pad)
        val r = Rec()
        // Hat 2 = right; X full right, Y full up, Z centre, Rz inside the flat; accelerator full, brake half.
        g.decode(bytes(0x00, 0x00, 0x02, 0xFF, 0x00, 0x80, 0x88, 0xFF, 0x80), 9, r)
        assertEquals(1f, r.axis(AndroidAxis.HAT_X), 0f)
        assertEquals(0f, r.axis(AndroidAxis.HAT_Y), 0f)
        assertEquals(1f, r.axis(AndroidAxis.X), 1e-4f)
        assertEquals(-1f, r.axis(AndroidAxis.Y), 1e-4f)
        assertEquals(0f, r.axis(AndroidAxis.Z), 0f)
        assertEquals(0f, r.axis(AndroidAxis.RZ), 0f)
        assertEquals(1f, r.axis(AndroidAxis.RTRIGGER), 1e-4f)
        // 128/255 = 0.502 past a flat of 15/255.
        assertEquals((128f / 255f - 15f / 255f) / (1f - 15f / 255f), r.axis(AndroidAxis.LTRIGGER), 1e-4f)
        assertEquals(Float.NaN, r.axis(AndroidAxis.RX), 0f)
        // Hat null state: centred.
        g.decode(bytes(0x00, 0x00, 0x08, 0xFF, 0x00, 0x80, 0x88, 0xFF, 0x80), 9, r)
        assertEquals(0f, r.axis(AndroidAxis.HAT_X), 0f)
        // Hat 7 = up left.
        g.decode(bytes(0x00, 0x00, 0x07, 0xFF, 0x00, 0x80, 0x88, 0xFF, 0x80), 9, r)
        assertEquals(-1f, r.axis(AndroidAxis.HAT_X), 0f)
        assertEquals(-1f, r.axis(AndroidAxis.HAT_Y), 0f)
    }

    @Test fun reportIdsAndOtherReportsIgnored() {
        // Report 1: 8 buttons; report 2: X (8 bits). A joystick: buttons are BUTTON_1..
        val d = bytes(
            0x05, 0x01, 0x09, 0x04, 0xA1, 0x01,
            0x85, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x08, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x85, 0x02, 0x05, 0x01, 0x09, 0x30, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
            0xC0,
        )
        val g = HidGamepad(d)
        assertEquals(8, g.keys.size)
        assertTrue(188 in g.keys)
        val r = Rec()
        g.decode(bytes(0x01, 0x02), 2, r)
        assertEquals(listOf(189 to true), r.keys)
        g.decode(bytes(0x02, 0x7F), 2, r)
        assertEquals(1f, r.axis(AndroidAxis.X), 1e-4f)
        g.decode(bytes(0x02, 0x81), 2, r)
        assertEquals(-1f, r.axis(AndroidAxis.X), 1e-4f)
        assertEquals(1, r.keys.size)
    }

    @Test fun aMouseIsNotAPad() {
        val mouse = bytes(
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
            0xC0, 0xC0,
        )
        assertFalse(HidGamepad(mouse).usable)
    }

    @Test fun releaseLetsGo() {
        val g = HidGamepad(pad)
        val r = Rec()
        g.decode(bytes(0x02, 0x00, 0x08, 0xFF, 0x80, 0x80, 0x80, 0x00, 0x00), 9, r)
        r.keys.clear()
        g.release(r)
        assertEquals(listOf(97 to false), r.keys)
        assertEquals(0f, r.axis(AndroidAxis.X), 0f)
    }
}
