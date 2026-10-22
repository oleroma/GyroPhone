package com.oleroma.gyrophone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.oleroma.gyrophone.core.model.ControllerDiagram
import com.oleroma.gyrophone.core.model.ControllerDiagrams
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.PhysicalControl
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.diagram
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs

/*
 * Devices and their profiles ([PadPreset]s): the device list of the pad menu, and the profile
 * editor it opens, with the mappings as two columns, button | mapping.
 */

/** One row of the mapping table: a physical input of [control], or the stick preset row of a stick. */
private data class MappingRow(val control: PhysicalControl, val input: BindableInput?)

/**
 * Full settings of a profile of device [deviceKey]: its name and every button of the device with
 * its mapping, as two columns. Works on a draft: Save stores the profile (a builtin or a new one
 * becomes a new saved profile attached to the device; saving an existing one updates every pad that
 * uses it). [presetId] null starts a new profile from pad [loadPadId]'s mappings, else the
 * standard ones; with [loadInto] (a workspace index) the saved profile is loaded into that pad.
 * Pressing a button on the device highlights its row and scrolls to it.
 */
@Composable
internal fun PadProfileEditor(
    runtime: GamepadRuntime,
    deviceKey: String,
    presetId: String?,
    loadInto: Int?,
    loadPadId: String?,
    modifier: Modifier,
    onDone: () -> Unit,
    /** Gets a guard while there are unsaved changes (null otherwise); the menu leaves through it. */
    onLeaveGuard: (((() -> Unit) -> Unit)?) -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val found = menuDevices(profile, controllers, userPresets).firstOrNull { it.key == deviceKey }
    // Keep the device once found: a controller that disconnects mid-edit must not end the editor.
    val lastFound = remember(deviceKey) { arrayOfNulls<PadDevice>(1) }
    if (found != null) lastFound[0] = found
    val padDevice = found ?: lastFound[0]
    if (padDevice == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This device is no longer in the menu.")
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
        return
    }
    // A pad of the device to draw the buttons for (the one to load into, else any, else a stand-in).
    val loadPad = loadInto?.let { i -> profile.workspace(i).pads.firstOrNull { it.id == loadPadId && it.match == padDevice.match } }
    val pad = loadPad
        ?: profile.workspaces.flatMap { it.pads }.firstOrNull { it.match == padDevice.match }
        ?: AttachedPad(id = "", match = padDevice.match, name = padDevice.name, family = padDevice.family)
    val source: PadPreset? = presetId?.let { id -> PadPresets.all(pad.family, userPresets).firstOrNull { it.id == id } }
    val builtin = source != null && PadPresets.isBuiltin(source.id)
    val draftKey = "$deviceKey/${presetId ?: ""}/${loadPadId ?: ""}"
    val startName = when {
        source == null -> "${pad.name} profile"
        builtin -> "${source.name} (custom)"
        else -> source.name
    }
    val startMappings = source?.mappings ?: loadPad?.mappings ?: KnownLayouts.familyMappings(pad.family)
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
        // Pads in all workspaces that use it.
        if (source == null || builtin) 0
        else profile.workspaces.sumOf { w -> w.pads.count { PadPresets.uses(it, source) } }
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
        val trimmed = name.trim().ifEmpty { startName }
        val saved = if (source != null && !builtin) {
            source.copy(name = trimmed, mappings = mappings).also { runtime.updatePadPreset(it) }
        } else {
            runtime.savePadPreset(trimmed, mappings, padDevice)
        }
        if (loadInto != null && loadPad != null) runtime.applyPadPreset(loadPad.id, saved, loadInto)
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
                    source == null -> append("Saving adds a new profile to this device" + (if (loadPad != null) " and loads it into its workspace." else "."))
                    builtin -> append("\"${source.name}\" is built in: saving adds your own copy to this device.")
                    else -> append("Saving updates every workspace pad that uses this profile ($users across your workspaces).")
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
                            val standard = ControllerDiagrams.defaultTargets(input, pad.family)
                            if (standard.toSet() != targets.toSet()) {
                                TextButton(onClick = { mappings = Bindings.setTargets(mappings, input, standard) }) {
                                    Text("Standard", style = MaterialTheme.typography.labelMedium)
                                }
                            }
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
            onDismissRequest = {
                confirmDiscard = false
                pendingLeave = null
            },
            title = { Text("Discard changes?") },
            text = { Text("The changes to \"$startName\" are not saved.") },
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

/**
 * The devices the menu lists: those attached to any workspace of [profile], then connected
 * controllers that none of their specific matches covers, then devices that only saved profiles
 * in [userPresets] name.
 */
internal fun menuDevices(
    profile: Profile,
    controllers: List<AttachedController>,
    userPresets: List<PadPreset>,
): List<PadDevice> {
    val attached = PadDevices.inProfile(profile)
    val extra = controllers
        // An "any game pad" device does not hide a connected model: it can still get its own place.
        .filter { c -> attached.none { d -> d.match?.matches(c.vendorId, c.productId, c.name) == true } }
        .map { deviceOf(it) }
        .distinctBy { it.match }
    // Devices only known from their saved profiles, so those profiles stay reachable.
    return attached + extra + PadDevices.ofProfiles(userPresets, attached + extra)
}

/** [c] as a device: matched by vendor / product id when both are known, else by name. */
internal fun deviceOf(c: AttachedController): PadDevice {
    val match = if (c.vendorId != 0 && c.productId != 0) {
        DeviceMatch(vendorId = c.vendorId, productId = c.productId)
    } else {
        DeviceMatch(nameContains = c.name)
    }
    return PadDevice(match, c.known?.name ?: c.name, c.known?.layout ?: FaceLayout.XBOX)
}

/** Whether a controller matching [device] is connected to the phone. */
internal fun isConnected(device: PadDevice, controllers: List<AttachedController>): Boolean =
    controllers.any { c -> device.match?.matches(c.vendorId, c.productId, c.name) ?: true }

/** The buttons of [pad]: the connected device's own, else its known model's, else its family's. */
private fun padDiagram(pad: AttachedPad, device: AttachedController?): ControllerDiagram = when {
    device != null -> ControllerDiagrams.forDevice(device.known, device.keys, device.axes)
    else -> {
        val known = pad.match?.let { m -> KnownLayouts.controllers.firstOrNull { it.match == m } }
        if (known != null) ControllerDiagrams.forDevice(known) else ControllerDiagrams.of(pad.family.diagram)
    }
}

/** The connected controller [pad] acts for, if any. */
private fun connectedDevice(pad: AttachedPad, controllers: List<AttachedController>): AttachedController? =
    controllers.firstOrNull { pad.matches(it.vendorId, it.productId, it.name) }

internal fun familyTitle(f: FaceLayout): String = when (f) {
    FaceLayout.XBOX -> "Xbox"
    FaceLayout.PLAYSTATION -> "PlayStation"
    FaceLayout.NINTENDO -> "Nintendo"
}
