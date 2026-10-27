package com.oleroma.gyrophone.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

import com.oleroma.gyrophone.core.model.GyroAxes
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.RatchetAxes

import com.oleroma.gyrophone.core.model.Workspace

import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

/** Slider top: higher than the old 60, for fast flicks. */
private const val MAX_GYRO_SENS = 150f

/**
 * Gyro, button and haptic settings of the active profile's workspace [index] (the touchpad's are
 * in edit mode: [TouchpadSettingsScreen]). Edits go
 * through [GamepadRuntime.editActiveProfile], so a builtin becomes an active custom copy.
 */
@Composable
internal fun WorkspaceSettings(runtime: GamepadRuntime, profile: Profile, index: Int) {
    val ws = profile.workspace(index)
    fun editWs(change: (Workspace) -> Workspace) {
        runtime.editActiveProfile { it.updateWorkspace(index, change) }
    }
    // Slider drafts restart when the profile or workspace changes.
    val key = profile.id + "/" + ws.id

    RememberedSection(runtime, "workspace.Gyro", "Gyro", defaultOpen = true, subtitle = ws.gyro.mode.label) {
        Text("Gyro to mouse", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(GyroMode.ALWAYS, GyroMode.OFF, GyroMode.SENSOR_OFF).forEach { mode ->
                FilterChip(selected = ws.gyro.mode == mode, onClick = {
                    editWs { it.copy(gyro = it.gyro.copy(mode = mode)) }
                }, label = { Text(mode.label) })
            }
        }
        Text(
            when (ws.gyro.mode) {
                GyroMode.ALWAYS -> "Gyro moves the mouse; a ratchet pauses it while held."
                GyroMode.SENSOR_OFF -> "Gyro is off completely: the sensor is not read (saves battery), and ratchets and Toggle gyro do nothing."
                else -> "Gyro is off; a ratchet turns it on while held."
            } + if (ws.gyro.mode == GyroMode.SENSOR_OFF) "" else
                " Turn on Ratchet on any touch button (or map Ratchet to a controller button); Toggle gyro swaps the two modes.",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow(
            "Touchpad is a ratchet (a finger on the empty pad flips gyro while it is down, even with the touchpad off)",
            ws.gyro.touchpadRatchet
        ) { v ->
            editWs { it.copy(gyro = it.gyro.copy(touchpadRatchet = v)) }
        }
        Text("Ratchet acts on", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RatchetAxes.entries.forEach { a ->
                FilterChip(
                    selected = ws.gyro.ratchetAxes == a,
                    onClick = { editWs { it.copy(gyro = it.gyro.copy(ratchetAxes = a)) } },
                    label = { Text(a.label) },
                )
            }
        }
        Text(
            "Only X: a ratchet stops or starts only the sideways motion (re-centre left and right while up and down keeps going).",
            style = MaterialTheme.typography.bodySmall,
        )
        var sens by remember(key) { mutableStateOf(ws.gyro.sensitivity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("X sensitivity %.0f".format(sens), Modifier.padding(end = 8.dp))
            Slider(
                value = sens, onValueChange = { sens = it.roundToInt().toFloat() }, valueRange = 0f..MAX_GYRO_SENS,
                onValueChangeFinished = { editWs { it.copy(gyro = it.gyro.copy(sensitivity = sens)) } })
        }
        var sensY by remember(key) { mutableStateOf(ws.gyro.verticalSensitivity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Y sensitivity %.0f".format(sensY), Modifier.padding(end = 8.dp))
            Slider(
                value = sensY, onValueChange = { sensY = it.roundToInt().toFloat() }, valueRange = 0f..MAX_GYRO_SENS,
                onValueChangeFinished = { editWs { it.copy(gyro = it.gyro.copy(sensitivityY = sensY)) } })
        }
        Text(
            "Mouse counts per degree, sideways (X) and up and down (Y). 0 turns that direction off.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("Held as", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GyroOrientation.entries.forEach { o ->
                FilterChip(
                    selected = ws.gyro.orientation == o,
                    onClick = { editWs { it.copy(gyro = it.gyro.copy(orientation = o)) } },
                    label = { Text(o.label) })
            }
        }
        // Landscape and portrait each have their own axis mapping; start on the way the phone is held.
        val heldPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        var axesPortrait by remember(heldPortrait) { mutableStateOf(heldPortrait) }
        val axes = ws.gyro.axesFor(axesPortrait)
        fun editAxes(change: (GyroAxes) -> GyroAxes) =
            editWs { it.copy(gyro = it.gyro.withAxes(axesPortrait, change(it.gyro.axesFor(axesPortrait)))) }
        Row(
            Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Axes for", Modifier.padding(end = 4.dp))
            FilterChip(selected = !axesPortrait, onClick = { axesPortrait = false }, label = { Text("Landscape") })
            FilterChip(selected = axesPortrait, onClick = { axesPortrait = true }, label = { Text("Portrait") })
            if (axesPortrait && ws.gyro.axesFor(true) != ws.gyro.landscapeAxes) {
                TextButton(onClick = { editWs { it.copy(gyro = it.gyro.copy(portrait = it.gyro.landscapeAxes)) } }) {
                    Text(
                        "Copy landscape"
                    )
                }
            }
        }
        AxisRow(
            "Mouse left/right", axes.mouseX, axes.invertX,
            onAxis = { a -> editAxes { it.copy(mouseX = a) } },
            onInvert = { inv -> editAxes { it.copy(invertX = inv) } })
        AxisRow(
            "Mouse up/down", axes.mouseY, axes.invertY,
            onAxis = { a -> editAxes { it.copy(mouseY = a) } },
            onInvert = { inv -> editAxes { it.copy(invertY = inv) } })
        // Both filters default to off: raw gyro. Calibration only subtracts a fixed resting bias.
        var tight by remember(key) { mutableStateOf(ws.gyro.tighteningThreshold) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jitter cutoff " + if (tight < 0.05f) "off" else "%.1f°/s".format(tight), Modifier.padding(end = 8.dp))
            Slider(
                value = tight, onValueChange = { tight = it }, valueRange = 0f..5f,
                onValueChangeFinished = {
                    if (tight < 0.05f) tight = 0f
                    editWs { it.copy(gyro = it.gyro.copy(tighteningThreshold = tight)) }
                })
        }
        var smooth by remember(key) { mutableStateOf(ws.gyro.smoothingThreshold) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Smoothing " + if (smooth < 0.05f) "off" else "below %.1f°/s".format(smooth),
                Modifier.padding(end = 8.dp)
            )
            Slider(
                value = smooth, onValueChange = { smooth = it }, valueRange = 0f..10f,
                onValueChangeFinished = {
                    if (smooth < 0.05f) smooth = 0f
                    editWs { it.copy(gyro = it.gyro.copy(smoothingThreshold = smooth)) }
                })
        }
        val cal by runtime.calibrationState.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val c = cal) {
                    GamepadRuntime.CalibrationState.None -> "Not calibrated (phone's own drift correction only)"
                    GamepadRuntime.CalibrationState.Running -> "Calibrating: keep the phone still…"
                    GamepadRuntime.CalibrationState.Moved -> "The phone moved. Lay it flat and try again."
                    is GamepadRuntime.CalibrationState.Calibrated -> "Calibrated (removes %.2f°/s drift)".format(c.degPerSec)
                },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            if (cal is GamepadRuntime.CalibrationState.Calibrated) {
                TextButton(onClick = { runtime.clearGyroCalibration() }) { Text("Clear") }
            }
            TextButton(
                onClick = { runtime.calibrateGyro() },
                enabled = cal != GamepadRuntime.CalibrationState.Running
            ) {
                Text("Calibrate")
            }
        }
    }
    RememberedSection(
        runtime,
        "workspace.Controls",
        "Controls & buttons",
        subtitle = "Long press and double tap times, touchpad over buttons, sticky modifiers, opacity"
    ) {
        var longMs by remember(key) { mutableStateOf(ws.longPressMs.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Long press %.0f ms".format(longMs), Modifier.padding(end = 8.dp))
            Slider(
                value = longMs, onValueChange = { longMs = (it / 10f).roundToInt() * 10f },
                valueRange = Workspace.MIN_LONG_PRESS_MS.toFloat()..Workspace.MAX_LONG_PRESS_MS.toFloat(),
                onValueChangeFinished = { editWs { it.copy(longPressMs = longMs.roundToInt()) } })
        }
        var doubleMs by remember(key) { mutableStateOf(ws.doubleTapMs.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Double tap window %.0f ms".format(doubleMs), Modifier.padding(end = 8.dp))
            Slider(
                value = doubleMs, onValueChange = { doubleMs = (it / 10f).roundToInt() * 10f },
                valueRange = Workspace.MIN_DOUBLE_TAP_MS.toFloat()..Workspace.MAX_DOUBLE_TAP_MS.toFloat(),
                onValueChangeFinished = { editWs { it.copy(doubleTapMs = doubleMs.roundToInt()) } })
        }
        Text(
            "For the Tap, Double tap and Long press mappings of touch buttons, controller buttons, keyboard keys " +
                    "and the touchpad. A touch button can set its own.",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow(
            "Touchpad over buttons (a finger on a key moves the cursor; a quick tap presses it)",
            ws.touchpadOverButtons
        ) { v ->
            editWs { it.copy(touchpadOverButtons = v) }
        }
        if (ws.touchpadOverButtons) {
            var tapMs by remember(key) { mutableStateOf(ws.buttonTapMs.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tap up to %.0f ms".format(tapMs), Modifier.padding(end = 8.dp))
                Slider(
                    value = tapMs, onValueChange = { tapMs = (it / 10f).roundToInt() * 10f },
                    valueRange = Workspace.MIN_BUTTON_TAP_MS.toFloat()..Workspace.MAX_BUTTON_TAP_MS.toFloat(),
                    onValueChangeFinished = { editWs { it.copy(buttonTapMs = tapMs.roundToInt()) } })
            }
        }
        SwitchRow("Sticky modifiers (tap Shift, Ctrl… for the next key; double tap locks)", ws.stickyModifiers) { v ->
            editWs { it.copy(stickyModifiers = v) }
        }
        var opacity by remember(key) { mutableStateOf(ws.controlOpacity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Control opacity %.0f%%".format(opacity * 100), Modifier.padding(end = 8.dp))
            Slider(
                value = opacity, onValueChange = { opacity = it }, valueRange = Profile.MIN_CONTROL_OPACITY..1f,
                onValueChangeFinished = { editWs { it.copy(controlOpacity = opacity) } })
        }
    }
    RememberedSection(
        runtime,
        "workspace.Haptics",
        "Haptics",
        subtitle = "How haptic mappings feel and when ticks play"
    ) {
        Text(
            "How haptic mappings without a feel of their own feel. They only play while a PC is connected.",
            style = MaterialTheme.typography.bodySmall,
        )
        HapticFeelEditor(
            ws.haptics,
            held = false,
            onChange = { f -> editWs { it.copy(haptics = f) } },
            onTry = { runtime.previewHaptic(ws.haptics, false) },
        )
        Text("Ticks play", style = MaterialTheme.typography.labelLarge)
        HapticEdgeChips(ws.hapticEdge, onSelect = { e -> if (e != null) editWs { it.copy(hapticEdge = e) } })
        Text(
            "When a source presses or lets go; for a zone, when a finger enters or leaves it. A tick mapping can set its own.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "Touchpad: in Edit layout, tap empty space for its mappings, or long-press it for its settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun AxisRow(
    title: String,
    axis: MotionAxis,
    inverted: Boolean,
    onAxis: (MotionAxis) -> Unit,
    onInvert: (Boolean) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, Modifier.padding(end = 4.dp))
        MotionAxis.entries.forEach { a ->
            FilterChip(selected = axis == a, onClick = { onAxis(a) }, label = { Text(a.label) })
        }
        Text("Invert", Modifier.padding(start = 8.dp))
        Switch(checked = inverted, onCheckedChange = onInvert)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
