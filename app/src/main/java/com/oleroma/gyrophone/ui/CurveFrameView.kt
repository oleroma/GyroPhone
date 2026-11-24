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
import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.model.CurveFrame
import com.oleroma.gyrophone.core.model.CurvePoint
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroCurves
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import com.oleroma.gyrophone.core.model.GameSens
import com.oleroma.gyrophone.core.model.GyroSettings
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** The graph's inset at a curve frame's sides (dp), so points at its edges can still be grabbed. */
private const val CURVE_SIDE_DP = 12f

/** The bands above and below the graph (dp), for the labels there: nothing is drawn over another. */
private const val CURVE_BAND_DP = 16f

/** The frame's text size (dp, so it fits its bands whatever the font scale). */
private const val CURVE_TEXT_DP = 10f

/** How near (dp) a finger must land to a point to take it. */
private val CURVE_GRAB = 28.dp

/** Held this long (ms) without moving on an unlocked frame: a point there goes, or one comes on an empty spot. */
private const val CURVE_REMOVE_MS = 500L

/** The live speed's poll interval in play (ms) and the change (°/s) it must make to redraw. */
private const val CURVE_LIVE_MS = 50L
private const val CURVE_LIVE_STEP = 1f

/** The graph's area inside a frame drawn over [r], at [pxPerDp]: in from the sides, a label band above and below. */
private fun graphArea(r: Rect, pxPerDp: Float): Rect {
    val side = CURVE_SIDE_DP * pxPerDp
    val band = CURVE_BAND_DP * pxPerDp
    return Rect(r.left + side, r.top + band, r.right - side, r.bottom - band)
}

/** A multiplier, short: "1×", "1.5×", "2.25×". */
private fun mult(gain: Float): String = "%.2f".format(gain).trimEnd('0').trimEnd('.', ',') + "×"

/** What [gain] gives at [sens], without its unit (the frame's corner names it once). */
private fun shortValue(gain: Float, sens: Float, counts: Float): String =
    if (counts > 0f) "%.2f".format(GameSens.rws(sens * gain, counts)) else "%.1f".format(sens * gain)

/** Where point ([speed], [gain]) of frame [f] sits in graph area [g]. */
private fun at(f: CurveFrame, g: Rect, speed: Float, gain: Float): Offset = Offset(
    g.left + speed / f.speedCap * g.width,
    g.top + yOfGain(gain, f.gainCap) * g.height,
)

/**
 * The curve frames' editing in play ([com.oleroma.gyrophone.core.model.TouchKind.CURVE]). A frame
 * starts locked, so a stray touch can't change its curve: a tap on an empty spot unlocks it, another
 * locks it again ([unlocked], for this play session only). Unlocked: a drag moves a point (the gyro
 * takes the curve at once, [com.oleroma.gyrophone.core.engine.MappingEngine.setFrameCurve]), a hold
 * on a point removes it and a hold near the curve adds one there, both as soon as the hold is long
 * enough, not on the lift. A moved curve is saved when the finger lifts. A frame takes its fingers
 * for itself: nothing else sees them. [drafts] holds a frame's curve while a finger changes it, for drawing.
 */
internal class CurveFrameTouches(private val runtime: GamepadRuntime) {
    val drafts = mutableStateMapOf<String, GyroCurve>()

    /** The point a finger is dragging now, by frame id: its frame shows where it will land. */
    val dragging = mutableStateMapOf<String, Int>()

    /** The frames unlocked for editing now, by id (every frame starts locked). */
    val unlocked = mutableStateMapOf<String, Boolean>()

    private class Touch(
        val id: String,
        val frame: CurveFrame,
        val graph: Rect,
        val point: Int,
        val down: Offset,
        val open: Boolean,
        /** Landed near the curve (not on a point): a hold adds a point there. */
        val nearCurve: Boolean,
    ) {
        var moved = false

        /** The hold already did its work (a point removed or added): the lift does nothing more. */
        var done = false
        var curve: GyroCurve? = null
        var hold: Runnable? = null
    }

    private val touches = HashMap<Long, Touch>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A finger [pointer] lands at [p] (px) on frame [c] drawn over [rect], at [atNs]. */
    @Suppress("UNUSED_PARAMETER")
    fun down(pointer: Long, c: TouchControl, rect: Rect, p: Offset, atNs: Long, pxPerDp: Float) {
        // The touch handler keeps its controls until their places change: the frame's range and curve
        // come from the profile in play now (a saved edit or a new range doesn't restart the handler).
        val fresh = runtime.activeProfile.value.workspace(runtime.workspaceIndex.value).touchControls.firstOrNull { it.id == c.id }
        val f = (fresh ?: c).curveFrame ?: return
        // One finger edits a frame at a time: a second one on it does nothing.
        if (touches.values.any { it.id == c.id }) return
        val g = graphArea(rect, pxPerDp)
        val curve = drafts[c.id] ?: f.inRange()
        val open = unlocked[c.id] == true
        var best = -1
        if (open) {
            var bestD = CURVE_GRAB.value * pxPerDp
            curve.points.forEachIndexed { i, q ->
                val o = at(f, g, q.speed, q.gain)
                val d = hypot(o.x - p.x, o.y - p.y)
                if (d < bestD) { bestD = d; best = i }
            }
        }
        // Near the curve: within the grab distance of it, straight up or down from the finger.
        val near = open && best < 0 && curve.points.isNotEmpty() && run {
            val speed = ((p.x - g.left) / g.width * f.speedCap).coerceIn(0f, f.speedCap)
            abs(at(f, g, speed, curve.gainAt(speed)).y - p.y) < CURVE_GRAB.value * pxPerDp
        }
        val t = Touch(c.id, f, g, best, p, open, near).also { it.curve = curve }
        touches[pointer] = t
        if (open && (best >= 0 || near)) {
            // Held still long enough: a point goes, or one comes on an empty spot, at once.
            val r = Runnable { held(pointer, t) }
            t.hold = r
            main.postDelayed(r, CURVE_REMOVE_MS)
        }
    }

    private fun held(pointer: Long, t: Touch) {
        if (touches[pointer] !== t || t.moved || t.done) return
        val c = t.curve ?: return
        val f = t.frame
        val result = when {
            t.point >= 0 -> if (c.points.size > 1) c.copy(points = c.points.filterIndexed { j, _ -> j != t.point }) else null
            t.nearCurve && c.points.size < GyroCurve.MAX_POINTS -> {
                val g = t.graph
                c.copy(points = c.points + CurvePoint(
                    ((t.down.x - g.left) / g.width * f.speedCap).coerceIn(0f, f.speedCap),
                    (1f + (1f - (t.down.y - g.top) / g.height) * (f.gainCap - 1f)).coerceIn(GyroCurve.MIN_GAIN, f.gainCap),
                ))
            }
            else -> null
        } ?: return
        t.done = true
        runtime.saveCurveFrame(t.id, f.inRange(result))
        drafts.remove(t.id)
    }

    /** Finger [pointer] moved to [p]: a taken point follows it, within its neighbours and the frame's range. */
    fun move(pointer: Long, p: Offset, slopPx: Float) {
        val t = touches[pointer] ?: return
        if (t.done) return
        if (!t.moved && (p - t.down).getDistance() <= slopPx) return
        if (!t.moved && t.point >= 0) dragging[t.id] = t.point
        t.moved = true
        t.hold?.let { main.removeCallbacks(it) }
        val i = t.point
        val c = t.curve ?: return
        if (!t.open || i < 0 || i >= c.points.size) return
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

    /** Finger [pointer] lifted at [atNs] ([cancelled]: the system took it, nothing changes). */
    @Suppress("UNUSED_PARAMETER")
    fun up(pointer: Long, atNs: Long, cancelled: Boolean) {
        val t = touches.remove(pointer) ?: return
        t.hold?.let { main.removeCallbacks(it) }
        dragging.remove(t.id)
        if (t.done) return
        val c = t.curve ?: return
        when {
            cancelled -> {}
            // A moved point: saved where it was let go.
            t.open && t.point >= 0 && t.moved -> {
                runtime.saveCurveFrame(t.id, t.frame.inRange(c))
                drafts.remove(t.id)
                return
            }
            // A tap or hold on an empty spot (a hold near the curve adds a point instead): lock or unlock the frame.
            t.point < 0 && !t.moved -> if (t.open) unlocked.remove(t.id) else unlocked[t.id] = true
        }
        if (t.id in drafts) { drafts.remove(t.id); runtime.engine.setFrameCurve(t.id, null) }
    }

    /** Every finger lets go without saving (the handler restarts, the pad hides). */
    fun cancelAll() {
        for (t in touches.values) {
            t.hold?.let { main.removeCallbacks(it) }
            runtime.engine.setFrameCurve(t.id, null)
        }
        touches.clear()
        drafts.clear()
        dragging.clear()
    }
}

/**
 * A curve frame's graph, drawn over its button-like shape: across, turning speed to its range;
 * up, the multiplier from 1× to its top. Each line's label sits just under it: its multiplier and,
 * with [CurveFrame.showValues], what it gives at the sensitivity in effect now (a held button's or a
 * slider's included), the unit named once in the top right corner ("1× = 10.00 RWS"). The curve as
 * the gyro uses it (Smooth included), its points (hollow while locked; filled, with a border round the
 * frame, while [unlocked]), a tooltip on a point being dragged ([dragPoint])
 * with where it will land, and in play the live speed line and what it gives now (top left). Grayed
 * while the frame's curve isn't in effect (a held button with its own curve, or a newer frame on its
 * axis). Redrawn on its own: the live parts only redraw (no recomposition), at most every [CURVE_LIVE_MS].
 */
@Composable
internal fun CurveFrameGraph(
    runtime: GamepadRuntime,
    control: TouchControl,
    curve: GyroCurve,
    dragPoint: Int?,
    unlocked: Boolean,
    counts: Float,
    live: Boolean,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier = Modifier,
) {
    val f = control.curveFrame ?: return
    val axis = f.axis
    // All axes: the axis that turns the camera sideways (a slider on it, or on every axis, shows).
    fun sensOf(gs: GyroSettings) =
        if (axis == MotionAxis.NONE) GyroDisplayFeed.sideSensOf(runtime.engine.gyroRoutes(), gs) else abs(gs.sensitivityOf(axis))
    val speed = remember { mutableFloatStateOf(-1f) }
    val peak = remember { mutableFloatStateOf(0f) }
    val sens = remember { mutableFloatStateOf(sensOf(runtime.engine.gyroSettings)) }
    val active = remember { mutableStateOf(true) }
    if (live) {
        DisposableEffect(runtime) {
            runtime.watchAccel()
            onDispose { runtime.unwatchAccel() }
        }
        val onScreen by runtime.onScreen.collectAsState()
        // The highest speed starts again each time the frame shows in play.
        LaunchedEffect(runtime) { runtime.resetGyroPeak(); peak.floatValue = 0f }
        LaunchedEffect(runtime, axis, onScreen) {
            while (onScreen) {
                val which = if (f.curve.axisSpeed && axis != MotionAxis.NONE) axis else null
                val v = runtime.liveGyroSpeed(which) ?: -1f
                if (abs(v - speed.floatValue) > CURVE_LIVE_STEP || (v < 0f) != (speed.floatValue < 0f)) speed.floatValue = v
                val pk = runtime.liveGyroPeak(which)
                if (pk - peak.floatValue > CURVE_LIVE_STEP) peak.floatValue = pk
                val gs = runtime.engine.gyroSettings
                val s = sensOf(gs)
                if (s != sens.floatValue) sens.floatValue = s
                val a = runtime.engine.frameInEffect(control.id)
                if (a != active.value) active.value = a
                delay(CURVE_LIVE_MS)
            }
        }
    }
    val isLive by rememberUpdatedState(live)
    val density = LocalDensity.current
    val pxPerDp = density.density
    val textPx = CURVE_TEXT_DP * pxPerDp
    val showValues = f.showValues
    Box(
        modifier.size(widthDp, heightDp)
            .graphicsLayer { alpha = if (!isLive || active.value) 1f else 0.35f }
            .drawWithCache {
                val whole = Rect(0f, 0f, size.width, size.height)
                val g = graphArea(whole, pxPerDp)
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
                    color = Color.White.copy(alpha = 0.8f).toArgb()
                    textSize = textPx
                    setShadowLayer(2f * pxPerDp, 0f, 0f, android.graphics.Color.BLACK)
                }
                val tipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = textPx
                }
                val fm = paint.fontMetrics
                val ascent = -fm.ascent
                val step = gridStep(maxG - 1f)
                // Labels sit under their lines; where lines are too close for that, every second (or more) is labelled.
                val gap = g.height * step / (maxG - 1f)
                val every = ceil((textPx + 3f * pxPerDp) / gap).toInt().coerceAtLeast(1)
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                val stepX = if (maxS > 400f) 100f else 50f
                val pad = 4f * pxPerDp
                // The bands' baselines: the top band's text and the bottom band's.
                val topBase = (g.top - pad / 2f).coerceAtLeast(ascent)
                val bottomBase = g.bottom + ascent + pad / 2f
                val rightText = "%.0f°/s".format(maxS)
                onDrawBehind {
                    val s = sens.floatValue
                    fun label(gain: Float) = if (showValues) mult(gain) + " " + shortValue(gain, s, counts) else mult(gain)
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
                    val nc = drawContext.canvas.nativeCanvas
                    // Bottom band: 1× under its line at the left, the range's end at the right (if both fit).
                    val one = label(1f)
                    val oneW = paint.measureText(one)
                    nc.drawText(one, g.left, bottomBase, paint)
                    val rw = paint.measureText(rightText)
                    if (g.left + oneW + pad < g.right - rw) nc.drawText(rightText, g.right - rw, bottomBase, paint)
                    // The other lines, each just under its line.
                    var k = every
                    while (1f + k * step < maxG + 0.001f) {
                        val ly = 1f + k * step
                        nc.drawText(label(ly), g.left + 2f * pxPerDp, y(ly) + ascent + 2f * pxPerDp, paint)
                        k += every
                    }
                    // Top band: the live speed at the left, the unit once at the right (dropped if they'd meet).
                    val v = speed.floatValue
                    val nowW = if (v >= 0f) {
                        val gain = curve.gainAt(v)
                        val now = "%.0f°/s ".format(v) + label(gain)
                        nc.drawText(now, g.left, topBase, paint)
                        paint.measureText(now)
                    } else 0f
                    if (showValues) {
                        val unit = if (counts > 0f) "1× = " + shortValue(1f, s, counts) + " RWS" else "1× = %.1f".format(s)
                        val uw = paint.measureText(unit)
                        if (g.left + nowW + pad < g.right - uw) nc.drawText(unit, g.right - uw, topBase, paint)
                    }
                    // The highest speed reached: the live line's yellow, dimmer and dashed, its value at the bottom of the graph.
                    val pk = peak.floatValue
                    if (pk > 0f) {
                        val px = x(pk.coerceAtMost(maxS))
                        drawLine(Color(0x66FFC857), Offset(px, g.top), Offset(px, g.bottom), 2f, pathEffect = dash)
                        val t = "max %.0f°/s".format(pk)
                        val tw = paint.measureText(t)
                        val tx = (px - tw - 3f * pxPerDp).let { if (it < g.left) px + 3f * pxPerDp else it }.coerceAtMost(g.right - tw)
                        val dim = paint.color
                        paint.color = Color.White.copy(alpha = 0.45f).toArgb()
                        nc.drawText(t, tx, g.bottom - 3f * pxPerDp, paint)
                        paint.color = dim
                    }
                    drawPath(path, Color(0xFF7CC4FF), style = Stroke(width = 4f))
                    // Locked: hollow points. Unlocked: filled points and a border round the frame.
                    for (q in pts) {
                        if (unlocked) drawCircle(Color(0xFF7CC4FF), 9f, Offset(x(q.speed), y(q.gain)))
                        else drawCircle(Color(0xFF7CC4FF), 7f, Offset(x(q.speed), y(q.gain)), style = Stroke(width = 2.5f))
                    }
                    if (unlocked) {
                        val b = 1.5f * pxPerDp
                        drawRoundRect(
                            Color(0xFF7CC4FF), topLeft = Offset(b, b), size = Size(size.width - 2 * b, size.height - 2 * b),
                            cornerRadius = CornerRadius(6f * pxPerDp), style = Stroke(width = 2 * b),
                        )
                    }
                    if (v >= 0f) {
                        val lx = x(v.coerceAtMost(maxS))
                        drawLine(Color(0xFFFFC857), Offset(lx, g.top), Offset(lx, g.bottom), 2f)
                        drawCircle(Color(0xFFFFC857), 7f, Offset(lx, y(curve.gainAt(v))))
                    }
                    // A point being dragged: a small tooltip with where it lands, kept inside the frame.
                    val q = dragPoint?.let { pts.getOrNull(it) }
                    if (q != null) {
                        val o = Offset(x(q.speed), y(q.gain))
                        drawCircle(Color.White, 12f, o, style = Stroke(width = 3f))
                        val tip = "%.0f°/s · ".format(q.speed) + label(q.gain)
                        val tw = tipPaint.measureText(tip) + 2 * pad
                        val th = textPx + 2 * pad
                        val lift = 18f * pxPerDp
                        val left = (o.x - tw / 2f).coerceIn(0f, max(0f, size.width - tw))
                        val above = o.y - lift - th
                        val top = if (above >= 0f) above else (o.y + lift).coerceAtMost(max(0f, size.height - th))
                        drawRoundRect(
                            Color(0xE6202428),
                            topLeft = Offset(left, top),
                            size = Size(tw, th),
                            cornerRadius = CornerRadius(6f * pxPerDp),
                        )
                        nc.drawText(tip, left + pad, top + pad + ascent - (ascent - textPx) / 2f, tipPaint)
                    }
                }
            },
    )
}
