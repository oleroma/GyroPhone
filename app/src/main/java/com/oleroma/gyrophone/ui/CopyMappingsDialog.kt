package com.oleroma.gyrophone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.runtime.GamepadRuntime

/**
 * "Copy mappings from…": brings another workspace's mappings into workspace [wsIndex] and saves
 * it ([WorkspaceMappings.copy]). Buttons and mappings: its buttons come too, replacing this
 * workspace's. Mappings only: this workspace keeps its buttons, matched by name; the ones with no
 * match are listed before copying, since they keep their own mappings. [discards]: the caller has
 * unsaved edits the copy replaces.
 */
@Composable
internal fun CopyMappingsDialog(
    runtime: GamepadRuntime,
    wsIndex: Int,
    discards: Boolean,
    onDismiss: () -> Unit,
    onCopied: () -> Unit,
) {
    val set by runtime.activeProfile.collectAsStateWithLifecycle()
    val target = set.workspace(wsIndex)
    val others = set.workspaces.filter { it.id != target.id }
    var fromId by remember { mutableStateOf(others.firstOrNull()?.id) }
    var withButtons by remember { mutableStateOf(true) }
    val from = others.firstOrNull { it.id == fromId }
    val copied = from?.let { WorkspaceMappings.copy(target, it, withButtons) }
    val buttons = target.touchControls.count { it.kind != TouchKind.FULLSCREEN }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy mappings from…") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Workspace", style = MaterialTheme.typography.labelLarge)
                for (w in others) {
                    Choice(w.name, null, selected = w.id == fromId) { fromId = w.id }
                }
                Text("What to copy", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Choice(
                    "Buttons and mappings",
                    "Its buttons come too, with their places and mappings, replacing the $buttons here.",
                    selected = withButtons,
                ) { withButtons = true }
                Choice(
                    "Mappings only",
                    "Keeps the buttons here: each takes the mappings of its button with the same name.",
                    selected = !withButtons,
                ) { withButtons = false }
                Hint(
                    "Touchpad mappings and the layers are copied as they are. " +
                        "Gyro and touchpad settings stay.",
                )
                val unmatched = copied?.unmatched.orEmpty()
                if (!withButtons && unmatched.isNotEmpty()) {
                    Text(
                        "No match, these keep their own mappings: " +
                            unmatched.joinToString { WorkspaceMappings.buttonName(target, it).ifEmpty { it.id } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (discards) Hint("Unsaved changes in the mapping editor are discarded.")
            }
        },
        confirmButton = {
            TextButton(enabled = copied != null, onClick = {
                val result = copied ?: return@TextButton
                runtime.editActiveProfile { p ->
                    val i = p.indexOfWorkspace(target.id)
                    if (i < 0) p else p.updateWorkspace(i) { result.workspace }
                }
                onCopied()
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
