package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.CurvePoint
import com.oleroma.gyrophone.core.model.GameSens
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroCurves
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.runtime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

private val PRESETS = listOf("Light" to GyroCurve.LIGHT, "Strong" to GyroCurve.STRONG)

/**
 * A gyro acceleration setting ([GyroCurve]): Off, a preset, or the person's own points, edited on
 * a graph of turning speed (across) against sensitivity (up: RWS once [g]'s game is known, else
 * counts per degree), at base sensitivity [sens]. [perAxis]: the "Speed from" choice names the
 * axis ("This axis") rather than each axis ("Each axis"). [onSet] gets the tidied curve.
 */
@Composable
internal fun AccelCurveSetting(
    key: String,
    title: String,
    curve: GyroCurve,
    sens: Float,
    g: GyroSettings,
    overridden: Boolean,
    onReset: (() -> Unit)?,
    hint: String,
    perAxis: Boolean = false,
    axis: MotionAxis? = null,
    children: ChildInfo? = null,
    onSet: (GyroCurve) -> Unit,
) {
    var draft by remember(key, curve) { mutableStateOf(curve) }
    var dragged by remember(key, curve) { mutableIntStateOf(-1) }
    val counts = g.gameCountsPer360
    fun shown(gain: Float): String = shownGain(gain, sens, counts)

    OverrideRow(title, overridden, onReset, hint, children = children) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = draft.isOff, onClick = { onSet(GyroCurve.OFF) }, label = { Text("Off") })
            for ((name, p) in PRESETS) {
                FilterChip(
                    selected = draft.points == p.points,
                    onClick = { onSet(p.copy(axisSpeed = draft.axisSpeed, smooth = draft.smooth, range = draft.range)) },
                    label = { Text(name) },
                )
            }
            FilterChip(
                selected = !draft.isOff && PRESETS.none { it.second.points == draft.points },
                onClick = { if (draft.isOff) onSet(GyroCurve.LIGHT.copy(axisSpeed = draft.axisSpeed, smooth = draft.smooth, range = draft.range)) },
                label = { Text("Own") },
            )
        }
        if (!draft.isOff) {
            val pts = draft.points
            // Live: how fast the phone turns now, as a line across the graph (one per axis for a shared per-axis curve).
            val runtime = LocalContext.current.runtime
            DisposableEffect(runtime) {
                runtime.watchAccel()
                onDispose { runtime.unwatchAccel() }
            }
            val axes: List<MotionAxis?> = when {
                !draft.axisSpeed -> listOf(null)
                axis != null -> listOf(axis)
                else -> MotionAxis.shown
            }
            // Read only where it is drawn (the graph's line, the "Now" text), so a new speed redraws just those;
            // updated about 30 times a second, and only when it moved by a visible amount.
            val liveState = remember { mutableStateOf(FloatArray(0)) }
            LaunchedEffect(runtime, axes) {
                while (true) {
                    delay(LIVE_MS)
                    val now = axes.mapNotNull { runtime.liveGyroSpeed(it) }.toFloatArray()
                    val was = liveState.value
                    if (now.size != was.size || now.indices.any { abs(now[it] - was[it]) > LIVE_STEP_DPS }) liveState.value = now
                }
            }
            val curvePath = remember { CurvePath() }
            // The graph's span stays put while a point is dragged, so it doesn't run away under the finger.
            // The "°/s cap" sets it; unset, it fits the points. It never cuts off a point.
            val lastSpeed = curve.points.maxOfOrNull { it.speed } ?: 0f
            val maxSpeed = remember(key, curve) {
                if (curve.range > 0f) max(curve.range, niceCeil(lastSpeed)) else niceCeil(max(200f, lastSpeed * 1.3f))
            }
            val maxGain = remember(key, curve) { max(2f, (curve.points.maxOfOrNull { it.gain } ?: 1f) * 1.3f) }
            val colors = MaterialTheme.colorScheme
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .padding(vertical = 6.dp)
                    .pointerInput(key, curve) {
                        fun toPoint(o: Offset) = CurvePoint(
                            (o.x / size.width * maxSpeed).coerceIn(0f, GyroCurve.MAX_SPEED),
                            ((1f - o.y / size.height) * maxGain).coerceIn(GyroCurve.MIN_GAIN, GyroCurve.MAX_GAIN),
                        )
                        fun nearest(o: Offset, within: Float): Int {
                            var best = -1
                            var bestD = within
                            draft.points.forEachIndexed { i, p ->
                                val d = hypot(p.speed / maxSpeed * size.width - o.x, (1f - p.gain / maxGain) * size.height - o.y)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            return best
                        }
                        detectTapGestures(
                            onTap = { o ->
                                if (draft.points.size < GyroCurve.MAX_POINTS && nearest(o, 24.dp.toPx()) < 0) {
                                    onSet(draft.copy(points = draft.points + toPoint(o)).tidy())
                                }
                            },
                            onLongPress = { o ->
                                val i = nearest(o, 32.dp.toPx())
                                // The last point goes only with Off.
                                if (i >= 0 && draft.points.size > 1) onSet(draft.copy(points = draft.points.filterIndexed { j, _ -> j != i }))
                            },
                        )
                    }
                    .pointerInput(key, curve) {
                        fun nearest(o: Offset): Int {
                            var best = -1
                            var bestD = 40.dp.toPx()
                            draft.points.forEachIndexed { i, p ->
                                val d = hypot(p.speed / maxSpeed * size.width - o.x, (1f - p.gain / maxGain) * size.height - o.y)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            return best
                        }
                        detectDragGestures(
                            onDragStart = { o -> dragged = nearest(o) },
                            onDragEnd = { if (dragged >= 0) onSet(draft.tidy()); dragged = -1 },
                            onDragCancel = { draft = curve; dragged = -1 },
                        ) { change, _ ->
                            val i = dragged
                            if (i < 0) return@detectDragGestures
                            change.consume()
                            val ps = draft.points
                            // A point stays between its neighbours, so the curve keeps rising in speed.
                            val lo = if (i > 0) ps[i - 1].speed + GyroCurve.MIN_GAP else 0f
                            val hi = if (i < ps.size - 1) ps[i + 1].speed - GyroCurve.MIN_GAP else maxSpeed
                            val o = change.position
                            val p = CurvePoint(
                                (o.x / size.width * maxSpeed).coerceIn(lo, max(lo, hi)),
                                ((1f - o.y / size.height) * maxGain).coerceIn(GyroCurve.MIN_GAIN, GyroCurve.MAX_GAIN),
                            ).quantized()
                            draft = draft.copy(points = ps.mapIndexed { j, q -> if (j == i) p else q })
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(speed: Float) = speed / maxSpeed * w
                fun y(gain: Float) = (1f - gain / maxGain) * h
                // Grid: every 50 or 100 deg/s, and gain 1 (the plain sensitivity) dashed.
                val stepX = if (maxSpeed > 400f) 100f else 50f
                var sx = stepX
                while (sx < maxSpeed) {
                    drawLine(colors.outlineVariant, Offset(x(sx), 0f), Offset(x(sx), h), 1f)
                    sx += stepX
                }
                drawLine(
                    colors.outline, Offset(0f, y(1f)), Offset(w, y(1f)), 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
                drawLine(colors.outline, Offset(0f, h), Offset(w, h), 2f)
                drawLine(colors.outline, Offset(0f, 0f), Offset(0f, h), 2f)
                // The curve, made again only when it or the graph's size or span changed.
                val c = curvePath
                if (c.of !== draft || c.w != w || c.h != h || c.maxSpeed != maxSpeed || c.maxGain != maxGain) {
                    c.of = draft; c.w = w; c.h = h; c.maxSpeed = maxSpeed; c.maxGain = maxGain
                    val path = c.path
                    path.reset()
                    path.moveTo(0f, y(pts.first().gain))
                    if (draft.smooth) {
                        // Sampled every few pixels through the same math the gyro uses.
                        val flat = draft.flat()
                        var px = 0f
                        while (px < w) {
                            path.lineTo(px, y(GyroCurves.gain(flat, px / w * maxSpeed, true)))
                            px += 3f
                        }
                    } else {
                        for (p in pts) path.lineTo(x(p.speed), y(p.gain))
                    }
                    path.lineTo(w, y(pts.last().gain))
                }
                drawPath(c.path, colors.primary, style = Stroke(width = 5f))
                for (v in liveState.value) {
                    val lx = x(v.coerceAtMost(maxSpeed))
                    drawLine(colors.tertiary, Offset(lx, 0f), Offset(lx, h), 3f)
                    drawCircle(colors.tertiary, 9f, Offset(lx, y(draft.gainAt(v))))
                }
                pts.forEachIndexed { i, p ->
                    drawCircle(if (i == dragged) colors.tertiary else colors.primary, if (i == dragged) 16f else 12f, Offset(x(p.speed), y(p.gain)))
                }
            }
            val d = dragged.takeIf { it in pts.indices }?.let { pts[it] }
            // One line that always shows, so the layout doesn't jump: the dragged point, else the speed now.
            if (d != null) {
                Text(
                    "Point %.0f°/s: %s (×%.2f)".format(d.speed, shown(d.gain), d.gain),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                NowLine({ liveState.value.maxOrNull() ?: 0f }, draft, sens, counts)
            }
            Text(
                "Across: turning speed, 0 to %.0f°/s; the coloured line is the speed now. Up: sensitivity; dashed = %s.".format(maxSpeed, shown(1f)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                "Tap to add a point (up to ${GyroCurve.MAX_POINTS}), drag one to move it, long-press one to remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Between points", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium)
                FilterChip(selected = !draft.smooth, onClick = { onSet(draft.copy(smooth = false)) }, label = { Text("Lines") })
                FilterChip(selected = draft.smooth, onClick = { onSet(draft.copy(smooth = true)) }, label = { Text("Smooth") })
            }
            // How far the graph reaches, so points can sit at fast flicks too. Only the graph: past the last point the gain stays flat.
            var cap by remember(key, curve) { mutableFloatStateOf(maxSpeed) }
            Text("°/s cap: %.0f°/s".format(cap), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = cap,
                onValueChange = { cap = (Math.round(it / 50f) * 50f).coerceIn(max(GyroCurve.MIN_RANGE, niceCeil(lastSpeed)), GyroCurve.MAX_SPEED) },
                onValueChangeFinished = { if (cap != maxSpeed) onSet(draft.copy(range = cap)) },
                valueRange = GyroCurve.MIN_RANGE..GyroCurve.MAX_SPEED,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Speed from", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium)
                FilterChip(selected = !draft.axisSpeed, onClick = { onSet(draft.copy(axisSpeed = false)) }, label = { Text("Whole phone") })
                FilterChip(
                    selected = draft.axisSpeed, onClick = { onSet(draft.copy(axisSpeed = true)) },
                    label = { Text(if (perAxis) "This axis" else "Each axis") },
                )
            }
        }
    }
}

/** The speed now's line under the graph: its own scope, so a new speed doesn't recompose the whole setting. */
@Composable
private fun NowLine(now: () -> Float, curve: GyroCurve, sens: Float, counts: Float) {
    val v = now()
    val gain = curve.gainAt(v)
    Text(
        "Now %.0f°/s: %s (×%.2f)".format(v, shownGain(gain, sens, counts), gain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Sensitivity [sens] times [gain] as the setting shows it: in RWS with the game's [counts] per 360, else plain. */
private fun shownGain(gain: Float, sens: Float, counts: Float): String =
    if (counts > 0f) "%.2f RWS".format(GameSens.rws(sens * gain, counts)) else "%.1f".format(sens * gain)

/** The graph's curve, kept from draw to draw with what it was made for. */
private class CurvePath {
    val path = Path()
    var of: GyroCurve? = null
    var w = 0f
    var h = 0f
    var maxSpeed = 0f
    var maxGain = 0f
}

// The live speed's update interval (ms) and the change (°/s) it must make to be shown.
private const val LIVE_MS = 33L
private const val LIVE_STEP_DPS = 0.5f

/** [v] rounded up to a tidy graph span (a multiple of 50 or 100). */
private fun niceCeil(v: Float): Float {
    val step = if (v > 400f) 100f else 50f
    return ceil(v / step) * step
}

/** The hint every acceleration setting shares. */
internal const val ACCEL_HINT = "Acceleration: slow turns use the sensitivity at the left of the graph, faster turns the sensitivity further right, " +
    "so you can aim finely and still turn around quickly. Whole phone: a diagonal flick speeds up both directions alike. " +
    "With the game's turn set, the game's turn per phone turn (RWS) is exact only where the line is flat."
