package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.isTouchpad
import com.oleroma.gyrophone.runtime.GamepadRuntime


/**
 * Full-screen settings of the workspace touchpad (a long press on empty space in edit mode): the
 * summary of its mappings
 * ([onEditMappings] opens their screen). Edits go to [onWorkspaceChange].
 */
@Composable
internal fun TouchpadSettingsScreen(
    workspace: Workspace,
    runtime: GamepadRuntime,
    defaults: Workspace,
    onWorkspaceChange: ((Workspace) -> Workspace) -> Unit,
    onEditMappings: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Workspace", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The empty pad area · " + workspace.name + " · settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onEditMappings) { Text("Mappings") }
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val section = Modifier.widthIn(max = 720.dp)
                    RememberedSection(runtime, "touchpad.Mappings", "Mappings", section, defaultOpen = true) {
                        TouchpadMappingsSummary(workspace, defaults, onEditMappings) {
                            // Its own Base touchpad mappings go: each input follows the Defaults again.
                            onWorkspaceChange { w -> w.copy(mappings = w.mappings.filterNot { it.source.isTouchpad && it.set == 0 }) }
                        }
                    }
                }
            }
        }
    }
}

/** What each finger count of the touchpad does in Base (its own mappings and those it takes from [defaults]), with Edit and Reset. */
@Composable
private fun TouchpadMappingsSummary(workspace: Workspace, defaults: Workspace, onEdit: () -> Unit, onReset: () -> Unit) {
    val mapped = Inheritance.workspace(workspace, defaults)
        .filter { it.source.isTouchpad && it.set == 0 && it.target != OutputTarget.NoOutput }
    for (n in 1..4) {
        val lines = Bindings.surfaceInputs(n).mapNotNull { input ->
            val targets = Bindings.targets(mapped, input)
            if (targets.isEmpty()) null else input.label + ": " + targets.joinToString { Describe.shortTarget(it) }
        }
        Text(if (n == 1) "1 finger" else "$n fingers", style = MaterialTheme.typography.labelLarge)
        Text(
            lines.joinToString(" · ").ifEmpty { "Nothing mapped" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "The touchpad's speed, scrolling, directions, timings and zones are this workspace's settings: " +
                "set them in Mappings (⚙ Settings at the top, or the ⚙ before an action) or in the workspace's settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit) { Text("Edit mappings") }
        if (workspace.mappings.any { it.source.isTouchpad && it.set == 0 }) {
            TextButton(onClick = onReset) { Text("Follow Defaults") }
        }
    }
}
