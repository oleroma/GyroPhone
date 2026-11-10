package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.RestFade
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * "Fade when phone rests" on the phone (the companion's OverlayRest, ported): tells when the phone
 * lies on a table. It rests for a display while, for the whole quiet time, it lies within the set
 * angle of that display's Level tilt, turns slower than the stillness threshold (measured over
 * [WINDOW_S] at a time, as the PC sees it per OVERLAY), and gets no input ([input]). Held in a hand it
 * always turns faster than the threshold (a steady hand shakes at 1-3°/s), and without gyro samples
 * lately it counts as moving, so a held phone never rests. Gyro and accelerometer samples come on
 * their threads, input and [resting] on others: everything is under one lock.
 */
class RestDetector {
    @Volatile var settings = RestFade()

    private val lock = Any()
    private var busyNs = Long.MIN_VALUE
    private var gyroAtNs = Long.MIN_VALUE
    private var sumYaw = 0f
    private var sumPitch = 0f
    private var sumRoll = 0f
    private var sumDt = 0f
    private val upNow = FloatArray(3)
    private var hasUp = false
    private val offLevelNs = HashMap<String, Long>()
    private var liveDps = Float.NaN
    private var liveAtNs = Long.MIN_VALUE

    /** One gyro sample: rates in deg/s ([GyroMouse.motion]) over [dtSeconds]. */
    fun gyro(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, nowNs: Long) {
        synchronized(lock) {
            gyroAtNs = nowNs
            if (busyNs == Long.MIN_VALUE) busyNs = nowNs
            if (dtSeconds <= 0f) return
            sumYaw += yaw * dtSeconds
            sumPitch += pitch * dtSeconds
            sumRoll += roll * dtSeconds
            sumDt += dtSeconds
            if (sumDt < WINDOW_S) return
            val dps = sqrt(sumYaw * sumYaw + sumPitch * sumPitch + sumRoll * sumRoll) / sumDt
            sumYaw = 0f; sumPitch = 0f; sumRoll = 0f; sumDt = 0f
            liveDps = if (liveDps.isNaN()) dps else liveDps + 0.2f * (dps - liveDps)
            liveAtNs = nowNs
            if (dps >= settings.stillBelowDps) busyNs = nowNs
        }
    }

    /**
     * An accelerometer reading in the phone's frame (right, up, toward the player), [g] = 1 g in its
     * units; [feeds] the displays on the pad, each measured against its Level up
     * ([com.oleroma.gyrophone.core.model.GyroDisplay.levelUp]; only the kinds with a Level).
     */
    fun up(x: Float, y: Float, z: Float, g: Float, nowNs: Long, feeds: Array<GyroDisplayFeed>) {
        val l = sqrt(x * x + y * y + z * z)
        if (l == 0f || g <= 0f) return
        synchronized(lock) {
            upNow[0] = x / l; upNow[1] = y / l; upNow[2] = z / l
            hasUp = true
            // Moved: the accelerometer reads more or less than gravity alone.
            if (abs(l / g - 1f) > G_SLACK) busyNs = nowNs
            val cosWithin = cos(Math.toRadians(settings.flatWithinDeg.toDouble())).toFloat()
            for (f in feeds) {
                val d = f.display
                if (!d.kind.pose) continue
                val level = d.levelUp
                val flat = level != null && level.size == 3 && dot(level) >= cosWithin
                if (!flat || f.id !in offLevelNs) offLevelNs[f.id] = nowNs
            }
        }
    }

    private var fingersDown = false
    private val keysDown = HashSet<Int>()
    private var sticksOff = false

    /** Fingers on the screen ([down]) or the last one lifted: in use while any is down. */
    fun fingers(down: Boolean, nowNs: Long) {
        synchronized(lock) {
            fingersDown = down
            busyNs = nowNs
        }
    }

    /** A key (controller button, volume key) pressed or let go: in use while any is held. */
    fun key(code: Int, down: Boolean, nowNs: Long) {
        synchronized(lock) {
            if (down) keysDown.add(code) else keysDown.remove(code)
            busyNs = nowNs
        }
    }

    /** A controller's sticks, triggers or d-pad moved: [off] while any is off centre (in use while so). */
    fun sticks(off: Boolean, nowNs: Long) {
        synchronized(lock) {
            if (!off && !sticksOff) return
            sticksOff = off
            busyNs = nowNs
        }
    }

    /** Whether display [id] should be faded now. */
    fun resting(id: String, nowNs: Long): Boolean {
        val s = settings
        if (!s.on) return false
        synchronized(lock) {
            // No gyro lately: it can't be told still.
            if (gyroAtNs == Long.MIN_VALUE || nowNs - gyroAtNs > GYRO_STALE_NS) return false
            if (fingersDown || keysDown.isNotEmpty() || sticksOff) return false
            val off = offLevelNs[id] ?: return false
            if (busyNs == Long.MIN_VALUE) return false
            return nowNs - maxOf(busyNs, off) >= s.quietMs * 1_000_000L
        }
    }

    /** The turning speed now (deg/s, smoothed), or null without gyro lately: shown beside "Still below". */
    fun liveDps(nowNs: Long): Float? = synchronized(lock) {
        if (liveDps.isNaN() || nowNs - liveAtNs > GYRO_STALE_NS) null else liveDps
    }

    /** Which way is up now (unit length, phone frame), or null before any reading: saved at Level. */
    fun upNow(): List<Float>? = synchronized(lock) { if (hasUp) upNow.toList() else null }

    /** The sensors stopped: everything starts again. */
    fun clear() {
        synchronized(lock) {
            busyNs = Long.MIN_VALUE
            gyroAtNs = Long.MIN_VALUE
            sumYaw = 0f; sumPitch = 0f; sumRoll = 0f; sumDt = 0f
            hasUp = false
            offLevelNs.clear()
            sticksOff = false
            keysDown.clear()
            fingersDown = false
            liveDps = Float.NaN
        }
    }

    private fun dot(level: List<Float>): Float {
        val l = sqrt(level[0] * level[0] + level[1] * level[1] + level[2] * level[2])
        if (l == 0f) return -1f
        return (upNow[0] * level[0] + upNow[1] * level[1] + upNow[2] * level[2]) / l
    }

    companion object {
        /** Turning is measured over this long at a time (about one OVERLAY's worth on the PC). */
        const val WINDOW_S = 0.016f
        private const val G_SLACK = 0.1f
        private const val GYRO_STALE_NS = 250_000_000L

        /** One step of a display's fade (1 = not faded) toward [resting]'s target over [dtMs] with settings [s]: down over the fade time, up at once. */
        fun step(fade: Float, resting: Boolean, dtMs: Float, s: RestFade): Float {
            val target = if (resting) s.fadeTo.coerceIn(0.05f, 1f) else 1f
            if (target >= fade) return target
            if (s.fadeMs <= 0) return target
            return maxOf(target, fade - (1f - target) * dtMs / s.fadeMs)
        }
    }
}
