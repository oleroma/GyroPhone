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
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceEdits
import com.oleroma.gyrophone.runtime.AttachedController

/**
 * Rename, duplicate and delete (not the last) for workspace [index], at the top of its settings
 * (reorder by dragging its capsule on the pad). [onProfileChange] gets the edited profile and the index to show next.
 */
@Composable
internal fun WorkspaceActions(
    profile: Profile,
    index: Int,
    onProfileChange: (Profile, Int) -> Unit,
) {
    val i = index.coerceIn(0, profile.workspaces.lastIndex)
    val current = profile.workspace(i)
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(contentPadding = compact, onClick = { renaming = current.name }) { Text("Rename") }
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

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${current.name}\"?") },
            text = { Text("Its controls, settings and attached pads go with it. This can't be undone.") },
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
