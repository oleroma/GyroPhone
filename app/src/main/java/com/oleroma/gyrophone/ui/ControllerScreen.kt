package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ControllerDiagram
import com.oleroma.gyrophone.core.model.ControllerDiagrams
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.DiagramStyle
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.PhysicalControl
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.diagram
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs
import kotlin.math.roundToInt

private val DiagramStyle.title: String
    get() = when (this) {
        DiagramStyle.XBOX -> "Xbox"
        DiagramStyle.PLAYSTATION -> "PlayStation"
        DiagramStyle.NINTENDO -> "Nintendo"
        DiagramStyle.GAMESIR_X5 -> "GameSir X5"
        DiagramStyle.GENERIC -> "Generic"
    }

private val FaceLayout.title: String
    get() = when (this) {
        FaceLayout.XBOX -> "Xbox"
        FaceLayout.PLAYSTATION -> "PlayStation"
        FaceLayout.NINTENDO -> "Nintendo"
    }

/** The connected controller an attached pad stands for, if any is connected. */
private fun connectedFor(pad: AttachedPad, controllers: List<AttachedController>): AttachedController? =
    controllers.firstOrNull { pad.matches(it.vendorId, it.productId, it.name) }

/** A new attached pad for a connected controller: matched by model, mapped for its family. */
private fun attachedPadFor(c: AttachedController, taken: List<AttachedPad>): AttachedPad {
    val family = c.known?.layout ?: FaceLayout.XBOX
    // Vendor/product identify the model; some Bluetooth pads report none, then the name does.
    val match = if (c.vendorId != 0 || c.productId != 0) DeviceMatch(vendorId = c.vendorId, productId = c.productId)
    else DeviceMatch(nameContains = c.name)
    val used = taken.mapTo(HashSet()) { it.id }
    var n = 1
    while ("pad$n" in used) n++
    return AttachedPad(
        id = "pad$n",
        match = match,
        name = c.known?.name ?: c.name,
        family = family,
        mappings = KnownLayouts.familyMappings(family),
    )
}

/**
 * The game pads attached to the active workspace, each drawn as a diagram; tap a control to remap
 * it for that pad. Connected pads not attached to the workspace can be attached here. Each pad's
 * mapping can be switched to a pad preset, or saved as a new one.
 *
 * [editPadId], when set, selects that pad once; then [onEditPadShown] is called.
 */
@Composable
fun ControllerScreen(runtime: GamepadRuntime, editPadId: String? = null, onEditPadShown: () -> Unit = {}) {
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val workspaceIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val workspace = profile.workspace(workspaceIndex)
    var padId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewStyle by rememberSaveable { mutableStateOf(DiagramStyle.XBOX) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var confirmDetach by remember { mutableStateOf(false) }
    var savePresetName by remember { mutableStateOf<String?>(null) }
    var confirmDeletePreset by remember { mutableStateOf<PadPreset?>(null) }

    LaunchedEffect(editPadId) {
        if (editPadId != null) {
            padId = editPadId
            selectedId = null
            onEditPadShown()
        }
    }

    val pad: AttachedPad? = workspace.pads.firstOrNull { it.id == padId } ?: workspace.pads.firstOrNull()
    val device: AttachedController? = pad?.let { connectedFor(it, controllers) }
    val unattached = controllers.filter { c -> workspace.padFor(c.vendorId, c.productId, c.name) == null }
    val diagram = remember(pad?.id, pad?.match, pad?.family, device, previewStyle) {
        when {
            device != null -> ControllerDiagrams.forDevice(device.known, device.keys, device.axes)
            pad != null -> {
                val known = pad.match?.let { m -> KnownLayouts.controllers.firstOrNull { it.match == m } }
                if (known != null) ControllerDiagrams.forDevice(known) else ControllerDiagrams.of(pad.family.diagram)
            }
            else -> ControllerDiagrams.of(previewStyle)
        }
    }
    val selected = if (pad == null) null else diagram.controls.firstOrNull { it.id == selectedId }
    val appliedPreset: PadPreset? = pad?.let { PadPresets.applied(it, userPresets) }

    /** Rewrites the shown pad in the active workspace (a builtin profile is saved as a custom copy). */
    fun editPad(change: (AttachedPad) -> AttachedPad) {
        val id = pad?.id ?: return
        runtime.editActiveProfile { p ->
            p.updateWorkspace(runtime.workspaceIndex.value) { w -> w.copy(pads = w.pads.map { if (it.id == id) change(it) else it }) }
        }
    }

    fun attach(c: AttachedController) {
        var added: AttachedPad? = null
        runtime.editActiveProfile { p ->
            p.updateWorkspace(runtime.workspaceIndex.value) { w ->
                val new = attachedPadFor(c, w.pads).also { added = it }
                w.copy(pads = w.pads + new)
            }
        }
        padId = added?.id
        selectedId = null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                if (pad != null) {
                    Text(pad.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            device != null -> "Connected: ${device.name}" +
                                "  ·  VID 0x%04X  PID 0x%04X".format(device.vendorId, device.productId)
                            pad.match == null -> "Acts for any game pad connected to the phone"
                            else -> "Not connected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text("No game pad in this workspace", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (unattached.isNotEmpty()) "Attach a connected pad below to use it in \"${workspace.name}\"."
                        else "Connect a game pad to the phone to attach it. Previewing a layout:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Editing: ${profile.name} · ${workspace.name}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (runtime.store.isBuiltin(profile)) {
                    Text(
                        "Changes save a custom copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pad != null) {
                OutlinedButton(onClick = { confirmResetAll = true }) { Text("Reset all") }
                OutlinedButton(onClick = { confirmDetach = true }) { Text("Detach") }
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in workspace.pads) {
                FilterChip(
                    selected = p.id == pad?.id,
                    onClick = { padId = p.id; selectedId = null },
                    label = { Text(p.name, maxLines = 1) },
                )
            }
            for (c in unattached) {
                OutlinedButton(onClick = { attach(c) }) { Text("Attach ${c.known?.name ?: c.name}", maxLines = 1) }
            }
            if (workspace.pads.isEmpty() && unattached.isEmpty()) {
                for (style in DiagramStyle.entries) {
                    FilterChip(
                        selected = style == previewStyle,
                        onClick = { previewStyle = style; selectedId = null },
                        label = { Text(style.title) },
                    )
                }
            }
        }

        if (pad != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Preset", style = MaterialTheme.typography.labelLarge)
                for (preset in PadPresets.all(pad.family, userPresets)) {
                    FilterChip(
                        selected = preset.id == appliedPreset?.id,
                        onClick = {
                            if (preset.id != appliedPreset?.id) {
                                runtime.applyPadPreset(pad.id, preset)
                                selectedId = null
                            }
                        },
                        label = { Text(preset.name, maxLines = 1) },
                    )
                }
                if (appliedPreset == null) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        enabled = false,
                        label = { Text(PadPresets.CUSTOM_NAME) },
                    )
                }
                OutlinedButton(onClick = { savePresetName = "${pad.name} preset" }) { Text("Save as preset…") }
                if (appliedPreset != null && !PadPresets.isBuiltin(appliedPreset.id)) {
                    OutlinedButton(onClick = { confirmDeletePreset = appliedPreset }) { Text("Delete preset") }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Buttons", style = MaterialTheme.typography.labelLarge)
                for (family in FaceLayout.entries) {
                    FilterChip(
                        selected = family == pad.family,
                        onClick = { editPad { it.copy(family = family) } },
                        label = { Text(family.title) },
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selectedId = null },
                contentAlignment = Alignment.Center,
            ) {
                ControllerDiagramView(
                    diagram = diagram,
                    mappings = pad?.mappings ?: KnownLayouts.familyMappings(FaceLayout.XBOX),
                    family = pad?.family ?: FaceLayout.XBOX,
                    runtime = runtime,
                    selectedId = selected?.id,
                    onSelect = { if (pad != null) selectedId = if (selectedId == it) null else it },
                )
            }
            if (selected != null && pad != null) {
                ControlPanel(
                    control = selected,
                    mappings = pad.mappings,
                    family = pad.family,
                    runtime = runtime,
                    onChange = { m -> editPad { it.copy(mappings = m) } },
                    onClose = { selectedId = null },
                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                )
            }
        }
    }

    if (confirmResetAll && pad != null) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset all to standard?") },
            text = { Text("Every button of ${pad.name} goes back to its standard ${pad.family.title} action in \"${workspace.name}\".") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetAll = false
                    editPad { it.copy(mappings = ControllerDiagrams.resetAll(it.mappings, diagram, it.family)) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") } },
        )
    }

    val presetName = savePresetName
    if (presetName != null && pad != null) {
        AlertDialog(
            onDismissRequest = { savePresetName = null },
            title = { Text("Save as preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saves the current mapping of ${pad.name} so it can be applied to any pad.")
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { savePresetName = it },
                        label = { Text("Preset name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        savePresetName = null
                        runtime.savePadPreset(presetName, pad.mappings)
                    },
                    enabled = presetName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { savePresetName = null }) { Text("Cancel") } },
        )
    }

    val presetToDelete = confirmDeletePreset
    if (presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeletePreset = null },
            title = { Text("Delete preset \"${presetToDelete.name}\"?") },
            text = { Text("The preset is removed. Pads using it keep their current mapping, shown as ${PadPresets.CUSTOM_NAME}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePreset = null
                    runtime.deletePadPreset(presetToDelete.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePreset = null }) { Text("Cancel") } },
        )
    }

    if (confirmDetach && pad != null) {
        AlertDialog(
            onDismissRequest = { confirmDetach = false },
            title = { Text("Detach ${pad.name}?") },
            text = { Text("It stops acting in \"${workspace.name}\" and its mapping here is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDetach = false
                    val id = pad.id
                    runtime.editActiveProfile { p ->
                        p.updateWorkspace(runtime.workspaceIndex.value) { w -> w.copy(pads = w.pads.filterNot { it.id == id }) }
                    }
                    padId = null
                    selectedId = null
                }) { Text("Detach") }
            },
            dismissButton = { TextButton(onClick = { confirmDetach = false }) { Text("Cancel") } },
        )
    }
}

/** Live state of the physical controls, polled from the engine every frame. */
private data class LiveState(
    val pressed: Set<String> = emptySet(),
    /** "dpad id/input label" of pressed D-pad directions. */
    val dpad: Set<String> = emptySet(),
    val sticks: Map<String, Pair<Float, Float>> = emptyMap(),
    val triggers: Map<String, Float> = emptyMap(),
)

private fun quantize(v: Float) = (v * 20f).roundToInt() / 20f

private fun readLive(runtime: GamepadRuntime, diagram: ControllerDiagram): LiveState {
    fun level(input: BindableInput) = input.sources.maxOf { abs(runtime.sourceValue(it)) }
    val pressed = HashSet<String>()
    val dpad = HashSet<String>()
    val sticks = HashMap<String, Pair<Float, Float>>()
    val triggers = HashMap<String, Float>()
    for (c in diagram.controls) {
        when (c.kind) {
            PhysicalKind.STICK -> {
                val x = quantize(runtime.sourceValue(c.inputs[0].sources[0]).coerceIn(-1f, 1f))
                val y = quantize(runtime.sourceValue(c.inputs[1].sources[0]).coerceIn(-1f, 1f))
                if (x != 0f || y != 0f) sticks[c.id] = x to y
                if (c.inputs.size > 6 && level(c.inputs[6]) >= 0.5f) pressed += c.id
            }
            PhysicalKind.DPAD -> for (input in c.inputs) if (level(input) >= 0.5f) dpad += "${c.id}/${input.label}"
            PhysicalKind.TRIGGER -> {
                val v = quantize(level(c.inputs[0]).coerceIn(0f, 1f))
                if (v > 0f) triggers[c.id] = v
                if (v >= 0.5f) pressed += c.id
            }
            else -> if (c.inputs.any { level(it) >= 0.5f }) pressed += c.id
        }
    }
    return LiveState(pressed, dpad, sticks, triggers)
}

/** Short text of what a control currently does. */
private fun summary(mappings: List<Mapping>, control: PhysicalControl): String =
    control.inputs.firstNotNullOfOrNull { Bindings.targets(mappings, it).firstOrNull() }?.let { Describe.shortTarget(it) } ?: "Unmapped"

/** Differs from the standard mapping of a pad of [family]. */
private fun isModified(mappings: List<Mapping>, control: PhysicalControl, family: FaceLayout): Boolean =
    control.inputs.any { Bindings.targets(mappings, it).toSet() != ControllerDiagrams.defaultTargets(it, family).toSet() }

@Composable
private fun ControllerDiagramView(
    diagram: ControllerDiagram,
    mappings: List<Mapping>,
    family: FaceLayout,
    runtime: GamepadRuntime,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var live by remember { mutableStateOf(LiveState()) }
    LaunchedEffect(diagram) {
        while (true) {
            withFrameMillis { }
            live = readLive(runtime, diagram)
        }
    }

    BoxWithConstraints(Modifier.aspectRatio(diagram.aspect).padding(4.dp)) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) { drawBody(diagram.style) }
        if (diagram.style == DiagramStyle.GAMESIR_X5) {
            Box(
                Modifier.offset(x = w * 0.215f, y = h * 0.16f).size(w * 0.57f, h * 0.68f),
                contentAlignment = Alignment.Center,
            ) {
                Text("Phone", color = Color(0x66FFFFFF), style = MaterialTheme.typography.labelLarge)
            }
        }
        for (c in diagram.controls) {
            PhysicalControlView(
                control = c,
                style = diagram.style,
                widthDp = w * c.w,
                heightDp = h * c.h,
                modifier = Modifier.offset(x = w * (c.x - c.w / 2f), y = h * (c.y - c.h / 2f)),
                summary = summary(mappings, c),
                modified = isModified(mappings, c, family),
                selected = c.id == selectedId,
                live = live,
                onClick = { onSelect(c.id) },
            )
        }
    }
}

private val controlFill = Color(0xFF1D2025)
private val controlEdge = Color(0xFF5D636E)
private val controlText = Color(0xFFE8EAEE)

/** Face-button letter colours as printed on the real controllers. */
private fun faceColor(style: DiagramStyle, id: String): Color = when (style) {
    DiagramStyle.XBOX, DiagramStyle.GAMESIR_X5 -> when (id) {
        "a" -> Color(0xFF6CC24A); "b" -> Color(0xFFE5484D); "x" -> Color(0xFF4C8DF6); "y" -> Color(0xFFF2C94C)
        else -> controlText
    }
    DiagramStyle.PLAYSTATION -> when (id) {
        "a" -> Color(0xFF7FA7E8); "b" -> Color(0xFFE86A7A); "x" -> Color(0xFFD99AD6); "y" -> Color(0xFF5FC9A8)
        else -> controlText
    }
    else -> controlText
}

private fun faceGlyph(style: DiagramStyle, control: PhysicalControl): String =
    if (style == DiagramStyle.PLAYSTATION && control.kind == PhysicalKind.FACE) {
        when (control.id) { "a" -> "✕"; "b" -> "○"; "x" -> "□"; "y" -> "△"; else -> control.label }
    } else control.label

@Composable
private fun PhysicalControlView(
    control: PhysicalControl,
    style: DiagramStyle,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier,
    summary: String,
    modified: Boolean,
    selected: Boolean,
    live: LiveState,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val shape: Shape = when (control.kind) {
        PhysicalKind.SHOULDER -> RoundedCornerShape(percent = 50)
        PhysicalKind.TRIGGER -> RoundedCornerShape(topStartPercent = 45, topEndPercent = 45, bottomStartPercent = 18, bottomEndPercent = 18)
        PhysicalKind.DPAD -> RoundedCornerShape(percent = 18)
        else -> CircleShape
    }
    val pressed = control.id in live.pressed
    val stick = live.sticks[control.id]
    val active = pressed || (stick != null && (abs(stick.first) > 0.3f || abs(stick.second) > 0.3f))
    val edge = when {
        selected -> primary
        modified -> accent
        active -> primary
        else -> controlEdge
    }
    val edgeWidth = if (selected) 2.5.dp else if (modified) 1.5.dp else 1.dp
    val small = minOf(widthDp, heightDp)
    val labelSize = (small.value * 0.34f).coerceIn(7f, 15f).sp
    val noteSize = (small.value * 0.2f).coerceIn(7f, 11f).sp
    val roomForNote = heightDp >= 40.dp && control.kind != PhysicalKind.DPAD && control.kind != PhysicalKind.STICK
    val labelColor = if (control.kind == PhysicalKind.FACE) faceColor(style, control.id) else controlText

    Box(
        modifier
            .size(widthDp, heightDp)
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (control.kind) {
            PhysicalKind.DPAD -> DpadView(control, live, edge, edgeWidth, primary)
            PhysicalKind.STICK -> StickView(stick, pressed, edge, edgeWidth, primary)
            else -> {
                val fill = if (pressed) primary.copy(alpha = 0.85f) else controlFill
                Box(Modifier.fillMaxSize().background(fill, shape).border(edgeWidth, edge, shape))
                val level = live.triggers[control.id]
                if (control.kind == PhysicalKind.TRIGGER && level != null && !pressed) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(level)
                            .background(primary.copy(alpha = 0.5f)),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val showSummaryOnly = modified && !roomForNote && control.kind != PhysicalKind.DPAD && control.kind != PhysicalKind.STICK
            Text(
                if (showSummaryOnly) summary else faceGlyph(style, control),
                color = if (showSummaryOnly) accent else if (pressed) Color.Black else labelColor,
                fontSize = if (showSummaryOnly) noteSize else labelSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
            )
            if (roomForNote || (modified && (control.kind == PhysicalKind.DPAD || control.kind == PhysicalKind.STICK))) {
                Text(
                    summary,
                    color = if (modified) accent else controlText.copy(alpha = 0.6f),
                    fontSize = noteSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DpadView(control: PhysicalControl, live: LiveState, edge: Color, edgeWidth: Dp, primary: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val s = size.minDimension
        val arm = s / 3f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = CornerRadius(arm * 0.22f)
        fun armRect(dir: String): Pair<Offset, Size> = when (dir) {
            "Up" -> Offset(cx - arm / 2, cy - s / 2) to Size(arm, arm * 1.05f)
            "Down" -> Offset(cx - arm / 2, cy + s / 2 - arm * 1.05f) to Size(arm, arm * 1.05f)
            "Left" -> Offset(cx - s / 2, cy - arm / 2) to Size(arm * 1.05f, arm)
            else -> Offset(cx + s / 2 - arm * 1.05f, cy - arm / 2) to Size(arm * 1.05f, arm)
        }
        val cross = Path().apply {
            moveTo(cx - arm / 2, cy - s / 2); lineTo(cx + arm / 2, cy - s / 2); lineTo(cx + arm / 2, cy - arm / 2)
            lineTo(cx + s / 2, cy - arm / 2); lineTo(cx + s / 2, cy + arm / 2); lineTo(cx + arm / 2, cy + arm / 2)
            lineTo(cx + arm / 2, cy + s / 2); lineTo(cx - arm / 2, cy + s / 2); lineTo(cx - arm / 2, cy + arm / 2)
            lineTo(cx - s / 2, cy + arm / 2); lineTo(cx - s / 2, cy - arm / 2); lineTo(cx - arm / 2, cy - arm / 2)
            close()
        }
        drawPath(cross, controlFill)
        for (input in control.inputs) {
            if ("${control.id}/${input.label}" in live.dpad) {
                val (o, sz) = armRect(input.label)
                drawRoundRect(primary.copy(alpha = 0.85f), topLeft = o, size = sz, cornerRadius = r)
            }
        }
        drawPath(cross, edge, style = Stroke(width = edgeWidth.toPx()))
        drawCircle(Color(0xFF2A2E35), radius = arm * 0.22f, center = Offset(cx, cy))
    }
}

@Composable
private fun StickView(deflection: Pair<Float, Float>?, clicked: Boolean, edge: Color, edgeWidth: Dp, primary: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF14161A), radius = r, center = c)
        drawCircle(edge, radius = r - edgeWidth.toPx() / 2, center = c, style = Stroke(width = edgeWidth.toPx()))
        val knobR = r * 0.68f
        val travel = r - knobR
        val (dx, dy) = deflection ?: (0f to 0f)
        val k = Offset(c.x + dx * travel, c.y + dy * travel)
        drawCircle(if (clicked) primary else Color(0xFF2B2F36), radius = knobR, center = k)
        drawCircle(Color(0xFF3A3F48), radius = knobR * 0.78f, center = k, style = Stroke(width = knobR * 0.12f))
        if (deflection != null) drawCircle(primary, radius = knobR, center = k, style = Stroke(width = 2.dp.toPx()))
    }
}

/** One cubic segment in normalised box coordinates. */
private class Seg(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float)

/** Closed path symmetric about x = 0.5: [segs] trace the left half from (0.5, startY) round to x = 0.5. */
private fun symmetricBody(size: Size, startY: Float, segs: List<Seg>): Path {
    val w = size.width
    val h = size.height
    val p = Path()
    p.moveTo(0.5f * w, startY * h)
    for (s in segs) p.cubicTo(s.c1x * w, s.c1y * h, s.c2x * w, s.c2y * h, s.x * w, s.y * h)
    for (i in segs.indices.reversed()) {
        val s = segs[i]
        val sx = if (i == 0) 0.5f else segs[i - 1].x
        val sy = if (i == 0) startY else segs[i - 1].y
        p.cubicTo((1f - s.c2x) * w, s.c2y * h, (1f - s.c1x) * w, s.c1y * h, (1f - sx) * w, sy * h)
    }
    p.close()
    return p
}

private val xboxBody = listOf(
    Seg(0.42f, 0.165f, 0.36f, 0.115f, 0.25f, 0.115f),
    Seg(0.13f, 0.115f, 0.075f, 0.20f, 0.055f, 0.36f),
    Seg(0.035f, 0.55f, 0.02f, 0.82f, 0.085f, 0.94f),
    Seg(0.13f, 1.0f, 0.235f, 0.99f, 0.275f, 0.90f),
    Seg(0.315f, 0.83f, 0.37f, 0.845f, 0.5f, 0.845f),
)

private val psBody = listOf(
    Seg(0.40f, 0.15f, 0.33f, 0.13f, 0.22f, 0.13f),
    Seg(0.12f, 0.13f, 0.07f, 0.19f, 0.055f, 0.32f),
    Seg(0.035f, 0.50f, 0.0f, 0.78f, 0.05f, 0.93f),
    Seg(0.085f, 1.0f, 0.19f, 1.0f, 0.235f, 0.90f),
    Seg(0.28f, 0.80f, 0.35f, 0.86f, 0.5f, 0.86f),
)

private val nintendoBody = listOf(
    Seg(0.42f, 0.16f, 0.35f, 0.12f, 0.24f, 0.12f),
    Seg(0.12f, 0.12f, 0.065f, 0.21f, 0.05f, 0.38f),
    Seg(0.035f, 0.58f, 0.03f, 0.84f, 0.10f, 0.95f),
    Seg(0.15f, 1.0f, 0.245f, 0.98f, 0.28f, 0.88f),
    Seg(0.31f, 0.82f, 0.37f, 0.85f, 0.5f, 0.85f),
)

/** One grip of the telescopic X5; the inner edge is straight where it clamps the phone. */
private fun x5Grip(size: Size, right: Boolean): Path {
    fun px(x: Float) = (if (right) 1f - x else x) * size.width
    fun py(y: Float) = y * size.height
    return Path().apply {
        moveTo(px(0.215f), py(0.12f))
        lineTo(px(0.07f), py(0.12f))
        cubicTo(px(0.025f), py(0.12f), px(0.004f), py(0.22f), px(0.004f), py(0.36f))
        lineTo(px(0.004f), py(0.80f))
        cubicTo(px(0.004f), py(0.93f), px(0.03f), py(0.99f), px(0.075f), py(0.99f))
        lineTo(px(0.215f), py(0.99f))
        close()
    }
}

private fun DrawScope.drawShell(path: Path, top: Color, bottom: Color, outline: Color) {
    translate(top = size.height * 0.02f) { drawPath(path, Color.Black.copy(alpha = 0.35f)) }
    drawPath(path, Brush.verticalGradient(listOf(top, bottom)))
    drawPath(path, outline, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawBody(style: DiagramStyle) {
    val w = size.width
    val h = size.height
    when (style) {
        DiagramStyle.XBOX -> drawShell(symmetricBody(size, 0.165f, xboxBody), Color(0xFF4B515B), Color(0xFF2C3036), Color(0xFF6A717D))
        DiagramStyle.GENERIC -> drawShell(symmetricBody(size, 0.165f, xboxBody), Color(0xFF43464D), Color(0xFF292B30), Color(0xFF62666F))
        DiagramStyle.NINTENDO -> drawShell(symmetricBody(size, 0.16f, nintendoBody), Color(0xFF3B3B40), Color(0xFF222225), Color(0xFF5C5C63))
        DiagramStyle.PLAYSTATION -> {
            drawShell(symmetricBody(size, 0.15f, psBody), Color(0xFFF1F2F5), Color(0xFFC6CAD2), Color(0xFF9AA0AA))
            drawRoundRect(
                Color(0xFF1E2126),
                topLeft = Offset(0.39f * w, 0.17f * h),
                size = Size(0.22f * w, 0.29f * h),
                cornerRadius = CornerRadius(0.05f * h),
            )
            drawRoundRect(
                Color(0xFF4C8DF6).copy(alpha = 0.6f),
                topLeft = Offset(0.39f * w, 0.17f * h),
                size = Size(0.22f * w, 0.29f * h),
                cornerRadius = CornerRadius(0.05f * h),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        DiagramStyle.GAMESIR_X5 -> {
            for (right in listOf(false, true)) {
                drawShell(x5Grip(size, right), Color(0xFF41454D), Color(0xFF24272C), Color(0xFF666C77))
            }
            val phoneTl = Offset(0.215f * w, 0.16f * h)
            val phoneSize = Size(0.57f * w, 0.68f * h)
            drawRoundRect(Color(0xFF0C0D10), topLeft = phoneTl, size = phoneSize, cornerRadius = CornerRadius(0.08f * h))
            val inset = 0.03f * h
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF121722))),
                topLeft = Offset(phoneTl.x + inset, phoneTl.y + inset),
                size = Size(phoneSize.width - 2 * inset, phoneSize.height - 2 * inset),
                cornerRadius = CornerRadius(0.06f * h),
            )
            drawRoundRect(
                Color(0xFF3A3F48),
                topLeft = phoneTl,
                size = phoneSize,
                cornerRadius = CornerRadius(0.08f * h),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ControlPanel(
    control: PhysicalControl,
    mappings: List<Mapping>,
    family: FaceLayout,
    runtime: GamepadRuntime,
    onChange: (List<Mapping>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(control.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (control.kind == PhysicalKind.STICK && control.inputs.size >= 6) {
                val stickInputs = control.inputs.take(6)
                val current = stickInputs.map { Bindings.targets(mappings, it).toSet() }
                val activePreset = Bindings.StickPreset.entries.firstOrNull { preset ->
                    val p = Bindings.applyStickPreset(mappings, stickInputs, preset)
                    stickInputs.map { Bindings.targets(p, it).toSet() } == current
                }
                Text("Preset", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in Bindings.StickPreset.entries) {
                        FilterChip(
                            selected = preset == activePreset,
                            onClick = { onChange(Bindings.applyStickPreset(mappings, stickInputs, preset)) },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }
            Text("Mapping", style = MaterialTheme.typography.labelLarge)
            BindingList(
                mappings = mappings,
                inputs = control.inputs,
                onChange = onChange,
                runtime = runtime,
                defaults = { ControllerDiagrams.defaultTargets(it, family) },
            )
            OutlinedButton(
                onClick = { onChange(ControllerDiagrams.resetControl(mappings, control, family)) },
                enabled = isModified(mappings, control, family),
            ) { Text("Reset to default") }
        }
    }
}
