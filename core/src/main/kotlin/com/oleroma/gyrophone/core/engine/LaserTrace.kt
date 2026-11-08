package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a laser pointer display remembers between frames: the beam's recent hit points on the
 * sphere (the trail) and, in Draw mode, the sphere's painted inside. Directions are unit vectors in
 * the shown frame (x right, y up, z toward the viewer); the sphere is cut into [CELL_DEG]° cells by
 * latitude (round the up axis) and longitude (0 straight ahead, into the screen). Each frame only
 * the cells under the brush are touched, so painting costs the same however much is painted; the
 * drawing merges neighbouring cells of one row into one strip ([forEachRun]). Same as the
 * companion's LaserTrace. One thread at a time (the UI's).
 */
class LaserTrace {
    // The trail: a ring of hit points, oldest first from [head].
    private val tx = FloatArray(TRAIL_MAX)
    private val ty = FloatArray(TRAIL_MAX)
    private val tz = FloatArray(TRAIL_MAX)
    private val tt = LongArray(TRAIL_MAX)
    private val tGap = BooleanArray(TRAIL_MAX)
    private var head = 0
    private var count = 0
    private var gap = true

    /** When each cell was last painted (ms, + 1 so 0 = never). */
    private val cells = LongArray(ROWS * COLS)
    private val rowPainted = IntArray(ROWS)

    /** Cells painted now. */
    var painted = 0
        private set

    /** Goes up when the paint is cleared. */
    var version = 0L
        private set

    /** The pose version last fed (the UI feeds once per new pose). */
    var fedVersion = Long.MIN_VALUE

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLast = false

    /** Trail points kept now. */
    val trailCount: Int get() = count

    /**
     * The beam now points at ([x], [y], [z]) (any length) at [nowMs]. While a ratchet is [held] it
     * neither trails nor paints (the pose eases back then, which isn't aiming). With [draw] the
     * cells within [brushDeg] of the beam, and of the way it went since the last call, are painted.
     */
    fun add(x: Float, y: Float, z: Float, nowMs: Long, trailMs: Int, draw: Boolean, brushDeg: Float, held: Boolean) {
        val l = sqrt(x * x + y * y + z * z)
        if (l < 1e-6f || held) {
            gap = true
            hasLast = false
            return
        }
        val dx = x / l
        val dy = y / l
        val dz = z / l
        if (trailMs > 0) {
            val i = (head + count) % TRAIL_MAX
            if (count == TRAIL_MAX) head = (head + 1) % TRAIL_MAX else count++
            tx[i] = dx; ty[i] = dy; tz[i] = dz; tt[i] = nowMs; tGap[i] = gap
            gap = false
        } else {
            count = 0
            gap = true
        }
        if (draw) {
            val brush = Math.toRadians(brushDeg.coerceIn(0.5f, 45f).toDouble()).toFloat()
            if (hasLast) {
                // Along the great circle from the last point, in steps of half the brush.
                val cosA = (lastX * dx + lastY * dy + lastZ * dz).coerceIn(-1f, 1f)
                val a = acos(cosA)
                val steps = ceil(a / (brush * 0.5f)).toInt().coerceIn(1, 360)
                val s = sin(a)
                for (k in 1..steps) {
                    val t = k / steps.toFloat()
                    if (s < 1e-4f) paint(dx, dy, dz, brush, nowMs)
                    else {
                        val wa = sin((1 - t) * a) / s
                        val wb = sin(t * a) / s
                        paint(lastX * wa + dx * wb, lastY * wa + dy * wb, lastZ * wa + dz * wb, brush, nowMs)
                    }
                }
            } else paint(dx, dy, dz, brush, nowMs)
            lastX = dx; lastY = dy; lastZ = dz
            hasLast = true
        } else hasLast = false
    }

    /** Paints every cell whose centre is within [brush] radians of unit direction ([x], [y], [z]). */
    private fun paint(x: Float, y: Float, z: Float, brush: Float, nowMs: Long) {
        val lat = asin(y.coerceIn(-1f, 1f))
        val lon = atan2(x, -z)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val cosB = cos(brush)
        val r0 = rowOf(lat - brush)
        val r1 = rowOf(lat + brush)
        val stamp = nowMs + 1
        for (r in r0..r1) {
            val lc = rowLat(r)
            val cl = cos(lc)
            val sl = sin(lc)
            val den = cosLat * cl
            // The longitudes within the brush on this row: cos d = sin a sin b + cos a cos b cos Δ.
            val half = if (den < 1e-6f) PI_F else {
                val c = (cosB - sinLat * sl) / den
                if (c > 1f) continue
                if (c <= -1f) PI_F else acos(c)
            }
            if (half >= PI_F) {
                for (c in 0 until COLS) mark(r, c, stamp)
                continue
            }
            val c0 = ceil((lon - half + PI_F) / CELL_RAD - 0.5f).toInt()
            val c1 = floor((lon + half + PI_F) / CELL_RAD - 0.5f).toInt()
            if (c1 < c0) mark(r, colOf(lon), stamp)
            else for (c in c0..c1) mark(r, ((c % COLS) + COLS) % COLS, stamp)
        }
        // The cell the beam is in always.
        mark(rowOf(lat), colOf(lon), stamp)
    }

    private fun mark(r: Int, c: Int, stamp: Long) {
        val i = r * COLS + c
        if (cells[i] == 0L) {
            painted++
            rowPainted[r]++
        }
        cells[i] = stamp
    }

    /** Drops trail points older than [trailMs] and, with [fadeMs] > 0, paint older than it. */
    fun expire(nowMs: Long, trailMs: Int, fadeMs: Int) {
        while (count > 0 && nowMs - tt[head] > trailMs) {
            head = (head + 1) % TRAIL_MAX
            count--
        }
        if (count > 0) tGap[head] = true
        if (fadeMs <= 0 || painted == 0) return
        for (r in 0 until ROWS) {
            if (rowPainted[r] == 0) continue
            val base = r * COLS
            for (c in 0 until COLS) {
                val s = cells[base + c]
                if (s != 0L && nowMs - (s - 1) > fadeMs) {
                    cells[base + c] = 0L
                    rowPainted[r]--
                    painted--
                }
            }
        }
    }

    /** Some trail point or fading paint is left (draw again while it fades). */
    fun fading(fadeMs: Int): Boolean = count > 0 || (fadeMs > 0 && painted > 0)

    /** Clears the paint and the trail. */
    fun clear() {
        cells.fill(0L)
        rowPainted.fill(0)
        painted = 0
        count = 0
        head = 0
        gap = true
        hasLast = false
        version++
    }

    /** Trail point [i] (0 oldest): direction into [out] (x, y, z), returns how fresh it is, 1 (new) .. 0 (gone). */
    fun trailPoint(i: Int, nowMs: Long, trailMs: Int, out: FloatArray): Float {
        val k = (head + i) % TRAIL_MAX
        out[0] = tx[k]; out[1] = ty[k]; out[2] = tz[k]
        return (1f - (nowMs - tt[k]) / trailMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    }

    /** Trail point [i] starts a new line (none drawn from the point before it). */
    fun trailGap(i: Int): Boolean = tGap[(head + i) % TRAIL_MAX]

    /**
     * Each run of neighbouring painted cells on one row with the same strength (1..[LEVELS], by
     * [fadeMs]; all full without fading) and the same side of [front]: row, first and last column.
     */
    inline fun forEachRun(nowMs: Long, fadeMs: Int, front: (row: Int, col: Int) -> Boolean, run: (row: Int, c0: Int, c1: Int, level: Int, front: Boolean) -> Unit) {
        for (r in 0 until ROWS) {
            if (rowPainted(r) == 0) continue
            var start = -1
            var level = 0
            var side = false
            for (c in 0..COLS) {
                val lv = if (c == COLS) 0 else levelOf(r, c, nowMs, fadeMs)
                val f = lv != 0 && front(r, c)
                if (start >= 0 && (lv != level || f != side)) {
                    run(r, start, c - 1, level, side)
                    start = -1
                }
                if (lv != 0 && start < 0) {
                    start = c
                    level = lv
                    side = f
                }
            }
        }
    }

    /** Cells painted on row [r]. */
    fun rowPainted(r: Int): Int = rowPainted[r]

    /** Cell ([r], [c])'s strength: 0 unpainted, 1..[LEVELS] fading to full. */
    fun levelOf(r: Int, c: Int, nowMs: Long, fadeMs: Int): Int {
        val s = cells[r * COLS + c]
        if (s == 0L) return 0
        if (fadeMs <= 0) return LEVELS
        val left = 1f - (nowMs - (s - 1)) / fadeMs.toFloat()
        if (left <= 0f) return 0
        return ceil(left * LEVELS).toInt().coerceIn(1, LEVELS)
    }

    companion object {
        const val CELL_DEG = 2
        const val ROWS = 180 / CELL_DEG
        const val COLS = 360 / CELL_DEG
        const val LEVELS = 8
        const val TRAIL_MAX = 512
        private const val PI_F = Math.PI.toFloat()
        const val CELL_RAD = (CELL_DEG * Math.PI / 180).toFloat()

        /** Row [r]'s centre latitude (radians, up +). */
        fun rowLat(r: Int): Float = (r + 0.5f) * CELL_RAD - PI_F / 2

        /** Column [c]'s centre longitude (radians, right +, 0 straight ahead). */
        fun colLon(c: Int): Float = (c + 0.5f) * CELL_RAD - PI_F

        fun rowOf(lat: Float): Int = floor((lat + PI_F / 2) / CELL_RAD).toInt().coerceIn(0, ROWS - 1)

        fun colOf(lon: Float): Int = ((floor((lon + PI_F) / CELL_RAD).toInt() % COLS) + COLS) % COLS

        /** The unit direction at latitude [lat], longitude [lon] into [out] at [at]. */
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

        /** Picture x, y, z of shown-frame point ([x], [y], [z]) into [out] at [at]. */
        fun view(x: Float, y: Float, z: Float, out: FloatArray, at: Int = 0) {
            val v = VIEW
            out[at] = v[0] * x + v[1] * y + v[2] * z
            out[at + 1] = v[3] * x + v[4] * y + v[5] * z
            out[at + 2] = v[6] * x + v[7] * y + v[8] * z
        }

        /** Each cell's centre faces the viewer (lies on the sphere's near half), by row * [COLS] + column. */
        val CELL_FRONT: BooleanArray = BooleanArray(ROWS * COLS).also { f ->
            val p = FloatArray(3)
            val q = FloatArray(3)
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                dir(rowLat(r), colLon(c), p)
                view(p[0], p[1], p[2], q)
                f[r * COLS + c] = q[2] > 0f
            }
        }

        /** The [GRID_DEG]° grid (latitudes, meridians) in the picture, unit radius, as polylines of x, y pairs: (near half, far half). */
        val GRID: Pair<List<FloatArray>, List<FloatArray>> by lazy {
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
                    view(p[0], p[1], p[2], q)
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
            front to back
        }

        const val GRID_DEG = 15
        private const val GRID_STEPS = 96

        /** The beam's direction for pose matrix [m] (shown frame): out of the phone's back. */
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
