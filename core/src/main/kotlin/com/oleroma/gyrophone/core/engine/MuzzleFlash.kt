package com.oleroma.gyrophone.core.engine

/**
 * The muzzle flash on each Fire display shot ([com.oleroma.gyrophone.core.model.GyroDisplay.muzzleFlash]), in
 * time with the kicks ([CrosshairKick.State.shotAt]): a burst of white light that cools to the Flash colour
 * (yellow) by [HOT_MS], then to Flash end (orange) by [END_MS], then fades back to the base colour by
 * [LENGTH_MS]. Its glow ([glow]) is brightest at the shot and gone by [LENGTH_MS].
 */
object MuzzleFlash {
    const val HOT_MS = 30f
    const val END_MS = 110f
    const val LENGTH_MS = 220f

    /** The flash's glow [ms] after a shot: 1 at the shot, easing out to 0 at [LENGTH_MS]; 0 outside it. */
    fun glow(ms: Float): Float {
        if (ms < 0f || ms >= LENGTH_MS) return 0f
        val u = 1f - ms / LENGTH_MS
        return u * u
    }

    /**
     * The flash's colour (ARGB) [ms] after a shot: white, to [hot] by [HOT_MS], to [end] by [END_MS], back to
     * [base] by [LENGTH_MS]; [base] outside it. Alpha blends too.
     */
    fun color(ms: Float, hot: Int, end: Int, base: Int): Int = when {
        ms < 0f || ms >= LENGTH_MS -> base
        ms < HOT_MS -> mix(WHITE, hot, ms / HOT_MS)
        ms < END_MS -> mix(hot, end, (ms - HOT_MS) / (END_MS - HOT_MS))
        else -> mix(end, base, (ms - END_MS) / (LENGTH_MS - END_MS))
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** ARGB colours [a] to [b] by [t] (0 .. 1), per channel. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        var out = 0
        for (s in intArrayOf(24, 16, 8, 0)) {
            val ca = (a ushr s) and 0xFF
            val cb = (b ushr s) and 0xFF
            out = out or ((ca + ((cb - ca) * k).toInt()).coerceIn(0, 255) shl s)
        }
        return out
    }
}
