package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.inPie
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where a swipe surface's activation lengths lie for one finger, for the pad's swipe line
 * ([MappingEngine.swipeGuide]): Swipe after, Hold after and the ends of the length zones, per
 * direction as mapped. [zones]: per direction ([Direction.ordinal]) how many zones its Hold has, 0 =
 * no Hold mapped; [swipes]: the directions with a Swipe mapped (mask of 1 shl ordinal).
 */
class SwipeGuide(val settings: TouchpadSettings, private val zones: IntArray, private val swipes: Int) {
    /** Hold mode From start: zones count the travel along their direction, from the start point. */
    private val fromStart = settings.holdMode == HoldMode.FROM_START

    /** The start point is dragged along ([TouchpadSettings.dragStart]); see [drag]. */
    val dragsStart = fromStart && settings.dragStart

    /**
     * From start without [dragsStart]: the line runs along its direction ([direction]), the finger
     * projected onto it, since that is the travel its zones count.
     */
    val onAxis = fromStart && !dragsStart

    private fun holdDp(d: Direction) = activate(settings.holdDpOf(d))

    /** The farthest hold distance: where a dragged start point stops trailing. */
    private val maxHoldDp = Direction.entries.maxOf { holdDp(it) }

    /** The direction a finger [dx], [dy] dp from the start point is in for the line; null = none mapped that way. */
    fun direction(dx: Float, dy: Float): Direction? {
        var best: Direction? = null
        var bestAlong = 0f
        for (d in Direction.entries) {
            if (zones[d.ordinal] == 0 && swipes and (1 shl d.ordinal) == 0) continue
            val along = along(d, dx, dy)
            // A direction with a Swipe hold uses its hold pie, one with only a Swipe its swipe pie.
            val hold = zones[d.ordinal] != 0
            if (along <= bestAlong || (settings.pies != null && !settings.inPie(dx, dy, d, hold))) continue
            best = d; bestAlong = along
        }
        return best
    }

    /**
     * A finger [dx], [dy] dp from the start point: writes to [out] how far along the line through it
     * (dp from the start point) its direction's marks are, and their [kinds] ([SWIPE], [HOLD],
     * [ZONE_END]: the ends of the zones, with 2+ zones). Returns how many; 0 = nothing mapped that way.
     * From start, a zone is reached by the travel along its direction, so on a slanted line its
     * marks sit further out ([MIN_COS] at most); a swipe and Direction change holds count the travel itself.
     */
    fun marks(dx: Float, dy: Float, out: FloatArray, kinds: IntArray): Int {
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0f) return 0
        val s = settings
        val d = direction(dx, dy) ?: return 0
        val bestAlong = along(d, dx, dy)
        var n = 0
        if (swipes and (1 shl d.ordinal) != 0) {
            out[n] = activate(s.swipeDpOf(d)); kinds[n++] = SWIPE
        }
        val count = zones[d.ordinal]
        if (count == 0) return n
        val cos = if (fromStart) max(bestAlong / len, MIN_COS) else 1f
        var edge = holdDp(d)
        out[n] = edge / cos; kinds[n++] = HOLD
        if (dragsStart || count < 2) return n
        for (i in 0 until count.coerceAtMost(TouchpadSettings.MAX_HOLD_ZONES)) {
            edge += s.holdZoneDp(d, i)
            out[n] = edge / cos; kinds[n++] = ZONE_END
        }
        return n
    }

    /**
     * With [dragsStart]: the start point ([start]: x, y dp) follows the finger at [fx], [fy] so it
     * never trails by more than Hold after + the release distance; once no direction is held after
     * one was ([held]), it jumps to the finger. Returns whether a direction is held now.
     */
    fun drag(start: FloatArray, fx: Float, fy: Float, held: Boolean): Boolean {
        val s = settings
        var ox = fx - start[0]
        var oy = fy - start[1]
        val most = maxHoldDp + s.releaseDp.coerceIn(0f, TouchpadSettings.MAX_RELEASE_DP)
        val dist = sqrt(ox * ox + oy * oy)
        if (dist > most) {
            ox *= most / dist; oy *= most / dist
            start[0] = fx - ox; start[1] = fy - oy
        }
        val now = Direction.entries.any { along(it, ox, oy) >= holdDp(it) && (s.pies == null || s.inPie(ox, oy, it, hold = true)) }
        if (!now && held) {
            start[0] = fx; start[1] = fy
        }
        return now
    }

    /** How far [dx], [dy] goes along [d]. */
    fun along(d: Direction, dx: Float, dy: Float) = when (d) {
        Direction.UP -> -dy
        Direction.RIGHT -> dx
        Direction.DOWN -> dy
        Direction.LEFT -> -dx
    }

    companion object {
        /** Mark kinds ([marks]). */
        const val SWIPE = 0
        const val HOLD = 1
        const val ZONE_END = 2

        /** The most marks [marks] writes. */
        const val MAX_MARKS = TouchpadSettings.MAX_HOLD_ZONES + 2

        /** Lines slanted further than this from their direction (cosine) draw their marks as at this slant. */
        const val MIN_COS = 0.25f

        private fun activate(dp: Float) = dp.coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)
    }
}
