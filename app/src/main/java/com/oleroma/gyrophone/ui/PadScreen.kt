package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.runtime.GamepadRuntime
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val GRID = 0.02f
private const val NEW_BUTTON_SIZE = 0.14f
private const val NEW_STICK_SIZE = 0.3f

private enum class EditGesture { PRESS, MOVE, RESIZE, PINCH, EMPTY, NONE }

/**
 * Full-screen virtual game pad for the active profile. Multi-touch: each finger that lands on a
 * stick owns it until lifted; other fingers press whatever button they are over (sliding works).
 * Edit mode works on a draft profile: tap a control for its settings and mappings, drag to move,
 * drag the corner handle or pinch to resize. Done saves (a builtin becomes a custom copy).
 */
@Composable
fun PadScreen(runtime: GamepadRuntime) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<Profile?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(false) }
    val pressed = remember { mutableStateMapOf<String, Boolean>() }
    val stickPos = remember { mutableStateMapOf<String, Offset>() }
    val editing = draft != null
    val shown = draft ?: profile

    fun releaseAll() {
        for (id in pressed.keys.toList()) runtime.engine.setTouch(id, false)
        for (id in stickPos.keys.toList()) runtime.engine.setTouchStick(id, 0f, 0f)
        pressed.clear()
        stickPos.clear()
    }

    DisposableEffect(Unit) { onDispose { releaseAll() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101318))) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        fun center(c: TouchControl) = Offset(c.x * w, c.y * h)
        fun half(c: TouchControl) = c.size * h / 2f
        fun hit(controls: List<TouchControl>, p: Offset): TouchControl? =
            controls.lastOrNull { it.contains(p.x, p.y, w, h) }
        fun handleCenter(c: TouchControl): Offset {
            val d = if (c.shape == TouchShape.CIRCLE) half(c) * 0.7071f else half(c)
            return center(c) + Offset(d, d)
        }

        /** Keeps the whole control on screen, optionally snapping its centre to the grid. */
        fun place(c: TouchControl, x: Float, y: Float, snapToGrid: Boolean): TouchControl {
            val sx = if (snapToGrid) (x / GRID).roundToInt() * GRID else x
            val sy = if (snapToGrid) (y / GRID).roundToInt() * GRID else y
            val hx = (half(c) / w).coerceAtMost(0.5f)
            val hy = (half(c) / h).coerceAtMost(0.5f)
            return c.copy(x = sx.coerceIn(hx, 1f - hx), y = sy.coerceIn(hy, 1f - hy))
        }

        fun updateControl(id: String, change: (TouchControl) -> TouchControl) {
            val d = draft ?: return
            draft = d.copy(touchControls = d.touchControls.map { if (it.id == id) change(it) else it })
        }

        fun resize(id: String, size: Float) = updateControl(id) {
            val sized = it.copy(size = size.coerceIn(TouchControl.MIN_SIZE, TouchControl.MAX_SIZE))
            place(sized, sized.x, sized.y, false)
        }

        val touchModifier = if (editing) {
            Modifier.pointerInput("edit", w, h) {
                val slop = viewConfiguration.touchSlop
                val handleRadius = 28.dp.toPx()
                awaitPointerEventScope {
                    while (true) {
                        var event = awaitPointerEvent()
                        val first = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (first == null) {
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val controls = draft?.touchControls ?: emptyList()
                        val downPos = first.position
                        val pointer = first.id
                        val sel = controls.firstOrNull { it.id == selected }
                        var mode: EditGesture
                        var target: String? = null
                        var grab = Offset.Zero
                        var startSize = 0f
                        var pinchStart = 0f
                        var resizeK = 1f
                        var pinchA: PointerId? = null
                        var pinchB: PointerId? = null
                        if (sel != null && (downPos - handleCenter(sel)).getDistance() <= handleRadius) {
                            mode = EditGesture.RESIZE
                            target = sel.id
                            startSize = sel.size
                            resizeK = if (sel.shape == TouchShape.CIRCLE) 1.4142f else 1f
                        } else {
                            val c = hit(controls, downPos)
                            if (c != null) {
                                mode = EditGesture.PRESS
                                target = c.id
                                grab = downPos - center(c)
                            } else {
                                mode = EditGesture.EMPTY
                            }
                        }
                        event.changes.forEach { it.consume() }

                        while (true) {
                            event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            val main = event.changes.firstOrNull { it.id == pointer }

                            if (down.size >= 2 && mode != EditGesture.PINCH && mode != EditGesture.NONE) {
                                val id = target ?: selected
                                val c = draft?.touchControls?.firstOrNull { it.id == id }
                                val dist = (down[0].position - down[1].position).getDistance()
                                if (c != null && dist > 1f) {
                                    mode = EditGesture.PINCH
                                    pinchA = down[0].id
                                    pinchB = down[1].id
                                    target = c.id
                                    selected = c.id
                                    startSize = c.size
                                    pinchStart = dist
                                    dragging = false
                                } else {
                                    mode = EditGesture.NONE
                                }
                            }

                            when (mode) {
                                EditGesture.PRESS -> if (main != null && (main.position - downPos).getDistance() > slop) {
                                    mode = EditGesture.MOVE
                                    selected = target
                                    dragging = true
                                }
                                EditGesture.EMPTY -> if (main != null && (main.position - downPos).getDistance() > slop) {
                                    mode = EditGesture.NONE
                                }
                                else -> Unit
                            }

                            when (mode) {
                                EditGesture.MOVE -> {
                                    if (main == null || !main.pressed) {
                                        mode = EditGesture.NONE
                                        dragging = false
                                    } else {
                                        val id = target
                                        if (id != null) {
                                            val p = main.position - grab
                                            updateControl(id) { place(it, p.x / w, p.y / h, snap) }
                                        }
                                    }
                                }
                                EditGesture.RESIZE -> {
                                    if (main == null || !main.pressed) {
                                        mode = EditGesture.NONE
                                    } else {
                                        val id = target
                                        val delta = main.position - downPos
                                        if (id != null) resize(id, startSize + resizeK * (delta.x + delta.y) / h)
                                    }
                                }
                                EditGesture.PINCH -> {
                                    val a = down.firstOrNull { it.id == pinchA }
                                    val b = down.firstOrNull { it.id == pinchB }
                                    if (a == null || b == null) {
                                        mode = EditGesture.NONE
                                    } else {
                                        val id = target
                                        val dist = (a.position - b.position).getDistance()
                                        if (id != null) resize(id, startSize * dist / pinchStart)
                                    }
                                }
                                else -> Unit
                            }
                            event.changes.forEach { it.consume() }
                        }

                        when (mode) {
                            EditGesture.PRESS -> selected = target
                            EditGesture.EMPTY -> selected = null
                            else -> Unit
                        }
                        dragging = false
                    }
                }
            }
        } else {
            Modifier.pointerInput("play", profile, w, h) {
                releaseAll()
                val controls = profile.touchControls
                val stickOwner = HashMap<PointerId, String>()
                val stickCenters = HashMap<String, Offset>()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val buttonsDown = HashSet<String>()
                        for (ch in event.changes) {
                            if (ch.pressed && !ch.previousPressed) {
                                val c = hit(controls, ch.position)
                                if (c?.kind == TouchKind.STICK && c.id !in stickOwner.values) {
                                    stickOwner[ch.id] = c.id
                                    stickCenters[c.id] = center(c)
                                }
                            }
                            val stickId = stickOwner[ch.id]
                            if (!ch.pressed) {
                                if (stickId != null) {
                                    stickOwner.remove(ch.id)
                                    runtime.engine.setTouchStick(stickId, 0f, 0f)
                                    stickPos.remove(stickId)
                                }
                            } else if (stickId != null) {
                                val c = controls.firstOrNull { it.id == stickId }
                                if (c != null) {
                                    var d = (ch.position - stickCenters.getValue(stickId)) / half(c)
                                    val len = hypot(d.x, d.y)
                                    if (len > 1f) d /= len
                                    runtime.engine.setTouchStick(stickId, d.x, d.y)
                                    stickPos[stickId] = d
                                }
                            } else {
                                hit(controls, ch.position)?.takeIf { it.kind == TouchKind.BUTTON }?.let { buttonsDown += it.id }
                            }
                            ch.consume()
                        }
                        for (c in controls) if (c.kind == TouchKind.BUTTON) {
                            val down = c.id in buttonsDown
                            if ((pressed[c.id] == true) != down) {
                                if (down) pressed[c.id] = true else pressed.remove(c.id)
                                runtime.engine.setTouch(c.id, down)
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize().then(touchModifier)) {
            if (editing && snap) {
                Canvas(Modifier.fillMaxSize()) {
                    val n = (1f / GRID).roundToInt()
                    for (i in 1 until n) {
                        val major = i % 5 == 0
                        val color = Color.White.copy(alpha = if (major) 0.12f else 0.05f)
                        val x = i * GRID * size.width
                        val y = i * GRID * size.height
                        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
                        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                }
            }
            for (c in shown.touchControls) {
                val r = half(c)
                val ctr = center(c)
                val isSelected = editing && selected == c.id
                val isPressed = !editing && (pressed[c.id] == true || stickPos.containsKey(c.id))
                val tint = when {
                    isSelected -> Color(0xFFFFC857)
                    isPressed -> Color(0xFF7CC4FF)
                    else -> Color(0x66FFFFFF)
                }
                PadControl(
                    control = c,
                    label = controlLabel(shown, c, editing),
                    sizeDp = with(density) { (r * 2).toDp() },
                    tint = tint,
                    filled = isPressed || isSelected,
                    knob = stickPos[c.id] ?: Offset.Zero,
                    knobRadiusPx = r * 0.6f,
                    modifier = Modifier.offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - r).roundToInt()) },
                )
                if (isSelected && !dragging) {
                    val hc = handleCenter(c)
                    val handlePx = with(density) { 20.dp.toPx() }
                    Box(
                        Modifier
                            .offset { IntOffset((hc.x - handlePx / 2).roundToInt(), (hc.y - handlePx / 2).roundToInt()) }
                            .size(20.dp)
                            .background(Color(0xFFFFC857), CircleShape)
                            .border(2.dp, Color(0xFF101318), CircleShape),
                    )
                }
            }
        }

        if (!editing) {
            FilledTonalButton(
                onClick = {
                    releaseAll()
                    selected = null
                    dragging = false
                    draft = profile
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
            ) { Text("Edit layout") }
        } else {
            val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            Row(
                Modifier.align(Alignment.TopCenter).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(contentPadding = compact, onClick = addButton@{
                    val d = draft ?: return@addButton
                    val id = freshId(d, "btn")
                    val c = TouchControl(id, TouchKind.BUTTON, "", 0.5f, 0.5f, NEW_BUTTON_SIZE, TouchShape.CIRCLE)
                    draft = d.copy(touchControls = d.touchControls + place(c, c.x, c.y, false))
                    selected = id
                }) { Text("+ Button") }
                FilledTonalButton(contentPadding = compact, onClick = addStick@{
                    val d = draft ?: return@addStick
                    val id = freshId(d, "stick")
                    val c = TouchControl(id, TouchKind.STICK, "", 0.5f, 0.5f, NEW_STICK_SIZE, TouchShape.CIRCLE)
                    draft = d.copy(touchControls = d.touchControls + place(c, c.x, c.y, false))
                    selected = id
                }) { Text("+ Stick") }
                if (snap) {
                    FilledTonalButton(contentPadding = compact, onClick = { snap = false }) { Text("Snap: on") }
                } else {
                    OutlinedButton(contentPadding = compact, onClick = { snap = true }) { Text("Snap: off") }
                }
                OutlinedButton(contentPadding = compact, onClick = {
                    draft = null
                    selected = null
                    dragging = false
                }) { Text("Cancel") }
                FilledTonalButton(contentPadding = compact, onClick = done@{
                    val d = draft ?: return@done
                    if (d == runtime.activeProfile.value) {
                        Unit
                    } else if (d.id == runtime.activeProfile.value.id) {
                        runtime.editActiveProfile { d }
                    } else {
                        val saved = if (runtime.store.isBuiltin(d)) {
                            d.copy(id = "user." + UUID.randomUUID(), name = d.name + " (custom)")
                        } else d
                        runtime.saveProfile(saved)
                        runtime.activate(saved)
                    }
                    draft = null
                    selected = null
                    dragging = false
                }) { Text("Done") }
            }

            val d = draft
            val sel = d?.touchControls?.firstOrNull { it.id == selected }
            if (d != null && sel != null && !dragging) {
                val panelWidth = (maxWidth * 0.42f).coerceAtMost(360.dp)
                ControlSettingsPanel(
                    profile = d,
                    control = sel,
                    runtime = runtime,
                    width = panelWidth,
                    onProfileChange = { draft = it },
                    onControlChange = { changed -> updateControl(sel.id) { place(changed, changed.x, changed.y, false) } },
                    onSize = { resize(sel.id, it) },
                    onDuplicate = {
                        val (p, id) = duplicate(d, sel)
                        val copy = p.touchControls.first { it.id == id }
                        draft = p.copy(touchControls = p.touchControls.map { if (it.id == id) place(copy, copy.x, copy.y, false) else it })
                        selected = id
                    },
                    onDelete = {
                        draft = Bindings.removeTouchControl(d, sel.id)
                        selected = null
                    },
                    onClose = { selected = null },
                    modifier = Modifier
                        .align(if (sel.x < 0.5f) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(start = 8.dp, end = 8.dp, top = 56.dp, bottom = 8.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** Label shown on a control: its own, else its first mapping's short name. */
private fun controlLabel(profile: Profile, c: TouchControl, editing: Boolean): String {
    if (c.label.isNotBlank()) return c.label
    val first = Bindings.touchInputs(c).firstNotNullOfOrNull { Bindings.targets(profile, it).firstOrNull() }
    return first?.let { Describe.shortTarget(it) } ?: if (editing) "?" else ""
}

private fun shapeOf(c: TouchControl): Shape =
    if (c.shape == TouchShape.SQUARE) RoundedCornerShape(percent = 12) else CircleShape

/** Id not used by any control nor by leftover mappings. */
private fun freshId(profile: Profile, prefix: String): String {
    val orphans = profile.mappings.mapNotNull {
        when (val s = it.source) {
            is InputSource.Touch -> s.id
            is InputSource.TouchStick -> s.id
            else -> null
        }
    }.distinct().map { TouchControl(it, TouchKind.BUTTON, "", 0f, 0f) }
    return Bindings.newTouchId(profile.touchControls + orphans, prefix)
}

/** Copies a control and its mappings next to the original; returns the profile and the new id. */
private fun duplicate(profile: Profile, c: TouchControl): Pair<Profile, String> {
    val id = freshId(profile, if (c.kind == TouchKind.STICK) "stick" else "btn")
    val copies = profile.mappings.mapNotNull { m ->
        when (val s = m.source) {
            is InputSource.Touch -> if (s.id == c.id) m.copy(source = InputSource.Touch(id)) else null
            is InputSource.TouchStick -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            else -> null
        }
    }
    val dx = if (c.x < 0.5f) 0.05f else -0.05f
    val copy = c.copy(id = id, x = c.x + dx, y = c.y + 0.05f)
    return profile.copy(touchControls = profile.touchControls + copy, mappings = profile.mappings + copies) to id
}

@Composable
private fun PadControl(
    control: TouchControl,
    label: String,
    sizeDp: Dp,
    tint: Color,
    filled: Boolean,
    knob: Offset,
    knobRadiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val shape = shapeOf(control)
    Box(
        modifier
            .size(sizeDp)
            .border(2.dp, tint, shape)
            .background(if (filled) tint.copy(alpha = 0.25f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (control.kind == TouchKind.STICK) {
            Box(
                Modifier
                    .offset { IntOffset((knob.x * knobRadiusPx).roundToInt(), (knob.y * knobRadiusPx).roundToInt()) }
                    .size(sizeDp / 2.5f)
                    .background(tint.copy(alpha = 0.6f), CircleShape),
            )
        }
        Text(
            label,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Settings and mappings of the selected control, docked at a screen side. */
@Composable
private fun ControlSettingsPanel(
    profile: Profile,
    control: TouchControl,
    runtime: GamepadRuntime,
    width: Dp,
    onProfileChange: (Profile) -> Unit,
    onControlChange: (TouchControl) -> Unit,
    onSize: (Float) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(control)
    val inputs = Bindings.touchInputs(control)
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
                    (if (control.kind == TouchKind.STICK) "Stick" else "Button") + " · " + control.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("✕") }
            }
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
                SelectChip("Circle", control.shape == TouchShape.CIRCLE) { onControlChange(current.copy(shape = TouchShape.CIRCLE)) }
                SelectChip("Square", control.shape == TouchShape.SQUARE) { onControlChange(current.copy(shape = TouchShape.SQUARE)) }
            }
            Column {
                Text("Size ${(control.size * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = control.size,
                    onValueChange = onSize,
                    valueRange = TouchControl.MIN_SIZE..TouchControl.MAX_SIZE,
                )
            }
            HorizontalDivider()
            Text("Mapping", style = MaterialTheme.typography.titleSmall)
            if (control.kind == TouchKind.STICK) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (preset in Bindings.StickPreset.entries) {
                        SelectChip(preset.label, presetMatches(profile, inputs, preset)) {
                            onProfileChange(Bindings.applyStickPreset(profile, inputs, preset))
                        }
                    }
                }
            }
            BindingList(profile = profile, inputs = inputs, onChange = onProfileChange, runtime = runtime)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDuplicate) { Text("Duplicate") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** True when the stick's mappings equal what [preset] would write. */
private fun presetMatches(
    profile: Profile,
    inputs: List<BindableInput>,
    preset: Bindings.StickPreset,
): Boolean {
    val applied = Bindings.applyStickPreset(profile, inputs, preset)
    return inputs.take(6).all { Bindings.targets(applied, it).toSet() == Bindings.targets(profile, it).toSet() }
}

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
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
