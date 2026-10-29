package com.oleroma.gyrophone.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.oleroma.gyrophone.core.engine.SwipeGuide
import com.oleroma.gyrophone.core.model.Direction
import kotlinx.serialization.Serializable
import kotlin.math.hypot

/**
 * How the pad shows touches (app-wide, [com.oleroma.gyrophone.runtime.GamepadRuntime.touchVisuals]).
 * [showTouches]: a dot under each finger that fades out over [fadeMs] once it lifts. [trail]: a
 * line behind each finger, [trailMs] of its path long, whose oldest [trailFade] (0..1) fades out
 * towards its end; it shrinks back to the finger as it ages. [liveMappings]: a button's label
 * briefly shows what it is sending right now. [swipeLine]: a finger on a swipe button with swipes
 * or holds mapped shows a line from its start point, through it, to its direction's furthest
 * activation length, marking each ([SwipeLines]).
 */
@Serializable
data class TouchVisuals(
    val showTouches: Boolean = false,
    val trail: Boolean = false,
    val trailMs: Int = 300,
    val trailFade: Float = 0.6f,
    val fadeMs: Int = 400,
    val liveMappings: Boolean = true,
    val swipeLine: Boolean = true,
) {
    companion object {
        const val MIN_TRAIL_MS = 50
        const val MAX_TRAIL_MS = 1500
        const val MAX_FADE_MS = 2000
    }
}

/** Finger paths for [TouchOverlay], fed by [recordTouches]. Main thread only. */
class TouchTrails {
    private class Point(val x: Float, val y: Float, val t: Long)

    private class Track {
        val points = ArrayDeque<Point>()

        /** Lift time (uptime ms), or -1 while down. */
        var liftT = -1L
    }

    private val tracks = HashMap<Long, Track>()

    /** Something is shown: the overlay redraws every frame while this is true. */
    var active by mutableStateOf(false)
        private set

    fun down(id: Long, p: Offset, now: Long) {
        val t = Track()
        t.points.addLast(Point(p.x, p.y, now))
        tracks[id] = t
        active = true
    }

    fun move(id: Long, p: Offset, now: Long, keepMs: Int) {
        val t = tracks[id] ?: return
        t.points.addLast(Point(p.x, p.y, now))
        while (t.points.size > 1 && now - t.points.first().t > keepMs) t.points.removeFirst()
    }

    fun up(id: Long, now: Long) {
        tracks[id]?.liftT = now
    }

    fun clear() {
        tracks.clear()
        active = false
    }

    /**
     * Draws every track as of [now] with [v] ([dotPx]: the dot's radius) and drops the faded ones;
     * clears [active] once nothing is left.
     */
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, v: TouchVisuals, now: Long, dotPx: Float) {
        val trailMs = v.trailMs.coerceIn(TouchVisuals.MIN_TRAIL_MS, TouchVisuals.MAX_TRAIL_MS)
        val fadeMs = v.fadeMs.coerceIn(0, TouchVisuals.MAX_FADE_MS)
        val fadePart = v.trailFade.coerceIn(0f, 1f)
        val it = tracks.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val pts = t.points
            while (pts.size > 1 && now - pts.first().t > trailMs) pts.removeFirst()
            val lifted = t.liftT >= 0
            // After the lift: the dot fades over fadeMs; the trail keeps aging back to the lift point.
            val fade = if (!lifted) 1f else if (fadeMs == 0) 0f else 1f - (now - t.liftT).toFloat() / fadeMs
            val trailLeft = v.trail && pts.size > 1 && now - pts.first().t <= trailMs
            val dot = v.showTouches && fade > 0f
            if (lifted && !dot && !trailLeft) {
                it.remove(); continue
            }
            if (v.trail) {
                for (i in 1 until pts.size) {
                    val a = pts[i - 1];
                    val b = pts[i]
                    val age = ((now - b.t).toFloat() / trailMs).coerceIn(0f, 1f)
                    val start = 1f - fadePart
                    val k = if (age <= start || fadePart == 0f) 1f else 1f - (age - start) / fadePart
                    if (k <= 0f) continue
                    scope.drawLine(
                        Color.White.copy(alpha = 0.45f * k * (if (lifted) fade.coerceAtLeast(0.35f) else 1f)),
                        Offset(a.x, a.y), Offset(b.x, b.y),
                        strokeWidth = dotPx * 0.9f * (1f - 0.5f * age),
                        cap = StrokeCap.Round,
                    )
                }
            }
            if (dot) {
                val p = pts.last()
                val c = Offset(p.x, p.y)
                scope.drawCircle(Color.White.copy(alpha = 0.3f * fade), dotPx, c)
                scope.drawCircle(Color.White.copy(alpha = 0.8f * fade), dotPx, c, style = Stroke(dotPx * 0.12f))
            }
        }
        if (tracks.isEmpty()) active = false
    }
}

/**
 * Records every finger into [trails] when [enabled], seeing touches before the pad's own handlers
 * and consuming nothing.
 */
fun Modifier.recordTouches(trails: TouchTrails, visuals: TouchVisuals, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(trails, visuals) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val now = SystemClock.uptimeMillis()
                for (ch in event.changes) {
                    val id = ch.id.value
                    when {
                        ch.pressed && !ch.previousPressed -> trails.down(id, ch.position, now)
                        !ch.pressed && ch.previousPressed -> trails.up(id, now)
                        ch.pressed && ch.position != ch.previousPosition ->
                            trails.move(
                                id,
                                ch.position,
                                now,
                                visuals.trailMs.coerceIn(TouchVisuals.MIN_TRAIL_MS, TouchVisuals.MAX_TRAIL_MS)
                            )
                    }
                }
            }
        }
    }

/** Draws [trails] over the pad, every frame while something is shown. */
@Composable
fun TouchOverlay(trails: TouchTrails, visuals: TouchVisuals, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    val active = trails.active
    LaunchedEffect(active) {
        while (active) withFrameMillis { frame = it }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw each frame
        trails.draw(this, visuals, SystemClock.uptimeMillis(), DOT_DP * density)
    }
}

private const val DOT_DP = 18f

/**
 * Swipe lines for [SwipeLineOverlay]: per finger on a swipe button, its start point (where it
 * landed, or dragged along with [SwipeGuide.dragsStart]), where it is and its [SwipeGuide]. Main
 * thread only; cheap to keep and draw (no allocation per move).
 */
class SwipeLines {
    private class Line(val guide: SwipeGuide, p: Offset) {
        /** Start point and finger, dp. */
        val start = floatArrayOf(p.x, p.y)
        var x = p.x
        var y = p.y
        var held = false
    }

    private val lines = HashMap<Long, Line>()
    private val marks = FloatArray(SwipeGuide.MAX_MARKS)
    private val kinds = IntArray(SwipeGuide.MAX_MARKS)

    /** Bumped on every change; only the overlay's drawing reads it, so a move redraws without recomposing. */
    private var version by mutableIntStateOf(0)

    /** Finger [id] landed at [p] (px; [pxPerDp]: display density). */
    fun down(id: Long, guide: SwipeGuide, p: Offset, pxPerDp: Float) {
        lines[id] = Line(guide, p / pxPerDp)
        version++
    }

    fun move(id: Long, p: Offset, pxPerDp: Float) {
        val l = lines[id] ?: return
        l.x = p.x / pxPerDp; l.y = p.y / pxPerDp
        if (l.guide.dragsStart) l.held = l.guide.drag(l.start, l.x, l.y, l.held)
        version++
    }

    fun up(id: Long) {
        if (lines.remove(id) != null) version++
    }

    fun clear() {
        if (lines.isEmpty()) return
        lines.clear()
        version++
    }

    /**
     * Draws every line: from the start point through the finger to its direction's furthest mark
 * ([SwipeGuide.onAxis]: along the direction, through the finger's travel that way).
     * Marks: Hold after and the zone ends as dots, Swipe after as a bar across the line; the ones
     * the finger passed are filled.
     */
    fun draw(scope: DrawScope, pxPerDp: Float) {
        @Suppress("UNUSED_EXPRESSION") version // redraw on every change
        if (lines.isEmpty()) return
        val stroke = LINE_DP * pxPerDp
        val r = MARK_DP * pxPerDp
        for (l in lines.values) {
            val sx = l.start[0] * pxPerDp
            val sy = l.start[1] * pxPerDp
            val start = Offset(sx, sy)
            if (l.guide.dragsStart) scope.drawCircle(EDGE_COLOR, r * 1.4f, start, style = Stroke(stroke))
            var dx = l.x - l.start[0]
            var dy = l.y - l.start[1]
            // Along its direction's axis: the finger as far along as it went that way.
            val axis = if (l.guide.onAxis) l.guide.direction(dx, dy) else null
            if (axis != null) {
                val a = l.guide.along(axis, dx, dy)
                dx = when (axis) { Direction.RIGHT -> a; Direction.LEFT -> -a; else -> 0f }
                dy = when (axis) { Direction.DOWN -> a; Direction.UP -> -a; else -> 0f }
            }
            val len = hypot(dx, dy)
            if (len * pxPerDp < stroke) continue
            val n = l.guide.marks(dx, dy, marks, kinds)
            val ux = dx / len
            val uy = dy / len
            var end = len
            for (i in 0 until n) end = maxOf(end, marks[i])
            val finger = Offset(sx + dx * pxPerDp, sy + dy * pxPerDp)
            scope.drawLine(LINE_COLOR, start, Offset(sx + ux * end * pxPerDp, sy + uy * end * pxPerDp), stroke, StrokeCap.Round)
            scope.drawLine(PASSED_COLOR, start, finger, stroke, StrokeCap.Round)
            for (i in 0 until n) {
                val at = marks[i] * pxPerDp
                val c = Offset(sx + ux * at, sy + uy * at)
                val passed = marks[i] <= len
                if (kinds[i] == SwipeGuide.SWIPE) {
                    val across = Offset(-uy * r * 1.6f, ux * r * 1.6f)
                    scope.drawLine(if (passed) PASSED_COLOR else EDGE_COLOR, c - across, c + across, stroke * 1.5f, StrokeCap.Round)
                } else if (passed) {
                    scope.drawCircle(PASSED_COLOR, r, c)
                } else {
                    scope.drawCircle(EDGE_COLOR, r, c, style = Stroke(stroke))
                }
            }
        }
    }

    private companion object {
        const val LINE_DP = 2f
        const val MARK_DP = 5f
        val LINE_COLOR = Color.White.copy(alpha = 0.25f)
        val PASSED_COLOR = Color.White.copy(alpha = 0.7f)
        val EDGE_COLOR = Color.White.copy(alpha = 0.55f)
    }
}

/** Draws [lines] over the pad; redraws only when a line changes. */
@Composable
fun SwipeLineOverlay(lines: SwipeLines, modifier: Modifier = Modifier) {
    Canvas(modifier) { lines.draw(this, density) }
}
