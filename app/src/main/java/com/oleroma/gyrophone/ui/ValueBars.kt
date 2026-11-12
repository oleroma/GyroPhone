package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.GameSens
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.OutputTarget
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A button (or, [id] "", the workspace touchpad) with value chips that slide or keep a value
 * ([com.oleroma.gyrophone.core.model.SetValue], a Move slide): its rectangle in px (left, top,
 * right, bottom), [round] or not, and whether any chip on it is a Toggle value chip ([set]: the sliders icon).
 */
data class ValueMark(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val round: Boolean,
    val set: Boolean,
    /** It has a button frame (the touchpad has none): the bars stay inside it. */
    val framed: Boolean = true,
)
/**
 * Value sliders on the pad, read from the engine once for both layers ([ValueFramesBehind] and
 * [ValueBarsOverlay]): the chips shown on each [ValueMark] with their values, and each mark's lines
 * ("62 · RT 62 %": where the value is in its range (0..100) and what it sends; chips sending the
 * same share a line, RWS named when the game is set). The lines sit in the middle of their button,
 * in place of its label, when they fit there stacked or else on one row ([centered] has those
 * buttons); otherwise in a frame outside the button by its top-right corner ([framePlace]),
 * behind the buttons. A Held chip shows only while it is driven. [bars] false (Touch display
 * switch off): only the sliders icons. It reads the engine every frame while [active] (a finger is
 * down), else a few times a second, and redraws only when something changed.
 */
class ValueBars internal constructor(val marks: List<ValueMark>, val bars: Boolean) {
    internal var frame by mutableLongStateOf(0L)
    internal val chips = ArrayList<Mapping>()
    internal val values = FloatArray(2 * MAX_SHOWN)
    internal val labels = arrayOfNulls<String>(marks.size)
    internal val laid = arrayOfNulls<TextLayoutResult>(marks.size)

    /** Each mark's lines sit in its middle (stacked or on one row), else outside. */
    internal val inside = BooleanArray(marks.size)

    /** The buttons whose values sit in their middle: they hide their label meanwhile. */
    var centered by mutableStateOf(emptySet<String>())
        internal set
}

/** The pad's [ValueBars] for [marks], kept reading [engine] while shown. */
@Composable
fun rememberValueBars(engine: MappingEngine, marks: List<ValueMark>, active: () -> Boolean, bars: Boolean = true): ValueBars {
    val vb = remember(marks, bars) { ValueBars(marks, bars) }
    if (marks.isEmpty()) return vb
    val text = rememberTextMeasurer()
    val density = LocalDensity.current
    val isActive by rememberUpdatedState(active)
    LaunchedEffect(vb) {
        val readChips = ArrayList<Mapping>()
        val readValues = FloatArray(vb.values.size)
        val lines = ArrayList<LinePart>()
        val pad = with(density) { FRAME_PAD_DP.dp.toPx() }
        val inset = with(density) { FRAME_DP.dp.toPx() }
        var first = true
        while (true) {
            // The first read is at once: a new overlay shows the values from its first frame.
            if (first) first = false else if (isActive()) withFrameMillis { } else delay(IDLE_MS)
            val n = engine.valuesShown(readChips, readValues)
            var changed = n != vb.chips.size || readChips != vb.chips
            if (!changed) for (i in 0 until 2 * n) if (readValues[i].toBits() != vb.values[i].toBits()) {
                changed = true; break
            }
            if (!changed) continue
            vb.chips.clear(); vb.chips.addAll(readChips)
            readValues.copyInto(vb.values)
            val counts = engine.workspace.gyro.gameCountsPer360
            var centered: MutableSet<String>? = null
            for ((k, mk) in vb.marks.withIndex()) {
                lines.clear()
                for (j in vb.chips.indices) {
                    val m = vb.chips[j]
                    if ((MappingProfiles.touchId(m.source) ?: "") != mk.id) continue
                    // Chips at the same place sending the same share one line: "40 · Yaw Pitch 4.50 RWS".
                    val p = linePart(m, vb.values[2 * j + 1], counts)
                    val same = lines.firstOrNull { it.pct == p.pct && it.kind == p.kind && it.value == p.value }
                    if (same == null) lines.add(p) else if (p.names[0] !in same.names) same.names.add(p.names[0])
                }
                if (lines.isEmpty()) {
                    vb.labels[k] = null; vb.laid[k] = null; vb.inside[k] = false
                    continue
                }
                // In the button's middle when it fits there (a round one: in the square inside its circle).
                val w = mk.right - mk.left
                val h = mk.bottom - mk.top
                val room = if (mk.round) 0.7f else 1f
                val fitW = w * room - 2 * (inset + pad)
                val fitH = h * room - 2 * (inset + pad)
                fun fits(l: TextLayoutResult) = l.size.width <= fitW && l.size.height <= fitH
                val stacked = lines.joinToString("\n") { it.text() }
                val row = lines.joinToString("   ") { it.text() }
                var label = stacked
                var laid = text.measure(stacked, VALUE_STYLE)
                var mid = mk.framed && vb.bars && fits(laid)
                if (mk.framed && vb.bars && !mid && lines.size > 1) {
                    val one = text.measure(row, VALUE_STYLE)
                    if (fits(one)) { label = row; laid = one; mid = true }
                }
                if (label != vb.labels[k] || mid != vb.inside[k]) {
                    vb.labels[k] = label
                    vb.laid[k] = laid
                }
                vb.inside[k] = mid
                if (mid) (centered ?: HashSet<String>().also { centered = it }).add(mk.id)
            }
            val now = centered ?: emptySet()
            if (now != vb.centered) vb.centered = now
            vb.frame++
        }
    }
    return vb
}

/** The value frames outside their buttons ([ValueBars]): drawn behind the buttons. */
@Composable
fun ValueFramesBehind(vb: ValueBars, modifier: Modifier = Modifier) {
    if (vb.marks.isEmpty() || !vb.bars) return
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_EXPRESSION") vb.frame // redrawn when a value changed
        for ((k, mk) in vb.marks.withIndex()) {
            if (!mk.framed || vb.inside[k]) continue
            val label = vb.laid[k] ?: continue
            val pad = FRAME_PAD_DP.dp.toPx()
            val bw = label.size.width + 2 * pad
            val bh = label.size.height + 2 * pad
            val (bx, by) = framePlace(mk, bw, bh, size.width, size.height)
            valueFrame(label, bx, by, bw, bh, pad)
        }
    }
}

/**
 * The value sliders over the buttons ([ValueBars]): on each mark a sliders icon in its top-left
 * corner when a chip on it is a Toggle value chip; for each Position value chip (Held or Toggle) a
 * bar across the button where its value is (upright for X, flat for Y, a ring for distance, a
 * needle for angle; the part from 0 lightly filled, ticks at its Snap steps), clipped by the
 * button's edge; the values that fit in the middle of their button; the touchpad's in its
 * top-right corner.
 */
@Composable
fun ValueBarsOverlay(vb: ValueBars, color: Color, modifier: Modifier = Modifier) {
    if (vb.marks.isEmpty()) return
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_EXPRESSION") vb.frame // redrawn when a value changed
        for ((k, mk) in vb.marks.withIndex()) {
            val w = mk.right - mk.left
            val h = mk.bottom - mk.top
            if (w <= 0f || h <= 0f) continue
            if (mk.set) tuneIcon(Offset(mk.left + 6.dp.toPx(), mk.top + 6.dp.toPx()), color)
            if (!vb.bars) continue
            // The bars, clipped by the button's edge.
            // Inside the button's frame (its 2 dp border; a square's corners rounded by 12 % as the button's).
            val inset = if (mk.framed) FRAME_DP.dp.toPx() else 0f
            val inner = androidx.compose.ui.geometry.Rect(mk.left + inset, mk.top + inset, mk.right - inset, mk.bottom - inset)
            val clip = Path().apply {
                if (mk.round) addOval(inner)
                else if (!mk.framed) addRect(inner)
                else {
                    val r = (minOf(w, h) * 0.12f - inset).coerceAtLeast(0f)
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(inner, CornerRadius(r)))
                }
            }
            clipPath(clip) {
                for (j in vb.chips.indices) {
                    val m = vb.chips[j]
                    val src = m.source as? InputSource.Finger ?: continue
                    if (src.id != mk.id) continue
                    val v = vb.values[2 * j]
                    if (v.isNaN()) continue
                    bar(mk, src.axis, v, color, snapTicks(m, src.axis, mk))
                }
            }
            val label = vb.laid[k] ?: continue
            val pad = FRAME_PAD_DP.dp.toPx()
            val bw = label.size.width + 2 * pad
            val bh = label.size.height + 2 * pad
            when {
                // In place of the button's label.
                vb.inside[k] -> valueFrame(label, (mk.left + mk.right - bw) / 2, (mk.top + mk.bottom - bh) / 2, bw, bh, pad)
                !mk.framed -> valueFrame(label, (mk.right - bw - 4.dp.toPx()).coerceAtLeast(0f), mk.top + 4.dp.toPx(), bw, bh, pad)
            }
        }
    }
}

/** A value frame: [label] on a dark rounded box [bw] × [bh] at ([bx], [by]). */
private fun DrawScope.valueFrame(label: TextLayoutResult, bx: Float, by: Float, bw: Float, bh: Float, pad: Float) {
    drawRoundRect(Color(0xEB141418), Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()))
    drawRoundRect(Color(0xFF666666), Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
    drawText(label, topLeft = Offset(bx + pad, by + pad))
}

/** The values' text, and the padding round it in its frame (dp). */
private val VALUE_STYLE = TextStyle(color = Color.White, fontSize = 13.sp)
private const val FRAME_PAD_DP = 6f

/** A button's frame (border) width in dp, as the pad draws it. */
private const val FRAME_DP = 2f

/** At most this many value chips show at once. */
private const val MAX_SHOWN = 32

/** How often the values are read while no finger is down (a timer or a Let go may end one). */
private const val IDLE_MS = 120L

/**
 * One chip's part of a corner line: where [out] is in its range (0..100), its kind, its own name
 * ("Yaw", "RT") and what it sends; chips alike but for the name share a line ([text]).
 */
private class LinePart(val pct: String, val kind: Int, name: String, val value: String) {
    val names = arrayListOf(name)

    /** "40 · Yaw Pitch 4.50 RWS", "40 · Yaw sens 16", "40 · Sens 16", "62 · RT 62 %". */
    fun text(): String {
        val n = names.filter { it.isNotEmpty() }.joinToString(" ")
        val what = when (kind) {
            KIND_RWS -> listOf(n, "$value RWS")
            KIND_SENS -> listOf(if (n.isEmpty()) "Sens" else "$n sens", value)
            KIND_TOUCH -> listOf("Touch sens", n, value)
            else -> listOf(n, value)
        }.filter { it.isNotEmpty() }.joinToString(" ")
        return "$pct · $what"
    }
}

private const val KIND_RWS = 0
private const val KIND_SENS = 1
private const val KIND_TOUCH = 2
private const val KIND_PAD = 3

/** Chip [m]'s [LinePart] at output [out]; [counts] = the workspace game's counts per 360 for RWS (0 = none). */
private fun linePart(m: Mapping, out: Float, counts: Float): LinePart {
    val r = m.range
    val pct = if (r != null && r.to != r.from) (out - r.from) / (r.to - r.from) else out
    val p = "%.0f".format(pct.coerceIn(0f, 1f) * 100)
    return when (val t = m.target) {
        is OutputTarget.GyroSens -> {
            val name = if (t.axis == com.oleroma.gyrophone.core.model.MotionAxis.NONE) "" else t.axis.label
            if (counts > 0f) LinePart(p, KIND_RWS, name, "%.2f".format(GameSens.rws(out, counts)))
            else LinePart(p, KIND_SENS, name, "%.1f".format(out).removeSuffix(".0").removeSuffix(",0"))
        }
        is OutputTarget.TouchSens -> LinePart(p, KIND_TOUCH, t.axis?.name ?: "", "%.2f".format(out))
        else -> LinePart(p, KIND_PAD, Describe.shortTarget(t), "%.0f %%".format(kotlin.math.abs(out) * 100))
    }
}

/**
 * Where a value frame [bw] × [bh] goes by button [mk] on a [sw] × [sh] screen: outside the button by
 * its top-right corner, never sticking out past the corner. A wide (or square) button has it above,
 * lined up with its right edge (below near the top edge); a tall one beside it on the right, lined
 * up with its top (on the left near the screen's right edge). Always kept on screen.
 */
private fun DrawScope.framePlace(mk: ValueMark, bw: Float, bh: Float, sw: Float, sh: Float): Pair<Float, Float> {
    val gap = 4.dp.toPx()
    // The touchpad fills the screen: its frame stays inside its top-right corner.
    if (!mk.framed) return (mk.right - bw - gap).coerceAtLeast(0f) to (mk.top + gap)
    val w = mk.right - mk.left
    val h = mk.bottom - mk.top
    var x: Float
    var y: Float
    if (w >= h) {
        y = mk.top - gap - bh
        if (y < 0f) y = mk.bottom + gap
        x = mk.right - bw
    } else {
        x = mk.right + gap
        if (x + bw > sw) x = mk.left - gap - bw
        y = mk.top
    }
    return x.coerceIn(0f, (sw - bw).coerceAtLeast(0f)) to y.coerceIn(0f, (sh - bh).coerceAtLeast(0f))
}

/**
 * The ruler ticks of Position chip [m] on [mk] along [axis], as (place 0..1, long) pairs: with a
 * Snap ([Mapping.snap]) one at each step its value lands on (every 2nd, 5th, 10th… when they would
 * sit closer than [MIN_TICK_DP]; every 5th shown one long), else every 10 % (long at 50 %).
 */
private fun DrawScope.snapTicks(m: Mapping, axis: FingerAxis, mk: ValueMark): List<Pair<Float, Boolean>> {
    val step = m.snap?.takeIf { it > 0f }
    val r = m.range ?: if (m.target is OutputTarget.PadAxis) com.oleroma.gyrophone.core.model.AxisRange(0f, 1f) else null
    if (step == null || r == null || r.to == r.from) return DEFAULT_TICKS
    val w = mk.right - mk.left
    val h = mk.bottom - mk.top
    val length = when (axis) {
        FingerAxis.X -> w
        FingerAxis.Y -> h
        FingerAxis.DISTANCE -> minOf(w, h) / 2
        FingerAxis.ANGLE -> (minOf(w, h) * PI).toFloat()
    }
    val span = kotlin.math.abs(r.to - r.from)
    val spacing = step / span * length
    if (spacing <= 0f) return DEFAULT_TICKS
    val min = MIN_TICK_DP.dp.toPx()
    var n = 1
    val thin = intArrayOf(1, 2, 5)
    var k = 0
    while (spacing * n < min && n < 1_000_000) {
        k++
        n = thin[k % 3] * pow10(k / 3)
    }
    val lo = minOf(r.from, r.to)
    val hi = maxOf(r.from, r.to)
    val first = kotlin.math.ceil(lo / (step * n)).toLong()
    val last = kotlin.math.floor(hi / (step * n)).toLong()
    if (last - first > MAX_TICKS) return DEFAULT_TICKS
    return (first..last).mapNotNull { i ->
        val p = (i * n * step - r.from) / (r.to - r.from)
        // The ends are the button's edges: no tick there.
        if (p <= 0.001f || p >= 0.999f) null else p to (i % 5 == 0L)
    }
}

private fun pow10(e: Int): Int { var v = 1; repeat(e) { v *= 10 }; return v }

/** Without a Snap: a tick every 10 %, long at 50 %. */
private val DEFAULT_TICKS = (1..9).map { it / 10f to (it == 5) }

/** Snap ticks closer than this are thinned; past [MAX_TICKS] the plain scale shows. */
private const val MIN_TICK_DP = 6f
private const val MAX_TICKS = 400L

/**
 * A Position chip's bar on [mk] at its value [v] (0..1) of [axis]: the part from 0 lightly filled,
 * a scale of [ticks] ([snapTicks]; rings for distance, notches round the rim for angle, longest
 * at the top), and a line at the value.
 */
private fun DrawScope.bar(mk: ValueMark, axis: FingerAxis, v: Float, c: Color, ticks: List<Pair<Float, Boolean>>) {
    val w = mk.right - mk.left
    val h = mk.bottom - mk.top
    val fill = c.copy(alpha = c.alpha * 0.18f)
    val line = 3.dp.toPx()
    // The scale, as the Move ruler's ticks: white, every 10 %, longer at 50 %.
    val tick = Color.White.copy(alpha = 0.75f)
    val stroke = TICK_STROKE_DP.dp.toPx()
    val minor = MINOR_DP.dp.toPx()
    val major = MAJOR_DP.dp.toPx()
    fun len(long: Boolean) = if (long) major else minor
    when (axis) {
        FingerAxis.X -> {
            val x = mk.left + v * w
            drawRect(fill, Offset(mk.left, mk.top), Size(x - mk.left, h))
            for ((p, long) in ticks) {
                val tx = mk.left + p * w
                drawLine(tick, Offset(tx, mk.bottom), Offset(tx, mk.bottom - len(long)), stroke, StrokeCap.Round)
            }
            drawLine(c, Offset(x, mk.top), Offset(x, mk.bottom), line)
        }
        FingerAxis.Y -> {
            val y = mk.bottom - v * h
            drawRect(fill, Offset(mk.left, y), Size(w, mk.bottom - y))
            for ((p, long) in ticks) {
                val ty = mk.bottom - p * h
                drawLine(tick, Offset(mk.left, ty), Offset(mk.left + len(long), ty), stroke, StrokeCap.Round)
            }
            drawLine(c, Offset(mk.left, y), Offset(mk.right, y), line)
        }
        FingerAxis.DISTANCE -> {
            val centre = Offset((mk.left + mk.right) / 2, (mk.top + mk.bottom) / 2)
            val full = minOf(w, h) / 2
            val r = full * v
            drawCircle(fill, r, centre)
            for ((p, long) in ticks) drawCircle(tick.copy(alpha = if (long) 0.5f else 0.22f), full * p, centre, style = Stroke(stroke))
            drawCircle(c, r, centre, style = Stroke(line))
        }
        FingerAxis.ANGLE -> {
            val centre = Offset((mk.left + mk.right) / 2, (mk.top + mk.bottom) / 2)
            val r = minOf(w, h) / 2
            val sweep = v * 360f
            drawArc(fill, -90f, sweep, true, Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r))
            // The top (0 and 100 %) always has the long notch.
            for ((p, long) in ticks + (0f to true)) {
                val ta = (p * 2 * PI - PI / 2).toFloat()
                val l = len(long)
                drawLine(
                    tick, Offset(centre.x + cos(ta) * r, centre.y + sin(ta) * r),
                    Offset(centre.x + cos(ta) * (r - l), centre.y + sin(ta) * (r - l)), stroke, StrokeCap.Round,
                )
            }
            val a = (v * 2 * PI - PI / 2).toFloat()
            drawLine(c, centre, Offset(centre.x + cos(a) * r, centre.y + sin(a) * r), line, StrokeCap.Round)
        }
    }
}

/** The scale's ticks, as the Move ruler's (dp). */
private const val MINOR_DP = 8f
private const val MAJOR_DP = 16f
private const val TICK_STROKE_DP = 2f

/** The slider (Toggle) mark: a small sliders icon (three lines with knobs) with its top-left at [at]. */
private fun DrawScope.tuneIcon(at: Offset, c: Color) {
    val s = 14.dp.toPx()
    val stroke = 1.6f.dp.toPx()
    drawRoundRect(Color(0xB0141418), Offset(at.x - 3.dp.toPx(), at.y - 3.dp.toPx()), Size(s + 6.dp.toPx(), s + 6.dp.toPx()), CornerRadius(5.dp.toPx()))
    val knobs = floatArrayOf(0.3f, 0.7f, 0.45f)
    for (i in 0 until 3) {
        val y = at.y + s * (i + 0.5f) / 3f
        drawLine(Color.White, Offset(at.x, y), Offset(at.x + s, y), stroke, StrokeCap.Round)
        drawCircle(c, 2.4f.dp.toPx(), Offset(at.x + s * knobs[i], y))
    }
}
