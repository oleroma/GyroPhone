package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.SurfaceInput

/**
 * A touch surface's mappings ([InputSource.Surface] with [id]) compiled for [Touchpad]: which
 * inputs are mapped, what mouse buttons and keys each presses, and where its motion goes.
 */
class SurfaceMap(mappings: List<Mapping>, val id: String = "") {
    private val mapped = BooleanArray(BITS)

    /** Per input bit: its targets (to tell whether a tap and its double tap do the same). */
    private val targets = arrayOfNulls<HashSet<OutputTarget>>(BITS)

    /** Per input bit: the mouse buttons, HID modifier bits and first other key its targets press. */
    internal val mouse = IntArray(BITS)
    internal val mods = IntArray(BITS)
    internal val key = IntArray(BITS)

    /** Per finger count (index fingers - 1): where Move X / Y go. */
    internal val motion = Array(4) { Motion() }

    /** Pinch zooms (Ctrl + wheel). */
    internal var zoom = false

    /**
     * Motion routing: cursor counts and wheel units out per unit of X / Y travel in (X in, Y in).
     * Sideways wheel already carries its sign (natural: the page follows the finger).
     */
    internal class Motion {
        var cxx = 0f;
        var cxy = 0f;
        var cyx = 0f;
        var cyy = 0f
        var wx = 0f;
        var wy = 0f;
        var hx = 0f;
        var hy = 0f
        var switchApps = false
        val cursor get() = cxx != 0f || cxy != 0f || cyx != 0f || cyy != 0f
        val scroll get() = wx != 0f || wy != 0f || hx != 0f || hy != 0f
    }

    init {
        for (m in mappings) {
            val s = m.source as? InputSource.Surface ?: continue
            if (s.id != id || s.fingers !in 1..4) continue
            val b = bit(s.fingers, s.input)
            mapped[b] = true
            (targets[b] ?: HashSet<OutputTarget>().also { targets[b] = it }).add(m.target)
            val mo = motion[s.fingers - 1]
            val axis = when (s.input) {
                SurfaceInput.MOTION_X -> StickAxis.X
                SurfaceInput.MOTION_Y -> StickAxis.Y
                else -> null
            }
            when (val t = m.target) {
                is OutputTarget.Mouse -> mouse[b] = mouse[b] or (1 shl t.button.bit)
                is OutputTarget.Key -> when {
                    HidUsage.isModifier(t.usage) -> mods[b] = mods[b] or (1 shl (t.usage - HidUsage.LEFT_CTRL))
                    key[b] == 0 -> key[b] = t.usage and 0xFF
                }

                is OutputTarget.MouseAxis -> if (axis != null) {
                    val k = t.scale
                    val x = axis == StickAxis.X
                    when (t.motion) {
                        MouseMotion.MOVE_X -> if (x) mo.cxx += k else mo.cxy += k
                        MouseMotion.MOVE_Y -> if (x) mo.cyx += k else mo.cyy += k
                        MouseMotion.WHEEL -> if (x) mo.wx += k else mo.wy += k
                        MouseMotion.HWHEEL -> if (x) mo.hx -= k else mo.hy -= k
                    }
                }

                OutputTarget.SwitchApps -> if (axis == StickAxis.X) mo.switchApps = true
                OutputTarget.Zoom -> if (s.input == SurfaceInput.PINCH) zoom = true
                else -> Unit
            }
        }
    }

    fun has(fingers: Int, input: SurfaceInput): Boolean = fingers in 1..4 && mapped[bit(fingers, input)]

    /**
     * A tap with [fingers] waits out the double-tap window: its double tap is mapped and does
     * something else than the tap (mapped alike, a double click, the tap stays instant).
     */
    fun tapWaits(fingers: Int): Boolean {
        if (!has(fingers, SurfaceInput.DOUBLE_TAP)) return false
        return targets[bit(fingers, SurfaceInput.DOUBLE_TAP)] != (targets[bit(fingers, SurfaceInput.TAP)]
            ?: emptySet<OutputTarget>())
    }

    /** Something happens on a tap with [fingers]: a tap, double tap or tap-drag mapping. */
    fun taps(fingers: Int): Boolean =
        has(fingers, SurfaceInput.TAP) || has(fingers, SurfaceInput.DOUBLE_TAP) || has(fingers, SurfaceInput.TAP_DRAG)

    /** Something follows the travel of [fingers]: a move, swipe or held direction mapping. */
    fun moves(fingers: Int): Boolean = MOVES.any { has(fingers, it) }

    /** Something follows a two-finger pinch. */
    fun pinches(): Boolean =
        has(2, SurfaceInput.PINCH) || has(2, SurfaceInput.PINCH_IN) || has(2, SurfaceInput.PINCH_OUT)

    companion object {
        val INPUTS = SurfaceInput.entries.size
        val BITS = 4 * INPUTS
        fun bit(fingers: Int, input: SurfaceInput): Int = (fingers - 1) * INPUTS + input.ordinal
        private val MOVES = listOf(
            SurfaceInput.MOTION_X, SurfaceInput.MOTION_Y,
            SurfaceInput.SWIPE_LEFT, SurfaceInput.SWIPE_RIGHT, SurfaceInput.SWIPE_UP, SurfaceInput.SWIPE_DOWN,
            SurfaceInput.HOLD_LEFT, SurfaceInput.HOLD_RIGHT, SurfaceInput.HOLD_UP, SurfaceInput.HOLD_DOWN,
        )
    }
}
