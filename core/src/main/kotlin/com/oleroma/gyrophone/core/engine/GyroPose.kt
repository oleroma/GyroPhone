package com.oleroma.gyrophone.core.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's orientation since the last [reset], integrated from gyro rates, for the pad's gyro
 * phone view. Its frame is the player's view: x right, y up, z toward the player; neutral is the
 * phone facing the player. Written by the gyro thread ([rotate], a few multiplies per sample),
 * read by the UI ([matrix]) once per frame.
 */
class GyroPose {
    private var w = 1f
    private var x = 0f
    private var y = 0f
    private var z = 0f

    /** Bumped on every change, so a reader can skip frames where nothing moved. */
    @Volatile var version = 0L
        private set

    @Synchronized
    fun reset() {
        w = 1f; x = 0f; y = 0f; z = 0f
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
        val ax = pitch * k
        val ay = -yaw * k
        val az = -roll * k
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle == 0f) return
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
        // Renormalized every step: float error never builds up into a stretched model.
        val n = 1f / sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
        w = nw * n; x = nx * n; y = ny * n; z = nz * n
        version++
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
    }
}
