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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.isWorkspaceInput
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.runtime.ScreenOrientation
import kotlin.math.roundToInt

/**
 * The Settings page (from the pad's Settings capsule): the App tab, options of the whole app with
 * the corners, and the Defaults tab, what every workspace uses unless it sets its own (its
 * settings page, ⚙ in edit mode).
 */
@Composable
fun AppSettingsScreen(runtime: GamepadRuntime, modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("App") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Defaults") })
        }
        if (tab == 0) AppTab(runtime) else DefaultsTab(runtime)
    }
}

/**
 * The Defaults: every workspace's settings unless it sets them itself. Each shows its
 * value, highlighted with a ↺ where it differs from the built-in one.
 */
@Composable
private fun DefaultsTab(runtime: GamepadRuntime) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "Every workspace uses these, except where it sets its own (its settings): those show highlighted " +
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

@Composable
private fun AppTab(runtime: GamepadRuntime) {
    val context = LocalContext.current
    val gyro by runtime.gyroInfo.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val orientation by runtime.screenOrientation.collectAsStateWithLifecycle()
    val padOnly by runtime.padOnlyInput.collectAsStateWithLifecycle()
    val startFullscreen by runtime.startFullscreen.collectAsStateWithLifecycle()
    val autoHide by runtime.cornerAutoHide.collectAsStateWithLifecycle()
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val visuals by runtime.touchVisuals.collectAsStateWithLifecycle()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow("Start in fullscreen", startFullscreen) { runtime.setStartFullscreen(it) }
        Hint("The pad opens in fullscreen when the app starts. Press ← and → together (or Back) to leave it.")
        SwitchRow("Hide the ← / → corners after a few seconds", autoHide) { runtime.setCornerAutoHide(it) }
        Hint(
            "Tap the screen 4 times quickly to show them again. Hidden corners still work. " +
                    "Off: they are always shown.",
        )

        Text("Touch display", style = MaterialTheme.typography.titleSmall)
        SwitchRow("Show touches", visuals.showTouches) { runtime.setTouchVisuals(visuals.copy(showTouches = it)) }
        SwitchRow("Trail behind fingers", visuals.trail) { runtime.setTouchVisuals(visuals.copy(trail = it)) }
        if (visuals.trail) {
            MsSlider("Trail length", visuals.trailMs, TouchVisuals.MIN_TRAIL_MS..TouchVisuals.MAX_TRAIL_MS) {
                runtime.setTouchVisuals(visuals.copy(trailMs = it))
            }
            var fadePart by remember(visuals.trailFade) { mutableStateOf(visuals.trailFade) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trail fade %.0f%%".format(fadePart * 100), Modifier.padding(end = 8.dp))
                Slider(
                    value = fadePart,
                    onValueChange = { fadePart = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { runtime.setTouchVisuals(visuals.copy(trailFade = fadePart)) },
                )
            }
        }
        if (visuals.showTouches || visuals.trail) {
            MsSlider("Fade out after lift", visuals.fadeMs, 0..TouchVisuals.MAX_FADE_MS) {
                runtime.setTouchVisuals(visuals.copy(fadeMs = it))
            }
        }
        Hint(
            "Trail length: how much of the finger's path (in time) the trail keeps. Trail fade: how much of " +
                    "it, from its end, fades out. Fade out: how long a touch takes to disappear once the finger lifts.",
        )
        SwitchRow("Show live mappings on buttons", visuals.liveMappings) {
            runtime.setTouchVisuals(
                visuals.copy(
                    liveMappings = it
                )
            )
        }
        Hint("A button's label briefly shows what it is sending, such as a tap, double tap or long press action.")
        SwitchRow("Show the swipe line on touchpad buttons", visuals.swipeLine) {
            runtime.setTouchVisuals(visuals.copy(swipeLine = it))
        }
        Hint(
            "A finger on a touchpad button draws a line from its start point, through it, to the last activation length " +
                    "of that direction: a bar at Quick swipe after, dots at Hold after and each zone's end, a ring on a dragged " +
                    "start point. The marks it passed fill in.",
        )
        SwitchRow("Show Move rulers on touchpad buttons", visuals.moveRulers) {
            runtime.setTouchVisuals(visuals.copy(moveRulers = it))
        }
        Hint(
            "Once a finger on a touchpad button moves its Move X or Move Y, it shows a short ruler for each Move, next to " +
                    "the swipe line. The ticks slide as the axis moves, stay dim while a Move is held back, and keep " +
                    "sliding while inertia glides after the lift.",
        )
        GyroVisualsSettings(visuals) { runtime.setTouchVisuals(it) }

        Text("Corners", style = MaterialTheme.typography.titleSmall)
        var cornerOpacity by remember(profile.id) { mutableStateOf(profile.cornerOpacity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity %.0f%%".format(cornerOpacity * 100), Modifier.padding(end = 8.dp))
            Slider(
                value = cornerOpacity,
                onValueChange = { cornerOpacity = it },
                valueRange = Profile.MIN_CONTROL_OPACITY..1f,
                onValueChangeFinished = { runtime.editActiveProfile { it.copy(cornerOpacity = cornerOpacity) } },
            )
        }
        SwitchRow("Wrap around (→ on the last workspace goes to the first)", profile.wrapWorkspaces) { on ->
            runtime.editActiveProfile { it.copy(wrapWorkspaces = on) }
        }
        Hint("Size and position of the corners: Edit layout, then tap or drag a corner.")

        SwitchRow("Send input only while the pad is open", padOnly) { runtime.setPadOnlyInput(it) }
        Hint(
            if (padOnly) "Controller, keyboard and gyro reach the PC only on the pad, outside its pages and edit mode. The connection stays up."
            else "Controller, keyboard and gyro reach the PC from anywhere, also with the app in the background.",
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

        Text("Controllers on phone", style = MaterialTheme.typography.titleSmall)
        if (controllers.isEmpty()) Text("None. Attach any gamepad.")
        controllers.forEach { c ->
            Text("${c.name} (${c.known?.name ?: "standard layout"}) " + "%04X:%04X".format(c.vendorId, c.productId))
        }

        Text("Debugging", style = MaterialTheme.typography.titleSmall)
        val debugOn by runtime.debug.enabled.collectAsStateWithLifecycle()
        SwitchRow("Debug tools", debugOn) { runtime.debug.setEnabled(it) }
        Hint(
            "Shows a debug panel on the pad: the link's speed and latency, the gyro, the active workspace and " +
                    "layer, what the PC is sent now, and a list of what you press and what it turns into. " +
                    "Record saves the last 30 seconds (the PC saves its side too over Wi-Fi or USB). " +
                    "Off: nothing is logged.",
        )

        Text("About", style = MaterialTheme.typography.titleSmall)
        Text("GyroPhone $version", style = MaterialTheme.typography.bodySmall)
        Text("Gyro: ${gyro.source}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Gyro rate: ${gyro.rateHz} Hz" + if (gyro.active) " · moving mouse" else "",
            style = MaterialTheme.typography.bodySmall
        )
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
