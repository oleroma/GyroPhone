package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.KeyMode
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.nothing
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.SWIPE_SLOTS
import com.oleroma.gyrophone.core.model.holdInputOf
import com.oleroma.gyrophone.core.model.holdSlot

/**
 * A touch surface's mappings ([InputSource.Surface] with [id]) compiled for [Touchpad]: which
 * inputs are mapped, what mouse buttons and keys each presses, and where its motion goes.
 */
class SurfaceMap(mappings: List<Mapping>, val id: String = "") {
    private val mapped = BooleanArray(BITS)

    /** Per input bit: something maps it that is not a swipe Release ([InputSource.Surface.on]). */
    private val holds = BooleanArray(BITS)

    /** Per input bit: its targets. */
    private val targets = arrayOfNulls<HashSet<OutputTarget>>(BITS)

    /** Per input bit: its targets with their How (to tell whether a tap and its double tap do the same). */
    private val shapes = arrayOfNulls<HashSet<Pair<OutputTarget, Pair<How, Int>>>>(BITS)

    /** Per input bit: the mouse buttons, HID modifier bits and first other key its targets press. */
    internal val mouse = IntArray(BITS)
    internal val mods = IntArray(BITS)
    internal val key = IntArray(BITS)

    /** Per finger count (index fingers - 1): where Move X / Y go. */
    internal val motion = Array(4) { Motion() }

    /** Per finger count and swipe slot ((fingers - 1) * [SWIPE_SLOTS] + slot, [holdSlot]): how many length zones are mapped (at least 1). */
    private val zones = IntArray(4 * SWIPE_SLOTS) { 1 }

    /** Pinch zooms (Ctrl + wheel). */
    internal var zoom = false

    /**
     * Per finger count (index fingers - 1): the held directions that latch ([Touchpad] keeps them
     * held after the lift), a mask of 1 shl [Direction.ordinal]. A direction latches when one of its
     * On swipe chips is a Toggle ([How.TOGGLE]) that also ends on a tap (without, it is a plain Toggle).
     */
    private val latchDirs = IntArray(4)

    /** Per finger count and slot ((fingers - 1) * [SWIPE_SLOTS] + slot): the zones that latch, 1 shl zone. */
    private val latchZoneMask = IntArray(4 * SWIPE_SLOTS)

    /** Per finger count: a latch lets go by itself this long after the lift (ms); 0 = never. */
    internal val latchMs = IntArray(4) { -1 }

    /** Per finger count: a latch also ends on a Let go action / on the next other input. */
    internal val latchLetGo = BooleanArray(4)
    internal val latchNext = BooleanArray(4)
    internal val latchSame = BooleanArray(4)

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
        // A double tap with Held and other chips: the engine tells its second touch apart, so it presses them all.
        val split = HashSet<InputSource>()
        mappings.filter { m ->
            val s = m.source as? InputSource.Surface
            s != null && s.id == id && s.on == Trigger.HOLD && s.input == SurfaceInput.DOUBLE_TAP && !m.target.nothing
        }.groupBy { it.source }.forEach { (src, list) ->
            val held = list.count { (it.options?.mode ?: How.HELD) == How.HELD }
            if (held in 1 until list.size) split.add(src)
        }
        for (m in mappings) {
            val s = m.source as? InputSource.Surface ?: continue
            if (s.id != id || s.fingers !in 1..4) continue
            val dir = s.input.holdSlot
            if (s.zone != 0 && (dir < 0 || s.zone !in 1 until TouchpadSettings.MAX_HOLD_ZONES)) continue
            if (dir >= 0) {
                val z = (s.fingers - 1) * SWIPE_SLOTS + dir
                zones[z] = maxOf(zones[z], s.zone + 1)
            }
            val b = bit(s.fingers, s.input, s.zone)
            mapped[b] = true
            val o = m.options
            if (dir >= 0 && s.on == Trigger.HOLD && o != null && o.mode == How.TOGGLE && o.endsOnTap) {
                val f = s.fingers - 1
                latchDirs[f] = latchDirs[f] or (1 shl dir)
                latchZoneMask[f * SWIPE_SLOTS + dir] = latchZoneMask[f * SWIPE_SLOTS + dir] or (1 shl s.zone)
                if (o.endsOnSame) latchSame[f] = true
                val ms = o.endsAfterMs.coerceIn(0, MapOptions.MAX_TOGGLE_MS)
                // Any chip without a timer keeps it; else the longest timer.
                latchMs[f] = if (latchMs[f] < 0) ms else if (latchMs[f] == 0 || ms == 0) 0 else maxOf(latchMs[f], ms)
                if (o.endsOnLetGo) latchLetGo[f] = true
                if (o.endsOnNextInput) latchNext[f] = true
            }
            if (s.on != Trigger.RELEASE) holds[b] = true
            (targets[b] ?: HashSet<OutputTarget>().also { targets[b] = it }).add(m.target)
            (shapes[b] ?: HashSet<Pair<OutputTarget, Pair<How, Int>>>().also { shapes[b] = it }).add(m.target to (m.options ?: MapOptions()).shape)
            val mo = motion[s.fingers - 1]
            val axis = when (s.input) {
                SurfaceInput.MOTION_X -> StickAxis.X
                SurfaceInput.MOTION_Y -> StickAxis.Y
                else -> null
            }
            // A mapping with options is shaped and pressed by the engine ([MappingEngine]).
            val shaped = m.options != null || s.on != Trigger.HOLD || s in split
            when (val t = m.target) {
                is OutputTarget.Mouse -> if (!shaped) mouse[b] = mouse[b] or (1 shl t.button.bit)
                // Toggle and next-key keys are held by the engine ([MappingEngine]).
                is OutputTarget.Key -> if (t.mode == KeyMode.HOLD && !shaped) when {
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

    /**
     * Per input bit: the bits that share a target with it, itself included, as three words (null
     * when it maps nothing). Their changes show in order and a pulse apart ([Touchpad.poll]), so
     * one output never merges two presses; other inputs change at once.
     */
    internal val peers = arrayOfNulls<LongArray>(BITS)

    init {
        val bits = (0 until BITS).filter { targets[it] != null }
        for (b in bits) {
            val tb = targets[b]!!
            val w = LongArray(3)
            for (c in bits) if (c == b || targets[c]!!.any { it in tb }) w[c shr 6] = w[c shr 6] or (1L shl (c and 63))
            peers[b] = w
        }
    }

    fun has(fingers: Int, input: SurfaceInput, zone: Int = 0): Boolean =
        fingers in 1..4 && zone in 0 until TouchpadSettings.MAX_HOLD_ZONES && mapped[bit(fingers, input, zone)]

    /**
     * The zones of held direction [d] with [fingers] mapped only to fire as the finger lifts in
     * them (swipe Release), a mask of 1 shl zone: nothing holds them ([DirectionTracker]).
     */
    fun releaseOnly(fingers: Int, slot: Int): Int {
        if (fingers !in 1..4) return 0
        var m = 0
        for (z in 0 until TouchpadSettings.MAX_HOLD_ZONES) {
            val b = bit(fingers, holdInputOf(slot), z)
            if (mapped[b] && !holds[b]) m = m or (1 shl z)
        }
        return m
    }

    fun releaseOnly(fingers: Int, d: Direction): Int = releaseOnly(fingers, d.ordinal)

    /** Held swipe slot [slot] with [fingers] latches (see [latchDirs]). */
    fun latches(fingers: Int, slot: Int): Boolean = fingers in 1..4 && latchDirs[fingers - 1] and (1 shl slot) != 0
    fun latches(fingers: Int, d: Direction): Boolean = latches(fingers, d.ordinal)

    /** The zones of held swipe slot [slot] with [fingers] that latch (their On swipe chip is a Toggle), 1 shl zone. */
    fun latchZones(fingers: Int, slot: Int): Int = if (fingers in 1..4) latchZoneMask[(fingers - 1) * SWIPE_SLOTS + slot] else 0
    fun latchZones(fingers: Int, d: Direction): Int = latchZones(fingers, d.ordinal)

    /** How many length zones held swipe slot [slot] with [fingers] has mapped (at least 1). */
    fun zoneCount(fingers: Int, slot: Int): Int = if (fingers in 1..4) zones[(fingers - 1) * SWIPE_SLOTS + slot] else 1
    fun zoneCount(fingers: Int, d: Direction): Int = zoneCount(fingers, d.ordinal)

    /**
     * A tap with [fingers] waits out the double-tap window: its double tap is mapped and does
     * something else than the tap (mapped alike, a double click, the tap stays instant).
     */
    fun tapWaits(fingers: Int): Boolean {
        if (!has(fingers, SurfaceInput.DOUBLE_TAP)) return false
        return shapes[bit(fingers, SurfaceInput.DOUBLE_TAP)] != (shapes[bit(fingers, SurfaceInput.TAP)]
            ?: emptySet<Pair<OutputTarget, Pair<How, Int>>>())
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

        /** Every input per finger count, then the held swipe slots' later zones. */
        val BITS = 4 * INPUTS + 4 * SWIPE_SLOTS * (TouchpadSettings.MAX_HOLD_ZONES - 1)

        init {
            // Touchpad keeps the inputs' states in three 64-bit words.
            check(BITS <= 192)
        }

        /** The bit of [input] with [fingers]; [zone] > 0 only for a held swipe slot. */
        fun bit(fingers: Int, input: SurfaceInput, zone: Int = 0): Int {
            if (zone == 0) return (fingers - 1) * INPUTS + input.ordinal
            val d = input.holdSlot
            if (d < 0) return (fingers - 1) * INPUTS + input.ordinal
            return 4 * INPUTS + ((fingers - 1) * SWIPE_SLOTS + d) * (TouchpadSettings.MAX_HOLD_ZONES - 1) + zone - 1
        }

        private val MOVES = listOf(
            SurfaceInput.MOTION_X, SurfaceInput.MOTION_Y,
            SurfaceInput.SWIPE_LEFT, SurfaceInput.SWIPE_RIGHT, SurfaceInput.SWIPE_UP, SurfaceInput.SWIPE_DOWN,
            SurfaceInput.HOLD_LEFT, SurfaceInput.HOLD_RIGHT, SurfaceInput.HOLD_UP, SurfaceInput.HOLD_DOWN,
            SurfaceInput.HOLD_ANY, SurfaceInput.SWIPE_ANY,
        )
    }
}
