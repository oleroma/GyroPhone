package com.oleroma.gyrophone.core.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's orientation for the pad's gyro phone view, integrated from gyro rates. Its frame is
 * the player's view: x right, y up, z toward the player. [rotate]: since the last [reset], neutral
 * being the phone facing the player. [fuse]: against the world, its tilt held to gravity
 * ([setUp], from the accelerometer) and its heading eased back to facing the player, so it never
 * drifts. Written by the sensor threads (a few multiplies per sample), read by the UI ([matrix])
 * once per frame.
 */
class GyroPose {
    private var w = 1f
    private var x = 0f
    private var y = 0f
    private var z = 0f

    /** Bumped on every change, so a reader can skip frames where nothing moved. */
    @Volatile var version = 0L
        private set

    /** The last measured "up" in the phone's frame (unit length), or none yet. */
    private var upX = 0f
    private var upY = 0f
    private var upZ = 0f
    private var hasUp = false

    /** Seconds of [fuse] left that pull hard toward gravity: a fresh pose settles at once. */
    private var settling = SETTLE_S

    @Synchronized
    fun reset() {
        w = 1f; x = 0f; y = 0f; z = 0f
        hasUp = false
        settling = SETTLE_S
        version++
    }

    /**
     * The accelerometer's reading ([ax] right, [ay] up, [az] toward the player, in the phone's
     * frame, any unit): at rest it points up. Readings far from 1 g (the phone being shaken) are
     * skipped, so a jolt does not tip the view.
     */
    @Synchronized
    fun setUp(ax: Float, ay: Float, az: Float, g: Float) {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l == 0f || l < g * (1 - G_SLACK) || l > g * (1 + G_SLACK)) return
        upX = ax / l; upY = ay / l; upZ = az / l
        hasUp = true
    }

    /**
     * [rotate] against the world: the gyro turn, pulled toward the measured up ([setUp]) so the tilt
     * matches gravity, then the heading (which way the right edge points, round the vertical)
     * eased back to facing the player over about [easeSeconds].
     */
    @Synchronized
    fun fuse(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, easeSeconds: Float) {
        var ax = pitch * DEG_TO_RAD
        var ay = -yaw * DEG_TO_RAD
        var az = -roll * DEG_TO_RAD
        if (hasUp) {
            // Up as the pose has it, in the phone's frame: the matrix's middle row.
            val px = 2 * (x * y + w * z)
            val py = 1 - 2 * (x * x + z * z)
            val pz = 2 * (y * z - w * x)
            // Turning about measured × predicted brings the predicted up onto the measured one.
            val k = if (settling > 0f) KP_SETTLE else KP
            ax += k * (upY * pz - upZ * py)
            ay += k * (upZ * px - upX * pz)
            az += k * (upX * py - upY * px)
            settling -= dtSeconds
        }
        turn(ax * dtSeconds, ay * dtSeconds, az * dtSeconds)
        // Heading: the right edge's angle round the vertical; turn the world back by part of it.
        val rx = 1 - 2 * (y * y + z * z)
        val rz = 2 * (x * z - w * y)
        if (rx * rx + rz * rz > 0.04f && easeSeconds > 0f) {
            val a = -atan2(-rz, rx) * (dtSeconds / easeSeconds).coerceAtMost(1f)
            val bw = cos(a / 2)
            val by = sin(a / 2)
            // q = b * q, b about the world's vertical.
            val nw = bw * w - by * y
            val nx = bw * x + by * z
            val ny = bw * y + by * w
            val nz = bw * z - by * x
            w = nw; x = nx; y = ny; z = nz
            normalize()
        }
        version++
    }

    /**
     * Turns by [yaw] (right +), [pitch] (up +) and [roll] (clockwise as seen by the player +), in
     * deg/s as [GyroMouse.motion] gives them, over [dtSeconds]. They are rates about the phone's
     * own axes, so they apply on the phone's side of the orientation.
     */
    @Synchronized
    fun rotate(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float) {
        // Pitching up turns about +x (the top comes toward the player), yaw right about -y, clockwise roll about -z.
        val k = DEG_TO_RAD * dtSeconds
        if (turn(pitch * k, -yaw * k, -roll * k)) version++
    }

    /** Turns by the rotation vector ([ax], [ay], [az], radians) about the phone's own axes; false when it is zero. */
    private fun turn(ax: Float, ay: Float, az: Float): Boolean {
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle == 0f) return false
        val s = sin(angle / 2) / angle
        val bw = cos(angle / 2)
        val bx = ax * s
        val by = ay * s
        val bz = az * s
        // q = q * b
        val nw = w * bw - x * bx - y * by - z * bz
        val nx = w * bx + x * bw + y * bz - z * by
        val ny = w * by - x * bz + y * bw + z * bx
        val nz = w * bz + x * by - y * bx + z * bw
        w = nw; x = nx; y = ny; z = nz
        normalize()
        return true
    }

    /** Renormalized every step: float error never builds up into a stretched model. */
    private fun normalize() {
        val n = 1f / sqrt(w * w + x * x + y * y + z * z)
        w *= n; x *= n; y *= n; z *= n
    }

    /** Writes the rotation as a row-major 3×3 matrix (phone frame → view frame) into [out]. */
    @Synchronized
    fun matrix(out: FloatArray) {
        out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y - w * z); out[2] = 2 * (x * z + w * y)
        out[3] = 2 * (x * y + w * z); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z - w * x)
        out[6] = 2 * (x * z - w * y); out[7] = 2 * (y * z + w * x); out[8] = 1 - 2 * (x * x + y * y)
    }

    private companion object {
        const val DEG_TO_RAD = (Math.PI / 180).toFloat()

        /** Pull toward gravity (rad/s per unit of error): slow enough to ride out hand shake. */
        const val KP = 1.5f
        const val KP_SETTLE = 20f
        const val SETTLE_S = 0.3f

        /** How far from 1 g a reading may be and still count as gravity. */
        const val G_SLACK = 0.15f
    }
}
