package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

/**
 * Settings of one workspace (edit mode, opened from the workspace bar): gyro mode, touchpad,
 * touchpad over buttons, sticky modifiers, control opacity and the attached game pads.
 */
@Composable
internal fun WorkspaceSettingsPanel(
    workspace: Workspace,
    width: Dp,
    onChange: (Workspace) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = workspace
    Surface(
        modifier = modifier.width(width),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Workspace · " + w.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("✕") }
            }

            Text("Gyro", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((mode, label) in GYRO_MODES) {
                    WsChoice(label, w.gyro.mode == mode) { onChange(w.copy(gyro = w.gyro.copy(mode = mode))) }
                }
            }

            WsSwitchRow("Touchpad on the background", null, w.touchpad.enabled) { v ->
                onChange(w.copy(touchpad = w.touchpad.copy(enabled = v)))
            }
            WsSwitchRow(
                "Touchpad over buttons",
                "A finger that moves on a button drives the touchpad; a still tap presses the button.",
                w.touchpadOverButtons,
            ) { v -> onChange(w.copy(touchpadOverButtons = v)) }
            WsSwitchRow(
                "Sticky modifiers",
                "Tap Shift, Ctrl, Alt or Win for the next key only; double tap to lock.",
                w.stickyModifiers,
            ) { v -> onChange(w.copy(stickyModifiers = v)) }

            Column {
                Text("Control opacity ${(w.controlOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = w.controlOpacity,
                    onValueChange = { v -> onChange(w.copy(controlOpacity = v)) },
                    valueRange = Profile.MIN_CONTROL_OPACITY..1f,
                )
            }

            HorizontalDivider()
            Text("Game pads", style = MaterialTheme.typography.titleSmall)
            if (w.pads.isEmpty()) {
                Text(
                    "None. Connected controllers do nothing in this workspace; connect one to attach it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (pad in w.pads) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pad.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            padSubtitle(pad),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onChange(w.copy(pads = w.pads.filterNot { it.id == pad.id })) }) {
                        Text("Detach", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (w.pads.isNotEmpty()) {
                Text(
                    "Edit a pad's profile from the pad's Menu, under Devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val GYRO_MODES = listOf(
    GyroMode.OFF to "Off",
    GyroMode.ALWAYS to "Always",
    GyroMode.HOLD_OR_TOGGLE to "Hold / toggle button",
)

private fun padSubtitle(pad: AttachedPad): String {
    val family = when (pad.family) {
        FaceLayout.XBOX -> "Xbox layout"
        FaceLayout.PLAYSTATION -> "PlayStation layout"
        FaceLayout.NINTENDO -> "Nintendo layout"
    }
    val which = if (pad.match == null) "any connected pad" else "this model"
    return "$family · $which · ${pad.mappings.size} mappings"
}

@Composable
private fun WsSwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun WsChoice(text: String, selected: Boolean, onClick: () -> Unit) {
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

/**
 * Mappings of a touch control's inputs in a workspace's mapping list, as removable chips plus an
 * Add chip per input; tapping a chip replaces it.
 */
@Composable
internal fun TouchMappingList(
    mappings: List<Mapping>,
    inputs: List<BindableInput>,
    onChange: (List<Mapping>) -> Unit,
    runtime: GamepadRuntime,
) {
    // (input, target being replaced or null to add)
    var picking by remember { mutableStateOf<Pair<BindableInput, OutputTarget?>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (input in inputs) {
            val targets = Bindings.targets(mappings, input)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inputs.size > 1) {
                    Text(
                        input.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(64.dp),
                    )
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (targets.isEmpty()) {
                        Text("Unmapped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    for (t in targets) {
                        WsTargetChip(
                            text = Describe.shortTarget(t),
                            onClick = { picking = input to t },
                            onRemove = { onChange(Bindings.removeTarget(mappings, input, t)) },
                        )
                    }
                    WsAddChip(onClick = { picking = input to null })
                }
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
                onChange(Bindings.setTargets(mappings, input, next))
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun WsTargetChip(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        )
        Box(Modifier.size(32.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Text("✕", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun WsAddChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ Add", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
