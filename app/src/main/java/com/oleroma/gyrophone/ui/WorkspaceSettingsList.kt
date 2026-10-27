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
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GyroAxes
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.RatchetAxes
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.isTouchpad
import com.oleroma.gyrophone.core.model.withTouchpadMappings
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

/** Slider tops: higher than the old 60 and 5, for fast flicks and big screens. */
private const val MAX_GYRO_SENS = 150f
private const val MAX_TOUCHPAD_SENS = 12f

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
        subtitle = "Touchpad over buttons, sticky modifiers, opacity"
    ) {
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
    RememberedSection(
        runtime,
        "workspace.Touchpad",
        "Touchpad",
        subtitle = "The empty pad area: speed, scrolling, taps"
    ) {
        val tp = ws.touchpad
        SwitchRow("Enabled", tp.enabled) { v -> editWs { it.copy(touchpad = it.touchpad.copy(enabled = v)) } }
        SwitchRow("Natural scrolling", tp.naturalScroll) { v ->
            editWs { it.copy(touchpad = it.touchpad.copy(naturalScroll = v)) }
        }
        var tpSens by remember(key) { mutableStateOf(tp.sensitivity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("X speed %.1f".format(tpSens), Modifier.padding(end = 8.dp))
            Slider(
                value = tpSens,
                onValueChange = { tpSens = (it * 10).roundToInt() / 10f },
                valueRange = 0f..MAX_TOUCHPAD_SENS,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(sensitivity = tpSens)) } })
        }
        var tpSensY by remember(key) { mutableStateOf(tp.verticalSensitivity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Y speed %.1f".format(tpSensY), Modifier.padding(end = 8.dp))
            Slider(
                value = tpSensY,
                onValueChange = { tpSensY = (it * 10).roundToInt() / 10f },
                valueRange = 0f..MAX_TOUCHPAD_SENS,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(sensitivityY = tpSensY)) } })
        }
        Text(
            "0 turns that direction off, e.g. X 0 for a touchpad that only moves up and down.",
            style = MaterialTheme.typography.bodySmall
        )
        var tpAccel by remember(key) { mutableStateOf(tp.acceleration) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Acceleration %.1f".format(tpAccel), Modifier.padding(end = 8.dp))
            Slider(
                value = tpAccel, onValueChange = { tpAccel = it }, valueRange = 0f..2f,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(acceleration = tpAccel)) } })
        }
        var tpScroll by remember(key) { mutableStateOf(tp.scrollSpeed) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scroll speed %.1f".format(tpScroll), Modifier.padding(end = 8.dp))
            Slider(
                value = tpScroll, onValueChange = { tpScroll = it }, valueRange = 0.25f..3f,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(scrollSpeed = tpScroll)) } })
        }
        SwitchRow("Inertia (keeps scrolling after a flick)", tp.inertia) { v ->
            editWs { it.copy(touchpad = it.touchpad.copy(inertia = v)) }
        }
        if (tp.inertia) {
            var tpGlide by remember(key) { mutableStateOf(tp.inertiaGlideMs) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Glide %.0f ms".format(tpGlide), Modifier.padding(end = 8.dp))
                Slider(
                    value = tpGlide, onValueChange = { tpGlide = it },
                    valueRange = TouchpadSettings.MIN_GLIDE_MS..TouchpadSettings.MAX_GLIDE_MS,
                    onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(inertiaGlideMs = tpGlide)) } })
            }
        }
        var tpReleaseTap by remember(key) { mutableStateOf(tp.releaseTapMs.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tpReleaseTap < 1f) "Release tap time: off" else "Release tap time %.0f ms".format(tpReleaseTap),
                Modifier.padding(end = 8.dp),
            )
            Slider(
                value = tpReleaseTap, onValueChange = { tpReleaseTap = (it / 10f).roundToInt() * 10f },
                valueRange = 0f..TouchpadSettings.MAX_RELEASE_TAP_MS.toFloat(),
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(releaseTapMs = tpReleaseTap.roundToInt())) } })
        }
        Text(
            "Touchpad Release mappings fire only after a touch shorter than this. Off: after every touch.",
            style = MaterialTheme.typography.bodySmall,
        )
        var tpDeadzone by remember(key) { mutableStateOf(tp.cursorDeadzoneDp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tpDeadzone < 1f) "Cursor deadzone: off" else "Cursor deadzone %.0f dp".format(tpDeadzone),
                Modifier.padding(end = 8.dp),
            )
            Slider(
                value = tpDeadzone, onValueChange = { tpDeadzone = it.roundToInt().toFloat() },
                valueRange = 0f..TouchpadSettings.MAX_CURSOR_DEADZONE_DP,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(cursorDeadzoneDp = tpDeadzone)) } })
        }
        Text(
            "One finger: the cursor stays still until the finger has moved this far, then catches up with it.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (tp.cursorDeadzoneDp > 0f) {
            var tpCatchUp by remember(key) { mutableStateOf(tp.catchUpMs.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (tpCatchUp < 1f) "Catch-up: instant" else "Catch-up %.0f ms".format(tpCatchUp),
                    Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = tpCatchUp, onValueChange = { tpCatchUp = (it / 10f).roundToInt() * 10f },
                    valueRange = 0f..TouchpadSettings.MAX_CATCH_UP_MS.toFloat(),
                    onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(catchUpMs = tpCatchUp.roundToInt())) } })
            }
            var tpRearm by remember(key) { mutableStateOf(tp.deadzoneRearmMs.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (tpRearm < 1f) "Re-arm: off" else "Re-arm after %.0f ms still".format(tpRearm),
                    Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = tpRearm, onValueChange = { tpRearm = (it / 10f).roundToInt() * 10f },
                    valueRange = 0f..TouchpadSettings.MAX_REARM_MS.toFloat(),
                    onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(deadzoneRearmMs = tpRearm.roundToInt())) } })
            }
            Text(
                "The deadzone arms again once the finger stands still this long. Off: once per touch.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (tp.deadzoneRearmMs > 0) {
                var tpStill by remember(key) { mutableStateOf(tp.deadzoneStillDp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Still within %.1f dp".format(tpStill), Modifier.padding(end = 8.dp))
                    Slider(
                        value = tpStill, onValueChange = { tpStill = (it * 2f).roundToInt() / 2f },
                        valueRange = 0f..TouchpadSettings.MAX_STILL_DP,
                        onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(deadzoneStillDp = tpStill)) } })
                }
                Text(
                    "Movement smaller than this still counts as standing still.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        var tpVLock by remember(key) { mutableStateOf(tp.verticalLockDeg) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tpVLock < 1f) "Vertical lock: off" else "Vertical lock %.0f°".format(tpVLock),
                Modifier.padding(end = 8.dp),
            )
            Slider(
                value = tpVLock, onValueChange = { tpVLock = it.roundToInt().toFloat() },
                valueRange = 0f..TouchpadSettings.MAX_LOCK_DEG,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(verticalLockDeg = tpVLock)) } })
        }
        var tpHLock by remember(key) { mutableStateOf(tp.horizontalLockDeg) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tpHLock < 1f) "Horizontal lock: off" else "Horizontal lock %.0f°".format(tpHLock),
                Modifier.padding(end = 8.dp),
            )
            Slider(
                value = tpHLock, onValueChange = { tpHLock = it.roundToInt().toFloat() },
                valueRange = 0f..TouchpadSettings.MAX_LOCK_DEG,
                onValueChangeFinished = { editWs { it.copy(touchpad = it.touchpad.copy(horizontalLockDeg = tpHLock)) } })
        }
        Text(
            "Motion within this angle of straight up / down loses its sideways part (vertical lock), or within " +
                    "this angle of sideways its up / down part (horizontal lock): cursor, scroll, sticks and held " +
                    "directions alike. So a swipe down does not turn the camera.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    RememberedSection(
        runtime,
        "workspace.TouchpadMappings",
        "Touchpad mappings",
        subtitle = "Taps and gestures with 1–4 fingers"
    ) {
        // Taps and gestures are mappings; edits go to the workspace's mapping profile.
        val tpMappings = ws.withTouchpadMappings().mappings
        for (n in 1..4) {
            Text(
                if (n == 1) "1 finger" else "$n fingers",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            TouchMappingList(
                mappings = tpMappings,
                inputs = Bindings.surfaceInputs(n),
                onChange = { list -> editWs { it.copy(mappings = list, touchpad = it.touchpad.copy(mapped = true)) } },
                runtime = runtime,
            )
        }
        TextButton(onClick = {
            editWs { w ->
                val m = w.withTouchpadMappings()
                m.copy(mappings = m.mappings.filterNot { it.source.isTouchpad } + Bindings.defaultTouchpadMappings(
                    TouchpadSettings()
                ))
            }
        }) { Text("Reset touchpad mappings") }
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
