package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.WorkspaceEdits
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** One capsule of the pad's top row: a workspace (by index in the profile), Controller, Settings or Connection. */
internal sealed interface Capsule {
    data class Ws(val index: Int) : Capsule
    /** The device screens: what physical devices do ([com.oleroma.gyrophone.core.model.DevicePreset]). */
    data object Devices : Capsule
    data object Settings : Capsule
    data object Connection : Capsule
}

/**
 * The capsules in row order from the saved [order] ('W' = a workspace slot, 'D', 'S', 'C'), filled with
 * [count] workspaces in profile order. Workspaces beyond the saved slots are new ones: they were
 * added at the front, so their slots go first; missing ones drop the last slots.
 */
internal fun capsules(order: String, count: Int): List<Capsule> {
    val seen = HashSet<Char>()
    val t = order.filter { it == 'W' || (it in "DSC" && seen.add(it)) }.toMutableList()
    // New since the order was saved: right before Settings.
    if ('D' !in t) t.add(t.indexOf('S').takeIf { it >= 0 } ?: t.size, 'D')
    if ('S' !in t) t.add('S')
    if ('C' !in t) t.add('C')
    var w = t.count { it == 'W' }
    while (w < count) { t.add(0, 'W'); w++ }
    while (w > count) { t.removeAt(t.lastIndexOf('W')); w-- }
    var next = 0
    return t.map {
        when (it) {
            'W' -> Capsule.Ws(next++)
            'D' -> Capsule.Devices
            'S' -> Capsule.Settings
            else -> Capsule.Connection
        }
    }
}

private fun encode(row: List<Capsule>): String = row.joinToString("") {
    when (it) {
        is Capsule.Ws -> "W"
        Capsule.Devices -> "D"
        Capsule.Settings -> "S"
        Capsule.Connection -> "C"
    }
}

/**
 * The pad's top row in play: + (add a workspace at the front), then the workspaces, Controller,
 * Settings and Connection in the saved order. It scrolls; long-press a capsule and drag it to reorder (moving
 * workspaces reorders them). Tap a workspace to switch to it, the current one to edit its layout.
 */
@Composable
internal fun CapsuleRow(
    runtime: GamepadRuntime,
    onEdit: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    onConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val order by runtime.capsuleOrder.collectAsStateWithLifecycle()
    val source = capsules(order, profile.workspaces.size)
    /** The live order while a capsule is dragged (a copy of [source] taken at drag start). */
    val row = remember { mutableStateListOf<Capsule>() }
    var dragged by remember { mutableStateOf<Capsule?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val widths = remember { mutableStateMapOf<Capsule, Int>() }
    val shown = if (dragged == null) source else row.toList()
    var adding by remember { mutableStateOf(false) }
    val gap = 8.dp
    val current by rememberUpdatedState(wsIndex)

    fun drop() {
        val moved = row.filterIsInstance<Capsule.Ws>().map { it.index }
        runtime.setCapsuleOrder(encode(row))
        if (moved != moved.sorted()) {
            val p = runtime.activeProfile.value
            val now = moved.indexOf(current)
            runtime.editActiveProfile { it.copy(workspaces = moved.map { i -> p.workspaces[i] }) }
            runtime.switchWorkspace(now)
        }
        dragged = null
        dragX = 0f
    }

    Row(
        modifier.horizontalScroll(rememberScrollState(), enabled = dragged == null),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleChip("+", selected = false, onTap = { adding = true })
        for (c in shown) key(c) {
            val label = when (c) {
                is Capsule.Ws -> profile.workspaces.getOrNull(c.index)?.name.orEmpty() + if (c.index == wsIndex) " ✎" else ""
                Capsule.Devices -> "Devices"
                Capsule.Settings -> "Settings"
                Capsule.Connection -> "Connection"
            }
            val isDragged = dragged == c
            CapsuleChip(
                label,
                icons = c == Capsule.Devices,
                selected = c is Capsule.Ws && c.index == wsIndex,
                modifier = Modifier
                    .onSizeChanged { widths[c] = it.width }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            translationX = dragX
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    }
                    .pointerInput(c) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                row.clear()
                                row.addAll(capsules(runtime.capsuleOrder.value, runtime.activeProfile.value.workspaces.size))
                                dragged = c
                                dragX = 0f
                            },
                            onDragEnd = { drop() },
                            onDragCancel = { drop() },
                        ) { change, drag ->
                            change.consume()
                            dragX += drag.x
                            // Swap with a neighbour once the capsule is past half of it.
                            val at = row.indexOf(c)
                            val step = gap.toPx()
                            if (dragX > 0 && at < row.lastIndex) {
                                val next = widths[row[at + 1]] ?: 0
                                if (dragX > next / 2f + step) {
                                    row.removeAt(at); row.add(at + 1, c)
                                    dragX -= next + step
                                }
                            } else if (dragX < 0 && at > 0) {
                                val prev = widths[row[at - 1]] ?: 0
                                if (-dragX > prev / 2f + step) {
                                    row.removeAt(at); row.add(at - 1, c)
                                    dragX += prev + step
                                }
                            }
                        }
                    },
                onTap = {
                    when (c) {
                        is Capsule.Ws -> if (c.index == wsIndex) onEdit() else runtime.switchWorkspace(c.index)
                        Capsule.Devices -> onDevices()
                        Capsule.Settings -> onSettings()
                        Capsule.Connection -> onConnection()
                    }
                },
            )
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add workspace") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val choices = listOf(
                        "Blank (touchpad only)" to BuiltinProfiles.blankWorkspace("Workspace ${profile.workspaces.size + 1}"),
                    ) + BuiltinProfiles.presets.map { it.name to it }
                    for ((label, w) in choices) {
                        Text(
                            label,
                            Modifier.fillMaxWidth().clickable {
                                adding = false
                                runtime.editActiveProfile { WorkspaceEdits.add(it, w, at = 0).first }
                                runtime.switchWorkspace(0)
                            }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CapsuleChip(
    text: String,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    icons: Boolean = false,
) {
    val tap by rememberUpdatedState(onTap)
    Surface(
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color(0xE02B313A),
    ) {
        if (icons) {
            DeviceIcons(
                Color.White,
                Modifier.semantics { contentDescription = text }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            return@Surface
        }
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A game pad, a keyboard and a mouse, drawn in [color] side by side, each about a line of text tall. */
@Composable
private fun DeviceIcons(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(66.dp, 20.dp)) {
        val line = 1.6.dp.toPx()
        val stroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val u = size.height / 20f
        // Game pad: a body with two grips, a D-pad cross on the left and two buttons on the right.
        val pad = Path().apply {
            moveTo(6 * u, 5 * u)
            lineTo(18 * u, 5 * u)
            cubicTo(22 * u, 5 * u, 24 * u, 12 * u, 23.5f * u, 15 * u)
            cubicTo(23 * u, 17.5f * u, 20 * u, 17.5f * u, 18.5f * u, 15 * u)
            lineTo(17 * u, 13 * u)
            lineTo(7 * u, 13 * u)
            lineTo(5.5f * u, 15 * u)
            cubicTo(4 * u, 17.5f * u, 1 * u, 17.5f * u, 0.5f * u, 15 * u)
            cubicTo(0f, 12 * u, 2 * u, 5 * u, 6 * u, 5 * u)
            close()
        }
        drawPath(pad, color, style = stroke)
        drawLine(color, Offset(5 * u, 9 * u), Offset(9 * u, 9 * u), line, StrokeCap.Round)
        drawLine(color, Offset(7 * u, 7 * u), Offset(7 * u, 11 * u), line, StrokeCap.Round)
        drawCircle(color, 1.1f * u, Offset(16 * u, 8 * u))
        drawCircle(color, 1.1f * u, Offset(18.5f * u, 10.5f * u))
        // Keyboard: a slab with two rows of keys and a space bar.
        val kx = 28 * u
        drawRoundRect(color, Offset(kx, 4 * u), Size(22 * u, 13 * u), CornerRadius(2 * u), style = stroke)
        for (row in 0..1) for (k in 0..4) {
            drawCircle(color, 0.9f * u, Offset(kx + 3.5f * u + k * 3.75f * u, 7.5f * u + row * 3 * u))
        }
        drawLine(color, Offset(kx + 6 * u, 13.8f * u), Offset(kx + 16 * u, 13.8f * u), line, StrokeCap.Round)
        // Mouse: a tall rounded body split at the top into two buttons.
        val mx = 56 * u
        drawRoundRect(color, Offset(mx, 2 * u), Size(10 * u, 16 * u), CornerRadius(5 * u), style = stroke)
        drawLine(color, Offset(mx + 5 * u, 2.5f * u), Offset(mx + 5 * u, 8 * u), line, StrokeCap.Round)
        drawLine(color, Offset(mx + 0.5f * u, 8 * u), Offset(mx + 9.5f * u, 8 * u), line, StrokeCap.Round)
    }
}
