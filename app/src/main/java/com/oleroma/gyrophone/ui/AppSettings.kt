package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.isWorkspaceInput
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.runtime.ScreenOrientation
import kotlin.math.roundToInt

/**
 * The Settings page (the pad's Settings capsule, or ⚙ in edit mode): chips pick App (options of
 * the whole app), Defaults (what every workspace uses unless it sets its own) or the current
 * workspace (its own settings over the Defaults, with its layers). [startOnWorkspace]: opened
 * from edit mode, on the workspace.
 */
@Composable
fun SettingsScreen(runtime: GamepadRuntime, modifier: Modifier, startOnWorkspace: Boolean, onWorkspaceDeleted: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val ws = profile.workspace(wsIndex.coerceIn(0, profile.workspaces.lastIndex))
    var tab by rememberSaveable(startOnWorkspace) { mutableIntStateOf(if (startOnWorkspace) TAB_WORKSPACE else TAB_APP) }
    Column(modifier) {
        Row(
            Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = tab == TAB_APP, onClick = { tab = TAB_APP }, label = { Text("App") })
            FilterChip(selected = tab == TAB_DEFAULTS, onClick = { tab = TAB_DEFAULTS }, label = { Text("Defaults") })
            FilterChip(selected = tab == TAB_WORKSPACE, onClick = { tab = TAB_WORKSPACE }, label = { Text(ws.name) })
        }
        when (tab) {
            TAB_APP -> AppTab(runtime)
            TAB_DEFAULTS -> DefaultsTab(runtime)
            else -> WorkspaceTab(runtime, Modifier.fillMaxSize(), onDeleted = onWorkspaceDeleted)
        }
    }
}

private const val TAB_APP = 0
private const val TAB_DEFAULTS = 1
private const val TAB_WORKSPACE = 2

/**
 * The Defaults: every workspace's settings unless it sets them itself. Each shows its
 * value, highlighted with a ↺ where it differs from the built-in one.
 */
@Composable
private fun DefaultsTab(runtime: GamepadRuntime) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "Every workspace uses these, except where it sets its own (its chip above): those show highlighted " +
                    "there, with ↺ to follow these again.",
        )
        var mapping by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The touchpad and motion mappings every workspace starts from (none until you map them; " +
                        "Presets has Windows touchpad and Gyro mouse). A workspace maps an action itself to override one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(4.dp),
            )
            OutlinedButton(onClick = { mapping = true }) { Text("Mappings") }
        }
        val level = defaultsLevel(runtime, profile)
        WorkspaceSettings(runtime, level, profile.id + "/defaults")
        if (mapping) {
            ControlMappingScreen(
                control = TOUCHPAD_STAND_IN,
                mappings = profile.defaults.mappings.filter { it.source.isWorkspaceInput },
                runtime = runtime,
                onMappingsChange = { m ->
                    runtime.editActiveProfile { p ->
                        p.copy(defaults = p.defaults.copy(mappings = p.defaults.mappings.filterNot { it.source.isWorkspaceInput } + m))
                    }
                },
                onSettings = null,
                onClose = { mapping = false },
                settings = touchpadSettings(runtime, level),
            )
        }
    }
}

/** Options of the whole app, as categories like the workspace's: the pad, the touch display, the phone. */
@Composable
private fun AppTab(runtime: GamepadRuntime) {
    val context = LocalContext.current
    val gyro by runtime.gyroInfo.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val orientation by runtime.screenOrientation.collectAsStateWithLifecycle()
    val startFullscreen by runtime.startFullscreen.collectAsStateWithLifecycle()
    val visuals by runtime.touchVisuals.collectAsStateWithLifecycle()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }

    val items = listOf(
        SectionItem("pad") { card, header ->
            RememberedSection(runtime, "app.Pad", "Pad", card, defaultOpen = true, subtitle = "Fullscreen and rotation", headerModifier = header) {
                SwitchRow("Start in fullscreen", startFullscreen) { runtime.setStartFullscreen(it) }
                Hint(
                    "The pad opens in fullscreen when the app starts. Press ← and → together (or Back) to leave it. " +
                            "The corners' own settings are on them: Edit layout, then tap a corner.",
                )
                Text("Screen rotation", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenOrientation.entries.forEach { o ->
                        FilterChip(
                            selected = orientation == o,
                            onClick = { runtime.setScreenOrientation(o) },
                            label = { Text(o.label) })
                    }
                }
                Hint(
                    "Auto, Landscape and Portrait follow the phone's rotation lock: unlocked they turn with the phone " +
                            "(Landscape and Portrait only between their two sides), locked they stay as they are. The forced " +
                            "ones never turn. The gyro always follows the way the screen is turned.",
                )
            }
        },
        SectionItem("touches") { card, header ->
            RememberedSection(runtime, "app.Touches", "Touch display", card, subtitle = "Touches, live mappings, swipe line", headerModifier = header) {
                TouchesSettings(visuals) { runtime.setTouchVisuals(it) }
            }
        },
        SectionItem("phone") { card, header ->
            RememberedSection(runtime, "app.Phone", "Phone", card, subtitle = "Gyro calibration, controllers, version", headerModifier = header) {
                GyroCalibrationRow(runtime)
                Text("Controllers on phone", style = MaterialTheme.typography.titleSmall)
                if (controllers.isEmpty()) Text("None. Attach a GameSir X5 or any gamepad.")
                controllers.forEach { c ->
                    Text("${c.name} (${c.known?.name ?: "standard layout"}) " + "%04X:%04X".format(c.vendorId, c.productId))
                }
                Text("About", style = MaterialTheme.typography.titleSmall)
                Text("GyroPhone $version", style = MaterialTheme.typography.bodySmall)
                Text("Gyro: ${gyro.source}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Gyro rate: ${gyro.rateHz} Hz" + if (gyro.active) " · moving mouse" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReorderableSections(runtime, "app", items)
    }
}

/** How touches show: Off, Dots (where fingers are) or Trail (also the path behind them), live mappings and the swipe line. */
@Composable
private fun TouchesSettings(visuals: TouchVisuals, onChange: (TouchVisuals) -> Unit) {
    Text("Touches", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !visuals.showTouches && !visuals.trail, onClick = { onChange(visuals.copy(showTouches = false, trail = false)) }, label = { Text("Off") })
        FilterChip(selected = visuals.showTouches && !visuals.trail, onClick = { onChange(visuals.copy(showTouches = true, trail = false)) }, label = { Text("Dots") })
        FilterChip(selected = visuals.trail, onClick = { onChange(visuals.copy(showTouches = true, trail = true)) }, label = { Text("Trail") })
    }
    if (visuals.trail) {
        MsSlider("Trail length", visuals.trailMs, TouchVisuals.MIN_TRAIL_MS..TouchVisuals.MAX_TRAIL_MS) {
            onChange(visuals.copy(trailMs = it))
        }
    }
    if (visuals.showTouches || visuals.trail) {
        MsSlider("Fade out after lift", visuals.fadeMs, 0..TouchVisuals.MAX_FADE_MS) {
            onChange(visuals.copy(fadeMs = it))
        }
        Hint("Trail length: how much of the finger's path (in time) the trail keeps. Fade out: how long a touch takes to disappear once the finger lifts.")
    }
    SwitchRow("Show live mappings on buttons", visuals.liveMappings) { onChange(visuals.copy(liveMappings = it)) }
    Hint("A button's label briefly shows what it is sending, such as its tap, double or long press mapping.")
    SwitchRow("Show the swipe line on buttons", visuals.swipeLine) { onChange(visuals.copy(swipeLine = it)) }
    Hint(
        "A finger on a button draws a line from its start point, through it, to the last length of that " +
                "direction: a bar at the repeating swipe distance, dots at the hold distance and each zone's end, a ring " +
                "on a dragged start point. The marks it passed fill in.",
    )
}

/** The phone's gyro calibration: one resting drift for the phone, whatever the workspace. */
@Composable
private fun GyroCalibrationRow(runtime: GamepadRuntime) {
    val cal by runtime.calibrationState.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (val c = cal) {
                GamepadRuntime.CalibrationState.None -> "Gyro not calibrated (phone's own drift correction only)"
                GamepadRuntime.CalibrationState.Running -> "Calibrating: keep the phone still…"
                GamepadRuntime.CalibrationState.Moved -> "The phone moved. Lay it flat and try again."
                is GamepadRuntime.CalibrationState.Calibrated -> "Gyro calibrated (removes %.2f°/s drift)".format(c.degPerSec)
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

/** A time setting in steps of 10 ms; [onChange] gets it once the drag ends. */
@Composable
private fun MsSlider(title: String, ms: Int, range: IntRange, onChange: (Int) -> Unit) {
    var v by remember(ms) { mutableStateOf(ms.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$title %.0f ms".format(v), Modifier.padding(end = 8.dp))
        Slider(
            value = v,
            onValueChange = { v = (it / 10f).roundToInt() * 10f },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { onChange(v.roundToInt()) },
        )
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
