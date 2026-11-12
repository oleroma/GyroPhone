package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
 * right, bottom), [round] or not, and whether any chip on it has Set value ([set]: the sliders icon).
 */
class ValueMark(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val round: Boolean,
    val set: Boolean,
)

/**
 * Set value on the pad: on each [ValueMark], a sliders icon in its top-left corner when a chip on it
 * has Set value; for each Position chip with Set value, a bar across the button where its value is
 * (upright for X, flat for Y, a ring for distance, a needle for angle; the part from 0 lightly
 * filled), clipped by the button's edge; and in its top-right corner one line per chip, "62 · RT
 * 62 %": where the value is in its range (0..100) and what it sends. A chip without Set value shows
 * only while it is driven. It reads the engine every frame while [active] (a finger is down), else
 * a few times a second, and redraws only when something changed.
 */
@Composable
fun ValueBarsOverlay(
    engine: MappingEngine, marks: List<ValueMark>, color: Color, active: () -> Boolean, modifier: Modifier = Modifier,
) {
    if (marks.isEmpty()) return
    var frame by remember { mutableLongStateOf(0L) }
    val text = rememberTextMeasurer()
    val style = TextStyle(color = Color.White, fontSize = 13.sp)
    // The chips shown, with their input and output, as last read; each mark's label laid out once per change.
    val chips = remember(marks) { ArrayList<Mapping>() }
    val values = remember(marks) { FloatArray(2 * MAX_SHOWN) }
    val labels = remember(marks) { arrayOfNulls<String>(marks.size) }
    val laid = remember(marks) { arrayOfNulls<TextLayoutResult>(marks.size) }
    val isActive by rememberUpdatedState(active)
    LaunchedEffect(marks) {
        val readChips = ArrayList<Mapping>()
        val readValues = FloatArray(values.size)
        val lines = ArrayList<String>()
        while (true) {
            if (isActive()) withFrameMillis { } else delay(IDLE_MS)
            val n = engine.valuesShown(readChips, readValues)
            var changed = n != chips.size || readChips != chips
            if (!changed) for (i in 0 until 2 * n) if (readValues[i].toBits() != values[i].toBits()) {
                changed = true; break
            }
            if (!changed) continue
            chips.clear(); chips.addAll(readChips)
            readValues.copyInto(values)
            val counts = engine.workspace.gyro.gameCountsPer360
            for ((k, mk) in marks.withIndex()) {
                lines.clear()
                for (j in chips.indices) {
                    val m = chips[j]
                    if ((MappingProfiles.touchId(m.source) ?: "") != mk.id) continue
                    val line = valueLine(m, values[2 * j + 1], counts)
                    if (line !in lines) lines.add(line)
                }
                val label = if (lines.isEmpty()) null else lines.joinToString("\n")
                if (label != labels[k]) {
                    labels[k] = label
                    laid[k] = label?.let { text.measure(it, style) }
                }
            }
            frame++
        }
    }
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_EXPRESSION") frame // redrawn when a value changed
        for ((k, mk) in marks.withIndex()) {
            val w = mk.right - mk.left
            val h = mk.bottom - mk.top
            if (w <= 0f || h <= 0f) continue
            // The bars, clipped by the button's edge.
            val clip = Path().apply {
                if (mk.round) addOval(androidx.compose.ui.geometry.Rect(mk.left, mk.top, mk.right, mk.bottom))
                else addRect(androidx.compose.ui.geometry.Rect(mk.left, mk.top, mk.right, mk.bottom))
            }
            clipPath(clip) {
                for (j in chips.indices) {
                    val m = chips[j]
                    val src = m.source as? InputSource.Finger ?: continue
                    if (src.id != mk.id || m.setValue == null) continue
                    val v = values[2 * j]
                    if (v.isNaN()) continue
                    bar(mk, src.axis, v, color)
                }
            }
            if (mk.set) tuneIcon(Offset(mk.left + 6.dp.toPx(), mk.top + 6.dp.toPx()), color)
            val label = laid[k] ?: continue
            val pad = 6.dp.toPx()
            val bw = label.size.width + 2 * pad
            val bh = label.size.height + 2 * pad
            val bx = mk.right - bw - pad
            val by = mk.top + pad
            drawRoundRect(Color(0xEB141418), Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()))
            drawRoundRect(Color(0xFF666666), Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawText(label, topLeft = Offset(bx + pad, by + pad))
        }
    }
}

/** At most this many value chips show at once. */
private const val MAX_SHOWN = 32

/** How often the values are read while no finger is down (a timer or a Let go may end one). */
private const val IDLE_MS = 120L

/**
 * One chip's corner line: where [out] is in its range (0..100) and what it sends ("62 · RT 62 %",
 * "40 · Sens 4.50"); [counts] = the workspace game's counts per 360 for RWS (0 = none).
 */
private fun valueLine(m: Mapping, out: Float, counts: Float): String {
    val r = m.range
    val pct = if (r != null && r.to != r.from) (out - r.from) / (r.to - r.from) else out
    val sends = when (m.target) {
        is OutputTarget.GyroSens -> if (counts > 0f) "%.2f".format(GameSens.rws(out, counts)) else "%.1f".format(out)
        is OutputTarget.TouchSens -> "%.2f".format(out)
        else -> "%.0f %%".format(kotlin.math.abs(out) * 100)
    }
    return "%.0f".format(pct.coerceIn(0f, 1f) * 100) + " · " + Describe.shortTarget(m.target) + " " + sends
}

/** A Position chip's bar on [mk] at its value [v] (0..1) of [axis]: the part from 0 lightly filled, a line at the value. */
private fun DrawScope.bar(mk: ValueMark, axis: FingerAxis, v: Float, c: Color) {
    val w = mk.right - mk.left
    val h = mk.bottom - mk.top
    val fill = c.copy(alpha = c.alpha * 0.18f)
    val line = 3.dp.toPx()
    when (axis) {
        FingerAxis.X -> {
            val x = mk.left + v * w
            drawRect(fill, Offset(mk.left, mk.top), Size(x - mk.left, h))
            drawLine(c, Offset(x, mk.top), Offset(x, mk.bottom), line)
        }
        FingerAxis.Y -> {
            val y = mk.bottom - v * h
            drawRect(fill, Offset(mk.left, y), Size(w, mk.bottom - y))
            drawLine(c, Offset(mk.left, y), Offset(mk.right, y), line)
        }
        FingerAxis.DISTANCE -> {
            val centre = Offset((mk.left + mk.right) / 2, (mk.top + mk.bottom) / 2)
            val r = minOf(w, h) / 2 * v
            drawCircle(fill, r, centre)
            drawCircle(c, r, centre, style = Stroke(line))
        }
        FingerAxis.ANGLE -> {
            val centre = Offset((mk.left + mk.right) / 2, (mk.top + mk.bottom) / 2)
            val r = minOf(w, h) / 2
            val sweep = v * 360f
            drawArc(fill, -90f, sweep, true, Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r))
            val a = (v * 2 * PI - PI / 2).toFloat()
            drawLine(c, centre, Offset(centre.x + cos(a) * r, centre.y + sin(a) * r), line, StrokeCap.Round)
        }
    }
}

/** The Set value mark: a small sliders icon (three lines with knobs) with its top-left at [at]. */
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
