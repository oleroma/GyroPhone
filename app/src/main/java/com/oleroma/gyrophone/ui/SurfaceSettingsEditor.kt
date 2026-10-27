package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.ownSurface
import com.oleroma.gyrophone.core.model.surfaceSettings
import kotlin.math.roundToInt

/** Same top as the workspace touchpad's speed sliders. */
private const val MAX_SURFACE_SENS = 12f

/**
 * A swipe button's touch surface settings: each follows the workspace touchpad [pad] until its
 * "Touchpad" switch is turned off, which keeps the current value as the button's own.
 */
@Composable
internal fun SurfaceSettingsEditor(control: TouchControl, pad: TouchpadSettings, onChange: (TouchControl) -> Unit) {
    val current by rememberUpdatedState(control)
    val own = control.ownSurface
    val eff = control.surfaceSettings(pad)
    fun edit(change: (SurfaceSettings, TouchpadSettings) -> SurfaceSettings) {
        val c = current
        onChange(c.copy(surface = change(c.ownSurface, c.surfaceSettings(pad))))
    }

    Column {
        Text("Surface settings", style = MaterialTheme.typography.labelLarge)
        Text(
            "Each setting follows the workspace touchpad unless you turn off its 'Touchpad' switch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SurfaceSettingRow("Sensitivity %.1f".format(eff.sensitivity), own.sensitivity == null, { follow ->
        edit { s, e -> s.copy(sensitivity = if (follow) null else e.sensitivity) }
    }) {
        Slider(
            value = eff.sensitivity,
            onValueChange = { v -> edit { s, _ -> s.copy(sensitivity = (v * 10).roundToInt() / 10f) } },
            valueRange = 0f..MAX_SURFACE_SENS,
        )
    }
    SurfaceSettingRow("Vertical sensitivity %.1f".format(eff.verticalSensitivity), own.sensitivityY == null, { follow ->
        edit { s, e -> s.copy(sensitivityY = if (follow) null else e.verticalSensitivity) }
    }) {
        Slider(
            value = eff.verticalSensitivity,
            onValueChange = { v -> edit { s, _ -> s.copy(sensitivityY = (v * 10).roundToInt() / 10f) } },
            valueRange = 0f..MAX_SURFACE_SENS,
        )
    }
    SurfaceSettingRow("Acceleration %.1f".format(eff.acceleration), own.acceleration == null, { follow ->
        edit { s, e -> s.copy(acceleration = if (follow) null else e.acceleration) }
    }) {
        Slider(
            value = eff.acceleration,
            onValueChange = { v -> edit { s, _ -> s.copy(acceleration = (v * 10).roundToInt() / 10f) } },
            valueRange = 0f..2f,
        )
    }
    SurfaceSettingRow("Scroll speed %.1f".format(eff.scrollSpeed), own.scrollSpeed == null, { follow ->
        edit { s, e -> s.copy(scrollSpeed = if (follow) null else e.scrollSpeed) }
    }) {
        Slider(
            value = eff.scrollSpeed,
            onValueChange = { v -> edit { s, _ -> s.copy(scrollSpeed = (v * 20).roundToInt() / 20f) } },
            valueRange = 0.25f..3f,
        )
    }
    SurfaceSettingRow("Natural scrolling", own.naturalScroll == null, { follow ->
        edit { s, e -> s.copy(naturalScroll = if (follow) null else e.naturalScroll) }
    }) {
        SurfaceSwitch(
            if (eff.naturalScroll) "Content follows the fingers." else "Content moves against the fingers.",
            eff.naturalScroll,
        ) { v -> edit { s, _ -> s.copy(naturalScroll = v) } }
    }
    SurfaceSettingRow("Inertia", own.inertia == null, { follow ->
        edit { s, e -> s.copy(inertia = if (follow) null else e.inertia) }
    }) {
        SurfaceSwitch(
            if (eff.inertia) "Keeps scrolling after a flick." else "Scrolling stops when the fingers lift.",
            eff.inertia,
        ) { v -> edit { s, _ -> s.copy(inertia = v) } }
    }
    SurfaceSettingRow(
        if (eff.releaseTapMs <= 0) "Release tap time: off" else "Release tap time " + eff.releaseTapMs + " ms",
        own.releaseTapMs == null,
        { follow -> edit { s, e -> s.copy(releaseTapMs = if (follow) null else e.releaseTapMs) } },
    ) {
        Slider(
            value = eff.releaseTapMs.toFloat(),
            onValueChange = { v -> edit { s, _ -> s.copy(releaseTapMs = (v / 10f).roundToInt() * 10) } },
            valueRange = 0f..TouchpadSettings.MAX_RELEASE_TAP_MS.toFloat(),
        )
    }
    SurfaceSettingRow(
        if (eff.cursorDeadzoneDp < 1f) "Cursor deadzone: off" else "Cursor deadzone %.0f dp".format(eff.cursorDeadzoneDp),
        own.cursorDeadzoneDp == null,
        { follow -> edit { s, e -> s.copy(cursorDeadzoneDp = if (follow) null else e.cursorDeadzoneDp) } },
    ) {
        Slider(
            value = eff.cursorDeadzoneDp,
            onValueChange = { v -> edit { s, _ -> s.copy(cursorDeadzoneDp = v.roundToInt().toFloat()) } },
            valueRange = 0f..TouchpadSettings.MAX_CURSOR_DEADZONE_DP,
        )
    }
    if (eff.cursorDeadzoneDp > 0f) {
        SurfaceSettingRow(
            if (eff.catchUpMs <= 0) "Catch-up: instant" else "Catch-up " + eff.catchUpMs + " ms",
            own.catchUpMs == null,
            { follow -> edit { s, e -> s.copy(catchUpMs = if (follow) null else e.catchUpMs) } },
        ) {
            Slider(
                value = eff.catchUpMs.toFloat(),
                onValueChange = { v -> edit { s, _ -> s.copy(catchUpMs = (v / 10f).roundToInt() * 10) } },
                valueRange = 0f..TouchpadSettings.MAX_CATCH_UP_MS.toFloat(),
            )
        }
        SurfaceSettingRow(
            if (eff.deadzoneRearmMs <= 0) "Re-arm: off" else "Re-arm after " + eff.deadzoneRearmMs + " ms still",
            own.deadzoneRearmMs == null,
            { follow -> edit { s, e -> s.copy(deadzoneRearmMs = if (follow) null else e.deadzoneRearmMs) } },
        ) {
            Slider(
                value = eff.deadzoneRearmMs.toFloat(),
                onValueChange = { v -> edit { s, _ -> s.copy(deadzoneRearmMs = (v / 10f).roundToInt() * 10) } },
                valueRange = 0f..TouchpadSettings.MAX_REARM_MS.toFloat(),
            )
        }
        if (eff.deadzoneRearmMs > 0) {
            SurfaceSettingRow(
                "Still within %.1f dp".format(eff.deadzoneStillDp),
                own.deadzoneStillDp == null,
                { follow -> edit { s, e -> s.copy(deadzoneStillDp = if (follow) null else e.deadzoneStillDp) } },
            ) {
                Slider(
                    value = eff.deadzoneStillDp,
                    onValueChange = { v -> edit { s, _ -> s.copy(deadzoneStillDp = (v * 2f).roundToInt() / 2f) } },
                    valueRange = 0f..TouchpadSettings.MAX_STILL_DP,
                )
            }
        }
    }
}

/** A setting's name with its "Touchpad" switch ([follows]); its own [editor] shows when it doesn't follow. */
@Composable
private fun SurfaceSettingRow(
    title: String,
    follows: Boolean,
    onFollow: (Boolean) -> Unit,
    editor: @Composable () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(
                "Touchpad",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = follows, onCheckedChange = onFollow, modifier = Modifier.scale(0.75f))
        }
        if (!follows) editor()
    }
}

@Composable
private fun SurfaceSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
