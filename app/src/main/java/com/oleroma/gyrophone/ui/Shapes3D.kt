package com.oleroma.gyrophone.ui

import com.oleroma.gyrophone.core.model.BallShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wireframe shape for the 3D shape display: [vertices] (x, y, z in the phone's frame, the shape
 * within the unit ball), [faces] (vertex indices round each), each face's outward [normals] and
 * centres, and its [edges] (vertex pairs) with the faces either side. [twoSided]: an open sheet
 * (the paper plane), every face seen from both sides. [convex]: no face ever hides another that
 * faces the viewer. [onSphere]: its vertices lie on the unit ball, so it can be drawn smooth.
 * Built once; the scratch arrays hold one frame's projection (UI thread only).
 */
internal class Mesh(
    val vertices: FloatArray,
    val faces: Array<IntArray>,
    val twoSided: Boolean = false,
    val convex: Boolean = true,
    val onSphere: Boolean = false,
    normalsOf: ((Int) -> FloatArray)? = null,
) {
    val normals = FloatArray(faces.size * 3)
    val centres = FloatArray(faces.size * 3)
    val edges: IntArray
    val edgeFaces: IntArray

    /** Pieces to bend each edge into on the ball (smooth drawing). */
    val edgePieces: IntArray

    val tx = FloatArray(vertices.size / 3)
    val ty = FloatArray(vertices.size / 3)
    val tz = FloatArray(vertices.size / 3)
    val faceZ = FloatArray(faces.size)
    val faceFront = BooleanArray(faces.size)
    val faceShade = FloatArray(faces.size)
    val order = IntArray(faces.size) { it }

    init {
        for ((f, face) in faces.withIndex()) {
            var cx = 0f; var cy = 0f; var cz = 0f
            for (i in face) { cx += vertices[i * 3]; cy += vertices[i * 3 + 1]; cz += vertices[i * 3 + 2] }
            cx /= face.size; cy /= face.size; cz /= face.size
            centres[f * 3] = cx; centres[f * 3 + 1] = cy; centres[f * 3 + 2] = cz
            val n = normalsOf?.invoke(f) ?: if (convex && !twoSided) floatArrayOf(cx, cy, cz) else cross(face)
            val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-6f)
            normals[f * 3] = n[0] / l; normals[f * 3 + 1] = n[1] / l; normals[f * 3 + 2] = n[2] / l
        }
        // Edges from the faces' sides, each once, with the faces on either side (-1: none).
        val index = HashMap<Long, Int>()
        val pairs = ArrayList<Int>()
        val sides = ArrayList<Int>()
        for ((f, face) in faces.withIndex()) {
            for (k in face.indices) {
                val a = face[k]
                val b = face[(k + 1) % face.size]
                val key = minOf(a, b).toLong() * 100000 + maxOf(a, b)
                val e = index[key]
                if (e == null) {
                    index[key] = pairs.size / 2
                    pairs += a; pairs += b
                    sides += f; sides += -1
                } else sides[e * 2 + 1] = f
            }
        }
        edges = pairs.toIntArray()
        edgeFaces = sides.toIntArray()
        edgePieces = IntArray(edges.size / 2) { e ->
            val a = edges[e * 2] * 3
            val b = edges[e * 2 + 1] * 3
            val dot = (vertices[a] * vertices[b] + vertices[a + 1] * vertices[b + 1] + vertices[a + 2] * vertices[b + 2]).coerceIn(-1f, 1f)
            ceil(Math.toDegrees(acos(dot).toDouble()) / SMOOTH_PIECE_DEG).toInt().coerceIn(1, 16)
        }
    }

    private fun cross(face: IntArray): FloatArray {
        val a = face[0] * 3; val b = face[1] * 3; val c = face[2] * 3
        val ux = vertices[b] - vertices[a]; val uy = vertices[b + 1] - vertices[a + 1]; val uz = vertices[b + 2] - vertices[a + 2]
        val vx = vertices[c] - vertices[a]; val vy = vertices[c + 1] - vertices[a + 1]; val vz = vertices[c + 2] - vertices[a + 2]
        return floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    }

    /**
     * Projects every vertex by row-major [m] into [tx], [ty], [tz] (view frame, z toward the viewer)
     * and marks each face front facing or not, its depth and its shade (how squarely it faces).
     */
    fun project(m: FloatArray) {
        for (i in tx.indices) {
            val x = vertices[i * 3]; val y = vertices[i * 3 + 1]; val z = vertices[i * 3 + 2]
            tx[i] = m[0] * x + m[1] * y + m[2] * z
            ty[i] = m[3] * x + m[4] * y + m[5] * z
            tz[i] = m[6] * x + m[7] * y + m[8] * z
        }
        for (f in faces.indices) {
            val nz = m[6] * normals[f * 3] + m[7] * normals[f * 3 + 1] + m[8] * normals[f * 3 + 2]
            faceFront[f] = twoSided || nz > 0f
            faceShade[f] = abs(nz)
            faceZ[f] = m[6] * centres[f * 3] + m[7] * centres[f * 3 + 1] + m[8] * centres[f * 3 + 2]
        }
    }

    /** [order] sorted far to near; nearly sorted from the last frame, so insertion sort is quick. */
    fun sortFarToNear() {
        for (i in 1 until order.size) {
            val f = order[i]
            var j = i - 1
            while (j >= 0 && faceZ[order[j]] > faceZ[f]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = f
        }
    }

    /** Edge [e] faces the viewer: a face beside it does (an open edge: its one face). */
    fun edgeFront(e: Int): Boolean {
        val f1 = edgeFaces[e * 2]
        val f2 = edgeFaces[e * 2 + 1]
        return faceFront[f1] || (f2 >= 0 && faceFront[f2])
    }

    companion object {
        /** Degrees of arc per piece when an edge is bent onto the ball. */
        const val SMOOTH_PIECE_DEG = 6.0
    }
}

/** The meshes, built on first use. */
internal object Meshes {
    private val cache = HashMap<String, Mesh>()

    /** [shape] with [subdivision] (icosphere) and [smooth] (torus: more sides); null for the globe and gimbal rings, drawn as circles. */
    fun of(shape: BallShape, subdivision: Int, smooth: Boolean): Mesh? = when (shape) {
        BallShape.GLOBE, BallShape.GIMBAL -> null
        BallShape.ICOSPHERE -> cache.getOrPut("ico$subdivision") { icosphere(subdivision.coerceIn(0, MAX_SUBDIVISION)) }
        BallShape.CUBE -> cache.getOrPut("cube") { cube() }
        BallShape.TETRAHEDRON -> cache.getOrPut("tetra") { tetrahedron() }
        BallShape.DODECAHEDRON -> cache.getOrPut("dodeca") { dodecahedron() }
        BallShape.TORUS -> cache.getOrPut("torus$smooth") { if (smooth) torus(28, 14) else torus(12, 6) }
        BallShape.PLANE -> cache.getOrPut("plane") { plane() }
    }

    const val MAX_SUBDIVISION = 3

    private fun unit(pts: List<FloatArray>): FloatArray {
        val out = FloatArray(pts.size * 3)
        for ((i, p) in pts.withIndex()) {
            val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            out[i * 3] = p[0] / l; out[i * 3 + 1] = p[1] / l; out[i * 3 + 2] = p[2] / l
        }
        return out
    }

    /** An icosahedron split [n] times, two corners on the up axis. */
    private fun icosphere(n: Int): Mesh {
        val t = ((1 + sqrt(5.0)) / 2).toFloat()
        val pts = ArrayList<FloatArray>()
        fun add(x: Float, y: Float, z: Float): Int {
            val l = sqrt(x * x + y * y + z * z)
            pts += floatArrayOf(x / l, y / l, z / l)
            return pts.size - 1
        }
        for ((a, b) in listOf(-1f to t, 1f to t, -1f to -t, 1f to -t)) {
            add(a, b, 0f); add(0f, a, b); add(b, 0f, a)
        }
        val tip = atan2(1.0, t.toDouble()).toFloat()
        for (p in pts) {
            val x = p[0]; val y = p[1]
            p[0] = x * cos(tip) - y * sin(tip)
            p[1] = x * sin(tip) + y * cos(tip)
        }
        var faces = triangles(pts, 2 / sqrt(1 + t * t))
        repeat(n) {
            val mids = HashMap<Long, Int>()
            fun mid(i: Int, j: Int): Int = mids.getOrPut(minOf(i, j).toLong() * 100000 + maxOf(i, j)) {
                add(pts[i][0] + pts[j][0], pts[i][1] + pts[j][1], pts[i][2] + pts[j][2])
            }
            faces = faces.flatMap { f ->
                val ab = mid(f[0], f[1]); val bc = mid(f[1], f[2]); val ca = mid(f[2], f[0])
                listOf(intArrayOf(f[0], ab, ca), intArrayOf(ab, f[1], bc), intArrayOf(ca, bc, f[2]), intArrayOf(ab, bc, ca))
            }
        }
        return Mesh(unit(pts), faces.toTypedArray(), onSphere = true)
    }

    /** Every three of [pts] all [edge] apart: the faces of a triangle-faced solid. */
    private fun triangles(pts: List<FloatArray>, edge: Float): List<IntArray> {
        fun near(i: Int, j: Int) = abs(dist(pts[i], pts[j]) - edge) < 1e-3f
        val out = ArrayList<IntArray>()
        for (i in pts.indices) for (j in i + 1 until pts.size) for (k in j + 1 until pts.size) {
            if (near(i, j) && near(j, k) && near(i, k)) out += intArrayOf(i, j, k)
        }
        return out
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    private fun cube(): Mesh {
        val pts = ArrayList<FloatArray>()
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) pts += floatArrayOf(x, y, z)
        fun at(x: Int, y: Int, z: Int) = x * 4 + y * 2 + z
        val faces = arrayOf(
            intArrayOf(at(0, 0, 0), at(0, 1, 0), at(0, 1, 1), at(0, 0, 1)),
            intArrayOf(at(1, 0, 0), at(1, 0, 1), at(1, 1, 1), at(1, 1, 0)),
            intArrayOf(at(0, 0, 0), at(0, 0, 1), at(1, 0, 1), at(1, 0, 0)),
            intArrayOf(at(0, 1, 0), at(1, 1, 0), at(1, 1, 1), at(0, 1, 1)),
            intArrayOf(at(0, 0, 0), at(1, 0, 0), at(1, 1, 0), at(0, 1, 0)),
            intArrayOf(at(0, 0, 1), at(0, 1, 1), at(1, 1, 1), at(1, 0, 1)),
        )
        return Mesh(unit(pts), faces, onSphere = true)
    }

    /** A tetrahedron standing on a face, a corner up. */
    private fun tetrahedron(): Mesh {
        val pts = ArrayList<FloatArray>()
        pts += floatArrayOf(0f, 1f, 0f)
        for (i in 0 until 3) {
            val a = i * 2 * PI / 3
            pts += floatArrayOf((sqrt(8.0 / 9) * cos(a)).toFloat(), -1f / 3, (sqrt(8.0 / 9) * sin(a)).toFloat())
        }
        val faces = arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3), intArrayOf(0, 3, 1), intArrayOf(1, 3, 2))
        return Mesh(unit(pts), faces, onSphere = true)
    }

    /** A dodecahedron: 12 pentagons, each round the direction of its centre. */
    private fun dodecahedron(): Mesh {
        val p = ((1 + sqrt(5.0)) / 2).toFloat()
        val q = 1 / p
        val pts = ArrayList<FloatArray>()
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) pts += floatArrayOf(x, y, z)
        for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) {
            pts += floatArrayOf(0f, a * q, b * p)
            pts += floatArrayOf(a * q, b * p, 0f)
            pts += floatArrayOf(a * p, 0f, b * q)
        }
        val v = unit(pts)
        val faces = ArrayList<IntArray>()
        for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) {
            // The face centres: (0, ±φ, ±1) and its cyclic turns.
            for (d in listOf(floatArrayOf(0f, a * p, b), floatArrayOf(a * p, b, 0f), floatArrayOf(b, 0f, a * p))) {
                val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                val dx = d[0] / l; val dy = d[1] / l; val dz = d[2] / l
                // The five corners nearest this direction, in order round it.
                val near = (0 until pts.size).sortedByDescending { v[it * 3] * dx + v[it * 3 + 1] * dy + v[it * 3 + 2] * dz }.take(5)
                val ux: Float; val uy: Float; val uz: Float
                run {
                    val f = near[0] * 3
                    val k = v[f] * dx + v[f + 1] * dy + v[f + 2] * dz
                    val x = v[f] - k * dx; val y = v[f + 1] - k * dy; val z = v[f + 2] - k * dz
                    val m = sqrt(x * x + y * y + z * z)
                    ux = x / m; uy = y / m; uz = z / m
                }
                val wx = dy * uz - dz * uy; val wy = dz * ux - dx * uz; val wz = dx * uy - dy * ux
                faces += near.sortedBy { i ->
                    val x = v[i * 3]; val y = v[i * 3 + 1]; val z = v[i * 3 + 2]
                    atan2(x * wx + y * wy + z * wz, x * ux + y * uy + z * uz)
                }.toIntArray()
            }
        }
        return Mesh(v, faces.toTypedArray(), onSphere = true)
    }

    /** A torus round the up axis: [u] sides round the ring, [w] round the tube. */
    private fun torus(u: Int, w: Int): Mesh {
        val pts = ArrayList<FloatArray>()
        for (i in 0 until u) for (j in 0 until w) {
            val a = 2 * PI * i / u
            val b = 2 * PI * j / w
            val ring = TORUS_R + TORUS_TUBE * cos(b)
            pts += floatArrayOf((ring * cos(a)).toFloat(), (TORUS_TUBE * sin(b)).toFloat(), (ring * sin(a)).toFloat())
        }
        fun at(i: Int, j: Int) = (i % u) * w + (j % w)
        val faces = Array(u * w) { k -> val i = k / w; val j = k % w; intArrayOf(at(i, j), at(i, j + 1), at(i + 1, j + 1), at(i + 1, j)) }
        val v = FloatArray(pts.size * 3).also { out -> pts.forEachIndexed { i, pt -> pt.copyInto(out, i * 3) } }
        return Mesh(v, faces, convex = false) { k ->
            val a = 2 * PI * (k / w + 0.5) / u
            val b = 2 * PI * (k % w + 0.5) / w
            floatArrayOf((cos(b) * cos(a)).toFloat(), sin(b).toFloat(), (cos(b) * sin(a)).toFloat())
        }
    }

    /** A paper plane, nose up, wings spread across, keel behind (away from the screen). */
    private fun plane(): Mesh {
        val v = floatArrayOf(
            0f, 0.95f, 0f, // nose
            -0.62f, -0.72f, 0.06f, // left wing tip
            0f, -0.72f, 0f, // tail
            0.62f, -0.72f, 0.06f, // right wing tip
            0f, -0.72f, -0.32f, // keel bottom
        )
        val faces = arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3), intArrayOf(0, 2, 4))
        return Mesh(v, faces, twoSided = true, convex = false)
    }

    private const val TORUS_R = 0.66
    private const val TORUS_TUBE = 0.3
}
