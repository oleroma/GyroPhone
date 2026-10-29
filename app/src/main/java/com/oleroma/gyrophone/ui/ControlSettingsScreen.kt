package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickZones
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchLayer
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.withPlacement
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-screen settings of a touch control (a long press in edit mode): collapsible sections for
 * its look, behaviour, area, stick, surface and a summary of its mappings ([onEditMappings]
 * opens the mapping screen). [workspace] holds its mappings; edits go through the callbacks.
 */
@Composable
internal fun ControlSettingsScreen(
    workspace: Workspace,
    control: TouchControl,
    runtime: GamepadRuntime,
    inPortrait: Boolean,
    onControlChange: (TouchControl) -> Unit,
    onSize: (Float) -> Unit,
    onMappingsChange: (List<Mapping>) -> Unit,
    onEditMappings: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val library by runtime.buttonLibrary.collectAsStateWithLifecycle()
    // Saved to the library during this visit: the button says so instead of saving it twice.
    var savedTo by remember(control.id) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            control.label.ifBlank { control.id },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (control.kind == TouchKind.STICK) "Stick" else if (control.swipes) "Touchpad button" else "Button") +
                                    " · " + control.id + " · settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDuplicate) { Text("Duplicate") }
                    if (control.kind != TouchKind.FULLSCREEN) {
                        val saved = savedTo != null && library.any { it.id == savedTo }
                        TextButton(enabled = !saved, onClick = {
                            val b = ButtonLibrary.save(workspace, control, library.map { it.id })
                            runtime.addToLibrary(b)
                            savedTo = b.id
                        }) { Text(if (saved) "In library" else "Save to library") }
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onEditMappings) { Text("Mappings") }
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val section = Modifier.widthIn(max = 720.dp)
                    RememberedSection(runtime, "control.Look", "Look", section, defaultOpen = true) {
                        LookSettings(control, inPortrait, onControlChange, onSize)
                    }
                    if (workspace.sets.isNotEmpty()) {
                        RememberedSection(runtime, "control.Layers", "Layers", section) {
                            SwitchSetting(
                                "Follows layers while held",
                                "When the layer changes while this is held, it lets go of what it pressed and does the new layer's mapping at once. " +
                                        "Off: it keeps pressing it until released.",
                                control.followLayers,
                            ) { onControlChange(control.copy(followLayers = it)) }
                        }
                    }
                    RememberedSection(runtime, "control.Mappings", "Mappings", section, defaultOpen = true) {
                        MappingsSummary(control, workspace.mappings, onEditMappings)
                        Text(
                            "Timings, areas" + (if (control.swipes) ", speed and directions" else if (control.kind == TouchKind.STICK) ", deadzone and directions" else "") +
                                    " are set in Mappings: the ⚙ before an action, or Settings at the top.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${control.label.ifBlank { control.id }}\"?") },
            text = { Text("The ${if (control.kind == TouchKind.STICK) "stick" else "button"} and its mappings are removed from this layout.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Label, shape and size of a control. */
@Composable
internal fun LookSettings(
    control: TouchControl,
    inPortrait: Boolean,
    onControlChange: (TouchControl) -> Unit,
    onSize: (Float) -> Unit,
) {
    val current by rememberUpdatedState(control)
    OutlinedTextField(
        value = control.label,
        onValueChange = { onControlChange(current.copy(label = it)) },
        label = { Text("Label") },
        placeholder = { Text("Shows the mapping") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shape", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
        SelectChip(
            "Circle",
            control.shape == TouchShape.CIRCLE
        ) { onControlChange(current.copy(shape = TouchShape.CIRCLE)) }
        SelectChip(
            "Square",
            control.shape == TouchShape.SQUARE
        ) { onControlChange(current.copy(shape = TouchShape.SQUARE)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Layer", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
        for (l in TouchLayer.entries) SelectChip(
            l.label,
            control.layer == l
        ) { onControlChange(current.copy(layer = l)) }
    }
    Text(
        "Where controls overlap, the smaller one (by its shortest side) is in front. Front / Back override that.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchSetting(
        "Shares touches",
        "Also works when a finger lands on a control in front of it, so both fire.",
        control.sharesTouches,
    ) { onControlChange(current.copy(sharesTouches = it)) }
    val size = control.placeSize(inPortrait)
    if (control.kind == TouchKind.BUTTON && control.shape == TouchShape.SQUARE) {
        // Width and height apart: each slider keeps the other side as it is.
        val height = size * control.aspect
        fun sized(w: Float, h: Float) = current.withPlacement(
            inPortrait, current.placeX(inPortrait), current.placeY(inPortrait), w,
        ).copy(aspect = h / w)
        SizeSlider("Width", size) { onControlChange(sized(it, height)) }
        SizeSlider("Height", height) { onControlChange(sized(size, it)) }
    } else {
        SizeSlider("Size", size, onSize)
    }
}

/** A switch with a title and an explanation under it. */
@Composable
private fun SwitchSetting(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A stick's deadzone and direction zones. */
@Composable
internal fun StickSettings(control: TouchControl, onControlChange: (TouchControl) -> Unit) {
    val current by rememberUpdatedState(control)
    Column {
        Text("Deadzone " + (control.deadzone * 100).roundToInt() + "%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = control.deadzone,
            onValueChange = { onControlChange(current.copy(deadzone = it)) },
            valueRange = 0f..TouchControl.MAX_DEADZONE,
        )
        Text(
            "How far from the centre the stick stays at rest. Past it, the stick still reaches full.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text("Directions", style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (z in StickZones.entries) {
            SelectChip(z.label, control.zones == z) { onControlChange(current.copy(zones = z)) }
        }
    }
    Text(
        when (control.zones) {
            StickZones.OVERLAP -> "Left, Right, Up and Down each on their own: a diagonal presses two."
            StickZones.FOUR -> "One of Left, Right, Up and Down at a time, never two."
            StickZones.EIGHT -> "8 zones. A diagonal does its own mapping, or presses both neighbours when it has none."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Each mapped action of [control] with its targets, and a way into the mapping screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MappingsSummary(control: TouchControl, mappings: List<Mapping>, onEditMappings: () -> Unit) {
    val mapped =
        Bindings.touchInputs(control).map { it to Bindings.targets(mappings, it) }.filter { it.second.isNotEmpty() }
    if (mapped.isEmpty()) {
        Text(
            "Nothing mapped yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    for ((input, targets) in mapped) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                actionLabel(control, input),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp),
            )
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (t in targets) {
                    Text(
                        Describe.shortTarget(t),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
    FilledTonalButton(onClick = onEditMappings) { Text("Edit mappings") }
}

/** What the editor calls an action: a button's own press is "Press" (its input is named after the button). */
internal fun actionLabel(control: TouchControl, input: BindableInput): String = when {
    control.kind != TouchKind.BUTTON || input.sources.firstOrNull() !is InputSource.Touch -> input.label
    // In swipe mode the press is one finger's hold.
    control.swipes -> "Hold"
    else -> "Press"
}

/**
 * A control size slider, in % of the pad's shorter side. The track is squared, so the usual
 * sizes get most of it while the far end still fills the screen.
 */
@Composable
internal fun SizeSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    val span = TouchControl.MAX_SIZE - TouchControl.MIN_SIZE
    Column {
        Text(title + " " + (value * 100).roundToInt() + "%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = sqrt(((value - TouchControl.MIN_SIZE) / span).coerceIn(0f, 1f)),
            onValueChange = { t -> onChange(TouchControl.MIN_SIZE + span * t * t) },
        )
    }
}

/** A ratchet action (the old gyro hold works as one). */
internal fun isRatchet(t: OutputTarget): Boolean = t == OutputTarget.GyroPause || t == OutputTarget.GyroHold || t is OutputTarget.GyroPauseAxis

/** True when the stick's mappings equal what [preset] would write. */
internal fun presetMatches(
    mappings: List<Mapping>,
    inputs: List<BindableInput>,
    preset: Bindings.StickPreset,
): Boolean {
    val applied = Bindings.applyStickPreset(mappings, inputs, preset)
    return inputs.all { Bindings.targets(applied, it).toSet() == Bindings.targets(mappings, it).toSet() }
}

@Composable
internal fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent, shape)
            .border(1.dp, if (selected) colors.secondaryContainer else colors.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
        )
    }
}
