package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.inPie
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where a swipe surface's held directions and their length zones lie for one finger, for the
 * pad's swipe line ([MappingEngine.swipeGuide]). [zones]: per direction ([Direction.ordinal]) how
 * many zones are mapped, 0 = no Hold mapped. Hold mode From start only, without drag start (the
 * start point is then where the finger landed).
 */
class SwipeGuide(val settings: TouchpadSettings, private val zones: IntArray) {
    /**
     * A finger [dx], [dy] dp from where it landed: writes to [out] how far along the line through
     * it (dp from where it landed) the edges of its direction's zones are: where the first zone
     * starts, then where each mapped zone ends (with one zone, only where it starts). Returns how
     * many it wrote; 0 = no mapped direction that way. A zone is reached by the travel along its
     * direction, so on a slanted line the edges sit further out ([MIN_COS] at most).
     */
    fun edges(dx: Float, dy: Float, out: FloatArray): Int {
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0f) return 0
        val s = settings
        var best: Direction? = null
        var bestAlong = 0f
        for (d in Direction.entries) {
            if (zones[d.ordinal] == 0) continue
            val along = when (d) {
                Direction.UP -> -dy
                Direction.RIGHT -> dx
                Direction.DOWN -> dy
                Direction.LEFT -> -dx
            }
            if (along <= bestAlong || (s.pies != null && !s.inPie(dx, dy, d))) continue
            best = d; bestAlong = along
        }
        val d = best ?: return 0
        val cos = max(bestAlong / len, MIN_COS)
        val n = zones[d.ordinal].coerceIn(1, TouchpadSettings.MAX_HOLD_ZONES)
        var edge = s.holdDp.coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)
        out[0] = edge / cos
        if (n < 2) return 1
        for (i in 0 until n) {
            edge += s.holdZoneDp(d, i)
            out[i + 1] = edge / cos
        }
        return n + 1
    }

    companion object {
        /** The most edges [edges] writes. */
        const val MAX_EDGES = TouchpadSettings.MAX_HOLD_ZONES + 1

        /** Lines slanted further than this from their direction (cosine) draw their edges as at this slant. */
        const val MIN_COS = 0.25f
    }
}
