package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oleroma.gyrophone.core.model.CurveFrame
import com.oleroma.gyrophone.core.model.CurvePoint
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroCurves
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** The graph's inset inside a curve frame, so points at its edges can still be grabbed. */
internal val CURVE_FRAME_INSET = 12.dp

/** How near (dp) a finger must land to a point to take it. */
private val CURVE_GRAB = 28.dp

/** Held this long (ms) on a point without moving, then lifted: the point goes. */
private const val CURVE_REMOVE_MS = 500L

/** The live speed's poll interval in play (ms) and the change (°/s) it must make to redraw. */
private const val CURVE_LIVE_MS = 50L
private const val CURVE_LIVE_STEP = 1f

/** The graph's area inside a frame drawn over [r], [inset] px in from each side. */
private fun graphArea(r: Rect, inset: Float): Rect = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)

/** Where point ([speed], [gain]) of frame [f] sits in graph area [g]. */
private fun at(f: CurveFrame, g: Rect, speed: Float, gain: Float): Offset = Offset(
    g.left + speed / f.speedCap * g.width,
    g.top + yOfGain(gain, f.gainCap) * g.height,
)

/**
 * The curve frames' editing in play ([com.oleroma.gyrophone.core.model.TouchKind.CURVE]): a tap
 * adds a point, a drag moves one (the gyro takes the curve at once, [com.oleroma.gyrophone.core.engine.MappingEngine.setFrameCurve]),
 * a hold on a point then a lift removes it. The curve is saved when the finger lifts. A frame
 * takes its fingers for itself: nothing else sees them. [drafts] holds a frame's curve while a
 * finger changes it, for drawing.
 */
internal class CurveFrameTouches(private val runtime: GamepadRuntime) {
    val drafts = mutableStateMapOf<String, GyroCurve>()

    private class Touch(
        val id: String,
        val frame: CurveFrame,
        val graph: Rect,
        val point: Int,
        val downNs: Long,
        val down: Offset,
    ) {
        var moved = false
        var curve: GyroCurve? = null
    }

    private val touches = HashMap<Long, Touch>()

    /** A finger [pointer] lands at [p] (px) on frame [c] drawn over [rect], at [atNs]. */
    fun down(pointer: Long, c: TouchControl, rect: Rect, p: Offset, atNs: Long, pxPerDp: Float) {
        // The touch handler keeps its controls until their places change: the frame's range and curve
        // come from the profile in play now (a saved edit or a new range doesn't restart the handler).
        val fresh = runtime.activeProfile.value.workspace(runtime.workspaceIndex.value).touchControls.firstOrNull { it.id == c.id }
        val f = (fresh ?: c).curveFrame ?: return
        // One finger edits a frame at a time: a second one on it does nothing.
        if (touches.values.any { it.id == c.id }) return
        val g = graphArea(rect, CURVE_FRAME_INSET.value * pxPerDp)
        val curve = drafts[c.id] ?: f.inRange()
        var best = -1
        var bestD = CURVE_GRAB.value * pxPerDp
        curve.points.forEachIndexed { i, q ->
            val o = at(f, g, q.speed, q.gain)
            val d = hypot(o.x - p.x, o.y - p.y)
            if (d < bestD) { bestD = d; best = i }
        }
        touches[pointer] = Touch(c.id, f, g, best, atNs, p).also { it.curve = curve }
    }

    /** Finger [pointer] moved to [p]: a taken point follows it, within its neighbours and the frame's range. */
    fun move(pointer: Long, p: Offset, slopPx: Float) {
        val t = touches[pointer] ?: return
        if (!t.moved && (p - t.down).getDistance() <= slopPx) return
        t.moved = true
        val i = t.point
        val c = t.curve ?: return
        if (i < 0 || i >= c.points.size) return
        val f = t.frame
        val ps = c.points
        val lo = if (i > 0) ps[i - 1].speed + GyroCurve.MIN_GAP else 0f
        val hi = if (i < ps.size - 1) ps[i + 1].speed - GyroCurve.MIN_GAP else f.speedCap
        val g = t.graph
        val np = CurvePoint(
            ((p.x - g.left) / g.width * f.speedCap).coerceIn(lo, max(lo, hi)),
            (1f + (1f - (p.y - g.top) / g.height) * (f.gainCap - 1f)).coerceIn(GyroCurve.MIN_GAIN, f.gainCap),
        ).quantized()
        if (np == ps[i]) return
        val next = c.copy(points = ps.mapIndexed { j, q -> if (j == i) np else q })
        t.curve = next
        drafts[t.id] = next
        runtime.engine.setFrameCurve(t.id, next)
    }

    /** Finger [pointer] lifted at [atNs] at [p] ([cancelled]: the system took it, nothing changes). */
    fun up(pointer: Long, atNs: Long, cancelled: Boolean) {
        val t = touches.remove(pointer) ?: return
        val c = t.curve ?: return
        val f = t.frame
        val result: GyroCurve? = when {
            cancelled -> null
            t.point >= 0 && t.moved -> c
            // A hold on a point, then a lift: it goes (the last one stays).
            t.point >= 0 && atNs - t.downNs >= CURVE_REMOVE_MS * 1_000_000L ->
                if (c.points.size > 1) c.copy(points = c.points.filterIndexed { j, _ -> j != t.point }) else null
            // A tap on an empty spot: a new point there.
            t.point < 0 && !t.moved && c.points.size < GyroCurve.MAX_POINTS -> {
                val g = t.graph
                val q = CurvePoint(
                    ((t.down.x - g.left) / g.width * f.speedCap).coerceIn(0f, f.speedCap),
                    (1f + (1f - (t.down.y - g.top) / g.height) * (f.gainCap - 1f)).coerceIn(GyroCurve.MIN_GAIN, f.gainCap),
                )
                c.copy(points = c.points + q)
            }
            else -> null
        }
        if (result == null) {
            if (t.id in drafts) { drafts.remove(t.id); runtime.engine.setFrameCurve(t.id, null) }
            return
        }
        val saved = f.inRange(result)
        runtime.saveCurveFrame(t.id, saved)
        drafts.remove(t.id)
    }

    /** Every finger lets go without saving (the handler restarts, the pad hides). */
    fun cancelAll() {
        for (t in touches.values) runtime.engine.setFrameCurve(t.id, null)
        touches.clear()
        drafts.clear()
    }
}

/**
 * A curve frame's graph, drawn over its button-like shape: across, turning speed to its range;
 * up, the multiplier from 1× to its top, each line labelled with what it gives at the sensitivity
 * in effect now; the curve as the gyro uses it (Smooth included), its points, and in play the
 * live speed line with what it gives now. Redrawn on its own: the live line only redraws (no
 * recomposition), at most every [CURVE_LIVE_MS].
 */
@Composable
internal fun CurveFrameGraph(
    runtime: GamepadRuntime,
    control: TouchControl,
    curve: GyroCurve,
    counts: Float,
    live: Boolean,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier = Modifier,
) {
    val f = control.curveFrame ?: return
    val axis = f.axis
    val speed = remember { mutableFloatStateOf(-1f) }
    val sens = remember { mutableFloatStateOf(runtime.engine.gyroSettings.let { if (axis == MotionAxis.NONE) it.sensitivity else it.sensitivityOf(axis) }) }
    if (live) {
        DisposableEffect(runtime) {
            runtime.watchAccel()
            onDispose { runtime.unwatchAccel() }
        }
        val onScreen by runtime.onScreen.collectAsState()
        LaunchedEffect(runtime, axis, onScreen) {
            while (onScreen) {
                val v = runtime.liveGyroSpeed(if (f.curve.axisSpeed && axis != MotionAxis.NONE) axis else null) ?: -1f
                if (abs(v - speed.floatValue) > CURVE_LIVE_STEP || (v < 0f) != (speed.floatValue < 0f)) speed.floatValue = v
                val gs = runtime.engine.gyroSettings
                val s = if (axis == MotionAxis.NONE) gs.sensitivity else gs.sensitivityOf(axis)
                if (s != sens.floatValue) sens.floatValue = s
                delay(CURVE_LIVE_MS)
            }
        }
    }
    val density = LocalDensity.current
    val textPx = with(density) { 10.sp.toPx() }
    val inset = with(density) { CURVE_FRAME_INSET.toPx() }
    Box(
        modifier.size(widthDp, heightDp).drawWithCache {
            val g = graphArea(Rect(0f, 0f, size.width, size.height), inset)
            val maxS = f.speedCap
            val maxG = f.gainCap
            fun x(s: Float) = g.left + s / maxS * g.width
            fun y(v: Float) = g.top + yOfGain(v, maxG) * g.height
            val path = Path()
            val pts = curve.points
            if (pts.isNotEmpty()) {
                path.moveTo(g.left, y(pts.first().gain))
                if (curve.smooth) {
                    val flat = curve.flat()
                    var px = 0f
                    while (px <= g.width) {
                        path.lineTo(g.left + px, y(GyroCurves.gain(flat, px / g.width * maxS, true)))
                        px += 3f
                    }
                } else {
                    for (q in pts) path.lineTo(x(q.speed), y(q.gain))
                }
                path.lineTo(g.right, y(pts.last().gain))
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.White.copy(alpha = 0.75f).toArgb()
                textSize = textPx
            }
            val step = gridStep(maxG - 1f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            val stepX = if (maxS > 400f) 100f else 50f
            onDrawBehind {
                val s = sens.floatValue
                var sx = stepX
                while (sx < maxS) {
                    drawLine(Color.White.copy(alpha = 0.10f), Offset(x(sx), g.top), Offset(x(sx), g.bottom), 1f)
                    sx += stepX
                }
                var gy = 1f + step
                while (gy < maxG + 0.001f) {
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(g.left, y(gy)), Offset(g.right, y(gy)), 1f, pathEffect = dash)
                    gy += step
                }
                drawLine(Color.White.copy(alpha = 0.5f), Offset(g.left, g.bottom), Offset(g.right, g.bottom), 2f)
                drawContext.canvas.nativeCanvas.apply {
                    drawText(gainLabel(1f, s, counts), g.left + 4f, g.bottom - 4f, paint)
                    var ly = 1f + step
                    while (ly < maxG + 0.001f) {
                        drawText(gainLabel(ly, s, counts), g.left + 4f, y(ly) + textPx + 2f, paint)
                        ly += step
                    }
                    val right = "%.0f°/s".format(maxS)
                    drawText(right, g.right - paint.measureText(right), g.bottom + textPx, paint)
                }
                drawPath(path, Color(0xFF7CC4FF), style = Stroke(width = 4f))
                for (q in pts) drawCircle(Color(0xFF7CC4FF), 9f, Offset(x(q.speed), y(q.gain)))
                val v = speed.floatValue
                if (v >= 0f) {
                    val lx = x(v.coerceAtMost(maxS))
                    val gain = curve.gainAt(v)
                    drawLine(Color(0xFFFFC857), Offset(lx, g.top), Offset(lx, g.bottom), 2f)
                    drawCircle(Color(0xFFFFC857), 7f, Offset(lx, y(gain)))
                    val now = "%.0f°/s · ".format(v) + gainLabel(gain, s, counts)
                    drawContext.canvas.nativeCanvas.drawText(now, g.right - paint.measureText(now), g.top + textPx, paint)
                }
            }
        },
    )
}
