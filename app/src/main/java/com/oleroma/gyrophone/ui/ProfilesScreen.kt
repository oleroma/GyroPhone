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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GyroAxes
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.runtime.GamepadRuntime
import java.util.UUID

/** Game profile list and profile editor (pad menu page). Builtin profiles are duplicated before editing. */
@Composable
internal fun ProfilesPage(runtime: GamepadRuntime) {
    val profiles by runtime.profiles.collectAsStateWithLifecycle()
    val active by runtime.activeProfile.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf(active.id) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: active
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // One editor instance for both layouts: rotating moves it instead of recreating it, so open
    // dialogs and uncommitted slider values survive.
    val editor = remember(runtime) {
        movableContentOf { p: Profile, mod: Modifier ->
            ProfileEditor(runtime, p, onSelect = { selectedId = it }, modifier = mod)
        }
    }
    val newProfile = {
        val p = BuiltinProfiles.newProfile("user." + UUID.randomUUID(), "New profile")
        runtime.saveProfile(p)
        selectedId = p.id
    }

    if (portrait) {
        // Portrait: the list on top, the editor below it.
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileList(runtime, profiles, active.id, selectedId, { selectedId = it }, newProfile, Modifier.fillMaxWidth().heightIn(max = 220.dp))
            editor(selected, Modifier.weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileList(runtime, profiles, active.id, selectedId, { selectedId = it }, newProfile, Modifier.weight(0.35f))
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
    onNew: () -> Unit,
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
        item {
            OutlinedButton(onClick = onNew, modifier = Modifier.padding(top = 4.dp)) { Text("New profile") }
        }
    }
}

@Composable
private fun ProfileEditor(runtime: GamepadRuntime, profile: Profile, onSelect: (String) -> Unit, modifier: Modifier) {
    val builtin = runtime.store.isBuiltin(profile)
    val activeProfile by runtime.activeProfile.collectAsStateWithLifecycle()
    val workspaceIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val isActive = profile.id == activeProfile.id
    var confirmDelete by remember { mutableStateOf(false) }

    /** Saves [change] of this profile; a builtin becomes a custom copy (active if this one was). */
    fun edit(change: (Profile) -> Profile) {
        if (isActive) {
            onSelect(runtime.editActiveProfile(change).id)
            return
        }
        val base = if (builtin) profile.copy(id = "user." + UUID.randomUUID(), name = profile.name + " (custom)") else profile
        val updated = change(base)
        if (updated == profile) return
        runtime.saveProfile(updated)
        onSelect(updated.id)
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        if (builtin) Text("Built-in: changes are saved as a custom copy.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runtime.activate(profile) }, enabled = !isActive) { Text(if (isActive) "In use" else "Use") }
            OutlinedButton(onClick = {
                val copy = profile.copy(id = "user." + UUID.randomUUID(), name = profile.name + " copy")
                runtime.saveProfile(copy)
                onSelect(copy.id)
            }) { Text("Duplicate") }
            if (!builtin) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }

        if (!builtin) {
            var name by remember(profile.id) { mutableStateOf(profile.name) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { edit { it.copy(name = name.trim()) } },
                    enabled = name.isNotBlank() && name.trim() != profile.name,
                ) { Text("Rename") }
            }
        }

        GamesEditor(profile.games) { games -> edit { it.copy(games = games) } }

        HorizontalDivider()
        Text("Workspaces", style = MaterialTheme.typography.titleSmall)
        Text(
            "Tap ← and → on the pad to switch; both together = fullscreen. Add, rename and reorder them with Edit layout.",
            style = MaterialTheme.typography.bodySmall,
        )
        profile.workspaces.forEachIndexed { i, w ->
            val current = isActive && i == workspaceIndex
            Text(
                (if (current) "● " else "") + "${i + 1}. ${w.name}" + workspaceSummary(w),
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isActive) { runtime.switchWorkspace(i) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        SwitchRow("Wrap around (→ on the last goes to the first)", profile.wrapWorkspaces) { v ->
            edit { it.copy(wrapWorkspaces = v) }
        }
        var cornerOpacity by remember(profile.id) { mutableStateOf(profile.cornerOpacity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("← → opacity %.0f%%".format(cornerOpacity * 100f), Modifier.padding(end = 8.dp))
            Slider(value = cornerOpacity, onValueChange = { cornerOpacity = it }, valueRange = Profile.MIN_CONTROL_OPACITY..1f,
                onValueChangeFinished = { edit { it.copy(cornerOpacity = cornerOpacity) } })
        }
        SwitchRow("← and → resize together", profile.cornerSameSize) { v -> edit { it.copy(cornerSameSize = v) } }

        HorizontalDivider()
        Text(
            if (isActive) "Gyro, touchpad and devices are set per workspace: Menu › Workspace settings and Menu › Devices."
            else "Gyro, touchpad and devices are set per workspace. Use this profile to edit them from the menu.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${profile.name}\"?") },
            text = { Text("Its workspaces, layouts and mappings are removed from the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    runtime.deleteProfile(profile)
                    onSelect(runtime.activeProfile.value.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** "  ·  2 pads · gyro always" style hints for the workspace list. */
private fun workspaceSummary(w: Workspace): String {
    val parts = buildList {
        if (w.pads.isNotEmpty()) add(if (w.pads.size == 1) w.pads[0].name else "${w.pads.size} pads")
        when (w.gyro.mode) {
            GyroMode.OFF -> {}
            GyroMode.ALWAYS -> add("gyro always")
            GyroMode.HOLD_OR_TOGGLE -> add("gyro on hold")
        }
    }
    return if (parts.isEmpty()) "" else "  ·  " + parts.joinToString(" · ")
}

/** Windows executables the companion switches to this profile for. */
@Composable
private fun GamesEditor(games: List<String>, onChange: (List<String>) -> Unit) {
    var entry by remember { mutableStateOf("") }
    Text("Games", style = MaterialTheme.typography.titleSmall)
    if (games.isEmpty()) {
        Text("No games: the PC companion never switches to this profile by itself.", style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in games) {
            FilterChip(selected = true, onClick = { onChange(games - g) }, label = { Text("$g  ✕") })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            singleLine = true,
            placeholder = { Text("game.exe") },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val g = entry.trim()
                if (g.isNotEmpty() && games.none { it.equals(g, ignoreCase = true) }) onChange(games + g)
                entry = ""
            },
            enabled = entry.isNotBlank(),
        ) { Text("Add") }
    }
}

/**
 * Gyro and touchpad settings of the active profile's current workspace ([index]). Edits go
 * through [GamepadRuntime.editActiveProfile], so a builtin becomes an active custom copy.
 */
@Composable
internal fun WorkspaceSettings(runtime: GamepadRuntime, profile: Profile, index: Int) {
    val ws = profile.workspace(index)
    fun editWs(change: (Workspace) -> Workspace) {
        runtime.editActiveProfile { it.updateWorkspace(runtime.workspaceIndex.value, change) }
    }
    // Slider drafts restart when the profile or workspace changes.
    val key = profile.id + "/" + ws.id

    Text("Workspace \"${ws.name}\"", style = MaterialTheme.typography.titleSmall)
    Text("Gyro to mouse", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(GyroMode.OFF to "Off", GyroMode.ALWAYS to "Always", GyroMode.HOLD_OR_TOGGLE to "Hold / toggle button")
            .forEach { (mode, label) ->
                FilterChip(selected = ws.gyro.mode == mode, onClick = {
                    editWs { it.copy(gyro = it.gyro.copy(mode = mode)) }
                }, label = { Text(label) })
            }
    }
    var sens by remember(key) { mutableStateOf(ws.gyro.sensitivity) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sensitivity %.0f".format(sens), Modifier.padding(end = 8.dp))
        Slider(value = sens, onValueChange = { sens = it }, valueRange = 2f..60f,
            onValueChangeFinished = { editWs { it.copy(gyro = it.gyro.copy(sensitivity = sens)) } })
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
        if (axesPortrait && ws.gyro.portrait != null) {
            TextButton(onClick = { editWs { it.copy(gyro = it.gyro.copy(portrait = null)) } }) { Text("Same as landscape") }
        }
    }
    if (axesPortrait && ws.gyro.portrait == null) {
        Text("Portrait uses the landscape axes until you change one here.", style = MaterialTheme.typography.bodySmall)
    }
    AxisRow("Mouse left/right", axes.mouseX, axes.invertX,
        onAxis = { a -> editAxes { it.copy(mouseX = a) } },
        onInvert = { inv -> editAxes { it.copy(invertX = inv) } })
    AxisRow("Mouse up/down", axes.mouseY, axes.invertY,
        onAxis = { a -> editAxes { it.copy(mouseY = a) } },
        onInvert = { inv -> editAxes { it.copy(invertY = inv) } })

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
