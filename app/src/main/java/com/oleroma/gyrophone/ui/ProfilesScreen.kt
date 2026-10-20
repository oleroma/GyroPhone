package com.oleroma.gyrophone.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.runtime.GamepadRuntime
import java.util.UUID

/** Profile list and mapping editor. Builtin profiles are duplicated before editing. */
@Composable
fun ProfilesScreen(runtime: GamepadRuntime) {
    val profiles by runtime.profiles.collectAsStateWithLifecycle()
    val active by runtime.activeProfile.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf(active.id) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: active
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // One editor instance for both layouts: rotating moves it instead of recreating it, so open
    // dialogs, a learn in progress and uncommitted slider values survive.
    val editor = remember(runtime) {
        movableContentOf { p: Profile, mod: Modifier ->
            ProfileEditor(runtime, p, onSelect = { selectedId = it }, modifier = mod)
        }
    }

    if (portrait) {
        // Portrait: the list on top, the editor below it.
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileList(runtime, profiles, active.id, selectedId, { selectedId = it }, Modifier.fillMaxWidth().heightIn(max = 180.dp))
            editor(selected, Modifier.weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileList(runtime, profiles, active.id, selectedId, { selectedId = it }, Modifier.weight(0.35f))
            editor(selected, Modifier.weight(0.65f))
        }
    }
}

@Composable
private fun ProfileList(
    runtime: GamepadRuntime,
    profiles: List<Profile>,
    activeId: String,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(profiles, key = { it.id }) { p ->
            val marker = if (p.id == activeId) "● " else ""
            val suffix = if (runtime.store.isBuiltin(p)) "  (built-in)" else ""
            Text(
                marker + p.name + suffix,
                Modifier.fillMaxWidth().clickable { onSelect(p.id) }.padding(8.dp),
                color = if (p.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProfileEditor(runtime: GamepadRuntime, profile: Profile, onSelect: (String) -> Unit, modifier: Modifier) {
    val builtin = runtime.store.isBuiltin(profile)
    var learning by remember { mutableStateOf(false) }
    var pendingSource by remember { mutableStateOf<InputSource?>(null) }
    var pickTouch by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { runtime.learnListener = null } }

    fun edit(change: (Profile) -> Profile) {
        val base = if (builtin) profile.copy(id = "user." + UUID.randomUUID(), name = profile.name + " (custom)") else profile
        val updated = change(base)
        runtime.saveProfile(updated)
        onSelect(updated.id)
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        if (builtin) Text("Built-in: changes are saved as a custom copy.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runtime.activate(profile) }) { Text("Use") }
            OutlinedButton(onClick = { edit { it } }) { Text("Duplicate") }
            if (!builtin) OutlinedButton(onClick = { runtime.deleteProfile(profile) }) { Text("Delete") }
        }

        Text("Gyro to mouse", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(GyroMode.OFF to "Off", GyroMode.ALWAYS to "Always", GyroMode.HOLD_OR_TOGGLE to "Hold / toggle button")
                .forEach { (mode, label) ->
                    FilterChip(selected = profile.gyro.mode == mode, onClick = {
                        edit { it.copy(gyro = it.gyro.copy(mode = mode)) }
                    }, label = { Text(label) })
                }
        }
        var sens by remember(profile.id) { mutableStateOf(profile.gyro.sensitivity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sensitivity %.0f".format(sens), Modifier.padding(end = 8.dp))
            Slider(value = sens, onValueChange = { sens = it }, valueRange = 2f..60f,
                onValueChangeFinished = { edit { it.copy(gyro = it.gyro.copy(sensitivity = sens)) } })
        }
        Text("Held as", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GyroOrientation.entries.forEach { o ->
                FilterChip(selected = profile.gyro.orientation == o,
                    onClick = { edit { it.copy(gyro = it.gyro.copy(orientation = o)) } },
                    label = { Text(o.label) })
            }
        }
        AxisRow("Mouse left/right", profile.gyro.mouseX, profile.gyro.invertX,
            onAxis = { a -> edit { it.copy(gyro = it.gyro.copy(mouseX = a)) } },
            onInvert = { inv -> edit { it.copy(gyro = it.gyro.copy(invertX = inv)) } })
        AxisRow("Mouse up/down", profile.gyro.mouseY, profile.gyro.invertY,
            onAxis = { a -> edit { it.copy(gyro = it.gyro.copy(mouseY = a)) } },
            onInvert = { inv -> edit { it.copy(gyro = it.gyro.copy(invertY = inv)) } })

        HorizontalDivider()
        Text("Touchpad (empty pad area)", style = MaterialTheme.typography.titleSmall)
        SwitchRow("Enabled", profile.touchpad.enabled) { v -> edit { it.copy(touchpad = it.touchpad.copy(enabled = v)) } }
        SwitchRow("Tap to click", profile.touchpad.tapToClick) { v -> edit { it.copy(touchpad = it.touchpad.copy(tapToClick = v)) } }
        SwitchRow("Tap and drag", profile.touchpad.tapDrag) { v -> edit { it.copy(touchpad = it.touchpad.copy(tapDrag = v)) } }
        SwitchRow("Two-finger scroll", profile.touchpad.twoFingerScroll) { v ->
            edit { it.copy(touchpad = it.touchpad.copy(twoFingerScroll = v)) }
        }
        SwitchRow("Natural scrolling", profile.touchpad.naturalScroll) { v ->
            edit { it.copy(touchpad = it.touchpad.copy(naturalScroll = v)) }
        }
        var tpSens by remember(profile.id) { mutableStateOf(profile.touchpad.sensitivity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed %.1f".format(tpSens), Modifier.padding(end = 8.dp))
            Slider(value = tpSens, onValueChange = { tpSens = it }, valueRange = 0.3f..5f,
                onValueChangeFinished = { edit { it.copy(touchpad = it.touchpad.copy(sensitivity = tpSens)) } })
        }
        var tpAccel by remember(profile.id) { mutableStateOf(profile.touchpad.acceleration) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Acceleration %.1f".format(tpAccel), Modifier.padding(end = 8.dp))
            Slider(value = tpAccel, onValueChange = { tpAccel = it }, valueRange = 0f..2f,
                onValueChangeFinished = { edit { it.copy(touchpad = it.touchpad.copy(acceleration = tpAccel)) } })
        }
        var tpScroll by remember(profile.id) { mutableStateOf(profile.touchpad.scrollSpeed) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scroll speed %.1f".format(tpScroll), Modifier.padding(end = 8.dp))
            Slider(value = tpScroll, onValueChange = { tpScroll = it }, valueRange = 0.25f..3f,
                onValueChangeFinished = { edit { it.copy(touchpad = it.touchpad.copy(scrollSpeed = tpScroll)) } })
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mappings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                learning = true
                runtime.learnListener = { src -> learning = false; pendingSource = src }
            }) { Text(if (learning) "Press a control…" else "Add from controller") }
            OutlinedButton(onClick = { pickTouch = true }) { Text("Add from touch") }
        }
        LazyColumn(Modifier.heightIn(max = 600.dp)) {
            itemsIndexed(profile.mappings) { index, m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Describe.source(m.source)}  →  ${Describe.target(m.target)}", Modifier.weight(1f))
                    TextButton(onClick = {
                        edit { it.copy(mappings = it.mappings.filterIndexed { i, _ -> i != index }) }
                    }) { Text("Remove") }
                }
            }
        }
    }

    if (pickTouch) {
        AlertDialog(
            onDismissRequest = { pickTouch = false },
            confirmButton = { TextButton(onClick = { pickTouch = false }) { Text("Cancel") } },
            title = { Text("Touch control") },
            text = {
                LazyColumn {
                    items(profile.touchControls) { c ->
                        Text(c.label + " (" + c.id + ")", Modifier.fillMaxWidth().clickable {
                            pickTouch = false
                            pendingSource = if (c.kind == TouchKind.STICK) {
                                InputSource.TouchStick(c.id, com.oleroma.gyrophone.core.model.StickAxis.X)
                            } else InputSource.Touch(c.id)
                        }.padding(10.dp))
                    }
                }
            },
        )
    }

    pendingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingSource = null },
            confirmButton = { TextButton(onClick = { pendingSource = null }) { Text("Cancel") } },
            title = { Text("Map ${Describe.source(source)} to") },
            text = {
                LazyColumn {
                    items(Describe.allTargets) { target ->
                        Text(Describe.target(target), Modifier.fillMaxWidth().clickable {
                            pendingSource = null
                            edit { it.copy(mappings = it.mappings + Mapping(source, target)) }
                        }.padding(10.dp))
                    }
                }
            },
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

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
