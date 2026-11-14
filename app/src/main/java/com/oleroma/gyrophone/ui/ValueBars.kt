package com.oleroma.gyrophone.ui

import android.os.SystemClock
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.oleroma.gyrophone.core.model.isTouchpad
import com.oleroma.gyrophone.core.model.nothing
import com.oleroma.gyrophone.core.model.slides
import com.oleroma.gyrophone.core.model.valueTarget
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A button (or, [id] "", the workspace touchpad) with a frame ([ValueBars]): its rectangle in px
 * (left, top, right, bottom), [round] or not, and whether any chip on it is a Toggle value chip
 * ([set]: the sliders icon).
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
    /** Each Position axis's Snap steps ([com.oleroma.gyrophone.core.model.FingerAxisSettings.steps]); none = smooth. */
    val steps: Map<FingerAxis, Int> = emptyMap(),
    /** Every line its frame could show ([frameLines]): whether they fit decides inside or outside, once. */
    val possible: List<String> = emptyList(),
)

/**
 * Each button's frame on the pad, read from the engine once for both layers ([ValueFramesBehind]
 * and [ValueBarsOverlay]). With [frames] (Show mappings on buttons) it lists the button's mappings
 * active now, one line each ("LMB"; one fired by a tap or long press stays a moment), and every
 * Toggle it has, on or off ("Crouch on", green; "Crouch off", grey). With [bars] (Show sliders on
 * buttons) the value chips' lines follow ("62 · RT 62 %": where the value is in its range (0..100)
 * and what it sends; chips sending the same share a line, RWS named when the game is set), and
 * the Position bars show. Whether the frame sits in the middle of its button, in place of its
 * label, or outside it by its top-right corner ([framePlace], behind the buttons) is decided once,
 * from every line the button could show ([ValueMark.possible]): stacked, else on one row, when
 * that fits inside. A Held value chip shows only while it is driven. It reads the engine every
 * frame while [active] (a finger is down, or a gesture may still fire), else a few times a second,
 * and redraws only when something changed.
 */
class ValueBars internal constructor(val marks: List<ValueMark>, val bars: Boolean, val frames: Boolean) {
    internal var frame by mutableLongStateOf(0L)
    internal val chips = ArrayList<Mapping>()
    internal val values = FloatArray(2 * MAX_SHOWN)
    internal val labels = arrayOfNulls<AnnotatedString>(marks.size)
    internal val laid = arrayOfNulls<TextLayoutResult>(marks.size)

    /** Each mark's frame sits in its middle, else outside. */
    internal val inside = BooleanArray(marks.size)

    /** Inside, its lines go on one row (stacked they don't fit). */
    internal val row = BooleanArray(marks.size)

    /** The buttons whose frame shows in their middle now: they hide their label meanwhile. */
    var centered by mutableStateOf(emptySet<String>())
        internal set
}

/**
 * The lines button [id]'s frame could show: from [ms] (the workspace's touch mappings in every
 * set, with their buttons' How, [com.oleroma.gyrophone.core.model.ButtonHow.touch]) its mappings
 * ([frames]; a Toggle as "… off") and its value chips at their range's end ([bars]).
 */
fun frameLines(id: String, ms: List<Mapping>, toggles: (Mapping) -> Boolean, counts: Float, bars: Boolean, frames: Boolean): List<String> {
    val out = LinkedHashSet<String>()
    for (m in ms) {
        if (frameId(m) != id) continue
        if (valueLine(m)) {
            if (bars) out.add(linePart(m, m.range?.to ?: 1f, counts).text())
        } else if (frames && framesMapping(m)) {
            out.add(Describe.shortTarget(m.target) + if (toggles(m)) OFF else "")
        }
    }
    return out.toList()
}

/** The frame a mapping's line goes in: its button's id, "" for the touchpad, null for none. */
private fun frameId(m: Mapping): String? =
    MappingProfiles.touchId(m.source) ?: if (m.source.isTouchpad) "" else null

/** A value chip's line (sliders), not a mapping line. */
private fun valueLine(m: Mapping) = m.target.valueTarget && m.source.slides

/** A mapping that gets a line of its own: not a haptic, an empty one or a gyro card. */
private fun framesMapping(m: Mapping): Boolean {
    val t = m.target
    if (t.nothing || Describe.isHaptic(t)) return false
    val s = m.source
    return s !is InputSource.Gyro && s !is InputSource.Tilt && s !is InputSource.Shake
}

/** The pad's [ValueBars] for [marks], kept reading [engine] while shown. */
@Composable
fun rememberValueBars(engine: MappingEngine, marks: List<ValueMark>, active: () -> Boolean, bars: Boolean = true, frames: Boolean = true): ValueBars {
    val vb = remember(marks, bars, frames) { ValueBars(marks, bars, frames) }
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
        // Inside or outside, once: from every line the button could show, not the ones it shows now.
        for ((k, mk) in vb.marks.withIndex()) {
            if (!mk.framed || mk.possible.isEmpty()) continue
            // In the button's middle when it fits there (a round one: in the square inside its circle).
            val room = if (mk.round) 0.7f else 1f
            val fitW = (mk.right - mk.left) * room - 2 * (inset + pad)
            val fitH = (mk.bottom - mk.top) * room - 2 * (inset + pad)
            fun fits(s: String) = text.measure(s, VALUE_STYLE).let { it.size.width <= fitW && it.size.height <= fitH }
            when {
                fits(mk.possible.joinToString("\n")) -> vb.inside[k] = true
                mk.possible.size > 1 && fits(mk.possible.joinToString(ROW)) -> { vb.inside[k] = true; vb.row[k] = true }
            }
        }
        // The mappings that get lines, of the set in use; those active now and the Toggles' states.
        var inUseSeen: List<Mapping>? = null
        var shownMs: List<Mapping> = emptyList()
        val toggles = HashMap<Mapping, Boolean>()
        val live = ArrayList<String>()
        val lastLive = arrayOfNulls<List<String>>(vb.marks.size)
        val liveUntil = LongArray(vb.marks.size)
        var first = true
        while (true) {
            // The first read is at once: a new overlay shows the values from its first frame.
            if (first) first = false else if (isActive()) withFrameMillis { } else delay(IDLE_MS)
            val now = SystemClock.uptimeMillis()
            var changed = false
            if (vb.bars) {
                val n = engine.valuesShown(readChips, readValues)
                changed = n != vb.chips.size || readChips != vb.chips
                if (!changed) for (i in 0 until 2 * n) if (readValues[i].toBits() != vb.values[i].toBits()) {
                    changed = true; break
                }
                if (changed) {
                    vb.chips.clear(); vb.chips.addAll(readChips)
                    readValues.copyInto(vb.values)
                }
            }
            var active: Set<Mapping> = emptySet()
            if (vb.frames) {
                val inUse = engine.touchMappingsInUse
                if (inUse !== inUseSeen) {
                    inUseSeen = inUse
                    shownMs = inUse.filter { frameId(it) != null && !valueLine(it) && framesMapping(it) }
                }
                active = engine.activeMappings(shownMs).toHashSet()
                engine.togglesOn(shownMs, toggles)
            }
            val counts = engine.workspace.gyro.gameCountsPer360
            var centered: MutableSet<String>? = null
            for ((k, mk) in vb.marks.withIndex()) {
                val label = buildAnnotatedString {
                    var any = false
                    fun line(s: String, c: Color?) {
                        if (any) append(if (vb.row[k]) ROW else "\n")
                        any = true
                        if (c == null) append(s) else withStyle(SpanStyle(color = c)) { append(s) }
                    }
                    if (vb.frames) {
                        live.clear()
                        for (m in shownMs) {
                            if (frameId(m) != mk.id) continue
                            val on = toggles[m]
                            val name = Describe.shortTarget(m.target)
                            if (on != null) line(name + if (on) ON else OFF, if (on) TOGGLE_ON else TOGGLE_OFF)
                            else if (m in active && name !in live) live.add(name)
                        }
                        // A tap's or long press's mapping fires for a moment: it stays a while longer.
                        if (live.isNotEmpty()) {
                            lastLive[k] = ArrayList(live); liveUntil[k] = now + LIVE_LINGER_MS
                        }
                        val shown = if (live.isNotEmpty()) live else if (now < liveUntil[k]) lastLive[k].orEmpty() else emptyList()
                        for (s in shown) line(s, null)
                    }
                    if (vb.bars) {
                        lines.clear()
                        for (j in vb.chips.indices) {
                            val m = vb.chips[j]
                            if ((MappingProfiles.touchId(m.source) ?: "") != mk.id) continue
                            // Chips at the same place sending the same share one line: "40 · Yaw Pitch 4.50 RWS".
                            val p = linePart(m, vb.values[2 * j + 1], counts)
                            val same = lines.firstOrNull { it.pct == p.pct && it.kind == p.kind && it.value == p.value }
                            if (same == null) lines.add(p) else if (p.names[0] !in same.names) same.names.add(p.names[0])
                        }
                        for (p in lines) line(p.text(), null)
                    }
                }.takeIf { it.isNotEmpty() }
                if (label != vb.labels[k]) {
                    vb.labels[k] = label
                    vb.laid[k] = label?.let { text.measure(it, VALUE_STYLE) }
                    changed = true
                }
                if (label != null && vb.inside[k]) (centered ?: HashSet<String>().also { centered = it }).add(mk.id)
            }
            val now2 = centered ?: emptySet()
            if (now2 != vb.centered) vb.centered = now2
            if (changed) vb.frame++
        }
    }
    return vb
}

/** The frames outside their buttons ([ValueBars]): drawn behind the buttons. */
@Composable
fun ValueFramesBehind(vb: ValueBars, modifier: Modifier = Modifier) {
    if (vb.marks.isEmpty()) return
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
 * The frames and value sliders over the buttons ([ValueBars]): on each mark a sliders icon in its
 * top-left corner when a chip on it is a Toggle value chip; with [ValueBars.bars], for each
 * Position value chip (Held or Toggle) a bar across the button where its value is (upright for X,
 * flat for Y, a ring for distance, a needle for angle; the part from 0 lightly filled, ticks at
 * its Snap steps), clipped by the button's edge; the frames that sit in the middle of their
 * button; the touchpad's in its top-right corner.
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
            if (vb.bars) {
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
                    // One bar per axis: the chips on one Position share it (the first one driven shows where it is).
                    for (axis in FingerAxis.entries) {
                        for (j in vb.chips.indices) {
                            val src = vb.chips[j].source as? InputSource.Finger ?: continue
                            if (src.id != mk.id || src.axis != axis) continue
                            val v = vb.values[2 * j]
                            if (v.isNaN()) continue
                            bar(mk, axis, v, color, snapTicks(mk.steps[axis], axis, mk))
                            break
                        }
                    }
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

/** How long a mapping line stays after its mapping stops (a tap's fires only for a moment). */
private const val LIVE_LINGER_MS = 700L

/** Lines on one row are this far apart. */
private const val ROW = "   "

/** A Toggle's line: its state after its name, green while on, grey while off. */
private const val ON = " on"
private const val OFF = " off"
private val TOGGLE_ON = Color(0xFF8FE39A)
private val TOGGLE_OFF = Color(0xFF9E9E9E)

/**
 * One chip's part of a corner line: where [out] is in its range (0..100), its kind, its own name
 * ("Yaw", "RT") and what it sends; chips alike but for the name share a line ([text]).
 */
private class LinePart(val pct: String, val kind: Int, name: String, val value: String) {
    val names = arrayListOf(name)

    /** "40 · Yaw Pitch 4.50 RWS", "40 · Yaw sens 16", "40 · Sens 16", "62 · RT 62 %", "50 · Tap 300 ms". */
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
        OutputTarget.TapTime -> LinePart(p, KIND_PAD, "Tap", "%.0f ms".format(out))
        is OutputTarget.GyroGlide, is OutputTarget.TiltRange ->
            LinePart(p, KIND_PAD, Describe.shortTarget(t), if (t is OutputTarget.GyroGlide) "%.0f ms".format(out) else "%.0f°".format(out))
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
 * The ruler ticks of a Position on [mk] along [axis], as (place 0..1, long) pairs: with its Snap
 * ([n] steps, the ones its value lands on) one at each step (every 2nd, 5th, 10th… when they would
 * sit closer than [MIN_TICK_DP]; every 5th shown one long), else every 10 % (long at 50 %).
 */
private fun DrawScope.snapTicks(n: Int?, axis: FingerAxis, mk: ValueMark): List<Pair<Float, Boolean>> {
    if (n == null) return DEFAULT_TICKS
    val w = mk.right - mk.left
    val h = mk.bottom - mk.top
    val length = when (axis) {
        FingerAxis.X -> w
        FingerAxis.Y -> h
        FingerAxis.DISTANCE -> minOf(w, h) / 2
        FingerAxis.ANGLE -> (minOf(w, h) * PI).toFloat()
    }
    val spacing = length / n
    if (spacing <= 0f) return DEFAULT_TICKS
    val min = MIN_TICK_DP.dp.toPx()
    var every = 1
    val thin = intArrayOf(1, 2, 5)
    var k = 0
    while (spacing * every < min && every < n) {
        k++
        every = thin[k % 3] * pow10(k / 3)
    }
    // The ends are the button's edges: no tick there.
    return (1 until n).filter { it % every == 0 }.map { i -> i.toFloat() / n to ((i / every) % 5 == 0) }
}

private fun pow10(e: Int): Int { var v = 1; repeat(e) { v *= 10 }; return v }

/** Without a Snap: a tick every 10 %, long at 50 %. */
private val DEFAULT_TICKS = (1..9).map { it / 10f to (it == 5) }

/** Snap ticks closer than this are thinned. */
private const val MIN_TICK_DP = 6f

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
