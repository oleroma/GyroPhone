package com.oleroma.gyrophone.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults

import com.oleroma.gyrophone.runtime.GamepadRuntime

/** Slider top: higher than the old 60, for fast flicks. */
private const val MAX_GYRO_SENS = 150f

/**
 * One level of workspace settings ([WorkspaceDefaults.keys]): [ws] in effect over [parent], which
 * keys it sets itself ([isOwn], highlighted with a ↺), and edits. [parentName] names what a reset
 * goes back to. A workspace sits over the workspace defaults, the defaults over the built-in values.
 */
internal class WorkspaceLevel(
    val ws: Workspace,
    val parentName: String,
    val isOwn: (String) -> Boolean,
    val edit: ((Workspace) -> Workspace) -> Unit,
    val reset: (String) -> Unit,
    val resetAll: () -> Unit,
) {
    /** Any of [keys] is set here; [resetKeys] sets them all back. */
    fun ownAny(keys: List<String>) = keys.any(isOwn)
    fun resetKeys(keys: List<String>): () -> Unit = { keys.forEach(reset) }
}

/** The active profile's workspace [index]: its Workspace defaults over the Defaults. */
internal fun workspaceLevel(runtime: GamepadRuntime, profile: Profile, index: Int): WorkspaceLevel =
    workspaceLevel(profile.workspace(index), profile.defaults) { change -> runtime.editActiveProfile { it.updateWorkspace(index, change) } }

/** Workspace [ws]'s Workspace defaults over [defaults]; [update] changes the workspace. */
internal fun workspaceLevel(ws: Workspace, defaults: Workspace, update: ((Workspace) -> Workspace) -> Unit): WorkspaceLevel {
    val own = WorkspaceDefaults.own(ws)
    return WorkspaceLevel(
        ws = ws,
        parentName = "Defaults",
        isOwn = { it in own },
        edit = update,
        reset = { k -> update { WorkspaceDefaults.reset(it, k, defaults) } },
        resetAll = { update { WorkspaceDefaults.resetAll(it, defaults) } },
    )
}

/** The Defaults of the active profile over the built-in values. */
internal fun defaultsLevel(runtime: GamepadRuntime, profile: Profile): WorkspaceLevel {
    val d = profile.defaults
    val builtin = WorkspaceDefaults.builtin
    return WorkspaceLevel(
        ws = d,
        parentName = "built-in",
        isOwn = { !WorkspaceDefaults.same(it, d, builtin) },
        edit = { change -> runtime.editActiveProfile { it.copy(defaults = change(it.defaults)) } },
        reset = { k -> runtime.editActiveProfile { it.copy(defaults = WorkspaceDefaults.take(k, it.defaults, builtin)) } },
        resetAll = { runtime.editActiveProfile { p -> p.copy(defaults = WorkspaceDefaults.keys.fold(p.defaults) { r, k -> WorkspaceDefaults.take(k, r, builtin) }) } },
    )
}

private fun g(p: String) = WorkspaceDefaults.gyroKey(p)
private val AXES = listOf("mouseX", "invertX", "scaleX", "mouseY", "invertY", "scaleY", "portrait").map(::g)

/**
 * Every setting of one [level] (a workspace's Workspace defaults, or the Defaults): gyro, buttons,
 * haptics, and the touchpad and gestures, each highlighted with a ↺ where it differs from the
 * parent. [touchpadOpen]: the touchpad's first and open (its ⚙ Settings in the mapping editor).
 * [key] restarts slider drafts when the level changes.
 */
@Composable
internal fun WorkspaceSettings(runtime: GamepadRuntime, level: WorkspaceLevel, key: String, touchpadOpen: Boolean = false) {
    val ws = level.ws
    fun editWs(change: (Workspace) -> Workspace) = level.edit(change)
    fun own(k: String) = level.isOwn(k)
    fun reset(k: String): () -> Unit = { level.reset(k) }

    ResetAllButton(level.parentName, WorkspaceDefaults.keys.any(level.isOwn)) { level.resetAll() }
    if (touchpadOpen) TouchpadSection(runtime, level, key, "touchpad.Touchpad", open = true)
    RememberedSection(runtime, "workspace.Gyro", "Gyro", defaultOpen = true, subtitle = ws.gyro.mode.label) {
        OverrideRow(
            "Gyro to mouse", own(g("mode")), reset(g("mode")),
            hint = when (ws.gyro.mode) {
                GyroMode.ALWAYS -> "Gyro moves the mouse; a ratchet pauses it while held."
                GyroMode.SENSOR_OFF -> "Gyro is off completely: the sensor is not read (saves battery), and ratchets and Toggle gyro do nothing."
                else -> "Gyro is off; a ratchet turns it on while held."
            } + if (ws.gyro.mode == GyroMode.SENSOR_OFF) "" else
                " Turn on Ratchet on any touch button (or map Ratchet to a controller button); Toggle gyro swaps the two modes.",
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(GyroMode.ALWAYS, GyroMode.OFF, GyroMode.SENSOR_OFF).forEach { mode ->
                    FilterChip(selected = ws.gyro.mode == mode, onClick = {
                        editWs { it.copy(gyro = it.gyro.copy(mode = mode)) }
                    }, label = { Text(mode.label) })
                }
            }
        }
        OverrideSwitch(
            "Touchpad is a ratchet",
            "A finger on the empty pad flips gyro while it is down, even with the touchpad off.",
            ws.gyro.touchpadRatchet, own(g("touchpadRatchet")), reset(g("touchpadRatchet")),
        ) { v -> editWs { it.copy(gyro = it.gyro.copy(touchpadRatchet = v)) } }
        OverrideRow(
            "Ratchet acts on", own(g("ratchetAxes")), reset(g("ratchetAxes")),
            hint = "Only X: a ratchet stops or starts only the sideways motion (re-centre left and right while up and down keeps going).",
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RatchetAxes.entries.forEach { a ->
                    FilterChip(
                        selected = ws.gyro.ratchetAxes == a,
                        onClick = { editWs { it.copy(gyro = it.gyro.copy(ratchetAxes = a)) } },
                        label = { Text(a.label) },
                    )
                }
            }
        }
        OverrideSlider(key + "gx", "X sensitivity %.0f", ws.gyro.sensitivity, 0f..MAX_GYRO_SENS, 1f, own(g("sensitivity")), reset(g("sensitivity"))) { v ->
            editWs { it.copy(gyro = it.gyro.copy(sensitivity = v)) }
        }
        OverrideSlider(
            key + "gy", "Y sensitivity %.0f", ws.gyro.verticalSensitivity, 0f..MAX_GYRO_SENS, 1f,
            own(g("sensitivityY")) || own(g("verticalRatio")), level.resetKeys(listOf(g("sensitivityY"), g("verticalRatio"))),
            hint = "Mouse counts per degree, sideways (X) and up and down (Y). 0 turns that direction off.",
        ) { v -> editWs { it.copy(gyro = it.gyro.copy(sensitivityY = v)) } }
        OverrideRow("Held as", own(g("orientation")), reset(g("orientation"))) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GyroOrientation.entries.forEach { o ->
                    FilterChip(
                        selected = ws.gyro.orientation == o,
                        onClick = { editWs { it.copy(gyro = it.gyro.copy(orientation = o)) } },
                        label = { Text(o.label) })
                }
            }
        }
        // Landscape and portrait each have their own axis mapping; start on the way the phone is held.
        val heldPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        var axesPortrait by remember(heldPortrait) { mutableStateOf(heldPortrait) }
        val axes = ws.gyro.axesFor(axesPortrait)
        fun editAxes(change: (GyroAxes) -> GyroAxes) =
            editWs { it.copy(gyro = it.gyro.withAxes(axesPortrait, change(it.gyro.axesFor(axesPortrait)))) }
        OverrideRow("Axes", level.ownAny(AXES), level.resetKeys(AXES)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Axes for", Modifier.padding(end = 4.dp))
                FilterChip(selected = !axesPortrait, onClick = { axesPortrait = false }, label = { Text("Landscape") })
                FilterChip(selected = axesPortrait, onClick = { axesPortrait = true }, label = { Text("Portrait") })
                if (axesPortrait && ws.gyro.axesFor(true) != ws.gyro.landscapeAxes) {
                    TextButton(onClick = { editWs { it.copy(gyro = it.gyro.copy(portrait = it.gyro.landscapeAxes)) } }) {
                        Text("Copy landscape")
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
        }
        // Both filters default to off: raw gyro. Calibration only subtracts a fixed resting bias.
        val tight = ws.gyro.tighteningThreshold
        OverrideSlider(
            key + "tight", if (tight < 0.05f) "Jitter cutoff off" else "Jitter cutoff %.1f°/s", tight, 0f..5f, 0.1f,
            own(g("tighteningThreshold")), reset(g("tighteningThreshold")),
        ) { v -> editWs { it.copy(gyro = it.gyro.copy(tighteningThreshold = if (v < 0.05f) 0f else v)) } }
        val smooth = ws.gyro.smoothingThreshold
        OverrideSlider(
            key + "smooth", if (smooth < 0.05f) "Smoothing off" else "Smoothing below %.1f°/s", smooth, 0f..10f, 0.1f,
            own(g("smoothingThreshold")), reset(g("smoothingThreshold")),
        ) { v -> editWs { it.copy(gyro = it.gyro.copy(smoothingThreshold = if (v < 0.05f) 0f else v)) } }
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
        OverrideSwitch(
            "Touchpad over buttons",
            "A finger on a key moves the cursor; a quick tap presses it.",
            ws.touchpadOverButtons, own(WorkspaceDefaults.TOUCHPAD_OVER_BUTTONS), reset(WorkspaceDefaults.TOUCHPAD_OVER_BUTTONS),
        ) { v -> editWs { it.copy(touchpadOverButtons = v) } }
        if (ws.touchpadOverButtons) {
            MsOverride(
                key + "tap", "Tap up to", ws.buttonTapMs, Workspace.MIN_BUTTON_TAP_MS..Workspace.MAX_BUTTON_TAP_MS,
                own(WorkspaceDefaults.BUTTON_TAP), reset(WorkspaceDefaults.BUTTON_TAP),
            ) { ms -> editWs { it.copy(buttonTapMs = ms) } }
        }
        OverrideSwitch(
            "Sticky modifiers",
            "Tap Shift, Ctrl… for the next key; double tap locks.",
            ws.stickyModifiers, own(WorkspaceDefaults.STICKY_MODIFIERS), reset(WorkspaceDefaults.STICKY_MODIFIERS),
        ) { v -> editWs { it.copy(stickyModifiers = v) } }
        OverrideSlider(
            key + "opacity", "Control opacity %.0f%%", ws.controlOpacity * 100, Profile.MIN_CONTROL_OPACITY * 100..100f, 1f,
            own(WorkspaceDefaults.CONTROL_OPACITY), reset(WorkspaceDefaults.CONTROL_OPACITY),
        ) { v -> editWs { it.copy(controlOpacity = v / 100f) } }
    }
    RememberedSection(
        runtime,
        "workspace.Haptics",
        "Haptics",
        subtitle = "How haptic mappings feel and when ticks play"
    ) {
        OverrideRow(
            "Feel", own(WorkspaceDefaults.HAPTICS), reset(WorkspaceDefaults.HAPTICS),
            hint = "How haptic mappings without a feel of their own feel. They only play while a PC is connected.",
        ) {
            HapticFeelEditor(
                ws.haptics,
                held = false,
                onChange = { f -> editWs { it.copy(haptics = f) } },
                onTry = { runtime.previewHaptic(ws.haptics, false) },
            )
        }
        OverrideRow(
            "Ticks play", own(WorkspaceDefaults.HAPTIC_EDGE), reset(WorkspaceDefaults.HAPTIC_EDGE),
            hint = "When a source presses or lets go; for an area, when a finger enters or leaves it. A tick mapping can set its own.",
        ) {
            HapticEdgeChips(ws.hapticEdge, onSelect = { e -> if (e != null) editWs { it.copy(hapticEdge = e) } })
        }
    }
    if (!touchpadOpen) TouchpadSection(runtime, level, key, "workspace.Touchpad", open = false)
}

/** The touchpad and gesture settings of [level]: gesture times, then the touchpad's own. */
@Composable
private fun TouchpadSection(runtime: GamepadRuntime, level: WorkspaceLevel, key: String, prefKey: String, open: Boolean) {
    val ws = level.ws
    RememberedSection(
        runtime,
        prefKey,
        "Touchpad & gestures",
        defaultOpen = open,
        subtitle = "Long press and double tap times, speed, scrolling and directions",
    ) {
        MsOverride(
            key + "long", "Long press after", ws.longPressMs, Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
            level.isOwn(WorkspaceDefaults.LONG_PRESS), { level.reset(WorkspaceDefaults.LONG_PRESS) },
            hint = "For the Tap and Long press of the touchpad, touch buttons, controller buttons and keyboard keys without a time of their own.",
        ) { ms -> level.edit { it.copy(longPressMs = ms) } }
        MsOverride(
            key + "double", "Double tap within", ws.doubleTapMs, Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
            level.isOwn(WorkspaceDefaults.DOUBLE_TAP), { level.reset(WorkspaceDefaults.DOUBLE_TAP) },
            hint = "The same, for Double tap windows.",
        ) { ms -> level.edit { it.copy(doubleTapMs = ms) } }
        SurfaceSharedSettings(
            workspaceSurfaceLevel(level),
            key + "tp",
            Bindings.holdZoneCounts(ws.mappings, ""),
            touchpad = true,
            resetAll = false,
        )
        Text(
            "Touchpad buttons use these too, unless they set their own (their Mappings, ⚙ Settings).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
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
