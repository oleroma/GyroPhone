package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.SensorAxis
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gyro-to-mouse conversion (Alpakka / JoyShockMapper style): picks yaw and pitch from the sensor
 * axes, applies soft tightening and tiered smoothing at low speeds, scales to mouse counts and
 * keeps the sub-count remainder so slow motion is not lost.
 */
class GyroMouse(var settings: GyroSettings = GyroSettings()) {

    private var remX = 0f
    private var remY = 0f
    private val bufX = FloatArray(SMOOTHING_WINDOW)
    private val bufY = FloatArray(SMOOTHING_WINDOW)
    private var bufIndex = 0

    data class Counts(val dx: Int, val dy: Int)

    /**
     * @param gx, gy, gz angular velocity in rad/s (Android sensor frame)
     * @param dtSeconds time since the previous sample
     */
    fun update(gx: Float, gy: Float, gz: Float, dtSeconds: Float): Counts {
        val s = settings
        var yaw = pick(s.yawAxis, gx, gy, gz) * RAD_TO_DEG * (if (s.yawInvert) -1f else 1f)
        var pitch = pick(s.pitchAxis, gx, gy, gz) * RAD_TO_DEG * (if (s.pitchInvert) -1f else 1f)

        val speed = sqrt(yaw * yaw + pitch * pitch)
        if (s.tighteningThreshold > 0f && speed < s.tighteningThreshold) {
            val k = speed / s.tighteningThreshold
            yaw *= k; pitch *= k
        }

        bufX[bufIndex] = yaw; bufY[bufIndex] = pitch
        bufIndex = (bufIndex + 1) % SMOOTHING_WINDOW
        if (s.smoothingThreshold > 0f) {
            // Fully smoothed below threshold/2, fully direct above threshold, blended between.
            val direct = ((speed - s.smoothingThreshold / 2f) / (s.smoothingThreshold / 2f)).coerceIn(0f, 1f)
            yaw = yaw * direct + bufX.average().toFloat() * (1f - direct)
            pitch = pitch * direct + bufY.average().toFloat() * (1f - direct)
        }

        val fx = yaw * dtSeconds * s.sensitivity + remX
        val fy = pitch * dtSeconds * s.sensitivity + remY
        val dx = fx.roundToInt()
        val dy = fy.roundToInt()
        remX = fx - dx
        remY = fy - dy
        return Counts(dx, dy)
    }

    fun reset() {
        remX = 0f; remY = 0f
        bufX.fill(0f); bufY.fill(0f)
    }

    private fun pick(axis: SensorAxis, x: Float, y: Float, z: Float) = when (axis) {
        SensorAxis.X -> x
        SensorAxis.Y -> y
        SensorAxis.Z -> z
    }

    companion object {
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
        private const val SMOOTHING_WINDOW = 8

        /**
         * Default axes for a phone held in landscape like a game pad, screen facing the player.
         * Display rotation 90 = top of the phone on the left, 270 = top on the right.
         */
        fun defaultsForRotation(rotationDegrees: Int, base: GyroSettings = GyroSettings()): GyroSettings =
            when (rotationDegrees) {
                270 -> base.copy(yawAxis = SensorAxis.X, yawInvert = false, pitchAxis = SensorAxis.Y, pitchInvert = true)
                0, 180 -> base.copy(yawAxis = SensorAxis.Y, yawInvert = rotationDegrees == 0, pitchAxis = SensorAxis.X,
                    pitchInvert = rotationDegrees != 0)
                else -> base.copy(yawAxis = SensorAxis.X, yawInvert = true, pitchAxis = SensorAxis.Y, pitchInvert = false)
            }
    }
}
