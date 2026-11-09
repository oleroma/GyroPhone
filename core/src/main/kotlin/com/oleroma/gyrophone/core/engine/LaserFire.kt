package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.MouseButton

/**
 * A laser pointer's fire output ([com.oleroma.gyrophone.core.model.GyroDisplay.laserFire]): while
 * GyroPhone sends it, Draw paints in the Fire colours. One number: [MOUSE] + a mouse button's bit,
 * [KEY] + a keyboard usage, [PAD] + a controller button's bit, [LT] or [RT], [DPAD] + 0 up, 1 down,
 * 2 left, 3 right; 0 none. Same numbers
 * as the companion's LaserFire.
 */
object LaserFire {
    const val MOUSE = 0x100
    const val KEY = 0x200
    const val PAD = 0x300
    const val LT = 0x400
    const val RT = 0x401
    const val DPAD = 0x500
    const val DEFAULT = MOUSE // left

    /** A trigger past this much counts as held. */
    private const val TRIGGER_HELD = GamepadState.TRIGGER_MAX * 3 / 10

    /** The choices the settings offer, by group: (label, number). */
    val MOUSE_CHOICES = listOf("Left" to MOUSE + MouseButton.LEFT.bit, "Right" to MOUSE + MouseButton.RIGHT.bit,
        "Middle" to MOUSE + MouseButton.MIDDLE.bit, "Back" to MOUSE + MouseButton.BACK.bit, "Forward" to MOUSE + MouseButton.FORWARD.bit)
    val PAD_CHOICES = listOf(GamepadButton.A, GamepadButton.B, GamepadButton.X, GamepadButton.Y, GamepadButton.LB, GamepadButton.RB)
        .map { it.label to PAD + it.bit } + listOf("LT" to LT, "RT" to RT) +
        listOf(GamepadButton.LS, GamepadButton.RS, GamepadButton.BACK, GamepadButton.START, GamepadButton.GUIDE, GamepadButton.SHARE)
            .map { it.label to PAD + it.bit } +
        listOf("D-pad up" to DPAD, "D-pad down" to DPAD + 1, "D-pad left" to DPAD + 2, "D-pad right" to DPAD + 3) +
        listOf(GamepadButton.M1, GamepadButton.M2, GamepadButton.M3, GamepadButton.M4).map { it.label to PAD + it.bit }
    val KEY_CHOICES: List<Pair<String, Int>> = buildList {
        add("Space" to KEY + 0x2C)
        for (c in 0 until 26) add(('A' + c).toString() to KEY + 0x04 + c)
        for (d in 1..9) add(d.toString() to KEY + 0x1D + d)
        add("0" to KEY + 0x27)
        add("Enter" to KEY + 0x28)
        add("Tab" to KEY + 0x2B)
        add("Shift" to KEY + HidUsage.LEFT_SHIFT)
        add("Ctrl" to KEY + HidUsage.LEFT_CTRL)
        add("Alt" to KEY + HidUsage.LEFT_ALT)
    }

    /** D-pad way [way] (0 up, 1 down, 2 left, 3 right) is held at hat value [hat] (diagonals hold both ways). */
    fun dpadHeld(way: Int, hat: Int): Boolean = when (way) {
        0 -> hat == Hat.UP_LEFT || hat == Hat.UP || hat == Hat.UP_RIGHT
        1 -> hat == Hat.DOWN_LEFT || hat == Hat.DOWN || hat == Hat.DOWN_RIGHT
        2 -> hat == Hat.UP_LEFT || hat == Hat.LEFT || hat == Hat.DOWN_LEFT
        3 -> hat == Hat.UP_RIGHT || hat == Hat.RIGHT || hat == Hat.DOWN_RIGHT
        else -> false
    }

    /** Fire output [code] is held in output [s]. */
    fun held(code: Int, s: HidState): Boolean {
        val v = code and 0xFF
        return when (code and 0xF00) {
            MOUSE -> s.mouse.buttons and (1 shl v) != 0
            KEY -> if (HidUsage.isModifier(v)) s.keyboard.modifiers and HidUsage.modifierBit(v) != 0 else v in s.allKeys
            PAD -> s.gamepad.buttons and (1 shl v) != 0
            LT and 0xF00 -> if (code == LT) s.gamepad.lt > TRIGGER_HELD else s.gamepad.rt > TRIGGER_HELD
            DPAD -> dpadHeld(v, s.gamepad.hat)
            else -> false
        }
    }
}
