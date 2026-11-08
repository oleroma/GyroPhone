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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.core.model.TRACKBALL_AS_PHONE
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.core.engine.GyroPose
import com.oleroma.gyrophone.core.model.BallShape
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayColor
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TurnsWith
import kotlin.math.roundToInt

/** Settings of the parallax edge ([GyroEdgeLayer]); [set] saves a changed [v]. The other gyro displays are placed in edit mode. */
@Composable
fun GyroVisualsSettings(v: TouchVisuals, set: (TouchVisuals) -> Unit) {
    Hint("The other gyro displays (3D phone, ratchet ring, speed graph, attitude indicator, bubble disk, 3D shape, laser pointer): add them in edit mode, like buttons.")
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
 * what it shows, how, and its colours. Edits go through [onChange]. [gameKnown]: the workspace's
 * game turn is set (Gyro → Game), so a trackball's RWS is exact.
 */
@Composable
internal fun GyroDisplaySettingsScreen(
    runtime: GamepadRuntime,
    control: TouchControl,
    onChange: (TouchControl) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    gameKnown: Boolean = false,
) {
    val d = control.display ?: return
    // Level reads the accelerometer a moment later: it changes the control as it is then.
    val latest = rememberUpdatedState(control)
    var confirmDelete by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<GyroDisplayColor?>(null) }
    fun set(nd: GyroDisplay) = onChange(control.copy(display = nd))
    SettingsDialog(
        title = d.kind.label,
        subtitle = "Gyro display · settings",
        onClose = onClose,
        actions = {
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
    ) {
        // As the PC overlays' tabs: Look (what it shows, colours) and Turning (how the pose moves).
        val look = category(runtime, "gyroDisplayLook", "Look", "What it shows, opacity, colours", defaultOpen = true) {
            when (d.kind) {
                GyroDisplayKind.PHONE -> {
                    SwitchRow("Ghost trail", d.ghosts) { set(d.copy(ghosts = it)) }
                    SwitchRow("Axis arrows", d.axes) { set(d.copy(axes = it)) }
                    Hint("Arrows along the phone's own right, up and screen directions.")
                    SwitchRow("Axonometric view", d.axonometric, AXON_HINT) { set(d.copy(axonometric = it)) }
                }

                GyroDisplayKind.RATCHET_RING ->
                    Hint("Yaw, pitch and roll arcs, lit while that axis moves the mouse and dim while a ratchet (or the gyro mode) pauses it.")

                GyroDisplayKind.GRAPH -> {
                    Chips(listOf(180, 360, 720, 1440).map { "±$it°/s" to it }, d.graphRange) { set(d.copy(graphRange = it)) }
                    Chips(listOf(2, 3, 5, 8).map { "$it s" to it }, d.graphSeconds) { set(d.copy(graphSeconds = it)) }
                    Hint("Yaw, pitch and roll speed; shaded while a ratchet is held.")
                }

                GyroDisplayKind.HORIZON ->
                    Hint("It tilts with roll and moves with pitch. The tape on top shows the heading.")

                GyroDisplayKind.DISK -> {
                    GroupTitle("Bubble disk")
                    Hint("The dot moves off centre where the screen tilts; the disk and its marks spin with the heading.")
                    IntSlider("Dot on the rim at %d°", d.diskRange, 5..90, step = 5) { set(d.copy(diskRange = it)) }
                    SwitchRow("Crosshair", d.crosshair) { set(d.copy(crosshair = it)) }
                }

                GyroDisplayKind.BALL -> {
                    val shape = d.ballShape
                    GroupTitle("Shape")
                    Hint("A shape turned with the phone: the yellow mark shows where the screen faces, the red one its top.")
                    Chips(BallShape.entries.map { it.label to it }, shape) { set(d.copy(shape = it, icosphere = false)) }
                    if (shape == BallShape.ICOSPHERE) {
                        IntSlider("Subdivision %d", d.subdivision, 0..Meshes.MAX_SUBDIVISION) { set(d.copy(subdivision = it)) }
                    }
                    if (shape != BallShape.PLANE && shape != BallShape.CAMERA) {
                        SwitchRow("Smooth (edges bent onto a ball)", d.smooth) { set(d.copy(smooth = it)) }
                        SwitchRow("Edges round the back, faint", d.hiddenEdges) { set(d.copy(hiddenEdges = it)) }
                    }
                    if (shape != BallShape.GIMBAL) SwitchRow("Filled faces", d.fillFaces) { set(d.copy(fillFaces = it)) }
                    if (shape == BallShape.GLOBE) SwitchRow("Equator", d.equator) { set(d.copy(equator = it)) }
                    SwitchRow("Mark where the screen faces", d.screenMark) { set(d.copy(screenMark = it)) }
                    SwitchRow("Mark at the top", d.topMark) { set(d.copy(topMark = it)) }
                    SwitchRow("Axonometric view", d.axonometric, AXON_HINT) { set(d.copy(axonometric = it)) }
                }

                GyroDisplayKind.LASER -> {
                    GroupTitle("Laser pointer")
                    Hint("A see-through sphere seen from above and to the side. The laser at its centre shines out of the phone's back onto the inside; the tick at the dot shows which way the phone's top points. The beam dims while a ratchet is held.")
                    SwitchRow("Axonometric view", d.laserAxonometric, LASER_AXON_HINT) { set(d.copy(laserAxonometric = it)) }
                    SwitchRow("Grid every 15°", d.laserGrid) { set(d.copy(laserGrid = it)) }
                    SwitchRow("Axes (yaw red, pitch green, roll blue)", d.laserAxes) { set(d.copy(laserAxes = it)) }
                    GroupTitle("Draw")
                    SwitchRow("Draw mode", d.laserDraw, "The beam paints the inside of the sphere as you aim.") { set(d.copy(laserDraw = it)) }
                    if (d.laserDraw) Indented {
                        IntSlider("Brush %d°", d.laserBrush, 1..20) { set(d.copy(laserBrush = it)) }
                        Chips(LASER_FADES, d.laserFadeMs) {
                            set(d.copy(laserFadeMs = it))
                        }
                        SwitchRow("Clear on ratchet", d.laserClearOnRatchet, "A ratchet press clears the drawing.") { set(d.copy(laserClearOnRatchet = it)) }
                        Hint("Strokes glow, older ones darker. Their colour goes from Paint ahead (straight ahead) to Paint behind (turned all the way round), so a turn past 90° still reads. Nothing is painted while a ratchet is held. Turning Draw mode off clears the drawing. A Clear displays mapping (Other targets) clears every laser pointer at once.")
                    }
                    TextButton(onClick = { runtime.clearLaser(control.id) }) { Text("Clear") }
                }
            }
            PercentSlider("Opacity", d.opacity, 0.1f..1f) { set(d.copy(opacity = it)) }
            Hint("Resize it with a pinch in edit mode; it always keeps its shape.")
            GroupTitle("Colours")
            for (c in GyroDisplayColor.of(d.kind).filter { shown(d, it) }) Searchable(c.label, "colour color") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picking = c }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(Color(d.color(c).toInt()))
                    Text(c.label, Modifier.padding(start = 12.dp).weight(1f))
                    if (c.key in d.colors) TextButton(onClick = { set(d.withColor(c, null)) }) { Text("Reset") }
                }
            }
            if (d.colors.isNotEmpty()) TextButton(onClick = { set(d.copy(colors = emptyMap())) }) { Text("Default colours") }
        }
        val turning = if (!d.kind.pose) null else category(runtime, "gyroDisplayTurning", "Turning", "Turns with, gravity, ease back, turn amount, Level", defaultOpen = true) {
            GroupTitle("Turns with")
            Chips(TurnsWith.entries.map { it.label to it }, d.turnsWith) { set(d.copy(turnsWith = it)) }
            Hint(
                when (d.turnsWith) {
                    TurnsWith.PHONE -> "How you hold the phone."
                    TurnsWith.CAMERA -> "Where the game's camera points: it turns left and right and up and down as the camera does, never rolling, with the gyro and every other camera move (touchpad, buttons, controllers, keys, glides). A ratchet stops it, as it stops the camera."
                    TurnsWith.TRACKBALL -> "Like a ball rolled by your moves: each of its axes turns by the gyro turn picked below (as the phone, by default), and every other camera move rolls it too. A ratchet stops it."
                },
            )
            if (d.followsCamera) Indented {
                SwitchRow("As far as the game's camera", d.trackballRws) { set(d.copy(trackballRws = it)) }
                if (d.trackballRws) {
                    Hint(
                        if (gameKnown) "It turns exactly as far as the game's camera."
                        else "Set the game's turn in Gyro → Game for exact turns. Until then it turns as far as the phone, and every movement matches the gyro.",
                    )
                } else {
                    FloatSlider("Spin × %.1f", d.trackballSpin, 0.1f..10f, 0.1f) { set(d.copy(trackballSpin = it)) }
                    Hint("Every movement turns it Spin times as far as the phone turn that sends it.")
                }
            }
            if (d.turnsWith == TurnsWith.TRACKBALL) Indented { TrackballAxesSettings(d) { set(it) } }
            if (d.turnsWith == TurnsWith.PHONE) PoseSettings(d) { set(it) }
            // A trackball never eases back: every roll is a camera move.
            if (d.turnsWith == TurnsWith.CAMERA) {
                GroupTitle("Pose")
                EaseSettings(d) { set(it) }
            }
            GroupTitle("Amount")
            // Following the camera it turns by Spin (or as far as the game's camera) instead.
            if (!d.followsCamera) FloatSlider("Turn amount × %.1f", d.motionScale, 0.5f..3f, 0.1f) { set(d.copy(motionScale = it)) }
            Hint("Hold the phone the way you play and press Level: held that way, it shows face-on, its screen flat toward you.")
            TextButton(onClick = {
                if (!d.needsGravity) {
                    runtime.gyroFeed(control.id)?.restart()
                    if (d.rest != null) set(d.copy(rest = null))
                } else runtime.readUp { up ->
                    val rest = up?.let { GyroPose.tiltFromUp(it[0], it[1], it[2]) } ?: return@readUp
                    latest.value.display?.let { onChange(latest.value.copy(display = it.copy(rest = rest.toList()))) }
                }
            }) { Text("Level") }
        }
        val items = listOfNotNull(look, turning)
        ReorderableSections(runtime, "gyroDisplay", items)
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

/** The pose settings of a pose display ([GyroDisplayKind.pose]), as the PC overlays' Pose card: gravity, compass and ease back. */
@Composable
private fun PoseSettings(d: GyroDisplay, set: (GyroDisplay) -> Unit) {
    GroupTitle("Pose")
    SwitchRow("Gravity", d.gravity, "Tilt held to the world, so it never drifts.") { set(d.copy(gravity = it)) }
    if (d.gravity) Indented {
        SwitchRow("Compass", d.compass, "Heading held to where the phone faced.") { set(d.copy(compass = it)) }
    }
    if (d.needsCompass) {
        Hint("With the compass the heading never drifts, so it doesn't ease back; a ratchet press aims it again.")
        return
    }
    EaseSettings(d, set)
}

/** A trackball's axes: for each, the gyro turn that rolls it about that axis, and which way. */
@Composable
private fun TrackballAxesSettings(d: GyroDisplay, set: (GyroDisplay) -> Unit) {
    // Its bytes: 0 about up (shown as roll), 1 about right (pitch), 2 through the screen (shown as yaw).
    for ((i, name) in listOf(2 to "Yaw", 1 to "Pitch", 0 to "Roll")) {
        val code = d.trackballAxes shr (8 * i) and 0xFF
        fun put(c: Int) = set(d.copy(trackballAxes = (d.trackballAxes and (0xFF shl (8 * i)).inv()) or (c shl (8 * i))))
        Text("$name axis turns with", style = MaterialTheme.typography.bodyMedium)
        Chips(listOf("Yaw" to 3, "Pitch" to 2, "Roll" to 1, "None" to 0), code and 0x7F) { put(it or (code and 0x80)) }
        if (code and 0x7F != 0) SwitchRow("Invert $name axis", code and 0x80 != 0) { put((code and 0x7F) or (if (it) 0x80 else 0)) }
    }
    if (d.trackballAxes != TRACKBALL_AS_PHONE) TextButton(onClick = { set(d.copy(trackballAxes = TRACKBALL_AS_PHONE)) }) { Text("As the phone") }
}

/** Ease back to straight and its options (Phone and Camera; a trackball never eases back). */
@Composable
private fun EaseSettings(d: GyroDisplay, set: (GyroDisplay) -> Unit) {
    SwitchRow("Ease back to straight", d.ease) { set(d.copy(ease = it)) }
    if (d.ease) Indented {
        Chips(listOf("On ratchet" to true, "When the phone stops" to false), d.easeOnRatchet) { set(d.copy(easeOnRatchet = it)) }
        Hint(
            if (d.easeOnRatchet) "While a ratchet is held it eases back until straight, and stops when you let go."
            else "It eases back whenever the phone stops turning.",
        )
        // With gravity only the heading eases back.
        if (!d.needsGravity) SwitchRow("Heading only", d.easeHeadingOnly) { set(d.copy(easeHeadingOnly = it)) }
        IntSlider("Delay %d ms", d.easeDelayMs, 0..3000, step = 50) { set(d.copy(easeDelayMs = it)) }
        Chips(listOf("By time" to false, "By speed" to true), d.easeBySpeed) { set(d.copy(easeBySpeed = it)) }
        if (d.easeBySpeed) {
            IntSlider("Speed %d °/s", d.easeSpeed, 10..720, step = 10) { set(d.copy(easeSpeed = it)) }
        } else {
            FloatSlider("Back in %.1f s", d.easeSeconds, 0.1f..5f, 0.1f) { set(d.copy(easeSeconds = it)) }
        }
    }
}

/** A group's title inside a section, as a card title on the PC. */
@Composable
private fun GroupTitle(text: String) = NotSearching {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** Colour [c] is drawn on [d] (a 3D shape only uses those of its shape). */
private fun shown(d: GyroDisplay, c: GyroDisplayColor): Boolean = when (c) {
    GyroDisplayColor.BALL_GIMBAL_YAW, GyroDisplayColor.BALL_GIMBAL_PITCH, GyroDisplayColor.BALL_GIMBAL_ROLL -> d.ballShape == BallShape.GIMBAL
    GyroDisplayColor.BALL_LINES -> d.ballShape != BallShape.GIMBAL
    GyroDisplayColor.BALL_BODY -> d.ballShape != BallShape.GIMBAL && d.fillFaces
    GyroDisplayColor.BALL_EQUATOR -> d.ballShape == BallShape.GLOBE && d.equator
    GyroDisplayColor.BALL_MARK -> d.screenMark
    GyroDisplayColor.BALL_POLE -> d.topMark
    GyroDisplayColor.LASER_GRID -> d.laserGrid
    GyroDisplayColor.LASER_X, GyroDisplayColor.LASER_Y, GyroDisplayColor.LASER_Z -> d.laserAxes
    GyroDisplayColor.LASER_PAINT, GyroDisplayColor.LASER_PAINT_BEHIND -> d.laserDraw
    else -> true
}

private val LASER_FADES = listOf("Never fades" to 0, "0.25 s" to 250, "0.5 s" to 500, "1 s" to 1000, "2 s" to 2000, "5 s" to 5000, "10 s" to 10_000, "30 s" to 30_000, "60 s" to 60_000)
private const val LASER_AXON_HINT = "Seen from 30° round and 30° above, so a beam into the far side shows as a line. Off, face-on: a beam straight in is a dot."
private const val AXON_HINT = "Seen from 30° round and 30° above with the world's axes (yaw toward you red, pitch right green, roll up blue, as the gimbal's rings), as the laser pointer is, instead of face-on."

/** A colour on a checkerboard, so its transparency shows. */
@Composable
internal fun Swatch(c: Color, size: Int = 32) {
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
internal fun ColorPickerDialog(title: String, initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
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
private fun <T> Chips(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) = Searchable(*options.map { it.first }.toTypedArray()) {
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
    Searchable(format(v)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(format(v), Modifier.padding(end = 8.dp))
            Slider(value = v, onValueChange = { v = snap(it) }, valueRange = range, onValueChangeFinished = { onDone(v) })
        }
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
