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
import androidx.compose.ui.graphics.drawscope.Stroke
import com.oleroma.gyrophone.core.engine.GyroPose

/**
 * A small 3D phone, centred in the pad, turned by [pose]: a box of 8 corners, its faces seen from
 * the front drawn flat (no 3D library). It redraws only on frames where the pose changed.
 */
@Composable
fun GyroPhoneView(pose: GyroPose, modifier: Modifier = Modifier) {
    var shown by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(pose) {
        while (true) withFrameMillis { pose.version.let { if (it != shown) shown = it } }
    }
    val m = remember { FloatArray(9) }
    val px = remember { FloatArray(CORNERS * 2) }
    val nz = remember { FloatArray(FACES.size) }
    val path = remember { Path() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") shown // redraw when the pose changes
        pose.matrix(m)
        val landscape = size.width >= size.height
        // Half sizes in model units: the long side follows the screen's.
        val hx = if (landscape) 1f else 0.5f
        val hy = if (landscape) 0.5f else 1f
        val unit = minOf(size.width, size.height) * SIZE
        val cx = size.width / 2
        val cy = size.height / 2
        for (i in 0 until CORNERS) {
            val x = if (i and 1 != 0) hx else -hx
            val y = if (i and 2 != 0) hy else -hy
            val z = if (i and 4 != 0) DEPTH else -DEPTH
            val rx = m[0] * x + m[1] * y + m[2] * z
            val ry = m[3] * x + m[4] * y + m[5] * z
            val rz = m[6] * x + m[7] * y + m[8] * z
            val k = unit * CAMERA / (CAMERA - rz)
            px[i * 2] = cx + rx * k
            px[i * 2 + 1] = cy - ry * k
        }
        // Each face's normal toward the player (z of its rotated outward axis); only those facing them are drawn.
        for (f in FACES.indices) {
            val a = FACE_AXIS[f]
            nz[f] = m[6 + (a shr 1)] * (if (a and 1 == 0) 1f else -1f)
        }
        val stroke = Stroke(width = density * 1.5f, join = StrokeJoin.Round)
        for (f in FACES.indices) {
            if (nz[f] <= 0.01f) continue
            val c = FACES[f]
            path.reset()
            path.moveTo(px[c[0] * 2], px[c[0] * 2 + 1])
            for (j in 1 until 4) path.lineTo(px[c[j] * 2], px[c[j] * 2 + 1])
            path.close()
            val fill = when (f) {
                SCREEN -> Color(0xFF1B2330)
                BACK -> Color(0xFF3A4352)
                else -> Color(0xFF59647A)
            }
            drawPath(path, fill.copy(alpha = ALPHA * (0.45f + 0.55f * nz[f])))
            drawPath(path, Color.White.copy(alpha = ALPHA * 0.8f), style = stroke)
            if (f == SCREEN) {
                // The display, inset from the frame.
                path.reset()
                for (j in 0 until 4) {
                    val p = inset(px, c[j], c[(j + 2) % 4], 0.08f)
                    if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, Color(0xFF4F7DBF).copy(alpha = ALPHA * 0.5f * nz[f]))
            }
            if (f == BACK) {
                // Camera bump near one corner, so the back reads as the back.
                val p = inset(px, c[0], c[2], 0.2f)
                drawCircle(Color.White.copy(alpha = ALPHA * 0.7f), unit * 0.09f * nz[f], p, style = stroke)
            }
        }
    }
}

/** Corner [a] of the projected [px], moved [t] of the way toward the opposite corner [b]. */
private fun inset(px: FloatArray, a: Int, b: Int, t: Float) = Offset(
    px[a * 2] + (px[b * 2] - px[a * 2]) * t,
    px[a * 2 + 1] + (px[b * 2 + 1] - px[a * 2 + 1]) * t,
)

private const val CORNERS = 8

/** Corner index bits: 1 = +x, 2 = +y, 4 = +z. Each face lists its corners around its edge. */
private val FACES = arrayOf(
    intArrayOf(4, 5, 7, 6), // +z: the screen
    intArrayOf(0, 2, 3, 1), // -z: the back
    intArrayOf(1, 3, 7, 5), // +x
    intArrayOf(0, 4, 6, 2), // -x
    intArrayOf(2, 6, 7, 3), // +y
    intArrayOf(0, 1, 5, 4), // -y
)

/** Each face's outward axis: (axis index shl 1) or (1 when negative); the axis index picks the matrix column. */
private val FACE_AXIS = intArrayOf(2 shl 1, (2 shl 1) or 1, 0, 1, 1 shl 1, (1 shl 1) or 1)

private const val SCREEN = 0
private const val BACK = 1

/** Half thickness, against half sizes of 1 and 0.5. */
private const val DEPTH = 0.07f

/** Camera distance in model units: enough perspective to read the turn, little distortion. */
private const val CAMERA = 6f

/** Model unit as a part of the pad's shorter side. */
private const val SIZE = 0.2f

private const val ALPHA = 0.55f
