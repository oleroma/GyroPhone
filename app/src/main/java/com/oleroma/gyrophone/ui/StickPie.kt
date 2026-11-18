package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.StickTuning
import com.oleroma.gyrophone.runtime.StickWatch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A physical stick's tuning [t] as a picture, like the touchpad's pies: the four direction pies,
 * the inner deadzone, the outer deadzone's band and the Outer ring (dashed). A dot follows the
 * real stick ([right]: the right one) through [watch] while it is shown, or the finger on the
 * circle, with what that way presses. [only]: the direction whose card opened it, drawn bright
 * and the others faint (null: all alike).
 */
@Composable
internal fun StickPiePicture(t: StickTuning, watch: StickWatch, right: Boolean, only: Direction?) {
    val colors = MaterialTheme.colorScheme
    val pieColors = listOf(colors.primary, colors.tertiary, colors.secondary, colors.error)
    val ringColor = Color(0xFFFFD180)
    var touch by remember { mutableStateOf<Offset?>(null) }
    var stick by remember { mutableStateOf(Offset.Zero) }
    // The stick, read once a frame while shown: the readers feed the watch only meanwhile.
    DisposableEffect(watch) {
        watch.watch()
        onDispose { watch.stop() }
    }
    LaunchedEffect(watch, right) {
        while (true) {
            withFrameNanos { }
            val p = Offset(watch.x(right), watch.y(right))
            if (p != stick) stick = p
        }
    }
    val at = touch ?: stick.takeIf { hypot(it.x, it.y) > STICK_REST }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(
            Modifier
                .size(140.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val r = size.width.coerceAtMost(size.height) / 2f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        touch = (down.position - c) / r
                        do {
                            val e = awaitPointerEvent()
                            val p = e.changes.firstOrNull() ?: break
                            touch = (p.position - c) / r
                            p.consume()
                        } while (p.pressed)
                        touch = null
                    }
                },
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val outer = r * t.outer
            val inner = r * t.inner
            // Each pie as a band from the inner deadzone to the outer one: where it presses.
            val mid = (outer + inner) / 2f
            for (d in Direction.entries) {
                val w = pie(t, d)
                val faint = only != null && only != d
                drawArc(
                    pieColors[d.ordinal].copy(alpha = if (faint) 0.12f else 0.35f),
                    startAngle = d.deg - 90f - w / 2f, sweepAngle = w, useCenter = false,
                    topLeft = Offset(c.x - mid, c.y - mid), size = Size(2 * mid, 2 * mid),
                    style = Stroke(outer - inner),
                )
            }
            // Past the outer deadzone: all the way, whichever way.
            if (t.outer < 1f) {
                drawCircle(colors.onSurface.copy(alpha = 0.15f), (r + outer) / 2f, c, style = Stroke(r - outer))
            }
            drawCircle(colors.outline, r, c, style = Stroke(1.dp.toPx()))
            val ring = r * (t.inner + t.outerRing * (t.outer - t.inner))
            drawCircle(
                ringColor, ring, c,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
            drawCircle(colors.outline, inner, c, style = Stroke(1.dp.toPx()))
            if (at != null) {
                val len = at.getDistance()
                val p = (if (len > 1f) at / len else at) * r
                drawLine(colors.onSurface, c, c + p, 2.dp.toPx())
                drawCircle(colors.onSurface, 5.dp.toPx(), c + p)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            for (d in Direction.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawCircle(pieColors[d.ordinal].copy(alpha = 0.6f)) }
                    Text(d.label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) {
                    drawCircle(ringColor, size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                }
                Text("Outer ring", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (at == null) "Push the stick or touch the circle to try a way." else wayText(t, at.x, at.y),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (at != null) Color.Unspecified else colors.onSurfaceVariant,
            )
        }
    }
}

/** A stick this close to the centre is resting: no dot. */
private const val STICK_REST = 0.02f

private fun pie(t: StickTuning, d: Direction): Float =
    (if (d.x) t.pieX else t.pieY).coerceIn(StickTuning.MIN_PIE, StickTuning.MAX_PIE)

/** What the stick at ([x], [y]) (y down, 1 = all the way) presses with tuning [t], as the engine judges it. */
internal fun wayText(t: StickTuning, x: Float, y: Float): String {
    val r = hypot(x, y)
    if (r <= t.inner) return "Deadzone: nothing"
    val travel = ((r - t.inner) / (t.outer - t.inner).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    val dirs = Direction.entries.filter { d ->
        // Along the direction, and within half its pie of it (MappingEngine.inPie).
        val along = when (d) { Direction.UP -> -y; Direction.DOWN -> y; Direction.LEFT -> -x; Direction.RIGHT -> x }
        val other = if (d.x) y else x
        along > 0f && Math.toDegrees(atan2(abs(other), along).toDouble()) <= pie(t, d) / 2.0
    }
    val ways = dirs.joinToString(" + ") { it.label }.ifEmpty { "No direction (gap)" }
    return ways + " · %.0f%%".format(travel * 100) + if (travel >= t.outerRing) " · Outer" else ""
}
