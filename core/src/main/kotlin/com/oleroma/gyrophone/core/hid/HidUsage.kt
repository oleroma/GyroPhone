package com.oleroma.gyrophone.core.hid

/** HID keyboard usage IDs (Usage Page 0x07) with display names, for mapping UI and profiles. */
object HidUsage {
    const val LEFT_CTRL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val LEFT_ALT = 0xE2
    const val LEFT_GUI = 0xE3
    const val RIGHT_CTRL = 0xE4
    const val RIGHT_SHIFT = 0xE5
    const val RIGHT_ALT = 0xE6
    const val RIGHT_GUI = 0xE7

    fun isModifier(usage: Int): Boolean = usage in LEFT_CTRL..RIGHT_GUI
    fun modifierBit(usage: Int): Int = 1 shl (usage - LEFT_CTRL)

    val names: Map<Int, String> = buildMap {
        ('A'..'Z').forEachIndexed { i, c -> put(0x04 + i, c.toString()) }
        "1234567890".forEachIndexed { i, c -> put(0x1E + i, c.toString()) }
        put(0x28, "Enter"); put(0x29, "Esc"); put(0x2A, "Backspace"); put(0x2B, "Tab")
        put(0x2C, "Space"); put(0x2D, "-"); put(0x2E, "="); put(0x2F, "["); put(0x30, "]")
        put(0x31, "\\"); put(0x33, ";"); put(0x34, "'"); put(0x35, "`"); put(0x36, ",")
        put(0x37, "."); put(0x38, "/"); put(0x39, "Caps Lock")
        (1..12).forEach { put(0x3A + it - 1, "F$it") }
        put(0x49, "Insert"); put(0x4A, "Home"); put(0x4B, "Page Up"); put(0x4C, "Delete")
        put(0x4D, "End"); put(0x4E, "Page Down")
        put(0x4F, "Right"); put(0x50, "Left"); put(0x51, "Down"); put(0x52, "Up")
        put(0x46, "Print Screen"); put(0x47, "Scroll Lock"); put(0x48, "Pause"); put(0x65, "Menu")
        put(0x53, "Num Lock"); put(0x54, "Keypad /"); put(0x55, "Keypad *"); put(0x56, "Keypad -")
        put(0x57, "Keypad +"); put(0x58, "Keypad Enter")
        (1..9).forEach { put(0x59 + it - 1, "Keypad $it") }
        put(0x62, "Keypad 0"); put(0x63, "Keypad .")
        (13..24).forEach { put(0x68 + it - 13, "F$it") }
        put(LEFT_CTRL, "Left Ctrl"); put(LEFT_SHIFT, "Left Shift"); put(LEFT_ALT, "Left Alt")
        put(LEFT_GUI, "Left Win"); put(RIGHT_CTRL, "Right Ctrl"); put(RIGHT_SHIFT, "Right Shift")
        put(RIGHT_ALT, "Right Alt"); put(RIGHT_GUI, "Right Win")
    }

    fun byName(name: String): Int? = names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
}

/** Android KeyEvent keycodes of a physical keyboard to HID usages (for "press a key" capture). */
object AndroidKeyToHid {
    private val table: Map<Int, Int> = buildMap {
        (0..25).forEach { put(29 + it, 0x04 + it) } // KEYCODE_A..Z
        put(7, 0x27) // KEYCODE_0
        (1..9).forEach { put(7 + it, 0x1E + it - 1) } // KEYCODE_1..9
        (0..11).forEach { put(131 + it, 0x3A + it) } // F1..F12
        put(66, 0x28); put(111, 0x29); put(67, 0x2A); put(112, 0x4C); put(61, 0x2B); put(62, 0x2C)
        put(69, 0x2D); put(70, 0x2E); put(71, 0x2F); put(72, 0x30); put(73, 0x31); put(74, 0x33)
        put(75, 0x34); put(68, 0x35); put(55, 0x36); put(56, 0x37); put(76, 0x38); put(115, 0x39)
        put(113, HidUsage.LEFT_CTRL); put(114, HidUsage.RIGHT_CTRL)
        put(59, HidUsage.LEFT_SHIFT); put(60, HidUsage.RIGHT_SHIFT)
        put(57, HidUsage.LEFT_ALT); put(58, HidUsage.RIGHT_ALT)
        put(117, HidUsage.LEFT_GUI); put(118, HidUsage.RIGHT_GUI)
        put(19, 0x52); put(20, 0x51); put(21, 0x50); put(22, 0x4F)
        put(124, 0x49); put(122, 0x4A); put(123, 0x4D); put(92, 0x4B); put(93, 0x4E)
        put(120, 0x46); put(116, 0x47); put(121, 0x48); put(143, 0x53)
        put(144, 0x62) // NUMPAD_0
        (1..9).forEach { put(144 + it, 0x59 + it - 1) } // NUMPAD_1..9
        put(154, 0x54); put(155, 0x55); put(156, 0x56); put(157, 0x57); put(158, 0x63); put(160, 0x58)
        put(82, 0x65)
    }

    /** HID usage for an Android keycode, or null if it has no keyboard equivalent. */
    fun usage(androidKeyCode: Int): Int? = table[androidKeyCode]
}
