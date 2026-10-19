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
        put(LEFT_CTRL, "Left Ctrl"); put(LEFT_SHIFT, "Left Shift"); put(LEFT_ALT, "Left Alt")
        put(LEFT_GUI, "Left Win"); put(RIGHT_CTRL, "Right Ctrl"); put(RIGHT_SHIFT, "Right Shift")
        put(RIGHT_ALT, "Right Alt"); put(RIGHT_GUI, "Right Win")
    }

    fun byName(name: String): Int? = names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
}
