package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gyro-to-mouse conversion (Alpakka / JoyShockMapper style). Converts the sensor's angular
 * velocity into yaw / pitch / roll for the way the screen is turned ([displayRotation]), routes each where the gyro
 * mappings say, optionally applies soft tightening and tiered smoothing at low speeds (both off
 * by default: raw gyro), scales to mouse counts and keeps the sub-count remainder so slow motion
 * is not lost.
 */
class GyroMouse(settings: GyroSettings = GyroSettings()) {

    /** Replaced whole from another thread (the UI); [route] reads it once per sample. */
    @Volatile var settings: GyroSettings = settings

    /** Screen rotation in degrees (0/90/180/270): how the phone is held. */
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
        }
    }

    /** Where [route] sent one sample: mouse counts, wheel units and game pad axes (by [GamepadAxis] ordinal). */
    class Routed {
        var dx = 0
        var dy = 0
        var wheel = 0
        var hwheel = 0
        val pad = FloatArray(GamepadAxis.entries.size)
    }

    private val routed = Routed()
    private var remWheel = 0f
    private var remHWheel = 0f

    /**
     * One sample (angular velocity [gx], [gy], [gz] in rad/s, Android sensor frame) sent where gyro
     * mappings [routes] ([InputSource.Gyro] sources) say: mouse motion at the axis's sensitivity
     * ([GyroSettings.sensitivityOf], counts per degree) through the filters, of the gyro axes
     * [axes] says move ([MappingEngine.gyroAxes]: a ratchet stops or starts one), scrolling at [DEG_PER_NOTCH] and game pad
     * axes full at [STICK_FULL_DEG_PER_S] at the default sensitivity, faster or slower with the
     * axis's (pitching up pushes a stick up). A target's scale multiplies.
     * The result is reused by the next call.
     */
    fun route(gx: Float, gy: Float, gz: Float, dtSeconds: Float, routes: List<Mapping>, axes: Int = MappingEngine.ALL_GYRO): Routed {
        val s = settings
        val m = motion(gx, gy, gz, resolvedOrientation())
        var vx = 0f; var vy = 0f; var w = 0f; var hw = 0f
        val pad = routed.pad
        pad.fill(0f)
        for (i in routes.indices) {
            val r = routes[i]
            val src = r.source as? InputSource.Gyro ?: continue
            if (axes and MappingEngine.gyroBit(src.axis) == 0) continue
            val k = s.sensitivityOf(src.axis)
            val v = m[src.axis]
            when (val t = r.target) {
                is OutputTarget.MouseAxis -> when (t.motion) {
                    MouseMotion.MOVE_X -> vx += v * k * t.scale
                    MouseMotion.MOVE_Y -> vy += v * k * t.scale
                    MouseMotion.WHEEL -> w += v * k / GyroSettings.DEFAULT_SENSITIVITY * t.scale
                    MouseMotion.HWHEEL -> hw += v * k / GyroSettings.DEFAULT_SENSITIVITY * t.scale
                }

                is OutputTarget.PadAxis -> {
                    // Stick Y is + down: pitching up pushes it up.
                    val up = if (t.axis == GamepadAxis.LY || t.axis == GamepadAxis.RY) -1f else 1f
                    pad[t.axis.ordinal] += up * v * k / GyroSettings.DEFAULT_SENSITIVITY * t.scale / STICK_FULL_DEG_PER_S
                }

                else -> Unit
            }
        }
        for (a in pad.indices) {
            pad[a] = if (GamepadAxis.entries[a].isTrigger) kotlin.math.abs(pad[a]).coerceAtMost(1f) else pad[a].coerceIn(-1f, 1f)
        }
        val c = counts(vx, vy, dtSeconds)
        routed.dx = c.dx; routed.dy = c.dy
        val fw = w * dtSeconds * MouseState.WHEEL_UNIT / DEG_PER_NOTCH + remWheel
        val fh = hw * dtSeconds * MouseState.WHEEL_UNIT / DEG_PER_NOTCH + remHWheel
        routed.wheel = fw.toInt(); remWheel = fw - routed.wheel
        routed.hwheel = fh.toInt(); remHWheel = fh - routed.hwheel
        return routed
    }

    /**
     * Mouse counts of cursor velocity [vx0] (right +) and [vy0] (up +) in counts/s, sensitivity
     * applied, over [dtSeconds]: filtered, the remainder kept. The filters' thresholds are in °/s
     * at the default sensitivity.
     */
    private fun counts(vx0: Float, vy0: Float, dtSeconds: Float): Counts {
        val s = settings
        var vx = vx0 / GyroSettings.DEFAULT_SENSITIVITY
        var vy = vy0 / GyroSettings.DEFAULT_SENSITIVITY
        val speed = sqrt(vx * vx + vy * vy)
        if (s.tighteningThreshold > 0f && speed < s.tighteningThreshold) {
            val k = speed / s.tighteningThreshold
            vx *= k; vy *= k
        }

        if (s.smoothingThreshold > 0f) {
            // Fully smoothed below threshold/2, fully direct above threshold, blended between. Only
            // the smoothed share enters the window, so it is sent once, spread over the window, and
            // fast motion is never replayed after the phone stops. Off (0): raw, the window unused.
            val direct = ((speed - s.smoothingThreshold / 2f) / (s.smoothingThreshold / 2f)).coerceIn(0f, 1f)
            bufX[bufIndex] = vx * (1f - direct); bufY[bufIndex] = vy * (1f - direct)
            bufIndex = (bufIndex + 1) % SMOOTHING_WINDOW
            vx = vx * direct + bufX.average().toFloat()
            vy = vy * direct + bufY.average().toFloat()
        }

        val fx = vx * dtSeconds * GyroSettings.DEFAULT_SENSITIVITY + remX
        val fy = -vy * dtSeconds * GyroSettings.DEFAULT_SENSITIVITY + remY
        val dx = fx.roundToInt()
        val dy = fy.roundToInt()
        remX = fx - dx
        remY = fy - dy
        return Counts(dx, dy)
    }

    fun reset() {
        remX = 0f; remY = 0f; remWheel = 0f; remHWheel = 0f
        bufX.fill(0f); bufY.fill(0f)
    }

    private enum class Resolved { LANDSCAPE_LEFT, LANDSCAPE_RIGHT, PORTRAIT, PORTRAIT_UPSIDE_DOWN }

    private fun resolvedOrientation(): Resolved = when (displayRotation) {
        0 -> Resolved.PORTRAIT
        180 -> Resolved.PORTRAIT_UPSIDE_DOWN
        270 -> Resolved.LANDSCAPE_RIGHT
        else -> Resolved.LANDSCAPE_LEFT
    }

    companion object {
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
        private const val SMOOTHING_WINDOW = 8

        /** Degrees of turn per wheel notch when gyro scrolls. */
        const val DEG_PER_NOTCH = 15f

        /** Turning this fast (°/s) deflects a game pad axis fully. */
        const val STICK_FULL_DEG_PER_S = 90f
    }
}
