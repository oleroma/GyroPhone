package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.isTouchpad
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

private const val MAX_TOUCHPAD_SENS = 12f

/**
 * Full-screen settings of the workspace touchpad (a long press on empty space in edit mode):
 * speed, scrolling, deadzone and locks, the touchpad ratchet, and a summary of its mappings
 * ([onEditMappings] opens their screen). Edits go to [onWorkspaceChange].
 */
@Composable
internal fun TouchpadSettingsScreen(
    workspace: Workspace,
    runtime: GamepadRuntime,
    onWorkspaceChange: ((Workspace) -> Workspace) -> Unit,
    onEditMappings: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Touchpad", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "The empty pad area · " + workspace.name + " · settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onEditMappings) { Text("Mappings") }
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val section = Modifier.widthIn(max = 720.dp)
                    RememberedSection(
                        runtime,
                        "touchpad.Settings",
                        "Movement & scrolling",
                        section,
                        defaultOpen = true
                    ) {
                        TouchpadSettingsEditor(workspace.touchpad, workspace.id) { tp ->
                            onWorkspaceChange {
                                it.copy(
                                    touchpad = tp
                                )
                            }
                        }
                    }
                    RememberedSection(runtime, "touchpad.Gyro", "Gyro", section) {
                        TouchpadSwitch(
                            "Touchpad is a ratchet (a finger on the empty pad flips gyro while it is down, even with the touchpad off)",
                            workspace.gyro.touchpadRatchet,
                        ) { v -> onWorkspaceChange { it.copy(gyro = it.gyro.copy(touchpadRatchet = v)) } }
                    }
                    RememberedSection(runtime, "touchpad.Mappings", "Mappings", section, defaultOpen = true) {
                        TouchpadMappingsSummary(workspace, onEditMappings) {
                            onWorkspaceChange { w ->
                                w.copy(
                                    mappings = w.mappings.filterNot { it.source.isTouchpad } + Bindings.defaultTouchpadMappings(
                                        TouchpadSettings()
                                    ),
                                    touchpad = w.touchpad.copy(mapped = true),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What each finger count of the touchpad does, with Edit and Reset. */
@Composable
private fun TouchpadMappingsSummary(workspace: Workspace, onEdit: () -> Unit, onReset: () -> Unit) {
    val mapped = workspace.mappings.filter { it.source.isTouchpad }
    for (n in 1..4) {
        val lines = Bindings.surfaceInputs(n).mapNotNull { input ->
            val targets = Bindings.targets(mapped, input)
            if (targets.isEmpty()) null else input.label + ": " + targets.joinToString { Describe.shortTarget(it) }
        }
        Text(if (n == 1) "1 finger" else "$n fingers", style = MaterialTheme.typography.labelLarge)
        Text(
            lines.joinToString(" · ").ifEmpty { "Nothing mapped" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit) { Text("Edit mappings") }
        TextButton(onClick = onReset) { Text("Reset to defaults") }
    }
}

/** The touchpad's own settings: on / off, speed, acceleration, scrolling, inertia, deadzone, axis locks. */
@Composable
internal fun TouchpadSettingsEditor(tp: TouchpadSettings, key: String, onChange: (TouchpadSettings) -> Unit) {
    val current by rememberUpdatedState(tp)
    fun edit(change: (TouchpadSettings) -> TouchpadSettings) = onChange(change(current))

    TouchpadSwitch("Enabled", tp.enabled) { v -> edit { it.copy(enabled = v) } }
    TouchpadSwitch("Natural scrolling", tp.naturalScroll) { v -> edit { it.copy(naturalScroll = v) } }
    FloatSlider(key, "X speed %.1f", tp.sensitivity, 0f..MAX_TOUCHPAD_SENS, step = 0.1f) { v ->
        edit {
            it.copy(
                sensitivity = v
            )
        }
    }
    FloatSlider(key, "Y speed %.1f", tp.verticalSensitivity, 0f..MAX_TOUCHPAD_SENS, step = 0.1f) { v ->
        edit {
            it.copy(
                sensitivityY = v
            )
        }
    }
    Note("0 turns that direction off, e.g. X 0 for a touchpad that only moves up and down.")
    FloatSlider(key, "Acceleration %.1f", tp.acceleration, 0f..2f) { v -> edit { it.copy(acceleration = v) } }
    FloatSlider(key, "Scroll speed %.1f", tp.scrollSpeed, 0.25f..3f) { v -> edit { it.copy(scrollSpeed = v) } }
    TouchpadSwitch("Inertia (keeps scrolling after a flick)", tp.inertia) { v -> edit { it.copy(inertia = v) } }
    if (tp.inertia) {
        FloatSlider(
            key,
            "Glide %.0f ms",
            tp.inertiaGlideMs,
            TouchpadSettings.MIN_GLIDE_MS..TouchpadSettings.MAX_GLIDE_MS
        ) { v ->
            edit { it.copy(inertiaGlideMs = v) }
        }
    }
    FloatSlider(
        key, if (tp.cursorDeadzoneDp < 1f) "Cursor deadzone: off" else "Cursor deadzone %.0f dp",
        tp.cursorDeadzoneDp, 0f..TouchpadSettings.MAX_CURSOR_DEADZONE_DP, step = 1f,
    ) { v -> edit { it.copy(cursorDeadzoneDp = v) } }
    Note("One finger: the cursor stays still until the finger has moved this far, then catches up with it.")
    if (tp.cursorDeadzoneDp > 0f) {
        FloatSlider(
            key, if (tp.catchUpMs < 1) "Catch-up: instant" else "Catch-up %.0f ms",
            tp.catchUpMs.toFloat(), 0f..TouchpadSettings.MAX_CATCH_UP_MS.toFloat(), step = 10f,
        ) { v -> edit { it.copy(catchUpMs = v.roundToInt()) } }
        FloatSlider(
            key, if (tp.deadzoneRearmMs < 1) "Re-arm: off" else "Re-arm after %.0f ms still",
            tp.deadzoneRearmMs.toFloat(), 0f..TouchpadSettings.MAX_REARM_MS.toFloat(), step = 10f,
        ) { v -> edit { it.copy(deadzoneRearmMs = v.roundToInt()) } }
        Note("The deadzone arms again once the finger stands still this long. Off: once per touch.")
        if (tp.deadzoneRearmMs > 0) {
            FloatSlider(
                key,
                "Still within %.1f dp",
                tp.deadzoneStillDp,
                0f..TouchpadSettings.MAX_STILL_DP,
                step = 0.5f
            ) { v ->
                edit { it.copy(deadzoneStillDp = v) }
            }
            Note("Movement smaller than this still counts as standing still.")
        }
    }
    FloatSlider(
        key, if (tp.verticalLockDeg < 1f) "Vertical lock: off" else "Vertical lock %.0f°",
        tp.verticalLockDeg, 0f..TouchpadSettings.MAX_LOCK_DEG, step = 1f,
    ) { v -> edit { it.copy(verticalLockDeg = v) } }
    FloatSlider(
        key, if (tp.horizontalLockDeg < 1f) "Horizontal lock: off" else "Horizontal lock %.0f°",
        tp.horizontalLockDeg, 0f..TouchpadSettings.MAX_LOCK_DEG, step = 1f,
    ) { v -> edit { it.copy(horizontalLockDeg = v) } }
    Note(
        "Motion within this angle of straight up / down loses its sideways part (vertical lock), or within " +
                "this angle of sideways its up / down part (horizontal lock): cursor, scroll, sticks and held " +
                "directions alike. So a swipe down does not turn the camera.",
    )
    Text("Directions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    DirectionSettings(tp, key) { t -> onChange(t) }
}

/**
 * A slider whose label [format] shows the value (a plain label without a format code); it
 * snaps to [step] (0 = none) and hands the value to [onDone] once the drag ends.
 */
@Composable
private fun FloatSlider(
    key: String,
    format: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 0f,
    onDone: (Float) -> Unit,
) {
    var v by remember(key, value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if ('%' in format) format.format(v) else format, Modifier.padding(end = 8.dp))
        Slider(
            value = v,
            onValueChange = { v = if (step > 0f) (it / step).roundToInt() * step else it },
            valueRange = range,
            onValueChangeFinished = { onDone(v) },
        )
    }
}

@Composable
private fun TouchpadSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)
