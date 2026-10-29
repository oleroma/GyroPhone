package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.oleroma.gyrophone.core.engine.GyroPose
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A small 3D phone, centred in the pad, turned by [pose]: a rounded slab (front and back outlines
 * joined by a band of flat strips), with thin bezels round an unfilled display and a front camera
 * dot, drawn flat with no 3D library. Only the parts facing the player are drawn, and it redraws
 * only on frames where the pose changed.
 */
@Composable
fun GyroPhoneView(pose: GyroPose, modifier: Modifier = Modifier) {
    var shown by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(pose) {
        while (true) withFrameMillis { pose.version.let { if (it != shown) shown = it } }
    }
    val portrait = remember { PhoneModel(landscape = false) }
    val landscape = remember { PhoneModel(landscape = true) }
    val m = remember { FloatArray(9) }
    val path = remember { Path() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") shown // redraw when the pose changes
        pose.matrix(m)
        val model = if (size.width >= size.height) landscape else portrait
        model.draw(this, m, path)
    }
}

/** The phone's points in model units (long side 2) and the screen points they were last projected to. */
private class PhoneModel(landscape: Boolean) {
    private val hx = if (landscape) 1f else WIDTH
    private val hy = if (landscape) WIDTH else 1f

    /** Outline corners, anticlockwise; the same outline makes the body (front and back) and, inset, the display. */
    private val body = roundedRect(hx, hy, RADIUS)
    private val display = roundedRect(hx - BEZEL, hy - BEZEL, RADIUS - BEZEL)
    private val n = body.size / 2

    /** Each band strip's outward normal in the phone's plane. */
    private val normals = FloatArray(n * 2).also { out ->
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = body[j * 2] - body[i * 2]
            val dy = body[j * 2 + 1] - body[i * 2 + 1]
            val l = hypot(dx, dy)
            out[i * 2] = dy / l
            out[i * 2 + 1] = -dx / l
        }
    }

    /** The front camera: in the middle of the top edge, which lies to the left in landscape. */
    private val camX = if (landscape) -hx + BEZEL * 1.6f else 0f
    private val camY = if (landscape) 0f else hy - BEZEL * 1.6f
    private val bumpX = if (landscape) hx - 0.28f else -hx + 0.2f
    private val bumpY = if (landscape) hy - 0.2f else hy - 0.28f

    private val front = FloatArray(n * 2)
    private val back = FloatArray(n * 2)
    private val screen = FloatArray(n * 2)
    private val p = FloatArray(3)

    private var cx = 0f
    private var cy = 0f
    private var unit = 0f

    /** Projects (x, y, z) turned by [m] into [p]: screen x, y and the scale there. */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float) {
        val rx = m[0] * x + m[1] * y + m[2] * z
        val ry = m[3] * x + m[4] * y + m[5] * z
        val rz = m[6] * x + m[7] * y + m[8] * z
        val k = unit * CAMERA / (CAMERA - rz)
        p[0] = cx + rx * k
        p[1] = cy - ry * k
        p[2] = k
    }

    private fun ring(m: FloatArray, src: FloatArray, z: Float, out: FloatArray) {
        for (i in 0 until n) {
            project(m, src[i * 2], src[i * 2 + 1], z)
            out[i * 2] = p[0]; out[i * 2 + 1] = p[1]
        }
    }

    private fun Path.outline(pts: FloatArray) {
        reset()
        moveTo(pts[0], pts[1])
        for (i in 1 until n) lineTo(pts[i * 2], pts[i * 2 + 1])
        close()
    }

    fun draw(scope: DrawScope, m: FloatArray, path: Path) = with(scope) {
        cx = size.width / 2
        cy = size.height / 2
        unit = minOf(size.width, size.height) * SIZE
        ring(m, body, DEPTH, front)
        ring(m, body, -DEPTH, back)
        val line = Stroke(width = density * 1.5f, join = StrokeJoin.Round)
        val thin = Stroke(width = density * 1f, join = StrokeJoin.Round)
        // How squarely the front faces the player (the screen normal's z); the back's is minus it.
        val facing = m[8]
        if (-facing > 0.01f) {
            path.outline(back)
            drawPath(path, BACK.copy(alpha = ALPHA * (0.45f + 0.55f * -facing)))
            drawPath(path, EDGE, style = line)
            project(m, bumpX, bumpY, -DEPTH)
            drawCircle(EDGE, 0.1f * p[2] * -facing, Offset(p[0], p[1]), style = line)
        }
        // The band round the sides: the strips facing the player, shaded by how squarely they do.
        for (i in 0 until n) {
            val f = m[6] * normals[i * 2] + m[7] * normals[i * 2 + 1]
            if (f <= 0f) continue
            val j = (i + 1) % n
            path.reset()
            path.moveTo(front[i * 2], front[i * 2 + 1])
            path.lineTo(front[j * 2], front[j * 2 + 1])
            path.lineTo(back[j * 2], back[j * 2 + 1])
            path.lineTo(back[i * 2], back[i * 2 + 1])
            path.close()
            drawPath(path, SIDE.copy(alpha = ALPHA * (0.45f + 0.55f * f)))
        }
        if (facing > 0.01f) {
            path.outline(front)
            drawPath(path, BODY.copy(alpha = ALPHA * (0.45f + 0.55f * facing)))
            drawPath(path, EDGE, style = line)
            // The display: only its edge, inside thin bezels.
            ring(m, display, DEPTH, screen)
            path.outline(screen)
            drawPath(path, EDGE.copy(alpha = EDGE.alpha * 0.7f * facing), style = thin)
            project(m, camX, camY, DEPTH)
            drawCircle(EDGE.copy(alpha = EDGE.alpha * facing), CAMERA_DOT * p[2], Offset(p[0], p[1]))
        } else {
            // Seen from behind, the body's front edge still outlines the band.
            path.outline(front)
            drawPath(path, EDGE.copy(alpha = EDGE.alpha * 0.5f), style = thin)
        }
    }
}

/** An anticlockwise rounded rectangle of half sizes [hx], [hy] and corner radius [r], as x, y pairs. */
private fun roundedRect(hx: Float, hy: Float, r: Float): FloatArray {
    val out = FloatArray(4 * ARC * 2)
    var o = 0
    // Corner centres from top right, anticlockwise; each arc sweeps its quarter.
    val cxs = floatArrayOf(hx - r, -hx + r, -hx + r, hx - r)
    val cys = floatArrayOf(hy - r, hy - r, -hy + r, -hy + r)
    for (c in 0 until 4) {
        for (s in 0 until ARC) {
            val a = (c + s / (ARC - 1f)) * (Math.PI / 2).toFloat()
            out[o++] = cxs[c] + r * cos(a)
            out[o++] = cys[c] + r * sin(a)
        }
    }
    return out
}

/** Points per rounded corner. */
private const val ARC = 6

/** Half the short side, against half the long side 1: a modern 20:9 phone. */
private const val WIDTH = 0.46f
private const val RADIUS = 0.13f
private const val BEZEL = 0.035f

/** Half thickness. */
private const val DEPTH = 0.04f
private const val CAMERA_DOT = 0.022f

/** Camera distance in model units: enough perspective to read the turn, little distortion. */
private const val CAMERA = 6f

/** Model unit as a part of the pad's shorter side. */
private const val SIZE = 0.2f

private const val ALPHA = 0.55f
private val BODY = Color(0xFF1B2330)
private val BACK = Color(0xFF3A4352)
private val SIDE = Color(0xFF59647A)
private val EDGE = Color.White.copy(alpha = 0.45f)
