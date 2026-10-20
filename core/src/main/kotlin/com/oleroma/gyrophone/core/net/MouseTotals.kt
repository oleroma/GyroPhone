package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.hid.MouseState

/**
 * Cumulative mouse motion since the session began. STATE carries these totals instead of
 * deltas, so any packet that arrives repairs the loss of all earlier ones. Sums wrap like the
 * i32 on the wire; the host takes wrapping differences.
 */
class MouseTotals {
    var x = 0; private set
    var y = 0; private set
    var wheel = 0; private set

    fun add(m: MouseState) = add(m.dx, m.dy, m.wheel)

    fun add(dx: Int, dy: Int, dWheel: Int) {
        x += dx; y += dy; wheel += dWheel
    }

    fun reset() {
        x = 0; y = 0; wheel = 0
    }
}
