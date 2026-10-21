package com.oleroma.gyrophone.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.oleroma.gyrophone.core.model.ControllerDiagram
import com.oleroma.gyrophone.core.model.ControllerDiagrams
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.PhysicalControl
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.diagram
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs

/*
 * Devices of the current workspace (pad menu pages): workspace -> attached game pads -> each pad's
 * profile (a [PadPreset]), loaded from all saved profiles or created new. Editing or creating a
 * profile opens its full settings with the mappings as two columns, button | mapping.
 */

/** The game pads attached to the current workspace, plus connected pads that can be attached. */
@Composable
internal fun DevicesPage(runtime: GamepadRuntime, modifier: Modifier, onOpenDevice: (String) -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val workspace = profile.workspace(wsIndex)
    val unattached = unattachedControllers(workspace, controllers)

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Game pads in ${profile.name} · ${workspace.name}. A pad not listed here does nothing in this workspace.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (workspace.pads.isEmpty() && unattached.isEmpty()) {
            Text(
                "No game pads in this workspace. Connect a game pad to the phone to attach it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        workspace.pads.forEachIndexed { i, pad ->
            if (i > 0) HorizontalDivider()
            MenuRow(
                pad.name,
                connectionText(pad, connectedDevice(pad, controllers)) + " · Profile: ${PadPresets.label(pad, userPresets)}",
            ) { onOpenDevice(pad.id) }
        }
        if (unattached.isNotEmpty()) {
            HorizontalDivider()
            Text("Connected, not in this workspace", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            for (c in unattached) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.known?.name ?: c.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        runtime.editActiveProfile { p ->
                            p.updateWorkspace(runtime.workspaceIndex.value) { attachController(it, c) }
                        }
                    }) { Text("Attach") }
                }
            }
        }
    }
}

/**
 * One attached pad: its profile (edit it, create a new one, or load any saved one), its button
 * layout, and Detach. [onEditProfile] gets the profile id to edit, or null for a new profile.
 */
@Composable
internal fun DevicePage(
    runtime: GamepadRuntime,
    padId: String,
    modifier: Modifier,
    onEditProfile: (String?) -> Unit,
    onGone: () -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val workspace = profile.workspace(wsIndex)
    val pad = workspace.pads.firstOrNull { it.id == padId }
    var confirmDetach by remember { mutableStateOf(false) }

    if (pad == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This device is not attached to \"${workspace.name}\".")
            OutlinedButton(onClick = onGone) { Text("Back to devices") }
        }
        return
    }
    val applied = PadPresets.applied(pad, userPresets)

    fun editPad(change: (AttachedPad) -> AttachedPad) = editAttachedPad(runtime, pad.id, change)

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(connectionText(pad, connectedDevice(pad, controllers)), style = MaterialTheme.typography.bodyMedium)
        if (runtime.store.isBuiltin(profile)) {
            Text(
                "\"${profile.name}\" is built in: changes here save it as a custom copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Text("Profile", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                applied?.name ?: "${PadPresets.CUSTOM_NAME} (not saved as a profile)",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (applied != null) {
                Button(onClick = { onEditProfile(applied.id) }) { Text("Edit") }
                OutlinedButton(onClick = { onEditProfile(null) }) { Text("New profile") }
            } else {
                Button(onClick = { onEditProfile(null) }) { Text("Save as profile…") }
            }
        }

        Text("Load a saved profile", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        for (preset in PadPresets.all(pad.family, userPresets)) {
            Row(
                Modifier.fillMaxWidth().clickable { if (preset.id != applied?.id) runtime.applyPadPreset(pad.id, preset) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = preset.id == applied?.id, onClick = null, modifier = Modifier.padding(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (PadPresets.isBuiltin(preset.id)) "Built in · " else "") + "${preset.mappings.size} mappings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onEditProfile(preset.id) }) { Text("Edit") }
            }
        }

        HorizontalDivider()
        Text("Button layout", style = MaterialTheme.typography.titleSmall)
        Text(
            "Which face-button convention the pad has; the standard profile follows it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (family in FaceLayout.entries) {
                FilterChip(
                    selected = family == pad.family,
                    onClick = {
                        if (family == pad.family) return@FilterChip
                        // A pad on the standard profile stays standard: it takes the new layout's standard mapping.
                        val onStandard = applied?.id == PadPresets.STANDARD
                        editPad { p ->
                            val moved = p.copy(family = family)
                            if (onStandard) PadPresets.apply(moved, PadPresets.builtins(family).first { it.id == PadPresets.STANDARD })
                            else moved
                        }
                    },
                    label = { Text(familyTitle(family)) },
                )
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = { confirmDetach = true }) {
            Text("Detach from this workspace", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDetach) {
        AlertDialog(
            onDismissRequest = { confirmDetach = false },
            title = { Text("Detach ${pad.name}?") },
            text = { Text("It stops acting in \"${workspace.name}\". Saved profiles are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDetach = false
                    val id = pad.id
                    runtime.editActiveProfile { p ->
                        p.updateWorkspace(runtime.workspaceIndex.value) { w -> w.copy(pads = w.pads.filterNot { it.id == id }) }
                    }
                    onGone()
                }) { Text("Detach", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDetach = false }) { Text("Cancel") } },
        )
    }
}

/** One row of the mapping table: a physical input of [control], or the stick preset row of a stick. */
private data class MappingRow(val control: PhysicalControl, val input: BindableInput?)

/**
 * Full settings of a pad profile, used by pad [padId] of the current workspace: its name and every
 * button of the pad with its mapping, as two columns. Works on a draft: Save stores the profile
 * (a builtin or a new one becomes a new saved profile; saving an existing one updates every pad
 * that uses it) and loads it into the pad. [presetId] null starts a new profile from the pad's
 * current mappings. Pressing a button on the pad highlights its row and scrolls to it.
 */
@Composable
internal fun PadProfileEditor(
    runtime: GamepadRuntime,
    padId: String,
    presetId: String?,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val pad = profile.workspace(wsIndex).pads.firstOrNull { it.id == padId }
    if (pad == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This device is not attached to the current workspace.")
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
        return
    }
    val source: PadPreset? = presetId?.let { id -> PadPresets.all(pad.family, userPresets).firstOrNull { it.id == id } }
    val builtin = source != null && PadPresets.isBuiltin(source.id)
    val draftKey = "$padId/${presetId ?: ""}"
    val startName = when {
        source == null -> "${pad.name} profile"
        builtin -> "${source.name} (custom)"
        else -> source.name
    }
    val startMappings = source?.mappings ?: pad.mappings
    var name by rememberSaveable(draftKey) { mutableStateOf(startName) }
    var mappings by remember(draftKey) { mutableStateOf(startMappings) }
    val dirty = name != startName || mappings.toSet() != startMappings.toSet()
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // (input, target being replaced or null to add)
    var picking by remember { mutableStateOf<Pair<BindableInput, OutputTarget?>?>(null) }

    val device = connectedDevice(pad, controllers)
    val diagram = remember(pad.match, pad.family, device) { padDiagram(pad, device) }
    val rows = remember(diagram) {
        diagram.controls.flatMap { c ->
            val stickRow = if (c.kind == PhysicalKind.STICK && c.inputs.size >= 6) listOf(MappingRow(c, null)) else emptyList()
            stickRow + c.inputs.map { MappingRow(c, it) }
        }
    }
    val users = remember(source, profile) {
        if (source == null || builtin) 0
        else profile.workspaces.sumOf { w -> w.pads.count { it.presetId == source.id } }
    }

    // Live: rows whose input is pressed (half or more) on a connected pad.
    var pressed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val listState = rememberLazyListState()
    LaunchedEffect(rows) {
        while (true) {
            withFrameMillis { }
            val now = HashSet<Int>()
            rows.forEachIndexed { i, r ->
                val input = r.input ?: return@forEachIndexed
                if (input.sources.maxOf { abs(runtime.sourceValue(it)) } >= 0.5f) now += i
            }
            if (now != pressed) {
                val fresh = now - pressed
                pressed = now
                // Bring a newly pressed row into view, so pressing a button finds its mapping.
                val target = fresh.minOrNull()
                if (target != null && listState.layoutInfo.visibleItemsInfo.none { it.index == target + 1 }) {
                    listState.animateScrollToItem(target + 1)
                }
            }
        }
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }

    fun save() {
        val trimmed = name.trim().ifEmpty { startName }
        val saved = if (source != null && !builtin) {
            source.copy(name = trimmed, mappings = mappings).also { runtime.updatePadPreset(it) }
        } else {
            runtime.savePadPreset(trimmed, mappings)
        }
        runtime.applyPadPreset(pad.id, saved)
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
            buildString {
                append("For ${pad.name} (${diagram.name}). ")
                when {
                    source == null -> append("Saving creates a new profile and loads it into this pad.")
                    builtin -> append("\"${source.name}\" is built in: saving creates your own copy and loads it into this pad.")
                    users > 1 -> append("Saving updates every pad that uses this profile ($users in this game profile, and any in other game profiles).")
                    else -> append("Saving updates every pad that uses this profile and loads it into this pad.")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all to standard") }
            if (source != null && !builtin) {
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete profile", color = MaterialTheme.colorScheme.error) }
            }
            Text(
                "Press a button on the pad to find it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Button", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.38f))
                    Text("Mapping", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.62f))
                }
                HorizontalDivider()
            }
            itemsIndexed(rows, key = { _, r -> r.control.id + "/" + (r.input?.label ?: "preset") }) { i, row ->
                val highlight = i in pressed
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
                            row.control.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val rowInput = row.input
                        val sub = when {
                            rowInput == null -> "Preset"
                            row.control.inputs.size > 1 -> rowInput.label
                            else -> null
                        }
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        Modifier.weight(0.62f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val input = row.input
                        if (input == null) {
                            val current = mappings.toSet()
                            for (preset in Bindings.StickPreset.entries) {
                                FilterChip(
                                    selected = Bindings.applyStickPreset(mappings, row.control.inputs, preset).toSet() == current,
                                    onClick = { mappings = Bindings.applyStickPreset(mappings, row.control.inputs, preset) },
                                    label = { Text(preset.label, maxLines = 1) },
                                )
                            }
                        } else {
                            val targets = Bindings.targets(mappings, input)
                            if (targets.isEmpty()) {
                                Text("Unmapped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            for (t in targets) {
                                TargetChip(
                                    text = Describe.shortTarget(t),
                                    onClick = { picking = input to t },
                                    onRemove = { mappings = Bindings.removeTarget(mappings, input, t) },
                                )
                            }
                            AddChip(onClick = { picking = input to null })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    picking?.let { (input, replacing) ->
        TargetPickerDialog(
            analog = input.analog,
            runtime = runtime,
            current = Bindings.targets(mappings, input),
            onPick = { picked ->
                val current = Bindings.targets(mappings, input)
                val next = if (replacing != null && replacing in current) {
                    current.map { if (it == replacing) picked else it }.distinct()
                } else {
                    (current + picked).distinct()
                }
                mappings = Bindings.setTargets(mappings, input, next)
            },
            onDismiss = { picking = null },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all to standard?") },
            text = { Text("Every button goes back to its standard ${familyTitle(pad.family)} action. Nothing is saved until Save.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    mappings = ControllerDiagrams.resetAll(mappings, diagram, pad.family)
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("The changes to \"$startName\" are not saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (confirmDelete && source != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete profile \"${source.name}\"?") },
            text = { Text("It is removed from the saved profiles. Pads that use it keep their current mapping, shown as ${PadPresets.CUSTOM_NAME}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    runtime.deletePadPreset(source.id)
                    onDone()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Rewrites attached pad [padId] of the active workspace (a builtin profile is saved as a custom copy). */
private fun editAttachedPad(runtime: GamepadRuntime, padId: String, change: (AttachedPad) -> AttachedPad) {
    runtime.editActiveProfile { p ->
        p.updateWorkspace(runtime.workspaceIndex.value) { w -> w.copy(pads = w.pads.map { if (it.id == padId) change(it) else it }) }
    }
}

/** The buttons of [pad]: the connected device's own, else its known model's, else its family's. */
private fun padDiagram(pad: AttachedPad, device: AttachedController?): ControllerDiagram = when {
    device != null -> ControllerDiagrams.forDevice(device.known, device.keys, device.axes)
    else -> {
        val known = pad.match?.let { m -> KnownLayouts.controllers.firstOrNull { it.match == m } }
        if (known != null) ControllerDiagrams.forDevice(known) else ControllerDiagrams.of(pad.family.diagram)
    }
}

private fun connectionText(pad: AttachedPad, device: AttachedController?): String = when {
    device != null -> "Connected: ${device.name}"
    pad.match == null -> "Any game pad"
    else -> "Not connected"
}

/** The connected controller [pad] acts for, if any. */
private fun connectedDevice(pad: AttachedPad, controllers: List<AttachedController>): AttachedController? =
    controllers.firstOrNull { pad.matches(it.vendorId, it.productId, it.name) }

private fun familyTitle(f: FaceLayout): String = when (f) {
    FaceLayout.XBOX -> "Xbox"
    FaceLayout.PLAYSTATION -> "PlayStation"
    FaceLayout.NINTENDO -> "Nintendo"
}
