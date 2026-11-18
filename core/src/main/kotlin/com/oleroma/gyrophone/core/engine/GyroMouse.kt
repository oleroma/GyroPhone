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
import com.oleroma.gyrophone.core.model.YawAxis
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Gyro-to-mouse conversion (Alpakka / JoyShockMapper style). Converts the sensor's angular
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

    /** Which screen axis is yaw ([YawAxis]): [motion] turns the screen's frame by it. */
    @Volatile var yawAxis: YawAxis = YawAxis.SCREEN_Z

    /** [YawAxis.CUSTOM]'s tilt back in degrees ([com.oleroma.gyrophone.core.model.GyroSettings.gripPitch]). */
    @Volatile var gripPitch: Float = 0f

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
    fun motion(gx: Float, gy: Float, gz: Float): Motion = withYawAxis(screenMotion(gx, gy, gz), yawAxis, gripPitch)

    /** As [motion], about the screen's own axes whatever [yawAxis] says. */
    fun screenMotion(gx: Float, gy: Float, gz: Float): Motion = motion(gx, gy, gz, resolvedOrientation())

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
     * Motion [m] about the screen's axes in the frame [axis] makes ([YawAxis]), a plain rotation
     * of the phone's frame (right, up, toward the player = pitch, -yaw, -roll):
     * - Screen Y, tilted back 90°: right stays, up becomes away from the player (-toward), toward
     *   becomes the screen's up. Stored roll (shown yaw) is then the turn about the screen's vertical.
     * - Screen X, turned 90° about the vertical: right becomes away from the player, toward becomes
     *   the screen's right. Stored roll (shown yaw) is then the turn about the screen's horizontal.
     */
    fun withYawAxis(m: Motion, axis: YawAxis, pitchDeg: Float = 0f): Motion = when (axis) {
        YawAxis.SCREEN_Z -> m
        YawAxis.SCREEN_Y -> Motion(yaw = -m.roll, pitch = m.pitch, roll = m.yaw)
        YawAxis.SCREEN_X -> Motion(yaw = m.yaw, pitch = m.roll, roll = -m.pitch)
        // The other way up: turned 180° more about the across axis (Z, Y) or the vertical one (X).
        YawAxis.SCREEN_Z_FLIPPED -> Motion(yaw = -m.yaw, pitch = m.pitch, roll = -m.roll)
        YawAxis.SCREEN_Y_FLIPPED -> Motion(yaw = m.roll, pitch = m.pitch, roll = -m.yaw)
        YawAxis.SCREEN_X_FLIPPED -> Motion(yaw = m.yaw, pitch = -m.roll, roll = m.pitch)
        YawAxis.CUSTOM -> tiltedBack(m, pitchDeg)
    }

    /**
     * [m] in the frame tilted back by [deg] about the screen's across axis: right stays, toward
     * becomes (0, sin, cos) and up (0, cos, -sin) in the screen's frame (90° = [YawAxis.SCREEN_Y]).
     */
    private fun tiltedBack(m: Motion, deg: Float): Motion {
        val a = Math.toRadians(deg.toDouble())
        val s = kotlin.math.sin(a).toFloat(); val c = kotlin.math.cos(a).toFloat()
        val wu = -m.yaw; val wt = -m.roll
        return Motion(yaw = -(c * wu - s * wt), pitch = m.pitch, roll = -(s * wu + c * wt))
    }

    /**
     * The world's [up] (right, up, toward the player, the screen's frame) in the base orientation
     * [axis] (and [pitchDeg]) makes, as [motion] turns a sample: for a display's level after Set orientation.
     */
    fun upInFrame(up: FloatArray, axis: YawAxis, pitchDeg: Float): FloatArray {
        val m = withYawAxis(Motion(yaw = -up[1], pitch = up[0], roll = -up[2]), axis, pitchDeg)
        return floatArrayOf(m.pitch, -m.yaw, -m.roll)
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
                // JoyShockMapper's player space: shown yaw (stored as roll) from the turns about the
                // screen's vertical axis and through the screen, by how gravity lies in their plane,
                // eased (×1.41) but never faster than both together. Pitch and roll stay the phone's.
                val world = -(wu * uy + wt * uz)
                val most = sqrt(wu * wu + wt * wt)
                Motion(m.yaw, m.pitch, sign(world) * min(abs(world) * PLAYER_RELAX, most))
            }

            GyroSpace.LOCAL -> m
        }
    }

    /**
     * Motion [m] with each axis measured in its own space ([GyroSettings.spaceOf]): yaw from [m] in
     * yaw's space, pitch in pitch's, roll in roll's ([inSpace]).
     */
    fun inSpaces(m: Motion, s: GyroSettings, up: FloatArray?): Motion {
        // Stored roll is the shown yaw, stored yaw the shown roll.
        val ys = s.spaceOf(MotionAxis.YAW); val ps = s.spaceOf(MotionAxis.PITCH); val rs = s.spaceOf(MotionAxis.ROLL)
        if (up == null || ys == ps && ps == rs) return inSpace(m, rs, up)
        val y = inSpace(m, ys, up)
        val p = if (ps == ys) y else inSpace(m, ps, up)
        val r = if (rs == ys) y else if (rs == ps) p else inSpace(m, rs, up)
        return Motion(y.yaw, p.pitch, r.roll)
    }

    private val math = GyroMath()

    /** The app's jitter tightening and smoothing (deg/s, 0 = off); null = [settings]' own (tests). */
    @Volatile var tightening: Float? = null
    @Volatile var smoothing: Float? = null

    // [route]'s last config and what it was made from, so a new one is made only on a change.
    private var cfgSettings: GyroSettings? = null
    private var cfgRoutes: List<Mapping>? = null
    private var cfg = GyroMath.Config()

    /** [s] and [routes] as [GyroMath]'s config, made again only when either changed. */
    fun config(s: GyroSettings, routes: List<Mapping>): GyroMath.Config {
        if (s !== cfgSettings || routes !== cfgRoutes) {
            cfg = GyroMath.Config.of(s, routes)
            cfgSettings = s; cfgRoutes = routes
        }
        return cfg
    }

    /** An axis glides, or one was on last sample (turning it off may start a glide): [route] wants every sample. */
    val coasting: Boolean get() = math.coasting

    /**
     * One sample (angular velocity [gx], [gy], [gz] in rad/s, Android sensor frame) sent where gyro
     * mappings [routes] ([InputSource.Gyro] sources) say ([GyroMath.step]), of the gyro axes [axes]
     * says move ([MappingEngine.gyroAxes]: a ratchet stops or starts one), measured in [space].
     * The result is reused by the next call.
     */
    fun route(
        gx: Float, gy: Float, gz: Float, dtSeconds: Float, routes: List<Mapping>, axes: Int = MappingEngine.ALL_GYRO,
        space: GyroSpace = GyroSpace.LOCAL, up: FloatArray? = null,
    ): GyroMath.Routed {
        val m = inSpace(motion(gx, gy, gz, resolvedOrientation()), space, up)
        return route(m, dtSeconds, routes, axes)
    }

    /** As [route], for a sample already turned and measured in its space ([motion], [inSpace]). */
    fun route(m: Motion, dtSeconds: Float, routes: List<Mapping>, axes: Int = MappingEngine.ALL_GYRO): GyroMath.Routed {
        val s = settings
        return math.step(m.yaw, m.pitch, m.roll, dtSeconds, config(s, routes), axes,
            tightening ?: s.tighteningThreshold, smoothing ?: s.smoothingThreshold)
    }

    fun reset() = math.reset()

    private enum class Resolved { LANDSCAPE_LEFT, LANDSCAPE_RIGHT, PORTRAIT, PORTRAIT_UPSIDE_DOWN }

    private fun resolvedOrientation(): Resolved = when (displayRotation) {
        0 -> Resolved.PORTRAIT
        180 -> Resolved.PORTRAIT_UPSIDE_DOWN
        270 -> Resolved.LANDSCAPE_RIGHT
        else -> Resolved.LANDSCAPE_LEFT
    }

    companion object {
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        /** Degrees of turn per wheel notch when gyro scrolls. */
        const val DEG_PER_NOTCH = GyroMath.DEG_PER_NOTCH

        /** Turning this fast (°/s) deflects a game pad axis fully. */
        const val STICK_FULL_DEG_PER_S = GyroMath.STICK_FULL_DEG_PER_S

        /** World space: the right axis this close to vertical has no flat direction to pitch about. */
        private const val FLAT_MIN = 0.1f

        /** Player space's yaw easing (JoyShockMapper's relax factor). */
        private const val PLAYER_RELAX = 1.41f

        const val GLIDE_MIN_DEG_PER_S = GyroMath.GLIDE_MIN_DEG_PER_S
        const val GLIDE_STOP_DEG_PER_S = GyroMath.GLIDE_STOP_DEG_PER_S
        const val MIN_GLIDE_MS = GyroMath.MIN_GLIDE_MS
        const val MAX_GLIDE_MS = GyroMath.MAX_GLIDE_MS
    }
}
