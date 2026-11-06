package com.oleroma.gyrophone.ui

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.DevicePresets
import com.oleroma.gyrophone.core.model.HeldButton
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.PhysicalControl
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import com.oleroma.gyrophone.core.model.withSets
import com.oleroma.gyrophone.runtime.GamepadRuntime

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
    val board = remember(device, preset?.id) { ControlBoard() }
    var undo by remember { mutableStateOf<Pair<String, DevicePreset>?>(null) }
    val colors = MaterialTheme.colorScheme

    /** Adds a standard preset and picks it. */
    fun add() {
        runtime.editActiveProfile { p ->
            val id = DevicePresets.newId(p.devicePresets.map { it.id })
            pickedId = id
            DevicePresets.put(p, DevicePresets.standard(id, DevicePresets.newName(p.devicePresets.map { it.name })))
        }
    }

    // Clear of the camera cutout and system bars on every side (the background still fills behind them).
    Column(Modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                            "Each workspace uses one preset, or none: pick it in the workspace's edit mode.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    FilledTonalButton(onClick = { add() }) { Text("+ New preset") }
                }
            } else {
                val all = preset.mappings(device)
                val counts = remember(all, device) { HashMap<String, Int>() }
                fun lines(e: Element) = DevicePresets.remaps(device, all, e.inputs).map { r ->
                    val layer = if (r.set == MappingSet.BASE) "" else (ws.sets.firstOrNull { it.id == r.set }?.label ?: "Layer ${r.set}") + " · "
                    layer + r.input to r.targets.joinToString(", ") { Describe.shortTarget(it) }
                }
                board.wire(
                    count = { e -> counts.getOrPut(e.id) { DevicePresets.remaps(device, all, e.inputs).sumOf { it.targets.size } } },
                    onOpen = { editing = it },
                    onSwap = { a, b ->
                        val before = preset
                        runtime.editActiveProfile { p ->
                            val now = p.devicePresets.firstOrNull { it.id == preset.id } ?: return@editActiveProfile p
                            DevicePresets.put(p, DevicePresets.swap(now, device, a.id, a.inputs, b.id, b.inputs))
                        }
                        undo = "Swapped ${a.label} and ${b.label}" to before
                    },
                )
                BoardArea(board, Modifier.fillMaxSize()) {
                    when (device) {
                        PhysicalDevice.CONTROLLER -> {
                            // A back button can be picked when an attached pad has it, or none is attached to tell.
                            val has = { c: PhysicalControl ->
                                val keys = PhysicalLayouts.paddles[c.id]
                                keys == null || controllers.isEmpty() || controllers.any { p -> p.keys.isEmpty() || p.keys.any { it in keys } }
                            }
                            HandlesView(board, has)
                        }
                        PhysicalDevice.KEYBOARD -> KeyboardView(board)
                        PhysicalDevice.MOUSE -> MouseView(board)
                        PhysicalDevice.PHONE -> PhoneView(board)
                    }
                    BoardOverlay(
                        board,
                        lines = ::lines,
                        undo = undo?.first,
                        onUndo = {
                            val old = undo?.second
                            undo = null
                            if (old != null) runtime.editActiveProfile { DevicePresets.put(it, old) }
                        },
                        onUndoGone = { undo = null },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceSwitch(
                PhysicalDevice.entries,
                device,
                label = { it.label },
                icon = {
                    when (it) {
                        PhysicalDevice.CONTROLLER -> DeviceGlyph.PAD
                        PhysicalDevice.KEYBOARD -> DeviceGlyph.KEYBOARD
                        PhysicalDevice.MOUSE -> DeviceGlyph.MOUSE
                        PhysicalDevice.PHONE -> DeviceGlyph.PHONE
                    }
                },
                onPick = { deviceName = it.name },
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Tap: what it does · Hold: edit · Hold and drag: swap",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }

    val e = editing
    if (e != null && preset != null) {
        // A key or button (not a stick or a wheel axis): what presses it, for its settings and gyro while held.
        val held = e.inputs.firstOrNull { !it.analog }?.takeIf { !e.stick }?.let { PhysicalHeld(device.name + ":" + e.id, it.sources) }
        val editSet = if (ws.sets.any { it.id == mapSet }) mapSet else MappingSet.BASE
        ControlMappingScreen(
            control = physicalStandIn(device, e.id, e.label, e.stick),
            mappings = preset.mappings(device),
            runtime = runtime,
            onMappingsChange = { m ->
                runtime.editActiveProfile { p ->
                    val now = p.devicePresets.firstOrNull { it.id == preset.id } ?: return@editActiveProfile p
                    val next = now.withMappings(device, m)
                    // Gyro mapped while held needs what presses the button.
                    DevicePresets.put(p, if (held == null) next else next.syncHeld(held.id, held.sources))
                }
            },
            onSettings = null,
            onClose = { editing = null },
            sets = ws.sets,
            editSet = if (ws.sets.any { it.id == mapSet }) mapSet else MappingSet.BASE,
            onEditSet = { mapSet = it },
            onSetsChange = { sets, removed ->
                runtime.editActiveProfile { p ->
                    val next = p.updateWorkspace(current) { it.withSets(sets, removed) }
                    val now = next.devicePresets.firstOrNull { it.id == preset.id }
                    // The layer's mappings in the preset go too, and the Layer actions to it, unless
                    // another workspace using the preset still has a layer with that id.
                    if (removed == null || now == null ||
                        next.workspaces.any { w -> w.devicePreset == now.id && w.sets.any { it.id == removed } }) next
                    else DevicePresets.put(
                        next,
                        PhysicalDevice.entries.fold(now) { d, dev -> d.withMappings(dev, Bindings.dropSet(d.mappings(dev), removed)) },
                    )
                }
            },
            physicalInputs = e.inputs,
            physicalMotionId = held?.let { HeldButton.GYRO_ID + it.id },
            inherited = held?.let { h -> Inheritance.gyroInherited(Inheritance.workspace(ws, profile.defaults), HeldButton.GYRO_ID + h.id, editSet) }.orEmpty(),
            parentName = "Workspace",
            settings = physicalSettings(runtime, preset, ws, held, editSet) { change ->
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
                        if (users.isEmpty()) "No workspace uses it yet: pick it in a workspace's edit mode."
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

/** How a controller control is marked: no name on A B X Y and the D-pad, icons on View, Menu and Home. */
private fun markOf(c: PhysicalControl): Mark = when {
    c.kind == PhysicalKind.DPAD -> Mark.NONE
    c.id in setOf("a", "b", "x", "y") -> Mark.NONE
    c.id == "select" -> Mark.VIEW
    c.id == "start" -> Mark.MENU
    c.id == "home" -> Mark.HOME
    else -> Mark.NAME
}

/**
 * The controller drawn as the companion's overlay draws it: the phone with a handle on each side,
 * the triggers and bumpers above the handles, the back buttons and Home below
 * ([PhysicalLayouts.controllerHandles]), as large as fits. [has]: whether a control can be picked.
 */
@Composable
private fun HandlesView(board: ControlBoard, has: (PhysicalControl) -> Boolean) {
    val colors = MaterialTheme.colorScheme
    val body = colors.surfaceContainer
    val edge = colors.outlineVariant
    val screen = colors.surfaceContainerLowest
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On a narrow (portrait) screen no phone: the handles side by side, as large as fits.
        val phone = maxWidth > maxHeight
        val d = if (phone) PhysicalLayouts.controllerHandles else PhysicalLayouts.controllerHandlesNarrow
        val w = min(maxWidth, maxHeight * d.aspect)
        val h = w / d.aspect
        Box(Modifier.align(Alignment.Center).size(w, h)) {
            Canvas(Modifier.fillMaxSize()) {
                val u = size.height / PhysicalLayouts.HANDLES_H
                val top = PhysicalLayouts.HANDLES_TOP * u
                val hw = PhysicalLayouts.HANDLE_W * u
                val line = Stroke(width = 1.5.dp.toPx())
                // Each handle: big round corners outside, small ones against the phone.
                fun handle(x0: Float, outerLeft: Boolean): Path {
                    val ro = 0.12f * u
                    val ri = 0.015f * u
                    val (rl, rr) = if (outerLeft) ro to ri else ri to ro
                    return Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                Rect(x0, top, x0 + hw, top + u),
                                topLeft = CornerRadius(rl), topRight = CornerRadius(rr),
                                bottomRight = CornerRadius(rr), bottomLeft = CornerRadius(rl),
                            ),
                        )
                    }
                }
                for (p in listOf(handle(0f, true), handle(size.width - hw, false))) {
                    drawPath(p, body)
                    drawPath(p, edge, style = line)
                }
                if (phone) {
                    val at = Offset(hw, top)
                    val phoneSize = Size(size.width - 2 * hw, u)
                    drawRoundRect(body, at, phoneSize, CornerRadius(0.11f * u))
                    drawRoundRect(edge, at, phoneSize, CornerRadius(0.11f * u), style = line)
                    val inset = 0.04f * u
                    drawRoundRect(
                        screen, at + Offset(inset, inset), Size(phoneSize.width - 2 * inset, u - 2 * inset),
                        CornerRadius(0.08f * u),
                    )
                }
            }
            for (c in d.controls) {
                val round = c.kind == PhysicalKind.STICK || c.kind == PhysicalKind.SYSTEM ||
                    (c.kind == PhysicalKind.FACE && c.id !in PhysicalLayouts.paddles)
                DeviceControl(
                    board,
                    Element(c.id, c.label, c.kind == PhysicalKind.STICK, c.inputs, round, markOf(c)),
                    enabled = has(c),
                    modifier = Modifier.offset(w * (c.x - c.w / 2f), h * (c.y - c.h / 2f)).size(w * c.w, h * c.h),
                )
            }
        }
    }
}

/** A full keyboard: the width split in key units, at least [MIN_KEY] each (then it scrolls sideways). */
@Composable
private fun KeyboardView(board: ControlBoard) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rows = PhysicalLayouts.keyboardRows
        val unit = max(maxWidth / PhysicalLayouts.KEYBOARD_UNITS, MIN_KEY)
        val keyH = min(unit * 1.15f, maxHeight / rows.size)
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
                            DeviceControl(
                                board,
                                Element("key$code", k.label, false, PhysicalLayouts.buttonInputs(listOf(code))),
                                enabled = true,
                                modifier = Modifier.size(unit * k.width, keyH).padding(1.5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A mouse from above: its buttons, wheel, side buttons and movement. */
@Composable
private fun MouseView(board: ControlBoard) {
    val body = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.outlineVariant
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
                drawPath(outline, line, style = Stroke(width = 1.5.dp.toPx()))
                drawLine(line, Offset(sw * 0.5f, sh * 0.02f), Offset(sw * 0.5f, sh * 0.35f), strokeWidth = 1.5.dp.toPx())
            }
            for (c in PhysicalLayouts.mouseControls) {
                DeviceControl(
                    board,
                    Element(c.id, c.label, false, c.inputs),
                    enabled = true,
                    modifier = Modifier.offset(w * (c.x - c.w / 2f), h * (c.y - c.h / 2f)).size(w * c.w, h * c.h),
                )
            }
        }
    }
}

/**
 * The phone in landscape with its volume rocker on the top edge. The rocker acts while the pad is
 * open; a key mapped to nothing turns the PC's volume.
 */
@Composable
private fun PhoneView(board: ControlBoard) {
    val colors = MaterialTheme.colorScheme
    val body = colors.surfaceContainer
    val line = colors.outlineVariant
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = min(maxHeight, maxWidth / PhysicalLayouts.PHONE_ASPECT)
            val w = h * PhysicalLayouts.PHONE_ASPECT
            Box(Modifier.align(Alignment.Center).size(w, h)) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = size.width
                    val sh = size.height
                    val top = sh * PhysicalLayouts.PHONE_TOP
                    val r = CornerRadius(sh * 0.12f, sh * 0.12f)
                    val stroke = Stroke(width = 1.5.dp.toPx())
                    val at = Offset(sw * 0.02f, top)
                    val bodySize = Size(sw * 0.96f, sh - top - sh * 0.02f)
                    drawRoundRect(body, at, bodySize, r)
                    drawRoundRect(line, at, bodySize, r, style = stroke)
                    drawRoundRect(
                        line, Offset(sw * 0.06f, top + sh * 0.08f), Size(sw * 0.88f, sh - top - sh * 0.18f),
                        CornerRadius(sh * 0.06f, sh * 0.06f), style = stroke,
                    )
                }
                for (c in PhysicalLayouts.phoneControls) {
                    DeviceControl(
                        board,
                        Element(c.id, c.label, false, c.inputs),
                        enabled = true,
                        modifier = Modifier.offset(w * (c.x - c.w / 2f), h * (c.y - c.h / 2f)).size(w * c.w, h * c.h),
                    )
                }
            }
        }
        Text(
            "The volume rocker acts while the pad is open. A grey key turns the PC's volume.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val MIN_KEY: Dp = 34.dp
