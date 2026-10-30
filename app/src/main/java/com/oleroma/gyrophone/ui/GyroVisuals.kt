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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayColor
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A gyro display ([control]) and the box it is drawn in, in pad pixels. */
class DisplayBox(val control: TouchControl, val box: Rect)

/**
 * The gyro displays placed on the pad ([com.oleroma.gyrophone.core.model.TouchKind.DISPLAY]), each
 * drawn flat in its [DisplayBox] (no 3D library) from the runtime's data for it, once per screen
 * frame. In edit mode each also gets its box, [selected] one lit.
 */
@Composable
fun GyroDisplaysLayer(
    runtime: GamepadRuntime,
    boxes: List<DisplayBox>,
    editing: Boolean,
    selected: String?,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frame = it }
    }
    val st = remember { VisState() }
    Canvas(modifier) {
        st.now = frame
        for (b in boxes) {
            val d = b.control.display ?: continue
            st.drawDisplay(this, runtime.gyroFeed(b.control.id), d, b.control.id, b.box, runtime.gyroLiveAxes)
            if (editing) {
                val sel = b.control.id == selected
                drawRect(
                    (if (sel) SELECTED_BOX else Color.White.copy(alpha = 0.45f)),
                    b.box.topLeft, b.box.size,
                    style = Stroke(
                        (if (sel) 2.5f else 1.5f) * density,
                        pathEffect = if (sel) null else PathEffect.dashPathEffect(floatArrayOf(8 * density, 6 * density)),
                    ),
                )
            }
        }
    }
}

/** The parallax edge ([TouchVisuals.gyroEdge]): outlines just inside the screen's edge that sway as the phone turns. */
@Composable
fun GyroEdgeLayer(runtime: GamepadRuntime, v: TouchVisuals, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frame = it }
    }
    val st = remember { VisState() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw each frame
        st.drawEdge(this, runtime, v)
    }
}

/** Buffers reused from frame to frame, so drawing allocates little. */
private class VisState {
    val portrait = PhoneModel(landscape = false)
    val landscape = PhoneModel(landscape = true)
    val raw = FloatArray(9)
    val m = FloatArray(9)
    val path = Path()
    val path2 = Path()

    /** The path [penTo] draws into. */
    var pen = path
    var now = 0L

    /** Per phone with a ghost trail: its last [GHOSTS] poses. */
    val ghosts = HashMap<String, Ghosts>()

    class Ghosts {
        val poses = FloatArray(GHOSTS * 9)
        var count = 0
        var head = 0
        var t = 0L
    }

    val traceYaw = FloatArray(1024)
    val tracePitch = FloatArray(1024)
    val traceRoll = FloatArray(1024)
    val traceHeld = BooleanArray(1024)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** [m] = the pose of [raw] seen from the level angle: the pose [levelDeg] back from upright shows square on. */
    fun level(levelDeg: Int) {
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
    fun horizonRollDeg() = Math.toDegrees(atan2(m[3], m[4]).toDouble()).toFloat()
    fun pitchDeg() = Math.toDegrees(asin((-m[5]).coerceIn(-1f, 1f)).toDouble()).toFloat()

    fun drawDisplay(scope: DrawScope, feed: GyroDisplayFeed?, d: GyroDisplay, id: String, box: Rect, live: Int) = with(scope) {
        val op = d.opacity.coerceIn(0.05f, 1f)
        fun col(c: GyroDisplayColor) = Color(d.color(c).toInt()).let { it.copy(alpha = it.alpha * op) }
        when (d.kind) {
            GyroDisplayKind.PHONE -> {
                poseOf(feed, d)
                val model = if (size.width >= size.height) landscape else portrait
                // The model's long side is 2 units, its box about 2.3 of them.
                val unit = minOf(box.width, box.height) / 2.3f
                if (d.ghosts) {
                    val g = ghosts.getOrPut(id) { Ghosts() }
                    if (now - g.t >= GHOST_MS) {
                        g.t = now
                        m.copyInto(g.poses, g.head * 9)
                        g.head = (g.head + 1) % GHOSTS
                        if (g.count < GHOSTS) g.count++
                    }
                    // Oldest first, fainter the older.
                    for (i in g.count downTo 1) {
                        val at = ((g.head - i + GHOSTS) % GHOSTS) * 9
                        model.drawOutline(this, g.poses, at, path, box.center, unit, col(GyroDisplayColor.PHONE_EDGE).copy(alpha = col(GyroDisplayColor.PHONE_EDGE).alpha * 0.4f * (1f - (i - 1f) / GHOSTS)))
                    }
                } else ghosts.remove(id)
                model.draw(
                    this, m, path, box.center, unit,
                    col(GyroDisplayColor.PHONE_FRONT), col(GyroDisplayColor.PHONE_BACK), col(GyroDisplayColor.PHONE_SIDE),
                    col(GyroDisplayColor.PHONE_EDGE),
                    if (d.axes) arrayOf(col(GyroDisplayColor.PHONE_X), col(GyroDisplayColor.PHONE_Y), col(GyroDisplayColor.PHONE_Z)) else null,
                )
            }

            GyroDisplayKind.RATCHET_RING -> drawRing(box, live, col(GyroDisplayColor.RING_TEXT),
                arrayOf(col(GyroDisplayColor.RING_YAW), col(GyroDisplayColor.RING_PITCH), col(GyroDisplayColor.RING_ROLL)))

            GyroDisplayKind.GRAPH -> drawGraph(feed, d, box, ::col)
            GyroDisplayKind.HORIZON -> drawHorizon(feed, d, box, ::col)
            GyroDisplayKind.DISK -> drawDisk(feed, d, box, ::col)
            GyroDisplayKind.BALL -> drawBall(feed, d, box, ::col)
        }
    }

    /** [raw] = [feed]'s pose (straight without one), [m] = it seen from [GyroDisplay.levelDeg]. */
    private fun poseOf(feed: GyroDisplayFeed?, d: GyroDisplay) {
        if (feed == null) raw.fill(0f).also { raw[0] = 1f; raw[4] = 1f; raw[8] = 1f } else feed.pose.matrix(raw)
        level(d.levelDeg)
    }

    /** The heading of [raw] in degrees (right +), as [com.oleroma.gyrophone.core.engine.GyroPose.heading]. */
    private fun headingDeg(): Float =
        if (raw[0] * raw[0] + raw[6] * raw[6] > 0.04f) Math.toDegrees(atan2(raw[6], raw[0]).toDouble()).toFloat() else 0f

    private fun DrawScope.drawDisk(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        val r = minOf(box.width, box.height) / 2f * 0.95f
        val c = box.center
        drawCircle(col(GyroDisplayColor.DISK_FACE), r, c)
        drawCircle(col(GyroDisplayColor.DISK_RIM), r, c, style = Stroke(1.5f * density))
        // The card spins against the heading, as a compass card does: the 12 o'clock mark stays put in the world.
        rotate(-headingDeg(), c) {
            val marks = col(GyroDisplayColor.DISK_MARKS)
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0 - 90.0)
                val inner = if (i % 3 == 0) 0.74f else 0.84f
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                drawLine(
                    if (i == 0) col(GyroDisplayColor.DISK_NORTH) else marks,
                    Offset(c.x + ca * r * inner, c.y + sa * r * inner), Offset(c.x + ca * r * 0.95f, c.y + sa * r * 0.95f),
                    (if (i == 0) 3.5f else if (i % 3 == 0) 2.5f else 1.5f) * density, StrokeCap.Round,
                )
            }
            if (d.crosshair) {
                val cross = col(GyroDisplayColor.DISK_CROSS)
                drawLine(cross, Offset(c.x - r * 0.66f, c.y), Offset(c.x + r * 0.66f, c.y), density)
                drawLine(cross, Offset(c.x, c.y - r * 0.66f), Offset(c.x, c.y + r * 0.66f), density)
                drawCircle(cross, r * 0.33f, c, style = Stroke(density))
            }
        }
        // The dot: where the screen points, off centre by the tilt, on the rim at the range.
        val nx = m[2]
        val ny = m[5]
        val flat = hypot(nx, ny)
        val tilt = Math.toDegrees(acos(m[8].coerceIn(-1f, 1f).toDouble())).toFloat()
        val off = (tilt / d.diskRange.coerceAtLeast(5)).coerceAtMost(1f) * r * 0.86f
        val p = if (flat > 1e-4f) Offset(c.x + nx / flat * off, c.y - ny / flat * off) else c
        val dot = col(GyroDisplayColor.DISK_DOT)
        drawCircle(dot, r * 0.09f, p)
        drawCircle(Color.Black.copy(alpha = 0.4f * dot.alpha), r * 0.09f, p, style = Stroke(density))
    }

    private fun DrawScope.drawBall(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        val r = minOf(box.width, box.height) / 2f * 0.95f
        val c = box.center
        val body = col(GyroDisplayColor.BALL_BODY)
        val lit = Color(body.red + (1 - body.red) * 0.35f, body.green + (1 - body.green) * 0.35f, body.blue + (1 - body.blue) * 0.35f, body.alpha)
        drawCircle(Brush.radialGradient(listOf(lit, body), Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 1.3f), r, c)
        // Every line and the silhouette in one path, stroked once: where they meet or cross the
        // colour does not add up, so the rim is just another line.
        path.reset()
        pen = path
        var equator = false
        if (d.icosphere) {
            addIcosphere(c, r)
        } else {
            // Latitudes every 30° round the phone's up axis; the equator apart, in its own colour.
            for (lat in -60..60 step 30) {
                val a = Math.toRadians(lat.toDouble())
                if (lat == 0 && d.equator) {
                    equator = true
                    continue
                }
                sphereCircle(c, r, sin(a).toFloat(), 0f, 1f, 0f, cos(a).toFloat(), 0f, 0f, 1f, 1f, 0f, 0f)
            }
            // Meridians every 30°: great circles through both poles.
            for (lon in 0 until 180 step 30) {
                val a = Math.toRadians(lon.toDouble())
                sphereCircle(c, r, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, sin(a).toFloat(), 0f, cos(a).toFloat())
            }
        }
        path.addOval(Rect(c, r))
        drawPath(path, col(GyroDisplayColor.BALL_LINES), style = Stroke(density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (equator) {
            path2.reset()
            pen = path2
            sphereCircle(c, r, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f)
            pen = path
            drawPath(path2, col(GyroDisplayColor.BALL_EQUATOR), style = Stroke(2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (d.screenMark) sphereDot(c, r, 0f, 0f, 1f, col(GyroDisplayColor.BALL_MARK))
        if (d.topMark) sphereDot(c, r, 0f, 1f, 0f, col(GyroDisplayColor.BALL_POLE))
    }

    /**
     * Adds to [pen] the front half of a circle on the unit ball [h] along ([ax], [ay], [az]), radius [rho], spanned
     * by ([ux], [uy], [uz]) and ([vx], [vy], [vz]) in the phone's frame, turned by [m], ball radius [r] at [c].
     */
    private fun sphereCircle(
        c: Offset, r: Float, h: Float, ax: Float, ay: Float, az: Float, rho: Float,
        ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
    ) {
        penUp()
        for (i in 0..SPHERE_STEPS) {
            val t = i * (2 * Math.PI / SPHERE_STEPS)
            val ct = cos(t).toFloat()
            val st = sin(t).toFloat()
            penTo(c, r, h * ax + rho * (ct * ux + st * vx), h * ay + rho * (ct * uy + st * vy), h * az + rho * (ct * uz + st * vz))
        }
    }

    /** The pen's last point in the view frame (on the unit ball), or none since [penUp]. */
    private var penX = 0f
    private var penY = 0f
    private var penZ = 0f
    private var penDown = false

    private fun penUp() {
        penDown = false
    }

    /**
     * Adds to [path] the line to the phone-frame point ([px], [py], [pz]) on the unit ball, turned by
     * [m], ball radius [r] at [c]: only its front part, cut exactly at the rim so no line stops short
     * of the silhouette.
     */
    private fun penTo(c: Offset, r: Float, px: Float, py: Float, pz: Float) {
        val x = m[0] * px + m[1] * py + m[2] * pz
        val y = m[3] * px + m[4] * py + m[5] * pz
        val z = m[6] * px + m[7] * py + m[8] * pz
        if (penDown) {
            val wasFront = penZ >= 0f
            val front = z >= 0f
            if (wasFront && front) {
                pen.lineTo(c.x + x * r, c.y - y * r)
            } else if (wasFront != front) {
                // Where it crosses the rim: on the line between the points, pushed out onto the circle.
                val t = penZ / (penZ - z)
                var rx = penX + (x - penX) * t
                var ry = penY + (y - penY) * t
                val l = hypot(rx, ry)
                if (l > 0f) { rx /= l; ry /= l }
                if (wasFront) {
                    pen.lineTo(c.x + rx * r, c.y - ry * r)
                } else {
                    pen.moveTo(c.x + rx * r, c.y - ry * r)
                    pen.lineTo(c.x + x * r, c.y - y * r)
                }
            }
        } else if (z >= 0f) {
            pen.moveTo(c.x + x * r, c.y - y * r)
        }
        penX = x; penY = y; penZ = z
        penDown = true
    }

    /** Adds to [pen] the front edges of [Icosphere], turned by [m], ball radius [r] at [c]. */
    private fun addIcosphere(c: Offset, r: Float) {
        val v = Icosphere.vertices
        val e = Icosphere.edges
        var i = 0
        while (i < e.size) {
            val a = e[i] * 3
            val b = e[i + 1] * 3
            i += 2
            // Each edge as an arc on the ball (a few pieces), so it meets the rim where it crosses it.
            penUp()
            for (k in 0..ICO_PIECES) {
                val s = k / ICO_PIECES.toFloat()
                val px = v[a] + (v[b] - v[a]) * s
                val py = v[a + 1] + (v[b + 1] - v[a + 1]) * s
                val pz = v[a + 2] + (v[b + 2] - v[a + 2]) * s
                val l = kotlin.math.sqrt(px * px + py * py + pz * pz)
                penTo(c, r, px / l, py / l, pz / l)
            }
        }
    }

    /** A mark on the ball's surface at the phone-frame direction ([px], [py], [pz]), shown while it faces the player. */
    private fun DrawScope.sphereDot(c: Offset, r: Float, px: Float, py: Float, pz: Float, color: Color) {
        val z = m[6] * px + m[7] * py + m[8] * pz
        if (z <= 0f) return
        val x = c.x + (m[0] * px + m[1] * py + m[2] * pz) * r
        val y = c.y - (m[3] * px + m[4] * py + m[5] * pz) * r
        // Foreshortened toward the rim.
        drawCircle(color, r * 0.1f * (0.4f + 0.6f * z), Offset(x, y))
    }

    private fun DrawScope.drawRing(box: Rect, live: Int, letters: Color, colors: Array<Color>) {
        val r = minOf(box.width, box.height) / 2f * 0.8f
        val c = box.center
        val w = r * 0.28f
        val tl = Offset(c.x - r, c.y - r)
        for ((i, a) in RING_AXES.withIndex()) {
            val on = live and MappingEngine.gyroBit(a) != 0
            drawArc(
                colors[i].copy(alpha = colors[i].alpha * (if (on) 1f else 0.18f)),
                startAngle = -90f - 55f + i * 120f, sweepAngle = 110f, useCenter = false,
                topLeft = tl, size = Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round),
            )
            // Each arc's letter: Y, P, R.
            val mid = Math.toRadians((-90.0 + i * 120.0))
            text.textSize = r * 0.55f
            text.color = letters.copy(alpha = letters.alpha * (if (on) 1f else 0.35f)).toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                RING_LETTERS[i], c.x + (r * 0.45f * cos(mid)).toFloat(),
                c.y + (r * 0.45f * sin(mid)).toFloat() + text.textSize * 0.35f, text,
            )
        }
    }

    private fun DrawScope.drawGraph(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        val radius = CornerRadius(8 * density)
        drawRoundRect(col(GyroDisplayColor.GRAPH_BACK), box.topLeft, box.size, radius)
        val mid = box.center.y
        drawLine(Color.White.copy(alpha = 0.2f * d.opacity), Offset(box.left, mid), Offset(box.right, mid), density)
        val trace = feed?.trace ?: return
        val want = (d.graphSeconds * trace.rateHz).coerceIn(2, traceYaw.size)
        val n = trace.read(want, traceYaw, tracePitch, traceRoll, traceHeld)
        if (n < 2) return
        // The newest bucket sits at the right edge; a part-full window starts mid-way.
        val step = box.width / (want - 1)
        val x0 = box.right - (n - 1) * step
        val held = col(GyroDisplayColor.GRAPH_HELD)
        var j = 0
        while (j < n) {
            if (!traceHeld[j]) { j++; continue }
            val s = j
            while (j < n && traceHeld[j]) j++
            drawRect(held, Offset(x0 + s * step, box.top), Size(maxOf(step, (j - s) * step), box.height))
        }
        val range = d.graphRange.coerceAtLeast(30).toFloat()
        val half = box.height / 2 * 0.92f
        val lines = arrayOf(GyroDisplayColor.GRAPH_YAW, GyroDisplayColor.GRAPH_PITCH, GyroDisplayColor.GRAPH_ROLL)
        for ((i, data) in arrayOf(traceYaw, tracePitch, traceRoll).withIndex()) {
            path.reset()
            for (k in 0 until n) {
                val y = mid - (data[k] / range).coerceIn(-1f, 1f) * half
                if (k == 0) path.moveTo(x0, y) else path.lineTo(x0 + k * step, y)
            }
            drawPath(path, col(lines[i]), style = Stroke(1.6f * density, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawHorizon(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        // The tape on top, the dial under it: fit both in the box.
        val r = minOf(box.width / 2f, box.height / (2f + TAPE + TAPE_GAP)) * 0.97f
        val tape = r * TAPE
        val gap = r * TAPE_GAP
        val c = Offset(box.center.x, box.center.y + (tape + gap) / 2)
        val roll = horizonRollDeg()
        val pitch = pitchDeg()
        val k = r / 40f // px per degree of pitch
        path.reset()
        path.addOval(Rect(c, r))
        val line = col(GyroDisplayColor.HORIZON_LINE)
        val ladder = col(GyroDisplayColor.HORIZON_LADDER)
        clipPath(path) {
            rotate(roll, c) {
                val hy = c.y + pitch * k
                drawRect(col(GyroDisplayColor.HORIZON_SKY), Offset(c.x - 2 * r, hy - 4 * r), Size(4 * r, 4 * r))
                drawRect(col(GyroDisplayColor.HORIZON_GROUND), Offset(c.x - 2 * r, hy), Size(4 * r, 4 * r))
                drawLine(line, Offset(c.x - 2 * r, hy), Offset(c.x + 2 * r, hy), 1.5f * density)
                // Pitch ladder every 10°.
                var a = -60
                while (a <= 60) {
                    if (a != 0) {
                        val y = hy - a * k
                        val w = if (a % 20 == 0) r * 0.4f else r * 0.22f
                        drawLine(ladder, Offset(c.x - w, y), Offset(c.x + w, y), density)
                    }
                    a += 10
                }
            }
        }
        drawCircle(col(GyroDisplayColor.HORIZON_RIM), r, c, style = Stroke(1.5f * density))
        // The fixed aircraft symbol.
        val sym = col(GyroDisplayColor.HORIZON_AIRCRAFT)
        drawLine(sym, Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.18f, c.y), 2.5f * density, StrokeCap.Round)
        drawLine(sym, Offset(c.x + r * 0.18f, c.y), Offset(c.x + r * 0.55f, c.y), 2.5f * density, StrokeCap.Round)
        drawCircle(sym, 2.5f * density, c)
        // Heading tape: ticks every 10°, numbers every 30°, the heading under the middle mark.
        val tTop = c.y - r - gap - tape
        val tRect = Rect(c.x - r, tTop, c.x + r, tTop + tape)
        drawRoundRect(col(GyroDisplayColor.HORIZON_TAPE), tRect.topLeft, tRect.size, CornerRadius(4 * density))
        val heading = (headingDeg() + 360f) % 360f
        val pxPerDeg = r / 45f
        val numbers = col(GyroDisplayColor.HORIZON_TEXT)
        text.textSize = tape * 0.48f
        text.color = numbers.toArgb()
        clipRect(tRect.left, tRect.top, tRect.right, tRect.bottom) {
            var deg = (heading / 10f).roundToInt() * 10 - 50
            while (deg <= heading + 50) {
                val x = c.x + (deg - heading) * pxPerDeg
                val big = deg % 30 == 0
                drawLine(
                    numbers.copy(alpha = numbers.alpha * 0.8f),
                    Offset(x, tRect.bottom), Offset(x, tRect.bottom - tape * (if (big) 0.35f else 0.2f)), density,
                )
                if (big) {
                    drawContext.canvas.nativeCanvas.drawText("${((deg % 360) + 360) % 360}", x, tRect.top + tape * 0.5f, text)
                }
                deg += 10
            }
        }
        drawLine(sym, Offset(c.x, tRect.top), Offset(c.x, tRect.bottom), 2 * density)
    }

    fun drawEdge(scope: DrawScope, runtime: GamepadRuntime, v: TouchVisuals) = with(scope) {
        val op = v.gyroOpacity.coerceIn(0.05f, 1f)
        val live = runtime.gyroLiveAxes
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
    fun drawOutline(scope: DrawScope, m: FloatArray, at: Int, path: Path, c: Offset, size: Float, color: Color) = with(scope) {
        place(c, size)
        ring(m, body, DEPTH, front, at)
        path.outline(front)
        drawPath(path, color, style = Stroke(width = density, join = StrokeJoin.Round))
    }

    /** The phone turned by [m]; [axes]: arrow colours for its right, up and screen axes, or none. */
    fun draw(
        scope: DrawScope, m: FloatArray, path: Path, c: Offset, size: Float,
        frontColor: Color, backColor: Color, sideColor: Color, edge: Color, axes: Array<Color>?,
    ) = with(scope) {
        place(c, size)
        ring(m, body, DEPTH, front)
        ring(m, body, -DEPTH, back)
        val line = Stroke(width = density * 1.5f, join = StrokeJoin.Round)
        val thin = Stroke(width = density * 1f, join = StrokeJoin.Round)
        fun Color.shade(f: Float) = copy(alpha = alpha * (0.45f + 0.55f * f))
        // How squarely the front faces the player (the screen normal's z); the back's is minus it.
        val facing = m[8]
        if (-facing > 0.01f) {
            path.outline(back)
            drawPath(path, backColor.shade(-facing))
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
            drawPath(path, sideColor.shade(f))
        }
        if (facing > 0.01f) {
            path.outline(front)
            drawPath(path, frontColor.shade(facing))
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
        if (axes != null) {
            project(m, 0f, 0f, 0f)
            val o = Offset(p[0], p[1])
            for (a in 0..2) {
                project(m, if (a == 0) AXIS_LEN else 0f, if (a == 1) AXIS_LEN else 0f, if (a == 2) AXIS_LEN else 0f)
                val tip = Offset(p[0], p[1])
                drawLine(axes[a], o, tip, 2f * density, StrokeCap.Round)
                drawCircle(axes[a], 3f * density, tip)
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

/** The heading tape's height and its gap to the dial, in dial radii. */
private const val TAPE = 0.3f
private const val TAPE_GAP = 0.12f

/** An icosahedron split once (42 points on the unit ball, 120 edges), poles on the phone's up axis. */
private object Icosphere {
    val vertices: FloatArray
    val edges: IntArray

    init {
        val t = ((1 + Math.sqrt(5.0)) / 2).toFloat()
        val pts = ArrayList<FloatArray>()
        fun add(x: Float, y: Float, z: Float): Int {
            val l = kotlin.math.sqrt(x * x + y * y + z * z)
            pts += floatArrayOf(x / l, y / l, z / l)
            return pts.size - 1
        }
        // The 12 corners: (0, ±1, ±t) and its cyclic turns, then tipped so two corners sit on the y axis.
        for ((a, b) in listOf(-1f to t, 1f to t, -1f to -t, 1f to -t)) {
            add(a, b, 0f); add(0f, a, b); add(b, 0f, a)
        }
        val tip = atan2(1.0, t.toDouble()).toFloat()
        for (p in pts) {
            val x = p[0]
            val y = p[1]
            p[0] = x * cos(tip) - y * sin(tip)
            p[1] = x * sin(tip) + y * cos(tip)
        }
        // The 20 faces: every three corners all an edge apart.
        val edge = 2 / kotlin.math.sqrt(1 + t * t)
        fun near(i: Int, j: Int): Boolean {
            val a = pts[i]
            val b = pts[j]
            return kotlin.math.abs(hypot(hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2]) - edge) < 1e-3f
        }
        val faces = ArrayList<IntArray>()
        for (i in 0 until 12) for (j in i + 1 until 12) for (k in j + 1 until 12) {
            if (near(i, j) && near(j, k) && near(i, k)) faces += intArrayOf(i, j, k)
        }
        // Split each face in four, sharing the new middle points between faces.
        val mids = HashMap<Long, Int>()
        fun mid(i: Int, j: Int): Int = mids.getOrPut(minOf(i, j).toLong() * 1000 + maxOf(i, j)) {
            add(pts[i][0] + pts[j][0], pts[i][1] + pts[j][1], pts[i][2] + pts[j][2])
        }
        val lines = HashSet<Long>()
        fun line(i: Int, j: Int) { lines += minOf(i, j).toLong() * 1000 + maxOf(i, j) }
        for (f in faces) {
            val ab = mid(f[0], f[1])
            val bc = mid(f[1], f[2])
            val ca = mid(f[2], f[0])
            for ((p, q, s) in listOf(Triple(f[0], ab, ca), Triple(ab, f[1], bc), Triple(ca, bc, f[2]), Triple(ab, bc, ca))) {
                line(p, q); line(q, s); line(s, p)
            }
        }
        vertices = FloatArray(pts.size * 3).also { out -> pts.forEachIndexed { i, p -> p.copyInto(out, i * 3) } }
        edges = IntArray(lines.size * 2).also { out ->
            lines.forEachIndexed { i, k -> out[i * 2] = (k / 1000).toInt(); out[i * 2 + 1] = (k % 1000).toInt() }
        }
    }
}

/** Pieces per icosphere edge. */
private const val ICO_PIECES = 6

/** Points round each line on the 3D ball. */
private const val SPHERE_STEPS = 96

private const val GHOSTS = 6
private const val GHOST_MS = 45L

/** The edge's swing per degree, its furthest swing, each layer's step inwards and the outer corner radius. */
private const val EDGE_PX_PER_DEG = 1.5f
private const val EDGE_MAX_DP = 80f
private const val EDGE_GAP_DP = 10f
private const val EDGE_CORNER_DP = 34f

private val SELECTED_BOX = Color(0xFFFFC94D)

/** Yaw, pitch, roll: the edge's tint. */
private val AXIS_COLORS = arrayOf(Color(0xFFFF5A5A), Color(0xFF5ADB6E), Color(0xFF5AA8FF))
private val RING_AXES = arrayOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)
private val RING_LETTERS = arrayOf("Y", "P", "R")
