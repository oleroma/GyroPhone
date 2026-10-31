package com.oleroma.gyrophone.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import kotlin.math.max
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
 * The pad's debug panel while Settings › Debug tools is on: link, gyro, workspace and layer, what
 * is held (inputs and output), and the newest events. It keeps its place and size whatever it
 * shows: drag the title to move it, drag the ◢ corner to resize it, tap ▾ to fold it to the
 * link and what is held. It stays on screen. Record saves the last 30 s ([PhoneDebug.record]).
 */
@Composable
fun DebugOverlay(runtime: GamepadRuntime, modifier: Modifier = Modifier) {
    val on by runtime.debug.enabled.collectAsStateWithLifecycle()
    val link by runtime.linkState.collectAsStateWithLifecycle()
    val g by runtime.gyroInfo.collectAsStateWithLifecycle()
    if (!on) return
    val trace = runtime.debug.trace ?: return
    val density = LocalDensity.current
    // Place (px from the top left) and size (dp), remembered: "x,y,w,h".
    val stored = remember { runtime.uiPref(PREF_BOX)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 } }
    var pos by remember { mutableStateOf(Offset(stored?.get(0) ?: 0f, stored?.get(1) ?: Float.MAX_VALUE)) }
    var size by remember { mutableStateOf(DpSize((stored?.get(2) ?: 360f).dp, (stored?.get(3) ?: 260f).dp)) }
    var folded by remember { mutableStateOf(runtime.uiPref(PREF_FOLDED) == "1") }
    var paused by remember { mutableStateOf(false) }
    var timings by remember { mutableStateOf(runtime.uiPref(PREF_TIMINGS) == "1") }
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
    fun saveBox() = runtime.setUiPref(PREF_BOX, "${pos.x},${pos.y},${size.width.value},${size.height.value}")

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

    remember(tick) { tick } // reading tick redraws the panel every 200 ms
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val areaW = maxWidth
        val areaH = maxHeight
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val minSize = with(density) { DpSize(MIN_W.dp.coerceAtMost(maxWidth), MIN_H.dp.coerceAtMost(maxHeight)) }
        val w = size.width.coerceIn(minSize.width, maxWidth)
        val h = size.height.coerceIn(minSize.height, maxHeight)
        val wPx = with(density) { w.toPx() }
        // Folded, the panel is as tall as what it shows; the full height still decides where it may go.
        val hPx = with(density) { h.toPx() }
        val x = pos.x.coerceIn(0f, max(0f, maxW - wPx))
        val y = pos.y.coerceIn(0f, max(0f, maxH - hPx))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xD0101418),
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(w)
                .then(if (folded) Modifier.heightIn(max = h) else Modifier.height(h)),
        ) {
            Box {
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clipToBounds(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .pointerInput(Unit) {
                                detectDragGestures(onDragEnd = { saveBox() }) { change, drag ->
                                    change.consume()
                                    pos = Offset(
                                        (pos.x.coerceIn(0f, max(0f, maxW - wPx)) + drag.x).coerceIn(0f, max(0f, maxW - wPx)),
                                        (pos.y.coerceIn(0f, max(0f, maxH - hPx)) + drag.y).coerceIn(0f, max(0f, maxH - hPx)),
                                    )
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
                        Button(if (timings) "☑ ms" else "☐ ms") {
                            timings = !timings
                            runtime.setUiPref(PREF_TIMINGS, if (timings) "1" else null)
                        }
                        Button(if (paused) "▶ Live" else "❚❚ Pause") {
                            paused = !paused
                            if (paused) frozen = trace.latest(EVENTS, shownKinds)
                        }
                    }
                    savedText?.let { Text(it, color = Color(0xFFB9F6CA), style = mono) }
                    Line("link", PhoneDebug.describe(link).take(60), mono)
                    val held = runtime.engine.heldInputs()
                    Line("held", held.joinToString(" · ").ifEmpty { "nothing" }, mono, maxLines = 3)
                    Line("out", PhoneDebug.outputLine(trace), mono, maxLines = 3)
                    if (!folded) {
                        Line("", PhoneDebug.linkLine(runtime.linkStats()), mono)
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
                            ws.name + " · " + (listOf(name(layers.set)) + layers.overrides.map { "+ " + name(it) }).joinToString(" ") +
                                    " · ${trace.snapshotsPerSecond} snapshots/s",
                            mono,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (k in DebugTrace.Kind.entries) {
                                val shown = k in shownKinds
                                Button((if (shown) "☑ " else "☐ ") + k.tag, if (shown) kindColor(k) else Color(0xFF757575)) {
                                    shownKinds = if (shown) shownKinds - k else shownKinds + k
                                    runtime.setUiPref(PREF_KINDS, shownKinds.joinToString(",") { it.tag })
                                    if (paused) frozen = trace.latest(EVENTS, shownKinds)
                                }
                            }
                        }
                        // Newest first; rows that don't fit are cut off at the bottom (the oldest).
                        // With timings on, each row ends with the ms since the event below it.
                        val events = (if (paused) frozen else trace.latest(EVENTS, shownKinds)).asReversed()
                        Column(Modifier.weight(1f, fill = false).clipToBounds()) {
                            for ((i, e) in events.withIndex()) {
                                val gap = events.getOrNull(i + 1)?.let { " +${(e.nanos - it.nanos) / 1_000_000} ms" } ?: ""
                                Text(
                                    "%-5s %s".format(e.kind.tag, e.text) + if (timings) gap else "",
                                    color = kindColor(e.kind),
                                    style = mono,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
                // Resize corner.
                Text(
                    "◢",
                    color = Color(0xFF9AD0FF),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .padding(6.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { saveBox() }) { change, drag ->
                                change.consume()
                                val dw = with(density) { drag.x.toDp() }
                                val dh = with(density) { drag.y.toDp() }
                                size = DpSize(
                                    (size.width.coerceIn(minSize.width, areaW) + dw).coerceIn(minSize.width, areaW),
                                    (size.height.coerceIn(minSize.height, areaH) + dh).coerceIn(minSize.height, areaH),
                                )
                            }
                        },
                )
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
private fun Line(label: String, text: String, style: androidx.compose.ui.text.TextStyle, maxLines: Int = 2) {
    Text(
        (if (label.isEmpty()) "      " else "%-5s ".format(label)) + text,
        color = Color(0xFFE0E0E0),
        style = style,
        maxLines = maxLines,
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

private const val EVENTS = 60
private const val MIN_W = 220f
private const val MIN_H = 120f
private const val PREF_BOX = "debug_overlay_box"
private const val PREF_FOLDED = "debug_overlay_folded"
private const val PREF_KINDS = "debug_overlay_kinds"
private const val PREF_TIMINGS = "debug_overlay_timings"
