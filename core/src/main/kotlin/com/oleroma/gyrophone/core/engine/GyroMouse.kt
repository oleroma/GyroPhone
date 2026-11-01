package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.GyroSpace
import com.oleroma.gyrophone.core.model.InertiaCurve
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Gyro-to-mouse conversion. Converts the sensor's angular
 * velocity into yaw / pitch / roll for the way the screen is turned ([displayRotation]), routes each where the gyro
 * mappings say, optionally applies soft tightening and tiered smoothing at low speeds (both off
 * by default: raw gyro), scales to mouse counts and keeps the sub-count remainder so slow motion
 * is not lost. An axis a ratchet turns off glides on with inertia ([GyroSettings.inertiaOf]).
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

    /**
     * Yaw (right +), pitch (up +), roll (clockwise as seen by the player +), in deg/s, by their
     * stored names ([MotionAxis]): shown, [roll] is yaw and [yaw] is roll.
     */
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

    /**
     * Motion [m] (the phone's own axes) measured in [space] ([GyroSpace]), with [up] the world's up
     * in the phone's frame (right, up, toward the player, unit length); local without it.
     */
    fun inSpace(m: Motion, space: GyroSpace, up: FloatArray?): Motion {
        if (space == GyroSpace.LOCAL || up == null) return m
        val ux = up[0]; val uy = up[1]; val uz = up[2]
        // The turn as a vector in the phone's frame (right, up, toward the player).
        val wr = m.pitch; val wu = -m.yaw; val wt = -m.roll
        return when (space) {
            GyroSpace.WORLD -> {
                // Shown yaw (stored as roll): the turn around gravity.
                val turn = -(wr * ux + wu * uy + wt * uz)
                // The right axis laid flat; straight up or down (phone on its side), the phone's own pitch and roll.
                var rx = 1f - ux * ux; var ry = -ux * uy; var rz = -ux * uz
                val n = sqrt(rx * rx + ry * ry + rz * rz)
                if (n < FLAT_MIN) return Motion(m.yaw, m.pitch, turn)
                rx /= n; ry /= n; rz /= n
                // The flat axis along the screen: right × up. Turning about it raises the right edge
                // (shown roll, stored as yaw); lying flat that is the phone's own.
                val tx = ry * uz - rz * uy; val ty = rz * ux - rx * uz; val tz = rx * uy - ry * ux
                Motion(wr * tx + wu * ty + wt * tz, wr * rx + wu * ry + wt * rz, turn)
            }

            GyroSpace.PLAYER -> {
                // Player space: shown yaw (stored as roll) from the turns about the
                // screen's vertical axis and through the screen, by how gravity lies in their plane,
                // eased (×1.41) but never faster than both together. Pitch and roll stay the phone's.
                val world = -(wu * uy + wt * uz)
                val most = sqrt(wu * wu + wt * wt)
                Motion(m.yaw, m.pitch, sign(world) * min(abs(world) * PLAYER_RELAX, most))
            }

            GyroSpace.LOCAL -> m
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

    // Inertia, per axis (yaw, pitch, roll): its recent speed (deg/s EMA), its glide speed (deg/s,
    // 0 = none), glide time (ms) and linear slowing (deg/s per ms, 0 = smooth); the axes on last sample.
    private val speed = FloatArray(3)
    private val glideV = FloatArray(3)
    private val glideTau = FloatArray(3)
    private val glideDec = FloatArray(3)
    private val glideOut = FloatArray(3)
    private var lastAxes = 0

    /** An axis glides, or one was on last sample (turning it off may start a glide): [route] wants every sample. */
    val coasting: Boolean get() = lastAxes != 0 || glideV[0] != 0f || glideV[1] != 0f || glideV[2] != 0f

    /**
     * One sample (angular velocity [gx], [gy], [gz] in rad/s, Android sensor frame) sent where gyro
     * mappings [routes] ([InputSource.Gyro] sources) say: mouse motion at the axis's sensitivity
     * ([GyroSettings.sensitivityOf], counts per degree) through the filters, of the gyro axes
     * [axes] says move ([MappingEngine.gyroAxes]: a ratchet stops or starts one), scrolling at [DEG_PER_NOTCH] and game pad
     * axes full at [STICK_FULL_DEG_PER_S] at the default sensitivity, faster or slower with the
     * axis's (pitching up pushes a stick up). A target's scale multiplies.
     * The result is reused by the next call.
     */
    fun route(
        gx: Float, gy: Float, gz: Float, dtSeconds: Float, routes: List<Mapping>, axes: Int = MappingEngine.ALL_GYRO,
        space: GyroSpace = GyroSpace.LOCAL, up: FloatArray? = null,
    ): Routed {
        val s = settings
        val m = inSpace(motion(gx, gy, gz, resolvedOrientation()), space, up)
        coast(s, m, axes, dtSeconds)
        var vx = 0f; var vy = 0f; var w = 0f; var hw = 0f
        val pad = routed.pad
        pad.fill(0f)
        for (i in routes.indices) {
            val r = routes[i]
            val src = r.source as? InputSource.Gyro ?: continue
            val on = axes and MappingEngine.gyroBit(src.axis) != 0
            val g = if (on) 0f else glideOf(src.axis)
            // Off, only a glide moves it, and only the mouse and wheel.
            if (!on && (g == 0f || r.target !is OutputTarget.MouseAxis)) continue
            val k = s.sensitivityOf(src.axis)
            val v = if (on) m[src.axis] else g
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
        speed.fill(0f); glideV.fill(0f); glideOut.fill(0f); lastAxes = 0
    }

    /** Axis [a]'s glide speed over the last sample (deg/s), 0 when it does not glide. */
    private fun glideOf(a: MotionAxis): Float = if (a == MotionAxis.NONE) 0f else glideOut[a.ordinal]

    /**
     * Inertia for one sample of motion [m] with axes [axes] on, over [dtSeconds]: an axis on
     * follows its speed and never glides; one just turned off with inertia glides on from its
     * recent speed; a gliding one slows down by its glide time and curve ([InertiaCurve]).
     */
    private fun coast(s: GyroSettings, m: Motion, axes: Int, dtSeconds: Float) {
        val dtMs = dtSeconds * 1000f
        for (i in 0 until 3) {
            val a = AXES[i]
            val bit = MappingEngine.gyroBit(a)
            glideOut[i] = 0f
            if (axes and bit != 0) {
                // A short EMA, so one noisy sample does not set the glide.
                val k = if (dtMs <= 0f) 0f else 1f - exp(-dtMs / SPEED_TAU_MS)
                speed[i] += k * (m[a] - speed[i])
                glideV[i] = 0f
                continue
            }
            if (lastAxes and bit != 0) {
                // Just turned off: glides on from its recent speed, this sample at that speed.
                glideV[i] = 0f
                if (s.inertiaOf(a) && abs(speed[i]) >= GLIDE_MIN_DEG_PER_S) {
                    val tau = s.glideMsOf(a).coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
                    glideV[i] = speed[i]; glideTau[i] = tau
                    // Linear: stops after 2·T, so it turns as far as the smooth one.
                    glideDec[i] = if (s.curveOf(a) == InertiaCurve.LINEAR) abs(speed[i]) / (2f * tau) else 0f
                    glideOut[i] = speed[i]
                }
                speed[i] = 0f
                continue
            }
            val v = glideV[i]
            if (v == 0f || dtMs <= 0f) continue
            // The sample moves by the glide's mean speed over it.
            val next: Float
            if (glideDec[i] == 0f) {
                val decay = exp(-dtMs / glideTau[i])
                next = v * decay
                glideOut[i] = v * glideTau[i] * (1f - decay) / dtMs
            } else {
                val t = min(dtMs, abs(v) / glideDec[i])
                next = (abs(v) - glideDec[i] * t) * sign(v)
                glideOut[i] = (v + next) / 2f * t / dtMs
            }
            glideV[i] = if (abs(next) < GLIDE_STOP_DEG_PER_S) 0f else next
        }
        lastAxes = axes
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

        private val AXES = arrayOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)

        /** World space: the right axis this close to vertical has no flat direction to pitch about. */
        private const val FLAT_MIN = 0.1f

        /** Player space's yaw easing (relax factor). */
        private const val PLAYER_RELAX = 1.41f

        /** The recent speed a glide starts from: an EMA with this time constant (ms). */
        private const val SPEED_TAU_MS = 15f

        /** An axis turned off slower than this (deg/s) does not glide; a glide stops below [GLIDE_STOP_DEG_PER_S]. */
        const val GLIDE_MIN_DEG_PER_S = 5f
        const val GLIDE_STOP_DEG_PER_S = 0.5f
        const val MIN_GLIDE_MS = 100f
        const val MAX_GLIDE_MS = 1000f
    }
}
