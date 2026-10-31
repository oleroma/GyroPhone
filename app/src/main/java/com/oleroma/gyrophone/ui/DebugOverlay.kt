package com.oleroma.gyrophone.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.debug.DebugTrace
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.runtime.PhoneDebug
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The pad's debug panel while Settings › Debug tools is on: link, gyro, workspace and layer, the
 * output now, and the newest events. Drag its title to move it; tap ▾ to fold it to one line.
 * Record saves the last 30 s ([PhoneDebug.record]).
 */
@Composable
fun DebugOverlay(runtime: GamepadRuntime, modifier: Modifier = Modifier) {
    val on by runtime.debug.enabled.collectAsStateWithLifecycle()
    val link by runtime.linkState.collectAsStateWithLifecycle()
    val g by runtime.gyroInfo.collectAsStateWithLifecycle()
    if (!on) return
    val trace = runtime.debug.trace ?: return
    var offset by remember { mutableStateOf(IntOffset(0, 0)) }
    var folded by remember { mutableStateOf(runtime.uiPref(PREF_FOLDED) == "1") }
    var paused by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var savedText by remember { mutableStateOf<String?>(null) }
    var frozen by remember { mutableStateOf<List<DebugTrace.Event>>(emptyList()) }
    // Kinds shown in the list (recordings keep everything); movement is hidden until turned on.
    var shownKinds by remember {
        mutableStateOf(
            runtime.uiPref(PREF_KINDS)?.split(',')?.mapNotNull { t -> DebugTrace.Kind.entries.firstOrNull { it.tag == t } }?.toSet()
                ?: (DebugTrace.Kind.entries.toSet() - DebugTrace.Kind.MOVE),
        )
    }

    LaunchedEffect(trace) {
        while (true) {
            delay(200)
            tick++
        }
    }
    LaunchedEffect(savedText) {
        if (savedText != null) {
            delay(4000); savedText = null
        }
    }

    // Reading tick redraws the panel every 200 ms.
    remember(tick) { tick } // reading tick redraws the panel every 200 ms
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xD0101418),
        modifier = modifier
            .offset { offset }
            .padding(8.dp)
            .widthIn(max = 420.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset = IntOffset(offset.x + drag.x.roundToInt(), offset.y + drag.y.roundToInt())
                    }
                },
            ) {
                Text("⠿ Debug", color = Color(0xFF9AD0FF), style = MaterialTheme.typography.labelMedium)
                Button(if (folded) "▸" else "▾") {
                    folded = !folded
                    runtime.setUiPref(PREF_FOLDED, if (folded) "1" else null)
                }
                Button("● Record", Color(0xFFFF8A80)) {
                    val f = runtime.debug.record()
                    savedText = if (f != null) "Saved ${f.name}" else "Debug tools are off"
                }
                Button(if (paused) "▶ Live" else "❚❚ Pause") {
                    paused = !paused
                    if (paused) frozen = trace.latest(EVENTS, shownKinds)
                }
            }
            savedText?.let { Text(it, color = Color(0xFFB9F6CA), style = mono) }
            val stats = runtime.linkStats()
            Line("link", PhoneDebug.describe(link).take(60), mono)
            if (!folded) {
                Line("", PhoneDebug.linkLine(stats), mono)
                val raw = trace.gyroRaw
                Line(
                    "gyro",
                    "${g.source} · ${trace.gyroPerSecond}/s · " +
                            "%+6.1f %+6.1f %+6.1f °/s".format(raw[0], raw[1], raw[2]) +
                            if (g.active) " · mouse" else "",
                    mono,
                )
                val ws = runtime.engine.workspace
                val layers = runtime.engine.layers
                fun name(id: Int) = if (id == MappingSet.BASE) "Base" else ws.sets.firstOrNull { it.id == id }?.label ?: "Layer $id"
                Line(
                    "where",
                    ws.name + " · " + (listOf(name(layers.set)) + layers.overrides.map { "+ " + name(it) }).joinToString(" "),
                    mono,
                )
                Line("out", PhoneDebug.outputLine(trace) + " · ${trace.snapshotsPerSecond} snapshots/s", mono)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in DebugTrace.Kind.entries) {
                        val on = k in shownKinds
                        Button((if (on) "☑ " else "☐ ") + k.tag, if (on) kindColor(k) else Color(0xFF757575)) {
                            shownKinds = if (on) shownKinds - k else shownKinds + k
                            runtime.setUiPref(PREF_KINDS, shownKinds.joinToString(",") { it.tag })
                            if (paused) frozen = trace.latest(EVENTS, shownKinds)
                        }
                    }
                }
                val events = (if (paused) frozen else trace.latest(EVENTS, shownKinds)).asReversed()
                for (e in events) {
                    Text(
                        "%-5s %s".format(e.kind.tag, e.text),
                        color = kindColor(e.kind),
                        style = mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Button(text: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun Line(label: String, text: String, style: androidx.compose.ui.text.TextStyle) {
    Text(
        (if (label.isEmpty()) "      " else "%-5s ".format(label)) + text,
        color = Color(0xFFE0E0E0),
        style = style,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun kindColor(k: DebugTrace.Kind): Color = when (k) {
    DebugTrace.Kind.IN -> Color(0xFFFFE082)
    DebugTrace.Kind.OUT -> Color(0xFF80CBC4)
    DebugTrace.Kind.MOVE -> Color(0xFF9E9E9E)
    DebugTrace.Kind.STATE -> Color(0xFFCE93D8)
    DebugTrace.Kind.LINK -> Color(0xFF90CAF9)
    DebugTrace.Kind.NOTE -> Color(0xFFBDBDBD)
}

private const val EVENTS = 12
private const val PREF_FOLDED = "debug_overlay_folded"
private const val PREF_KINDS = "debug_overlay_kinds"
