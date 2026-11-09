package com.oleroma.gyrophone.core.engine

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draw mode's paint as a map of the whole sphere's inside, [width] × [height] texels by longitude
 * and latitude: texel (i, j) is the direction at longitude (i + ½)/width · 360° − 180° (0 straight
 * ahead, into the screen) and latitude 90° − (j + ½)/height · 180°, as [LaserTrace.dir] makes it, in
 * the shown frame. The beam's brush stamps it; the UI's shader reads it for every pixel of the
 * sphere, so the paint follows the sphere's real shape, depth and perspective, and costs the same
 * however much is painted. Same as the companion's LaserPaint.
 *
 * Paint builds up as real paint does: each line lays a soft-edged amount of paint over what is
 * there ("over": a + b·(1 − a)), its colour mixing in by its share, so crossing lines blend. Within
 * one line the brush's stamps overlap; there a texel takes the line's strongest stamp, not their
 * sum, so a line is even along its length.
 *
 * The bytes the GPU reads, one per texel each (alpha-only bitmaps), all premultiplied by the
 * amount so the GPU can blend between texels: [amount] (0 .. 255), [fire] (the amount that is
 * shots, Fire colours) and [glow] (a wide faint halo, built up the same way). With way colours kept,
 * [way] holds each texel's colour times its amount (RGB, alpha 255).
 */
class LaserPaint(val width: Int = WIDTH, val height: Int = HEIGHT) {
    private val n = width * height
    val amount = ByteArray(n)
    val fire = ByteArray(n)
    val glow = ByteArray(n)

    /** Each texel's way colour times its amount (RGB, alpha 255), made at the first stamp that keeps one. */
    var way: IntArray? = null
        private set

    /** Goes up at each change. */
    var version = 0L
        private set

    // The current line's own part of each texel it reached (its strongest stamp there), and which line
    // and how far along it that was: a later stamp of the same line nearby replaces it rather than adding.
    private val lineAmount = ByteArray(n)
    private val lineGlow = ByteArray(n)
    private val lineTag = ShortArray(n)
    private val lineAlong = ShortArray(n)
    private var line: Short = 0

    private val sinLat = FloatArray(height) { sin(latOf(it)).toFloat() }
    private val cosLat = FloatArray(height) { cos(latOf(it)).toFloat() }
    private val sinLon = FloatArray(width) { sin(lonOf(it)).toFloat() }
    private val cosLon = FloatArray(width) { cos(lonOf(it)).toFloat() }

    private fun latOf(j: Int) = PI / 2 - (j + 0.5) / height * PI
    private fun lonOf(i: Int) = (i + 0.5) / width * 2 * PI - PI

    fun clear() {
        amount.fill(0)
        fire.fill(0)
        glow.fill(0)
        way?.fill(0)
        lineAmount.fill(0)
        lineGlow.fill(0)
        lineTag.fill(0)
        line = 0
        version++
    }

    /** The next stamps start a new line: they lay paint over everything there. */
    fun newLine() {
        line = (line + 1).toShort()
        if (line == 0.toShort()) line = 1 // 0 is "no line"
    }

    /**
     * Paints the line from unit direction ([ax], [ay], [az]) to ([bx], [by], [bz]) (the same point for
     * a dot), [alongDeg] degrees along its line at its end, [halfDeg] degrees each side of its middle,
     * its edge [soft] (0 sharp .. 1 soft); [fired] while firing; [wayRgb] its way colour (RGB, or null).
     */
    fun stamp(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, alongDeg: Double, halfDeg: Float,
        fired: Boolean, soft: Float = 0.5f, wayRgb: Int? = null,
    ) {
        if (line == 0.toShort()) newLine()
        if (wayRgb != null && way == null) way = IntArray(n)
        val half = Math.toRadians(halfDeg.toDouble()).toFloat().coerceAtLeast(1e-4f)
        val brush = Brush(half, soft.coerceIn(0f, 1f))
        // Long hops in pieces of up to PIECE_DEG, so each piece's straight chord stays on the sphere.
        val hop = acos((ax * bx + ay * by + az * bz).toDouble().coerceIn(-1.0, 1.0))
        val pieces = maxOf(1, kotlin.math.ceil(Math.toDegrees(hop) / PIECE_DEG).toInt())
        val a = FloatArray(3)
        val b = FloatArray(3)
        val along = (Math.round(alongDeg / ALONG_UNIT) and 0xFFFF).toInt()
        for (k in 0 until pieces) {
            slerp(ax, ay, az, bx, by, bz, hop, k / pieces.toFloat(), a)
            slerp(ax, ay, az, bx, by, bz, hop, (k + 1) / pieces.toFloat(), b)
            piece(a, b, brush, fired, wayRgb, along)
        }
        version++
    }

    /** The brush: paint to [half] radians from the middle with an edge [soft], a glow out to [REACH] half widths. */
    private class Brush(val half: Float, soft: Float) {
        val edge = half * (0.08f + 0.72f * soft)
        val reach = maxOf(half * REACH, half + edge)

        /** The paint's amount (0 .. 1) [d] radians from the middle. */
        fun paint(d: Float): Float {
            val t = ((d - (half - edge)) / (2f * edge)).coerceIn(0f, 1f)
            return 1f - t * t * (3f - 2f * t)
        }

        /** The glow's amount (0 .. 1) [d] radians from the middle. */
        fun glow(d: Float): Float {
            val t = (1f - d / (half * REACH)).coerceIn(0f, 1f)
            return t * t
        }
    }

    private fun piece(a: FloatArray, b: FloatArray, brush: Brush, fired: Boolean, wayRgb: Int?, along: Int) {
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val abz = b[2] - a[2]
        val ab2 = abx * abx + aby * aby + abz * abz
        // The cap round the piece's middle holding everything it can reach.
        var cx = a[0] + b[0]
        var cy = a[1] + b[1]
        var cz = a[2] + b[2]
        val cl = sqrt(cx * cx + cy * cy + cz * cz)
        if (cl < 1e-6f) return
        cx /= cl; cy /= cl; cz /= cl
        val reach = brush.reach.toDouble()
        val span = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0)) / 2 + reach + 1e-3
        val latC = asin(cy.toDouble().coerceIn(-1.0, 1.0))
        val lonC = atan2(cx.toDouble(), -cz.toDouble())
        val j0 = floor((PI / 2 - (latC + span)) / PI * height).toInt().coerceIn(0, height - 1)
        val j1 = floor((PI / 2 - (latC - span)) / PI * height).toInt().coerceIn(0, height - 1)
        val cosSpan = cos(span)
        val sinLatC = sin(latC)
        val cosLatC = cos(latC)
        val reachF = brush.reach
        // Within the same line, a stamp this far along from the last one at a texel is the same pass over it.
        val samePass = (Math.toDegrees((brush.reach * 2).toDouble()) / ALONG_UNIT).toInt() + SAME_PASS_EXTRA
        val wr = if (wayRgb == null) 0f else (wayRgb shr 16 and 0xFF).toFloat()
        val wg = if (wayRgb == null) 0f else (wayRgb shr 8 and 0xFF).toFloat()
        val wb = if (wayRgb == null) 0f else (wayRgb and 0xFF).toFloat()
        val ways = way
        val f = if (fired) 1f else 0f
        for (j in j0..j1) {
            val lat = latOf(j)
            // The longitudes of this row within the cap: cos(d) = sin φ sin φc + cos φ cos φc cos Δλ ≥ cos(span).
            val den = cos(lat) * cosLatC
            val i0: Int
            val count: Int
            if (den < 1e-6) {
                i0 = 0; count = width
            } else {
                val q = (cosSpan - sin(lat) * sinLatC) / den
                if (q > 1.0) continue
                if (q <= -1.0) {
                    i0 = 0; count = width
                } else {
                    val dl = acos(q)
                    i0 = floor((lonC - dl + PI) / (2 * PI) * width).toInt()
                    count = minOf(width, floor((lonC + dl + PI) / (2 * PI) * width).toInt() - i0 + 1)
                }
            }
            val sl = sinLat[j]
            val clat = cosLat[j]
            val row = j * width
            for (s in 0 until count) {
                val i = Math.floorMod(i0 + s, width)
                val px = clat * sinLon[i]
                val py = sl
                val pz = -clat * cosLon[i]
                // The nearest point of the piece (on its chord, then out onto the sphere).
                var t = if (ab2 < 1e-12f) 0f else ((px - a[0]) * abx + (py - a[1]) * aby + (pz - a[2]) * abz) / ab2
                t = t.coerceIn(0f, 1f)
                var qx = a[0] + abx * t
                var qy = a[1] + aby * t
                var qz = a[2] + abz * t
                val ql = sqrt(qx * qx + qy * qy + qz * qz)
                qx /= ql; qy /= ql; qz /= ql
                val dx = px - qx
                val dy = py - qy
                val dz = pz - qz
                val d = sqrt(dx * dx + dy * dy + dz * dz) // the chord: as the angle, this close
                if (d >= reachF) continue
                val k = row + i
                var pa = brush.paint(d)
                var pg = brush.glow(d)
                if (pa <= 0f && pg <= 0f) continue
                val oldA = (amount[k].toInt() and 0xFF) / 255f
                val oldG = (glow[k].toInt() and 0xFF) / 255f
                // What was there before this line's pass: take this line's part back out.
                var baseA = oldA
                var baseG = oldG
                var keep = 1f // how much of the old colour stays, as a share of the old
                if (lineTag[k] == line && Math.floorMod(along - lineAlong[k], 0x10000) <= samePass) {
                    val la = (lineAmount[k].toInt() and 0xFF) / 255f
                    val lg = (lineGlow[k].toInt() and 0xFF) / 255f
                    if (pa <= la && pg <= lg) continue
                    pa = maxOf(pa, la)
                    pg = maxOf(pg, lg)
                    if (la < 0.995f) baseA = ((oldA - la) / (1f - la)).coerceIn(0f, 1f)
                    if (lg < 0.995f) baseG = ((oldG - lg) / (1f - lg)).coerceIn(0f, 1f)
                    // The old colour's share that was the base's, before this line's part.
                    keep = if (oldA > 1e-4f) (baseA / oldA).coerceIn(0f, 1f) else 0f
                }
                val newA = baseA + pa * (1f - baseA)
                val newG = baseG + pg * (1f - baseG)
                // Premultiplied colours: the base's part, under the new paint, plus the new paint's.
                val oldF = (fire[k].toInt() and 0xFF) / 255f
                val newF = oldF * keep * (1f - pa) + f * pa
                amount[k] = (newA * 255f + 0.5f).toInt().toByte()
                glow[k] = (newG * 255f + 0.5f).toInt().toByte()
                fire[k] = (newF.coerceAtMost(newA) * 255f + 0.5f).toInt().toByte()
                if (ways != null && wayRgb != null) {
                    val o = ways[k]
                    val under = keep * (1f - pa)
                    val r = ((o shr 16 and 0xFF) * under + wr * pa).toInt().coerceIn(0, 255)
                    val g = ((o shr 8 and 0xFF) * under + wg * pa).toInt().coerceIn(0, 255)
                    val bl = ((o and 0xFF) * under + wb * pa).toInt().coerceIn(0, 255)
                    ways[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
                lineTag[k] = line
                lineAlong[k] = along.toShort()
                lineAmount[k] = (pa * 255f + 0.5f).toInt().toByte()
                lineGlow[k] = (pg * 255f + 0.5f).toInt().toByte()
            }
        }
    }

    /** The texel holding unit direction ([x], [y], [z]). */
    fun texelOf(x: Float, y: Float, z: Float): Int {
        val lat = asin(y.toDouble().coerceIn(-1.0, 1.0))
        val lon = atan2(x.toDouble(), -z.toDouble())
        val i = floor((lon + PI) / (2 * PI) * width).toInt().coerceIn(0, width - 1)
        val j = floor((PI / 2 - lat) / PI * height).toInt().coerceIn(0, height - 1)
        return j * width + i
    }

    companion object {
        const val WIDTH = 1024
        const val HEIGHT = 512

        /** How far the glow reaches, in brush half widths. */
        const val REACH = 3f

        private const val PIECE_DEG = 4.0

        // How finely the place along a line is kept (degrees), and the slack added to "the same pass".
        private const val ALONG_UNIT = 0.1
        private const val SAME_PASS_EXTRA = 20

        /** The unit direction [f] of the way from unit a to unit b ([hop] radians apart) into [out]. */
        private fun slerp(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, hop: Double, f: Float, out: FloatArray) {
            if (hop < 1e-4 || sin(hop) < 1e-4) {
                out[0] = ax + (bx - ax) * f; out[1] = ay + (by - ay) * f; out[2] = az + (bz - az) * f
            } else {
                val s = sin(hop)
                val wa = (sin((1 - f) * hop) / s).toFloat()
                val wb = (sin(f * hop) / s).toFloat()
                out[0] = ax * wa + bx * wb; out[1] = ay * wa + by * wb; out[2] = az * wa + bz * wb
            }
            LaserTrace.unit(out)
        }
    }
}
