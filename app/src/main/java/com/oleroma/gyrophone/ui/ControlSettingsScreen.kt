package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.oleroma.gyrophone.core.model.Profile
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
 * The one settings page of a touch control (Settings in its mapping editor, or Configure in edit
 * mode). Duplicate, Save as preset (Add preset) and Delete sit fixed on top (beside the title in landscape,
 * under it in portrait); then categories: its look, layers, then the settings its actions share
 * ([shared], from [touchControlSettings]: gyro, a touchpad button's surface, a stick's). [onEditMappings] opens the mapping editor. Edits go through the callbacks.
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
    shared: MappingSettings? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val library by runtime.buttonLibrary.collectAsStateWithLifecycle()
    // Saved to the library during this visit: the button says so instead of saving it twice.
    var savedTo by remember(control.id) { mutableStateOf<String?>(null) }
    SettingsDialog(
        title = control.label.ifBlank { control.id },
        subtitle = (if (control.kind == TouchKind.STICK) "Stick" else if (control.swipes) "Touchpad button" else "Button") +
                " · " + control.id + " · settings",
        onClose = onClose,
        actions = { OutlinedButton(onClick = onEditMappings) { Text("Mappings") } },
        top = {
            // The button's own actions stay fixed above its settings, like a workspace's.
            val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(contentPadding = compact, onClick = onDuplicate) { Text("Duplicate") }
                if (control.kind != TouchKind.FULLSCREEN) {
                    val saved = savedTo != null && library.any { it.id == savedTo }
                    OutlinedButton(contentPadding = compact, enabled = !saved, onClick = {
                        val b = ButtonLibrary.save(workspace, control, library.map { it.id })
                        runtime.addToLibrary(b)
                        savedTo = b.id
                    }) { Text(if (saved) "Saved as preset" else "Save as preset") }
                }
                TextButton(contentPadding = compact, onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        val items = listOfNotNull(
            category(runtime, "look", "Look", "Label, shape, layer, size, opacity, fade", defaultOpen = true, prefKey = "control.Look") {
                LookSettings(control, inPortrait, onControlChange, onSize, workspace.controlOpacity)
            },
            if (workspace.sets.isNotEmpty()) category(runtime, "layers", "Layers", "What it does when the layer changes while held", prefKey = "control.Layers") {
                SwitchSetting(
                    "Follows layers while held",
                    "When the layer changes while this is held, it lets go of what it pressed and does the new layer's mapping at once. " +
                            "Off: it keeps pressing it until released.",
                    control.followLayers,
                ) { onControlChange(control.copy(followLayers = it)) }
            } else null,
        ) + shared?.items.orEmpty()
        shared?.itemsHeader?.invoke()
        ReorderableSections(runtime, "control", items)
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

/** Shape and size of a control (its label is edited in the mapping editor's title). */
@Composable
internal fun LookSettings(
    control: TouchControl,
    inPortrait: Boolean,
    onControlChange: (TouchControl) -> Unit,
    onSize: (Float) -> Unit,
    workspaceOpacity: Float = 0.5f,
) {
    val current by rememberUpdatedState(control)
    // The label is edited in the mapping editor's title (LabelField).
    Searchable("Shape", "Circle Square") { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shape", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
        SelectChip(
            "Circle",
            control.shape == TouchShape.CIRCLE
        ) { onControlChange(current.copy(shape = TouchShape.CIRCLE)) }
        SelectChip(
            "Square",
            control.shape == TouchShape.SQUARE
        ) { onControlChange(current.copy(shape = TouchShape.SQUARE)) }
    } }
    Searchable(
        "Layer", "overlap front back in front", *TouchLayer.entries.map { it.label }.toTypedArray(),
    ) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    ) } }
    SwitchSetting(
        "Shares touches",
        "Also works when a finger lands on a control in front of it, so both fire.",
        control.sharesTouches,
    ) { onControlChange(current.copy(sharesTouches = it)) }
    // Its own opacity (else the workspace's), and Fade when idle: never fully transparent.
    if (control.kind != TouchKind.DISPLAY) {
        SwitchSetting(
            "Own opacity",
            "Off: the workspace's control opacity (" + (workspaceOpacity * 100).roundToInt() + "%).",
            control.opacity != null,
        ) { on -> onControlChange(current.copy(opacity = if (on) workspaceOpacity else null)) }
        control.opacity?.let { o ->
            OpacitySlider("Opacity", o) { onControlChange(current.copy(opacity = it, fade = current.fade?.coerceAtMost(it))) }
        }
        SwitchSetting(
            "Fade when idle",
            "A few seconds after its last touch it fades, never fully: it stays visible. A touch brings it back at once.",
            control.fade != null,
        ) { on ->
            val full = current.opacity ?: workspaceOpacity
            onControlChange(current.copy(fade = if (on) TouchControl.DEFAULT_FADE.coerceAtMost(full) else null))
        }
        control.fade?.let { f ->
            OpacitySlider("Faded opacity", f, max = control.opacity ?: workspaceOpacity) { onControlChange(current.copy(fade = it)) }
        }
    }
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
private fun SwitchSetting(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) = Searchable(title, hint) {
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
    Searchable("Deadzone", "stick rest centre") { Column {
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
    } }
    Searchable("Directions", "stick zones diagonal", *StickZones.entries.map { it.label }.toTypedArray()) { Column {
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
    } }
}

/**
 * What the editor calls an action: a button's own touch is "Touch" (its input is named after the
 * button; "Press" is now a trigger). Its triggers (Tap, Long press...) are chips on that card.
 */
internal fun actionLabel(control: TouchControl, input: BindableInput): String = when {
    control.kind != TouchKind.BUTTON || input.sources.firstOrNull() !is InputSource.Touch -> input.label
    else -> "Touch"
}

/**
 * A control size slider, in % of the pad's shorter side. The track is squared, so the usual
 * sizes get most of it while the far end still fills the screen.
 */
@Composable
internal fun SizeSlider(title: String, value: Float, onChange: (Float) -> Unit) = Searchable(title, "size") {
    val span = TouchControl.MAX_SIZE - TouchControl.MIN_SIZE
    Column {
        Text(title + " " + (value * 100).roundToInt() + "%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = sqrt(((value - TouchControl.MIN_SIZE) / span).coerceIn(0f, 1f)),
            onValueChange = { t -> onChange(TouchControl.MIN_SIZE + span * t * t) },
        )
    }
}

/** An opacity from [Profile.MIN_CONTROL_OPACITY] (never fully transparent) to [max]. */
@Composable
private fun OpacitySlider(title: String, value: Float, max: Float = 1f, onChange: (Float) -> Unit) = Searchable(title, "transparency fade") {
    val top = max.coerceAtLeast(Profile.MIN_CONTROL_OPACITY + 0.01f)
    Column {
        Text(title + " " + (value * 100).roundToInt() + "%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.coerceIn(Profile.MIN_CONTROL_OPACITY, top),
            onValueChange = onChange,
            valueRange = Profile.MIN_CONTROL_OPACITY..top,
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
