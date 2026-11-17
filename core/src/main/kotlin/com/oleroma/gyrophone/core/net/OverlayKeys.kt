package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.AxisStage
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import com.oleroma.gyrophone.core.model.nothing

/**
 * What the PC's Keys overlay shows of the game pad attached to the phone: a row for each of its sticks that sends
 * something ([sticks]), and a row for each button firing now or with a Toggle on ([rows]). The PC keeps each row
 * in its place while it lasts and fades it out when it goes; this only says what is on now.
 */
data class OverlayKeys(val sticks: List<OverlayKeyStick>, val rows: List<OverlayKeyRow>) {
    companion object {
        val NONE = OverlayKeys(emptyList(), emptyList())
    }
}

/**
 * A stick's row: [stick] [LEFT] or [RIGHT]; [analog] what its whole axes send ("Mouse", "LS"; "" none); [keys] what
 * each way sends (up, right, down, left; "" none) and [lit] the ways firing now ([UP], [RIGHT_WAY], [DOWN], [LEFT_WAY] bits).
 */
data class OverlayKeyStick(val stick: Int, val analog: String, val keys: List<String>, val lit: Int) {
    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val UP = 1
        const val RIGHT_WAY = 2
        const val DOWN = 4
        const val LEFT_WAY = 8
    }
}

/**
 * A button's row: its [name] as the controller editor shows it ("A", "LT", "LS click", "A long press") and what it
 * sends now ([target]: "Space", several joined by " + "); [flags] [TOGGLE] (on only by a Toggle on), [LEFT_TRIGGER] or
 * [RIGHT_TRIGGER] (it is that trigger: the PC shows how far it is pulled).
 */
data class OverlayKeyRow(val name: String, val target: String, val flags: Int) {
    companion object {
        const val TOGGLE = 1
        const val LEFT_TRIGGER = 2
        const val RIGHT_TRIGGER = 4
    }
}

/**
 * The attached pad's mappings in use ([ms], from [com.oleroma.gyrophone.core.engine.MappingEngine.padKeys]) sorted
 * once into the Keys overlay's rows, so each read only checks which are on ([keys]).
 */
class OverlayKeysTable(val ms: List<Mapping>, rightStickRx: Boolean) {
    // Per mapping: its button row's index in [names] (-1 none), else its stick and way.
    private val row = IntArray(ms.size) { -1 }
    private val stickOf = IntArray(ms.size) { -1 }
    private val wayOf = IntArray(ms.size)
    private val text = Array(ms.size) { "" }
    private val names = ArrayList<String>()
    private val rowFlags = ArrayList<Int>()
    private val stickAnalog = arrayOf("", "")
    private val stickKeys = arrayOf(arrayOf("", "", "", ""), arrayOf("", "", "", ""))
    private val stickUsed = BooleanArray(2)

    init {
        val analog = arrayOf(LinkedHashSet<String>(), LinkedHashSet<String>())
        for ((i, m) in ms.withIndex()) {
            if (!shown(m)) continue
            val src = m.source
            text[i] = targetText(m.target)
            val stick = if (src is InputSource.Axis) stickOf(src.axis, rightStickRx) else -1
            if (src is InputSource.Axis && stick >= 0) {
                stickUsed[stick] = true
                stickOf[i] = stick
                if (src.half == AxisHalf.FULL) {
                    if (src.stage == AxisStage.NONE) {
                        analog[stick].add(analogText(m.target))
                        stickOf[i] = -1 // shown by the dot, not lit
                    } else {
                        // A whole stick's Outer: a button row of its own.
                        stickOf[i] = -1
                        row[i] = rowOf(name(src), 0)
                    }
                    continue
                }
                val way = wayOf(src.axis, src.half, rightStickRx)
                wayOf[i] = way
                val slot = WAYS.indexOf(way)
                // Each way's first mapping names it; an Outer one only when nothing else does.
                if (stickKeys[stick][slot].isEmpty() || src.stage == AxisStage.NONE && outerOnly(stick, slot, i)) {
                    stickKeys[stick][slot] = text[i]
                }
                continue
            }
            row[i] = rowOf(name(src), triggerFlag(src, rightStickRx))
        }
        for (s in 0..1) stickAnalog[s] = joinAnalog(analog[s])
    }

    /** Whether way [slot] of [stick] is named only by Outer mappings before mapping [upTo]. */
    private fun outerOnly(stick: Int, slot: Int, upTo: Int): Boolean {
        for (j in 0 until upTo) {
            val s = ms[j].source as? InputSource.Axis ?: continue
            if (stickOf[j] != stick || WAYS.indexOf(wayOf[j]) != slot) continue
            if (s.stage == AxisStage.NONE) return false
        }
        return true
    }

    private fun rowOf(name: String, flags: Int): Int {
        val k = names.indexOf(name)
        if (k >= 0) return k
        names.add(name)
        rowFlags.add(flags)
        return names.size - 1
    }

    /** The rows now, from which of [ms] are [on] and [toggled] (as the engine's padKeys gives them). */
    fun keys(on: BooleanArray, toggled: BooleanArray): OverlayKeys {
        val sticks = ArrayList<OverlayKeyStick>(2)
        for (s in 0..1) {
            if (!stickUsed[s]) continue
            var lit = 0
            for (i in ms.indices) if (stickOf[i] == s && (on[i] || toggled[i])) lit = lit or wayOf[i]
            sticks.add(OverlayKeyStick(s, stickAnalog[s], stickKeys[s].toList(), lit))
        }
        val rows = ArrayList<OverlayKeyRow>()
        for (r in names.indices) {
            var targets: StringBuilder? = null
            var pressed = false
            for (i in ms.indices) {
                if (row[i] != r) continue
                val held = on[i] && ms[i].options?.mode != How.TOGGLE
                if (!held && !toggled[i]) continue
                if (held) pressed = true
                val t = text[i] + if (toggled[i]) ON else ""
                val b = targets ?: StringBuilder().also { targets = it }
                if (b.isNotEmpty()) b.append(" + ")
                b.append(t)
            }
            val b = targets ?: continue
            rows.add(OverlayKeyRow(names[r], b.toString(), rowFlags[r] or if (pressed) 0 else OverlayKeyRow.TOGGLE))
        }
        return OverlayKeys(sticks, rows)
    }

    companion object {
        private const val ON = " on"
        private val WAYS = intArrayOf(OverlayKeyStick.UP, OverlayKeyStick.RIGHT_WAY, OverlayKeyStick.DOWN, OverlayKeyStick.LEFT_WAY)

        /** A mapping that gets a row: not empty, a haptic or a gyro card. */
        private fun shown(m: Mapping): Boolean {
            val t = m.target
            if (t.nothing || Describe.isHaptic(t)) return false
            return m.source is InputSource.Key || m.source is InputSource.KeyGesture || m.source is InputSource.Axis
        }

        /** [LEFT] or [RIGHT] stick for [axis], or -1 (a trigger, the D-pad's hat...). */
        fun stickOf(axis: Int, rightStickRx: Boolean): Int = when (axis) {
            AndroidAxis.X, AndroidAxis.Y -> OverlayKeyStick.LEFT
            AndroidAxis.RX, AndroidAxis.RY -> OverlayKeyStick.RIGHT
            AndroidAxis.Z, AndroidAxis.RZ -> if (rightStickRx) -1 else OverlayKeyStick.RIGHT
            else -> -1
        }

        private fun wayOf(axis: Int, half: AxisHalf, rx: Boolean): Int {
            val y = axis == AndroidAxis.Y || axis == AndroidAxis.RY || axis == AndroidAxis.RZ && !rx
            return if (y) {
                if (half == AxisHalf.NEGATIVE) OverlayKeyStick.UP else OverlayKeyStick.DOWN
            } else {
                if (half == AxisHalf.NEGATIVE) OverlayKeyStick.LEFT_WAY else OverlayKeyStick.RIGHT_WAY
            }
        }

        private fun triggerFlag(src: InputSource, rx: Boolean): Int {
            fun left(a: Int) = a == AndroidAxis.LTRIGGER || a == AndroidAxis.BRAKE || rx && a == AndroidAxis.Z
            fun right(a: Int) = a == AndroidAxis.RTRIGGER || a == AndroidAxis.GAS || rx && a == AndroidAxis.RZ
            val code = when (src) {
                is InputSource.Key -> src.keyCode
                is InputSource.KeyGesture -> src.keyCode
                else -> -1
            }
            return when {
                src is InputSource.Axis && left(src.axis) || code == AndroidKey.BUTTON_L2 -> OverlayKeyRow.LEFT_TRIGGER
                src is InputSource.Axis && right(src.axis) || code == AndroidKey.BUTTON_R2 -> OverlayKeyRow.RIGHT_TRIGGER
                else -> 0
            }
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

        private fun joinAnalog(s: Set<String>): String = s.joinToString(" + ")
    }
}
