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
 * Each texel is one byte: how close it is to the painted line's middle, 7 bits ([REACH_MAX] on it,
 * 0 at [REACH] brush half widths away), shifted up one, the lowest bit set when the paint was a shot
 * (Fire). The UI hands the bytes to the GPU as they are (an alpha-only bitmap, 512 KB).
 *
 * With the way colours kept ([stamp]'s wayRgb), [way] holds each texel's colour (RGB, alpha 255).
 */
class LaserPaint(val width: Int = WIDTH, val height: Int = HEIGHT) {
    val data = ByteArray(width * height)

    /** Each texel's way colour (RGB, alpha 255), made at the first stamp that keeps one. */
    var way: IntArray? = null
        private set

    /** Goes up at each change. */
    var version = 0L
        private set

    private val sinLat = FloatArray(height) { sin(latOf(it)).toFloat() }
    private val cosLat = FloatArray(height) { cos(latOf(it)).toFloat() }
    private val sinLon = FloatArray(width) { sin(lonOf(it)).toFloat() }
    private val cosLon = FloatArray(width) { cos(lonOf(it)).toFloat() }

    private fun latOf(j: Int) = PI / 2 - (j + 0.5) / height * PI
    private fun lonOf(i: Int) = (i + 0.5) / width * 2 * PI - PI

    fun clear() {
        data.fill(0)
        way?.fill(0)
        version++
    }

    /**
     * Paints the line from unit direction ([ax], [ay], [az]) to ([bx], [by], [bz]) (the same point for
     * a dot), [halfDeg] degrees each side of its middle; [fired] while firing. Where the new paint
     * reaches at least as far as what is there, it takes the texel (newer paint wins: its Fire and
     * [wayRgb], its way colour (RGB, or null)).
     */
    fun stamp(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, halfDeg: Float, fired: Boolean, wayRgb: Int? = null) {
        if (wayRgb != null && way == null) way = IntArray(width * height)
        val reachRad = Math.toRadians((halfDeg * REACH).toDouble()).coerceAtLeast(1e-4)
        // Long hops in pieces of up to PIECE_DEG, so each piece's straight chord stays on the sphere.
        val hop = acos((ax * bx + ay * by + az * bz).toDouble().coerceIn(-1.0, 1.0))
        val pieces = maxOf(1, kotlin.math.ceil(Math.toDegrees(hop) / PIECE_DEG).toInt())
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (k in 0 until pieces) {
            slerp(ax, ay, az, bx, by, bz, hop, k / pieces.toFloat(), a)
            slerp(ax, ay, az, bx, by, bz, hop, (k + 1) / pieces.toFloat(), b)
            piece(a, b, reachRad, fired, wayRgb)
        }
        version++
    }

    private fun piece(a: FloatArray, b: FloatArray, reach: Double, fired: Boolean, wayRgb: Int?) {
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
        val span = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0)) / 2 + reach + 1e-3
        val latC = asin(cy.toDouble().coerceIn(-1.0, 1.0))
        val lonC = atan2(cx.toDouble(), -cz.toDouble())
        val j0 = floor((PI / 2 - (latC + span)) / PI * height).toInt().coerceIn(0, height - 1)
        val j1 = floor((PI / 2 - (latC - span)) / PI * height).toInt().coerceIn(0, height - 1)
        val cosSpan = cos(span)
        val sinLatC = sin(latC)
        val cosLatC = cos(latC)
        val reachF = reach.toFloat()
        val fire = if (fired) 1 else 0
        val ways = way
        for (j in j0..j1) {
            val lat = latOf(j)
            // The longitudes of this row within the cap: cos(d) = sin φ sin φc + cos φ cos φc cos Δλ ≥ cos(span).
            val den = cos(lat) * cosLatC
            val i0: Int
            val n: Int
            if (den < 1e-6) {
                i0 = 0; n = width
            } else {
                val q = (cosSpan - sin(lat) * sinLatC) / den
                if (q > 1.0) continue
                if (q <= -1.0) {
                    i0 = 0; n = width
                } else {
                    val dl = acos(q)
                    i0 = floor((lonC - dl + PI) / (2 * PI) * width).toInt()
                    n = minOf(width, floor((lonC + dl + PI) / (2 * PI) * width).toInt() - i0 + 1)
                }
            }
            val sl = sinLat[j]
            val clat = cosLat[j]
            val row = j * width
            for (s in 0 until n) {
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
                val cov = ((1f - d / reachF) * REACH_MAX + 0.5f).toInt().coerceIn(1, REACH_MAX)
                val k = row + i
                if (cov < (data[k].toInt() and 0xFF) shr 1) continue
                data[k] = (cov shl 1 or fire).toByte()
                if (ways != null && wayRgb != null) ways[k] = (0xFF shl 24) or (wayRgb and 0xFFFFFF)
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

        /** How far the paint reaches, in brush half widths: the line's edge is at 1, its glow fades out by here. */
        const val REACH = 3f

        /** Red's 7 bits: on the line's middle. */
        const val REACH_MAX = 127

        /** Where the line's edge is in red's reach (0 .. 1): one half width of [REACH]. */
        const val EDGE = 1f - 1f / REACH

        private const val PIECE_DEG = 4.0

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
