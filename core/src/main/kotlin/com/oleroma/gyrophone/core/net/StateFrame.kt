package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage

/**
 * The full input state carried by one STATE packet. Mutable and reused by the pump so the send
 * loop does not allocate. Pad, keys and mouse buttons are absolute; mouse and wheel are the
 * session's cumulative wrapping totals.
 */
class StateFrame : WirePacket {
    /** Low 32 bits of the phone's elapsed-realtime microseconds. */
    var phoneTimeUs = 0
    var flags = 0
    var buttons = 0
    var hat = Hat.CENTER
    var mouseButtons = 0
    var lx = 0
    var ly = 0
    var rx = 0
    var ry = 0
    var lt = 0
    var rt = 0

    /** Usage u is held when `keyBitmap[u >> 3] & (1 << (u & 7))`; byte 28 is the HID modifier byte. */
    val keyBitmap = ByteArray(KEY_BITMAP_SIZE)
    var mouseTotalX = 0
    var mouseTotalY = 0
    /** Whole notches in STATE, 1/120 notch in STATE_HIRES (see [hires]). */
    var wheelTotal = 0
    /** Horizontal wheel total in 1/120 notch, right = +; STATE_HIRES only. */
    var hwheelTotal = 0
    /** Sent as STATE_HIRES. */
    var hires = false

    /** Builds the frame from an engine snapshot; time and REPEAT/KEEPALIVE flags are left to the sender. */
    fun fill(s: HidState, totals: MouseTotals, gyroActive: Boolean, hires: Boolean = false) {
        this.hires = hires
        val g = s.gamepad
        flags = if (gyroActive) StateFlags.GYRO_ACTIVE else 0
        buttons = g.buttons and 0xFFFF
        hat = if (g.hat in 0..Hat.CENTER) g.hat else Hat.CENTER
        mouseButtons = s.mouse.buttons and 0x1F
        lx = stick(g.lx); ly = stick(g.ly); rx = stick(g.rx); ry = stick(g.ry)
        lt = g.lt.coerceIn(0, GamepadState.TRIGGER_MAX)
        rt = g.rt.coerceIn(0, GamepadState.TRIGGER_MAX)
        keyBitmap.fill(0)
        for (usage in s.keyboard.keys) setKey(usage)
        keyBitmap[MODIFIER_BYTE] = s.keyboard.modifiers.toByte()
        mouseTotalX = totals.x
        mouseTotalY = totals.y
        wheelTotal = if (hires) totals.wheel else totals.wheelNotches
        hwheelTotal = if (hires) totals.hwheel else 0
    }

    /** Marks [usage] held. Usages 0..3 (no event / error codes) are never set. */
    fun setKey(usage: Int) {
        if (usage < FIRST_KEY_USAGE || usage > 0xFF) return
        keyBitmap[usage shr 3] = (keyBitmap[usage shr 3].toInt() or (1 shl (usage and 7))).toByte()
    }

    fun isKeyHeld(usage: Int): Boolean =
        usage in 0..0xFF && (keyBitmap[usage shr 3].toInt() and (1 shl (usage and 7))) != 0

    /** Modifier bits (LCtrl = bit 0 .. RGui = bit 7), the same as bitmap byte 28. */
    val modifiers: Int get() = keyBitmap[MODIFIER_BYTE].toInt() and 0xFF

    /** Held non-modifier usages, ascending. Allocates; not for the hot path. */
    fun heldKeys(): List<Int> = (0..0xFF).filter { isKeyHeld(it) && !HidUsage.isModifier(it) }

    /** Everything except time, flags and totals: a change here is a discrete change. */
    fun discreteEquals(o: StateFrame): Boolean =
        buttons == o.buttons && hat == o.hat && mouseButtons == o.mouseButtons &&
            lx == o.lx && ly == o.ly && rx == o.rx && ry == o.ry && lt == o.lt && rt == o.rt &&
            keyBitmap.contentEquals(o.keyBitmap)

    fun totalsEqual(o: StateFrame): Boolean =
        mouseTotalX == o.mouseTotalX && mouseTotalY == o.mouseTotalY && wheelTotal == o.wheelTotal &&
            hwheelTotal == o.hwheelTotal && hires == o.hires

    fun copyFrom(o: StateFrame) {
        phoneTimeUs = o.phoneTimeUs; flags = o.flags; buttons = o.buttons; hat = o.hat
        mouseButtons = o.mouseButtons; lx = o.lx; ly = o.ly; rx = o.rx; ry = o.ry; lt = o.lt; rt = o.rt
        o.keyBitmap.copyInto(keyBitmap)
        mouseTotalX = o.mouseTotalX; mouseTotalY = o.mouseTotalY; wheelTotal = o.wheelTotal
        hwheelTotal = o.hwheelTotal; hires = o.hires
    }

    /** Neutral pad, nothing held, totals zero. */
    fun clear() {
        phoneTimeUs = 0; flags = 0; buttons = 0; hat = Hat.CENTER; mouseButtons = 0
        lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0
        keyBitmap.fill(0)
        mouseTotalX = 0; mouseTotalY = 0; wheelTotal = 0; hwheelTotal = 0; hires = false
    }

    /** Releases pad, keys and buttons but keeps the totals, for the final STATE before BYE. */
    fun neutralize() {
        buttons = 0; hat = Hat.CENTER; mouseButtons = 0
        lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0
        keyBitmap.fill(0)
    }

    override fun toString(): String =
        "StateFrame(t=$phoneTimeUs flags=$flags buttons=$buttons hat=$hat mb=$mouseButtons " +
            "l=($lx,$ly) r=($rx,$ry) lt=$lt rt=$rt mods=$modifiers keys=${heldKeys()} " +
            "totals=($mouseTotalX,$mouseTotalY,$wheelTotal,$hwheelTotal)${if (hires) " hires" else ""})"

    private fun stick(v: Int): Int = v.coerceIn(-GamepadState.STICK_MAX, GamepadState.STICK_MAX)

    companion object {
        const val KEY_BITMAP_SIZE = 32
        const val MODIFIER_BYTE = 28
        private const val FIRST_KEY_USAGE = 4
    }
}
