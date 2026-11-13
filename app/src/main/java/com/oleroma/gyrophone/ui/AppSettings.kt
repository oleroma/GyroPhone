package com.oleroma.gyrophone.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
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
import com.oleroma.gyrophone.core.model.ProfileExport
import com.oleroma.gyrophone.core.model.isWorkspaceInput
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.runtime.ScreenOrientation
import kotlin.math.roundToInt

/** The App page (the pad's App capsule, a gear): options of the whole app. */
@Composable
fun AppSettingsScreen(runtime: GamepadRuntime, modifier: Modifier) {
    SearchableSettings(modifier) { AppTab(runtime) }
}

/**
 * The Defaults page (the pad's Defaults capsule, sliders): what every workspace uses unless it sets
 * its own (its settings page, the sliders in edit mode).
 */
@Composable
fun DefaultsSettingsScreen(runtime: GamepadRuntime, modifier: Modifier) {
    SearchableSettings(modifier) { DefaultsTab(runtime) }
}

/**
 * The Defaults: every workspace's settings unless it sets them itself. Each shows its
 * value, highlighted with a ↺ where it differs from the built-in one.
 */
@Composable
private fun DefaultsTab(runtime: GamepadRuntime) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "Every workspace uses these, except where it sets its own (its settings): those show highlighted " +
                    "there, with ↺ to follow these again. Long press a tab to move it.",
        )
        var mapping by remember { mutableStateOf(false) }
        NotSearching {
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
        }
        // Kept while the profile is: its own keys and children are worked out once, not per recomposition.
        val level = remember(profile) { defaultsLevel(runtime, profile) }
        WorkspaceSettings(runtime, level, profile.id + "/defaults")
        if (mapping) NoSearch {
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

/** The App tab: the whole app's options, as categories the person can reorder. */
@Composable
private fun AppTab(runtime: GamepadRuntime) {
    val context = LocalContext.current
    val gyro by runtime.gyroInfo.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val orientation by runtime.screenOrientation.collectAsStateWithLifecycle()
    val padOnly by runtime.padOnlyInput.collectAsStateWithLifecycle()
    val padOnlyHardware by runtime.padOnlyHardware.collectAsStateWithLifecycle()
    val startFullscreen by runtime.startFullscreen.collectAsStateWithLifecycle()
    val keepScreenOn by runtime.keepScreenOn.collectAsStateWithLifecycle()
    val gameRumble by runtime.gameRumble.collectAsStateWithLifecycle()
    val blackIdle by runtime.blackScreenIdle.collectAsStateWithLifecycle()
    val blackSeconds by runtime.blackScreenSeconds.collectAsStateWithLifecycle()
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val visuals by runtime.touchVisuals.collectAsStateWithLifecycle()
    // Export and import of everything ("Backup"); a workspace alone is exported from its settings.
    val export = rememberExportFile { note(context, it) }
    var importing by remember { mutableStateOf<ProfileExport.Read?>(null) }
    val importFile = rememberImportFile(
        onRead = { r ->
            if (r.kind == ProfileExport.EVERYTHING) importing = r
            else note(context, "This file holds one workspace: add it with + in the workspace row")
        },
        onError = { note(context, it) },
    )
    importing?.let { r ->
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "Every workspace, the Defaults and the device presets are replaced by the file's " +
                        "(${r.profile.workspaces.size} workspaces). What you have now is kept as a backup file in the app. " +
                        "Button and mapping presets from the file are added beside yours.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    importing = null
                    runtime.importEverything(r)
                    note(context, "Imported" + if (r.dropped > 0) " (${r.dropped} mappings this version can't use left out)" else "")
                }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { importing = null }) { Text("Cancel") } },
        )
    }
    val debugOn by runtime.debug.enabled.collectAsStateWithLifecycle()
    val ratchetTimingOn by runtime.ratchetTimingOn.collectAsStateWithLifecycle()
    val tight by runtime.gyroTightening.collectAsStateWithLifecycle()
    val smooth by runtime.gyroSmoothing.collectAsStateWithLifecycle()
    val capRws by runtime.sensCapRws.collectAsStateWithLifecycle()
    val capCounts by runtime.sensCapCounts.collectAsStateWithLifecycle()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }

    val items = listOf(
        category(runtime, "pad", "Screen", "Fullscreen, screen on, black screen, screen rotation, when input is sent", defaultOpen = true) {
            SwitchRow(
                "Start in fullscreen", startFullscreen,
                hint = "The pad opens in fullscreen when the app starts. Press ← and → together, or Back, to leave it.",
            ) { runtime.setStartFullscreen(it) }
            SwitchRow(
                "Keep the screen on while the pad is open", keepScreenOn,
                hint = if (keepScreenOn) "The phone doesn't dim or lock while the pad is on screen. Its pages and other apps lock as usual."
                else "The phone dims and locks after its usual screen timeout, also on the pad.",
            ) { runtime.setKeepScreenOn(it) }
            SwitchRow(
                "Black screen when idle", blackIdle,
                hint = "In play, the pad goes black after a while without a screen touch, to save battery when you only use a " +
                        "controller. Gyro, controllers and the PC connection keep working. Tap the screen to bring the pad back: " +
                        "that tap presses nothing. A controller button mapped to Black screen (Other) blacks it out at once." +
                        if (keepScreenOn) "" else " With the screen not kept on, the phone may lock first.",
            ) { runtime.setBlackScreenIdle(it) }
            if (blackIdle) Searchable("Black screen after", "idle timeout seconds minutes") {
                Column {
                    Text("Black screen after", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GamepadRuntime.BLACK_SCREEN_SECONDS.forEach { s ->
                            FilterChip(
                                selected = blackSeconds == s,
                                onClick = { runtime.setBlackScreenSeconds(s) },
                                label = { Text(if (s < 60) "$s s" else "${s / 60} min") })
                        }
                    }
                }
            }
            Searchable(
                "Screen rotation", "Auto Landscape Portrait rotation lock turn", *ScreenOrientation.entries.map { it.label }.toTypedArray(),
            ) {
                Column {
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
            }
            SwitchRow(
                "Send input only while the pad is open", padOnly,
                hint = if (padOnly) "Controller, keyboard and gyro reach the PC only on the pad, outside its pages and edit mode. The connection stays up."
                else "Controller, keyboard and gyro reach the PC from anywhere, also with the app in the background.",
            ) { runtime.setPadOnlyInput(it) }
            if (padOnly) {
                SwitchRow(
                    "Hardware devices too", padOnlyHardware,
                    hint = if (padOnlyHardware) "Controllers, keyboards and the volume buttons also reach the PC only on the pad."
                    else "Controllers, keyboards and the volume buttons reach the PC with the pad closed too, while GP is on screen " +
                        "(Android gives an app in the background no buttons). A keyboard still types into GP's boxes.",
                ) { runtime.setPadOnlyHardware(it) }
            }
        },
        // Kept under its old id, so its place in the list stays.
        category(runtime, "corners", "Workspace switching", "Previous / next workspace: wrap around") {
            SwitchRow("Wrap around (Next workspace on the last one goes to the first)", profile.wrapWorkspaces) { on ->
                runtime.editActiveProfile { it.copy(wrapWorkspaces = on) }
            }
            Hint("The ← / → buttons are ordinary buttons of every workspace (Make global): move, resize, map or delete them in Edit layout. Add them back from Add preset ★ → Workspace arrows.")
        },
        category(runtime, "touchDisplay", "Touch display", "Touches, trails, mappings on buttons, swipe lines, Move rulers") {
            SwitchRow("Show touches", visuals.showTouches) { runtime.setTouchVisuals(visuals.copy(showTouches = it)) }
            SwitchRow("Trail behind fingers", visuals.trail) { runtime.setTouchVisuals(visuals.copy(trail = it)) }
            if (visuals.trail) {
                MsSlider(
                    "Trail length", visuals.trailMs, TouchVisuals.MIN_TRAIL_MS..TouchVisuals.MAX_TRAIL_MS,
                    hint = "How much of the finger's path (in time) the trail keeps.",
                ) { runtime.setTouchVisuals(visuals.copy(trailMs = it)) }
                var fadePart by remember(visuals.trailFade) { mutableStateOf(visuals.trailFade) }
                Searchable("Trail fade", "How much of the trail, from its end, fades out.") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Trail fade %.0f%%".format(fadePart * 100), Modifier.padding(end = 8.dp))
                            Slider(
                                value = fadePart,
                                onValueChange = { fadePart = (it * 20).roundToInt() / 20f },
                                onValueChangeFinished = { runtime.setTouchVisuals(visuals.copy(trailFade = fadePart)) },
                            )
                        }
                        Hint("How much of the trail, from its end, fades out.")
                    }
                }
            }
            if (visuals.showTouches || visuals.trail) {
                MsSlider(
                    "Fade out after lift", visuals.fadeMs, 0..TouchVisuals.MAX_FADE_MS,
                    hint = "How long a touch takes to disappear once the finger lifts.",
                ) { runtime.setTouchVisuals(visuals.copy(fadeMs = it)) }
            }
            SwitchRow(
                "Show mappings on buttons", visuals.liveMappings,
                hint = "A frame on each button lists what it is sending now, one line each (a tap, double tap or long press action " +
                        "stays a moment), and each Toggle it has with its state: \"Crouch on\" in green, \"Crouch off\" in grey. " +
                        "Slider values join the same frame. It sits in the middle of the button, in place of its label, when everything " +
                        "the button could show fits there; otherwise outside by its top-right corner.",
            ) { runtime.setTouchVisuals(visuals.copy(liveMappings = it)) }
            SwitchRow(
                "Show the swipe line on touchpad buttons", visuals.swipeLine,
                hint = "A finger on a touchpad button draws a line from its start point, through it, to the last activation length " +
                        "of that direction: a bar at Quick swipe after, dots at Hold after and each zone's end, a ring on a dragged " +
                        "start point. The marks it passed fill in.",
            ) { runtime.setTouchVisuals(visuals.copy(swipeLine = it)) }
            SwitchRow(
                "Show Move rulers on touchpad buttons", visuals.moveRulers,
                hint = "Once a finger on a touchpad button moves its Move X or Move Y, it shows a short ruler for each Move, next to " +
                        "the swipe line. The ticks slide as the axis moves, stay dim while a Move is held back, and keep " +
                        "sliding while inertia glides after the lift.",
            ) { runtime.setTouchVisuals(visuals.copy(moveRulers = it)) }
            SwitchRow(
                "Show sliders on buttons", visuals.positionRuler,
                hint = "A button whose Position X, Y, distance or angle drives a stick, trigger or sensitivity fills up to its value " +
                        "(across for X, up for Y, a ring for distance, a sweep for angle), and its top-right corner shows what it sends " +
                        "(\"62 · RT 62 %\"). A Held one shows while your finger is on it; a Toggle one always. Move sliders show only the values.",
            ) { runtime.setTouchVisuals(visuals.copy(positionRuler = it)) }
            if (visuals.positionRuler) {
                var picking by remember { mutableStateOf(false) }
                Searchable("Slider colour", "colour color slider ruler") {
                    Row(
                        Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Slider colour", Modifier.weight(1f))
                        Swatch(Color(visuals.rulerColor.toInt()))
                        if (visuals.rulerColor != TouchVisuals().rulerColor) {
                            TextButton(onClick = { runtime.setTouchVisuals(visuals.copy(rulerColor = TouchVisuals().rulerColor)) }) { Text("Reset") }
                        }
                    }
                }
                if (picking) ColorPickerDialog(
                    "Slider colour", Color(visuals.rulerColor.toInt()),
                    onPick = { c -> runtime.setTouchVisuals(visuals.copy(rulerColor = c.toArgb().toLong() and 0xFFFFFFFFL)); picking = false },
                    onDismiss = { picking = false },
                )
            }
        },
        category(runtime, "gyro", "Gyro", "Jitter cutoff, smoothing and the sensitivity slider cap, for every workspace") {
            // Both default to off: raw gyro. One setting for the whole app, also what the PC uses when it runs the gyro.
            OverrideSlider(
                "appGyroTight", if (tight < 0.05f) "Jitter cutoff off" else "Jitter cutoff %.1f°/s", tight, 0f..5f, 0.1f,
                overridden = false, onReset = null,
                hint = "Turns slower than this move less, which hides hand shake. Every workspace uses it.",
            ) { v -> runtime.setGyroTightening(v) }
            OverrideSlider(
                "appGyroSmooth", if (smooth < 0.05f) "Smoothing off" else "Smoothing below %.1f°/s", smooth, 0f..10f, 0.1f,
                overridden = false, onReset = null,
                hint = "Turns slower than this are averaged over a few samples, which steadies slow aim. Every workspace uses it.",
            ) { v -> runtime.setGyroSmoothing(v) }
            OverrideSlider(
                "appSensCapRws", "Slider cap %.1f RWS", capRws,
                GamepadRuntime.MIN_SENS_CAP_RWS..GamepadRuntime.MAX_SENS_CAP_RWS, 0.5f,
                overridden = false, onReset = null,
                hint = "The top of every gyro sensitivity slider (and Gyro sensitivity ranges) in workspaces that know their " +
                        "game, so the values you use fill the slider. Tap a slider to type a value above it.",
            ) { v -> runtime.setSensCapRws(v) }
            OverrideSlider(
                "appSensCapCounts", "Slider cap %.0f counts/°", capCounts,
                GamepadRuntime.MIN_SENS_CAP_COUNTS..GamepadRuntime.MAX_SENS_CAP_COUNTS, 1f,
                overridden = false, onReset = null,
                hint = "The same in workspaces without a game set, where sensitivity is in mouse counts per degree.",
            ) { v -> runtime.setSensCapCounts(v) }
        },
        category(runtime, "gyroDisplay", "Gyro display", "The parallax edge; other gyro displays are placed in edit mode") {
            GyroVisualsSettings(visuals, runtime::restStillnessDps) { runtime.setTouchVisuals(it) }
        },
        category(runtime, "controllers", "Controllers", "Gamepads attached to the phone") {
            Searchable("Controllers on phone", "gamepad attached", *controllers.map { it.name }.toTypedArray()) {
                Column {
                    if (controllers.isEmpty()) Text("None. Attach any gamepad.")
                    controllers.forEach { c ->
                        Text("${c.name} (${c.known?.name ?: "standard layout"}) " + "%04X:%04X".format(c.vendorId, c.productId))
                    }
                }
            }
        },
        category(runtime, "vibration", "Vibration", "Game rumble from the PC") {
            SwitchRow(
                "Vibrate with game rumble", gameRumble,
                hint = "When a game rumbles the PC's virtual controller (Xbox 360 or DualShock 4 output), the phone vibrates " +
                        "with it, as strong as the stronger motor. Keyboard and mouse output has no rumble. The phone's motor is " +
                        "weaker than a controller's, and long rumble costs battery. The buzz when a Performance test record " +
                        "ends on the PC plays either way.",
            ) { runtime.setGameRumble(it) }
        },
        category(runtime, "debugging", "Debugging", "The debug panel, ratchet timing and recordings") {
            SwitchRow(
                "Debug tools", debugOn,
                hint = "Shows a debug panel on the pad: the link's speed and latency, the gyro, the active workspace and " +
                        "layer, what the PC is sent now, and a list of what you press and what it turns into. " +
                        "Record saves the last 30 seconds (the PC saves its side too over Wi-Fi or USB). " +
                        "Off: nothing is logged.",
            ) { runtime.debug.setEnabled(it) }
            SwitchRow(
                "Ratchet timing", ratchetTimingOn,
                hint = "Measures each ratchet press made during a quick swing and back, shown at the top of the pad " +
                        "(the last 10 and the middle of each). You: ms from the swing's turn-around to your press " +
                        "(+ = after the turn). App: ms from the press reaching the phone to the gyro stopping. " +
                        "Back: how far the camera still moved back after the turn before it stopped (degrees once the " +
                        "game's turn is set in Gyro, else mouse counts). Turning it off clears the list.",
            ) { runtime.setRatchetTiming(it) }
        },
        category(runtime, "backup", "Backup", "Export or import everything as a file") {
            Searchable(
                "Export or import everything", "backup file export import share move to another phone restore",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "One file with every workspace, the Defaults, device presets, the button library and your mapping presets. " +
                            "To share one workspace, use Export in its settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { export(ProfileExport.fileName("GPEverything"), runtime.exportEverything()) }) {
                            Text("Export everything")
                        }
                        OutlinedButton(onClick = importFile) { Text("Import everything…") }
                    }
                }
            }
        },
        category(runtime, "about", "About", "Version and gyro sensor") {
            Searchable("About", "version GyroPhone gyro rate sensor Hz", gyro.source) {
                Column {
                    Text("GyroPhone $version", style = MaterialTheme.typography.bodySmall)
                    Text("Gyro: ${gyro.source}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Gyro rate: ${gyro.rateHz} Hz" + if (gyro.active) " · moving mouse" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint("Long press a tab to move it.")
        ReorderableSections(runtime, "app", items)
    }
}

/** A time setting in steps of 10 ms; [onChange] gets it once the drag ends. */
@Composable
private fun MsSlider(title: String, ms: Int, range: IntRange, hint: String? = null, onChange: (Int) -> Unit) = Searchable(title, hint) {
    var v by remember(ms) { mutableStateOf(ms.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$title %.0f ms".format(v), Modifier.padding(end = 8.dp))
            Slider(
                value = v,
                onValueChange = { v = (it / 10f).roundToInt() * 10f },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                onValueChangeFinished = { onChange(v.roundToInt()) },
            )
        }
        if (hint != null) Hint(hint)
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) = Searchable(title, hint) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = checked, onCheckedChange = onChange)
        }
        if (hint != null) Hint(hint)
    }
}
