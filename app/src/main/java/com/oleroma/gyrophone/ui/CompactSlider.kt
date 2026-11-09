package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's slider: a thin track and a round handle, in a short row. Dragging moves the handle
 * from where it is (it does not jump to the finger); a tap without dragging opens a box to type
 * the exact value ([TypeValueDialog]), which may go past the track to [typedRange] (default
 * [valueRange]). Same parameters as the Material slider the screens used.
 */
@Composable
internal fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    typedRange: ClosedFloatingPointRange<Float>? = null,
    typedTitle: String? = null,
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    var typing by remember { mutableStateOf(false) }
    HandleTrack(
        values = listOf(value),
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        onMove = { _, v -> change(v) },
        onDone = { finished?.invoke() },
        onTap = { typing = true },
    )
    if (typing) TypeValueDialog(typedTitle ?: "Set the value", value, typedRange ?: valueRange, onDismiss = { typing = false }) { v ->
        typing = false
        change(v)
        finished?.invoke()
    }
}

/**
 * A slider with two handles ([from] and [to]), which may pass each other ([from] above [to]
 * reverses what it sets). Dragging moves the handle nearest the finger; tapping opens a box to
 * type that handle's exact value ([fromTitle] / [toTitle]). [onChange] follows the drag,
 * [onDone] gets the values once it ends.
 */
@Composable
internal fun TwoHandleSlider(
    from: Float,
    to: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float, Float) -> Unit,
    onDone: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    typedRange: ClosedFloatingPointRange<Float>? = null,
    fromTitle: String = "From",
    toTitle: String = "To",
) {
    val a by rememberUpdatedState(from)
    val b by rememberUpdatedState(to)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var typing by remember { mutableIntStateOf(-1) }
    fun snap(v: Float) = if (step > 0f) (v / step).roundToInt() * step else v
    HandleTrack(
        values = listOf(from, to),
        valueRange = valueRange,
        steps = 0,
        enabled = true,
        modifier = modifier,
        onMove = { i, v -> if (i == 0) change(snap(v), b) else change(a, snap(v)) },
        onDone = { done(a, b) },
        onTap = { typing = it },
    )
    if (typing >= 0) {
        val i = typing
        TypeValueDialog(
            if (i == 0) fromTitle else toTitle, if (i == 0) from else to, typedRange ?: valueRange,
            onDismiss = { typing = -1 },
        ) { v ->
            typing = -1
            if (i == 0) done(v, to) else done(from, v)
        }
    }
}

/**
 * The track and handles of [Slider] and [TwoHandleSlider]: one handle fills from the start,
 * two fill between them. A drag past the touch slop moves the handle nearest where it began,
 * keeping the finger's distance to it; a lift before that is a tap ([onTap], with that handle).
 */
@Composable
private fun HandleTrack(
    values: List<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier,
    onMove: (Int, Float) -> Unit,
    onDone: () -> Unit,
    onTap: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val inactive = colors.surfaceVariant
    val current by rememberUpdatedState(values)
    val range by rememberUpdatedState(valueRange)
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onDone)
    val tap by rememberUpdatedState(onTap)
    val on by rememberUpdatedState(enabled)
    val radius = with(LocalDensity.current) { 9.dp.toPx() }
    fun frac(v: Float): Float {
        val span = range.endInclusive - range.start
        return if (span <= 0f) 0f else ((v - range.start) / span).coerceIn(0f, 1f)
    }
    fun snapped(v: Float): Float {
        val r = range
        val c = v.coerceIn(r.start, r.endInclusive)
        if (steps <= 0) return c
        val stepSize = (r.endInclusive - r.start) / (steps + 1)
        return r.start + ((c - r.start) / stepSize).roundToInt() * stepSize
    }
    Canvas(
        modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!on) return@awaitEachGesture
                val w = size.width.toFloat()
                val usable = (w - 2 * radius).coerceAtLeast(1f)
                fun xOf(v: Float) = radius + frac(v) * usable
                val vs = current
                var i = 0
                for (k in vs.indices) if (abs(xOf(vs[k]) - down.position.x) < abs(xOf(vs[i]) - down.position.x)) i = k
                val grab = xOf(vs[i]) - down.position.x
                val slop = viewConfiguration.touchSlop
                var dragging = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        if (dragging) done() else if (!c.isConsumed) tap(i)
                        c.consume()
                        break
                    }
                    if (!dragging) {
                        // A list scrolling took it, or the finger went up or down first: not ours.
                        if (c.isConsumed || abs(c.position.y - down.position.y) > slop) break
                        if (abs(c.position.x - down.position.x) > slop) dragging = true
                    }
                    if (dragging) {
                        val x = c.position.x + grab
                        move(i, snapped(range.start + ((x - radius) / usable) * (range.endInclusive - range.start)))
                        c.consume()
                    }
                }
            }
        },
    ) {
        val y = size.height / 2f
        val h = 4.dp.toPx()
        val usable = size.width - 2 * radius
        drawLine(inactive, Offset(radius, y), Offset(size.width - radius, y), h, StrokeCap.Round)
        val xs = values.map { radius + frac(it) * usable }
        val lo = if (xs.size == 1) radius else xs.min()
        val hi = xs.max()
        drawLine(active, Offset(lo, y), Offset(hi, y), h, StrokeCap.Round)
        for (x in xs) drawCircle(active, radius, Offset(x, y))
    }
}

/**
 * A box to type an exact value ([value] to start), kept inside [range]: Set takes it, Cancel or
 * tapping outside leaves it as it was.
 */
@Composable
internal fun TypeValueDialog(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onDismiss: () -> Unit,
    onSet: (Float) -> Unit,
) {
    val start = fmtTyped(value)
    var text by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    val parsed = text.text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
    fun set() {
        if (parsed != null) onSet(parsed.coerceIn(range.start, range.endInclusive))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                supportingText = { Text("${fmtTyped(range.start)} to ${fmtTyped(range.endInclusive)}") },
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { set() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { set() }, enabled = parsed != null) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A typed value as text: up to 3 decimals, no trailing zeros. */
private fun fmtTyped(f: Float): String = "%.3f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.').ifEmpty { "0" }
