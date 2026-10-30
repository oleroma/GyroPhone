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
        SectionTitle("Move")
        OverrideRow(
            "Direction", own(SurfaceField.MOVE_MODE), reset(SurfaceField.MOVE_MODE),
            hint = moveModeHint(s.moveMode) + " Each Move X / Y ⚙ can set its own.",
        ) { MoveModeChips(s.moveMode) { m -> edit { it.copy(moveMode = m) } } }
        if (s.moveMode != MoveMode.FREE) {
            SharedPies(level, key, PieKind.MOVE)
            OverrideSwitch(
                "Lock heading", LOCK_HINT, s.moveLock, own(SurfaceField.MOVE_LOCK), reset(SurfaceField.MOVE_LOCK),
            ) { v -> edit { it.copy(moveLock = v) } }
            if (s.moveLock) {
                OverrideSlider(
                    key + "turn", "Turn after %.1f dp", s.moveTurnDp, 0f..TouchpadSettings.MAX_MOVE_TURN_DP, 0.5f,
                    own(SurfaceField.MOVE_TURN), reset(SurfaceField.MOVE_TURN), hint = TURN_HINT,
                ) { v -> edit { it.copy(moveTurnDp = v) } }
            }
            OverrideSlider(
                key + "margin", if (s.movePieMargin < 1f) "Pie margin: off" else "Pie margin %.0f°",
                s.movePieMargin, 0f..TouchpadSettings.MAX_MOVE_PIE_MARGIN, 1f,
                own(SurfaceField.MOVE_PIE_MARGIN), reset(SurfaceField.MOVE_PIE_MARGIN), hint = MARGIN_HINT,
            ) { v -> edit { it.copy(movePieMargin = v) } }
        }
        OverrideSwitch(
            "Swipe hold stops Move", HOLD_STOPS_HINT, s.holdStopsMove, own(SurfaceField.HOLD_STOPS_MOVE), reset(SurfaceField.HOLD_STOPS_MOVE),
        ) { v -> edit { it.copy(holdStopsMove = v) } }

        SectionTitle("Swipe")
        SharedPies(level, key, PieKind.SWIPE)
        OverrideSlider(
            key + "swipe", "Swipe after %.0f dp", s.swipeDp,
            TouchpadSettings.MIN_ACTIVATE_DP..TouchpadSettings.MAX_ACTIVATE_DP, 1f, own(SurfaceField.SWIPE), reset(SurfaceField.SWIPE),
            hint = "Measured from where the finger turned into that direction. Each new direction swipes again.",
        ) { v -> edit { it.copy(swipeDp = v) } }

        SectionTitle("Swipe hold")
        OverrideRow(
            "Hold mode",
            own(SurfaceField.HOLD_MODE),
            reset(SurfaceField.HOLD_MODE),
            hint = if (s.holdMode == HoldMode.FROM_START) "Held while the finger is past the hold distance from the start point, like a floating stick: sliding back lets go."
            else "Held once the finger has gone the hold distance that way, until it goes another way as far or lifts. There is no neutral spot. " +
                    "Pies of 90° (Larger axis) keep one direction at a time.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in HoldMode.entries) SelectChip(m.label, s.holdMode == m) { edit { it.copy(holdMode = m) } }
            }
        }
        SharedPies(level, key, PieKind.HOLD)
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
            "Every Move, Swipe and Swipe hold uses these unless its ⚙ in the mapping editor sets its own: " +
                    "a Move X / Y its pie, a Swipe or Swipe hold direction its pie, sample and distance."
        )
        SectionTitle("Length zones")
        if (zones.any { it > 1 }) HoldZoneSettings(s, zones, key, only = null, zone = null, onChange = current.setZones)
        else DirNote("Drag a mapping past the ┃ divider at the end of a Swipe hold card, so it fires further along.")
    }
}

/**
 * The ⚙ of direction [d]'s Swipe ([hold] false: its pie, sample and swipe distance) or Swipe hold (its
 * pie, sample, hold distance, keep held after lift and, with [zones] mapped, its length zones), each its
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
        var live by remember { mutableStateOf<Float?>(null) }
        val pie = if (hold) DirField.HOLD_PIE else DirField.SWIPE_PIE
        val sample = if (hold) DirField.HOLD_SAMPLE else DirField.SWIPE_SAMPLE
        PiePicture(if (hold) PieKind.HOLD else PieKind.SWIPE, shown = 1 shl d.ordinal) { live ?: s.pieDeg(it, hold) }
        OverrideSlider(
            k + "pie", "Pie %.0f°", s.pieDeg(d, hold), TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f,
            own(pie), reset(pie),
            hint = if (hold) "Where a held ${d.label} counts. Its Swipe has a pie of its own." else "Where a swipe ${d.label} counts. Its Swipe hold has a pie of its own.",
            onLive = { live = it },
        ) { v -> edit { if (hold) it.copy(holdPie = v) else it.copy(swipePie = v) } }
        OverrideSlider(
            k + "sample", "Sample %.1f dp", s.sampleDp(d, hold), TouchpadSettings.MIN_SAMPLE_DP..TouchpadSettings.MAX_SAMPLE_DP, 0.5f,
            own(sample), reset(sample),
            hint = SAMPLE_HINT,
        ) { v -> edit { if (hold) it.copy(holdSample = v) else it.copy(swipeSample = v) } }
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
            MoveModeChips(mode) { md -> edit(MoveField.MODE) { it.copy(mode = md) } }
        }
        if (mode != MoveMode.FREE) {
            var live by remember { mutableStateOf<Float?>(null) }
            val pie = if (x) f.pieX else f.pieY
            PiePicture(PieKind.MOVE, shown = PieKind.axisMask(x)) { live ?: pie }
            OverrideSlider(
                k + "pie", "Pie %.0f°", pie, TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f,
                own(MoveField.PIE), reset(MoveField.PIE),
                hint = "It moves while the finger heads within this pie, " + if (x) "left and right alike." else "up and down alike.",
                onLive = { live = it },
            ) { v -> edit(MoveField.PIE) { it.copy(pie = v) } }
            OverrideSlider(
                k + "sample", "Sample %.1f dp", f.moveSampleDp, TouchpadSettings.MIN_SAMPLE_DP..TouchpadSettings.MAX_SAMPLE_DP, 0.5f,
                own(MoveField.SAMPLE), reset(MoveField.SAMPLE),
                hint = "$SAMPLE_HINT A stroke's first this much waits for it. For Move X and Y alike.",
            ) { v -> edit(MoveField.SAMPLE) { it.copy(sample = v) } }
            OverrideSwitch(
                "Lock heading", "$LOCK_HINT For Move X and Y alike.", f.moveLock, own(MoveField.LOCK), reset(MoveField.LOCK),
            ) { v -> edit(MoveField.LOCK) { it.copy(lock = v) } }
            if (f.moveLock) {
                OverrideSlider(
                    k + "turn", "Turn after %.1f dp", f.moveTurnDp, 0f..TouchpadSettings.MAX_MOVE_TURN_DP, 0.5f,
                    own(MoveField.TURN), reset(MoveField.TURN), hint = "$TURN_HINT For Move X and Y alike.",
                ) { v -> edit(MoveField.TURN) { it.copy(turnDp = v) } }
            }
            OverrideSlider(
                k + "margin", if (f.movePieMargin < 1f) "Pie margin: off" else "Pie margin %.0f°",
                f.movePieMargin, 0f..TouchpadSettings.MAX_MOVE_PIE_MARGIN, 1f,
                own(MoveField.PIE_MARGIN), reset(MoveField.PIE_MARGIN), hint = "$MARGIN_HINT For Move X and Y alike.",
            ) { v -> edit(MoveField.PIE_MARGIN) { it.copy(margin = v) } }
        }
        OverrideSwitch(
            "Swipe hold stops Move", "$HOLD_STOPS_HINT For Move X and Y alike.", f.holdStopsMove,
            own(MoveField.HOLD_STOPS), reset(MoveField.HOLD_STOPS),
        ) { v -> edit(MoveField.HOLD_STOPS) { it.copy(holdStops = v) } }
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
    MoveMode.PIE, MoveMode.LARGER_AXIS -> "Pie: a Move moves while the finger heads within its pie. 90° pies snap to the larger axis; wider ones overlap at diagonals."
}

private const val LOCK_HINT =
    "A stroke keeps moving the way it started, so a wobbly finger doesn't drop movement or move the other axis."
private const val TURN_HINT =
    "The stroke turns once it heads another way for this long; that travel then moves the new way, so none is lost. 0 turns at once."
private const val MARGIN_HINT =
    "A Move that is moving keeps going until the finger heads this far past its pie, so it doesn't flicker at the pie's edge."
private const val HOLD_STOPS_HINT =
    "Once a mapped swipe hold presses, Move X and Y stop until the fingers lift, so a jump or crouch never moves the cursor."

private const val SAMPLE_HINT = "The heading is that of about the last this much travel, so small wobbles don't change it."

/** Move direction chips: Free or Pie (an old Larger axis shows as Pie: it is two 90° pies). */
@Composable
private fun MoveModeChips(mode: MoveMode, onPick: (MoveMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectChip(MoveMode.FREE.label, mode == MoveMode.FREE) { onPick(MoveMode.FREE) }
        SelectChip(MoveMode.PIE.label, mode != MoveMode.FREE) { onPick(MoveMode.PIE) }
    }
}

/**
 * Whose pies a picture shows, with the shared settings of them: Move X / Y's (one width per axis),
 * the swipes' or the held directions' (per direction, over the shared width of its axis).
 */
private enum class PieKind(val pieX: SurfaceField, val pieY: SurfaceField, val sample: SurfaceField) {
    MOVE(SurfaceField.MOVE_PIE_X, SurfaceField.MOVE_PIE_Y, SurfaceField.MOVE_SAMPLE),
    SWIPE(SurfaceField.SWIPE_PIE_X, SurfaceField.SWIPE_PIE_Y, SurfaceField.SWIPE_SAMPLE),
    HOLD(SurfaceField.HOLD_PIE_X, SurfaceField.HOLD_PIE_Y, SurfaceField.HOLD_SAMPLE);

    fun x(s: TouchpadSettings) = when (this) { MOVE -> s.movePieX; SWIPE -> s.swipePieX; HOLD -> s.holdPieX }
    fun y(s: TouchpadSettings) = when (this) { MOVE -> s.movePieY; SWIPE -> s.swipePieY; HOLD -> s.holdPieY }
    fun sample(s: TouchpadSettings) = when (this) { MOVE -> s.moveSampleDp; SWIPE -> s.swipeSampleDp; HOLD -> s.holdSampleDp }

    fun withX(s: TouchpadSettings, v: Float) = when (this) { MOVE -> s.copy(movePieX = v); SWIPE -> s.copy(swipePieX = v); HOLD -> s.copy(holdPieX = v) }
    fun withY(s: TouchpadSettings, v: Float) = when (this) { MOVE -> s.copy(movePieY = v); SWIPE -> s.copy(swipePieY = v); HOLD -> s.copy(holdPieY = v) }
    fun withSample(s: TouchpadSettings, v: Float) =
        when (this) { MOVE -> s.copy(moveSampleDp = v); SWIPE -> s.copy(swipeSampleDp = v); HOLD -> s.copy(holdSampleDp = v) }

    /** The pie of [d] in [s]: a direction's own one counts for swipes and held directions. */
    fun width(s: TouchpadSettings, d: Direction): Float = when (this) {
        MOVE -> if (d.x) s.movePieX else s.movePieY
        SWIPE -> s.pieDeg(d, hold = false)
        HOLD -> s.pieDeg(d, hold = true)
    }

    companion object {
        const val ALL = 0b1111

        /** Left and right ([x]), or up and down. */
        fun axisMask(x: Boolean): Int =
            if (x) (1 shl Direction.LEFT.ordinal) or (1 shl Direction.RIGHT.ordinal)
            else (1 shl Direction.UP.ordinal) or (1 shl Direction.DOWN.ordinal)
    }
}

/**
 * [kind]'s shared pies at [level]: the picture (redrawn while a slider is dragged), X pie, Y pie,
 * quick widths and Sample, each its own over the parent's.
 */
@Composable
private fun SharedPies(level: SurfaceLevel, key: String, kind: PieKind) {
    val current by rememberUpdatedState(level)
    val s = level.eff
    var live by remember { mutableStateOf<TouchpadSettings?>(null) }
    val shownOf = live ?: s
    PiePicture(kind) { kind.width(shownOf, it) }
    fun edit(change: (TouchpadSettings) -> TouchpadSettings) = current.edit(change)
    val (xs, ys) = when (kind) {
        PieKind.MOVE -> "Move X" to "Move Y"
        else -> "Left and Right" to "Up and Down"
    }
    OverrideSlider(
        key + kind.name + "x", "X pie %.0f°", kind.x(s), TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f,
        level.isOwn(kind.pieX), { current.reset(kind.pieX) },
        hint = "$xs alike.",
        onLive = { v -> live = v?.let { kind.withX(s, it) } },
    ) { v -> edit { kind.withX(it, v) } }
    OverrideSlider(
        key + kind.name + "y", "Y pie %.0f°", kind.y(s), TouchpadSettings.MIN_PIE_DEG..TouchpadSettings.MAX_PIE_DEG, 5f,
        level.isOwn(kind.pieY), { current.reset(kind.pieY) },
        hint = "$ys alike.",
        onLive = { v -> live = v?.let { kind.withY(s, it) } },
    ) { v -> edit { kind.withY(it, v) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fun both(deg: Float) = edit { kind.withY(kind.withX(it, deg), deg) }
        val x = kind.x(s)
        val y = kind.y(s)
        SelectChip("Larger axis 90°", x == TouchpadSettings.LARGER_AXIS_PIE_DEG && y == x) { both(TouchpadSettings.LARGER_AXIS_PIE_DEG) }
        if (kind == PieKind.HOLD) {
            SelectChip("Per axis 180°", x == TouchpadSettings.PER_AXIS_PIE_DEG && y == x) { both(TouchpadSettings.PER_AXIS_PIE_DEG) }
        }
    }
    OverrideSlider(
        key + kind.name + "sample", "Sample %.1f dp", kind.sample(s), TouchpadSettings.MIN_SAMPLE_DP..TouchpadSettings.MAX_SAMPLE_DP, 0.5f,
        level.isOwn(kind.sample), { current.reset(kind.sample) },
        hint = SAMPLE_HINT + if (kind == PieKind.MOVE) " A stroke's first this much waits for it." else "",
    ) { v -> edit { kind.withSample(it, v) } }
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
 * [kind]'s pies, [width] wide each (degrees), only those of [shown] (mask of 1 shl
 * [Direction.ordinal]), with a live line: touching the circle shows what that way counts as.
 */
@Composable
private fun PiePicture(kind: PieKind, shown: Int = PieKind.ALL, width: (Direction) -> Float) {
    val colors = MaterialTheme.colorScheme
    // Move: one colour per axis; swipes and held directions: one per direction.
    val pieColors = if (kind == PieKind.MOVE) listOf(colors.tertiary, colors.primary, colors.tertiary, colors.primary)
    else listOf(colors.primary, colors.tertiary, colors.secondary, colors.error)
    var touch by remember { mutableStateOf<Offset?>(null) }
    val at = touch
    val widths = Direction.entries.map { width(it).coerceIn(TouchpadSettings.MIN_PIE_DEG, TouchpadSettings.MAX_PIE_DEG) }
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
                if (shown and (1 shl d.ordinal) == 0) continue
                val w = widths[d.ordinal]
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
            val legend = if (kind == PieKind.MOVE) {
                listOf("Move X" to Direction.RIGHT, "Move Y" to Direction.UP).filter { (_, d) -> shown and PieKind.axisMask(d.x) != 0 }
            } else Direction.entries.filter { shown and (1 shl it.ordinal) != 0 }.map { it.label to it }
            for ((label, d) in legend) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawCircle(pieColors[d.ordinal].copy(alpha = 0.6f)) }
                    Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            val mask = if (at == null) -1 else piesAt(widths, at.x, at.y) and shown
            Text(
                when {
                    mask == -1 -> "Touch the circle to try a way."
                    kind == PieKind.MOVE -> listOfNotNull(
                        "Move X".takeIf { mask and PieKind.axisMask(true) != 0 },
                        "Move Y".takeIf { mask and PieKind.axisMask(false) != 0 },
                    ).joinToString(" + ").ifEmpty { "Nothing moves (gap)" }
                    mask == 0 -> "No direction (gap)"
                    else -> Direction.entries.filter { mask and (1 shl it.ordinal) != 0 }.joinToString(" + ") { it.label }
                },
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (at != null) Color.Unspecified else colors.onSurfaceVariant,
            )
        }
    }
}

/** The pies ([widths] by [Direction.ordinal]) the way ([x], [y]) (y down) lies in, as [TouchpadSettings.inPie] judges. */
private fun piesAt(widths: List<Float>, x: Float, y: Float): Int {
    if (x == 0f && y == 0f) return 0
    val deg = Math.toDegrees(kotlin.math.atan2(x.toDouble(), (-y).toDouble())).toFloat()
    var mask = 0
    for (d in Direction.entries) {
        val off = kotlin.math.abs(((deg - d.deg) % 360f + 540f) % 360f - 180f)
        if (off <= widths[d.ordinal] / 2f && off < 90f) mask = mask or (1 shl d.ordinal)
    }
    return mask
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
