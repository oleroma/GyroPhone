package com.oleroma.gyrophone.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pad's gyro displays ([TouchVisuals]): a 3D phone, a ratchet ring, a speed graph, an attitude
 * indicator and a parallax edge, all drawn flat on one canvas (no 3D library) from the runtime's
 * gyro data, once per screen frame while any is on.
 */
@Composable
fun GyroVisualsLayer(runtime: GamepadRuntime, v: TouchVisuals, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frame = it }
    }
    val st = remember { VisState() }
    Canvas(modifier) {
        st.draw(this, runtime, v, frame)
    }
}

/** Buffers the displays reuse from frame to frame, so drawing allocates nothing. */
private class VisState {
    val portrait = PhoneModel(landscape = false)
    val landscape = PhoneModel(landscape = true)
    val raw = FloatArray(9)
    val m = FloatArray(9)
    val path = Path()

    /** The last [GHOSTS] phone poses, a matrix each, newest at [ghostHead - 1]. */
    val ghosts = FloatArray(GHOSTS * 9)
    var ghostCount = 0
    var ghostHead = 0
    var ghostT = 0L

    val traceYaw = FloatArray(1024)
    val tracePitch = FloatArray(1024)
    val traceRoll = FloatArray(1024)
    val traceHeld = BooleanArray(1024)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun draw(scope: DrawScope, runtime: GamepadRuntime, v: TouchVisuals, now: Long) = with(scope) {
        val op = v.gyroOpacity.coerceIn(0.05f, 1f)
        val live = runtime.gyroLiveAxes
        if (v.gyroEdge) drawEdge(runtime, v, op, live)
        if (v.gyroGraph) drawGraph(runtime, v, op)
        if (v.gyroHorizon) drawHorizon(runtime, v, op)
        if (v.gyroPhone) drawPhone(runtime, v, op, now)
        if (v.gyroRatchetRing) drawRing(v, op, live)
    }

    /** Where a display of half size [half] centres for [p]. */
    private fun DrawScope.placeAt(p: VisPlace, half: Float): Offset {
        val e = MARGIN_DP * density + half
        return when (p) {
            VisPlace.CENTER -> center
            VisPlace.TOP_LEFT -> Offset(e, e)
            VisPlace.TOP_RIGHT -> Offset(size.width - e, e)
            VisPlace.BOTTOM_LEFT -> Offset(e, size.height - e)
            VisPlace.BOTTOM_RIGHT -> Offset(size.width - e, size.height - e)
        }
    }

    /** [m] = the pose of [raw] seen from the level angle: the pose [levelDeg] back from upright shows square on. */
    private fun level(levelDeg: Int) {
        val a = Math.toRadians(levelDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        for (j in 0..2) {
            m[j] = raw[j]
            m[3 + j] = c * raw[3 + j] - s * raw[6 + j]
            m[6 + j] = s * raw[3 + j] + c * raw[6 + j]
        }
    }

    /** How far the horizon turns on screen (clockwise +, against the phone's roll) and the pitch (nose up +), in degrees, of the pose in [m]. */
    private fun horizonRollDeg() = Math.toDegrees(atan2(m[3], m[4]).toDouble()).toFloat()
    private fun pitchDeg() = Math.toDegrees(asin((-m[5]).coerceIn(-1f, 1f)).toDouble()).toFloat()

    private fun DrawScope.drawPhone(runtime: GamepadRuntime, v: TouchVisuals, op: Float, now: Long) {
        (if (v.gyroPhoneGravity) runtime.worldPose else runtime.gyroPose).matrix(raw)
        level(v.gyroLevelDeg)
        val model = if (size.width >= size.height) landscape else portrait
        val unit = minOf(size.width, size.height) * PHONE_SIZE * v.gyroPhoneSize.coerceIn(0.4f, 2.5f)
        val c = placeAt(v.gyroPhonePlace, unit * 1.15f)
        if (v.gyroPhoneGhosts) {
            if (now - ghostT >= GHOST_MS) {
                ghostT = now
                m.copyInto(ghosts, ghostHead * 9)
                ghostHead = (ghostHead + 1) % GHOSTS
                if (ghostCount < GHOSTS) ghostCount++
            }
            // Oldest first, fainter the older.
            for (i in ghostCount downTo 1) {
                val at = ((ghostHead - i + GHOSTS) % GHOSTS) * 9
                model.drawOutline(this, ghosts, at, path, c, unit, op * 0.35f * (1f - (i - 1f) / GHOSTS))
            }
        } else ghostCount = 0
        model.draw(this, m, path, c, unit, op, v.gyroPhoneAxes)
    }

    private fun DrawScope.drawRing(v: TouchVisuals, op: Float, live: Int) {
        val r = minOf(size.width, size.height) * RING_SIZE
        val c = placeAt(v.gyroRatchetRingPlace, r)
        val w = r * 0.28f
        val box = Size(r * 2, r * 2)
        val tl = Offset(c.x - r, c.y - r)
        for ((i, a) in RING_AXES.withIndex()) {
            val on = live and MappingEngine.gyroBit(a) != 0
            drawArc(
                AXIS_COLORS[i].copy(alpha = op * (if (on) 1f else 0.18f)),
                startAngle = -90f - 55f + i * 120f, sweepAngle = 110f, useCenter = false,
                topLeft = tl, size = box, style = Stroke(w, cap = StrokeCap.Round),
            )
            // Each arc's letter: Y, P, R.
            val mid = Math.toRadians((-90.0 + i * 120.0))
            text.textSize = r * 0.55f
            text.color = Color.White.copy(alpha = op * (if (on) 0.9f else 0.3f)).toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                RING_LETTERS[i], c.x + (r * 0.45f * cos(mid)).toFloat(),
                c.y + (r * 0.45f * sin(mid)).toFloat() + text.textSize * 0.35f, text,
            )
        }
    }

    private fun DrawScope.drawGraph(runtime: GamepadRuntime, v: TouchVisuals, op: Float) {
        val trace = runtime.gyroTrace
        val want = (v.gyroGraphSeconds * trace.rateHz).coerceIn(2, traceYaw.size)
        val n = trace.read(want, traceYaw, tracePitch, traceRoll, traceHeld)
        val e = MARGIN_DP * density
        val h = size.height * GRAPH_HEIGHT
        val top = size.height - e - h
        val left = e
        val width = size.width - 2 * e
        val mid = top + h / 2
        drawRoundRect(Color.Black.copy(alpha = op * 0.35f), Offset(left, top), Size(width, h), CornerRadius(8 * density))
        drawLine(Color.White.copy(alpha = op * 0.25f), Offset(left, mid), Offset(left + width, mid), density)
        if (n < 2) return
        // The newest bucket sits at the right edge; a part-full window starts mid-way.
        val step = width / (want - 1)
        val x0 = left + width - (n - 1) * step
        var j = 0
        while (j < n) {
            if (!traceHeld[j]) { j++; continue }
            val s = j
            while (j < n && traceHeld[j]) j++
            drawRect(
                Color.White.copy(alpha = op * 0.12f),
                Offset(x0 + s * step, top), Size(maxOf(step, (j - s) * step), h),
            )
        }
        val range = v.gyroGraphRange.coerceAtLeast(30).toFloat()
        val half = h / 2 * 0.92f
        for ((i, data) in listOf(traceYaw, tracePitch, traceRoll).withIndex()) {
            path.reset()
            for (k in 0 until n) {
                val y = mid - (data[k] / range).coerceIn(-1f, 1f) * half
                if (k == 0) path.moveTo(x0, y) else path.lineTo(x0 + k * step, y)
            }
            drawPath(path, AXIS_COLORS[i].copy(alpha = op), style = Stroke(1.6f * density, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawHorizon(runtime: GamepadRuntime, v: TouchVisuals, op: Float) {
        runtime.worldPose.matrix(raw)
        level(v.gyroLevelDeg)
        val r = minOf(size.width, size.height) * HORIZON_SIZE * v.gyroHorizonSize.coerceIn(0.4f, 2.5f)
        val tape = r * 0.3f
        val gap = r * 0.12f
        // The tape sits above the dial: centre the pair as one.
        val c0 = placeAt(v.gyroHorizonPlace, r + (tape + gap) / 2)
        val c = Offset(c0.x, c0.y + (tape + gap) / 2)
        val roll = horizonRollDeg()
        val pitch = pitchDeg()
        val k = r / 40f // px per degree of pitch
        path.reset()
        path.addOval(Rect(c, r))
        clipPath(path) {
            rotate(roll, c) {
                val hy = c.y + pitch * k
                drawRect(SKY.copy(alpha = op), Offset(c.x - 2 * r, hy - 4 * r), Size(4 * r, 4 * r))
                drawRect(GROUND.copy(alpha = op), Offset(c.x - 2 * r, hy), Size(4 * r, 4 * r))
                drawLine(Color.White.copy(alpha = op), Offset(c.x - 2 * r, hy), Offset(c.x + 2 * r, hy), 1.5f * density)
                // Pitch ladder every 10°.
                var a = -60
                while (a <= 60) {
                    if (a != 0) {
                        val y = hy - a * k
                        val w = if (a % 20 == 0) r * 0.4f else r * 0.22f
                        drawLine(Color.White.copy(alpha = op * 0.7f), Offset(c.x - w, y), Offset(c.x + w, y), density)
                    }
                    a += 10
                }
            }
        }
        drawCircle(Color.White.copy(alpha = op * 0.8f), r, c, style = Stroke(1.5f * density))
        // The fixed aircraft symbol.
        val sym = AIRCRAFT.copy(alpha = op)
        drawLine(sym, Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.18f, c.y), 2.5f * density, StrokeCap.Round)
        drawLine(sym, Offset(c.x + r * 0.18f, c.y), Offset(c.x + r * 0.55f, c.y), 2.5f * density, StrokeCap.Round)
        drawCircle(sym, 2.5f * density, c)
        // Heading tape: ticks every 10°, numbers every 30°, the heading under the middle mark.
        val tTop = c.y - r - gap - tape
        val tRect = Rect(c.x - r, tTop, c.x + r, tTop + tape)
        drawRoundRect(Color.Black.copy(alpha = op * 0.4f), tRect.topLeft, tRect.size, CornerRadius(4 * density))
        val heading = runtime.gyroHeading
        val pxPerDeg = r / 45f
        text.textSize = tape * 0.48f
        text.color = Color.White.copy(alpha = op).toArgb()
        clipRect(tRect.left, tRect.top, tRect.right, tRect.bottom) {
            var d = (heading / 10f).roundToInt() * 10 - 50
            while (d <= heading + 50) {
                val x = c.x + (d - heading) * pxPerDeg
                val big = d % 30 == 0
                drawLine(
                    Color.White.copy(alpha = op * 0.8f),
                    Offset(x, tRect.bottom), Offset(x, tRect.bottom - tape * (if (big) 0.35f else 0.2f)), density,
                )
                if (big) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${((d % 360) + 360) % 360}", x, tRect.top + tape * 0.5f, text,
                    )
                }
                d += 10
            }
        }
        drawLine(AIRCRAFT.copy(alpha = op), Offset(c.x, tRect.top), Offset(c.x, tRect.bottom), 2 * density)
    }

    private fun DrawScope.drawEdge(runtime: GamepadRuntime, v: TouchVisuals, op: Float, live: Int) {
        val sx: Float
        val sy: Float
        val rot: Float
        if (v.gyroEdgeTilt) {
            runtime.worldPose.matrix(raw)
            level(v.gyroLevelDeg)
            // Tilt as if turning: rolling right reads as a turn right, pitching up as up.
            sx = -horizonRollDeg()
            sy = pitchDeg()
            rot = 0f
        } else {
            val s = runtime.gyroSway
            sx = s.x; sy = s.y; rot = s.rot
        }
        val dir = if (v.gyroEdgeInvert) -1f else 1f
        val k = EDGE_PX_PER_DEG * density * v.gyroEdgeStrength * dir
        val limit = EDGE_MAX_DP * density
        // Tint: white with every axis live; else the live axes' colours mixed, dimmer the fewer.
        var color = Color.White
        var dim = 1f
        if (v.gyroEdgeRatchetTint && live != MappingEngine.ALL_GYRO) {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for ((i, a) in RING_AXES.withIndex()) {
                if (live and MappingEngine.gyroBit(a) == 0) continue
                r += AXIS_COLORS[i].red; g += AXIS_COLORS[i].green; b += AXIS_COLORS[i].blue; n++
            }
            color = if (n == 0) Color.Gray else Color(r / n, g / n, b / n)
            dim = 0.3f + 0.7f * n / 3f
        }
        val layers = v.gyroEdgeLayers.coerceIn(1, 5)
        val width = v.gyroEdgeWidthDp.coerceIn(0.5f, 8f) * density
        for (i in 0 until layers) {
            val depth = (i + 1f) / layers
            val inset = (v.gyroEdgeInsetDp + i * EDGE_GAP_DP) * density
            if (inset * 2 >= minOf(size.width, size.height)) break
            val dx = (-sx * k * depth).coerceIn(-limit, limit)
            val dy = (sy * k * depth).coerceIn(-limit, limit)
            val radius = (EDGE_CORNER_DP * density - inset * 0.3f).coerceAtLeast(6 * density)
            val alpha = op * dim * (1f - 0.55f * i / layers)
            withTransform({
                translate(dx, dy)
                rotate(-rot * 0.5f * depth * dir, center)
            }) {
                drawRoundRect(
                    color.copy(alpha = alpha), Offset(inset, inset),
                    Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(radius),
                    style = Stroke(width),
                )
            }
        }
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

    /** Projects (x, y, z) turned by [m] (from [at]) into [p]: screen x, y and the scale there. */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float, at: Int = 0) {
        val rx = m[at] * x + m[at + 1] * y + m[at + 2] * z
        val ry = m[at + 3] * x + m[at + 4] * y + m[at + 5] * z
        val rz = m[at + 6] * x + m[at + 7] * y + m[at + 8] * z
        val k = unit * CAMERA / (CAMERA - rz)
        p[0] = cx + rx * k
        p[1] = cy - ry * k
        p[2] = k
    }

    private fun ring(m: FloatArray, src: FloatArray, z: Float, out: FloatArray, at: Int = 0) {
        for (i in 0 until n) {
            project(m, src[i * 2], src[i * 2 + 1], z, at)
            out[i * 2] = p[0]; out[i * 2 + 1] = p[1]
        }
    }

    private fun Path.outline(pts: FloatArray) {
        reset()
        moveTo(pts[0], pts[1])
        for (i in 1 until n) lineTo(pts[i * 2], pts[i * 2 + 1])
        close()
    }

    private fun place(c: Offset, size: Float) {
        cx = c.x; cy = c.y; unit = size
    }

    /** Only the front outline of the pose in [m] from [at]: a ghost. */
    fun drawOutline(scope: DrawScope, m: FloatArray, at: Int, path: Path, c: Offset, size: Float, alpha: Float) = with(scope) {
        place(c, size)
        ring(m, body, DEPTH, front, at)
        path.outline(front)
        drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(width = density, join = StrokeJoin.Round))
    }

    fun draw(scope: DrawScope, m: FloatArray, path: Path, c: Offset, size: Float, alpha: Float, axes: Boolean) = with(scope) {
        place(c, size)
        ring(m, body, DEPTH, front)
        ring(m, body, -DEPTH, back)
        val edge = Color.White.copy(alpha = 0.8f * alpha)
        val line = Stroke(width = density * 1.5f, join = StrokeJoin.Round)
        val thin = Stroke(width = density * 1f, join = StrokeJoin.Round)
        // How squarely the front faces the player (the screen normal's z); the back's is minus it.
        val facing = m[8]
        if (-facing > 0.01f) {
            path.outline(back)
            drawPath(path, BACK.copy(alpha = alpha * (0.45f + 0.55f * -facing)))
            drawPath(path, edge, style = line)
            project(m, bumpX, bumpY, -DEPTH)
            drawCircle(edge, 0.1f * p[2] * -facing, Offset(p[0], p[1]), style = line)
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
            drawPath(path, SIDE.copy(alpha = alpha * (0.45f + 0.55f * f)))
        }
        if (facing > 0.01f) {
            path.outline(front)
            drawPath(path, BODY.copy(alpha = alpha * (0.45f + 0.55f * facing)))
            drawPath(path, edge, style = line)
            // The display: only its edge, inside thin bezels.
            ring(m, display, DEPTH, screen)
            path.outline(screen)
            drawPath(path, edge.copy(alpha = edge.alpha * 0.7f * facing), style = thin)
            project(m, camX, camY, DEPTH)
            drawCircle(edge.copy(alpha = edge.alpha * facing), CAMERA_DOT * p[2], Offset(p[0], p[1]))
        } else {
            // Seen from behind, the body's front edge still outlines the band.
            path.outline(front)
            drawPath(path, edge.copy(alpha = edge.alpha * 0.5f), style = thin)
        }
        if (axes) {
            project(m, 0f, 0f, 0f)
            val o = Offset(p[0], p[1])
            for (a in 0..2) {
                project(m, if (a == 0) AXIS_LEN else 0f, if (a == 1) AXIS_LEN else 0f, if (a == 2) AXIS_LEN else 0f)
                val tip = Offset(p[0], p[1])
                val col = AXIS_COLORS[a].copy(alpha = alpha.coerceAtLeast(0.5f))
                drawLine(col, o, tip, 2f * density, StrokeCap.Round)
                drawCircle(col, 3f * density, tip)
            }
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
private const val AXIS_LEN = 1.25f

/** Camera distance in model units: enough perspective to read the turn, little distortion. */
private const val CAMERA = 6f

/** Sizes as parts of the pad's shorter side: the phone's model unit, the ring's and the horizon's radius. */
private const val PHONE_SIZE = 0.2f
private const val RING_SIZE = 0.05f
private const val HORIZON_SIZE = 0.14f

/** The graph's height as a part of the pad's. */
private const val GRAPH_HEIGHT = 0.18f

private const val MARGIN_DP = 20f
private const val GHOSTS = 6
private const val GHOST_MS = 45L

/** The edge's swing per degree, its furthest swing, each layer's step inwards and the outer corner radius. */
private const val EDGE_PX_PER_DEG = 1.5f
private const val EDGE_MAX_DP = 80f
private const val EDGE_GAP_DP = 10f
private const val EDGE_CORNER_DP = 34f

private val BODY = Color(0xFF1B2330)
private val BACK = Color(0xFF3A4352)
private val SIDE = Color(0xFF59647A)
private val SKY = Color(0xFF2F6DB5)
private val GROUND = Color(0xFF7A5534)
private val AIRCRAFT = Color(0xFFFFC94D)

/** Yaw, pitch, roll; also the phone's x, y, z arrows. */
private val AXIS_COLORS = arrayOf(Color(0xFFFF5A5A), Color(0xFF5ADB6E), Color(0xFF5AA8FF))
private val RING_AXES = arrayOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)
private val RING_LETTERS = arrayOf("Y", "P", "R")
