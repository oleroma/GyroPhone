package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.AxisStage
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import com.oleroma.gyrophone.core.model.StickTuning
import com.oleroma.gyrophone.core.model.TriggerTuning
import com.oleroma.gyrophone.core.model.nothing

/**
 * What the PC's Keys overlay needs to know of the game pad attached to the phone: what each of its inputs sends in
 * the set in use ([keys]), with the sticks' and triggers' tuning and the tap time, so the PC works out by itself
 * what fires from the controller state each OVERLAY already carries. The phone reads nothing at run time for it: it
 * is made again only when the workspace, the set in use or the controller changes ([of]), never on an input thread.
 */
data class OverlayKeys(
    val keys: List<OverlayKey>,
    /** Left and right stick: inner deadzone and outer edge (0..1), their Outer ring, the Left/Right and Up/Down pies (degrees). */
    val sticks: List<StickTuning>,
    /** Left and right trigger: where Press and Full pull start (0..1). */
    val triggers: List<TriggerTuning>,
    /** The tap time (ms): shorter is a tap, longer a long press; also a double tap's longest gap. */
    val tapMs: Int,
    /** The touch buttons' mappings in the set in use: shown on the PC while the OVERLAY has the button pressed ([touchOf]). */
    val touch: List<OverlayTouchKey> = emptyList(),
) {
    companion object {
        val NONE = OverlayKeys(emptyList(), List(2) { StickTuning() }, List(2) { TriggerTuning() }, 300)

        /**
         * The table for [ms] (a pad's mappings in the set in use), sticks [sticks] and triggers [triggers] (by
         * [com.oleroma.gyrophone.core.model.PhysicalSticks] id), on a pad whose right stick is RX / RY ([rightStickRx]).
         * Inputs the OVERLAY doesn't carry (back buttons, other axes) are left out.
         */
        fun of(ms: List<Mapping>, rightStickRx: Boolean, sticks: Map<String, StickTuning>, triggers: Map<String, TriggerTuning>, tapMs: Int): OverlayKeys {
            val out = ArrayList<OverlayKey>()
            for (m in ms) {
                if (m.target.nothing || Describe.isHaptic(m.target)) continue
                val src = m.source
                val toggle = m.options?.mode == How.TOGGLE
                val gesture = (src as? InputSource.KeyGesture)?.gesture?.let { gestureOf(it) } ?: OverlayKey.HELD
                val input = inputOf(src, rightStickRx) ?: continue
                val outer = src is InputSource.Axis && src.stage == AxisStage.OUTER
                val flags = gesture or (if (outer) OverlayKey.OUTER else 0) or (if (toggle) OverlayKey.TOGGLE else 0)
                val target = if (input and 0xF0 == OverlayKey.STICK) analogText(m.target) else targetText(m.target)
                out.add(OverlayKey(input, flags, name(src), target))
            }
            return OverlayKeys(
                out,
                listOf(sticks["ls"] ?: StickTuning(), sticks["rs"] ?: StickTuning()),
                listOf(triggers["lt"] ?: TriggerTuning(), triggers["rt"] ?: TriggerTuning()),
                tapMs,
            )
        }

        /**
         * The touch buttons' mappings of [ms] (a workspace's touch mappings in the set in use): a button's press and its
         * tap, double tap, long press and release, with their Toggle; sticks, swipes and areas are left out.
         */
        fun touchOf(ms: List<Mapping>): List<OverlayTouchKey> {
            val out = ArrayList<OverlayTouchKey>()
            for (m in ms) {
                if (m.target.nothing || Describe.isHaptic(m.target)) continue
                val (id, gesture) = when (val s = m.source) {
                    is InputSource.Touch -> s.id to OverlayKey.HELD
                    is InputSource.TouchTap -> s.id to OverlayKey.TAP
                    is InputSource.TouchDoubleTap -> s.id to OverlayKey.DOUBLE_TAP
                    is InputSource.TouchLongPress -> s.id to OverlayKey.LONG_PRESS
                    is InputSource.TouchRelease -> s.id to OverlayKey.RELEASE
                    else -> continue
                }
                val toggle = if (m.options?.mode == How.TOGGLE) OverlayKey.TOGGLE else 0
                out.add(OverlayTouchKey(id, gesture or toggle, targetText(m.target)))
            }
            return out
        }

        private fun gestureOf(g: ButtonGesture): Int = when (g) {
            ButtonGesture.TAP -> OverlayKey.TAP
            ButtonGesture.DOUBLE_TAP -> OverlayKey.DOUBLE_TAP
            ButtonGesture.LONG_PRESS -> OverlayKey.LONG_PRESS
            ButtonGesture.RELEASE -> OverlayKey.RELEASE
            ButtonGesture.PRESS -> OverlayKey.PRESS
        }

        /** [src] as an [OverlayKey.input], or null when the OVERLAY doesn't carry it. */
        fun inputOf(src: InputSource, rx: Boolean): Int? {
            fun button(code: Int): Int? {
                val bit = OverlayFeed.padBit(code)
                return if (bit == 0) null else Integer.numberOfTrailingZeros(bit)
            }
            return when (src) {
                is InputSource.Key -> button(src.keyCode)
                is InputSource.KeyGesture -> button(src.keyCode)
                is InputSource.Axis -> {
                    val a = src.axis
                    val stick = stickOf(a, rx)
                    val left = a == AndroidAxis.LTRIGGER || a == AndroidAxis.BRAKE || rx && a == AndroidAxis.Z
                    val right = a == AndroidAxis.RTRIGGER || a == AndroidAxis.GAS || rx && a == AndroidAxis.RZ
                    when {
                        stick >= 0 && src.half == AxisHalf.FULL ->
                            if (src.stage == AxisStage.NONE) OverlayKey.STICK or stick
                            else OverlayKey.ANY_WAY or stick
                        stick >= 0 -> {
                            val y = a == AndroidAxis.Y || a == AndroidAxis.RY || a == AndroidAxis.RZ && !rx
                            val way = if (y) (if (src.half == AxisHalf.NEGATIVE) 0 else 2) else (if (src.half == AxisHalf.NEGATIVE) 3 else 1)
                            OverlayKey.WAY or (stick shl 2) or way
                        }
                        left || right -> {
                            val stage = when (src.stage) {
                                AxisStage.PRESS -> 1
                                AxisStage.FULL_PULL -> 2
                                else -> 0
                            }
                            OverlayKey.TRIGGER or ((if (left) 0 else 1) shl 2) or stage
                        }
                        a == AndroidAxis.HAT_X && src.half != AxisHalf.FULL ->
                            button(if (src.half == AxisHalf.NEGATIVE) AndroidKey.DPAD_LEFT else AndroidKey.DPAD_RIGHT)
                        a == AndroidAxis.HAT_Y && src.half != AxisHalf.FULL ->
                            button(if (src.half == AxisHalf.NEGATIVE) AndroidKey.DPAD_UP else AndroidKey.DPAD_DOWN)
                        else -> null
                    }
                }
                else -> null
            }
        }

        /** 0 left or 1 right stick for [axis], or -1 (a trigger, the D-pad's hat...). */
        fun stickOf(axis: Int, rightStickRx: Boolean): Int = when (axis) {
            AndroidAxis.X, AndroidAxis.Y -> 0
            AndroidAxis.RX, AndroidAxis.RY -> 1
            AndroidAxis.Z, AndroidAxis.RZ -> if (rightStickRx) -1 else 1
            else -> -1
        }

        private val controls by lazy { PhysicalLayouts.controllerFront.controls + PhysicalLayouts.controllerBack.controls }

        /** [src]'s name as the controller editor shows it: the control ("A", "LT"), then the input unless it is the main one ("LS click", "A tap"). */
        fun name(src: InputSource): String {
            for (c in controls) for (input in c.inputs) {
                if (src !in input.sources) continue
                return if (input.label == "Press" || input.label == "Pull") c.label else c.label + " " + input.label.lowercase()
            }
            return Describe.source(src).removePrefix("Controller ")
        }

        /** What [t] sends, short: as the pad's frames show it, without "Key ". */
        fun targetText(t: OutputTarget): String = Describe.shortTarget(t).removePrefix("Key ")

        /** What a stick's whole axis sends: the mouse or a pad stick by itself ("Mouse", "LS"), else as [targetText]. */
        private fun analogText(t: OutputTarget): String = when {
            t is OutputTarget.MouseAxis && (t.motion == MouseMotion.MOVE_X || t.motion == MouseMotion.MOVE_Y) -> "Mouse"
            t is OutputTarget.PadAxis && (t.axis == GamepadAxis.LX || t.axis == GamepadAxis.LY) -> "LS"
            t is OutputTarget.PadAxis && (t.axis == GamepadAxis.RX || t.axis == GamepadAxis.RY) -> "RS"
            else -> targetText(t)
        }
    }
}

/**
 * One mapping of the Keys overlay's table: its [input] (a button by its OVERLAY bit number, 0..31, as
 * [OverlayFeed.padBit]; [WAY] | stick << 2 | way (0 up, 1 right, 2 down, 3 left); [ANY_WAY] | stick: a whole stick's
 * Outer, any way; [STICK] | stick: the whole stick; [TRIGGER] | trigger << 2 | stage (0 pulled at all, 1 Press, 2 Full pull)),
 * [flags] (the gesture in the low bits, [OUTER], [TOGGLE]), its [name] as the controller editor shows it and what it
 * sends ([target]).
 */
data class OverlayKey(val input: Int, val flags: Int, val name: String, val target: String) {
    companion object {
        const val WAY = 0x40
        const val ANY_WAY = 0x50
        const val STICK = 0x60
        const val TRIGGER = 0x70
        const val HELD = 0
        const val TAP = 1
        const val DOUBLE_TAP = 2
        const val LONG_PRESS = 3
        const val RELEASE = 4
        const val PRESS = 5
        const val OUTER = 0x40
        const val TOGGLE = 0x80
    }
}

/** A touch button's mapping for the Keys overlay: the button ([id], sent as its layout index), [flags] as [OverlayKey.flags], what it sends. */
data class OverlayTouchKey(val id: String, val flags: Int, val target: String)
