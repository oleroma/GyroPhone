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
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.SurfaceField
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.ZoneKeep
import com.oleroma.gyrophone.core.model.directionsAt
import kotlin.math.roundToInt

/** Same top as the workspace touchpad's speed sliders had. */
private const val MAX_SURFACE_SENS = 12f

/**
 * The settings a touch surface's actions share (the mapping editor's Settings): movement and
 * scrolling, then the swipe directions (pies, how far a swipe and a held direction take, how held
 * directions work, the latch). Each shows [level]'s value, highlighted with a ↺ where it differs
 * from the parent. [touchpad]: the workspace touchpad, with its own on / off and glide.
 * [zones]: how many length zones each direction has mapped.
 */
@Composable
internal fun SurfaceSharedSettings(level: SurfaceLevel, key: String, zones: IntArray, touchpad: Boolean) {
    val current by rememberUpdatedState(level)
    val s = level.eff
    fun edit(change: (TouchpadSettings) -> TouchpadSettings) = current.edit(change)
    fun own(f: SurfaceField) = level.isOwn(f)
    fun reset(f: SurfaceField): () -> Unit = { current.reset(f) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ResetAllButton(level.parentName, level.anyOwn) { current.resetAll() }
        SectionTitle("Movement & scrolling")
        if (touchpad) {
            OverrideSwitch("Enabled", null, s.enabled, own(SurfaceField.ENABLED), reset(SurfaceField.ENABLED)) { v ->
                edit { it.copy(enabled = v) }
            }
        }
        OverrideSlider(key + "sx", "X speed %.1f", s.sensitivity, 0f..MAX_SURFACE_SENS, 0.1f, own(SurfaceField.SENSITIVITY), reset(SurfaceField.SENSITIVITY)) { v ->
            edit { it.copy(sensitivity = v) }
        }
        OverrideSlider(
            key + "sy", "Y speed %.1f", s.verticalSensitivity, 0f..MAX_SURFACE_SENS, 0.1f, own(SurfaceField.SENSITIVITY_Y), reset(SurfaceField.SENSITIVITY_Y),
            hint = "0 turns that direction off, e.g. X 0 for one that only moves up and down.",
        ) { v -> edit { it.copy(sensitivityY = v) } }
        OverrideSlider(key + "acc", "Acceleration %.1f", s.acceleration, 0f..2f, 0.1f, own(SurfaceField.ACCELERATION), reset(SurfaceField.ACCELERATION)) { v ->
            edit { it.copy(acceleration = v) }
        }
        OverrideSlider(key + "scroll", "Scroll speed %.2f", s.scrollSpeed, 0.25f..3f, 0.05f, own(SurfaceField.SCROLL_SPEED), reset(SurfaceField.SCROLL_SPEED)) { v ->
            edit { it.copy(scrollSpeed = v) }
        }
        OverrideSwitch(
            "Natural scrolling",
            if (s.naturalScroll) "Content follows the fingers." else "Content moves against the fingers.",
            s.naturalScroll, own(SurfaceField.NATURAL_SCROLL), reset(SurfaceField.NATURAL_SCROLL),
        ) { v -> edit { it.copy(naturalScroll = v) } }
        OverrideSwitch(
            "Inertia",
            if (s.inertia) "Keeps scrolling after a flick." else "Scrolling stops when the fingers lift.",
            s.inertia, own(SurfaceField.INERTIA), reset(SurfaceField.INERTIA),
        ) { v -> edit { it.copy(inertia = v) } }
        if (touchpad && s.inertia) {
            OverrideSlider(
                key + "glide", "Glide %.0f ms", s.inertiaGlideMs, TouchpadSettings.MIN_GLIDE_MS..TouchpadSettings.MAX_GLIDE_MS, 5f,
                own(SurfaceField.INERTIA_GLIDE), reset(SurfaceField.INERTIA_GLIDE),
            ) { v -> edit { it.copy(inertiaGlideMs = v) } }
        }
        OverrideSlider(
            key + "dz", if (s.cursorDeadzoneDp < 1f) "Cursor deadzone: off" else "Cursor deadzone %.0f dp",
            s.cursorDeadzoneDp, 0f..TouchpadSettings.MAX_CURSOR_DEADZONE_DP, 1f, own(SurfaceField.CURSOR_DEADZONE), reset(SurfaceField.CURSOR_DEADZONE),
            hint = "One finger: the cursor stays still until the finger has moved this far, then catches up with it.",
        ) { v -> edit { it.copy(cursorDeadzoneDp = v) } }
        if (s.cursorDeadzoneDp > 0f) {
            OverrideSlider(
                key + "catch", if (s.catchUpMs < 1) "Catch-up: instant" else "Catch-up %.0f ms",
                s.catchUpMs.toFloat(), 0f..TouchpadSettings.MAX_CATCH_UP_MS.toFloat(), 10f, own(SurfaceField.CATCH_UP), reset(SurfaceField.CATCH_UP),
            ) { v -> edit { it.copy(catchUpMs = v.roundToInt()) } }
            OverrideSlider(
                key + "rearm", if (s.deadzoneRearmMs < 1) "Re-arm: off" else "Re-arm after %.0f ms still",
                s.deadzoneRearmMs.toFloat(), 0f..TouchpadSettings.MAX_REARM_MS.toFloat(), 10f, own(SurfaceField.REARM), reset(SurfaceField.REARM),
                hint = "The deadzone arms again once the finger stands still this long. Off: once per touch.",
            ) { v -> edit { it.copy(deadzoneRearmMs = v.roundToInt()) } }
            if (s.deadzoneRearmMs > 0) {
                OverrideSlider(
                    key + "still", "Still within %.1f dp", s.deadzoneStillDp, 0f..TouchpadSettings.MAX_STILL_DP, 0.5f,
                    own(SurfaceField.STILL), reset(SurfaceField.STILL),
                    hint = "Movement smaller than this still counts as standing still.",
                ) { v -> edit { it.copy(deadzoneStillDp = v) } }
            }
        }
        OverrideSlider(
            key + "vlock", if (s.verticalLockDeg < 1f) "Vertical lock: off" else "Vertical lock %.0f°",
            s.verticalLockDeg, 0f..TouchpadSettings.MAX_LOCK_DEG, 1f, own(SurfaceField.VERTICAL_LOCK), reset(SurfaceField.VERTICAL_LOCK),
            hint = "Motion within this angle of straight up / down loses its sideways part.",
        ) { v -> edit { it.copy(verticalLockDeg = v) } }
        OverrideSlider(
            key + "hlock", if (s.horizontalLockDeg < 1f) "Horizontal lock: off" else "Horizontal lock %.0f°",
            s.horizontalLockDeg, 0f..TouchpadSettings.MAX_LOCK_DEG, 1f, own(SurfaceField.HORIZONTAL_LOCK), reset(SurfaceField.HORIZONTAL_LOCK),
            hint = "Motion within this angle of sideways loses its up / down part. Cursor, scroll, sticks and held directions alike.",
        ) { v -> edit { it.copy(horizontalLockDeg = v) } }

        SectionTitle("Directions")
        DirectionPies(s)
        OverrideRow(
            "Classic directions",
            own(SurfaceField.PIES),
            reset(SurfaceField.PIES),
            hint = if (s.pies == null) "A swipe goes the way of its larger axis; held directions from the start point work per axis, so diagonals hold both."
            else "Each direction counts inside its own pie: wider pies overlap (both count there), narrower ones leave gaps.",
            trailing = {
                Switch(checked = s.pies == null, onCheckedChange = { classic ->
                    edit { it.copy(pies = if (classic) null else List(4) { TouchpadSettings.CLASSIC_PIE_DEG }) }
                })
            },
        ) {
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
        }
        OverrideSlider(
            key + "sample", "Direction sample %.0f dp", s.dirSampleDp,
            TouchpadSettings.MIN_DIR_SAMPLE_DP..TouchpadSettings.MAX_DIR_SAMPLE_DP, 1f, own(SurfaceField.DIR_SAMPLE), reset(SurfaceField.DIR_SAMPLE),
            hint = "The direction is that of about the last this much travel, so small wobbles don't change it.",
        ) { v -> edit { it.copy(dirSampleDp = v) } }
        OverrideSlider(
            key + "swipe", "Swipe after %.0f dp", s.swipeDp,
            TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f, own(SurfaceField.SWIPE), reset(SurfaceField.SWIPE),
            hint = "Measured from where the finger turned into that direction. Each new direction swipes again.",
        ) { v -> edit { it.copy(swipeDp = v) } }
        OverrideRow(
            "Held directions",
            own(SurfaceField.HOLD_MODE),
            reset(SurfaceField.HOLD_MODE),
            hint = if (s.holdMode == HoldMode.FROM_START) "Held while the finger is past the hold distance from the start point, like a floating stick: sliding back lets go."
            else "Held once the finger has gone the hold distance that way, until it goes another way as far or lifts. There is no neutral spot.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in HoldMode.entries) SelectChip(m.label, s.holdMode == m) { edit { it.copy(holdMode = m) } }
            }
        }
        OverrideSlider(
            key + "hold", "Hold after %.0f dp", s.holdDp,
            TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f, own(SurfaceField.HOLD), reset(SurfaceField.HOLD),
        ) { v -> edit { it.copy(holdDp = v) } }
        if (s.holdMode == HoldMode.FROM_START) {
            val zoned = zones.any { it > 1 }
            OverrideSwitch(
                "Drag start point",
                if (zoned && !s.dragStart) "Off while held directions have length zones: they need the start point to stay put."
                else "The start point follows the finger, so sliding back only the release travel lets go, wherever the finger is.",
                s.dragStart, own(SurfaceField.DRAG_START), reset(SurfaceField.DRAG_START),
                enabled = s.dragStart || !zoned,
            ) { v -> edit { it.copy(dragStart = v) } }
            if (s.dragStart) {
                OverrideSlider(
                    key + "release", "Release after %.0f dp back", s.releaseDp, 0f..TouchpadSettings.MAX_RELEASE_DP, 1f,
                    own(SurfaceField.RELEASE), reset(SurfaceField.RELEASE),
                ) { v -> edit { it.copy(releaseDp = v) } }
            }
        }
        OverrideSwitch(
            "Keep held after lift",
            "A held direction stays held when the fingers lift. A tap lets it go; swiping on carries on from it.",
            s.latch, own(SurfaceField.LATCH), reset(SurfaceField.LATCH),
        ) { v -> edit { it.copy(latch = v) } }
        if (s.latch) {
            OverrideSlider(
                key + "latchMs", if (s.latchReleaseMs < 1) "Let go by itself: never" else "Let go by itself after %.1f s",
                s.latchReleaseMs / 1000f, 0f..TouchpadSettings.MAX_LATCH_RELEASE_MS / 1000f, 0.1f,
                own(SurfaceField.LATCH_RELEASE), reset(SurfaceField.LATCH_RELEASE),
            ) { v -> edit { it.copy(latchReleaseMs = (v * 1000).roundToInt()) } }
        }
        DirNote(
            if (zones.any { it > 1 }) "Length zones: tap ⚙ on a Swipe hold, or the ┃ divider that ends a zone."
            else "Length zones: drag a mapping past the ┃ divider at the end of a Swipe hold card, so it fires further along."
        )
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

/**
 * The settings of length zone [zone] (from 0; null = every zone) of held direction [only]
 * ([TouchpadSettings.holdZone]), one of the [zones] each direction has mapped: the mapping
 * editor's divider ending that zone, or the ⚙ of the Swipe hold (every zone).
 * [onChange] gets the shared zones and the per-direction ones (null = the same for every direction).
 */
@Composable
internal fun HoldZoneSettings(
    s: TouchpadSettings,
    zones: IntArray,
    key: String,
    only: Direction,
    zone: Int?,
    onChange: (List<HoldZone>, List<List<HoldZone>>?) -> Unit,
) {
    val current by rememberUpdatedState(s)
    val mapped = zones[only.ordinal]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.dragStart && s.holdMode == HoldMode.FROM_START) {
            DirNote("Drag start point is on, so only the first zone works. Turn it off to use the zones.")
            return@Column
        }
        if (mapped < 2) {
            DirNote("Zone settings work once a second zone has mappings: drag one past this ┃ divider.")
            return@Column
        }
        val same = s.dirHoldZones == null
        DirSwitch("Same zones for every direction", "Off: each direction has its own zone lengths and modes.", same) { on ->
            val t = current
            if (on) onChange(t.dirHoldZones?.getOrNull(only.ordinal) ?: t.holdZones, null)
            else onChange(t.holdZones, List(4) { t.holdZones })
        }
        DirNote(
            if (s.holdMode == HoldMode.FROM_START) "The first zone starts at the hold distance. The finger's distance from the start point picks the zone, so sliding back goes back a zone."
            else "The first zone starts at the hold distance. A zone counts the travel since the direction was held and never goes back."
        )
        // Shared: as many as the direction with the most; the others' extra zones use the same.
        val n = if (same) Direction.entries.maxOf { zones[it.ordinal] } else mapped
        val shown = if (zone == null) 0 until mapped else zone.coerceIn(0, mapped - 1)..zone.coerceIn(0, mapped - 1)
        for (i in shown) {
            ZoneList(current, only, n, i, key + "zones" + if (same) "" else only.name) { list ->
                val t = current
                if (same) onChange(list, null)
                else {
                    val all = (t.dirHoldZones ?: List(4) { t.holdZones }).toMutableList()
                    all[only.ordinal] = list
                    onChange(t.holdZones, all)
                }
            }
        }
    }
}

/**
 * Zone [i] of the [n] zones of [d]: where it starts, on reach or on release, how a reached zone
 * is held ([ZoneKeep]: Always, Only forward, Only in zone), and its length (not the last one's:
 * it goes on).
 */
@Composable
private fun ZoneList(s: TouchpadSettings, d: Direction, n: Int, i: Int, key: String, onChange: (List<HoldZone>) -> Unit) {
    val list = s.dirHoldZones?.getOrNull(d.ordinal) ?: s.holdZones
    fun set(z: HoldZone) =
        onChange(List(maxOf(list.size, i + 1)) { j -> if (j == i) z else list.getOrNull(j) ?: HoldZone() })
    var start = s.holdDp.coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)
    for (j in 0 until i) start += s.holdZoneDp(d, j)
    val z = s.holdZone(d, i)
    val last = i == n - 1
    Text(
        "Zone ${i + 1} · from %.0f dp".format(start) + if (last) " on" else "",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectChip("On reach", !z.onRelease) { set(z.copy(onRelease = false)) }
        SelectChip("On release", z.onRelease) { set(z.copy(onRelease = true, keep = ZoneKeep.OFF)) }
    }
    if (z.onRelease) DirNote("Fires once when the finger lifts in this zone.")
    else {
        Text("Held", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in listOf(ZoneKeep.UNTIL_LIFT, ZoneKeep.GOING_ON, ZoneKeep.OFF)) {
                // Only forward needs a zone after it.
                if (k == ZoneKeep.GOING_ON && last && z.keep != k) continue
                SelectChip(k.label, z.keep == k) { set(z.copy(keep = k)) }
            }
        }
        DirNote(
            when (z.keep) {
                ZoneKeep.UNTIL_LIFT -> "Once reached, held wherever the finger goes until it lifts."
                ZoneKeep.GOING_ON -> "Held while the finger is in this zone or goes on into the next ones; sliding back below it lets go."
                ZoneKeep.OFF -> "Held while the finger is in this zone."
            }
        )
    }
    if (!last) {
        DirSlider(key + i, "Length %.0f dp", s.holdZoneDp(d, i), TouchpadSettings.MIN_ZONE_DP..TouchpadSettings.MAX_ZONE_DP, 1f) { v ->
            set(z.copy(lengthDp = v))
        }
    }
}

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
private fun DirSwitch(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            DirNote(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun DirNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
