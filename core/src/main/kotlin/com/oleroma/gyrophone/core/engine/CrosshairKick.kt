package com.oleroma.gyrophone.core.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * The crosshair's fire kick ([com.oleroma.gyrophone.core.model.GyroDisplay.crosshairKick]): each shot it
 * jumps out and bounces back like a rifle's recoil. The bounce is one curve baked into [CURVE] once
 * (a sharp rise over [RISE_MS], then a damped spring that dips a little under its rest size and settles
 * by [LENGTH_MS]), so a frame only reads the table. [ONCE] kicks once per press; [AUTO] keeps kicking
 * every [periodMs] while the Fire display button is held, each shot rising from wherever the last one is.
 */
object CrosshairKick {
    const val OFF = 0
    const val ONCE = 1
    const val AUTO = 2

    /** The modes' names, by number. */
    val MODES = listOf("Off", "Once", "Auto rifle")

    const val RISE_MS = 24f
    const val LENGTH_MS = 280f
    private const val DECAY_MS = 42f
    private const val SWING_MS = 150f
    private const val STEPS = 128

    /** The bounce after the rise, from 1 at [RISE_MS] to 0 at [LENGTH_MS], one entry per [LENGTH_MS] / [STEPS]. */
    val CURVE: FloatArray = FloatArray(STEPS + 1) { i ->
        val t = i * LENGTH_MS / STEPS
        if (t <= RISE_MS) 1f
        else {
            val s = t - RISE_MS
            // Fades out over the last fifth so it ends at exactly 0.
            val tail = ((LENGTH_MS - t) / (LENGTH_MS * 0.2f)).coerceIn(0f, 1f)
            (exp(-s / DECAY_MS) * cos(2f * PI.toFloat() * s / SWING_MS) * tail)
        }
    }.also { it[STEPS] = 0f }

    /** The baked curve at [ms] after a shot that rose from [from] (0 rest .. 1 full kick). */
    fun at(ms: Float, from: Float): Float {
        if (ms < 0f || ms >= LENGTH_MS) return 0f
        if (ms < RISE_MS) {
            // Eases out of the shot: fast at first, like the kick.
            val u = ms / RISE_MS
            val e = 1f - (1f - u) * (1f - u)
            return from + (1f - from) * e
        }
        val x = ms / LENGTH_MS * STEPS
        val i = x.toInt().coerceAtMost(STEPS - 1)
        val f = x - i
        return CURVE[i] + (CURVE[i + 1] - CURVE[i]) * f
    }

    /** Auto rifle's shots per minute to a shot's period in milliseconds. */
    fun periodMs(rpm: Int): Long = (60_000L / rpm.coerceIn(60, 1800))

    /** One crosshair's kick: [step] it each frame with whether it fires; [value] is −0.2 .. 1 (0 rest). */
    class State {
        private var shotAt = -1L
        private var from = 0f
        private var held = false
        var value = 0f
            private set

        /** At [t] ms, with [firing] held, in [mode] at [periodMs] per shot: the kick now; true while it still moves. */
        fun step(t: Long, firing: Boolean, mode: Int, periodMs: Long): Boolean {
            if (mode == OFF) {
                held = firing
                shotAt = -1L
                value = 0f
                return false
            }
            val now = if (shotAt < 0L) 0f else at((t - shotAt).toFloat(), from)
            if (firing && !held) shoot(t, now)
            else if (firing && mode == AUTO && t - shotAt >= periodMs) {
                // Keeps the beat; a long stall (a dropped frame or two) starts it again from now.
                val at = shotAt + periodMs
                shoot(if (t - at < periodMs) at else t, now)
            }
            held = firing
            if (shotAt >= 0L && t - shotAt >= LENGTH_MS) shotAt = -1L
            value = if (shotAt < 0L) 0f else at((t - shotAt).toFloat(), from)
            return shotAt >= 0L || (firing && mode == AUTO)
        }

        private fun shoot(t: Long, now: Float) {
            from = now.coerceIn(0f, 1f)
            shotAt = t
        }
    }
}
