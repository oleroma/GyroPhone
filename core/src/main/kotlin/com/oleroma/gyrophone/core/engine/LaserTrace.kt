package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a laser pointer display remembers between frames: the beam's recent hit points on the
 * sphere (the trail) and, in Draw mode, the strokes it painted on the sphere's inside. Both are
 * rings of unit directions in the shown frame (x right, y up, z toward the viewer) with their
 * times; a point starts a new line after a gap (a held ratchet, Draw just turned on). Strokes keep
 * a point each time the beam moved [STROKE_STEP_DEG]° or more, up to [STROKE_MAX] (the oldest go
 * first), so drawing them costs the same however long you paint. Same as the companion's
 * LaserTrace. One thread at a time (the UI's).
 */
class LaserTrace {
    /** A ring of directions with times, oldest first. */
    class Ring(private val capacity: Int) {
        private val x = FloatArray(capacity)
        private val y = FloatArray(capacity)
        private val z = FloatArray(capacity)
        private val t = LongArray(capacity)
        private val gap = BooleanArray(capacity)
        private var head = 0

        /** Points kept now. */
        var count = 0
            private set

        fun add(px: Float, py: Float, pz: Float, ms: Long, startsLine: Boolean) {
            val i = (head + count) % capacity
            if (count == capacity) {
                head = (head + 1) % capacity
                gap[head] = true // the oldest left has no line from before it
            } else count++
            x[i] = px; y[i] = py; z[i] = pz; t[i] = ms; gap[i] = startsLine
        }

        /** Drops points older than [ms] at [nowMs]. */
        fun expire(nowMs: Long, ms: Int) {
            while (count > 0 && nowMs - t[head] > ms) {
                head = (head + 1) % capacity
                count--
            }
            if (count > 0) gap[head] = true
        }

        fun clear() {
            count = 0
            head = 0
        }

        /** Point [i] (0 oldest) into [out] (x, y, z); returns its time (ms). */
        fun point(i: Int, out: FloatArray, at: Int = 0): Long {
            val k = (head + i) % capacity
            out[at] = x[k]; out[at + 1] = y[k]; out[at + 2] = z[k]
            return t[k]
        }

        /** Point [i] starts a new line (none drawn from the point before it). */
        fun gap(i: Int): Boolean = gap[(head + i) % capacity]
    }

    /** The trail's hit points. */
    val trail = Ring(TRAIL_MAX)

    /** Draw mode's strokes. */
    val strokes = Ring(STROKE_MAX)

    private var trailGap = true
    private var strokeGap = true
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var wasHeld = false

    /** Goes up when the strokes are cleared. */
    var version = 0L
        private set

    /** The pose version last fed (the UI feeds once per new pose). */
    var fedVersion = Long.MIN_VALUE

    /**
     * The beam now points at ([x], [y], [z]) (any length) at [nowMs]. While a ratchet is [held] it
     * neither trails nor paints (the pose eases back then, which isn't aiming); with
     * [clearOnPress] pressing one clears the strokes. With [draw] it paints; turning Draw off
     * clears the strokes.
     */
    fun add(x: Float, y: Float, z: Float, nowMs: Long, trailMs: Int, draw: Boolean, held: Boolean, clearOnPress: Boolean = false) {
        val pressed = clearOnPress && held && !wasHeld
        wasHeld = held
        if ((!draw || pressed) && strokes.count > 0) clear()
        val l = sqrt(x * x + y * y + z * z)
        if (l < 1e-6f || held || l.isNaN()) {
            trailGap = true
            strokeGap = true
            return
        }
        val dx = x / l
        val dy = y / l
        val dz = z / l
        if (trailMs > 0) {
            trail.add(dx, dy, dz, nowMs, trailGap)
            trailGap = false
        } else {
            trail.clear()
            trailGap = true
        }
        if (!draw) {
            strokeGap = true
            return
        }
        // A new point once the beam moved a little: a still beam adds nothing.
        if (!strokeGap && lastX * dx + lastY * dy + lastZ * dz > STROKE_STEP_COS) return
        strokes.add(dx, dy, dz, nowMs, strokeGap)
        strokeGap = false
        lastX = dx; lastY = dy; lastZ = dz
    }

    /** Drops trail points older than [trailMs] and, with [fadeMs] > 0, strokes older than it. */
    fun expire(nowMs: Long, trailMs: Int, fadeMs: Int) {
        trail.expire(nowMs, trailMs)
        if (fadeMs > 0) strokes.expire(nowMs, fadeMs)
    }

    /** Some trail point or fading stroke is left (draw again while it fades). */
    fun fading(fadeMs: Int): Boolean = trail.count > 0 || (fadeMs > 0 && strokes.count > 0)

    /** Clears the strokes and the trail. */
    fun clear() {
        strokes.clear()
        trail.clear()
        trailGap = true
        strokeGap = true
        version++
    }

    companion object {
        const val TRAIL_MAX = 512
        const val STROKE_MAX = 4096
        const val STROKE_STEP_DEG = 0.5f
        private val STROKE_STEP_COS = cos(Math.toRadians(STROKE_STEP_DEG.toDouble())).toFloat()

        /** How bright stroke point [i] of [count] made at [t] is at [nowMs]: 1 new .. 0 gone ([fadeMs]), or by its place without fading. */
        fun strokeLife(i: Int, count: Int, t: Long, nowMs: Long, fadeMs: Int): Float =
            if (fadeMs > 0) (1f - (nowMs - t) / fadeMs.toFloat()).coerceIn(0f, 1f)
            else if (count <= 1) 1f else (i / (count - 1f))

        /** The unit direction at latitude [lat], longitude [lon] (0 straight ahead, into the screen) into [out] at [at]. */
        fun dir(lat: Float, lon: Float, out: FloatArray, at: Int = 0) {
            val cl = cos(lat)
            out[at] = cl * sin(lon)
            out[at + 1] = sin(lat)
            out[at + 2] = -cl * cos(lon)
        }

        // The fixed axonometric view: turned 30° round the vertical, then seen from 30° above.
        private val YAW = Math.toRadians(-30.0)
        private val TILT = Math.toRadians(30.0)

        /** The view's matrix (row-major 3×3): shown frame to the picture (x right, y up, z toward the viewer). */
        val VIEW: FloatArray = run {
            val cb = cos(YAW).toFloat()
            val sb = sin(YAW).toFloat()
            val ca = cos(TILT).toFloat()
            val sa = sin(TILT).toFloat()
            // Rx(TILT) · Ry(YAW)
            floatArrayOf(
                cb, 0f, sb,
                sa * sb, ca, -sa * cb,
                -ca * sb, sa, ca * cb,
            )
        }

        /** Picture x, y, z of shown-frame point ([x], [y], [z]) into [out] at [at]: through [VIEW], or as it is (face-on) without [axon]. */
        fun view(x: Float, y: Float, z: Float, out: FloatArray, at: Int = 0, axon: Boolean = true) {
            if (!axon) {
                out[at] = x; out[at + 1] = y; out[at + 2] = z
                return
            }
            val v = VIEW
            out[at] = v[0] * x + v[1] * y + v[2] * z
            out[at + 1] = v[3] * x + v[4] * y + v[5] * z
            out[at + 2] = v[6] * x + v[7] * y + v[8] * z
        }

        /** [m] (row-major 3×3, changed) seen through the axonometric [VIEW]: VIEW · m. */
        fun axonometric(m: FloatArray) {
            val v = VIEW
            val r = FloatArray(9)
            for (i in 0..2) for (j in 0..2) r[i * 3 + j] = v[i * 3] * m[j] + v[i * 3 + 1] * m[3 + j] + v[i * 3 + 2] * m[6 + j]
            r.copyInto(m)
        }

        /** The [GRID_DEG]° grid (latitudes, meridians) in the axonometric picture, unit radius, as polylines of x, y pairs: (near half, far half). */
        val GRID: Pair<List<FloatArray>, List<FloatArray>> by lazy { makeGrid(true) }

        /** The same grid seen face-on. */
        val FLAT_GRID: Pair<List<FloatArray>, List<FloatArray>> by lazy { makeGrid(false) }

        /** [GRID] or [FLAT_GRID]. */
        fun grid(axon: Boolean) = if (axon) GRID else FLAT_GRID

        private fun makeGrid(axon: Boolean): Pair<List<FloatArray>, List<FloatArray>> {
            val front = ArrayList<FloatArray>()
            val back = ArrayList<FloatArray>()
            val p = FloatArray(3)
            val q = FloatArray(3)
            fun circle(point: (Double, FloatArray) -> Unit) {
                var line = ArrayList<Float>()
                var lastFront = false
                var lx = 0f
                var ly = 0f
                var lz = 0f
                fun flush() {
                    if (line.size >= 4) (if (lastFront) front else back).add(line.toFloatArray())
                    line = ArrayList()
                }
                for (i in 0..GRID_STEPS) {
                    point(i * 2 * Math.PI / GRID_STEPS, p)
                    view(p[0], p[1], p[2], q, axon = axon)
                    val f = q[2] >= 0f
                    if (i > 0 && f != lastFront) {
                        // Cut where it crosses the rim, pushed onto it.
                        val t = lz / (lz - q[2])
                        var rx = lx + (q[0] - lx) * t
                        var ry = ly + (q[1] - ly) * t
                        val l = sqrt(rx * rx + ry * ry)
                        if (l > 0f) { rx /= l; ry /= l }
                        line.add(rx); line.add(ry)
                        flush()
                        line.add(rx); line.add(ry)
                    }
                    line.add(q[0]); line.add(q[1])
                    lastFront = f
                    lx = q[0]; ly = q[1]; lz = q[2]
                }
                flush()
            }
            var lat = -90 + GRID_DEG
            while (lat < 90) {
                val a = Math.toRadians(lat.toDouble()).toFloat()
                circle { t, out -> dir(a, t.toFloat(), out) }
                lat += GRID_DEG
            }
            var lon = 0
            while (lon < 180) {
                val b = Math.toRadians(lon.toDouble()).toFloat()
                val sb = sin(b)
                val cb = cos(b)
                // A great circle through both poles, at longitude b (and b + 180°).
                circle { t, out ->
                    val ct = cos(t).toFloat()
                    out[0] = ct * sb; out[1] = sin(t).toFloat(); out[2] = -ct * cb
                }
                lon += GRID_DEG
            }
            return front to back
        }

        const val GRID_DEG = 15
        private const val GRID_STEPS = 96

        /** The beam's direction for pose matrix [m] (row-major, phone frame to shown): out of the phone's back. */
        fun beam(m: FloatArray, out: FloatArray) {
            out[0] = -m[2]; out[1] = -m[5]; out[2] = -m[8]
        }

        /** The phone's up for pose matrix [m], squared against [beam] (the roll tick's way), into [out]; false when none. */
        fun rollTick(m: FloatArray, beam: FloatArray, out: FloatArray): Boolean {
            val ux = m[1]
            val uy = m[4]
            val uz = m[7]
            val d = ux * beam[0] + uy * beam[1] + uz * beam[2]
            val x = ux - d * beam[0]
            val y = uy - d * beam[1]
            val z = uz - d * beam[2]
            val l = sqrt(x * x + y * y + z * z)
            if (l < 1e-4f || abs(d) > 0.9999f) return false
            out[0] = x / l; out[1] = y / l; out[2] = z / l
            return true
        }
    }
}
