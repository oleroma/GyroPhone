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
        OutputTarget.GyroHold -> "Hold for gyro mouse"
        OutputTarget.GyroToggle -> "Toggle gyro mouse"
    }

    private fun half(h: AxisHalf) = when (h) {
        AxisHalf.FULL -> ""
        AxisHalf.POSITIVE -> " +"
        AxisHalf.NEGATIVE -> " −"
    }

    /** Every target the picker offers, in display order. */
    val allTargets: List<OutputTarget> = buildList {
        GamepadButton.entries.forEach { add(OutputTarget.Pad(it)) }
        GamepadAxis.entries.forEach {
            add(OutputTarget.PadAxis(it))
            if (!it.isTrigger) add(OutputTarget.PadAxis(it, -1f))
        }
        MouseButton.entries.forEach { add(OutputTarget.Mouse(it)) }
        add(OutputTarget.Wheel(1)); add(OutputTarget.Wheel(-1))
        add(OutputTarget.GyroHold); add(OutputTarget.GyroToggle)
        HidUsage.names.keys.sorted().forEach { add(OutputTarget.Key(it)) }
    }
}
