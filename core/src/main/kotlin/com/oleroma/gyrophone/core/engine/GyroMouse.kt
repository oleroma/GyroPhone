package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MotionAxis
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gyro-to-mouse conversion (Alpakka / JoyShockMapper style). Converts the sensor's angular
 * velocity into yaw / pitch / roll for the chosen [GyroOrientation], routes the chosen motion to
 * each mouse axis, applies soft tightening and tiered smoothing at low speeds, scales to mouse
 * counts and keeps the sub-count remainder so slow motion is not lost.
 */
class GyroMouse(var settings: GyroSettings = GyroSettings()) {

    /** Screen rotation in degrees (0/90/180/270), used when orientation is AUTO. */
    @Volatile var displayRotation: Int = 90

    private var remX = 0f
    private var remY = 0f
    private val bufX = FloatArray(SMOOTHING_WINDOW)
    private val bufY = FloatArray(SMOOTHING_WINDOW)
    private var bufIndex = 0

    data class Counts(val dx: Int, val dy: Int)

    /** Yaw (right +), pitch (up +), roll (clockwise as seen by the player +), in deg/s. */
    data class Motion(val yaw: Float, val pitch: Float, val roll: Float) {
        operator fun get(axis: MotionAxis): Float = when (axis) {
            MotionAxis.YAW -> yaw
            MotionAxis.PITCH -> pitch
            MotionAxis.ROLL -> roll
            MotionAxis.NONE -> 0f
        }
    }

    /**
     * Android sensor frame: x right, y up, z out of the screen (portrait), positive rotation is
     * counter-clockwise looking down the axis. Each orientation names which device axis points
     * up / right / toward the player.
     */
    fun motion(gx: Float, gy: Float, gz: Float): Motion = motion(gx, gy, gz, resolvedOrientation())

    private fun motion(gx: Float, gy: Float, gz: Float, resolved: Resolved): Motion {
        val x = gx * RAD_TO_DEG; val y = gy * RAD_TO_DEG; val z = gz * RAD_TO_DEG
        return when (resolved) {
            Resolved.LANDSCAPE_LEFT -> Motion(yaw = -x, pitch = -y, roll = -z)
            Resolved.LANDSCAPE_RIGHT -> Motion(yaw = x, pitch = y, roll = -z)
            Resolved.PORTRAIT -> Motion(yaw = -y, pitch = x, roll = -z)
            Resolved.PORTRAIT_UPSIDE_DOWN -> Motion(yaw = y, pitch = -x, roll = -z)
            Resolved.FLAT -> Motion(yaw = -z, pitch = x, roll = y)
        }
    }

    /**
     * @param gx, gy, gz angular velocity in rad/s (Android sensor frame)
     * @param dtSeconds time since the previous sample
     */
    fun update(gx: Float, gy: Float, gz: Float, dtSeconds: Float): Counts {
        val s = settings
        val resolved = resolvedOrientation()
        val m = motion(gx, gy, gz, resolved)
        // Portrait has its own axis mapping when set; read fields directly to avoid allocating.
        val p = if (resolved == Resolved.PORTRAIT || resolved == Resolved.PORTRAIT_UPSIDE_DOWN) s.portrait else null
        // Cursor X right = +, cursor Y: up = + here, flipped to HID (down = +) at the end.
        var vx = if (p != null) m[p.mouseX] * p.scaleX * (if (p.invertX) -1f else 1f)
        else m[s.mouseX] * s.scaleX * (if (s.invertX) -1f else 1f)
        var vy = if (p != null) m[p.mouseY] * p.scaleY * (if (p.invertY) -1f else 1f)
        else m[s.mouseY] * s.scaleY * (if (s.invertY) -1f else 1f)

        val speed = sqrt(vx * vx + vy * vy)
        if (s.tighteningThreshold > 0f && speed < s.tighteningThreshold) {
            val k = speed / s.tighteningThreshold
            vx *= k; vy *= k
        }

        bufX[bufIndex] = vx; bufY[bufIndex] = vy
        bufIndex = (bufIndex + 1) % SMOOTHING_WINDOW
        if (s.smoothingThreshold > 0f) {
            // Fully smoothed below threshold/2, fully direct above threshold, blended between.
            val direct = ((speed - s.smoothingThreshold / 2f) / (s.smoothingThreshold / 2f)).coerceIn(0f, 1f)
            vx = vx * direct + bufX.average().toFloat() * (1f - direct)
            vy = vy * direct + bufY.average().toFloat() * (1f - direct)
        }

        val fx = vx * dtSeconds * s.sensitivity + remX
        val fy = -vy * dtSeconds * s.sensitivity + remY
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

    private enum class Resolved { LANDSCAPE_LEFT, LANDSCAPE_RIGHT, PORTRAIT, PORTRAIT_UPSIDE_DOWN, FLAT }

    private fun resolvedOrientation(): Resolved = when (settings.orientation) {
        GyroOrientation.LANDSCAPE_LEFT -> Resolved.LANDSCAPE_LEFT
        GyroOrientation.LANDSCAPE_RIGHT -> Resolved.LANDSCAPE_RIGHT
        GyroOrientation.PORTRAIT -> Resolved.PORTRAIT
        GyroOrientation.FLAT -> Resolved.FLAT
        GyroOrientation.AUTO -> when (displayRotation) {
            0 -> Resolved.PORTRAIT
            180 -> Resolved.PORTRAIT_UPSIDE_DOWN
            270 -> Resolved.LANDSCAPE_RIGHT
            else -> Resolved.LANDSCAPE_LEFT
        }
    }

    companion object {
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
        private const val SMOOTHING_WINDOW = 8
    }
}
