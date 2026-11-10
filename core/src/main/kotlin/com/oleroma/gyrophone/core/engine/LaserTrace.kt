package com.oleroma.gyrophone.core.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a laser pointer display remembers between frames: in Draw mode, the strokes the beam
 * painted on the sphere's inside, a ring of unit directions in the shown frame (x right, y up, z toward the viewer) with their
 * times; a point starts a new line after a gap (a held ratchet, Draw just turned on). Strokes keep
 * a point each time the beam moved [STROKE_STEP_DEG]° or more, up to [STROKE_MAX] (the oldest go
 * first); each new point paints the [paint] map, which is what shows. Same as the companion's
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
        private val kind = ByteArray(capacity)
        /** Degrees painted up to each point, counted along the lines (a new line adds none). */
        private val along = DoubleArray(capacity)
        private var head = 0

        /** Points kept now. */
        var count = 0
            private set

        fun add(px: Float, py: Float, pz: Float, ms: Long, startsLine: Boolean, paintKind: Int = LaserPaint.PAINT) {
            val i = (head + count) % capacity
            var a = 0.0
            if (count > 0) {
                val l = (head + count - 1) % capacity
                a = along[l]
                if (!startsLine) {
                    val dot = (x[l] * px + y[l] * py + z[l] * pz).toDouble().coerceIn(-1.0, 1.0)
                    a += Math.toDegrees(kotlin.math.acos(dot))
                }
            }
            if (count == capacity) {
                head = (head + 1) % capacity
                gap[head] = true // the oldest left has no line from before it
            } else count++
            x[i] = px; y[i] = py; z[i] = pz; t[i] = ms; gap[i] = startsLine; kind[i] = paintKind.toByte(); along[i] = a
        }

        fun clear() {
            count = 0
            head = 0
        }

        /** Drops the oldest points more than [deg] degrees of line behind the newest. */
        fun cut(deg: Float) {
            if (count == 0) return
            val newest = along[(head + count - 1) % capacity]
            var dropped = false
            while (count > 1 && newest - along[head] > deg + 0.05) { // a hair over: sums of steps round
                head = (head + 1) % capacity
                count--
                dropped = true
            }
            if (dropped) gap[head] = true
        }

        /** Degrees of line from point [i] to the newest. */
        fun behindNewest(i: Int): Float =
            if (count == 0) 0f else (along[(head + count - 1) % capacity] - along[(head + i) % capacity]).toFloat()

        /** Degrees painted up to point [i] (0 oldest), counted along the lines. */
        fun along(i: Int): Double = along[(head + i) % capacity]

        /** Point [i] (0 oldest) into [out] (x, y, z); returns its time (ms). */
        fun point(i: Int, out: FloatArray, at: Int = 0): Long {
            val k = (head + i) % capacity
            out[at] = x[k]; out[at + 1] = y[k]; out[at + 2] = z[k]
            return t[k]
        }

        /** Point [i] starts a new line (none drawn from the point before it). */
        fun gap(i: Int): Boolean = gap[(head + i) % capacity]

        /** What painted point [i] (the line to it): [LaserPaint.PAINT], [LaserPaint.AIM] or [LaserPaint.FIRE] (its colours). */
        fun kind(i: Int): Int = kind[(head + i) % capacity].toInt()

        /** Point [i] was painted while firing (the line to it takes the Fire colours). */
        fun fire(i: Int): Boolean = kind(i) == LaserPaint.FIRE

        /**
         * The way the line to point [i] (from point i − 1) moves, a unit direction into [out] (zero
         * if it doesn't): from up to [reach] points before it to up to [reach] after, within its
         * line, so with [reach] > 0 small shakes even out.
         */
        fun direction(i: Int, reach: Int, out: FloatArray) {
            var a = i - 1
            var steps = 0
            while (steps < reach && a > 0 && !gap(a)) { a--; steps++ }
            var b = i
            steps = 0
            while (steps < reach && b + 1 < count && !gap(b + 1)) { b++; steps++ }
            val ka = (head + a) % capacity
            val kb = (head + b) % capacity
            out[0] = x[kb] - x[ka]; out[1] = y[kb] - y[ka]; out[2] = z[kb] - z[ka]
            unit(out)
        }
    }

    /** Draw mode's strokes. */
    val strokes = Ring(STROKE_MAX)

    private var strokeGap = true
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var wasHeld = false
    private var lastKind = LaserPaint.PAINT
    private var beamX = 0f
    private var beamY = 0f
    private var beamZ = 0f
    private var beamMs = Long.MIN_VALUE
    // Line smoothing: the steadied point the paint follows, and the raw beam before.
    private val steady = FloatArray(3)
    private val rawLast = FloatArray(3)
    private val velocity = FloatArray(3)
    private var steadyMs = 0L

    /**
     * The way the beam last moved (a unit direction in the shown frame, x right, y up, z toward the
     * viewer; zero before it ever moved): it keeps the last way when the beam stops. The crosshair's colour.
     */
    val moving = FloatArray(3)

    /** Goes up when the strokes are cleared. */
    var version = 0L
        private set

    /** The pose version last fed (the UI feeds once per new pose). */
    var fedVersion = Long.MIN_VALUE

    /**
     * The beam now points at ([x], [y], [z]) (any length) at [nowMs]. While a ratchet is [held] it
     * doesn't paint (the pose eases back then, which isn't aiming); with [clearOnPress] pressing
     * one clears the strokes. With [draw] it paints; turning Draw off clears the strokes. While
     * [fire] (the display's Fire display output is sent) it paints in the Fire colours, [fireBrushDeg] wide; else
     * while [aim] (its Aim display output) in the Aim colours, [aimBrushDeg] wide; with [fireOnly] only then.
     * [moving] follows the beam, evened out over [SMOOTH_MS] with [smooth]. With [lineSmoothing]
     * (0 off .. 1 strong) the paint follows a steadied beam ([steadyToward]). With [draw] each new
     * point paints [paint] from the point before, [brushDeg] wide, its edge [soft], with [wayColors] (as
     * [directionColor]'s) also keeping each texel's way colour, the map [mapWidth] texels round. With [snakeDeg] > 0 (Snake) the
     * strokes are kept (even without Draw) only for the newest that many degrees: the snake's line.
     */
    fun add(
        x: Float, y: Float, z: Float, nowMs: Long, draw: Boolean, held: Boolean, clearOnPress: Boolean = false,
        fire: Boolean = false, fireOnly: Boolean = false, smooth: Boolean = true, lineSmoothing: Float = 0f,
        brushDeg: Float = 0f, snakeDeg: Float = 0f, wayColors: LongArray? = null, mapWidth: Int = LaserPaint.WIDTH, soft: Float = 0.5f,
        aim: Boolean = false, fireBrushDeg: Float = brushDeg, aimBrushDeg: Float = brushDeg,
    ) {
        val pressed = clearOnPress && held && !wasHeld
        wasHeld = held
        val snake = snakeDeg > 0f
        if (!draw && paintUsed) clearPaint() // Draw off wipes the paint (a snake goes on)
        if ((!(draw || snake) || pressed) && (strokes.count > 0 || paintUsed)) clear()
        val l = sqrt(x * x + y * y + z * z)
        if (l > 1e-6f && !l.isNaN()) follow(x / l, y / l, z / l, nowMs, smooth)
        if (!(draw || snake) || l < 1e-6f || held || l.isNaN() || (fireOnly && !fire && !aim)) {
            strokeGap = true
            return
        }
        var dx = x / l
        var dy = y / l
        var dz = z / l
        if (lineSmoothing > 0f) {
            steadyToward(dx, dy, dz, nowMs, lineSmoothing)
            dx = steady[0]; dy = steady[1]; dz = steady[2]
        }
        val kind = if (fire) LaserPaint.FIRE else if (aim) LaserPaint.AIM else LaserPaint.PAINT
        val brush = when (kind) { LaserPaint.FIRE -> fireBrushDeg; LaserPaint.AIM -> aimBrushDeg; else -> brushDeg }
        // A new point once the beam moved a little (or the firing or aiming started or stopped): a still beam adds nothing.
        if (!strokeGap && kind == lastKind && lastX * dx + lastY * dy + lastZ * dz > STROKE_STEP_COS) return
        strokes.add(dx, dy, dz, nowMs, strokeGap, kind)
        if (draw && brush > 0f) stampNewest(brush / 2f, kind, wayColors, if (smooth) SMOOTH_REACH else 0, mapWidth, soft)
        if (snake) strokes.cut(snakeDeg)
        strokeGap = false
        lastKind = kind
        lastX = dx; lastY = dy; lastZ = dz
    }

    /**
     * Moves the steadied point toward the beam at unit ([x], [y], [z]) at [nowMs], as a drawing
     * app's stabiliser: it eases in over up to [LINE_SMOOTH_MS] × [strength] when the beam moves
     * slowly, much faster when it moves fast ([LINE_SMOOTH_SPEED]); a new line starts on the beam.
     */
    private fun steadyToward(x: Float, y: Float, z: Float, nowMs: Long, strength: Float) {
        if (strokeGap) {
            steady[0] = x; steady[1] = y; steady[2] = z
            velocity.fill(0f)
        } else {
            val dt = (nowMs - steadyMs).coerceIn(1L, 200L).toFloat()
            // The beam's speed, evened out over [SPEED_MS] so a shake back and forth cancels (radians/ms).
            val kv = 1f - kotlin.math.exp(-dt / SPEED_MS)
            velocity[0] += ((x - rawLast[0]) / dt - velocity[0]) * kv
            velocity[1] += ((y - rawLast[1]) / dt - velocity[1]) * kv
            velocity[2] += ((z - rawLast[2]) / dt - velocity[2]) * kv
            val degPerS = Math.toDegrees(sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]).toDouble()).toFloat() * 1000f
            val tau = strength.coerceIn(0f, 1f) * LINE_SMOOTH_MS / (1f + degPerS / LINE_SMOOTH_SPEED)
            val k = if (tau < 0.5f) 1f else 1f - kotlin.math.exp(-dt / tau)
            steady[0] += (x - steady[0]) * k
            steady[1] += (y - steady[1]) * k
            steady[2] += (z - steady[2]) * k
            unit(steady)
        }
        rawLast[0] = x; rawLast[1] = y; rawLast[2] = z
        steadyMs = nowMs
    }

    // Something is painted on the map (since the last clear).
    private var paintUsed = false

    private fun clearPaint() {
        paint?.clear()
        paintUsed = false
        hitCount = 0
        version++
    }

    /** Draw each shot's hits: x, y, z (a unit direction, as the strokes), half width (radians) each. The newest [HIT_MAX] are kept. */
    val hits = FloatArray(HIT_MAX * 4)

    /** How many [hits] there are. */
    var hitCount = 0
        private set
    private var hitNext = 0

    /** The last shot ([CrosshairKick.State.shots]) marked. */
    var hitShots = 0L

    /** Marks a hit at ([x], [y], [z]) (any length), [halfRad] radians round. */
    fun addHit(x: Float, y: Float, z: Float, halfRad: Float) {
        val l = sqrt(x * x + y * y + z * z)
        if (l < 1e-6f || l.isNaN()) return
        val i = hitNext * 4
        hits[i] = x / l; hits[i + 1] = y / l; hits[i + 2] = z / l; hits[i + 3] = halfRad
        hitNext = (hitNext + 1) % HIT_MAX
        if (hitCount < HIT_MAX) hitCount++
        version++
    }

    /** The paint map, made at the first painted point. */
    var paint: LaserPaint? = null
        private set

    private val wayDir = FloatArray(3)
    private val pa = FloatArray(3)
    private val pb = FloatArray(3)

    /** Paints the newest stroke point into [paint], from the point before it unless it starts a line. */
    private fun stampNewest(halfDeg: Float, kind: Int, wayColors: LongArray?, reach: Int, mapWidth: Int, soft: Float) {
        // A new map when the detail changed (the paint so far goes).
        val p = paint?.takeIf { it.width == mapWidth } ?: LaserPaint(mapWidth, mapWidth / 2).also { paint = it }
        val n = strokes.count
        val b = n - 1
        val a = if (strokes.gap(b) || n < 2) b else b - 1
        strokes.point(a, pa)
        strokes.point(b, pb)
        val rgb = if (wayColors == null) null else {
            if (a == b) wayDir.fill(0f) else strokes.direction(b, reach, wayDir)
            (directionColor(wayDir, wayColors) and 0xFFFFFF).toInt()
        }
        if (a == b) p.newLine()
        p.stamp(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], strokes.along(b), halfDeg, kind, soft, rgb)
        paintUsed = true
    }

    /** Turns [moving] toward the way the beam went to ([x], [y], [z]), a unit direction, at [nowMs]. */
    private fun follow(x: Float, y: Float, z: Float, nowMs: Long, smooth: Boolean) {
        if (beamMs == Long.MIN_VALUE) {
            beamX = x; beamY = y; beamZ = z; beamMs = nowMs
            return
        }
        val d = floatArrayOf(x - beamX, y - beamY, z - beamZ)
        // Barely moved (a still phone's noise): keep the last way.
        if (d[0] * d[0] + d[1] * d[1] + d[2] * d[2] < MOVE_MIN * MOVE_MIN) return
        unit(d)
        val k = if (!smooth || moving.all { it == 0f }) 1f
        else (1f - kotlin.math.exp(-(nowMs - beamMs).coerceAtLeast(1L) / SMOOTH_MS.toFloat())).coerceIn(0f, 1f)
        for (i in 0..2) moving[i] += (d[i] - moving[i]) * k
        unit(moving)
        beamX = x; beamY = y; beamZ = z; beamMs = nowMs
    }

    /** Clears the strokes. */
    fun clear() {
        strokes.clear()
        paint?.clear()
        paintUsed = false
        hitCount = 0
        hitNext = 0
        strokeGap = true
        version++
    }

    companion object {
        const val STROKE_MAX = 4096

        /** The most Draw each shot hits kept (the oldest go). */
        const val HIT_MAX = 2048

        /** Draw each shot's hit looks ([com.oleroma.gyrophone.core.model.GyroDisplay.laserHitLook]). */
        val HIT_LOOKS = listOf("Dot", "Ring", "Cross", "Bullet hole")

        /**
         * Where a shot lands ([out], unit) off [beam] by ([ox], [oy]) radians to the right and up of pose [m]
         * (the crosshair's right and up, [rollTick]).
         */
        fun shotPoint(m: FloatArray, beam: FloatArray, ox: Float, oy: Float, out: FloatArray) {
            val bl = sqrt(beam[0] * beam[0] + beam[1] * beam[1] + beam[2] * beam[2]).coerceAtLeast(1e-6f)
            val b = floatArrayOf(beam[0] / bl, beam[1] / bl, beam[2] / bl)
            val v = FloatArray(3)
            if (!rollTick(m, b, v)) {
                if (!square(floatArrayOf(0f, 0f, -1f), b, v)) square(floatArrayOf(1f, 0f, 0f), b, v)
            }
            val u = floatArrayOf(v[1] * b[2] - v[2] * b[1], v[2] * b[0] - v[0] * b[2], v[0] * b[1] - v[1] * b[0])
            val tx = kotlin.math.tan(ox)
            val ty = kotlin.math.tan(oy)
            for (k in 0..2) out[k] = b[k] + tx * u[k] + ty * v[k]
            unit(out)
        }

        /**
         * Turns pose [m] (row-major, phone to shown) and its [beam] by the turn that takes the beam onto [to]
         * (unit), [k] (0 .. 1) of the way: a gun or crosshair kicking toward where a shot lands.
         */
        fun turnToward(m: FloatArray, beam: FloatArray, to: FloatArray, k: Float) {
            if (k == 0f) return
            val bl = sqrt(beam[0] * beam[0] + beam[1] * beam[1] + beam[2] * beam[2]).coerceAtLeast(1e-6f)
            val bx = beam[0] / bl; val by = beam[1] / bl; val bz = beam[2] / bl
            var ax = by * to[2] - bz * to[1]
            var ay = bz * to[0] - bx * to[2]
            var az = bx * to[1] - by * to[0]
            val sinA = sqrt(ax * ax + ay * ay + az * az)
            if (sinA < 1e-7f) return
            ax /= sinA; ay /= sinA; az /= sinA
            val angle = kotlin.math.atan2(sinA, bx * to[0] + by * to[1] + bz * to[2]) * k
            val c = cos(angle); val s = sin(angle); val t = 1f - c
            val r = floatArrayOf(
                t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
                t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
                t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c,
            )
            val n = FloatArray(9)
            for (i in 0..2) for (j in 0..2) n[i * 3 + j] = r[i * 3] * m[j] + r[i * 3 + 1] * m[3 + j] + r[i * 3 + 2] * m[6 + j]
            n.copyInto(m)
            val x = beam[0]; val y = beam[1]; val z = beam[2]
            for (i in 0..2) beam[i] = r[i * 3] * x + r[i * 3 + 1] * y + r[i * 3 + 2] * z
        }

        /** Points each side a stroke's colour looks over with smoothing ([Ring.direction]): ±2°. */
        const val SMOOTH_REACH = 4

        /** Line smoothing at full strength: how long a slow move's paint takes to catch up (ms, 63%). */
        const val LINE_SMOOTH_MS = 150f

        /** Line smoothing eases off with speed: at this many degrees a second it catches up twice as fast. */
        const val LINE_SMOOTH_SPEED = 60f

        // How long line smoothing's speed reading evens out over (ms).
        private const val SPEED_MS = 50f

        /** How long the crosshair's colour takes to follow a new way with smoothing (ms, 63% of the way). */
        const val SMOOTH_MS = 60

        // Under this the beam hasn't moved (a radian, about 0.1°).
        private const val MOVE_MIN = 0.002f

        /** [v] made unit length (left at zero when it is). */
        fun unit(v: FloatArray) {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (l < 1e-9f || l.isNaN()) v.fill(0f) else for (i in 0..2) v[i] /= l
        }

        /**
         * The colour (ARGB) of moving the way of unit direction [d] (x right, y up, z toward the
         * viewer), blended as a normal map does: [colors] holds the colour of each straight way
         * (right, left, up, down, toward, away; ARGB); the middle is their average, and each part
         * of [d] takes its share of the way from the middle to its colour. With the normal-map
         * colours that is 0.5 + 0.5·d per red, green, blue. Not moving: the middle.
         */
        fun directionColor(d: FloatArray, colors: LongArray): Long {
            var argb = 0L
            for (ch in 0..3) {
                val shift = ch * 8
                fun c(i: Int) = (colors[i] shr shift and 0xFF).toFloat()
                var mid = 0f
                for (i in 0..5) mid += c(i)
                mid /= 6f
                var v = mid
                for (a in 0..2) {
                    val p = d[a]
                    v += kotlin.math.abs(p) * (c(if (p >= 0f) a * 2 else a * 2 + 1) - mid)
                }
                argb = argb or ((v + 0.5f).toInt().coerceIn(0, 255).toLong() shl shift)
            }
            return argb
        }
        const val STROKE_STEP_DEG = 0.5f

        /** Snake: its curve is cut into steps of at most this many degrees. */
        const val CURVE_STEP_DEG = 0.5f

        /** Snake: at most this many steps between two stroke points (a jump of 32° or more is cut coarser). */
        const val CURVE_MAX_STEPS = 64

        /**
         * A smooth curve through unit directions [p1] → [p2] at [t] (0..1) into [out] (unit): a centripetal
         * Catmull-Rom spline with neighbours [p0] (before [p1]) and [p3] (after [p2]), which never loops or
         * overshoots however unevenly the points are spaced. A line's ends pass the end point itself as its
         * missing neighbour. Each point holds x, y, z from its index ([a0] .. [a3]).
         */
        fun curve(
            p0: FloatArray, a0: Int, p1: FloatArray, a1: Int, p2: FloatArray, a2: Int, p3: FloatArray, a3: Int,
            t: Float, out: FloatArray,
        ) {
            fun knot(pa: FloatArray, ia: Int, pb: FloatArray, ib: Int): Float {
                val dx = pb[ib] - pa[ia]
                val dy = pb[ib + 1] - pa[ia + 1]
                val dz = pb[ib + 2] - pa[ia + 2]
                return sqrt(sqrt(dx * dx + dy * dy + dz * dz)) // centripetal: the chord's square root
            }
            val d1 = knot(p1, a1, p2, a2).coerceAtLeast(1e-6f)
            val d0 = knot(p0, a0, p1, a1).let { if (it < 1e-6f) d1 else it }
            val d2 = knot(p2, a2, p3, a3).let { if (it < 1e-6f) d1 else it }
            // Hermite form: the tangents at p1 and p2 scaled to the middle span.
            for (k in 0..2) {
                val q0 = p0[a0 + k]; val q1 = p1[a1 + k]; val q2 = p2[a2 + k]; val q3 = p3[a3 + k]
                val m1 = d1 * ((q1 - q0) / d0 - (q2 - q0) / (d0 + d1) + (q2 - q1) / d1)
                val m2 = d1 * ((q2 - q1) / d1 - (q3 - q1) / (d1 + d2) + (q3 - q2) / d2)
                val t2 = t * t
                val t3 = t2 * t
                out[k] = (2f * t3 - 3f * t2 + 1f) * q1 + (t3 - 2f * t2 + t) * m1 + (-2f * t3 + 3f * t2) * q2 + (t3 - t2) * m2
            }
            unit(out)
        }

        private val STROKE_STEP_COS = cos(Math.toRadians(STROKE_STEP_DEG.toDouble())).toFloat()

        /** Perspective: the eye's distance from the centre, in sphere radii (as the 3D phone's). */
        const val CAMERA_DISTANCE = 6f

        /** Perspective: how much bigger a picture point at depth [z] (toward the viewer +, sphere radii) shows. */
        fun perspective(z: Float): Float = CAMERA_DISTANCE / (CAMERA_DISTANCE - z.coerceAtMost(CAMERA_DISTANCE - 0.5f))

        /** The assault rifle's muzzle tip in its own frame (x right, y up, z toward the stock). */
        val MUZZLE = floatArrayOf(0f, 0f, -0.93f)

        /** Aiming ([rifle]'s aim 1) the gun is shouldered: pulled back toward the player this far. */
        const val AIM_PULL = 0.12f

        /** With Lowered when idle ([rifle]'s lower 1) the gun rests pitched this many degrees down. */
        const val LOWER_DEG = 15f

        /**
         * A point (x, y, z) of [gun]'s own frame at aim [aim] (0 .. 1), kick [kick] (0 rest .. 1 full, a little under
         * 0 as it springs back) and lowered by [lower] (0 .. 1), into [out] at [at]: shouldered by [AIM_PULL], kicked
         * back by its recoil's back and its muzzle up by its climb, and pitched down by [LOWER_DEG], both about its
         * pivot (the shoulder, or the wrist for a pistol). [dir]: a direction (a face's normal), turned only.
         */
        fun rifleAt(
            x: Float, y: Float, z: Float, aim: Float, kick: Float, out: FloatArray, at: Int = 0, dir: Boolean = false,
            gun: Guns.Gun = Guns.AR, lower: Float = 0f,
        ) {
            val r = gun.recoil
            val a = Math.toRadians((r.climbDeg * kick - LOWER_DEG * lower.coerceIn(0f, 1f)).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            // Scaled to fit (Guns.Gun.fit): a direction stays as it is.
            val f = if (dir) 1f else gun.fit
            val pz = if (dir) 0f else gun.fitZ(gun.pivotZ)
            val dz = (if (dir) z else gun.fitZ(z)) - pz
            val yf = y * f
            out[at] = x * f
            out[at + 1] = yf * c - dz * s
            out[at + 2] = pz + yf * s + dz * c + (if (dir) 0f else AIM_PULL * aim.coerceIn(0f, 1f) + r.back * kick)
        }

        /** [gun]'s muzzle tip at aim [aim] (0 .. 1), kick [kick] and lowered by [lower], in its own frame, into [out]. */
        fun muzzle(aim: Float, out: FloatArray, kick: Float = 0f, gun: Guns.Gun = Guns.AR, lower: Float = 0f) =
            rifleAt(0f, 0f, gun.muzzleZ, aim, kick, out, gun = gun, lower = lower)

        /** [gun]'s direction [dx], [dy], [dz] of its own frame at kick [kick] and lowered by [lower], into [out]. */
        fun gunDir(dx: Float, dy: Float, dz: Float, out: FloatArray, kick: Float = 0f, gun: Guns.Gun = Guns.AR, lower: Float = 0f) =
            rifleAt(dx, dy, dz, 0f, kick, out, dir = true, gun = gun, lower = lower)

        /** How far round from straight ahead (into the screen) shown-frame direction z is: 0 ahead .. 1 straight behind. */
        fun behind(z: Float): Float = ((1f + z) / 2f).coerceIn(0f, 1f)

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
            // One piece of a circle as x, y pairs: a plain float buffer, so no number is boxed.
            val line = FloatArray((GRID_STEPS + 3) * 2)
            var size = 0
            fun add(x: Float, y: Float) {
                line[size++] = x
                line[size++] = y
            }
            fun circle(point: (Double, FloatArray) -> Unit) {
                var lastFront = false
                var lx = 0f
                var ly = 0f
                var lz = 0f
                fun flush() {
                    if (size >= 4) (if (lastFront) front else back).add(line.copyOf(size))
                    size = 0
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
                        add(rx, ry)
                        flush()
                        add(rx, ry)
                    }
                    add(q[0], q[1])
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

        /** A round scope's sides: this many. */
        private const val SCOPE_SIDES = 12

        /** One face of the gun in the picture: its corners (x, y pairs, unit radius, any number) and how lit it is (0..1). */
        class Face(val xy: FloatArray, val light: Float, val depth: Float)

        /**
         * [gun] turned by pose [m], seen as [view] sees it ([axon], [turned]): the faces toward the viewer, farthest
         * first, lit from above left. Aiming ([aim] 0 .. 1, eased) it is shouldered ([AIM_PULL]) and, with [scope], its
         * round scope grows onto it; firing it kicks ([kick], [rifleAt]); [lower] pitches it down ([LOWER_DEG]).
         */
        fun rifle(
            m: FloatArray, axon: Boolean, turned: FloatArray?, persp: Boolean = false, aim: Float = 0f, kick: Float = 0f,
            gun: Guns.Gun = Guns.AR, lower: Float = 0f, scope: Boolean = true,
        ): List<Face> {
            val faces = ArrayList<Face>(64)
            val q = FloatArray(3)
            val v = FloatArray(3)
            val lx = -0.35f
            val ly = 0.6f
            val lz = 0.72f
            val a = aim.coerceIn(0f, 1f)
            // One face: its corners in the gun's own frame (x, y, z each), and its normal.
            fun face(c: FloatArray, nx: Float, ny: Float, nz: Float) {
                rifleAt(nx, ny, nz, a, kick, q, dir = true, gun = gun, lower = lower)
                view(m[0] * q[0] + m[1] * q[1] + m[2] * q[2], m[3] * q[0] + m[4] * q[1] + m[5] * q[2], m[6] * q[0] + m[7] * q[1] + m[8] * q[2], v, 0, axon, turned)
                if (v[2] <= 1e-4f) return
                val light = (0.45f + 0.55f * (v[0] * lx + v[1] * ly + v[2] * lz).coerceAtLeast(0f)).coerceIn(0f, 1f)
                val n = c.size / 3
                val xy = FloatArray(n * 2)
                var depth = 0f
                for (i in 0 until n) {
                    rifleAt(c[i * 3], c[i * 3 + 1], c[i * 3 + 2], a, kick, q, gun = gun, lower = lower)
                    view(m[0] * q[0] + m[1] * q[1] + m[2] * q[2], m[3] * q[0] + m[4] * q[1] + m[5] * q[2], m[6] * q[0] + m[7] * q[1] + m[8] * q[2], v, 0, axon, turned)
                    val f = if (persp) perspective(v[2]) else 1f
                    xy[i * 2] = v[0] * f
                    xy[i * 2 + 1] = v[1] * f
                    depth += v[2]
                }
                faces.add(Face(xy, light, depth / n))
            }
            fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float) {
                for (f in 0 until 6) {
                    val axis = f / 2
                    val sign = if (f % 2 == 0) -1f else 1f
                    // The face's corners, in order round it.
                    val o1 = (axis + 1) % 3
                    val o2 = (axis + 2) % 3
                    val c = FloatArray(12)
                    val us = floatArrayOf(-1f, 1f, 1f, -1f)
                    val ws = floatArrayOf(-1f, -1f, 1f, 1f)
                    val h = floatArrayOf(hx, hy, hz)
                    val ctr = floatArrayOf(cx, cy, cz)
                    for (k in 0 until 4) {
                        for (d in 0..2) c[k * 3 + d] = ctr[d]
                        c[k * 3 + axis] += sign * h[axis]
                        c[k * 3 + o1] += us[k] * h[o1]
                        c[k * 3 + o2] += ws[k] * h[o2]
                    }
                    face(c, if (axis == 0) sign else 0f, if (axis == 1) sign else 0f, if (axis == 2) sign else 0f)
                }
            }
            // A round tube along z at height y, from z0 (front) to z1, radius r: its sides and both ends.
            fun tube(y: Float, z0: Float, z1: Float, r: Float) {
                val k = SCOPE_SIDES
                for (i in 0 until k) {
                    val t0 = 2f * PI.toFloat() * i / k
                    val t1 = 2f * PI.toFloat() * (i + 1) / k
                    val tm = (t0 + t1) / 2f
                    face(
                        floatArrayOf(
                            r * cos(t0), y + r * sin(t0), z0, r * cos(t1), y + r * sin(t1), z0,
                            r * cos(t1), y + r * sin(t1), z1, r * cos(t0), y + r * sin(t0), z1,
                        ),
                        cos(tm), sin(tm), 0f,
                    )
                }
                for ((z, nz) in arrayOf(z0 to -1f, z1 to 1f)) {
                    face(FloatArray(k * 3) { j -> val t = 2f * PI.toFloat() * (j / 3) / k; when (j % 3) { 0 -> r * cos(t); 1 -> y + r * sin(t); else -> z } }, 0f, 0f, nz)
                }
            }
            val b = gun.boxes
            for (o in b.indices step 6) box(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5])
            val sc = gun.scope
            if (scope && sc != null && a > 0.02f) {
                // It grows in about its middle.
                val mid = (sc.front + sc.back) / 2f
                fun zz(z: Float) = mid + (z - mid) * a
                val rail = b.indices.step(6).maxOfOrNull { b[it + 1] + b[it + 4] } ?: 0f
                // It sits on its mounts, clear of the gun's top.
                val sy = maxOf(sc.y, rail + sc.radius + 0.012f)
                val base = sy - sc.radius
                for (mz in floatArrayOf(sc.front + 0.06f, sc.back - 0.08f)) {
                    val h = ((base - rail) / 2f).coerceAtLeast(0.004f) * a
                    box(0f, rail + h, zz(mz), 0.012f * a, h, 0.014f)
                }
                tube(sy, zz(sc.front + 0.04f), zz(sc.back - 0.03f), sc.radius * a)
                tube(sy, zz(sc.front - 0.03f), zz(sc.front + 0.04f), sc.bell * a)
                tube(sy, zz(sc.back - 0.03f), zz(sc.back), sc.radius * 1.25f * a)
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

        /**
         * Pose matrix [m] (row-major, changed) with the laser facing direction [faces] of it: one of
         * [FACES] (0 −Z into the screen at rest, 1 +Z, 2 +X, 3 −X, 4 +Y, 5 −Y). m · F, F turning −Z
         * onto that direction.
         */
        fun faces(m: FloatArray, faces: Int) {
            val f = FACE_TURNS.getOrNull(faces) ?: return
            val r = FloatArray(9)
            for (i in 0..2) for (j in 0..2) r[i * 3 + j] = m[i * 3] * f[j] + m[i * 3 + 1] * f[3 + j] + m[i * 3 + 2] * f[6 + j]
            r.copyInto(m)
        }

        /** The directions the laser can face (a trackball's), by their names. */
        val FACES = listOf("−Z", "+Z", "+X", "−X", "+Y", "−Y")

        private val FACE_TURNS = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f),
        )

        private val AIM_AXIS = intArrayOf(0, 2, 1)
        private val AIM_SIGN = floatArrayOf(1f, 1f, -1f)

        /** The beam's direction for pose matrix [m] (row-major, phone frame to shown): out of the phone's back. */
        fun beam(m: FloatArray, out: FloatArray) {
            out[0] = -m[2]; out[1] = -m[5]; out[2] = -m[8]
        }

        /** The gimbal rings' radii (the sphere's is 1): yaw outside, then pitch, then roll. */
        val GIMBAL_RADII = floatArrayOf(0.9f, 0.8f, 0.7f)

        /**
         * Gimbal ring [i] (0 yaw, 1 pitch, 2 roll) for the laser's pose [m] (row-major) as points (x, y, z…): one
         * rigid set turning with the laser's axes, each round its own axis (yaw round its up Y, pitch round its
         * right X, roll round the beam Z), so each ring is the path the other axes sweep turning about it.
         * Written into [out] (reused from frame to frame) and returned.
         */
        fun gimbalRing(i: Int, m: FloatArray, steps: Int = 64, out: FloatArray = FloatArray((steps + 1) * 3)): FloatArray {
            val rho = GIMBAL_RADII[i]
            for (j in 0..steps) {
                val t = j * 2f * Math.PI.toFloat() / steps
                val p = rho * cos(t)
                val q = rho * sin(t)
                // Round its axis: yaw XZ (round Y), pitch YZ (round X), roll XY (round Z).
                val x: Float; val y: Float; val z: Float
                when (i) {
                    0 -> { x = p; y = 0f; z = q }
                    1 -> { x = 0f; y = p; z = q }
                    else -> { x = p; y = q; z = 0f }
                }
                for (k in 0..2) out[j * 3 + k] = m[k * 3] * x + m[k * 3 + 1] * y + m[k * 3 + 2] * z
            }
            return out
        }

        /** The crosshair shapes ([com.oleroma.gyrophone.core.model.GyroDisplay.crosshairShape]). */
        val CROSSHAIRS = listOf("Cross", "Cross with gap", "Circle", "Circle and cross", "Dot", "T")

        /** A crosshair on the sphere: [lines] as polylines of points (x, y, z, x, y, z…); [dot] a closed ring to fill, or null. */
        class Crosshair(val lines: List<FloatArray>, val dot: FloatArray?)

        /**
         * Crosshair [shape] ([CROSSHAIRS]) lying on the sphere where [beam] lands, facing its centre:
         * arms [size] radians long from a [gap] radian gap, a centre [dot]. Its up is the pose's up
         * ([m]), so it turns about the beam only as the pose does: a trackball's roll axis as mapped
         * (locked, inverted), the phone's own roll when it turns with the phone. The dot is sized from [dotSize]
         * (by default [size]), so a kick can grow the lines alone.
         */
        fun crosshair(m: FloatArray, beam: FloatArray, shape: Int, size: Float, gap: Float, dot: Boolean, dotSize: Float = size): Crosshair {
            val v = FloatArray(3)
            if (!rollTick(m, beam, v)) {
                // Straight along the pose's up: any way across.
                if (!square(floatArrayOf(0f, 0f, -1f), beam, v)) square(floatArrayOf(1f, 0f, 0f), beam, v)
            }
            // Right: up × the outward normal (the beam).
            val u = floatArrayOf(v[1] * beam[2] - v[2] * beam[1], v[2] * beam[0] - v[0] * beam[2], v[0] * beam[1] - v[1] * beam[0])
            fun at(out: FloatArray, i: Int, a: Float, b: Float) {
                for (k in 0..2) out[i * 3 + k] = beam[k] + a * u[k] + b * v[k]
            }
            fun line(a0: Float, b0: Float, a1: Float, b1: Float) = FloatArray(6).also { at(it, 0, a0, b0); at(it, 1, a1, b1) }
            fun ring(rad: Float): FloatArray {
                val n = 32
                val out = FloatArray((n + 1) * 3)
                for (i in 0..n) {
                    val t = i * 2f * Math.PI.toFloat() / n
                    at(out, i, rad * kotlin.math.cos(t), rad * kotlin.math.sin(t))
                }
                return out
            }
            val lines = ArrayList<FloatArray>(6)
            val g = gap.coerceIn(0f, size * 0.9f)
            fun arms(up: Boolean) {
                lines += line(g, 0f, size, 0f)
                lines += line(-g, 0f, -size, 0f)
                lines += line(0f, -g, 0f, -size)
                if (up) lines += line(0f, g, 0f, size)
            }
            when (shape) {
                0 -> {
                    lines += line(-size, 0f, size, 0f)
                    lines += line(0f, -size, 0f, size)
                }
                2 -> lines += ring(size * 0.6f)
                3 -> {
                    lines += ring(size * 0.6f)
                    arms(true)
                }
                4 -> {}
                5 -> arms(false)
                else -> arms(true)
            }
            val dotRad = if (shape == 4) size * 0.35f else if (dot) dotSize * 0.12f else 0f
            return Crosshair(lines, if (dotRad > 0f) ring(dotRad) else null)
        }

        /** [d] squared against [beam], normalized, into [out]; false when they are (nearly) the same way. */
        private fun square(d: FloatArray, beam: FloatArray, out: FloatArray): Boolean {
            val k = d[0] * beam[0] + d[1] * beam[1] + d[2] * beam[2]
            val x = d[0] - k * beam[0]
            val y = d[1] - k * beam[1]
            val z = d[2] - k * beam[2]
            val l = sqrt(x * x + y * y + z * z)
            if (l < 1e-3f) return false
            out[0] = x / l; out[1] = y / l; out[2] = z / l
            return true
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
