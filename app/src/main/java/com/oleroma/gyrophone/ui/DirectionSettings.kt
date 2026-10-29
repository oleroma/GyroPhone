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
import com.oleroma.gyrophone.core.model.DirField
import com.oleroma.gyrophone.core.model.DirSettings
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.MoveField
import com.oleroma.gyrophone.core.model.MoveMode
import com.oleroma.gyrophone.core.model.MoveSlot
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
 * [zones]: how many length zones each direction has mapped. [resetAll]: with its own Reset all.
 */
@Composable
internal fun SurfaceSharedSettings(level: SurfaceLevel, key: String, zones: IntArray, touchpad: Boolean, resetAll: Boolean = true) {
    val current by rememberUpdatedState(level)
    val s = level.eff
    fun edit(change: (TouchpadSettings) -> TouchpadSettings) = current.edit(change)
    fun own(f: SurfaceField) = level.isOwn(f)
    fun reset(f: SurfaceField): () -> Unit = { current.reset(f) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (resetAll) ResetAllButton(level.parentName, level.anyOwn) { current.resetAll() }
        SectionTitle("Movement & scrolling")
        if (touchpad) {
            OverrideSwitch("Enabled", null, s.enabled, own(SurfaceField.ENABLED), reset(SurfaceField.ENABLED)) { v ->
                edit { it.copy(enabled = v) }
            }
        }
        DirNote("Every Move X and Move Y uses these unless its ⚙ sets its own, for each finger count.")
        OverrideSlider(
            key + "sx", "Sensitivity %.1f", s.sensitivity, 0f..MAX_SURFACE_SENS, 0.1f, own(SurfaceField.SENSITIVITY), reset(SurfaceField.SENSITIVITY),
            hint = "Counts per dp of finger travel. A Move X or Y's ⚙ can set its own: 0 turns that direction off.",
        ) { v -> edit { it.copy(sensitivity = v) } }
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
        OverrideRow(
            "Move direction", own(SurfaceField.MOVE_MODE), reset(SurfaceField.MOVE_MODE),
            hint = moveModeHint(s.moveMode) + " Each Move's ⚙ can set its own.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in MoveMode.entries) SelectChip(mode.label, s.moveMode == mode) { edit { it.copy(moveMode = mode) } }
            }
        }
        OverrideSlider(
            key + "mpx", "Move X pie %.0f°", s.movePieX, TouchpadSettings.MIN_MOVE_PIE_DEG..TouchpadSettings.MAX_MOVE_PIE_DEG, 5f,
            own(SurfaceField.MOVE_PIE_X), reset(SurfaceField.MOVE_PIE_X),
            hint = "With Pie: Move X moves while the finger heads within this pie, left and right alike.",
        ) { v -> edit { it.copy(movePieX = v) } }
        OverrideSlider(
            key + "mpy", "Move Y pie %.0f°", s.movePieY, TouchpadSettings.MIN_MOVE_PIE_DEG..TouchpadSettings.MAX_MOVE_PIE_DEG, 5f,
            own(SurfaceField.MOVE_PIE_Y), reset(SurfaceField.MOVE_PIE_Y),
            hint = "With Pie: Move Y moves while the finger heads within this pie, up and down alike.",
        ) { v -> edit { it.copy(movePieY = v) } }
        OverrideSlider(
            key + "msample", "Move sample %.1f dp", s.moveSampleDp, TouchpadSettings.MIN_MOVE_SAMPLE_DP..TouchpadSettings.MAX_MOVE_SAMPLE_DP, 0.5f,
            own(SurfaceField.MOVE_SAMPLE), reset(SurfaceField.MOVE_SAMPLE),
            hint = "Not with Free: a Move's heading is that of about the last this much travel. A stroke's first this much waits for it, then goes on at once.",
        ) { v -> edit { it.copy(moveSampleDp = v) } }

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
                DirSlider(key + "pie", "Pies %.0f°", pies[0], TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f) { v ->
                    edit { it.copy(pies = List(4) { v }) }
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
            "A held direction stays held when the fingers lift. A tap lets it go; swiping on carries on from it. " +
                    "Each Swipe hold ⚙ can set its own.",
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
            "Every direction uses these unless it sets its own: ⚙ on a Swipe card sets its pie and swipe distance, " +
                    "⚙ on a Swipe hold card its pie, hold distance, keep held and length zones."
        )
        SectionTitle("Length zones")
        if (zones.any { it > 1 }) HoldZoneSettings(s, zones, key, only = null, zone = null, onChange = current.setZones)
        else DirNote("Drag a mapping past the ┃ divider at the end of a Swipe hold card, so it fires further along.")
    }
}

/**
 * The ⚙ of direction [d]'s Swipe ([hold] false: its pie and swipe distance) or Swipe hold (its
 * pie, hold distance, keep held after lift and, with [zones] mapped, its length zones), each its
 * own over [level]'s shared value (the Settings), highlighted with a ↺ where it sets its own.
 */
@Composable
internal fun DirectionSettings(level: SurfaceLevel, key: String, d: Direction, hold: Boolean, zones: IntArray) {
    val current by rememberUpdatedState(level)
    val s = level.eff
    val k = key + d.name + if (hold) "hold" else "swipe"
    fun edit(change: (DirSettings) -> DirSettings) = current.edit { it.withDir(d, change) }
    fun own(f: DirField) = level.isDirOwn(d, f)
    fun reset(f: DirField): () -> Unit = { current.resetDir(d, f) }
    val fields = DirField.entries.filter { it.hold == hold }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ResetAllButton("Settings", fields.any(::own)) { fields.forEach { current.resetDir(d, it) } }
        DirectionPies(s, hold, d)
        val pie = if (hold) DirField.HOLD_PIE else DirField.SWIPE_PIE
        if (s.pies == null) DirNote(
            if (hold) "Classic directions (Settings): held directions work per axis, no pies."
            else "Classic directions (Settings): a swipe goes the way of its larger axis, no pies."
        ) else OverrideSlider(
            k + "pie", "Pie %.0f°", s.pieDeg(d, hold), TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f,
            own(pie), reset(pie),
            hint = if (hold) "Where a held ${d.label} counts. Its Swipe has a pie of its own." else "Where a swipe ${d.label} counts. Its Swipe hold has a pie of its own.",
        ) { v -> edit { if (hold) it.copy(holdPie = v) else it.copy(swipePie = v) } }
        if (!hold) {
            OverrideSlider(
                k + "dp", "Swipe after %.0f dp", s.swipeDpOf(d), TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f,
                own(DirField.SWIPE), reset(DirField.SWIPE),
            ) { v -> edit { it.copy(swipeDp = v) } }
        } else {
            OverrideSlider(
                k + "dp", "Hold after %.0f dp", s.holdDpOf(d), TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f,
                own(DirField.HOLD), reset(DirField.HOLD),
                hint = if (s.dragStart && s.holdMode == HoldMode.FROM_START) "With Drag start point, the start point trails the furthest hold distance of the four." else null,
            ) { v -> edit { it.copy(holdDp = v) } }
            OverrideSwitch(
                "Keep held after lift",
                "Stays held when the fingers lift. A tap lets it go; swiping on carries on from it." +
                        if (s.latchOf(d) && s.latchReleaseMs > 0) " Lets go by itself after %.1f s (Settings).".format(s.latchReleaseMs / 1000f) else "",
                s.latchOf(d), own(DirField.LATCH), reset(DirField.LATCH),
            ) { v -> edit { it.copy(latch = v) } }
            SectionTitle("Length zones")
            HoldZoneSettings(s, zones, k, only = d, zone = null, onChange = current.setZones)
        }
        DirNote("Highlighted: set for ${d.label} only. The rest is the Settings' value, shared by every direction.")
    }
}

/**
 * The ⚙ of Move X or Y [m]: the cursor and scrolling settings it uses, each its own over [level]'s
 * shared one (the Settings), highlighted with a ↺ where it sets its own. The cursor deadzone and
 * inertia go for the whole motion of its finger count: Move X and Y show the same ones.
 */
@Composable
internal fun MoveGearSettings(level: SurfaceLevel, key: String, m: MoveSlot, touchpad: Boolean) {
    val current by rememberUpdatedState(level)
    val s = level.eff
    val f = s.feel(m.fingers)
    val k = key + m.name
    val whole = MoveSlot.of(m.fingers, true)
    /** The slot that keeps [field]: the Move X of the finger count for the whole motion's. */
    fun slot(field: MoveField) = if (field.whole) whole else m
    fun edit(field: MoveField, change: (com.oleroma.gyrophone.core.model.MoveSettings) -> com.oleroma.gyrophone.core.model.MoveSettings) =
        current.edit { it.withMove(slot(field), change) }
    fun own(field: MoveField) = level.isMoveOwn(slot(field), field)
    fun reset(field: MoveField): () -> Unit = { current.resetMove(slot(field), field) }
    val fields = MoveField.entries
    val x = m.x
    val axis = if (x) "sideways" else "up and down"

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ResetAllButton("Settings", fields.any(::own)) { fields.forEach { reset(it)() } }
        SectionTitle("Cursor and sticks")
        OverrideSlider(
            k + "sens", "Sensitivity %.1f", if (x) f.sensitivityX else f.sensitivityY, 0f..MAX_SURFACE_SENS, 0.1f,
            own(MoveField.SENSITIVITY), reset(MoveField.SENSITIVITY),
            hint = "Counts per dp of travel $axis. 0 turns it off.",
        ) { v -> edit(MoveField.SENSITIVITY) { it.copy(sensitivity = v) } }
        OverrideSlider(
            k + "acc", "Acceleration %.1f", if (x) f.accelerationX else f.accelerationY, 0f..2f, 0.1f,
            own(MoveField.ACCELERATION), reset(MoveField.ACCELERATION),
        ) { v -> edit(MoveField.ACCELERATION) { it.copy(acceleration = v) } }
        val mode = if (x) f.modeX else f.modeY
        OverrideRow("Direction", own(MoveField.MODE), reset(MoveField.MODE), hint = moveModeHint(mode)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (md in MoveMode.entries) SelectChip(md.label, mode == md) { edit(MoveField.MODE) { it.copy(mode = md) } }
            }
        }
        if (mode == MoveMode.PIE) {
            OverrideSlider(
                k + "pie", "Pie %.0f°", if (x) f.pieX else f.pieY, TouchpadSettings.MIN_MOVE_PIE_DEG..TouchpadSettings.MAX_MOVE_PIE_DEG, 5f,
                own(MoveField.PIE), reset(MoveField.PIE),
                hint = "It moves while the finger heads within this pie, " + if (x) "left and right alike." else "up and down alike.",
            ) { v -> edit(MoveField.PIE) { it.copy(pie = v) } }
        }
        if (mode != MoveMode.FREE) {
            OverrideSlider(
                k + "sample", "Sample %.1f dp", f.moveSampleDp, TouchpadSettings.MIN_MOVE_SAMPLE_DP..TouchpadSettings.MAX_MOVE_SAMPLE_DP, 0.5f,
                own(MoveField.SAMPLE), reset(MoveField.SAMPLE),
                hint = "The heading is that of about the last this much travel; a stroke's first this much waits for it. For Move X and Y alike.",
            ) { v -> edit(MoveField.SAMPLE) { it.copy(sample = v) } }
        }
        if (m.fingers == 1) {
            OverrideSlider(
                k + "dz", if (f.cursorDeadzoneDp < 1f) "Cursor deadzone: off" else "Cursor deadzone %.0f dp",
                f.cursorDeadzoneDp, 0f..TouchpadSettings.MAX_CURSOR_DEADZONE_DP, 1f, own(MoveField.CURSOR_DEADZONE), reset(MoveField.CURSOR_DEADZONE),
                hint = "The cursor stays still until the finger has moved this far, then catches up with it. For Move X and Y alike.",
            ) { v -> edit(MoveField.CURSOR_DEADZONE) { it.copy(cursorDeadzoneDp = v) } }
            if (f.cursorDeadzoneDp > 0f) {
                OverrideSlider(
                    k + "catch", if (f.catchUpMs < 1) "Catch-up: instant" else "Catch-up %.0f ms",
                    f.catchUpMs.toFloat(), 0f..TouchpadSettings.MAX_CATCH_UP_MS.toFloat(), 10f, own(MoveField.CATCH_UP), reset(MoveField.CATCH_UP),
                ) { v -> edit(MoveField.CATCH_UP) { it.copy(catchUpMs = v.roundToInt()) } }
                OverrideSlider(
                    k + "rearm", if (f.deadzoneRearmMs < 1) "Re-arm: off" else "Re-arm after %.0f ms still",
                    f.deadzoneRearmMs.toFloat(), 0f..TouchpadSettings.MAX_REARM_MS.toFloat(), 10f, own(MoveField.REARM), reset(MoveField.REARM),
                    hint = "The deadzone arms again once the finger stands still this long. Off: once per touch.",
                ) { v -> edit(MoveField.REARM) { it.copy(deadzoneRearmMs = v.roundToInt()) } }
                if (f.deadzoneRearmMs > 0) {
                    OverrideSlider(
                        k + "still", "Still within %.1f dp", f.deadzoneStillDp, 0f..TouchpadSettings.MAX_STILL_DP, 0.5f,
                        own(MoveField.STILL), reset(MoveField.STILL),
                    ) { v -> edit(MoveField.STILL) { it.copy(deadzoneStillDp = v) } }
                }
            }
        }
        SectionTitle("Scrolling")
        OverrideSlider(
            k + "scroll", "Scroll speed %.2f", if (x) f.scrollSpeedX else f.scrollSpeedY, 0.25f..3f, 0.05f,
            own(MoveField.SCROLL_SPEED), reset(MoveField.SCROLL_SPEED),
        ) { v -> edit(MoveField.SCROLL_SPEED) { it.copy(scrollSpeed = v) } }
        val natural = if (x) f.naturalScrollX else f.naturalScrollY
        OverrideSwitch(
            "Natural scrolling",
            if (natural) "Content follows the fingers." else "Content moves against the fingers.",
            natural, own(MoveField.NATURAL_SCROLL), reset(MoveField.NATURAL_SCROLL),
        ) { v -> edit(MoveField.NATURAL_SCROLL) { it.copy(naturalScroll = v) } }
        OverrideSwitch(
            "Inertia",
            (if (f.inertia) "Keeps scrolling after a flick." else "Scrolling stops when the fingers lift.") +
                    " For Move X and Y alike" + if (touchpad) "; the glide is in Settings." else ".",
            f.inertia, own(MoveField.INERTIA), reset(MoveField.INERTIA),
        ) { v -> edit(MoveField.INERTIA) { it.copy(inertia = v) } }
        DirNote("Highlighted: set for ${m.label} only. The rest is the Settings' value, shared by every Move X and Y.")
    }
}

/** What Move direction [mode] does. */
private fun moveModeHint(mode: MoveMode): String = when (mode) {
    MoveMode.FREE -> "Free: the finger moves both ways at once, with no delay."
    MoveMode.LARGER_AXIS -> "Larger axis: only the way the finger mostly goes moves, so motion snaps straight."
    MoveMode.PIE -> "Pie: each Move moves while the finger heads within its pie; wide pies overlap at diagonals."
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

/**
 * The length zones of held direction [only] ([TouchpadSettings.holdZone]): its own, or the shared
 * ones every direction without its own follows ([only] null: those, as many as the direction with
 * the most). [zone]: only that one (from 0; a zone divider), null = all. [zones]: how many each
 * direction has mapped. [onChange] gets the shared zones and the per-direction ones (null = none
 * of its own; a direction whose list equals the shared one has none).
 */
@Composable
internal fun HoldZoneSettings(
    s: TouchpadSettings,
    zones: IntArray,
    key: String,
    only: Direction?,
    zone: Int?,
    onChange: (List<HoldZone>, List<List<HoldZone>>?) -> Unit,
) {
    val current by rememberUpdatedState(s)
    /** Per-direction lists with [d]'s (all: every one following [old]) changed to [list]. */
    fun commit(shared: List<HoldZone>, change: (MutableList<List<HoldZone>>) -> Unit) {
        val t = current
        val all = MutableList(4) { t.dirHoldZones?.getOrNull(it) ?: t.holdZones }
        change(all)
        onChange(shared, all.takeUnless { l -> l.all { it == shared } })
    }
    val mapped = if (only == null) Direction.entries.maxOf { zones[it.ordinal] } else zones[only.ordinal]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.dragStart && s.holdMode == HoldMode.FROM_START) {
            DirNote("Drag start point is on, so only the first zone works. Turn it off to use the zones.")
            return@Column
        }
        if (mapped < 2) {
            DirNote("Zone settings work once a second zone has mappings: drag one past the ┃ divider at the end of a Swipe hold card.")
            return@Column
        }
        val list = only?.let { s.dirHoldZones?.getOrNull(it.ordinal) } ?: s.holdZones
        val own = only != null && list != s.holdZones
        OverrideRow(
            if (only == null) "Zones of every direction" else "Zones of " + only.label,
            own,
            onReset = { commit(current.holdZones) { it[only!!.ordinal] = current.holdZones } },
            hint = if (only == null) "Each held direction uses these unless its Swipe hold ⚙ sets its own."
            else if (own) "Its own zones." else "The Settings' zones, shared by every direction: a change here makes them its own.",
        )
        DirNote(
            if (s.holdMode == HoldMode.FROM_START) "The first zone starts at the hold distance. The finger's distance from the start point picks the zone, so sliding back goes back a zone."
            else "The first zone starts at the hold distance. A zone counts the travel since the direction was held and never goes back."
        )
        val d = only ?: Direction.entries.maxBy { zones[it.ordinal] }
        val shown = if (zone == null) 0 until mapped else zone.coerceIn(0, mapped - 1)..zone.coerceIn(0, mapped - 1)
        for (i in shown) {
            ZoneList(current, d, list, mapped, i, key + "zones" + (only?.name ?: "")) { changed ->
                val t = current
                if (only == null) commit(changed) { all -> for (j in all.indices) if (all[j] == t.holdZones) all[j] = changed }
                else commit(t.holdZones) { it[only.ordinal] = changed }
            }
        }
    }
}

/**
 * Zone [i] of the [n] zones [list] of [d]: where it starts, on reach or on release, how a reached zone
 * is held ([ZoneKeep]: Always, Only forward, Only in zone), and its length (not the last one's:
 * it goes on).
 */
@Composable
private fun ZoneList(s: TouchpadSettings, d: Direction, list: List<HoldZone>, n: Int, i: Int, key: String, onChange: (List<HoldZone>) -> Unit) {
    fun set(z: HoldZone) =
        onChange(List(maxOf(list.size, i + 1)) { j -> if (j == i) z else list.getOrNull(j) ?: HoldZone() })
    // Zones of [list], as direction [d] would have them.
    val t = s.copy(holdZones = list, dirHoldZones = null)
    var start = t.holdDpOf(d).coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)
    for (j in 0 until i) start += t.holdZoneDp(d, j)
    val z = t.holdZone(d, i)
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
        DirSlider(key + i, "Length %.0f dp", t.holdZoneDp(d, i), TouchpadSettings.MIN_ZONE_DP..TouchpadSettings.MAX_ZONE_DP, 1f) { v ->
            set(z.copy(lengthDp = v))
        }
    }
}

/**
 * The four pies (of the swipes, or with [hold] of the held directions; [only]: that one stands
 * out), with a live dot: touching the circle shows which directions that way counts as.
 */
@Composable
private fun DirectionPies(s: TouchpadSettings, hold: Boolean = false, only: Direction? = null) {
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
                val w = s.pieDeg(d, hold)
                drawArc(
                    pieColors[d.ordinal].copy(alpha = if (only == null || only == d) 0.35f else 0.1f),
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
            val mask = if (at == null) -1 else s.directionsAt(at.x, at.y, hold)
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
private fun DirNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
