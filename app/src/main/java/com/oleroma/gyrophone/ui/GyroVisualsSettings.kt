package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.core.model.BallShape
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayColor
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.TouchControl
import kotlin.math.roundToInt

/** Settings of the parallax edge ([GyroEdgeLayer]); [set] saves a changed [v]. The other gyro displays are placed in edit mode. */
@Composable
fun GyroVisualsSettings(v: TouchVisuals, set: (TouchVisuals) -> Unit) {
    Text("Gyro display", style = MaterialTheme.typography.titleSmall)
    Hint("3D phone, ratchet ring, speed graph and attitude indicator: add them in edit mode, like buttons.")
    SwitchRow("Parallax edge", v.gyroEdge) { set(v.copy(gyroEdge = it)) }
    if (v.gyroEdge) Indented {
        Chips(listOf("Turn" to false, "Tilt" to true), v.gyroEdgeTilt) { set(v.copy(gyroEdgeTilt = it)) }
        Hint(
            if (v.gyroEdgeTilt) "It shifts while the phone is tilted against gravity and stays there."
            else "It slides as the phone turns and springs back once it stops.",
        )
        IntSlider("Layers %d", v.gyroEdgeLayers, 1..5) { set(v.copy(gyroEdgeLayers = it)) }
        PercentSlider("Strength", v.gyroEdgeStrength, 0.2f..4f) { set(v.copy(gyroEdgeStrength = it)) }
        if (v.gyroEdgeTilt) {
            IntSlider("Level at %d°", v.gyroLevelDeg, 0..180, step = 5) { set(v.copy(gyroLevelDeg = it)) }
            Hint("The tilt that leaves it centred: 0° upright facing you, 90° flat with the screen up, 180° upright facing away.")
        } else {
            IntSlider("Spring back %d ms", v.gyroEdgeSpringMs, 100..1500, step = 50) { set(v.copy(gyroEdgeSpringMs = it)) }
        }
        FloatSlider("Line %.1f dp", v.gyroEdgeWidthDp, 0.5f..8f, 0.5f) { set(v.copy(gyroEdgeWidthDp = it)) }
        FloatSlider("Inset %.0f dp", v.gyroEdgeInsetDp, 0f..60f, 1f) { set(v.copy(gyroEdgeInsetDp = it)) }
        PercentSlider("Opacity", v.gyroOpacity, 0.1f..1f) { set(v.copy(gyroOpacity = it)) }
        SwitchRow("Move with the phone", v.gyroEdgeInvert) { set(v.copy(gyroEdgeInvert = it)) }
        SwitchRow("Tint by ratchet", v.gyroEdgeRatchetTint) { set(v.copy(gyroEdgeRatchetTint = it)) }
        Hint("Off: the frame lags behind the phone, like a window onto a still world. Tint: it dims and takes the live axes' colours while a ratchet pauses some.")
    }
}

/**
 * Full-screen settings of a gyro display [control] (a long press or Configure in edit mode):
 * what it shows, how, and its colours. Edits go through [onChange].
 */
@Composable
internal fun GyroDisplaySettingsScreen(
    control: TouchControl,
    onChange: (TouchControl) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val d = control.display ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<GyroDisplayColor?>(null) }
    fun set(nd: GyroDisplay) = onChange(control.copy(display = nd))
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.kind.label + " · settings",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDuplicate) { Text("Duplicate") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val narrow = Modifier.widthIn(max = 720.dp)
                    Column(narrow, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (d.kind) {
                            GyroDisplayKind.PHONE -> {
                                PoseSettings(d) { set(it) }
                                SwitchRow("Ghost trail", d.ghosts) { set(d.copy(ghosts = it)) }
                                SwitchRow("Axis arrows", d.axes) { set(d.copy(axes = it)) }
                                Hint("Arrows along the phone's own right, up and screen directions.")
                            }

                            GyroDisplayKind.RATCHET_RING ->
                                Hint("Yaw, pitch and roll arcs, lit while that axis moves the mouse and dim while a ratchet (or the gyro mode) pauses it.")

                            GyroDisplayKind.GRAPH -> {
                                Chips(listOf(180, 360, 720, 1440).map { "±$it°/s" to it }, d.graphRange) { set(d.copy(graphRange = it)) }
                                Chips(listOf(2, 3, 5, 8).map { "$it s" to it }, d.graphSeconds) { set(d.copy(graphSeconds = it)) }
                                Hint("Yaw, pitch and roll speed; shaded while a ratchet is held.")
                            }

                            GyroDisplayKind.HORIZON -> {
                                Hint("It tilts with roll and moves with pitch. The tape on top shows the heading.")
                                PoseSettings(d) { set(it) }
                            }

                            GyroDisplayKind.DISK -> {
                                Hint("The dot moves off centre where the screen tilts; the disk and its marks spin with the heading.")
                                PoseSettings(d) { set(it) }
                                IntSlider("Dot on the rim at %d°", d.diskRange, 10..90, step = 5) { set(d.copy(diskRange = it)) }
                                SwitchRow("Crosshair", d.crosshair) { set(d.copy(crosshair = it)) }
                            }

                            GyroDisplayKind.BALL -> {
                                val shape = d.ballShape
                                Hint("A shape turned with the phone: the yellow mark shows where the screen faces, the red one its top.")
                                Chips(BallShape.entries.map { it.label to it }, shape) { set(d.copy(shape = it, icosphere = false)) }
                                if (shape == BallShape.ICOSPHERE) {
                                    IntSlider("Subdivision %d", d.subdivision, 0..Meshes.MAX_SUBDIVISION) { set(d.copy(subdivision = it)) }
                                }
                                if (shape != BallShape.PLANE) {
                                    Chips(listOf("Smooth" to true, "Polygonal" to false), d.smooth) { set(d.copy(smooth = it)) }
                                    Hint(
                                        when (shape) {
                                            BallShape.GLOBE, BallShape.TORUS, BallShape.GIMBAL -> "Smooth: round lines. Polygonal: a few straight sides."
                                            else -> "Smooth: every edge bent onto a ball. Polygonal: straight edges and flat faces."
                                        },
                                    )
                                    SwitchRow("Show hidden edges", d.hiddenEdges) { set(d.copy(hiddenEdges = it)) }
                                }
                                if (shape != BallShape.GIMBAL) SwitchRow("Fill faces", d.fillFaces) { set(d.copy(fillFaces = it)) }
                                SwitchRow("Trackball", d.trackball) { set(d.copy(trackball = it)) }
                                if (d.trackball) Indented {
                                    Hint(
                                        "While the gyro moves the mouse, the ball holds still against the world, so it rolls the other way on screen, " +
                                            "like a trackball under your hand. A ratchet stops it (only the axes it pauses), and it turns with the phone.",
                                    )
                                    FloatSlider("Spin %.2f×", d.trackballSpin, 0.25f..4f, 0.25f) { set(d.copy(trackballSpin = it)) }
                                    SwitchRow("Follow gyro sensitivity", d.trackballFollowSensitivity) { set(d.copy(trackballFollowSensitivity = it)) }
                                    Hint("Each axis also spins by its gyro sensitivity against the default, so the ball keeps pace with the cursor.")
                                    LevelSlider(d) { set(it) }
                                } else PoseSettings(d) { set(it) }
                                if (shape == BallShape.GLOBE) SwitchRow("Equator", d.equator) { set(d.copy(equator = it)) }
                                SwitchRow("Screen mark", d.screenMark) { set(d.copy(screenMark = it)) }
                                SwitchRow("Top mark", d.topMark) { set(d.copy(topMark = it)) }
                            }
                        }
                        PercentSlider("Opacity", d.opacity, 0.1f..1f) { set(d.copy(opacity = it)) }
                        Text("Colours", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        for (c in GyroDisplayColor.of(d.kind).filter { shown(d, it) }) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picking = c }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Swatch(Color(d.color(c).toInt()))
                                Text(c.label, Modifier.padding(start = 12.dp).weight(1f))
                                if (c.key in d.colors) TextButton(onClick = { set(d.withColor(c, null)) }) { Text("Reset") }
                            }
                        }
                        Hint("Resize it with a pinch in edit mode; it always keeps its shape.")
                    }
                }
            }
        }
    }
    picking?.let { c ->
        ColorPickerDialog(
            title = c.label,
            initial = Color(d.color(c).toInt()),
            onPick = { set(d.withColor(c, it.toArgb().toLong() and 0xFFFFFFFFL)); picking = null },
            onDismiss = { picking = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${d.kind.label.lowercase()}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** The settings every pose display has ([GyroDisplayKind.pose]): gravity, compass, reset, ease back and level. */
@Composable
private fun PoseSettings(d: GyroDisplay, set: (GyroDisplay) -> Unit) {
    SwitchRow("Gravity", d.gravity) { set(d.copy(gravity = it)) }
    Hint(
        if (d.gravity) "Its tilt follows gravity, so it never drifts. Only the heading (the turn left or right) can drift, reset or ease back."
        else "It turns as the gyro moves the mouse (paused axes stay still) and can drift over time.",
    )
    if (d.gravity) {
        SwitchRow("Compass", d.compass) { set(d.copy(compass = it)) }
        if (d.compass) Hint("The heading is held to north by the magnetometer: 0° is you facing north. Reset and ease back leave it alone.")
    }
    if (!d.needsCompass) {
        SwitchRow("Reset on ratchet", d.resetOnRatchet) { set(d.copy(resetOnRatchet = it)) }
        if (d.resetOnRatchet && !d.gravity) Indented {
            Chips(listOf("Whole pose" to false, "Heading only" to true), d.resetHeadingOnly) { set(d.copy(resetHeadingOnly = it)) }
        }
        SwitchRow("Ease back", d.ease) { set(d.copy(ease = it)) }
        if (d.ease) Indented {
            Hint(if (d.gravity) "The heading goes back to straight on its own." else "It goes back to straight on its own.")
            Chips(listOf("When the phone stops" to false, "When the ratchet is let go" to true), d.easeAfterRatchet) {
                set(d.copy(easeAfterRatchet = it))
            }
            IntSlider("Starting after %d ms", d.easeDelayMs, 0..3000, step = 50) { set(d.copy(easeDelayMs = it)) }
            Chips(listOf("By time" to false, "By speed" to true), d.easeBySpeed) { set(d.copy(easeBySpeed = it)) }
            if (d.easeBySpeed) {
                IntSlider("At %d°/s", d.easeSpeed, 10..720, step = 10) { set(d.copy(easeSpeed = it)) }
            } else {
                FloatSlider("Back in %.1f s", d.easeSeconds, 0.1f..5f, 0.1f) { set(d.copy(easeSeconds = it)) }
            }
        }
    }
    LevelSlider(d, set)
}

/** Colour [c] is drawn on [d] (a 3D shape only uses those of its shape). */
private fun shown(d: GyroDisplay, c: GyroDisplayColor): Boolean = when (c) {
    GyroDisplayColor.BALL_GIMBAL_YAW, GyroDisplayColor.BALL_GIMBAL_PITCH, GyroDisplayColor.BALL_GIMBAL_ROLL -> d.ballShape == BallShape.GIMBAL
    GyroDisplayColor.BALL_LINES -> d.ballShape != BallShape.GIMBAL
    GyroDisplayColor.BALL_BODY -> d.ballShape != BallShape.GIMBAL && d.fillFaces
    GyroDisplayColor.BALL_EQUATOR -> d.ballShape == BallShape.GLOBE && d.equator
    GyroDisplayColor.BALL_MARK -> d.screenMark
    GyroDisplayColor.BALL_POLE -> d.topMark
    else -> true
}

@Composable
private fun LevelSlider(d: GyroDisplay, set: (GyroDisplay) -> Unit) {
    IntSlider("Level at %d°", d.levelDeg, 0..180, step = 5) { set(d.copy(levelDeg = it)) }
    Hint("The real pose shown square on: 0° upright facing you, 90° flat with the screen up, 180° upright facing away. Set it to how you hold the phone.")
}

/** A colour on a checkerboard, so its transparency shows. */
@Composable
private fun Swatch(c: Color, size: Int = 32) {
    Canvas(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color.Gray, RoundedCornerShape(6.dp))) {
        val cell = this.size.width / 4
        for (i in 0 until 4) for (j in 0 until 4) {
            drawRect(if ((i + j) % 2 == 0) Color.LightGray else Color.White, Offset(i * cell, j * cell), androidx.compose.ui.geometry.Size(cell, cell))
        }
        drawRect(c)
    }
}

/**
 * Picks a colour: a saturation / brightness square, a hue bar and an opacity bar side by side
 * (so it fits a landscape screen), the old and new colour, an ARGB hex field and a few presets.
 * [onPick] gets the result.
 */
@Composable
private fun ColorPickerDialog(title: String, initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(initial.alpha) }
    fun current() = Color.hsv(hue, sat, value, alpha)
    var hex by remember { mutableStateOf(hexOf(initial)) }
    fun sync() {
        hex = hexOf(current())
    }
    fun set(c: Color) {
        val h = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), h)
        // Keep the hue on greys, where it is undefined.
        if (h[1] > 0f && h[2] > 0f) hue = h[0]
        sat = h[1]; value = h[2]; alpha = c.alpha
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 680.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.height(200.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Saturation across, brightness down.
                    Canvas(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .pickDrag { p, sz ->
                                sat = (p.x / sz.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / sz.height).coerceIn(0f, 1f)
                                sync()
                            },
                    ) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val p = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                        drawCircle(Color.Black, 11.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                    }
                    // Hue, top to bottom.
                    Canvas(
                        Modifier
                            .width(32.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .pickDrag { p, sz ->
                                hue = (p.y / sz.height).coerceIn(0f, 1f) * 360f
                                sync()
                            },
                    ) {
                        drawRect(Brush.verticalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                        barMark(hue / 360f)
                    }
                    // Opacity, opaque at the top.
                    Canvas(
                        Modifier
                            .width(32.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .pickDrag { p, sz ->
                                alpha = 1f - (p.y / sz.height).coerceIn(0f, 1f)
                                sync()
                            },
                    ) {
                        checkers(8.dp.toPx())
                        val solid = Color.hsv(hue, sat, value)
                        drawRect(Brush.verticalGradient(listOf(solid, solid.copy(alpha = 0f))))
                        barMark(1f - alpha)
                    }
                    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Old above new; tap the old one to go back to it.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clickable { set(initial); sync() }) { Swatch(initial, 40) }
                            Swatch(current(), 40)
                            Text("%.0f%%".format(alpha * 100), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = hex,
                            onValueChange = { t ->
                                hex = t
                                parseHex(t)?.let { set(it) }
                            },
                            label = { Text("ARGB hex") },
                            singleLine = true,
                        )
                        for (row in PRESETS.chunked(5)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (argb in row) {
                                    val c = Color(argb.toInt())
                                    Box(Modifier.clickable { set(c.copy(alpha = alpha)); sync() }) { Swatch(c, 24) }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(current()) }) { Text("OK") }
                }
            }
        }
    }
}

/** Calls [at] with the finger's spot and the element's size on touch down and while dragging. */
private fun Modifier.pickDrag(at: (Offset, IntSize) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        at(down.position, size)
        drag(down.id) {
            it.consume()
            at(it.position, size)
        }
    }
}

/** A light / grey checkerboard, so transparency shows. */
private fun DrawScope.checkers(cell: Float) {
    var y = 0f
    var j = 0
    while (y < size.height) {
        var x = 0f
        var i = 0
        while (x < size.width) {
            drawRect(if ((i + j) % 2 == 0) Color.LightGray else Color.White, Offset(x, y), Size(cell, cell))
            x += cell; i++
        }
        y += cell; j++
    }
}

/** The marker across a vertical bar at [f] (0 top, 1 bottom). */
private fun DrawScope.barMark(f: Float) {
    val y = f * size.height
    drawRect(Color.White, Offset(0f, y - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), style = Stroke(2.dp.toPx()))
    drawRect(Color.Black, Offset(0f, y - 4.dp.toPx()), Size(size.width, 8.dp.toPx()), style = Stroke(1.dp.toPx()))
}

/** Quick picks: greys, then bright and soft hues. */
private val PRESETS = listOf(
    0xFFFFFFFFL, 0xFFBDBDBDL, 0xFF757575L, 0xFF303030L, 0xFF000000L,
    0xFFF44336L, 0xFFFF9800L, 0xFFFFEB3BL, 0xFF4CAF50L, 0xFF2196F3L,
    0xFFE91E63L, 0xFF9C27B0L, 0xFF00BCD4L, 0xFF8BC34AL, 0xFFFFC857L,
)

private fun hexOf(c: Color) = "%08X".format(c.toArgb())

/** RRGGBB or AARRGGBB, with or without #; null when it is neither. */
private fun parseHex(t: String): Color? {
    val s = t.trim().removePrefix("#")
    val v = s.toLongOrNull(16) ?: return null
    return when (s.length) {
        6 -> Color((0xFF000000 or v).toInt())
        8 -> Color(v.toInt())
        else -> null
    }
}

@Composable
private fun Indented(content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun <T> Chips(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = selected == value, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

/** A slider that saves on release, [format] showing its value. */
@Composable
private fun SavedSlider(
    format: (Float) -> String, value: Float, range: ClosedFloatingPointRange<Float>, snap: (Float) -> Float, onDone: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(format(v), Modifier.padding(end = 8.dp))
        Slider(value = v, onValueChange = { v = snap(it) }, valueRange = range, onValueChangeFinished = { onDone(v) })
    }
}

@Composable
private fun PercentSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) =
    SavedSlider({ "$title %.0f%%".format(it * 100) }, value, range, { (it * 20).roundToInt() / 20f }, onDone)

@Composable
private fun FloatSlider(format: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onDone: (Float) -> Unit) =
    SavedSlider({ format.format(it) }, value, range, { (it / step).roundToInt() * step }, onDone)

@Composable
private fun IntSlider(format: String, value: Int, range: IntRange, step: Int = 1, onDone: (Int) -> Unit) =
    SavedSlider(
        { format.format(it.roundToInt()) }, value.toFloat(), range.first.toFloat()..range.last.toFloat(),
        { (it / step).roundToInt() * step.toFloat() }, { onDone(it.roundToInt()) },
    )
