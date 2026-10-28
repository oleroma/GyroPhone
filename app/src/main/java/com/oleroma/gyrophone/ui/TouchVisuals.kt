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
import kotlinx.serialization.Serializable
import kotlin.math.hypot

/**
 * How the pad shows touches (app-wide, [com.oleroma.gyrophone.runtime.GamepadRuntime.touchVisuals]).
 * [showTouches]: a dot under each finger that fades out over [fadeMs] once it lifts. [trail]: a
 * line behind each finger, [trailMs] of its path long, whose oldest [trailFade] (0..1) fades out
 * towards its end; it shrinks back to the finger as it ages. [liveMappings]: a button's label
 * briefly shows what it is sending right now. [swipeLine]: a finger on a swipe button with Hold
 * directions mapped shows a line from where it landed, through it, to the end of its direction's
 * last zone, with a dot at each zone edge ([SwipeLines]).
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
 * Swipe lines for [SwipeLineOverlay]: per finger on a swipe button, where it landed, where it is
 * and its [SwipeGuide]. Main thread only; cheap to keep and draw (no allocation per move).
 */
class SwipeLines {
    private class Line(val guide: SwipeGuide, val x0: Float, val y0: Float) {
        var x = x0
        var y = y0
    }

    private val lines = HashMap<Long, Line>()
    private val edges = FloatArray(SwipeGuide.MAX_EDGES)

    /** Bumped on every change; only the overlay's drawing reads it, so a move redraws without recomposing. */
    private var version by mutableIntStateOf(0)

    fun down(id: Long, guide: SwipeGuide, p: Offset) {
        lines[id] = Line(guide, p.x, p.y)
        version++
    }

    fun move(id: Long, p: Offset) {
        val l = lines[id] ?: return
        l.x = p.x; l.y = p.y
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

    /** Draws every line ([pxPerDp]: display density); edges the finger has passed are filled. */
    fun draw(scope: DrawScope, pxPerDp: Float) {
        @Suppress("UNUSED_EXPRESSION") version // redraw on every change
        if (lines.isEmpty()) return
        val stroke = LINE_DP * pxPerDp
        val r = EDGE_DP * pxPerDp
        for (l in lines.values) {
            val dx = l.x - l.x0
            val dy = l.y - l.y0
            val len = hypot(dx, dy)
            if (len < stroke) continue
            val n = l.guide.edges(dx / pxPerDp, dy / pxPerDp, edges)
            val ux = dx / len
            val uy = dy / len
            val end = if (n == 0) len else maxOf(len, edges[n - 1] * pxPerDp)
            val start = Offset(l.x0, l.y0)
            scope.drawLine(LINE_COLOR, start, Offset(l.x0 + ux * end, l.y0 + uy * end), stroke, StrokeCap.Round)
            scope.drawLine(PASSED_COLOR, start, Offset(l.x, l.y), stroke, StrokeCap.Round)
            for (i in 0 until n) {
                val at = edges[i] * pxPerDp
                val c = Offset(l.x0 + ux * at, l.y0 + uy * at)
                if (at <= len) scope.drawCircle(PASSED_COLOR, r, c)
                else scope.drawCircle(EDGE_COLOR, r, c, style = Stroke(stroke))
            }
        }
    }

    private companion object {
        const val LINE_DP = 2f
        const val EDGE_DP = 5f
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
