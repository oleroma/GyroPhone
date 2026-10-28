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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.directionsAt
import kotlin.math.roundToInt

/**
 * Swipe directions of a touch surface ([TouchpadSettings] direction settings): the direction pies,
 * how far a swipe and a held direction take, how held directions work, and the latch.
 * [s] is the settings in effect; [onChange] gets them changed.
 */
@Composable
internal fun DirectionSettings(s: TouchpadSettings, key: String, onChange: (TouchpadSettings) -> Unit) {
    val current by rememberUpdatedState(s)
    fun edit(change: (TouchpadSettings) -> TouchpadSettings) = onChange(change(current))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DirectionPies(s)
        DirSwitch(
            "Classic directions",
            if (s.pies == null) "A swipe goes the way of its larger axis; held directions from the start point work per axis, so diagonals hold both."
            else "Each direction counts inside its own pie: wider pies overlap (both count there), narrower ones leave gaps.",
            s.pies == null,
        ) { classic -> edit { it.copy(pies = if (classic) null else List(4) { TouchpadSettings.CLASSIC_PIE_DEG }) } }
        val pies = s.pies
        if (pies != null) {
            val same = pies.distinct().size == 1
            DirSwitch("Same for all", "One width for all four pies.", same) { on ->
                edit { it.copy(pies = if (on) List(4) { pies[0] } else pies) }
            }
            if (same) {
                DirSlider(key + "pie", "Pies %.0f°", pies[0], TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f) { v ->
                    edit { it.copy(pies = List(4) { v }) }
                }
            } else for (d in Direction.entries) {
                DirSlider(key + "pie" + d.name, d.label + " %.0f°", s.pieDeg(d), TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f) { v ->
                    edit { t -> t.copy(pies = (t.pies ?: pies).toMutableList().also { it[d.ordinal] = v }) }
                }
            }
        }
        DirSlider(
            key + "sample", "Direction sample %.0f dp", s.dirSampleDp,
            TouchpadSettings.MIN_DIR_SAMPLE_DP..TouchpadSettings.MAX_DIR_SAMPLE_DP, 1f,
        ) { v -> edit { it.copy(dirSampleDp = v) } }
        DirNote("The direction is that of about the last this much travel, so small wobbles don't change it.")
        DirSlider(
            key + "swipe", "Swipe after %.0f dp", s.swipeDp,
            TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f,
        ) { v -> edit { it.copy(swipeDp = v) } }
        DirNote("Measured from where the finger turned into that direction. Each new direction swipes again.")

        Text("Held directions", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in HoldMode.entries) SelectChip(m.label, s.holdMode == m) { edit { it.copy(holdMode = m) } }
        }
        DirNote(
            if (s.holdMode == HoldMode.FROM_START) "Held while the finger is past the hold distance from the start point, like a floating stick: sliding back lets go."
            else "Held once the finger has gone the hold distance that way, until it goes another way as far or lifts. There is no neutral spot."
        )
        DirSlider(
            key + "hold", "Hold after %.0f dp", s.holdDp,
            TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f,
        ) { v -> edit { it.copy(holdDp = v) } }
        if (s.holdMode == HoldMode.FROM_START) {
            DirSwitch(
                "Drag start point",
                "The start point follows the finger, so sliding back only the release travel lets go, wherever the finger is.",
                s.dragStart,
            ) { v -> edit { it.copy(dragStart = v) } }
            if (s.dragStart) {
                DirSlider(key + "release", "Release after %.0f dp back", s.releaseDp, 0f..TouchpadSettings.MAX_RELEASE_DP, 1f) { v ->
                    edit { it.copy(releaseDp = v) }
                }
            }
        }
        DirSwitch(
            "Keep held after lift",
            "A held direction stays held when the fingers lift. A tap lets it go; swiping on carries on from it.",
            s.latch,
        ) { v -> edit { it.copy(latch = v) } }
        if (s.latch) {
            DirSlider(
                key + "latchMs", if (s.latchReleaseMs < 1) "Let go by itself: never" else "Let go by itself after %.1f s",
                s.latchReleaseMs / 1000f, 0f..TouchpadSettings.MAX_LATCH_RELEASE_MS / 1000f, 0.1f,
            ) { v -> edit { it.copy(latchReleaseMs = (v * 1000).roundToInt()) } }
        }
    }
}

/** A swipe button's direction settings: the touchpad's until its "Touchpad" switch goes off. */
@Composable
internal fun SurfaceDirectionSettings(own: SurfaceSettings, eff: TouchpadSettings, key: String, onChange: (SurfaceSettings) -> Unit) {
    val follows = own.directionsFollow
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Directions", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text("Touchpad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = follows, onCheckedChange = { f -> onChange(if (f) own.followDirections() else own.withDirections(eff)) })
    }
    if (!follows) DirectionSettings(eff, key) { onChange(own.withDirections(it)) }
}

/** No direction setting of its own: all follow the touchpad. */
private val SurfaceSettings.directionsFollow: Boolean
    get() = dirSampleDp == null && swipeDp == null && holdDp == null && holdMode == null && dragStart == null &&
            releaseDp == null && pies == null && classicPies == null && latch == null && latchReleaseMs == null

private fun SurfaceSettings.followDirections() = copy(
    dirSampleDp = null, swipeDp = null, holdDp = null, holdMode = null, dragStart = null,
    releaseDp = null, pies = null, classicPies = null, latch = null, latchReleaseMs = null,
)

/** [t]'s direction settings as this surface's own. */
private fun SurfaceSettings.withDirections(t: TouchpadSettings) = copy(
    dirSampleDp = t.dirSampleDp, swipeDp = t.swipeDp, holdDp = t.holdDp, holdMode = t.holdMode,
    dragStart = t.dragStart, releaseDp = t.releaseDp, pies = t.pies, classicPies = t.pies == null,
    latch = t.latch, latchReleaseMs = t.latchReleaseMs,
)

/** The four pies, with a live dot: touching the circle shows which directions that way counts as. */
@Composable
private fun DirectionPies(s: TouchpadSettings) {
    val colors = MaterialTheme.colorScheme
    val pieColors = listOf(colors.primary, colors.tertiary, colors.secondary, colors.error)
    var touch by remember { mutableStateOf<Offset?>(null) }
    val at = touch
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(
            Modifier
                .size(140.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        touch = down.position - c
                        do {
                            val e = awaitPointerEvent()
                            val p = e.changes.firstOrNull() ?: break
                            touch = p.position - c
                            p.consume()
                        } while (p.pressed)
                        touch = null
                    }
                },
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(colors.outline, r, c, style = Stroke(1.dp.toPx()))
            for (d in Direction.entries) {
                // Classic: quadrants for swipes (held directions from the start point work per axis).
                val w = s.pieDeg(d)
                drawArc(
                    pieColors[d.ordinal].copy(alpha = 0.35f),
                    startAngle = d.deg - 90f - w / 2f, sweepAngle = w, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                )
            }
            if (at != null) {
                val len = at.getDistance()
                val p = if (len > r) at * (r / len) else at
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
            val mask = if (at == null) -1 else s.directionsAt(at.x, at.y)
            Text(
                when (mask) {
                    -1 -> "Touch the circle to try a direction."
                    0 -> "No direction (gap)"
                    else -> Direction.entries.filter { mask and (1 shl it.ordinal) != 0 }.joinToString(" + ") { it.label }
                },
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (at != null) Color.Unspecified else colors.onSurfaceVariant,
            )
        }
    }
}

/** A slider whose label [format] shows the value; it snaps to [step] and hands the value on once the drag ends. */
@Composable
private fun DirSlider(
    key: String,
    format: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onDone: (Float) -> Unit,
) {
    var v by remember(key, value) { mutableStateOf(value) }
    Column {
        Text(format.format(v), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = v,
            onValueChange = { v = (it / step).roundToInt() * step },
            valueRange = range,
            onValueChangeFinished = { onDone(v) },
        )
    }
}

@Composable
private fun DirSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            DirNote(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DirNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
