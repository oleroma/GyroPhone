package com.oleroma.gyrophone.core.model

import com.oleroma.gyrophone.core.hid.AndroidKeyToHid
import com.oleroma.gyrophone.core.net.PcPress

/**
 * "Press a key" in the mapping editor: what a press on a device attached to the phone, or on the
 * PC (CAPTURED), maps to. Each becomes the output of the same name: a key the same key, a mouse
 * button the same button, a controller button or stick the game pad's.
 */
object PressTargets {
    /** Android game pad keys to the game pad's buttons (and L2 / R2 to its triggers), as a standard controller maps them. */
    private val padKeys: Map<Int, OutputTarget> = KnownLayouts.standardControllerMappings()
        .mapNotNull { m -> (m.source as? InputSource.Key)?.let { it.keyCode to m.target } }
        .toMap()

    /**
     * A key of a device attached to the phone: a game pad's buttons as the pad's ([pad]: the device
     * is a game pad, whose D-pad keys share codes with a keyboard's arrows), a mouse's back and
     * forward buttons ([mouse]) as the mouse's, any other key as the keyboard key; null for one with no
     * keyboard equivalent.
     */
    fun ofPhoneKey(keyCode: Int, pad: Boolean, mouse: Boolean = false): OutputTarget? {
        if (mouse) when (keyCode) {
            AndroidKey.BACK -> return OutputTarget.Mouse(MouseButton.BACK)
            KEYCODE_FORWARD -> return OutputTarget.Mouse(MouseButton.FORWARD)
        }
        if (pad) padKeys[keyCode]?.let { return it }
        return AndroidKeyToHid.usage(keyCode)?.let { OutputTarget.Key(it) }
    }

    /**
     * A phone-attached pad's [axis] pushed past half way, [positive] or not: its stick or trigger
     * as the game pad's ([zStick]: the right stick is on Z / RZ, else RX / RY), its hat as the D-pad.
     */
    fun ofPhoneAxis(axis: Int, positive: Boolean, zStick: Boolean): OutputTarget? {
        val sign = if (positive) 1f else -1f
        return when (axis) {
            AndroidAxis.X -> OutputTarget.PadAxis(GamepadAxis.LX, sign)
            AndroidAxis.Y -> OutputTarget.PadAxis(GamepadAxis.LY, sign)
            AndroidAxis.Z -> if (zStick) OutputTarget.PadAxis(GamepadAxis.RX, sign) else null
            AndroidAxis.RZ -> if (zStick) OutputTarget.PadAxis(GamepadAxis.RY, sign) else null
            AndroidAxis.RX -> if (zStick) null else OutputTarget.PadAxis(GamepadAxis.RX, sign)
            AndroidAxis.RY -> if (zStick) null else OutputTarget.PadAxis(GamepadAxis.RY, sign)
            AndroidAxis.LTRIGGER, AndroidAxis.BRAKE -> if (positive) OutputTarget.PadAxis(GamepadAxis.LT) else null
            AndroidAxis.RTRIGGER, AndroidAxis.GAS -> if (positive) OutputTarget.PadAxis(GamepadAxis.RT) else null
            AndroidAxis.HAT_X -> OutputTarget.Pad(if (positive) GamepadButton.DPAD_RIGHT else GamepadButton.DPAD_LEFT)
            AndroidAxis.HAT_Y -> OutputTarget.Pad(if (positive) GamepadButton.DPAD_DOWN else GamepadButton.DPAD_UP)
            else -> null
        }
    }

    /** A wheel notch, up ([up]) or down. */
    fun ofWheel(up: Boolean): OutputTarget = OutputTarget.Wheel(if (up) 1 else -1)

    /** XInput button bits to the game pad's buttons. */
    private val xinput: Map<Int, GamepadButton> = mapOf(
        0x1000 to GamepadButton.A, 0x2000 to GamepadButton.B, 0x4000 to GamepadButton.X, 0x8000 to GamepadButton.Y,
        0x0100 to GamepadButton.LB, 0x0200 to GamepadButton.RB, 0x0020 to GamepadButton.BACK, 0x0010 to GamepadButton.START,
        0x0040 to GamepadButton.LS, 0x0080 to GamepadButton.RS, 0x0400 to GamepadButton.GUIDE,
        0x0001 to GamepadButton.DPAD_UP, 0x0002 to GamepadButton.DPAD_DOWN,
        0x0004 to GamepadButton.DPAD_LEFT, 0x0008 to GamepadButton.DPAD_RIGHT,
    )

    private val pcAxes = listOf(GamepadAxis.LX, GamepadAxis.LY, GamepadAxis.RX, GamepadAxis.RY, GamepadAxis.LT, GamepadAxis.RT)

    /** What an input held on the PC maps to; null for one with none. */
    fun ofPc(p: PcPress): OutputTarget? = when (p.kind) {
        PcPress.KEY -> if (p.code in 1..0xFF) OutputTarget.Key(p.code) else null
        PcPress.MOUSE -> MouseButton.entries.firstOrNull { it.bit == p.code }?.let { OutputTarget.Mouse(it) }
        PcPress.WHEEL -> if (p.arg != 0) ofWheel(p.arg > 0) else null
        PcPress.PAD_BUTTON -> xinput[p.code]?.let { OutputTarget.Pad(it) }
        PcPress.PAD_AXIS -> pcAxes.getOrNull(p.code)?.let { a ->
            when {
                a.isTrigger -> if (p.arg > 0) OutputTarget.PadAxis(a) else null
                p.arg != 0 -> OutputTarget.PadAxis(a, if (p.arg > 0) 1f else -1f)
                else -> null
            }
        }
        else -> null
    }

    /** Android's KEYCODE_FORWARD (a mouse's forward button when the pointer isn't captured). */
    const val KEYCODE_FORWARD = 125
}

/**
 * The presses of one "Press a key": every input pressed joins in the order pressed (Ctrl, then C:
 * both), and it is done once everything pressed has been let go. An input with nothing to map
 * to still has to be let go, but adds nothing.
 */
class PressCombo {
    private val held = LinkedHashSet<String>()
    private val got = ArrayList<OutputTarget>()

    /** What it maps to so far, in the order pressed, without repeats. */
    val targets: List<OutputTarget> get() = got

    /** Something is held now. */
    val holding: Boolean get() = held.isNotEmpty()

    /** [id] (the input, unique per device) went down, mapping to [target] (null: nothing). */
    fun press(id: String, target: OutputTarget?) {
        held.add(id)
        if (target != null && target !in got) got.add(target)
    }

    /** [id] was let go: true when that ends it (nothing held any more, and something to map). */
    fun release(id: String): Boolean {
        if (!held.remove(id)) return false
        return held.isEmpty() && got.isNotEmpty()
    }

    fun clear() {
        held.clear()
        got.clear()
    }
}
