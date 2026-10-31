package com.oleroma.gyrophone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
import com.oleroma.gyrophone.core.model.PhysicalControl
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import com.oleroma.gyrophone.core.model.withSets
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A control of a device drawing: what its mapping screen edits. */
private data class Element(val id: String, val label: String, val stick: Boolean, val inputs: List<BindableInput>)

/**
 * The device screens (the pad's Controller capsule): the device presets as tabs along the top
 * (tap to pick, long-press to rename, duplicate or delete; + adds one), the picked device drawn in
 * the middle (the controller from the top and the back, a keyboard or a mouse), and the devices to
 * switch between along the bottom. A tap on a control opens its mapping screen, as for touch
 * controls. Which preset a workspace uses is picked in its edit mode.
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

    /** Adds a standard preset and picks it. */
    fun add() {
        runtime.editActiveProfile { p ->
            val id = DevicePresets.newId(p.devicePresets.map { it.id })
            pickedId = id
            DevicePresets.put(p, DevicePresets.standard(id, DevicePresets.newName(p.devicePresets.map { it.name })))
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
                            "Each workspace uses one preset, or none: pick it in Settings, on the workspace's chip (Workspace → Device preset).",
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
                        // A back button can be picked when an attached pad has it, or none is attached to tell.
                        val has = { c: PhysicalControl ->
                            val keys = PhysicalLayouts.paddles[c.id]
                            keys == null || controllers.isEmpty() || controllers.any { p -> p.keys.isEmpty() || p.keys.any { it in keys } }
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            if (maxWidth > maxHeight) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DiagramView(PhysicalLayouts.controllerFront, view, has, Modifier.weight(1f).fillMaxSize(), open)
                                    DiagramView(PhysicalLayouts.controllerBack, view, has, Modifier.weight(1f).fillMaxSize(), open)
                                }
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DiagramView(PhysicalLayouts.controllerFront, view, has, Modifier.weight(1f).fillMaxSize(), open)
                                    DiagramView(PhysicalLayouts.controllerBack, view, has, Modifier.weight(1f).fillMaxSize(), open)
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
            settings = physicalSettings(preset, ws) { change ->
                runtime.editActiveProfile { p ->
                    val now = p.devicePresets.firstOrNull { it.id == preset.id } ?: return@editActiveProfile p
                    DevicePresets.put(p, change(now))
                }
            },
        )
    }

    menuFor?.let { p -> PresetMenu(runtime, p, onPick = { pickedId = it }, onDismiss = { menuFor = null }) }
}

/** A preset's tab. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTab(name: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A long press on a preset's tab: its name, Reset all (its keys' own timings), Duplicate and Delete. */
@Composable
private fun PresetMenu(runtime: GamepadRuntime, preset: DevicePreset, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var deleting by remember(preset.id) { mutableStateOf(false) }
    val users = profile.workspaces.filter { it.devicePreset == preset.id }.map { it.name }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleting) "Delete \"${preset.name}\"?" else "Preset") },
        text = {
            if (deleting) {
                Text(
                    "Its controller, keyboard and mouse mappings go." +
                        if (users.isEmpty()) "" else " " + users.joinToString() + " will use no preset.",
                )
            } else {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    Text(
                        if (users.isEmpty()) "No workspace uses it yet: pick it in Settings, on a workspace's chip (Workspace → Device preset)."
                        else "Used in " + users.joinToString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // Keys' own long press / double tap times (their ⚙): all back to the workspace's.
                    val timed = profile.devicePresets.firstOrNull { it.id == preset.id }?.timings.orEmpty().size
                    ResetAllButton("Workspace" + if (timed > 0) " ($timed keys)" else "", timed > 0) {
                        runtime.editActiveProfile { p ->
                            val now = p.devicePresets.firstOrNull { it.id == preset.id } ?: return@editActiveProfile p
                            DevicePresets.put(p, now.copy(timings = emptyList()))
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
                        val now = p.devicePresets.firstOrNull { it.id == preset.id } ?: preset
                        DevicePresets.put(p, now.copy(name = name.trim().ifBlank { preset.name }))
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

/**
 * What a control is mapped to in [view] (the edited set as Base): its first mapped input's first
 * target, "" when that is just the control itself (A to A), null when nothing is mapped.
 */
private fun summary(view: List<Mapping>, inputs: List<BindableInput>, label: String): String? {
    val mapped = inputs.mapNotNull { Bindings.targets(view, it).firstOrNull() }
    if (mapped.isEmpty()) return null
    val first = Describe.shortTarget(mapped.first())
    val more = if (mapped.size > 1) "+" + (mapped.size - 1) else ""
    return if (first.equals(label, ignoreCase = true)) more else first + if (more.isEmpty()) "" else " $more"
}

/**
 * A control of a device drawing: its [label] and, when mapped to something else, [summary] under
 * it ("" = mapped to itself, null = not mapped). The text shrinks to fit the control.
 */
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
    val mapped = summary != null
    val content = if (mapped) colors.onPrimaryContainer else colors.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.3f),
        shape = shape,
        color = if (mapped) colors.primaryContainer else colors.surfaceContainerHighest,
        border = BorderStroke(1.dp, if (mapped) colors.primary else colors.outline),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val second = !summary.isNullOrEmpty()
            // A circle's text has its inner square; letters are about 0.6 of the size wide.
            val room = if (round) 0.72f else 0.9f
            fun fit(text: String, share: Float, max: Float) = with(density) {
                minOf(maxWidth * room / (0.6f * text.length.coerceAtLeast(1)), maxHeight * room * share).toSp()
            }.value.coerceIn(6f, max).sp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    fontSize = fit(label, if (second) 0.5f else 0.6f, 13f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    color = content,
                )
                if (summary != null && second) {
                    Text(
                        summary,
                        fontSize = fit(summary, 0.32f, 10f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        color = content,
                    )
                }
            }
        }
    }
}

/**
 * The outline of a DualSense in a box of [w] x [h], as seen from above: a flat top with round
 * shoulders and two long grips. The back view uses it too.
 */
private fun padOutline(w: Float, h: Float): Path {
    // The left half, from the top middle round the left grip to the bottom middle (x, y as box fractions).
    val half = OUTLINE_HALF
    return Path().apply {
        moveTo(w * 0.5f, h * OUTLINE_TOP)
        for (c in half) cubicTo(w * c[0], h * c[1], w * c[2], h * c[3], w * c[4], h * c[5])
        // The right half: the same curves mirrored, from the bottom middle back up.
        val points = ArrayList<Pair<Float, Float>>()
        points.add(0.5f to OUTLINE_TOP)
        for (c in half) { points.add(c[0] to c[1]); points.add(c[2] to c[3]); points.add(c[4] to c[5]) }
        val back = points.reversed()
        var i = 1
        while (i + 2 < back.size) {
            val (x1, y1) = back[i]; val (x2, y2) = back[i + 1]; val (x3, y3) = back[i + 2]
            cubicTo(w * (1f - x1), h * y1, w * (1f - x2), h * y2, w * (1f - x3), h * y3)
            i += 3
        }
        close()
    }
}

private const val OUTLINE_TOP = 0.03f
private val OUTLINE_HALF = listOf(
    floatArrayOf(0.36f, 0.03f, 0.22f, 0.02f, 0.13f, 0.05f),
    floatArrayOf(0.05f, 0.08f, 0.025f, 0.20f, 0.02f, 0.40f),
    floatArrayOf(0.015f, 0.62f, 0.04f, 0.88f, 0.09f, 0.97f),
    floatArrayOf(0.13f, 1.02f, 0.21f, 1.0f, 0.25f, 0.92f),
    floatArrayOf(0.29f, 0.82f, 0.31f, 0.73f, 0.38f, 0.70f),
    floatArrayOf(0.44f, 0.68f, 0.48f, 0.68f, 0.5f, 0.68f),
)

/** [d] drawn as large as fits, a game pad body behind its controls; [has]: whether a control can be picked. */
@Composable
private fun DiagramView(
    d: ControllerDiagram,
    view: List<Mapping>,
    has: (PhysicalControl) -> Boolean,
    modifier: Modifier,
    onOpen: (Element) -> Unit,
) {
    val body = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier) {
        val w = min(maxWidth, (maxHeight - 20.dp) * d.aspect)
        val h = w / d.aspect
        Text(d.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopStart))
        Box(Modifier.align(Alignment.Center).padding(top = 20.dp).size(w, h)) {
            Canvas(Modifier.fillMaxSize()) {
                val outline = padOutline(size.width, size.height)
                drawPath(outline, body)
                drawPath(outline, edge, style = Stroke(width = 1.5.dp.toPx()))
                if (d === PhysicalLayouts.controllerFront) {
                    val (l, t, r, b) = PhysicalLayouts.TOUCHPAD
                    val pad = Offset(size.width * l, size.height * t)
                    val padSize = Size(size.width * (r - l), size.height * (b - t))
                    val corner = CornerRadius(8.dp.toPx())
                    drawRoundRect(edge.copy(alpha = 0.35f), pad, padSize, corner)
                    drawRoundRect(edge, pad, padSize, corner, style = Stroke(width = 1.dp.toPx()))
                }
            }
            for (c in d.controls) {
                val enabled = has(c)
                ElementBox(
                    c.label,
                    if (enabled) summary(view, c.inputs, c.label) else null,
                    round = c.kind == PhysicalKind.STICK || c.kind == PhysicalKind.SYSTEM ||
                        (c.kind == PhysicalKind.FACE && c.id !in PhysicalLayouts.paddles),
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
                                k.label, summary(view, inputs, k.label), round = false, enabled = true,
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
                    c.label, summary(view, c.inputs, c.label), round = false, enabled = true,
                    modifier = Modifier.offset(w * (c.x - c.w / 2f), h * (c.y - c.h / 2f)).size(w * c.w, h * c.h),
                ) { onOpen(Element(c.id, c.label, false, c.inputs)) }
            }
        }
    }
}

private val MIN_KEY: Dp = 34.dp
