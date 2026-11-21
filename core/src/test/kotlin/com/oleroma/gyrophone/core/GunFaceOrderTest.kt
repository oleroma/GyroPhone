package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.Guns
import com.oleroma.gyrophone.core.engine.LaserTrace
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The gun's faces, drawn one over another in [LaserTrace.rifle]'s order, show what a true depth test (each pixel the
 * nearest face) shows. Drawn by their middles' depth alone, a face behind a long one could cover it, and the long face
 * looked missing.
 */
class GunFaceOrderTest {
    private val n = 160

    private fun randomTurn(r: Random): FloatArray {
        var a = r.nextFloat() * 2 - 1; var b = r.nextFloat() * 2 - 1; var c = r.nextFloat() * 2 - 1; var d = r.nextFloat() * 2 - 1
        val l = sqrt(a * a + b * b + c * c + d * d)
        a /= l; b /= l; c /= l; d /= l
        return floatArrayOf(
            a * a + b * b - c * c - d * d, 2 * (b * c - a * d), 2 * (b * d + a * c),
            2 * (b * c + a * d), a * a - b * b + c * c - d * d, 2 * (c * d - a * b),
            2 * (b * d - a * c), 2 * (c * d + a * b), a * a - b * b - c * c + d * d,
        )
    }

    private fun inside(xy: FloatArray, x: Float, y: Float): Boolean {
        var pos = false
        var neg = false
        val k = xy.size / 2
        for (i in 0 until k) {
            val j = (i + 1) % k
            val cr = (xy[j * 2] - xy[i * 2]) * (y - xy[i * 2 + 1]) - (xy[j * 2 + 1] - xy[i * 2 + 1]) * (x - xy[i * 2])
            if (cr > 0) pos = true else if (cr < 0) neg = true
        }
        return !(pos && neg)
    }

    private fun depth(p: FloatArray, x: Float, y: Float, persp: Boolean): Float {
        if (!persp) return (p[3] - p[0] * x - p[1] * y) / p[2]
        val d = LaserTrace.CAMERA_DISTANCE
        val a = (p[0] * x + p[1] * y) / d
        return (p[3] - a * d) / (p[2] - a)
    }

    /** Share of the gun's pixels (away from edges) the drawing order gets wrong. */
    private fun wrong(faces: List<LaserTrace.Companion.Face>, persp: Boolean, ordered: List<LaserTrace.Companion.Face>): Float {
        val drawn = IntArray(n * n) { -1 }
        val nearest = IntArray(n * n) { -1 }
        val z = FloatArray(n * n) { -Float.MAX_VALUE }
        val index = faces.withIndex().associate { (i, f) -> f to i }
        for (py in 0 until n) for (px in 0 until n) {
            val x = (px + 0.5f) / n * 2.4f - 1.2f
            val y = (py + 0.5f) / n * 2.4f - 1.2f
            val at = py * n + px
            for (f in ordered) if (inside(f.xy, x, y)) drawn[at] = index.getValue(f)
            for ((i, f) in faces.withIndex()) if (inside(f.xy, x, y)) {
                val d = depth(f.plane, x, y, persp)
                if (d > z[at]) { z[at] = d; nearest[at] = i }
            }
        }
        var covered = 0
        var bad = 0
        for (py in 1 until n - 1) for (px in 1 until n - 1) {
            val at = py * n + px
            if (nearest[at] < 0) continue
            covered++
            // Edge pixels are a coin toss either way.
            if (nearest[at - 1] != nearest[at] || nearest[at + 1] != nearest[at] || nearest[at - n] != nearest[at] || nearest[at + n] != nearest[at]) continue
            if (drawn[at] != nearest[at]) bad++
        }
        return bad.toFloat() / maxOf(covered, 1)
    }

    @Test fun nearerFacesAlwaysCoverFartherOnes() {
        val r = Random(7)
        var worstNew = 0f
        var sumNew = 0f
        var sumOld = 0f
        var count = 0
        for (gun in Guns.ALL) for (persp in listOf(false, true)) repeat(12) {
            val m = randomTurn(r)
            val faces = LaserTrace.rifle(m, axon = false, turned = null, persp = persp, gun = gun)
            val w = wrong(faces, persp, faces)
            worstNew = maxOf(worstNew, w)
            sumNew += w
            sumOld += wrong(faces, persp, faces.sortedBy { it.depth })
            count++
        }
        // Only where boxes cut into each other (a barrel into its receiver) can any order be off, and only a sliver.
        assertTrue("worst $worstNew", worstNew < 0.005f)
        assertTrue("new ${sumNew / count} old ${sumOld / count}", sumNew < sumOld / 3f)
    }

    @Test fun facesAwayFromTheCameraAreLeftOut() {
        val r = Random(3)
        repeat(50) {
            val m = randomTurn(r)
            for (f in LaserTrace.rifle(m, axon = false, turned = null, persp = true)) {
                val p = f.plane
                // Toward the camera at (0, 0, D): normal · (camera − point) > 0.
                assertTrue(p[2] * LaserTrace.CAMERA_DISTANCE - p[3] > 0f)
            }
        }
    }
}
