package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceEdits
import com.oleroma.gyrophone.runtime.AttachedController

/**
 * Edit-mode bar of the profile's workspaces: a chip per workspace (tap = show it), and rename,
 * move left / right, duplicate, delete (not the last), add (blank or a preset) and settings for
 * the shown one. [onProfileChange] gets the edited profile and the index to show next.
 */
@Composable
internal fun WorkspaceBar(
    profile: Profile,
    index: Int,
    onSelect: (Int) -> Unit,
    onProfileChange: (Profile, Int) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val i = index.coerceIn(0, profile.workspaces.lastIndex)
    val current = profile.workspace(i)
    var renaming by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        profile.workspaces.forEachIndexed { k, w ->
            WorkspaceChip("${k + 1} · ${w.name}", k == i) { onSelect(k) }
        }
        FilledTonalButton(contentPadding = compact, onClick = { adding = true }) { Text("+ Workspace") }
        OutlinedButton(contentPadding = compact, onClick = onSettings) { Text("Settings") }
        OutlinedButton(contentPadding = compact, onClick = { renaming = current.name }) { Text("Rename") }
        if (i > 0) {
            OutlinedButton(contentPadding = compact, onClick = {
                onProfileChange(WorkspaceEdits.move(profile, i, i - 1), WorkspaceEdits.indexAfterMove(i, i, i - 1))
            }) { Text("◀ Move") }
        }
        if (i < profile.workspaces.lastIndex) {
            OutlinedButton(contentPadding = compact, onClick = {
                onProfileChange(WorkspaceEdits.move(profile, i, i + 1), WorkspaceEdits.indexAfterMove(i, i, i + 1))
            }) { Text("Move ▶") }
        }
        OutlinedButton(contentPadding = compact, onClick = {
            val (p, at) = WorkspaceEdits.duplicate(profile, i)
            onProfileChange(p, at)
        }) { Text("Duplicate") }
        if (profile.workspaces.size > 1) {
            TextButton(contentPadding = compact, onClick = { deleting = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    renaming?.let { name ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename workspace") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { renaming = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onProfileChange(WorkspaceEdits.rename(profile, i, trimmed), i)
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add workspace") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val choices = listOf(
                        "Blank (touchpad only)" to BuiltinProfiles.blankWorkspace("Workspace ${profile.workspaces.size + 1}"),
                    ) + BuiltinProfiles.presets.map { it.name to it }
                    for ((label, w) in choices) {
                        Text(
                            label,
                            Modifier.fillMaxWidth().clickable {
                                adding = false
                                val (p, at) = WorkspaceEdits.add(profile, w)
                                onProfileChange(p, at)
                            }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${current.name}\"?") },
            text = { Text("Its controls, settings and attached pads go with it. Cancel in the edit bar still undoes it.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    val p = WorkspaceEdits.delete(profile, i)
                    onProfileChange(p, i.coerceAtMost(p.workspaces.lastIndex))
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** "Attach <name>" buttons for connected controllers that do nothing in the shown workspace. */
@Composable
internal fun AttachHints(
    unattached: List<AttachedController>,
    onAttach: (AttachedController) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (unattached.isEmpty()) return
    val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Not in this workspace:",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge,
        )
        for (c in unattached) {
            FilledTonalButton(contentPadding = compact, onClick = { onAttach(c) }) {
                Text("Attach ${displayName(c)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Connected controllers that no attached pad of [workspace] matches (one per model). */
internal fun unattachedControllers(workspace: Workspace, controllers: List<AttachedController>): List<AttachedController> =
    controllers
        .filter { workspace.padFor(it.vendorId, it.productId, it.name) == null }
        .distinctBy { Triple(it.vendorId, it.productId, it.name) }

/**
 * [workspace] with [controller]'s model attached: matched by vendor / product id when both are
 * known, else by name; the family and starting mappings come from the known layout (Xbox if unknown).
 */
internal fun attachController(workspace: Workspace, controller: AttachedController): Workspace {
    val c = controller
    val match = if (c.vendorId != 0 && c.productId != 0) {
        DeviceMatch(vendorId = c.vendorId, productId = c.productId)
    } else {
        DeviceMatch(nameContains = c.name)
    }
    val family = c.known?.layout ?: FaceLayout.XBOX
    val used = workspace.pads.mapTo(HashSet()) { it.id }
    var n = 1
    while ("pad$n" in used) n++
    val pad = AttachedPad(
        id = "pad$n",
        match = match,
        name = displayName(c),
        family = family,
        mappings = KnownLayouts.familyMappings(family),
    )
    return workspace.copy(pads = workspace.pads + pad)
}

private fun displayName(c: AttachedController): String = c.known?.name ?: c.name

@Composable
private fun WorkspaceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) colors.primaryContainer else Color(0xCC1E232B), shape)
            .border(1.dp, if (selected) colors.primaryContainer else colors.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimaryContainer else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
