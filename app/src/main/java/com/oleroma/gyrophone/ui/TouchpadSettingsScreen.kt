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
import androidx.compose.material3.Switch
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
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.isTouchpad
import com.oleroma.gyrophone.runtime.GamepadRuntime


/**
 * Full-screen settings of the workspace touchpad (a long press on empty space in edit mode): the
 * touchpad ratchet and a summary of its mappings
 * ([onEditMappings] opens their screen). Edits go to [onWorkspaceChange].
 */
@Composable
internal fun TouchpadSettingsScreen(
    workspace: Workspace,
    runtime: GamepadRuntime,
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
                        Text("Touchpad", style = MaterialTheme.typography.titleMedium)
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
                    RememberedSection(runtime, "touchpad.Gyro", "Gyro", section) {
                        TouchpadSwitch(
                            "Touchpad is a ratchet (a finger on the empty pad flips gyro while it is down, even with the touchpad off)",
                            workspace.gyro.touchpadRatchet,
                        ) { v -> onWorkspaceChange { it.copy(gyro = it.gyro.copy(touchpadRatchet = v)) } }
                    }
                    RememberedSection(runtime, "touchpad.Mappings", "Mappings", section, defaultOpen = true) {
                        TouchpadMappingsSummary(workspace, onEditMappings) {
                            onWorkspaceChange { w ->
                                w.copy(
                                    mappings = w.mappings.filterNot { it.source.isTouchpad && it.set == 0 } + Bindings.defaultTouchpadMappings(
                                        TouchpadSettings()
                                    ),
                                    touchpad = w.touchpad.copy(mapped = true),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What each finger count of the touchpad does, with Edit and Reset. */
@Composable
private fun TouchpadMappingsSummary(workspace: Workspace, onEdit: () -> Unit, onReset: () -> Unit) {
    val mapped = workspace.mappings.filter { it.source.isTouchpad }
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
        "Speed, scrolling, directions, timings and zones are set in Mappings: the ⚙ before an action, or Settings at the top.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit) { Text("Edit mappings") }
        TextButton(onClick = onReset) { Text("Reset to defaults") }
    }
}

@Composable
private fun TouchpadSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
