package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.hid.MouseState

/**
 * Cumulative mouse motion since the session began. STATE carries these totals instead of
 * deltas, so any packet that arrives repairs the loss of all earlier ones. Sums wrap like the
 * i32 on the wire; the host takes wrapping differences.
 *
 * [wheel] and [hwheel] are in 1/120 notch (STATE_HIRES); [wheelNotches] is the same wheel in
 * whole notches for a host without high-resolution scrolling (plain STATE).
 */
class MouseTotals {
    var x = 0; private set
    var y = 0; private set
    var wheel = 0; private set
    var hwheel = 0; private set
    var wheelNotches = 0; private set
    private var notchRem = 0

    fun add(m: MouseState) = add(m.dx, m.dy, m.wheel, m.hwheel)

    fun add(dx: Int, dy: Int, dWheel: Int, dHwheel: Int = 0) {
        x += dx; y += dy; wheel += dWheel; hwheel += dHwheel
        notchRem += dWheel
        val n = notchRem / MouseState.WHEEL_UNIT
        notchRem -= n * MouseState.WHEEL_UNIT
        wheelNotches += n
    }

    fun reset() {
        x = 0; y = 0; wheel = 0; hwheel = 0; wheelNotches = 0; notchRem = 0
    }
}
