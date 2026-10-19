package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.runtime.GamepadRuntime
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen virtual game pad for the active profile. Multi-touch: each finger that lands on a
 * stick owns it until lifted; other fingers press whatever button they are over (sliding works).
 * Edit mode lets you drag controls anywhere and resize them, then saves a custom profile.
 */
@Composable
fun PadScreen(runtime: GamepadRuntime) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val controls = remember(profile) { mutableStateListOf<TouchControl>().apply { addAll(profile.touchControls) } }
    var selected by remember(profile) { mutableStateOf<String?>(null) }
    val pressed = remember(profile) { mutableStateMapOf<String, Boolean>() }
    val stickPos = remember(profile) { mutableStateMapOf<String, Offset>() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101318))) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        fun center(c: TouchControl) = Offset(c.x * w, c.y * h)
        fun radius(c: TouchControl) = c.size * h / 2f
        fun hit(p: Offset): TouchControl? = controls.lastOrNull { (p - center(it)).getDistance() <= radius(it) * 1.15f }

        val touchModifier = if (editing) {
            Modifier.pointerInput(profile, editing) {
                awaitPointerEventScope {
                    var dragging: Int = -1
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed && !change.previousPressed) {
                            val c = hit(change.position)
                            dragging = if (c == null) -1 else controls.indexOf(c)
                            selected = c?.id
                        } else if (change.pressed && dragging >= 0) {
                            val c = controls[dragging]
                            controls[dragging] = c.copy(
                                x = (change.position.x / w).coerceIn(0f, 1f),
                                y = (change.position.y / h).coerceIn(0f, 1f),
                            )
                        } else if (!change.pressed) dragging = -1
                        change.consume()
                    }
                }
            }
        } else {
            Modifier.pointerInput(profile, editing) {
                val stickOwner = HashMap<PointerId, String>()
                val stickCenters = HashMap<String, Offset>()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val buttonsDown = HashSet<String>()
                        for (ch in event.changes) {
                            if (ch.pressed && !ch.previousPressed) {
                                val c = hit(ch.position)
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
                                val c = controls.first { it.id == stickId }
                                val r = radius(c)
                                var d = (ch.position - stickCenters.getValue(stickId)) / r
                                val len = hypot(d.x, d.y)
                                if (len > 1f) d /= len
                                runtime.engine.setTouchStick(stickId, d.x, d.y)
                                stickPos[stickId] = d
                            } else {
                                hit(ch.position)?.takeIf { it.kind == TouchKind.BUTTON }?.let { buttonsDown += it.id }
                            }
                            ch.consume()
                        }
                        for (c in controls) if (c.kind == TouchKind.BUTTON) {
                            val down = c.id in buttonsDown
                            if ((pressed[c.id] == true) != down) {
                                pressed[c.id] = down
                                runtime.engine.setTouch(c.id, down)
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize().then(touchModifier)) {
            for (c in controls) {
                val r = radius(c)
                val ctr = center(c)
                val sizeDp = with(density) { (r * 2).toDp() }
                val isPressed = pressed[c.id] == true || stickPos.containsKey(c.id)
                val tint = when {
                    editing && selected == c.id -> Color(0xFFFFC857)
                    isPressed -> Color(0xFF7CC4FF)
                    else -> Color(0x66FFFFFF)
                }
                Box(
                    Modifier
                        .offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - r).roundToInt()) }
                        .size(sizeDp)
                        .border(2.dp, tint, CircleShape)
                        .background(if (isPressed) tint.copy(alpha = 0.25f) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (c.kind == TouchKind.STICK) {
                        val knob = stickPos[c.id] ?: Offset.Zero
                        val knobDp = sizeDp / 2.5f
                        Box(
                            Modifier
                                .offset { IntOffset((knob.x * r * 0.6f).roundToInt(), (knob.y * r * 0.6f).roundToInt()) }
                                .size(knobDp)
                                .background(tint.copy(alpha = 0.6f), CircleShape),
                        )
                    }
                    Text(c.label, color = Color.White)
                }
            }
        }

        Row(Modifier.align(Alignment.TopCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editing) {
                FilledTonalButton(onClick = { editing = true }) { Text("Edit layout") }
            } else {
                FilledTonalButton(onClick = {
                    selected?.let { id -> controls.replaceAll { if (it.id == id) it.copy(size = (it.size * 0.9f).coerceAtLeast(0.06f)) else it } }
                }) { Text("−") }
                FilledTonalButton(onClick = {
                    selected?.let { id -> controls.replaceAll { if (it.id == id) it.copy(size = (it.size * 1.1f).coerceAtMost(0.6f)) else it } }
                }) { Text("+") }
                FilledTonalButton(onClick = {
                    val id = "btn" + (controls.size + 1)
                    controls += TouchControl(id, TouchKind.BUTTON, id.uppercase(), 0.5f, 0.5f, 0.14f)
                    selected = id
                }) { Text("Add button") }
                FilledTonalButton(onClick = {
                    val base = profile
                    val custom = if (runtime.store.isBuiltin(base)) {
                        base.copy(id = "user." + UUID.randomUUID(), name = base.name + " (custom)")
                    } else base
                    runtime.saveProfile(custom.copy(touchControls = controls.toList()))
                    runtime.activate(custom.copy(touchControls = controls.toList()))
                    editing = false
                }) { Text("Save") }
                FilledTonalButton(onClick = {
                    controls.clear(); controls.addAll(profile.touchControls); editing = false
                }) { Text("Cancel") }
            }
        }
    }
}
