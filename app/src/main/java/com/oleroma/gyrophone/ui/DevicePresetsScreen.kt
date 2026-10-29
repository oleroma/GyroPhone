package com.oleroma.gyrophone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ControllerDiagram
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.DevicePresets
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import com.oleroma.gyrophone.core.model.withSets
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A control of a device drawing: what its mapping screen edits. */
private data class Element(val id: String, val label: String, val stick: Boolean, val inputs: List<BindableInput>)

/**
 * The device screens (the pad's Controller capsule): the device presets as tabs along the top
 * (tap to pick, long-press for name, workspaces, duplicate and delete; + adds one), the picked
 * device drawn in the middle (the controller from the top and the back, a keyboard or a mouse), and
 * the devices to switch between along the bottom, with whether the current workspace uses the
 * preset. A tap on a control opens its mapping screen, as for touch controls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DevicePresetsScreen(runtime: GamepadRuntime, onBack: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val current = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
    val ws = profile.workspace(current)
    val presets = profile.devicePresets
    var pickedId by rememberSaveable { mutableStateOf(ws.devicePreset) }
    val preset = presets.firstOrNull { it.id == pickedId } ?: presets.firstOrNull { it.id == ws.devicePreset } ?: presets.firstOrNull()
    var deviceName by rememberSaveable { mutableStateOf(PhysicalDevice.CONTROLLER.name) }
    val device = PhysicalDevice.entries.firstOrNull { it.name == deviceName } ?: PhysicalDevice.CONTROLLER
    var editing by remember { mutableStateOf<Element?>(null) }
    var menuFor by remember { mutableStateOf<DevicePreset?>(null) }
    var mapSet by remember { mutableIntStateOf(MappingSet.BASE) }
    val colors = MaterialTheme.colorScheme

    /** Adds a standard preset and picks it; the first one a workspace without one gets is pinned to it. */
    fun add() {
        runtime.editActiveProfile { p ->
            val id = DevicePresets.newId(p.devicePresets.map { it.id })
            pickedId = id
            val added = DevicePresets.put(p, DevicePresets.standard(id, DevicePresets.newName(p.devicePresets.map { it.name })))
            if (p.workspace(current).devicePreset == null) added.updateWorkspace(current) { it.copy(devicePreset = id) } else added
        }
    }

    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Pad") }
            for (p in presets) {
                PresetTab(
                    p.name,
                    selected = p.id == preset?.id,
                    usedHere = p.id == ws.devicePreset,
                    onClick = { pickedId = p.id },
                    onLongClick = { menuFor = p },
                )
            }
            TextButton(onClick = { add() }) { Text("+ Preset") }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            if (preset == null) {
                Column(Modifier.align(Alignment.Center).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No device presets yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A preset says what a controller, a keyboard and a mouse attached to the phone do. " +
                            "Each workspace uses one preset, or none.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    FilledTonalButton(onClick = { add() }) { Text("+ New preset") }
                }
            } else {
                val view = Bindings.viewSet(preset.mappings(device), mapSet)
                val open = { e: Element -> editing = e }
                when (device) {
                    PhysicalDevice.CONTROLLER -> {
                        val paddles = controllers.isEmpty() || controllers.any { c ->
                            c.keys.isEmpty() || c.keys.any { it in PhysicalLayouts.paddleKeys }
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            if (maxWidth > maxHeight) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DiagramView(PhysicalLayouts.controllerFront, view, true, Modifier.weight(1f).fillMaxSize(), open)
                                    DiagramView(PhysicalLayouts.controllerBack, view, paddles, Modifier.weight(1f).fillMaxSize(), open)
                                }
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DiagramView(PhysicalLayouts.controllerFront, view, true, Modifier.weight(1f).fillMaxSize(), open)
                                    DiagramView(PhysicalLayouts.controllerBack, view, paddles, Modifier.weight(1f).fillMaxSize(), open)
                                }
                            }
                        }
                    }
                    PhysicalDevice.KEYBOARD -> KeyboardView(view, open)
                    PhysicalDevice.MOUSE -> MouseView(view, open)
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (d in PhysicalDevice.entries) {
                FilterChip(selected = d == device, onClick = { deviceName = d.name }, label = { Text(d.label) })
            }
            if (preset != null) {
                Spacer(Modifier.width(16.dp))
                val used = ws.devicePreset == preset.id
                FilterChip(
                    selected = used,
                    onClick = {
                        runtime.editActiveProfile { p ->
                            p.updateWorkspace(current) { it.copy(devicePreset = if (used) null else preset.id) }
                        }
                    },
                    label = { Text(if (used) "Used in ${ws.name}" else "Use in ${ws.name}", maxLines = 1) },
                )
            }
        }
    }

    val e = editing
    if (e != null && preset != null) {
        ControlMappingScreen(
            control = physicalStandIn(device, e.id, e.label, e.stick),
            mappings = preset.mappings(device),
            runtime = runtime,
            onMappingsChange = { m ->
                runtime.editActiveProfile { p ->
                    val now = p.devicePresets.firstOrNull { it.id == preset.id } ?: return@editActiveProfile p
                    DevicePresets.put(p, now.withMappings(device, m))
                }
            },
            onSettings = null,
            onClose = { editing = null },
            sets = ws.sets,
            editSet = if (ws.sets.any { it.id == mapSet }) mapSet else MappingSet.BASE,
            onEditSet = { mapSet = it },
            onSetsChange = { sets, removed -> runtime.editActiveProfile { p -> p.updateWorkspace(current) { it.withSets(sets, removed) } } },
            physicalInputs = e.inputs,
        )
    }

    menuFor?.let { p -> PresetMenu(runtime, p, onPick = { pickedId = it }, onDismiss = { menuFor = null }) }
}

/** A preset's tab: [usedHere] marks the one the current workspace uses. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTab(name: String, selected: Boolean, usedHere: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .border(1.dp, if (selected) colors.secondary else colors.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (usedHere) Box(Modifier.padding(end = 6.dp).size(8.dp).background(colors.primary, CircleShape))
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A long press on a preset's tab: its name, the workspaces that use it, Duplicate and Delete. */
@Composable
private fun PresetMenu(runtime: GamepadRuntime, preset: DevicePreset, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var using by remember(preset.id) { mutableStateOf(DevicePresets.pinnedBy(profile, preset.id).toSet()) }
    var deleting by remember(preset.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleting) "Delete \"${preset.name}\"?" else "Preset") },
        text = {
            if (deleting) {
                Text("Its controller, keyboard and mouse mappings go. Workspaces that use it are left with none.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    Text("Used in", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    for (w in profile.workspaces) {
                        Row(
                            Modifier.fillMaxWidth().clickable { using = if (w.id in using) using - w.id else using + w.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = w.id in using, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            val other = profile.devicePresets.firstOrNull { it.id == w.devicePreset && it.id != preset.id }
                            Column {
                                Text(w.name)
                                if (other != null && w.id !in using) {
                                    Text("Uses ${other.name}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (deleting) {
                TextButton(onClick = {
                    runtime.editActiveProfile { DevicePresets.remove(it, preset.id) }
                    onDismiss()
                }) { Text("Delete", color = colors.error) }
            } else {
                TextButton(onClick = {
                    runtime.editActiveProfile { p ->
                        val named = DevicePresets.put(p, preset.copy(name = name.trim().ifBlank { preset.name }))
                        DevicePresets.pin(named, preset.id, using)
                    }
                    onDismiss()
                }) { Text("Done") }
            }
        },
        dismissButton = {
            Row {
                if (deleting) {
                    TextButton(onClick = { deleting = false }) { Text("Cancel") }
                } else {
                    TextButton(onClick = { deleting = true }) { Text("Delete", color = colors.error) }
                    TextButton(onClick = {
                        runtime.editActiveProfile { p ->
                            val id = DevicePresets.newId(p.devicePresets.map { it.id })
                            onPick(id)
                            DevicePresets.put(p, preset.copy(id = id, name = preset.name + " copy"))
                        }
                        onDismiss()
                    }) { Text("Duplicate") }
                }
            }
        },
    )
}

/** What a control is mapped to in [view] (the edited set as Base): its first mapped input's first target. */
private fun summary(view: List<Mapping>, inputs: List<BindableInput>): String? {
    val mapped = inputs.mapNotNull { Bindings.targets(view, it).firstOrNull() }
    if (mapped.isEmpty()) return null
    return Describe.shortTarget(mapped.first()) + if (mapped.size > 1) " +" + (mapped.size - 1) else ""
}

/** A control of a device drawing: its [label] and, when mapped, [summary] under it. */
@Composable
private fun ElementBox(
    label: String,
    summary: String?,
    round: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.35f),
        shape = shape,
        color = if (summary != null) colors.primaryContainer else colors.surfaceContainerHighest,
        border = BorderStroke(1.dp, if (summary != null) colors.primary else colors.outline),
    ) {
        Column(
            Modifier.fillMaxSize().padding(1.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = if (summary != null) colors.onPrimaryContainer else colors.onSurface,
            )
            if (summary != null) {
                Text(
                    summary,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = colors.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * [d] drawn as large as fits, a controller body behind its controls. [enabledExtras]: the back
 * buttons can be picked (an attached controller has them, or none is attached to tell).
 */
@Composable
private fun DiagramView(d: ControllerDiagram, view: List<Mapping>, enabledExtras: Boolean, modifier: Modifier, onOpen: (Element) -> Unit) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(modifier) {
        val w = min(maxWidth, (maxHeight - 20.dp) * d.aspect)
        val h = w / d.aspect
        Text(d.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopStart))
        Box(Modifier.align(Alignment.Center).padding(top = 20.dp).size(w, h)) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = size.width
                val sh = size.height
                drawRoundRect(body, Offset(sw * 0.1f, sh * 0.1f), Size(sw * 0.8f, sh * 0.62f), CornerRadius(sh * 0.18f))
                drawOval(body, Offset(sw * 0.05f, sh * 0.35f), Size(sw * 0.36f, sh * 0.65f))
                drawOval(body, Offset(sw * 0.59f, sh * 0.35f), Size(sw * 0.36f, sh * 0.65f))
            }
            for (c in d.controls) {
                val extra = c.id == "p1" || c.id == "p2"
                val enabled = !extra || enabledExtras
                ElementBox(
                    c.label,
                    if (enabled) summary(view, c.inputs) else null,
                    round = c.kind == PhysicalKind.FACE && !extra || c.kind == PhysicalKind.STICK ||
                        c.kind == PhysicalKind.SYSTEM || c.kind == PhysicalKind.DPAD,
                    enabled = enabled,
                    modifier = Modifier.offset(w * (c.x - c.w / 2f), h * (c.y - c.h / 2f)).size(w * c.w, h * c.h),
                ) { onOpen(Element(c.id, c.label, c.kind == PhysicalKind.STICK, c.inputs)) }
            }
        }
    }
}

/** A full keyboard: the width split in key units, at least [MIN_KEY] each (then it scrolls sideways). */
@Composable
private fun KeyboardView(view: List<Mapping>, onOpen: (Element) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rows = PhysicalLayouts.keyboardRows
        val unit = max(maxWidth / PhysicalLayouts.KEYBOARD_UNITS, MIN_KEY)
        val keyH = min(unit, maxHeight / rows.size)
        Column(
            Modifier.align(Alignment.Center).horizontalScroll(rememberScrollState()),
        ) {
            for (row in rows) {
                Row {
                    for (k in row) {
                        val code = k.code
                        if (code == null) {
                            Spacer(Modifier.size(unit * k.width, keyH))
                        } else {
                            val inputs = PhysicalLayouts.buttonInputs(listOf(code))
                            ElementBox(
                                k.label, summary(view, inputs), round = false, enabled = true,
                                modifier = Modifier.size(unit * k.width, keyH).padding(1.5.dp),
                            ) { onOpen(Element("key$code", k.label, false, inputs)) }
                        }
                    }
                }
            }
        }
    }
}

/** A mouse from above: its buttons, wheel, side buttons and movement. */
@Composable
private fun MouseView(view: List<Mapping>, onOpen: (Element) -> Unit) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outline
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = min(maxHeight, maxWidth / PhysicalLayouts.MOUSE_ASPECT)
        val w = h * PhysicalLayouts.MOUSE_ASPECT
        Box(Modifier.align(Alignment.Center).size(w, h)) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = size.width
                val sh = size.height
                val outline = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            sw * 0.15f, sh * 0.02f, sw * 0.85f, sh * 0.98f,
                            CornerRadius(sw * 0.35f, sw * 0.35f),
                        ),
                    )
                }
                drawPath(outline, body)
                drawLine(line, Offset(sw * 0.5f, sh * 0.02f), Offset(sw * 0.5f, sh * 0.35f), strokeWidth = 2f)
            }
            for (c in PhysicalLayouts.mouseControls) {
                ElementBox(
                    c.label, summary(view, c.inputs), round = false, enabled = true,
                    modifier = Modifier.offset(w * (c.x - c.w / 2f), h * (c.y - c.h / 2f)).size(w * c.w, h * c.h),
                ) { onOpen(Element(c.id, c.label, false, c.inputs)) }
            }
        }
    }
}

private val MIN_KEY: Dp = 34.dp
