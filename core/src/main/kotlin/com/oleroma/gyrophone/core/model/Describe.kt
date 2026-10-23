package com.oleroma.gyrophone.core.model

import com.oleroma.gyrophone.core.hid.HidUsage

/** Human-readable names for the mapping editor. */
object Describe {
    private val axisNames = mapOf(
        AndroidAxis.X to "Left stick X", AndroidAxis.Y to "Left stick Y",
        AndroidAxis.Z to "Right stick X", AndroidAxis.RZ to "Right stick Y",
        AndroidAxis.HAT_X to "D-pad X", AndroidAxis.HAT_Y to "D-pad Y",
        AndroidAxis.LTRIGGER to "Left trigger", AndroidAxis.RTRIGGER to "Right trigger",
        AndroidAxis.GAS to "Gas", AndroidAxis.BRAKE to "Brake",
    )

    fun source(s: InputSource): String = when (s) {
        is InputSource.Key -> "Controller " + (AndroidKey.names[s.keyCode] ?: "key ${s.keyCode}")
        is InputSource.Axis -> (axisNames[s.axis] ?: "Axis ${s.axis}") + half(s.half)
        is InputSource.Touch -> "Touch ${s.id}"
        is InputSource.TouchStick -> "Touch stick ${s.id} ${s.axis}" + half(s.half)
    }

    fun target(t: OutputTarget): String = when (t) {
        is OutputTarget.Pad -> "Gamepad ${t.button.label}"
        is OutputTarget.PadAxis -> "Gamepad ${t.axis}" + if (t.scale < 0) " (inverted)" else ""
        is OutputTarget.Key -> "Key " + (HidUsage.names[t.usage] ?: "0x%02X".format(t.usage))
        is OutputTarget.Mouse -> "Mouse ${t.button.name.lowercase()}"
        is OutputTarget.Wheel -> if (t.step > 0) "Wheel up" else "Wheel down"
        OutputTarget.GyroHold -> "Ratchet (flip gyro while held)"
        OutputTarget.GyroToggle -> "Toggle gyro (swap what a ratchet does)"
        OutputTarget.GyroPause -> "Ratchet (flip gyro while held)"
    }

    private fun half(h: AxisHalf) = when (h) {
        AxisHalf.FULL -> ""
        AxisHalf.POSITIVE -> " +"
        AxisHalf.NEGATIVE -> " −"
    }

    /** Compact chip text, e.g. "A", "LT", "Key W", "Mouse L", "Wheel ↑", "Gyro hold". */
    fun shortTarget(t: OutputTarget): String = when (t) {
        is OutputTarget.Pad -> when (t.button) {
            GamepadButton.DPAD_UP -> "D-pad ↑"
            GamepadButton.DPAD_DOWN -> "D-pad ↓"
            GamepadButton.DPAD_LEFT -> "D-pad ←"
            GamepadButton.DPAD_RIGHT -> "D-pad →"
            else -> t.button.label
        }
        is OutputTarget.PadAxis -> when (t.axis) {
            GamepadAxis.LX -> "LS X"
            GamepadAxis.LY -> "LS Y"
            GamepadAxis.RX -> "RS X"
            GamepadAxis.RY -> "RS Y"
            GamepadAxis.LT -> "LT"
            GamepadAxis.RT -> "RT"
        } + if (t.scale < 0) " inv" else ""
        is OutputTarget.Key -> "Key " + (HidUsage.names[t.usage] ?: "0x%02X".format(t.usage))
        is OutputTarget.Mouse -> when (t.button) {
            MouseButton.LEFT -> "Mouse L"
            MouseButton.RIGHT -> "Mouse R"
            MouseButton.MIDDLE -> "Mouse M"
            MouseButton.BACK -> "Mouse Back"
            MouseButton.FORWARD -> "Mouse Fwd"
        }
        is OutputTarget.Wheel -> if (t.step > 0) "Wheel ↑" else "Wheel ↓"
        OutputTarget.GyroHold -> "Ratchet"
        OutputTarget.GyroToggle -> "Gyro ⇄"
        OutputTarget.GyroPause -> "Ratchet"
    }

    private val keyboardOrder: List<Int> = buildList {
        addAll(0x04..0x1D) // letters
        addAll(0x1E..0x27) // digits
        addAll(0x3A..0x45) // F1-F12
        addAll(0x68..0x73) // F13-F24
        addAll(HidUsage.LEFT_CTRL..HidUsage.RIGHT_GUI)
        addAll(listOf(0x2C, 0x28, 0x29, 0x2A, 0x2B, 0x39)) // Space, Enter, Esc, Backspace, Tab, Caps Lock
        addAll(listOf(0x52, 0x51, 0x50, 0x4F)) // arrows
        addAll(0x49..0x4E) // Insert, Home, Page Up, Delete, End, Page Down
        addAll(listOf(0x46, 0x47, 0x48, 0x65)) // Print Screen, Scroll Lock, Pause, Menu
        addAll(0x2D..0x38) // symbols
        addAll(0x53..0x63) // keypad
    }

    /** Picker targets in display groups. */
    val targetGroups: List<Pair<String, List<OutputTarget>>> = listOf(
        "Gamepad buttons" to GamepadButton.entries.map { OutputTarget.Pad(it) },
        "Gamepad sticks & triggers" to GamepadAxis.entries.flatMap {
            if (it.isTrigger) listOf(OutputTarget.PadAxis(it)) else listOf(OutputTarget.PadAxis(it), OutputTarget.PadAxis(it, -1f))
        },
        "Mouse" to MouseButton.entries.map { OutputTarget.Mouse(it) } + listOf(OutputTarget.Wheel(1), OutputTarget.Wheel(-1)),
        "Gyro" to listOf(OutputTarget.GyroPause, OutputTarget.GyroToggle),
        "Keyboard" to (keyboardOrder.filter { it in HidUsage.names } + HidUsage.names.keys.sorted())
            .distinct().map { OutputTarget.Key(it) },
    )

    /** Every target the picker offers, in display order. */
    val allTargets: List<OutputTarget> = targetGroups.flatMap { it.second }

    /** Targets that make sense for an analog input (full stick axis). */
    val analogTargets: List<OutputTarget> = allTargets.filterIsInstance<OutputTarget.PadAxis>()
}
