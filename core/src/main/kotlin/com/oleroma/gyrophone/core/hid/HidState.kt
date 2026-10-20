package com.oleroma.gyrophone.core.hid

/** Hat switch values as encoded in the game pad report. */
object Hat {
    const val UP = 0
    const val UP_RIGHT = 1
    const val RIGHT = 2
    const val DOWN_RIGHT = 3
    const val DOWN = 4
    const val DOWN_LEFT = 5
    const val LEFT = 6
    const val UP_LEFT = 7
    const val CENTER = 8 // out of logical range -> null state

    fun fromDpad(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Int {
        val v = (if (up) 1 else 0) - (if (down) 1 else 0)
        val h = (if (right) 1 else 0) - (if (left) 1 else 0)
        return when {
            v > 0 && h == 0 -> UP
            v > 0 && h > 0 -> UP_RIGHT
            v == 0 && h > 0 -> RIGHT
            v < 0 && h > 0 -> DOWN_RIGHT
            v < 0 && h == 0 -> DOWN
            v < 0 && h < 0 -> DOWN_LEFT
            v == 0 && h < 0 -> LEFT
            v > 0 && h < 0 -> UP_LEFT
            else -> CENTER
        }
    }
}

data class GamepadState(
    val buttons: Int = 0,
    val hat: Int = Hat.CENTER,
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
) {
    fun encode(): ByteArray {
        val out = ByteArray(HidDescriptor.GAMEPAD_REPORT_SIZE)
        out[0] = (buttons and 0xFF).toByte()
        out[1] = ((buttons shr 8) and 0xFF).toByte()
        out[2] = (hat and 0x0F).toByte()
        putInt16(out, 3, lx.coerceIn(-STICK_MAX, STICK_MAX))
        putInt16(out, 5, ly.coerceIn(-STICK_MAX, STICK_MAX))
        putInt16(out, 7, rx.coerceIn(-STICK_MAX, STICK_MAX))
        putInt16(out, 9, ry.coerceIn(-STICK_MAX, STICK_MAX))
        putInt16(out, 11, lt.coerceIn(0, TRIGGER_MAX))
        putInt16(out, 13, rt.coerceIn(0, TRIGGER_MAX))
        return out
    }

    companion object {
        const val STICK_MAX = 32767
        const val TRIGGER_MAX = 1023
    }
}

data class KeyboardState(
    val modifiers: Int = 0,
    /** Up to six non-modifier HID usages, in press order. */
    val keys: List<Int> = emptyList(),
) {
    fun encode(): ByteArray {
        val out = ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE)
        out[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, usage -> out[2 + i] = usage.toByte() }
        return out
    }
}

/**
 * Mouse output of one snapshot. [wheel] (up +) and [hwheel] (right +) are in 1/120 notch units
 * (Windows WHEEL_DELTA fractions); the transport converts them for the host.
 */
data class MouseState(
    val buttons: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val wheel: Int = 0,
    val hwheel: Int = 0,
) {
    val hasMotion: Boolean get() = dx != 0 || dy != 0 || wheel != 0 || hwheel != 0

    companion object {
        /** One wheel notch in [wheel] / [hwheel] units. */
        const val WHEEL_UNIT = 120

        /**
         * The HID mouse input report. [wheel] and [pan] are in the units the host currently reads:
         * whole notches, or 1/120 notch once it has enabled the resolution multiplier.
         */
        fun report(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray {
            val out = ByteArray(HidDescriptor.MOUSE_REPORT_SIZE)
            out[0] = (buttons and 0x1F).toByte()
            putInt16(out, 1, dx.coerceIn(-32767, 32767))
            putInt16(out, 3, dy.coerceIn(-32767, 32767))
            putInt16(out, 5, wheel.coerceIn(-32767, 32767))
            putInt16(out, 7, pan.coerceIn(-32767, 32767))
            return out
        }
    }
}

/** Everything the phone currently reports to the host. */
data class HidState(
    val gamepad: GamepadState = GamepadState(),
    val keyboard: KeyboardState = KeyboardState(),
    val mouse: MouseState = MouseState(),
)

private fun putInt16(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value and 0xFF).toByte()
    out[offset + 1] = ((value shr 8) and 0xFF).toByte()
}
