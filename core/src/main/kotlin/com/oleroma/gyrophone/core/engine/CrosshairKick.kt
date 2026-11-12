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
 * The rifle look kicks back along the same curve, and the muzzle flash ([MuzzleFlash]) times itself by
 * the same shots ([State.shotAt]).
 */
object CrosshairKick {
    const val OFF = 0
    const val ONCE = 1
    const val AUTO = 2

    /** The modes' names, by number. */
    val MODES = listOf("Off", "Once", "Auto rifle")

    const val RISE_MS = 24f
    const val LENGTH_MS = 280f
    private const val STEPS = 128

    /**
     * One gun's recoil ([Guns.Gun.recoil]): a sharp rise over [riseMs], then a damped spring (dying away over
     * [decayMs], swinging every [swingMs]) settling by [lengthMs], baked into [curve] once. The gun look kicks
     * [back] toward the player and its muzzle up [climbDeg] at full kick; the crosshair kicks by [strength].
     */
    class Recoil(
        val riseMs: Float, val decayMs: Float, val swingMs: Float, val lengthMs: Float,
        val back: Float, val climbDeg: Float,
    ) {
        /** The bounce after the rise, from 1 just at [riseMs] to 0 at [lengthMs], one entry per ([lengthMs] − [riseMs]) / [STEPS]. */
        val curve: FloatArray = FloatArray(STEPS + 1) { i ->
            val s = i * (lengthMs - riseMs) / STEPS
            // Fades out over the last fifth so it ends at exactly 0.
            val tail = ((lengthMs - riseMs - s) / (lengthMs * 0.2f)).coerceIn(0f, 1f)
            exp(-s / decayMs) * cos(2f * PI.toFloat() * s / swingMs) * tail
        }.also { it[STEPS] = 0f }

        /** How hard it kicks next to the assault rifle (1). */
        val strength: Float get() = back / 0.12f

        /** The baked curve at [ms] after a shot that rose from [from] (0 rest .. 1 full kick). */
        fun at(ms: Float, from: Float): Float {
            if (ms < 0f || ms >= lengthMs) return 0f
            if (ms < riseMs) {
                // Eases out of the shot: fast at first, like the kick.
                val u = ms / riseMs
                val e = 1f - (1f - u) * (1f - u)
                return from + (1f - from) * e
            }
            val x = (ms - riseMs) / (lengthMs - riseMs) * STEPS
            val i = x.toInt().coerceAtMost(STEPS - 1)
            val f = x - i
            return curve[i] + (curve[i + 1] - curve[i]) * f
        }
    }

    /** The assault rifle's recoil, the crosshair's before guns. */
    val DEFAULT = Recoil(RISE_MS, 42f, 150f, LENGTH_MS, back = 0.12f, climbDeg = 10f)

    /** [DEFAULT]'s baked curve. */
    val CURVE: FloatArray get() = DEFAULT.curve

    /** [DEFAULT] at [ms] after a shot that rose from [from] (0 rest .. 1 full kick). */
    fun at(ms: Float, from: Float): Float = DEFAULT.at(ms, from)

    /** Auto rifle's shots per minute to a shot's period in milliseconds. */
    fun periodMs(rpm: Int): Long = (60_000L / rpm.coerceIn(RPM_MIN, 1800))

    /** The slowest fire rate, shots a minute (a sniper's bolt). */
    const val RPM_MIN = 30

    /** Bloom: shots in a row widen the spread up to twice over this many shots. */
    const val BLOOM_SHOTS = 10

    /** Bloom settles this long after letting go of fire (ms). */
    const val BLOOM_SETTLE_MS = 500L

    /** The most pellets a shot fires. */
    const val PELLETS_MAX = 8

    /** One crosshair's or rifle's kick: [step] it each frame with whether it fires; [value] is −0.2 .. 1 (0 rest). */
    class State {
        /** When the last shot was (ms), until its kick and flash are over; −1 none. */
        var shotAt = -1L
            private set
        private var from = 0f
        private var held = false
        var value = 0f
            private set

        /** Shots so far: goes up by one each shot. */
        var shots = 0L
            private set

        /**
         * The last shot's pellets ([pellets] of them) as offsets from the aim point (x right, y up, pairs), each
         * within the unit circle, a random way and distance; times [bloom], the spread it was fired with.
         */
        val offsets = FloatArray(PELLETS_MAX * 2)
        var pellets = 1
            private set

        /** The bloom the last shot was fired with (1 .. 2): shots in a row widen the spread. */
        var bloom = 1f
            private set

        /** Where the gun and crosshair kick toward: the pellets' middle (x, y as [offsets]). */
        var towardX = 0f
            private set
        var towardY = 0f
            private set

        private var streak = 0
        private var releasedAt = Long.MIN_VALUE / 2
        private var pelletsNext = 1
        private val random = kotlin.random.Random(System.nanoTime())

        /**
         * At [t] ms, with [firing] held, in [mode] at [periodMs] per shot: the kick now; true while it still moves.
         * With [flash] the shots still count in [OFF] (once per press) for the muzzle flash, without kicking.
         */
        fun step(t: Long, firing: Boolean, mode: Int, periodMs: Long, flash: Boolean = false, recoil: Recoil = DEFAULT, pellets: Int = 1): Boolean {
            pelletsNext = pellets.coerceIn(1, PELLETS_MAX)
            if (held && !firing) releasedAt = t
            if (mode == OFF && flash) {
                val moving = step(t, firing, ONCE, periodMs, recoil = recoil, pellets = pellets)
                value = 0f
                return moving
            }
            if (mode == OFF) {
                held = firing
                shotAt = -1L
                value = 0f
                return false
            }
            val now = if (shotAt < 0L) 0f else recoil.at((t - shotAt).toFloat(), from)
            if (firing && !held) shoot(t, now)
            else if (firing && mode == AUTO && t - shotAt >= periodMs) {
                // Keeps the beat; a long stall (a dropped frame or two) starts it again from now.
                val at = shotAt + periodMs
                shoot(if (t - at < periodMs) at else t, now)
            }
            held = firing
            // Kept until both the kick and its muzzle flash are over.
            if (shotAt >= 0L && t - shotAt >= maxOf(recoil.lengthMs, MuzzleFlash.LENGTH_MS)) shotAt = -1L
            value = if (shotAt < 0L) 0f else recoil.at((t - shotAt).toFloat(), from)
            return shotAt >= 0L || (firing && mode == AUTO)
        }

        private fun shoot(t: Long, now: Float) {
            from = now.coerceIn(0f, 1f)
            // Bloom: a new press after settling starts the streak again.
            if (!held && t - releasedAt >= BLOOM_SETTLE_MS) streak = 0
            bloom = 1f + minOf(streak, BLOOM_SHOTS).toFloat() / BLOOM_SHOTS
            streak++
            pellets = pelletsNext
            towardX = 0f
            towardY = 0f
            for (i in 0 until pellets) {
                // Evenly over the circle: a random way, the distance's square root.
                val a = random.nextFloat() * 2f * Math.PI.toFloat()
                val d = kotlin.math.sqrt(random.nextFloat())
                offsets[i * 2] = d * kotlin.math.cos(a)
                offsets[i * 2 + 1] = d * kotlin.math.sin(a)
                towardX += offsets[i * 2] / pellets
                towardY += offsets[i * 2 + 1] / pellets
            }
            shots++
            shotAt = t
        }
    }
}
