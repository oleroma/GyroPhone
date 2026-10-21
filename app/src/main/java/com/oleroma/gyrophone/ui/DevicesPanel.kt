package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime

/**
 * The game pads attached to the active workspace, each with its applied preset: tap a preset chip
 * to switch it, or Edit to open the pad's mapping in the Controller tab. Connected pads not yet
 * attached are listed with an Attach button.
 */
@Composable
internal fun AttachedDevicesPanel(
    runtime: GamepadRuntime,
    onDismiss: () -> Unit,
    onEditPad: (String) -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val workspace = profile.workspace(wsIndex.coerceIn(0, profile.workspaces.lastIndex))
    val unattached = unattachedControllers(workspace, controllers)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Attached devices", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${profile.name} · ${workspace.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(end = 8.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (workspace.pads.isEmpty() && unattached.isEmpty()) {
                        Text(
                            "No game pads in this workspace. Connect a game pad to the phone to attach it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    workspace.pads.forEachIndexed { i, pad ->
                        if (i > 0) HorizontalDivider()
                        PadRow(
                            pad = pad,
                            device = connectedDevice(pad, controllers),
                            userPresets = userPresets,
                            onApply = { preset -> runtime.applyPadPreset(pad.id, preset) },
                            onEdit = { onEditPad(pad.id) },
                        )
                    }
                    if (unattached.isNotEmpty()) {
                        if (workspace.pads.isNotEmpty()) HorizontalDivider()
                        Text("Connected, not attached", style = MaterialTheme.typography.labelLarge)
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
        }
    }
}

@Composable
private fun PadRow(
    pad: AttachedPad,
    device: AttachedController?,
    userPresets: List<PadPreset>,
    onApply: (PadPreset) -> Unit,
    onEdit: () -> Unit,
) {
    val applied = PadPresets.applied(pad, userPresets)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(pad.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        device != null -> "Connected: ${device.name}"
                        pad.match == null -> "Any game pad"
                        else -> "Not connected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Preset: ${PadPresets.label(pad, userPresets)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = onEdit) { Text("Edit") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in PadPresets.all(pad.family, userPresets)) {
                FilterChip(
                    selected = preset.id == applied?.id,
                    onClick = { onApply(preset) },
                    label = { Text(preset.name, maxLines = 1) },
                )
            }
        }
    }
}

/** The connected controller [pad] acts for, if any. */
private fun connectedDevice(pad: AttachedPad, controllers: List<AttachedController>): AttachedController? =
    controllers.firstOrNull { pad.matches(it.vendorId, it.productId, it.name) }
