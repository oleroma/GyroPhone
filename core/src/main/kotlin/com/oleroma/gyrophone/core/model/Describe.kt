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
        is InputSource.KeyGesture -> "Controller " + (AndroidKey.names[s.keyCode] ?: "key ${s.keyCode}") + " " +
                s.gesture.label.lowercase()

        is InputSource.Axis -> (axisNames[s.axis] ?: "Axis ${s.axis}") + half(s.half)
        is InputSource.Touch -> "Touch ${s.id}"
        is InputSource.TouchStick -> "Touch stick ${s.id} ${s.axis}" + half(s.half)
        is InputSource.TouchStickDiagonal -> "Touch stick ${s.id} ${s.diagonal.label}"
        is InputSource.TouchSwipe -> "Swipe on ${s.id} ${s.axis}" + half(s.half)
        is InputSource.TouchRelease -> "Touch ${s.id} release"
        is InputSource.TouchDoubleTap -> "Touch ${s.id} double tap"
        is InputSource.TouchTap -> "Touch ${s.id} tap"
        is InputSource.TouchLongPress -> "Touch ${s.id} long press"
        is InputSource.TouchArea -> "Touch ${s.id} ${s.kind.label.lowercase()}"
        is InputSource.Surface -> (if (s.id.isEmpty()) "Touchpad" else "Swipe on ${s.id}") +
                " ${s.fingers} finger" + (if (s.fingers > 1) "s " else " ") + s.input.label.lowercase() +
                if (s.zone > 0) " zone ${s.zone + 1}" else ""
        is InputSource.Gyro -> "Gyro " + s.axis.label.lowercase() + if (s.id.isEmpty()) "" else " on ${s.id}"
        is InputSource.Tilt -> "Tilt " + when (s.axis) {
            StickAxis.X -> when (s.half) {
                AxisHalf.FULL -> "sideways"
                AxisHalf.POSITIVE -> "right"
                AxisHalf.NEGATIVE -> "left"
            }

            StickAxis.Y -> when (s.half) {
                AxisHalf.FULL -> "forward and back"
                AxisHalf.POSITIVE -> "back"
                AxisHalf.NEGATIVE -> "forward"
            }
        } + if (s.id.isEmpty()) "" else " on ${s.id}"

        is InputSource.Shake -> "Shake" + if (s.id.isEmpty()) "" else " on ${s.id}"
    }

    fun target(t: OutputTarget): String = when (t) {
        is OutputTarget.Pad -> "Gamepad ${t.button.label}"
        is OutputTarget.PadAxis -> "Gamepad ${t.axis}" + if (t.scale < 0) " (inverted)" else ""
        is OutputTarget.Key -> "Key " + (HidUsage.names[t.usage] ?: "0x%02X".format(t.usage)) + keyMode(t.mode)
        is OutputTarget.Mouse -> "Mouse ${t.button.name.lowercase()}"
        is OutputTarget.Wheel -> if (t.step > 0) "Wheel up" else "Wheel down"
        is OutputTarget.MouseAxis -> t.motion.label + if (t.scale < 0) ", reversed" else ""
        OutputTarget.GyroHold -> "Ratchet (flip gyro while held)"
        OutputTarget.GyroToggle -> "Toggle gyro (swap what a ratchet does)"
        OutputTarget.GyroPause -> "Ratchet (flip gyro while held)"
        is OutputTarget.GyroPauseAxis -> "Ratchet ${t.axis.label.lowercase()} (flip it while held)"
        OutputTarget.SwitchApps -> "Switch apps (Alt+Tab)"
        OutputTarget.Zoom -> "Zoom (Ctrl + wheel)"
        is OutputTarget.Haptic -> when {
            t.held -> "Haptic while held"
            else -> "Haptic tick" + when (t.edge) {
                null -> ""
                HapticEdge.PRESS -> " on press"
                HapticEdge.RELEASE -> " on release"
                HapticEdge.BOTH -> " on press and release"
            }
        } + feel(t.feel)

        is OutputTarget.HapticStep -> "Haptic per ${t.percent} % step" + feel(t.feel)
        is OutputTarget.Layer -> layer(t) + if (t.set < 0) "" else " → " + setName(t.set)
        OutputTarget.LayerBack -> "Layer back (to the layers before the last change)"
        OutputTarget.NoOutput -> "Nothing (blocks the layer below)"
    }

    /** The "Layers" picker group: each action with no set yet (set -1, picked next), and Nothing. */
    val layerTargets: List<OutputTarget> = listOf(
        OutputTarget.Layer(-1), OutputTarget.Layer(-1, whileHeld = true),
        OutputTarget.Layer(-1, override = true), OutputTarget.Layer(-1, override = true, whileHeld = true),
        OutputTarget.LayerBack, OutputTarget.NoOutput,
    )

    /** "Layer set", "Layer hold", "Override layer set" or "Override layer hold". */
    fun layer(t: OutputTarget.Layer): String = when {
        t.override && t.whileHeld -> "Override layer hold"
        t.override -> "Override layer set"
        t.whileHeld -> "Layer hold"
        else -> "Layer set"
    }

    /** A layer's name where no profile is at hand: "Base" or "Layer 2". */
    fun setName(set: Int): String = if (set == MappingSet.BASE) "Base" else "Layer $set"

    fun isHaptic(t: OutputTarget): Boolean = t is OutputTarget.Haptic || t is OutputTarget.HapticStep

    /** ", system strong", ", custom 120 · 20 ms" or "" for the workspace's feel. */
    fun feel(f: HapticFeel?): String = when {
        f == null -> ""
        f.system -> ", system " + systemLevel(f.strength).lowercase()
        else -> ", custom ${f.strength} · ${f.lengthMs} ms"
    }

    /** Light / Medium / Strong: the system click effect a strength picks. */
    fun systemLevel(strength: Int): String = when {
        strength < 86 -> "Light"
        strength < 171 -> "Medium"
        else -> "Strong"
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

        is OutputTarget.Key -> "Key " + (HidUsage.names[t.usage] ?: "0x%02X".format(t.usage)) + keyMode(t.mode)
        is OutputTarget.Mouse -> when (t.button) {
            MouseButton.LEFT -> "Mouse L"
            MouseButton.RIGHT -> "Mouse R"
            MouseButton.MIDDLE -> "Mouse M"
            MouseButton.BACK -> "Mouse Back"
            MouseButton.FORWARD -> "Mouse Fwd"
        }

        is OutputTarget.Wheel -> if (t.step > 0) "Wheel ↑" else "Wheel ↓"
        is OutputTarget.MouseAxis -> when (t.motion) {
            MouseMotion.MOVE_X -> "Mouse X"
            MouseMotion.MOVE_Y -> "Mouse Y"
            MouseMotion.WHEEL -> "Scroll"
            MouseMotion.HWHEEL -> "Scroll ↔"
        } + if (t.scale < 0) " rev" else ""

        OutputTarget.GyroHold -> "Ratchet"
        OutputTarget.GyroToggle -> "Gyro ⇄"
        OutputTarget.GyroPause -> "Ratchet"
        is OutputTarget.GyroPauseAxis -> "Ratchet " + t.axis.label.lowercase()
        OutputTarget.SwitchApps -> "Alt+Tab"
        OutputTarget.Zoom -> "Zoom"
        is OutputTarget.Haptic -> when {
            t.held -> "Buzz held"
            t.edge == HapticEdge.PRESS -> "Buzz in"
            t.edge == HapticEdge.RELEASE -> "Buzz out"
            t.edge == HapticEdge.BOTH -> "Buzz in/out"
            else -> "Buzz"
        }

        is OutputTarget.HapticStep -> "Buzz ${t.percent}%"
        is OutputTarget.Layer -> layer(t) + if (t.set < 0) "" else " " + (if (t.set == MappingSet.BASE) "Base" else t.set.toString())
        OutputTarget.LayerBack -> "Layer back"

        OutputTarget.NoOutput -> "Nothing"
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
            if (it.isTrigger) listOf(OutputTarget.PadAxis(it)) else listOf(
                OutputTarget.PadAxis(it),
                OutputTarget.PadAxis(it, -1f)
            )
        },
        "Mouse" to MouseButton.entries.map { OutputTarget.Mouse(it) } + listOf(
            OutputTarget.Wheel(1),
            OutputTarget.Wheel(-1)
        ),
        "Mouse motion" to MouseMotion.entries.flatMap {
            listOf(
                OutputTarget.MouseAxis(it),
                OutputTarget.MouseAxis(it, -1f)
            )
        },
        "Gyro" to listOf(OutputTarget.GyroPause) +
            listOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL).map { OutputTarget.GyroPauseAxis(it) } +
            OutputTarget.GyroToggle,
        "Phone" to listOf(
            OutputTarget.Haptic(held = false),
            OutputTarget.Haptic(held = true),
            OutputTarget.HapticStep()
        ),
        "Keyboard" to (keyboardOrder.filter { it in HidUsage.names } + HidUsage.names.keys.sorted())
            .distinct().map { OutputTarget.Key(it) },
    )

    /** Every target the picker offers, in display order. */
    val allTargets: List<OutputTarget> = targetGroups.flatMap { it.second }

    /** Targets that make sense for an analog input (full stick axis). */
    val analogTargets: List<OutputTarget> = allTargets.filterIsInstance<OutputTarget.PadAxis>()

    /** Targets of a swipe axis: mouse motion and scrolling, or a game pad axis (floating stick). */
    val swipeTargets: List<OutputTarget> =
        allTargets.filter { it is OutputTarget.MouseAxis || it is OutputTarget.PadAxis }

    /**
     * Targets of the picker for [analog] / [swipe] inputs; mouse motion only follows swipes.
     * [extra]: targets only this input offers (switching apps, zoom), in a group of their own.
     */
    fun pickerGroups(
        analog: Boolean,
        swipe: Boolean,
        extra: List<OutputTarget> = emptyList(),
        mouseMotion: Boolean = false,
    ): List<Pair<String, List<OutputTarget>>> {
        // Haptics follow any input.
        val allowed: (OutputTarget) -> Boolean = when {
            mouseMotion -> { t -> t is OutputTarget.MouseAxis }
            swipe -> { t -> t is OutputTarget.MouseAxis || t is OutputTarget.PadAxis || isHaptic(t) }
            analog -> { t -> t is OutputTarget.PadAxis || isHaptic(t) }
            else -> { t -> t !is OutputTarget.MouseAxis }
        }
        val groups = targetGroups.map { (name, list) -> name to list.filter(allowed) }.filter { it.second.isNotEmpty() }
        return if (extra.isEmpty()) groups else listOf("Touchpad" to extra) + groups
    }

    /** How a key target's [mode] reads after its name; nothing for a plain held key. */
    private fun keyMode(mode: KeyMode): String = when (mode) {
        KeyMode.HOLD -> ""
        KeyMode.TOGGLE -> " (toggle)"
        KeyMode.NEXT_KEY -> " (next key)"
    }
}
