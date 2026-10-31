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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.SwipeGuide
import com.oleroma.gyrophone.core.engine.Touchpad
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
 * activation length, marking each ([SwipeLines]). [moveRulers]: a finger on a touch button whose
 * Move X or Y moves shows a sliding ruler per Move instead ([MoveRulers]). The gyroEdge* fields set up the parallax edge
 * ([GyroEdgeLayer]); the other gyro displays are placed in edit mode ([GyroDisplaysLayer]).
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
    val moveRulers: Boolean = true,
    /** Outlines just inside the screen's edge that sway as the phone turns. */
    val gyroEdge: Boolean = false,
    /** Moved by tilt against gravity (stays while tilted) instead of by turning (springs back). */
    val gyroEdgeTilt: Boolean = false,
    val gyroEdgeLayers: Int = 3,
    val gyroEdgeStrength: Float = 1f,
    val gyroEdgeSpringMs: Int = 400,
    val gyroEdgeWidthDp: Float = 2f,
    val gyroEdgeInsetDp: Float = 12f,
    /** Moves with the phone instead of lagging behind it. */
    val gyroEdgeInvert: Boolean = false,
    /** Dims and tints the edge by which gyro axes move now. */
    val gyroEdgeRatchetTint: Boolean = true,
    /** Tilt mode: the real pose that leaves the edge centred, 0 upright facing the player, 90 flat screen up. */
    val gyroLevelDeg: Int = 0,
    /** The edge's opacity. */
    val gyroOpacity: Float = 0.6f,
) {
    /** The edge holds its pose to gravity (and so needs the accelerometer). */
    val gyroNeedsGravity: Boolean get() = gyroEdge && gyroEdgeTilt

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
    private class Line(val guide: SwipeGuide, p: Offset, val offset: ((FloatArray) -> Boolean)?) {
        /** Start point and finger, dp. */
        val start = floatArrayOf(p.x, p.y)
        var x = p.x
        var y = p.y
        var held = false

        /** On its axis ([SwipeGuide.onAxis]): the direction it last pointed into; it stays while the finger is outside every pie. */
        var axis: Direction? = null

        /** Hold after was reached: the line shows from then until the finger lifts. */
        var shown = false
    }

    private val lines = HashMap<Long, Line>()
    private val off = FloatArray(2)
    private val marks = FloatArray(SwipeGuide.MAX_MARKS)
    private val kinds = IntArray(SwipeGuide.MAX_MARKS)

    /** Bumped on every change; only the overlay's drawing reads it, so a move redraws without recomposing. */
    private var version by mutableIntStateOf(0)

    /** Finger [id] landed at [p] (px; [pxPerDp]: display density). */
    /** [offset]: the engine's [com.oleroma.gyrophone.core.engine.MappingEngine.swipeOffset] for a start point that moves as the heading locks. */
    fun down(id: Long, guide: SwipeGuide, p: Offset, pxPerDp: Float, offset: ((FloatArray) -> Boolean)? = null) {
        lines[id] = Line(guide, p / pxPerDp, offset.takeIf { guide.locks })
        version++
    }

    fun move(id: Long, p: Offset, pxPerDp: Float) {
        val l = lines[id] ?: return
        l.x = p.x / pxPerDp; l.y = p.y / pxPerDp
        if (l.offset?.invoke(off) == true) {
            l.start[0] = l.x - off[0]; l.start[1] = l.y - off[1]
        }
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
 * ([SwipeGuide.onAxis]: along the direction, through the finger's travel that way), once the
 * finger has reached Hold after.
     * Marks: Hold after and the zone ends as dots, Quick swipe after as a bar across the line; the ones
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
            var dx = l.x - l.start[0]
            var dy = l.y - l.start[1]
            // Along its direction's axis: the finger as far along as it went that way.
            // Outside every pie (moving another way, such as a Move) it stays on the last direction's
            // axis, so it never turns into a line from the start point to the finger.
            if (l.guide.onAxis) {
                l.guide.direction(dx, dy)?.let { l.axis = it }
                if (l.axis == null) continue
            }
            val axis = if (l.guide.onAxis) l.axis else null
            if (axis != null) {
                val a = l.guide.along(axis, dx, dy).coerceAtLeast(0f)
                dx = when (axis) { Direction.RIGHT -> a; Direction.LEFT -> -a; else -> 0f }
                dy = when (axis) { Direction.DOWN -> a; Direction.UP -> -a; else -> 0f }
            }
            val len = hypot(dx, dy)
            if (len * pxPerDp < stroke) continue
            val n = l.guide.marks(dx, dy, marks, kinds)
            if (!l.shown) {
                // Only once Hold after is reached (else the first mark), so short touches don't flicker.
                var at = Float.MAX_VALUE
                for (i in 0 until n) if (kinds[i] == SwipeGuide.HOLD) at = marks[i]
                if (at == Float.MAX_VALUE) for (i in 0 until n) at = minOf(at, marks[i])
                if (n == 0 || len < at) continue
                l.shown = true
            }
            if (l.guide.dragsStart) scope.drawCircle(EDGE_COLOR, r * 1.4f, start, style = Stroke(stroke))
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

/**
 * Move rulers for [MoveRulerOverlay]: per finger on a touch button with Move X or Y mapped, once its
 * Move has moved, a short ruler for each mapped Move centred on the finger (X across, Y up and
 * down). Its ticks slide by that Move's travel ([MappingEngine.moveTrack]), so they stay put on the
 * screen while the finger moves the axis; a Move held back (by its pie or a swipe hold) holds its
 * ticks and dims. After the lift the rulers stay where the finger left, sliding on while inertia
 * glides, then fade. [track]: the engine's [MappingEngine.moveTrack]. Main thread only.
 */
class MoveRulers(private val track: (String, FloatArray) -> Int) {
    private class Ruler(val control: String, p: Offset, val baseX: Float, val baseY: Float, var mapped: Int) {
        /** Centre (px): the finger, or where it lifted. */
        var x = p.x
        var y = p.y
        var travelX = baseX
        var travelY = baseY

        /** When each Move last moved (uptime ms). */
        var movedXT = Long.MIN_VALUE / 4
        var movedYT = Long.MIN_VALUE / 4

        /** The Move moved since the touch-down: the rulers show. */
        var moved = false

        /** Lift time (uptime ms), or -1 while down; fading since (the glide over), or -1. */
        var liftT = -1L
        var fadeT = -1L
    }

    private val rulers = HashMap<Long, Ruler>()
    private val buf = FloatArray(2)

    /** Something may show: the overlay ticks every frame while this is true. */
    var active by mutableStateOf(false)
        private set

    /** Finger [id] landed on touch button [control] at [p] (px). */
    fun down(id: Long, control: String, p: Offset) {
        val m = track(control, buf)
        if (m == 0) {
            rulers.remove(id); return
        }
        rulers[id] = Ruler(control, p, buf[0], buf[1], m)
        active = true
    }

    /** Finger [id] moved to [p] (px), the engine fed first. True once its Move has moved. */
    fun move(id: Long, p: Offset): Boolean {
        val r = rulers[id] ?: return false
        if (r.liftT >= 0) return false
        r.x = p.x; r.y = p.y
        sample(r, SystemClock.uptimeMillis())
        return r.moved
    }

    fun up(id: Long) {
        val r = rulers[id] ?: return
        if (r.liftT < 0) r.liftT = SystemClock.uptimeMillis()
    }

    fun clear() {
        rulers.clear()
        active = false
    }

    private fun sample(r: Ruler, now: Long) {
        val m = track(r.control, buf)
        r.mapped = m
        if (m == 0) return
        if (buf[0] != r.travelX) { r.travelX = buf[0]; r.movedXT = now }
        if (buf[1] != r.travelY) { r.travelY = buf[1]; r.movedYT = now }
        if (r.travelX != r.baseX || r.travelY != r.baseY) r.moved = true
        // A glide may start a moment after the lift (the engine settling the lift): it waits that long.
        if (r.liftT >= 0 && r.fadeT < 0 && m and MappingEngine.MOVES_GLIDING == 0 && now - r.liftT >= LIFT_WAIT_MS) r.fadeT = now
    }

    /** Once a frame: follows the glides and drops faded rulers. */
    fun tick(now: Long) {
        val iter = rulers.values.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            sample(r, now)
            val gone = r.mapped == 0 || (r.fadeT >= 0 && (!r.moved || now - r.fadeT >= FADE_MS))
            if (gone) iter.remove()
        }
        if (rulers.isEmpty()) active = false
    }

    fun draw(scope: DrawScope, now: Long, pxPerDp: Float) {
        for (r in rulers.values) {
            if (!r.moved) continue
            val fade = if (r.fadeT < 0) 1f else (1f - (now - r.fadeT).toFloat() / FADE_MS).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            if (r.mapped and Touchpad.MOVES_X != 0) ruler(scope, r, true, lit(now - r.movedXT) * fade, pxPerDp)
            if (r.mapped and Touchpad.MOVES_Y != 0) ruler(scope, r, false, lit(now - r.movedYT) * fade, pxPerDp)
            if (r.liftT >= 0) scope.drawCircle(Color.White.copy(alpha = 0.35f * fade), RING_DP * pxPerDp, Offset(r.x, r.y), style = Stroke(1.5f * pxPerDp))
        }
    }

    /** How bright an axis is [since] ms after it last moved: full while moving, then down to [IDLE]. */
    private fun lit(since: Long): Float {
        val k = if (since <= HOLD_MS) 1f else (1f - (since - HOLD_MS).toFloat() / DIM_MS).coerceIn(0f, 1f)
        return IDLE + (1f - IDLE) * k
    }

    /** One Move's ruler ([x]: Move X, across): a faint rail and ticks [SPACING_DP] apart, pinned to its travel. */
    private fun ruler(scope: DrawScope, r: Ruler, x: Boolean, alpha: Float, pxPerDp: Float) {
        val half = HALF_DP * pxPerDp
        val gap = SPACING_DP * pxPerDp
        val cx = r.x
        val cy = r.y
        val c0 = if (x) cx else cy
        val rail = Color.White.copy(alpha = 0.3f * alpha)
        val clear = Color.Transparent
        if (x) {
            scope.drawLine(
                Brush.horizontalGradient(listOf(clear, rail, clear), cx - half, cx + half),
                Offset(cx - half, cy), Offset(cx + half, cy), pxPerDp,
            )
        } else {
            scope.drawLine(
                Brush.verticalGradient(listOf(clear, rail, clear), cy - half, cy + half),
                Offset(cx, cy - half), Offset(cx, cy + half), pxPerDp,
            )
        }
        // The ticks stay where they were on the screen as the travel goes on: tick k sits at
        // centre − travel + k·gap.
        val travel = if (x) r.travelX - r.baseX else r.travelY - r.baseY
        val first = kotlin.math.ceil((travel - half) / gap).toInt()
        val last = kotlin.math.floor((travel + half) / gap).toInt()
        val stroke = TICK_STROKE_DP * pxPerDp
        for (k in first..last) {
            val at = c0 - travel + k * gap
            val off = kotlin.math.abs(at - c0) / half
            if (off > 1f) continue
            val a = alpha * (1f - off) * (1f - off) * 0.95f
            val len = (if (Math.floorMod(k, 4) == 0) MAJOR_DP else MINOR_DP) * pxPerDp / 2f
            val color = Color.White.copy(alpha = a)
            if (x) scope.drawLine(color, Offset(at, cy - len), Offset(at, cy + len), stroke, StrokeCap.Round)
            else scope.drawLine(color, Offset(cx - len, at), Offset(cx + len, at), stroke, StrokeCap.Round)
        }
    }

    private companion object {
        const val SPACING_DP = 16f
        const val HALF_DP = 96f
        const val MINOR_DP = 8f
        const val MAJOR_DP = 16f
        const val TICK_STROKE_DP = 2f
        const val RING_DP = 14f
        const val IDLE = 0.3f
        const val HOLD_MS = 120L
        const val DIM_MS = 250L
        const val FADE_MS = 250L
        const val LIFT_WAIT_MS = 50L
    }
}

/** Draws [rulers] over the pad, every frame while one may show. */
@Composable
fun MoveRulerOverlay(rulers: MoveRulers, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    val active = rulers.active
    LaunchedEffect(active) {
        while (active) withFrameMillis {
            rulers.tick(SystemClock.uptimeMillis())
            frame = it
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw each frame
        rulers.draw(this, SystemClock.uptimeMillis(), density)
    }
}
