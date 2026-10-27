package com.oleroma.gyrophone.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ControllerDiagrams
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs

/*
 * The mapping profile editor: every remap of a profile in four categories (touch buttons,
 * touchpad, keyboard, controller), each as two columns, button | mapping.
 */

private enum class Category(val title: String) {
    TOUCH("Touch buttons"), TOUCHPAD("Touchpad"), KEYBOARD("Keyboard"), CONTROLLER("Controller"),
}

/** One line of the editor's table. */
private sealed interface EditorRow {
    val key: String

    data class Section(val category: Category, val detail: String) : EditorRow {
        override val key get() = "section/$category"
    }

    /** The stick preset chips of a touch or controller stick ([inputs]: X, Y, Left, Right, Up, Down). */
    data class Stick(val category: Category, val id: String, val label: String, val inputs: List<BindableInput>) :
        EditorRow {
        override val key get() = "stick/$category/$id"
    }

    data class Input(
        val category: Category,
        val id: String,
        val label: String,
        val sub: String?,
        val input: BindableInput
    ) : EditorRow {
        override val key get() = "input/$category/$id/${input.label}"
    }

    data class Note(val category: Category, val text: String) : EditorRow {
        override val key get() = "note/$category"
    }

    data object AddKey : EditorRow {
        override val key get() = "addKey"
    }

    data object PadPresetRow : EditorRow {
        override val key get() = "padPresets"
    }

    data object TouchpadReset : EditorRow {
        override val key get() = "touchpadReset"
    }
}

/** Which key row's gesture rows a toggle opens. */
private fun gestureKey(category: Category, id: String, input: BindableInput) = "$category/$id/${input.label}"

/**
 * Full settings of mapping profile [profileId] (null = a new profile, starting as a copy of the
 * current workspace's): its name, then its touch, keyboard and controller mappings. Works on a
 * draft: Save stores it (every workspace using it follows); a new profile is used by the current
 * workspace. Pressing a key or button highlights its row and scrolls to it.
 */
@Composable
internal fun MappingProfileEditor(
    runtime: GamepadRuntime,
    profileId: String?,
    modifier: Modifier,
    onDone: () -> Unit,
    /** Gets a guard while there are unsaved changes (null otherwise); the menu leaves through it. */
    onLeaveGuard: (((() -> Unit) -> Unit)?) -> Unit,
) {
    val set by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val profiles by runtime.mappingProfiles.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val ws = set.workspace(wsIndex)
    val currentProfile = MappingProfiles.of(ws, profiles)
    val source = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
    // Keep the start once found, so a profile renamed elsewhere does not reset the draft.
    val start = remember(profileId) {
        source ?: currentProfile?.let { it.copy(id = "", name = it.name + " copy") } ?: MappingProfile("", "Profile")
    }
    if (profileId != null && source == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This profile no longer exists.")
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
        return
    }

    var name by rememberSaveable(profileId) { mutableStateOf(start.name) }
    var touch by remember(profileId) { mutableStateOf(start.touch) }
    var keyboard by remember(profileId) { mutableStateOf(start.keyboard) }
    var controller by remember(profileId) { mutableStateOf(start.controller) }
    val startTouchpad = remember(profileId) { start.touchpad ?: Bindings.defaultTouchpadMappings(ws.touchpad) }
    var touchpad by remember(profileId) { mutableStateOf(startTouchpad) }
    fun get(c: Category): List<Mapping> = when (c) {
        Category.TOUCH -> touch
        Category.TOUCHPAD -> touchpad
        Category.KEYBOARD -> keyboard
        Category.CONTROLLER -> controller
    }

    fun put(c: Category, m: List<Mapping>) {
        when (c) {
            Category.TOUCH -> touch = m
            Category.TOUCHPAD -> touchpad = m
            Category.KEYBOARD -> keyboard = m
            Category.CONTROLLER -> controller = m
        }
    }

    val dirty = name != start.name || touch.toSet() != start.touch.toSet() ||
            keyboard.toSet() != start.keyboard.toSet() || controller.toSet() != start.controller.toSet() ||
            touchpad.toSet() != startTouchpad.toSet()

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var savingPreset by remember { mutableStateOf(false) }
    var capturingKey by remember { mutableStateOf(false) }
    // (category, input, target being replaced or null to add)
    var picking by remember { mutableStateOf<Triple<Category, BindableInput, OutputTarget?>?>(null) }
    val collapsed = remember { mutableStateMapOf<Category, Boolean>() }
    // Keys whose gesture rows (tap, double tap, long press, release) are open, by [gestureKey].
    val openGestures = remember { mutableStateMapOf<String, Boolean>() }

    val users = set.workspaces.filter { source != null && it.mappingProfileId == source.id }
    // Touch buttons of the workspaces that use it (this one first), each once.
    val controls = (listOf(ws) + users.filter { it.id != ws.id })
        .flatMap { w -> w.touchControls.filter { it.kind != TouchKind.FULLSCREEN } }
        .distinctBy { it.id }
    // The controller drawn: this workspace's first pad, else a stand-in of the standard layout.
    val pad = ws.pads.firstOrNull() ?: AttachedPad(id = "", family = FaceLayout.XBOX)
    val device = connectedDevice(pad, controllers)
    val diagram = remember(pad.match, pad.family, device) { padDiagram(pad, device) }

    val rows = buildList {
        // A key's row, then its gesture rows when open or one of them is mapped.
        fun addKey(category: Category, id: String, label: String, sub: String?, input: BindableInput) {
            add(EditorRow.Input(category, id, label, sub, input))
            val gestures = Bindings.gestureInputs(input)
            if (gestures.isEmpty()) return
            val ms = get(category)
            val open = openGestures[gestureKey(category, id, input)] == true ||
                    gestures.any { Bindings.targets(ms, it).isNotEmpty() }
            if (!open) return
            for (g in gestures) {
                add(EditorRow.Input(category, "$id/${input.label}", label, (sub?.let { "$it · " } ?: "") + g.label, g))
            }
        }
        add(EditorRow.Section(Category.TOUCH, "${controls.size} on screen"))
        if (collapsed[Category.TOUCH] != true) {
            if (controls.isEmpty()) add(EditorRow.Note(Category.TOUCH, "No touch buttons in this workspace's layout."))
            for (c in controls) {
                val label = c.label.ifBlank { c.id }
                val inputs = Bindings.touchInputs(c)
                if (c.kind == TouchKind.STICK) add(EditorRow.Stick(Category.TOUCH, c.id, label, inputs))
                for (i in inputs) add(
                    EditorRow.Input(
                        Category.TOUCH,
                        c.id,
                        label,
                        if (inputs.size > 1) i.label else null,
                        i
                    )
                )
            }
        }
        add(EditorRow.Section(Category.TOUCHPAD, "4 finger counts"))
        if (collapsed[Category.TOUCHPAD] != true) {
            for (n in 1..4) {
                for (input in Bindings.surfaceInputs(n)) {
                    add(
                        EditorRow.Input(
                            Category.TOUCHPAD,
                            "tp$n",
                            if (n == 1) "1 finger" else "$n fingers",
                            input.label,
                            input
                        )
                    )
                }
            }
            add(EditorRow.TouchpadReset)
        }
        val keys = keyboard.mapNotNull { (it.source as? InputSource.Key)?.keyCode }.distinct()
        add(EditorRow.Section(Category.KEYBOARD, "${keys.size} keys"))
        if (collapsed[Category.KEYBOARD] != true) {
            for (code in keys) {
                val input = BindableInput(keyName(code), listOf(InputSource.Key(code)))
                addKey(Category.KEYBOARD, "key$code", input.label, null, input)
            }
            add(EditorRow.AddKey)
        }
        add(EditorRow.Section(Category.CONTROLLER, diagram.name))
        if (collapsed[Category.CONTROLLER] != true) {
            add(EditorRow.PadPresetRow)
            for (c in diagram.controls) {
                if (c.kind == PhysicalKind.STICK && c.inputs.size >= 6) add(
                    EditorRow.Stick(
                        Category.CONTROLLER,
                        c.id,
                        c.label,
                        c.inputs
                    )
                )
                for (i in c.inputs) addKey(
                    Category.CONTROLLER,
                    c.id,
                    c.label,
                    if (c.inputs.size > 1) i.label else null,
                    i
                )
            }
        }
    }

    // Live: keyboard and controller rows whose input is pressed (half or more).
    var pressed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            val now = HashSet<String>()
            for (r in latestRows) {
                if (r !is EditorRow.Input || r.category == Category.TOUCH || r.category == Category.TOUCHPAD) continue
                if (r.input.sources.maxOf { abs(runtime.sourceValue(it)) } >= 0.5f) now += r.key
            }
            if (now != pressed) {
                val fresh = now - pressed
                pressed = now
                // Bring a newly pressed row into view, so pressing a button finds its mapping.
                val target = latestRows.indexOfFirst { it.key in fresh }
                if (target >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                    listState.animateScrollToItem(target)
                }
            }
        }
    }

    /** Where to go once changes are discarded; null = [onDone]. */
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    val guard by rememberUpdatedState<(() -> Unit) -> Unit>({ action ->
        pendingLeave = action
        confirmDiscard = true
    })
    DisposableEffect(dirty) {
        onLeaveGuard(if (dirty) { action -> guard(action) } else null)
        onDispose { onLeaveGuard(null) }
    }

    fun save() {
        val trimmed = name.trim().ifEmpty { start.name }
        if (source != null) {
            runtime.updateMappingProfile(
                source.copy(
                    name = trimmed,
                    touch = touch,
                    keyboard = keyboard,
                    controller = controller,
                    touchpad = touchpad
                ),
            )
        } else {
            runtime.newMappingProfile(
                trimmed,
                MappingProfile("", trimmed, touch, keyboard, controller, touchpad),
                wsIndex
            )
        }
        onDone()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Profile name") },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { if (dirty) confirmDiscard = true else onDone() }) { Text("Cancel") }
            Button(onClick = { save() }, enabled = name.isNotBlank()) { Text("Save") }
        }
        Text(
            when {
                source == null -> "Saving adds this profile and uses it in \"${ws.name}\"."
                users.size > 1 -> "Used by ${users.size} workspaces: ${users.joinToString { it.name }}. Saving changes all of them."
                users.size == 1 -> "Used by \"${users[0].name}\"."
                else -> "No workspace uses it yet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (source != null && profiles.size > 1) {
            TextButton(onClick = { confirmDelete = true }) {
                Text(
                    "Delete profile",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            itemsIndexed(rows, key = { _, r -> r.key }) { _, row ->
                when (row) {
                    is EditorRow.Section -> {
                        val open = collapsed[row.category] != true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { collapsed[row.category] = open }
                                .padding(top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (open) "▾" else "▸",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                row.category.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                row.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (open) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    when (row.category) {
                                        Category.KEYBOARD -> "Key"
                                        Category.TOUCHPAD -> "Gesture"
                                        else -> "Button"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(0.38f),
                                )
                                Text(
                                    "Mapping",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(0.62f)
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    is EditorRow.Note -> Hint(row.text)
                    is EditorRow.TouchpadReset -> {
                        TextButton(onClick = { touchpad = Bindings.defaultTouchpadMappings(TouchpadSettings()) }) {
                            Text("Reset touchpad to standard")
                        }
                        HorizontalDivider()
                    }

                    is EditorRow.AddKey -> {
                        if (capturingKey) {
                            KeyLearn(runtime, onKey = { code ->
                                capturingKey = false
                                picking = Triple(
                                    Category.KEYBOARD,
                                    BindableInput(keyName(code), listOf(InputSource.Key(code))),
                                    null
                                )
                            }, onCancel = { capturingKey = false })
                        } else {
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { capturingKey = true }) { Text("+ Add key") }
                                Text(
                                    "  Keys of a USB or Bluetooth keyboard on the phone, in every workspace.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    is EditorRow.PadPresetRow -> {
                        Text(
                            "One set for every game pad of the workspaces that use this profile. Load a preset:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val now = controller.toSet()
                            for (preset in PadPresets.all(pad.family, userPresets)) {
                                FilterChip(
                                    selected = preset.mappings.toSet() == now,
                                    onClick = { controller = preset.mappings },
                                    label = { Text(preset.name, maxLines = 1) },
                                )
                            }
                            TextButton(onClick = { savingPreset = true }) { Text("Save as preset…") }
                            TextButton(onClick = { confirmReset = true }) { Text("Reset to standard") }
                        }
                        HorizontalDivider()
                    }

                    is EditorRow.Stick -> {
                        MappingLine(row.label, "Preset", highlight = false) {
                            val ms = get(row.category)
                            val current = ms.toSet()
                            for (preset in Bindings.StickPreset.entries) {
                                FilterChip(
                                    selected = Bindings.applyStickPreset(ms, row.inputs, preset).toSet() == current,
                                    onClick = { put(row.category, Bindings.applyStickPreset(ms, row.inputs, preset)) },
                                    label = { Text(preset.label, maxLines = 1) },
                                )
                            }
                        }
                    }

                    is EditorRow.Input -> {
                        MappingLine(row.label, row.sub, highlight = row.key in pressed) {
                            val ms = get(row.category)
                            val input = row.input
                            val targets = Bindings.targets(ms, input)
                            if (targets.isEmpty()) {
                                Text(
                                    "Unmapped",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            for (t in targets) {
                                TargetChip(
                                    text = Describe.shortTarget(t),
                                    onClick = { picking = Triple(row.category, input, t) },
                                    onRemove = { put(row.category, Bindings.removeTarget(ms, input, t)) },
                                )
                            }
                            AddChip(onClick = { picking = Triple(row.category, input, null) })
                            val gestures = Bindings.gestureInputs(input)
                            if (gestures.isNotEmpty()) {
                                val gk = gestureKey(row.category, row.id, input)
                                val mapped = gestures.any { Bindings.targets(ms, it).isNotEmpty() }
                                if (!mapped) {
                                    val open = openGestures[gk] == true
                                    TextButton(onClick = { openGestures[gk] = !open }) {
                                        Text(
                                            if (open) "Hide gestures" else "Gestures",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                            if (row.category == Category.CONTROLLER && input.sources.first() !is InputSource.KeyGesture) {
                                val standard = ControllerDiagrams.defaultTargets(input, pad.family)
                                if (standard.toSet() != targets.toSet()) {
                                    TextButton(onClick = {
                                        put(
                                            row.category,
                                            Bindings.setTargets(ms, input, standard)
                                        )
                                    }) {
                                        Text("Standard", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { (category, input, replacing) ->
        TargetPickerDialog(
            analog = input.analog,
            swipe = input.swipe,
            extra = input.extra,
            runtime = runtime,
            current = Bindings.targets(get(category), input),
            onPick = { picked ->
                val ms = get(category)
                val current = Bindings.targets(ms, input)
                val next = if (replacing != null && replacing in current) {
                    current.map { if (it == replacing) picked else it }.distinct()
                } else {
                    (current + picked).distinct()
                }
                put(category, Bindings.setTargets(ms, input, next))
            },
            onDismiss = { picking = null },
        )
    }

    if (savingPreset) {
        var presetName by remember { mutableStateOf("${name.trim()} controller") }
        AlertDialog(
            onDismissRequest = { savingPreset = false },
            title = { Text("Save controller preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    singleLine = true,
                    label = { Text("Preset name") })
            },
            confirmButton = {
                TextButton(enabled = presetName.isNotBlank(), onClick = {
                    savingPreset = false
                    runtime.savePadPreset(presetName, controller)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { savingPreset = false }) { Text("Cancel") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset controller to standard?") },
            text = { Text("Every controller button goes back to its standard ${familyTitle(pad.family)} action. Nothing is saved until Save.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    controller = ControllerDiagrams.resetAll(controller, diagram, pad.family)
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = {
                confirmDiscard = false
                pendingLeave = null
            },
            title = { Text("Discard changes?") },
            text = { Text("The changes to \"${start.name}\" are not saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    val leave = pendingLeave ?: onDone
                    pendingLeave = null
                    leave()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    pendingLeave = null
                }) { Text("Keep editing") }
            },
        )
    }

    if (confirmDelete && source != null) {
        val fallback = profiles.firstOrNull { it.id != source.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete profile \"${source.name}\"?") },
            text = {
                Text(
                    if (users.isEmpty()) "It is removed from the saved profiles."
                    else "Workspaces that use it (${users.joinToString { it.name }}) switch to \"${fallback?.name}\".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    runtime.deleteMappingProfile(source.id)
                    onDone()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** One row of the table: [label] (and [sub]) in the left column, [mapping] scrolling in the right. */
@Composable
private fun MappingLine(label: String, sub: String?, highlight: Boolean, mapping: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.38f).padding(end = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            Modifier.weight(0.62f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { mapping() }
    }
    HorizontalDivider()
}

/** Waits for one key of a physical keyboard (through runtime.keyLearnListener) and gives its Android keycode. */
@Composable
private fun KeyLearn(runtime: GamepadRuntime, onKey: (Int) -> Unit, onCancel: () -> Unit) {
    val latest by rememberUpdatedState(onKey)
    DisposableEffect(Unit) {
        val listener: (Int) -> Unit = { latest(it) }
        runtime.keyLearnListener = listener
        onDispose { if (runtime.keyLearnListener === listener) runtime.keyLearnListener = null }
    }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Press a key on the keyboard…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** "A", "Space", "F1": the Android name of a key without its KEYCODE_ prefix. */
private fun keyName(code: Int): String =
    KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").split('_')
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
