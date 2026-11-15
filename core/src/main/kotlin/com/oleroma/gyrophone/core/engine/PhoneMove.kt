package com.oleroma.gyrophone.core.engine

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The phone's short moves (up, down, left, right, toward and away), for the 3D phone display's
 * "Show movement" ([com.oleroma.gyrophone.core.model.GyroDisplay.move]).
 *
 * A phone can't know where it is: it only feels pushes (the accelerometer). Pushes added up twice
 * give a position, but their small errors grow into meters within seconds. So this shows nudges
 * only: the push less gravity is added up into a speed and a position; once the phone is nearly
 * still the speed stops at once (so a move ends where it ended, not swinging back) and the
 * position glides back to the middle. A held ratchet ([held]) stops it and takes it back to the middle, at once or over a set time.
 *
 * Gravity in the phone's frame is followed by the gyro ([turn], the gyro thread) and pulled
 * slowly toward the accelerometer's reading ([push], its own thread), so turning the phone isn't
 * taken for moving it. A few multiplies per sample, off the input path.
 *
 * Frames: the phone's own (x right, y up, z toward the player), as [GyroPose]; [shift] turns the
 * push into the display's view with the pose shown before adding it up, so the offset is where
 * the phone moved on screen.
 */
class PhoneMove {
    // Gravity in the phone's frame (m/s²), and whether there is one yet.
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var hasG = false

    // Speed and position in the view (m/s, m).
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var px = 0f
    private var py = 0f
    private var pz = 0f

    /** The push less gravity (m/s²), smoothed: under the still threshold, it glides back. */
    private var shake = 0f

    /** Seconds the phone has been nearly still. */
    private var still = 0f

    private var lastNs = 0L

    /** How fast the phone turned at the last gyro sample (deg/s): gravity is trusted from the accelerometer only while it barely turns. */
    private var spin = 0f

    /** A ratchet is held: it stays in the middle, nothing adds up. */
    @Volatile var held = false

    /** Gyro rates in deg/s ([GyroMouse.motion], as [GyroPose.fuse] takes them) over [dt] seconds: turns gravity with the phone. */
    @Synchronized
    fun turn(yaw: Float, pitch: Float, roll: Float, dt: Float) {
        if (dt <= 0f) return
        spin = sqrt(yaw * yaw + pitch * pitch + roll * roll)
        if (!hasG) return
        val ax = pitch * DEG_TO_RAD * dt
        val ay = -yaw * DEG_TO_RAD * dt
        val az = -roll * DEG_TO_RAD * dt
        // A vector fixed in the world, seen from a phone turning by a: g -= a × g.
        val nx = gx - (ay * gz - az * gy)
        val ny = gy - (az * gx - ax * gz)
        val nz = gz - (ax * gy - ay * gx)
        gx = nx; gy = ny; gz = nz
    }

    /**
     * An accelerometer reading ([ax], [ay], [az] in the phone's frame, gravity [g] in its units) at
     * [nowNs]; [view] the pose shown now (row-major 3×3, phone into view), null for none.
     * Pushes under [ignoreBelow] m/s² count as nothing (and bigger ones lose that much), so hand
     * tremor and sensor noise don't move it. Once it shakes less than [stillBelow] m/s² for
     * [stillFor] seconds it stops, then goes 95% of the way back in [returnSeconds].
     */
    @Synchronized
    fun push(
        ax: Float, ay: Float, az: Float, g: Float, nowNs: Long, view: FloatArray?,
        stillBelow: Float, returnSeconds: Float, stillFor: Float = STILL_FOR_S, ignoreBelow: Float = NOISE,
        ratchetSeconds: Float = 0f,
    ) {
        if (g <= 0f) return
        val k = G / g
        val x = ax * k
        val y = ay * k
        val z = az * k
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceIn(0f, MAX_DT)
        lastNs = nowNs
        if (!hasG) {
            gx = x; gy = y; gz = z
            hasG = true
            return
        }
        if (dt == 0f) return
        // Pulled toward the reading: firmly while it is about 1 g and the phone barely turns (nothing but gravity), barely else.
        val l = sqrt(x * x + y * y + z * z)
        val calm = kotlin.math.abs(l - G) < CALM_G && spin < CALM_DPS
        val pull = (dt / (if (calm) G_FOLLOW_S else G_FOLLOW_MOVING_S)).coerceAtMost(1f)
        gx += (x - gx) * pull; gy += (y - gy) * pull; gz += (z - gz) * pull
        var lx = x - gx
        var ly = y - gy
        var lz = z - gz
        val a = sqrt(lx * lx + ly * ly + lz * lz)
        shake += (a - shake) * (dt / SHAKE_S).coerceAtMost(1f)
        // Sensor noise, tremor and leftover gravity, not a move: taken off every push (a soft dead zone, so a push
        // just over it doesn't jump in, and its speeding up and slowing down lose the same).
        val keep = if (a <= ignoreBelow || a == 0f) 0f else (a - ignoreBelow) / a
        lx *= keep; ly *= keep; lz *= keep
        if (held) {
            // A held ratchet: nothing adds up; back to the middle in [ratchetSeconds] (at once at 0).
            vx = 0f; vy = 0f; vz = 0f
            still = 0f
            if (ratchetSeconds <= 0f) {
                px = 0f; py = 0f; pz = 0f
            } else {
                val back = exp(-3f * dt / ratchetSeconds)
                px *= back; py *= back; pz *= back
            }
            return
        }
        if (view != null) {
            val rx = view[0] * lx + view[1] * ly + view[2] * lz
            val ry = view[3] * lx + view[4] * ly + view[5] * lz
            val rz = view[6] * lx + view[7] * ly + view[8] * lz
            lx = rx; ly = ry; lz = rz
        }
        val fade = exp(-dt / SPEED_FADE_S)
        vx = (vx + lx * dt) * fade
        vy = (vy + ly * dt) * fade
        vz = (vz + lz * dt) * fade
        px += vx * dt; py += vy * dt; pz += vz * dt
        still = if (shake < stillBelow) still + dt else 0f
        if (still >= stillFor) {
            // Still: it has stopped (the speed goes at once, so a move doesn't swing back past where it ended), then glides back.
            val stop = exp(-dt / STOP_S)
            vx *= stop; vy *= stop; vz *= stop
            val back = exp(-3f * dt / returnSeconds.coerceAtLeast(0.05f))
            px *= back; py *= back; pz *= back
        }
        // Far beyond what a hand moves without the phone noticing: hold it there.
        val d = sqrt(px * px + py * py + pz * pz)
        if (d > MAX_M) {
            val s = MAX_M / d
            px *= s; py *= s; pz *= s
        }
    }

    /** Back to the middle at once (a ratchet press), at rest. */
    @Synchronized
    fun center() {
        vx = 0f; vy = 0f; vz = 0f
        px = 0f; py = 0f; pz = 0f
        still = 0f
    }

    /** Starts again: no gravity yet (the accelerometer stopped, the display was switched). */
    @Synchronized
    fun reset() {
        center()
        hasG = false
        shake = 0f
        spin = 0f
        lastNs = 0L
    }

    /** Where the phone is now in the view (meters; x right, y up, z toward the player) into [out]. */
    @Synchronized
    fun shift(out: FloatArray) {
        out[0] = px; out[1] = py; out[2] = pz
    }

    companion object {
        /** Standard gravity in m/s². */
        const val G = 9.80665f
        private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
        private const val MAX_DT = 0.05f
        /** A reading within this of 1 g (m/s²) counts as gravity alone. */
        private const val CALM_G = 0.4f
        private const val G_FOLLOW_S = 0.5f
        private const val G_FOLLOW_MOVING_S = 20f
        /** Default: pushes below this (m/s²) are taken as noise. */
        const val NOISE = 0.05f
        /** How long the speed takes to fade to a third on its own while moving, so drift can't build up for long. */
        private const val SPEED_FADE_S = 2f
        /** Once still, the speed goes this fast. */
        private const val STOP_S = 0.03f
        /** Turning slower than this (deg/s) the accelerometer may set gravity. */
        private const val CALM_DPS = 20f
        private const val SHAKE_S = 0.08f
        /** Default: still that long before it glides back, so a pause mid-move doesn't pull it. */
        const val STILL_FOR_S = 0.15f
        private const val MAX_M = 0.5f
        /** Default still threshold, m/s². */
        const val STILL_BELOW = 0.3f
    }
}
