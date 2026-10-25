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
            swipe = input.swipe,
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
