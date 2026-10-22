package com.oleroma.gyrophone.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

/**
 * Gyro and touchpad settings of the active profile's workspace [index]. Edits go
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

    Text("Gyro to mouse", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(GyroMode.ALWAYS, GyroMode.OFF, GyroMode.HOLD_OR_TOGGLE).forEach { mode ->
            FilterChip(selected = ws.gyro.mode == mode, onClick = {
                editWs { it.copy(gyro = it.gyro.copy(mode = mode)) }
            }, label = { Text(mode.label) })
        }
    }
    Text(
        when (ws.gyro.mode) {
            GyroMode.ALWAYS -> "Gyro moves the mouse; a Ratchet button pauses it while held."
            GyroMode.OFF -> "Gyro is off; a Ratchet button turns it on while held."
            GyroMode.HOLD_OR_TOGGLE -> "Gyro moves the mouse while a Gyro hold button is held or after a Gyro toggle press."
        } + " Map Ratchet to any touch or controller button.",
        style = MaterialTheme.typography.bodySmall,
    )
    SwitchRow("Touchpad is a ratchet (a finger on the touchpad flips gyro while it is down)", ws.gyro.touchpadRatchet) { v ->
        editWs { it.copy(gyro = it.gyro.copy(touchpadRatchet = v)) }
    }
    SwitchRow("Gyro corner (tap to switch always on / always off, in every workspace)", profile.gyroCorner) { v ->
        runtime.editActiveProfile { it.copy(gyroCorner = v) }
    }
    var sens by remember(key) { mutableStateOf(ws.gyro.sensitivity) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sensitivity %.0f".format(sens), Modifier.padding(end = 8.dp))
        Slider(value = sens, onValueChange = { sens = it }, valueRange = 2f..60f,
            onValueChangeFinished = { editWs { it.copy(gyro = it.gyro.copy(sensitivity = sens)) } })
    }
    var ratio by remember(key) { mutableStateOf(ws.gyro.verticalRatio) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Vertical %.2f× horizontal".format(ratio), Modifier.padding(end = 8.dp))
        Slider(value = ratio, onValueChange = { ratio = (it * 20).roundToInt() / 20f }, valueRange = 0.25f..2f,
            onValueChangeFinished = { editWs { it.copy(gyro = it.gyro.copy(verticalRatio = ratio)) } })
    }
    Text("Held as", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GyroOrientation.entries.forEach { o ->
            FilterChip(selected = ws.gyro.orientation == o,
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
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Axes for", Modifier.padding(end = 4.dp))
        FilterChip(selected = !axesPortrait, onClick = { axesPortrait = false }, label = { Text("Landscape") })
        FilterChip(selected = axesPortrait, onClick = { axesPortrait = true }, label = { Text("Portrait") })
        if (axesPortrait && ws.gyro.axesFor(true) != ws.gyro.landscapeAxes) {
            TextButton(onClick = { editWs { it.copy(gyro = it.gyro.copy(portrait = it.gyro.landscapeAxes)) } }) { Text("Copy landscape") }
        }
    }
    AxisRow("Mouse left/right", axes.mouseX, axes.invertX,
        onAxis = { a -> editAxes { it.copy(mouseX = a) } },
        onInvert = { inv -> editAxes { it.copy(invertX = inv) } })
    AxisRow("Mouse up/down", axes.mouseY, axes.invertY,
        onAxis = { a -> editAxes { it.copy(mouseY = a) } },
        onInvert = { inv -> editAxes { it.copy(invertY = inv) } })
    // Both filters default to off: raw gyro. Calibration only subtracts a fixed resting bias.
    var tight by remember(key) { mutableStateOf(ws.gyro.tighteningThreshold) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Jitter cutoff " + if (tight < 0.05f) "off" else "%.1f°/s".format(tight), Modifier.padding(end = 8.dp))
        Slider(value = tight, onValueChange = { tight = it }, valueRange = 0f..5f,
            onValueChangeFinished = {
                if (tight < 0.05f) tight = 0f
                editWs { it.copy(gyro = it.gyro.copy(tighteningThreshold = tight)) }
            })
    }
    var smooth by remember(key) { mutableStateOf(ws.gyro.smoothingThreshold) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Smoothing " + if (smooth < 0.05f) "off" else "below %.1f°/s".format(smooth), Modifier.padding(end = 8.dp))
        Slider(value = smooth, onValueChange = { smooth = it }, valueRange = 0f..10f,
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
        TextButton(onClick = { runtime.calibrateGyro() }, enabled = cal != GamepadRuntime.CalibrationState.Running) {
            Text("Calibrate")
        }
    }

    HorizontalDivider()
    Text("Touch keys", style = MaterialTheme.typography.titleSmall)
    SwitchRow("Touchpad over buttons (a moving finger on a key moves the cursor)", ws.touchpadOverButtons) { v ->
        editWs { it.copy(touchpadOverButtons = v) }
    }
    SwitchRow("Sticky modifiers (tap Shift, Ctrl… for the next key; double tap locks)", ws.stickyModifiers) { v ->
        editWs { it.copy(stickyModifiers = v) }
    }

    HorizontalDivider()
    Text("Touchpad (empty pad area)", style = MaterialTheme.typography.titleSmall)
    val tp = ws.touchpad
    SwitchRow("Enabled", tp.enabled) { v -> editWs { it.copy(touchpad = it.touchpad.copy(enabled = v)) } }
    SwitchRow("Tap to click", tp.tapToClick) { v -> editWs { it.copy(touchpad = it.touchpad.copy(tapToClick = v)) } }
    SwitchRow("Tap and drag", tp.tapDrag) { v -> editWs { it.copy(touchpad = it.touchpad.copy(tapDrag = v)) } }
    SwitchRow("Two-finger scroll", tp.twoFingerScroll) { v ->
        editWs { it.copy(touchpad = it.touchpad.copy(twoFingerScroll = v)) }
    }
    SwitchRow("Natural scrolling", tp.naturalScroll) { v ->
        editWs { it.copy(touchpad = it.touchpad.copy(naturalScroll = v)) }
    }
    var tpSens by remember(key) { mutableStateOf(tp.sensitivity) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Speed %.1f".format(tpSens), Modifier.padding(end = 8.dp))
        Slider(value = tpSens, onValueChange = { tpSens = it }, valueRange = 0.3f..5f,
            onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(sensitivity = tpSens)) } })
    }
    var tpAccel by remember(key) { mutableStateOf(tp.acceleration) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Acceleration %.1f".format(tpAccel), Modifier.padding(end = 8.dp))
        Slider(value = tpAccel, onValueChange = { tpAccel = it }, valueRange = 0f..2f,
            onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(acceleration = tpAccel)) } })
    }
    var tpScroll by remember(key) { mutableStateOf(tp.scrollSpeed) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Scroll speed %.1f".format(tpScroll), Modifier.padding(end = 8.dp))
        Slider(value = tpScroll, onValueChange = { tpScroll = it }, valueRange = 0.25f..3f,
            onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(scrollSpeed = tpScroll)) } })
    }
    SwitchRow("Horizontal scrolling", tp.horizontalScroll) { v ->
        editWs { it.copy(touchpad = it.touchpad.copy(horizontalScroll = v)) }
    }
    SwitchRow("Inertia (keeps scrolling after a flick)", tp.inertia) { v ->
        editWs { it.copy(touchpad = it.touchpad.copy(inertia = v)) }
    }
    if (tp.inertia) {
        var tpGlide by remember(key) { mutableStateOf(tp.inertiaGlideMs) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Glide %.0f ms".format(tpGlide), Modifier.padding(end = 8.dp))
            Slider(value = tpGlide, onValueChange = { tpGlide = it },
                valueRange = TouchpadSettings.MIN_GLIDE_MS..TouchpadSettings.MAX_GLIDE_MS,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(inertiaGlideMs = tpGlide)) } })
        }
    }
    SwitchRow("Pinch to zoom (Ctrl + wheel)", tp.pinchZoom) { v ->
        editWs { it.copy(touchpad = it.touchpad.copy(pinchZoom = v)) }
    }
    SwitchRow("3 and 4 finger gestures", tp.gestures) { v ->
        editWs { it.copy(touchpad = it.touchpad.copy(gestures = v)) }
    }
    if (tp.gestures) {
        TouchpadGestureList(runtime, tp) { t -> editWs { it.copy(touchpad = t) } }
    }
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
