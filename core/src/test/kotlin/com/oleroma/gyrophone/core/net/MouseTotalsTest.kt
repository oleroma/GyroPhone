package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.hid.MouseState
import org.junit.Assert.assertEquals
import org.junit.Test

class MouseTotalsTest {
    @Test fun accumulatesAndWraps() {
        val t = MouseTotals()
        t.add(MouseState(dx = 10, dy = -4, wheel = 1))
        t.add(MouseState(dx = 5, dy = -5))
        assertEquals(15, t.x); assertEquals(-9, t.y); assertEquals(1, t.wheel)
        t.add(Int.MAX_VALUE, 0, 0)
        assertEquals(Int.MIN_VALUE + 14, t.x)
        // The host's wrapping difference still recovers the real delta.
        val before = t.x
        t.add(100, 0, 0)
        assertEquals(100, t.x - before)
        t.reset()
        assertEquals(0, t.x); assertEquals(0, t.y); assertEquals(0, t.wheel)
    }
}
