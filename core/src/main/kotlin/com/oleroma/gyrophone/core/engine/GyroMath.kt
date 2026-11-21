package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroCurves
import com.oleroma.gyrophone.core.model.GyroSettings
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
 * The gyro's math after the motion is known: yaw, pitch and roll (deg/s, by [MotionAxis] ordinal,
 * already turned for the screen and measured in the gyro space) to mouse counts, wheel units and
 * game pad axes, with glide (inertia) when a ratchet turns an axis off, jitter tightening and
 * smoothing, and the sub-count remainders kept. The filters' thresholds are the phone's own turning
 * speed (deg/s, before sensitivity and acceleration), and smoothing spreads motion over a time
 * ([SMOOTHING_WINDOW_S]), not a count of samples, so both act the same at any sensitivity and sample rate.
 *
 * The phone runs it inside [GyroMouse]; in "Use PC controller for gyro ratchet" mode the PC runs
 * the same math (companion GyroMath.cs) on the samples the phone sends. Both must give the same
 * result for the same input: every step is Float math in the same order, and the shared test
 * vectors (GyroMathVectors) check it. Change one side, change the other and the vectors.
 */
class GyroMath {

    /**
     * What one gyro configuration does, resolved (inheritance and held buttons already applied):
     * per axis (by [MotionAxis] ordinal: yaw, pitch, roll) its sensitivity (counts per degree)
     * and glide, and where each axis goes ([routes]: a gyro mapping each, in mapping order).
     */
    class Config(
        val sensitivity: FloatArray = FloatArray(3) { GyroSettings.DEFAULT_SENSITIVITY },
        val inertia: BooleanArray = BooleanArray(3),
        val glideMs: FloatArray = FloatArray(3) { GyroSettings.DEFAULT_GLIDE_MS },
        val linear: BooleanArray = BooleanArray(3),
        val routes: List<Route> = emptyList(),
        /**
         * Per axis its acceleration ([GyroCurve.flat]: speeds and gains interleaved, on the wire's
         * grid; empty = none) and whether the speed is the axis's own ([axisSpeed]) or the whole phone's.
         */
        val curves: Array<FloatArray> = Array(3) { FloatArray(0) },
        val axisSpeed: BooleanArray = BooleanArray(3),
        /** Per axis: its curve is smooth ([GyroCurve.smooth]) rather than straight lines. */
        val smooth: BooleanArray = BooleanArray(3),
        /**
         * Per axis the gyro space it is measured in ([GyroSpace] ordinal). [step] does not use it: the
         * sample comes already measured ([GyroSpaces]); the PC in PC gyro mode measures with it.
         */
        val space: IntArray = IntArray(3),
    ) {
        override fun equals(other: Any?): Boolean = other is Config &&
            sensitivity.contentEquals(other.sensitivity) && inertia.contentEquals(other.inertia) &&
            glideMs.contentEquals(other.glideMs) && linear.contentEquals(other.linear) && routes == other.routes &&
            curves.contentDeepEquals(other.curves) && axisSpeed.contentEquals(other.axisSpeed) && smooth.contentEquals(other.smooth) &&
            space.contentEquals(other.space)

        override fun hashCode(): Int = sensitivity.contentHashCode() * 31 + routes.hashCode()

        companion object {
            /** [s] and gyro mappings [mappings] ([InputSource.Gyro] sources) as a config; mappings that move nothing are left out. */
            fun of(s: GyroSettings, mappings: List<Mapping>): Config {
                val routes = ArrayList<Route>()
                for (m in mappings) {
                    val src = m.source as? InputSource.Gyro ?: continue
                    if (src.axis == MotionAxis.NONE) continue
                    val target = when (val t = m.target) {
                        is OutputTarget.MouseAxis -> when (t.motion) {
                            MouseMotion.MOVE_X -> Route.MOUSE_X
                            MouseMotion.MOVE_Y -> Route.MOUSE_Y
                            MouseMotion.WHEEL -> Route.WHEEL
                            MouseMotion.HWHEEL -> Route.HWHEEL
                        }
                        is OutputTarget.PadAxis -> Route.PAD + t.axis.ordinal
                        else -> continue
                    }
                    val scale = when (val t = m.target) {
                        is OutputTarget.MouseAxis -> t.scale
                        is OutputTarget.PadAxis -> t.scale
                        else -> 1f
                    }
                    routes.add(Route(src.axis.ordinal, target, scale))
                }
                return Config(
                    FloatArray(3) { s.sensitivityOf(AXES[it]) },
                    BooleanArray(3) { s.inertiaOf(AXES[it]) },
                    FloatArray(3) { s.glideMsOf(AXES[it]) },
                    BooleanArray(3) { s.curveOf(AXES[it]) == InertiaCurve.LINEAR },
                    routes,
                    Array(3) { s.accelOf(AXES[it]).flat() },
                    BooleanArray(3) { s.accelOf(AXES[it]).let { c -> !c.isOff && c.axisSpeed } },
                    BooleanArray(3) { s.accelOf(AXES[it]).let { c -> !c.isOff && c.smooth } },
                    IntArray(3) { s.spaceOf(AXES[it]).ordinal },
                )
            }
        }
    }

    /** One gyro mapping: [axis] ([MotionAxis] ordinal 0..2) to [target] ([MOUSE_X] .. [PAD] + [GamepadAxis] ordinal), times [scale]. */
    data class Route(val axis: Int, val target: Int, val scale: Float) {
        companion object {
            const val MOUSE_X = 0
            const val MOUSE_Y = 1
            const val WHEEL = 2
            const val HWHEEL = 3
            const val PAD = 4
        }
    }

    /** Where [step] sent one sample: mouse counts, wheel units and game pad axes (by [GamepadAxis] ordinal). */
    class Routed {
        var dx = 0
        var dy = 0
        var wheel = 0
        var hwheel = 0
        val pad = FloatArray(PAD_AXES)
    }

    private val routed = Routed()

    private var remX = 0f
    private var remY = 0f
    private var remWheel = 0f
    private var remHWheel = 0f

    // Smoothing: each sample's smoothed share (counts still to send, x and y) is spread evenly over
    // [SMOOTHING_WINDOW_S] from its own sample on; its age (s) says how much of that time has passed.
    // Oldest first, [spreadN] in use.
    private val spreadX = FloatArray(SPREAD_MAX)
    private val spreadY = FloatArray(SPREAD_MAX)
    private val spreadAge = FloatArray(SPREAD_MAX)
    private var spreadN = 0

    // Inertia, per axis (yaw, pitch, roll): its recent speed (deg/s EMA), its glide speed (deg/s,
    // 0 = none), glide time (ms) and linear slowing (deg/s per ms, 0 = smooth); the axes on last sample.
    private val speed = FloatArray(3)
    private val glideV = FloatArray(3)
    private val glideTau = FloatArray(3)
    private val glideDec = FloatArray(3)
    private val glideOut = FloatArray(3)
    private var lastAxes = 0

    // Acceleration, per axis: the gain of this sample; an axis off keeps the one it had when it was last on, so a glide keeps its boost.
    private val gain = floatArrayOf(1f, 1f, 1f)

    /** An axis glides, or one was on last sample (turning it off may start a glide): [step] wants every sample. */
    val coasting: Boolean get() = lastAxes != 0 || glideV[0] != 0f || glideV[1] != 0f || glideV[2] != 0f

    /**
     * One sample: [yaw], [pitch], [roll] (deg/s) over [dt] seconds with [axes] on
     * ([MappingEngine.gyroBit]s), sent where [c] says: mouse motion at the axis's sensitivity
     * through the filters ([tightening] and [smoothing] thresholds: the phone's turning speed in
     * deg/s, 0 = off), scrolling at [DEG_PER_NOTCH] and game pad axes full at
     * [STICK_FULL_DEG_PER_S] at the default sensitivity. An axis off moves only by its glide, and
     * only the mouse and wheel. The result is reused by the next call.
     */
    fun step(yaw: Float, pitch: Float, roll: Float, dt: Float, c: Config, axes: Int, tightening: Float, smoothing: Float): Routed {
        coast(c, yaw, pitch, roll, axes, dt)
        accelerate(c, yaw, pitch, roll, axes)
        var vx = 0f; var vy = 0f; var w = 0f; var hw = 0f
        // The phone's own turning (deg/s) that moves the cursor: before sensitivity and acceleration.
        var px = 0f; var py = 0f
        val pad = routed.pad
        pad.fill(0f)
        val routes = c.routes
        for (i in routes.indices) {
            val r = routes[i]
            val on = axes and (1 shl r.axis) != 0
            val g = if (on) 0f else glideOut[r.axis]
            if (!on && (g == 0f || r.target >= Route.PAD)) continue
            val k = c.sensitivity[r.axis]
            val raw = if (on) when (r.axis) { 0 -> yaw; 1 -> pitch; else -> roll } else g
            val v = raw * gain[r.axis]
            when (r.target) {
                Route.MOUSE_X -> { vx += v * k * r.scale; px += raw * sign(r.scale) }
                Route.MOUSE_Y -> { vy += v * k * r.scale; py += raw * sign(r.scale) }
                Route.WHEEL -> w += v * k / GyroSettings.DEFAULT_SENSITIVITY * r.scale
                Route.HWHEEL -> hw += v * k / GyroSettings.DEFAULT_SENSITIVITY * r.scale
                else -> {
                    val a = r.target - Route.PAD
                    // Stick Y is + down: pitching up pushes it up.
                    val up = if (a == LY || a == RY) -1f else 1f
                    pad[a] += up * v * k / GyroSettings.DEFAULT_SENSITIVITY * r.scale / STICK_FULL_DEG_PER_S
                }
            }
        }
        for (a in pad.indices) {
            pad[a] = if (a == LT || a == RT) abs(pad[a]).coerceAtMost(1f) else pad[a].coerceIn(-1f, 1f)
        }
        counts(vx, vy, sqrt(px * px + py * py), dt, tightening, smoothing)
        val fw = w * dt * MouseState.WHEEL_UNIT / DEG_PER_NOTCH + remWheel
        val fh = hw * dt * MouseState.WHEEL_UNIT / DEG_PER_NOTCH + remHWheel
        routed.wheel = fw.toInt(); remWheel = fw - routed.wheel
        routed.hwheel = fh.toInt(); remHWheel = fh - routed.hwheel
        return routed
    }

    /**
     * Mouse counts of cursor velocity [vx0] (right +) and [vy0] (up +) in counts/s, sensitivity
     * applied, over [dt]: filtered by the phone's turning speed [speed] (deg/s), the remainder kept,
     * into [routed].
     */
    private fun counts(vx0: Float, vy0: Float, speed: Float, dt: Float, tightening: Float, smoothing: Float) {
        var vx = vx0
        var vy = vy0
        if (tightening > 0f && speed < tightening) {
            val k = speed / tightening
            vx *= k; vy *= k
        }
        var sx = 0f; var sy = 0f
        if (smoothing > 0f || spreadN > 0) {
            // Fully smoothed below threshold/2, fully direct above threshold, blended between. Only
            // the smoothed share is spread, so it is sent once, over the window, and fast motion is
            // never replayed after the phone stops. Off (0): raw (what is still spread goes out).
            val direct = if (smoothing > 0f) ((speed - smoothing / 2f) / (smoothing / 2f)).coerceIn(0f, 1f) else 1f
            val share = 1f - direct
            spread(vx * share * dt, vy * share * dt, dt)
            sx = spreadOutX; sy = spreadOutY
            vx *= direct; vy *= direct
        }
        val fx = vx * dt + sx + remX
        val fy = -(vy * dt + sy) + remY
        val dx = fx.roundToInt()
        val dy = fy.roundToInt()
        remX = fx - dx
        remY = fy - dy
        routed.dx = dx; routed.dy = dy
    }

    private var spreadOutX = 0f
    private var spreadOutY = 0f

    /**
     * Adds this sample's smoothed counts [x], [y] to the spread and takes what every spread share
     * sends over the next [dt] (into [spreadOutX], [spreadOutY]): a share sends what it has left in
     * proportion to the window time it has left, and all of it once its window is over.
     */
    private fun spread(x: Float, y: Float, dt: Float) {
        if (x != 0f || y != 0f) {
            if (spreadN == SPREAD_MAX) {
                // Full (very short samples): the oldest goes out now.
                remX += spreadX[0]; remY -= spreadY[0]
                drop(0)
            }
            spreadX[spreadN] = x; spreadY[spreadN] = y; spreadAge[spreadN] = 0f
            spreadN++
        }
        var ox = 0f; var oy = 0f
        var i = 0
        while (i < spreadN) {
            val a0 = spreadAge[i]
            val a1 = a0 + dt
            if (a1 >= SMOOTHING_WINDOW_S) {
                ox += spreadX[i]; oy += spreadY[i]
                drop(i)
                continue
            }
            val part = dt / (SMOOTHING_WINDOW_S - a0)
            val gx = spreadX[i] * part; val gy = spreadY[i] * part
            ox += gx; oy += gy
            spreadX[i] -= gx; spreadY[i] -= gy
            spreadAge[i] = a1
            i++
        }
        spreadOutX = ox; spreadOutY = oy
    }

    private fun drop(i: Int) {
        for (j in i until spreadN - 1) {
            spreadX[j] = spreadX[j + 1]; spreadY[j] = spreadY[j + 1]; spreadAge[j] = spreadAge[j + 1]
        }
        spreadN--
    }

    /**
     * Each axis's acceleration gain for this sample: an axis on takes its curve's gain at its own
     * speed or the whole phone's ([Config.axisSpeed]); one off keeps its last.
     */
    private fun accelerate(c: Config, yaw: Float, pitch: Float, roll: Float, axes: Int) {
        val whole = sqrt(yaw * yaw + pitch * pitch + roll * roll)
        for (i in 0 until 3) {
            if (axes and (1 shl i) == 0) continue
            val curve = c.curves[i]
            if (curve.isEmpty()) { gain[i] = 1f; continue }
            val speed = if (c.axisSpeed[i]) abs(when (i) { 0 -> yaw; 1 -> pitch; else -> roll }) else whole
            gain[i] = GyroCurves.gain(curve, speed, c.smooth[i])
        }
    }

    fun reset() {
        remX = 0f; remY = 0f; remWheel = 0f; remHWheel = 0f
        spreadN = 0; spreadOutX = 0f; spreadOutY = 0f
        speed.fill(0f); glideV.fill(0f); glideOut.fill(0f); lastAxes = 0
        gain.fill(1f)
    }

    /**
     * Inertia for one sample with [axes] on, over [dt]: an axis on follows its speed and never
     * glides; one just turned off with inertia glides on from its recent speed; a gliding one
     * slows down by its glide time and curve ([InertiaCurve]).
     */
    private fun coast(c: Config, yaw: Float, pitch: Float, roll: Float, axes: Int, dt: Float) {
        val dtMs = dt * 1000f
        for (i in 0 until 3) {
            val bit = 1 shl i
            glideOut[i] = 0f
            if (axes and bit != 0) {
                // A short EMA, so one noisy sample does not set the glide.
                val k = if (dtMs <= 0f) 0f else 1f - exp(-dtMs / SPEED_TAU_MS)
                val m = when (i) { 0 -> yaw; 1 -> pitch; else -> roll }
                speed[i] += k * (m - speed[i])
                glideV[i] = 0f
                continue
            }
            if (lastAxes and bit != 0) {
                // Just turned off: glides on from its recent speed, this sample at that speed.
                glideV[i] = 0f
                if (c.inertia[i] && abs(speed[i]) >= GLIDE_MIN_DEG_PER_S) {
                    val tau = c.glideMs[i].coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
                    glideV[i] = speed[i]; glideTau[i] = tau
                    // Linear: stops after 2·T, so it turns as far as the smooth one.
                    glideDec[i] = if (c.linear[i]) abs(speed[i]) / (2f * tau) else 0f
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

    companion object {
        const val PAD_AXES = 6
        private val LY = GamepadAxis.LY.ordinal
        private val RY = GamepadAxis.RY.ordinal
        private val LT = GamepadAxis.LT.ordinal
        private val RT = GamepadAxis.RT.ordinal
        /** Smoothing spreads each sample's smoothed motion evenly over this time (s), whatever the sample rate. */
        const val SMOOTHING_WINDOW_S = 0.008f

        /** The most shares being spread at once: a window's worth of samples at 4 kHz. */
        private const val SPREAD_MAX = 32

        /** Degrees of turn per wheel notch when gyro scrolls. */
        const val DEG_PER_NOTCH = 15f

        /** Turning this fast (°/s) deflects a game pad axis fully. */
        const val STICK_FULL_DEG_PER_S = 90f

        private val AXES = arrayOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)

        /** The recent speed a glide starts from: an EMA with this time constant (ms). */
        private const val SPEED_TAU_MS = 15f

        /** An axis turned off slower than this (deg/s) does not glide; a glide stops below [GLIDE_STOP_DEG_PER_S]. */
        const val GLIDE_MIN_DEG_PER_S = 5f
        const val GLIDE_STOP_DEG_PER_S = 0.5f
        const val MIN_GLIDE_MS = 100f
        const val MAX_GLIDE_MS = 1000f
    }
}
