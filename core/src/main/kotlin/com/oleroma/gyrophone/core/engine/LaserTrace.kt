package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a laser pointer display remembers between frames: in Draw mode, the strokes the beam
 * painted on the sphere's inside (they fade after a while, or stay), a ring of unit directions in the shown frame (x right, y up, z toward the viewer) with their
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

    /** Draw mode's strokes. */
    val strokes = Ring(STROKE_MAX)

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
     * doesn't paint (the pose eases back then, which isn't aiming); with [clearOnPress] pressing
     * one clears the strokes. With [draw] it paints; turning Draw off clears the strokes.
     */
    fun add(x: Float, y: Float, z: Float, nowMs: Long, draw: Boolean, held: Boolean, clearOnPress: Boolean = false) {
        val pressed = clearOnPress && held && !wasHeld
        wasHeld = held
        if ((!draw || pressed) && strokes.count > 0) clear()
        val l = sqrt(x * x + y * y + z * z)
        if (!draw || l < 1e-6f || held || l.isNaN()) {
            strokeGap = true
            return
        }
        val dx = x / l
        val dy = y / l
        val dz = z / l
        // A new point once the beam moved a little: a still beam adds nothing.
        if (!strokeGap && lastX * dx + lastY * dy + lastZ * dz > STROKE_STEP_COS) return
        strokes.add(dx, dy, dz, nowMs, strokeGap)
        strokeGap = false
        lastX = dx; lastY = dy; lastZ = dz
    }

    /** With [fadeMs] > 0, drops strokes older than it. */
    fun expire(nowMs: Long, fadeMs: Int) {
        if (fadeMs > 0) strokes.expire(nowMs, fadeMs)
    }

    /** Some stroke is still fading (draw again while it fades). */
    fun fading(fadeMs: Int): Boolean = fadeMs > 0 && strokes.count > 0

    /** Clears the strokes. */
    fun clear() {
        strokes.clear()
        strokeGap = true
        version++
    }

    companion object {
        const val STROKE_MAX = 4096
        const val STROKE_STEP_DEG = 0.5f
        private val STROKE_STEP_COS = cos(Math.toRadians(STROKE_STEP_DEG.toDouble())).toFloat()

        /** How far round from straight ahead (into the screen) shown-frame direction z is: 0 ahead .. 1 straight behind. */
        fun behind(z: Float): Float = ((1f + z) / 2f).coerceIn(0f, 1f)

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

        /**
         * Picture x, y, z of shown-frame point ([x], [y], [z]) into [out] at [at]: through [VIEW], or
         * as it is (face-on) without [axon]. With [turned] (a pose matrix, row-major) the point is
         * first seen from that pose (its transpose times the point): the sphere turned under a beam
         * that stays ahead.
         */
        fun view(x: Float, y: Float, z: Float, out: FloatArray, at: Int = 0, axon: Boolean = true, turned: FloatArray? = null) {
            if (turned != null) {
                val t = turned
                return view(
                    t[0] * x + t[3] * y + t[6] * z, t[1] * x + t[4] * y + t[7] * z, t[2] * x + t[5] * y + t[8] * z,
                    out, at, axon,
                )
            }
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

        /** [GRID] or [FLAT_GRID]; with [turned] (see [view]) made anew, turned with the sphere. */
        fun grid(axon: Boolean, turned: FloatArray? = null) =
            if (turned != null) makeGrid(axon, turned) else if (axon) GRID else FLAT_GRID

        private fun makeGrid(axon: Boolean, turned: FloatArray? = null): Pair<List<FloatArray>, List<FloatArray>> {
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
                    view(p[0], p[1], p[2], q, axon = axon, turned = turned)
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

        /**
         * The rifle look's model in the phone's frame (x right, y up, z toward the player), the muzzle
         * out of the phone's back as the beam: boxes as centre x, y, z and half sizes, unit radius.
         */
        private val RIFLE = floatArrayOf(
            // stock, buffer tube, receiver, carry rail, handguard, barrel, muzzle, front sight, magazine, grip
            0f, -0.02f, 0.68f, 0.05f, 0.09f, 0.2f,
            0f, 0.03f, 0.42f, 0.03f, 0.03f, 0.08f,
            0f, 0.03f, 0.12f, 0.055f, 0.075f, 0.22f,
            0f, 0.13f, 0.1f, 0.028f, 0.025f, 0.18f,
            0f, 0.03f, -0.32f, 0.055f, 0.055f, 0.2f,
            0f, 0.03f, -0.7f, 0.018f, 0.018f, 0.18f,
            0f, 0.03f, -0.9f, 0.026f, 0.026f, 0.03f,
            0f, 0.1f, -0.5f, 0.012f, 0.06f, 0.015f,
            0f, -0.17f, 0.0f, 0.035f, 0.12f, 0.06f,
            0f, -0.14f, 0.28f, 0.03f, 0.09f, 0.045f,
        )

        /** One face of the rifle in the picture: its corners (x, y pairs, unit radius) and how lit it is (0..1). */
        class Face(val xy: FloatArray, val light: Float, val depth: Float)

        /**
         * The rifle turned by pose [m], seen as [view] sees it ([axon], [turned]): the faces toward the
         * viewer, farthest first, lit from above left.
         */
        fun rifle(m: FloatArray, axon: Boolean, turned: FloatArray?): List<Face> {
            val faces = ArrayList<Face>(32)
            val p = FloatArray(24)
            val n = FloatArray(3)
            val lx = -0.35f
            val ly = 0.6f
            val lz = 0.72f
            for (b in 0 until RIFLE.size / 6) {
                val o = b * 6
                for (k in 0 until 8) {
                    val x = RIFLE[o] + (if (k and 1 != 0) RIFLE[o + 3] else -RIFLE[o + 3])
                    val y = RIFLE[o + 1] + (if (k and 2 != 0) RIFLE[o + 4] else -RIFLE[o + 4])
                    val z = RIFLE[o + 2] + (if (k and 4 != 0) RIFLE[o + 5] else -RIFLE[o + 5])
                    view(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z, p, k * 3, axon, turned)
                }
                for (f in 0 until 6) {
                    val axis = f / 2
                    val sign = if (f % 2 == 0) -1f else 1f
                    val nx = if (axis == 0) sign else 0f
                    val ny = if (axis == 1) sign else 0f
                    val nz = if (axis == 2) sign else 0f
                    view(m[0] * nx + m[1] * ny + m[2] * nz, m[3] * nx + m[4] * ny + m[5] * nz, m[6] * nx + m[7] * ny + m[8] * nz, n, 0, axon, turned)
                    if (n[2] <= 1e-4f) continue
                    val bit = 1 shl axis
                    val on = if (sign > 0) bit else 0
                    // The face's corners, in order round it.
                    val others = (0..2).filter { it != axis }.map { 1 shl it }
                    val ring = intArrayOf(0, others[0], others[0] or others[1], others[1]).map { it or on }
                    val xy = FloatArray(8)
                    var depth = 0f
                    for ((i, k) in ring.withIndex()) {
                        xy[i * 2] = p[k * 3]; xy[i * 2 + 1] = p[k * 3 + 1]
                        depth += p[k * 3 + 2]
                    }
                    val light = (0.45f + 0.55f * (n[0] * lx + n[1] * ly + n[2] * lz).coerceAtLeast(0f)).coerceIn(0f, 1f)
                    faces.add(Face(xy, light, depth / 4f))
                }
            }
            faces.sortBy { it.depth }
            return faces
        }

        /**
         * Pose matrix [m] (row-major, changed) seen as the laser aims: the phone held as a gamepad
         * aims with its roll axis (out of its top edge), so that axis takes the place of the one out
         * of its back, into the screen at rest. Turning it with the phone (yaw, the turn through its
         * screen) then swings the beam sideways, and pitch up and down. A · m · Aᵀ with A taking the
         * phone's up into the screen and its screen's normal up.
         */
        fun aim(m: FloatArray) {
            val r = FloatArray(9)
            for (i in 0..2) for (j in 0..2) r[i * 3 + j] = AIM_SIGN[i] * AIM_SIGN[j] * m[AIM_AXIS[i] * 3 + AIM_AXIS[j]]
            r.copyInto(m)
        }

        private val AIM_AXIS = intArrayOf(0, 2, 1)
        private val AIM_SIGN = floatArrayOf(1f, 1f, -1f)

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
