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
import com.oleroma.gyrophone.core.model.TRACKBALL_AS_CAMERA
import com.oleroma.gyrophone.core.model.TRACKBALL_LOCK
import com.oleroma.gyrophone.core.model.trackballLocks
import com.oleroma.gyrophone.core.model.TRACKBALL_STOP
import com.oleroma.gyrophone.core.model.AxisEase
import com.oleroma.gyrophone.core.model.easeOf
import com.oleroma.gyrophone.core.model.withEase
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
import com.oleroma.gyrophone.core.engine.LaserFire
import com.oleroma.gyrophone.core.engine.Guns
import com.oleroma.gyrophone.core.engine.CrosshairKick
import com.oleroma.gyrophone.core.engine.LaserTrace
import com.oleroma.gyrophone.core.model.BallShape
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayColor
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TurnsWith
import com.oleroma.gyrophone.core.model.RestFade
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Settings of the parallax edge ([GyroEdgeLayer]) and "Fade when phone rests" for every gyro display
 * ([stillness] the phone's turning speed now, for its live line); [set] saves a changed [v]. The
 * other gyro displays are placed in edit mode.
 */
@Composable
fun GyroVisualsSettings(v: TouchVisuals, stillness: () -> Float? = { null }, set: (TouchVisuals) -> Unit) {
    Hint("The other gyro displays (3D phone, ratchet ring, speed graph, attitude indicator, bubble disk, 3D shape, pointer): add them in edit mode, like buttons.")
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
    RestFadeSettings(v.restFade, stillness) { set(v.copy(restFade = it)) }
}

/** "Fade when phone rests" for every gyro display on the pad, as the PC's Overlays page has it for the overlays. */
@Composable
private fun RestFadeSettings(r: RestFade, stillness: () -> Float?, set: (RestFade) -> Unit) {
    SwitchRow(
        "Fade when phone rests", r.on,
        "The gyro displays fade while the phone lies still at their Level with nothing pressed. Any touch, button or movement brings them back at once.",
    ) { set(r.copy(on = it)) }
    if (!r.on) return
    Indented {
        IntSlider("Flat within %d°", r.flatWithinDeg.roundToInt(), 1..45) { set(r.copy(flatWithinDeg = it.toFloat())) }
        Hint("Of the tilt each display was leveled at (its Level button or Set orientation).")
        FloatSlider("Still below %.1f°/s", r.stillBelowDps, 0.1f..5f, 0.1f) { set(r.copy(stillBelowDps = it)) }
        var live by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                live = stillness()
                delay(300)
            }
        }
        Hint(
            live?.let { "Phone now: %.2f°/s, %s".format(it, if (it < r.stillBelowDps) "still" else "moving") }
                ?: "Phone now: not measured (only while a gyro display is on the pad)",
        )
        FloatSlider("Quiet for %.1f s", r.quietMs / 1000f, 0.5f..30f, 0.5f) { set(r.copy(quietMs = (it * 1000).roundToInt())) }
        PercentSlider("Fade to", r.fadeTo, 0.05f..1f) { set(r.copy(fadeTo = it)) }
        FloatSlider("Fade over %.1f s", r.fadeMs / 1000f, 0f..3f, 0.1f) { set(r.copy(fadeMs = (it * 1000).roundToInt())) }
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
    /** Saves it (look, settings and size) as a preset under the name given, for Add preset. */
    onSavePreset: ((String) -> Unit)? = null,
) {
    val d = control.display ?: return
    // Level reads the accelerometer a moment later: it changes the control as it is then.
    val latest = rememberUpdatedState(control)
    var confirmDelete by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<GyroDisplayColor?>(null) }
    fun set(nd: GyroDisplay) = onChange(control.copy(display = nd))
    // Its label names it only here and in lists, never on the display itself.
    val name = control.label.ifBlank { d.kind.label }
    SettingsDialog(
        title = name,
        subtitle = "Gyro display · settings",
        onClose = onClose,
        titleField = { LabelField(control.label, d.kind.label, { onChange(latest.value.copy(label = it)) }) },
        actions = {
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
            if (onSavePreset != null) TextButton(onClick = { naming = true }) { Text("Save as preset") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
    ) {
        val laser = d.kind == GyroDisplayKind.LASER
        @Composable
        fun Colours() {
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
            if (movesShown(d)) {
                SwitchRow("Smooth way colours", d.laserSmoothColours, "Small shakes don't flicker the Moving colours.") { set(d.copy(laserSmoothColours = it)) }
                Hint("The Moving colours colour the crosshair, and with Colour by the way it moved the paint, by the way the beam moves (right, left, up, down, toward you, away, as the sphere's axes are when it isn't turned). A slant blends its ways' colours, as a normal map does. The crosshair keeps the last way's colour when you stop. With Colour by where it is, the paint takes them by where on the sphere it lands: its right side Moving right's colour, and so on.")
            }
            if (d.colors.isNotEmpty()) TextButton(onClick = { set(d.copy(colors = emptyMap())) }) { Text("Default colours") }
        }
        // As the PC overlays' tabs: Look (what it shows, colours) and Turning (how the pose moves); the laser pointer, with much to set, also Draw, Paint and Colours.
        val look = category(runtime, "gyroDisplayLook", "Look", "What it shows, opacity, colours", defaultOpen = true) {
            when (d.kind) {
                GyroDisplayKind.PHONE -> {
                    SwitchRow("Ghost trail", d.ghosts) { set(d.copy(ghosts = it)) }
                    SwitchRow("Axis arrows", d.axes) { set(d.copy(axes = it)) }
                    Hint("Arrows along the phone's own right, up and screen directions.")
                    SwitchRow("Axonometric view", d.axonometric, AXON_HINT) { set(d.copy(axonometric = it)) }
                    SwitchRow("Perspective", d.perspective, "Nearer parts show bigger, as a camera sees them. Off: flat, every part the same size wherever it is.") { set(d.copy(perspective = it)) }
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
                    SwitchRow("Perspective", d.perspective, "Nearer parts show bigger, as a camera sees them. Off: flat, every part the same size wherever it is.") { set(d.copy(perspective = it)) }
                }

                GyroDisplayKind.LASER -> {
                    GroupTitle("Pointer")
                    Hint("A see-through sphere seen from above and to the side. The pointer at its centre aims along the phone's roll axis (out of its top edge, into the screen at rest) onto the inside; the tick at the dot shows which way the phone's screen faces. The beam dims while a ratchet is held.")
                    val look = if (d.laserRifle) 1 else if (d.laserCrosshair) 2 else 0
                    Chips(listOf("Sphere" to 0, "Gun" to 1, "Crosshair" to 2), look) { set(d.copy(laserRifle = it == 1, laserCrosshair = it == 2)) }
                    if (d.laserRifle) Indented {
                        Hint("A gun aims instead: no sphere or beam, and Draw mode still paints where it points. Best with Axonometric view (face-on you look along the barrel). Each gun has its own model, recoil, fire rate and muzzle flash.")
                        Chips(Guns.ALL.mapIndexed { i, g -> g.name to i }, d.laserGun) { set(d.copy(laserGun = it)) }
                        SwitchRow("Lowered when idle", d.gunLowered, "It rests pitched down and comes up straight while you aim or fire.") { set(d.copy(gunLowered = it)) }
                        PercentSlider("Hip fire pull", d.gunHipPull, 0f..1f) { set(d.copy(gunHipPull = it)) }
                        IntSlider("Hip fire recovery %d ms", d.gunHipRecoverMs, 0..2000, 10) { set(d.copy(gunHipRecoverMs = it)) }
                        Hint("Firing without aiming, the gun pulls back toward you this share of the way to its muzzle at the sphere's centre, and eases back over Hip fire recovery once you stop.")
                        if (d.gun().scope != null) SwitchRow("Scope while aiming", d.gunScope, "Aiming, a round scope grows onto it.") { set(d.copy(gunScope = it)) }
                    }
                    if (look == 2) Indented {
                        Hint("A crosshair instead: no sphere or beam, just a crosshair lying on the sphere where the pointer lands, facing its centre, so it squashes toward the edge. It turns about the beam as the Trackball's Roll row maps it (Lock keeps it upright, Invert turns it the other way). Draw mode still paints where it points.")
                        Chips(LaserTrace.CROSSHAIRS.mapIndexed { i, n -> n to i }, d.crosshairShape) { set(d.copy(crosshairShape = it)) }
                        IntSlider("Size %d°", d.crosshairSize, 2..30) { set(d.copy(crosshairSize = it)) }
                        if (d.crosshairShape == 1 || d.crosshairShape == 3 || d.crosshairShape == 5) IntSlider("Gap %d°", d.crosshairGap, 0..10) { set(d.copy(crosshairGap = it)) }
                        if (d.crosshairShape != 4) FloatSlider("Thickness %.1f", d.crosshairThickness, 0.5f..6f, 0.5f) { set(d.copy(crosshairThickness = it)) }
                        if (d.crosshairShape != 4) SwitchRow("Centre dot", d.crosshairDot) { set(d.copy(crosshairDot = it)) }
                        SwitchRow("Outline", d.crosshairOutline, "A dark edge so it shows on any background.") { set(d.copy(crosshairOutline = it)) }
                    }
                    if (d.turnsWith == TurnsWith.TRACKBALL) {
                        Text("Pointer faces (as a trackball)", style = MaterialTheme.typography.bodyMedium)
                        Chips(listOf("−Z (into the screen)" to 0, "+Z" to 1, "+X" to 2, "−X" to 3, "+Y" to 4, "−Y" to 5), d.laserFaces) { set(d.copy(laserFaces = it)) }
                    }
                    SwitchRow("Axonometric view", d.laserAxonometric, LASER_AXON_HINT) { set(d.copy(laserAxonometric = it)) }
                    SwitchRow("Perspective", d.perspective, "Nearer parts show bigger, as a camera sees them. Off: flat, every part the same size wherever it is.") { set(d.copy(perspective = it)) }
                    SwitchRow("Fixed pointer mode", d.laserSphereTurns, "Off: the beam turns inside a still sphere. On: the beam stays pointing ahead and the sphere, with its grid, axes and drawing, turns under it.") { set(d.copy(laserSphereTurns = it)) }
                    if (!d.laserRifle) SwitchRow("Grid every 15°", d.laserGrid) { set(d.copy(laserGrid = it)) }
                    SwitchRow("Axes (pitch X red, yaw Y green, roll Z blue)", d.laserAxes, "They turn with the pointer, R along its beam.") { set(d.copy(laserAxes = it)) }
                    val paints = d.laserDraw || d.laserSnake
                    ButtonPicker("Fire display", d.laserFire, "While GyroPhone sends this button the gun and crosshair kick and flash with each shot, and Draw mode paints in the Fire colours. Tap to pick another, or None.") { set(d.copy(laserFire = it)) }
                    if (d.laserFire != LaserFire.NONE && (paints || look != 0)) Indented {
                        if (look != 0) {
                            Text("Kick", style = MaterialTheme.typography.bodyMedium)
                            Hint(if (look == 2) "Each shot the crosshair jumps bigger and bounces back with the recoil of the gun picked here. Once: one kick per press. Auto rifle: keeps kicking while the button is held. Each gun keeps its own kick size and fire rate." else "Each shot the gun kicks back toward you, its muzzle jumping up, and springs back with its own recoil. Once: one kick per press. Auto rifle: keeps kicking while the button is held. Each gun keeps its own kick size and fire rate.")
                            if (look == 2) {
                                Text("Recoil like", style = MaterialTheme.typography.bodySmall)
                                Chips(Guns.ALL.mapIndexed { i, g -> g.name to i }, d.laserGun) { set(d.copy(laserGun = it)) }
                            }
                            Chips(CrosshairKick.MODES.mapIndexed { i, n -> n to i }, d.crosshairKick) { set(d.copy(crosshairKick = it)) }
                            if (d.crosshairKick != CrosshairKick.OFF) {
                                val gun = d.gun()
                                PercentSlider("Kick size (${gun.name})", d.kickOf(gun), 0.05f..2f) { set(d.copy(gunKick = d.gunKick + (gun.key to it))) }
                                if (d.crosshairKick == CrosshairKick.AUTO) IntSlider("Fire rate %d a minute (${gun.name})", d.rpmOf(gun), CrosshairKick.RPM_MIN..1200, 5) { set(d.copy(gunRpm = d.gunRpm + (gun.key to it))) }
                                if (look == 2) Chips(listOf("Lines only" to 1, "Evenly, thickness too" to 0), if (d.crosshairKickLinesOnly) 1 else 0) { set(d.copy(crosshairKickLinesOnly = it == 1)) }
                            }
                            SwitchRow("Muzzle flash", d.muzzleFlash, if (look == 2) "Each shot the crosshair flashes in time with its kick: a burst of white light cooling to the Flash colour (yellow), then Flash end (orange), and back to its own colour." else "Each shot a blast of flame from the muzzle in time with the kick, as wide as the gun's muzzle and as long as its calibre (pistol shortest, shotgun and sniper longest): white, cooling to the Flash colour (yellow), then Flash end (orange).") { set(d.copy(muzzleFlash = it)) }
                        }
                        if (look == 0 && d.laserDraw) {
                            Text("Shoots like", style = MaterialTheme.typography.bodySmall)
                            Chips(Guns.ALL.mapIndexed { i, g -> g.name to i }, d.laserGun) { set(d.copy(laserGun = it)) }
                        }
                        if (look != 0 || d.laserDraw) {
                            val gun = d.gun()
                            SwitchRow("Simulate spread", d.laserSpread, "Each shot lands a little off the aim point, a random way, by up to the gun's spread." + (if (look != 0) " The ${if (look == 2) "crosshair" else "gun"} kicks toward it and springs back." else "") + " Each gun keeps its own spread.") { set(d.copy(laserSpread = it)) }
                            if (d.laserSpread) Indented {
                                FloatSlider("Spread %.1f° (${gun.name})", d.spreadOf(gun), 0f..10f, 0.1f) { set(d.copy(gunSpread = d.gunSpread + (gun.key to it))) }
                                SwitchRow("Bloom", d.laserBloom, "Shots fired in a row spread wider, up to twice over ${CrosshairKick.BLOOM_SHOTS} shots; it settles half a second after you let go.") { set(d.copy(laserBloom = it)) }
                            }
                        }
                        if (d.laserDraw) {
                            SwitchRow("Draw each shot", d.laserShotDraw, "Each shot marks a hit where it lands, in the Fire colours, as wide as the Fire brush (a shotgun's pellets half that). Firing then paints no Fire line. Hits stay until you clear the drawing.") { set(d.copy(laserShotDraw = it)) }
                            if (d.laserShotDraw) Indented {
                                if (look == 0) {
                                    // The Sphere look has no Kick: its shots' beat here.
                                    Chips(CrosshairKick.MODES.drop(1).mapIndexed { i, n -> n to i + 1 }, d.crosshairKick.coerceAtLeast(CrosshairKick.ONCE)) { set(d.copy(crosshairKick = it)) }
                                    if (d.crosshairKick == CrosshairKick.AUTO) IntSlider("Fire rate %d a minute (${d.gun().name})", d.rpmOf(d.gun()), CrosshairKick.RPM_MIN..1200, 5) { set(d.copy(gunRpm = d.gunRpm + (d.gun().key to it))) }
                                }
                                Text("Hit look", style = MaterialTheme.typography.bodySmall)
                                Chips(LaserTrace.HIT_LOOKS.mapIndexed { i, n -> n to i }, d.laserHitLook) { set(d.copy(laserHitLook = it)) }
                            }
                        }
                        if (paints) IntSlider("Fire brush %d°", d.laserFireBrush, 1..20) { set(d.copy(laserFireBrush = it)) }
                        if (d.laserDraw) PercentSlider("Fire glow", d.laserFireGlow, 0f..3f) { set(d.copy(laserFireGlow = it)) }
                    }
                    ButtonPicker("Aim display", d.laserAim, "While GyroPhone sends this button the crosshair shrinks and turns laser red (its Crosshair aiming colour), the gun is shouldered with a scope and a laser sight, and Draw mode paints in the Aim colours. Firing wins over aiming. Tap to pick another, or None.") { set(d.copy(laserAim = it)) }
                    if (d.laserAim != LaserFire.NONE) Indented {
                        if (look == 2) PercentSlider("Crosshair while aiming", d.laserAimSize, 0.2f..1f) { set(d.copy(laserAimSize = it)) }
                        if (paints) IntSlider("Aim brush %d°", d.laserAimBrush, 1..20) { set(d.copy(laserAimBrush = it)) }
                        if (d.laserDraw) PercentSlider("Aim glow", d.laserAimGlow, 0f..3f) { set(d.copy(laserAimGlow = it)) }
                    }
                    if (paints && (d.laserFire != LaserFire.NONE || d.laserAim != LaserFire.NONE)) {
                        SwitchRow("Paint only while firing or aiming", d.laserFireOnly, "The beam alone leaves no trail; only shots and aiming draw.") { set(d.copy(laserFireOnly = it)) }
                    }
                    if (!d.laserRifle && !d.laserCrosshair) SwitchRow("Gimbal rings", d.laserGimbal, "Yaw (green, outside), pitch (red) and roll (blue) rings turning with the pointer's axes, each round its own: the path the beam and crosshair take turning about it. A None axis's ring is faint and dashed, and an axis lights up while the gyro turns it.") { set(d.copy(laserGimbal = it)) }
                }
            }
            PercentSlider("Opacity", d.opacity, 0.1f..1f) { set(d.copy(opacity = it)) }
            Hint("Resize it with a pinch in edit mode; it always keeps its shape.")
            // Only the laser pointer, with much to set, has its colours in a tab of their own.
            if (!laser) {
                GroupTitle("Colours")
                Colours()
            }
        }
        val draw = if (!laser) null else category(runtime, "gyroDisplayDraw", "Draw", "Draw mode, snake, brush, Clear") {
            SwitchRow("Draw mode", d.laserDraw, "The beam paints the inside of the sphere as you aim; the paint stays until you clear it.") { set(d.copy(laserDraw = it)) }
            SwitchRow("Snake", d.laserSnake, "A line following the beam along the newest stretch of its path, thinning and fading toward its tail as set. With or without Draw mode.") { set(d.copy(laserSnake = it)) }
            // Length 10° to 720°, finer toward the short ones: the slider's square, in 5° steps.
            if (d.laserSnake) Indented {
                SavedSlider(
                    { p -> "Length ${lengthDeg(p)}°" },
                    sqrt(d.laserSnakeDeg.coerceIn(LASER_LENGTH_MIN, LASER_LENGTH_MAX) / LASER_LENGTH_MAX.toFloat()), 0f..1f,
                    { p -> sqrt(lengthDeg(p) / LASER_LENGTH_MAX.toFloat()) },
                ) { set(d.copy(laserSnakeDeg = lengthDeg(it))) }
                IntSlider("Snake brush %d°", d.laserSnakeBrush, 1..20) { set(d.copy(laserSnakeBrush = it)) }
                PercentSlider("Taper", d.laserSnakeTaper, 0f..1f) { set(d.copy(laserSnakeTaper = it)) }
                PercentSlider("Tail fade", d.laserSnakeFade, 0f..1f) { set(d.copy(laserSnakeFade = it)) }
                PercentSlider("Snake glow", d.laserSnakeGlow, 0f..1f) { set(d.copy(laserSnakeGlow = it)) }
                SwitchRow("Clear on ratchet", d.laserSnakeClearOnRatchet, "A ratchet press clears the Snake.") { set(d.copy(laserSnakeClearOnRatchet = it)) }
                Hint("Snake brush: how wide its head is. Taper: how much it thins toward its tail (0 keeps it the same width). Tail fade: how see-through it gets toward its tail (0 none). Snake glow: a soft glow round it (0 none). Shots and aiming keep their own brushes.")
            }
            if (d.laserDraw || d.laserSnake) Indented {
                if (d.laserDraw) IntSlider("Brush %d°", d.laserBrush, 1..20) { set(d.copy(laserBrush = it)) }
                if (d.laserDraw) SwitchRow("Clear on ratchet", d.laserClearOnRatchet, "A ratchet press clears the paint (the Snake has its own).") { set(d.copy(laserClearOnRatchet = it)) }
                Hint("The paint uncovers the sphere's own colours where it lands (the Moving colours, laid on the sphere as a normal map: right pink, left teal, up light green, down purple), or with Colour by the way it moved, the colour of each sweep's direction. Shots take the Fire colours and aiming the Aim colours, each with its own brush and glow (Look tab). Nothing is painted while a ratchet is held. Turning Draw mode off clears the drawing. A Clear displays mapping (Other targets) clears every pointer at once.")
            }
            TextButton(onClick = { runtime.clearLaser(control.id) }) { Text("Clear") }
        }
        val strokes = if (!laser || !(d.laserDraw || d.laserSnake)) null else category(runtime, "gyroDisplayStrokes", "Paint", "Colour by, detail, softness, glow, outline, shade, light, shine, smoothing") {
            GroupTitle("Colour by")
            Chips(listOf("Where it is" to false, "The way it moved" to true), d.laserColourByWay) { set(d.copy(laserColourByWay = it)) }
            if (d.laserDraw) {
                GroupTitle("Paint detail")
                Chips(listOf("Low" to 0, "Normal" to 1, "High" to 2), d.laserPaintDetail.coerceIn(0, 2)) { set(d.copy(laserPaintDetail = it)) }
                Hint("How finely Draw mode's paint is kept. Low is lightest on the battery; High is sharpest, best with the charger in. Changing it clears the paint.")
                PercentSlider("Softness", d.laserSoftness, 0f..1f) { set(d.copy(laserSoftness = it)) }
                PercentSlider("Glow", d.laserGlow, 0f..1f) { set(d.copy(laserGlow = it)) }
                SwitchRow("Outline", d.laserOutline, "A thin dark edge round the paint (its colour is Paint outline), so it shows on light backgrounds.") { set(d.copy(laserOutline = it)) }
            }
            PercentSlider("Facing shade", d.laserShade, 0f..1f) { set(d.copy(laserShade = it)) }
            PercentSlider("Inside", d.laserInside, 0.2f..1f) { set(d.copy(laserInside = it)) }
            GroupTitle("Light")
            PercentSlider("Light strength", d.laserLight, 0f..1f) { set(d.copy(laserLight = it)) }
            Chips(listOf("Upper left" to 0, "Above" to 1, "Upper right" to 2, "Front" to 3), d.laserLightFrom.coerceIn(0, 3)) { set(d.copy(laserLightFrom = it)) }
            PercentSlider("Shine", d.laserShine, 0f..1f) { set(d.copy(laserShine = it)) }
            Hint("A light fixed in the scene, shining from where you pick: it stays put while the sphere or the pointer turns. Light strength darkens what faces away from it (0 off); Shine adds a glossy highlight where it reflects toward you (0 none).")
            SavedSlider(
                { if (it < 0.025f) "Line smoothing: off" else "Line smoothing %.0f%%".format(it * 100) },
                d.laserLineSmoothing, 0f..1f, { (it * 20).roundToInt() / 20f },
            ) { set(d.copy(laserLineSmoothing = it)) }
            Hint("Softness: how soft the paint's edge is (0 sharp); it shapes the paint as you lay it down, so changing it changes only new strokes. Where lines cross, the paint builds up and their colours blend. Facing shade darkens paint where the sphere's surface turns away from you, so you see its roundness (0 off). Inside: how bright the far half's paint is, seen from inside the sphere, next to the near half's seen from outside. Line smoothing steadies the painted line against hand shake, strongly when you move slowly and hardly when you move fast, so the paint may trail a slow aim a little; the beam, dot and crosshair stay as they are.")
        }
        val colours = if (!laser) null else category(runtime, "gyroDisplayColours", "Colours", "Each part's colour") { Colours() }
        val turning = if (!d.kind.pose) null else category(runtime, "gyroDisplayTurning", "Turning", "Turns with, gravity, ease back, turn amount, Level", defaultOpen = true) {
            GroupTitle("Turns with")
            Chips(TurnsWith.entries.map { it.label to it }, d.turnsWith) { set(d.copy(turnsWith = it)) }
            Hint(
                when (d.turnsWith) {
                    TurnsWith.PHONE -> "How you hold the phone."
                    TurnsWith.CAMERA -> "Where the game's camera points: it turns left and right and up and down as the camera does, never rolling, with the gyro and every other camera move (touchpad, buttons, controllers, keys, glides). A ratchet stops it, as it stops the camera."
                    TurnsWith.TRACKBALL -> "Like a ball rolled by your moves: each of its axes turns by the gyro turn picked below (as Camera, by default), and every other camera move rolls it too. A ratchet stops it."
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
            // A trackball eases back per axis (in its axis rows), not here.
            if (d.turnsWith == TurnsWith.CAMERA) {
                GroupTitle("Pose")
                EaseSettings(d) { set(it) }
            }
            GroupTitle("Amount")
            // Following the camera it turns by Spin (or as far as the game's camera) instead.
            if (!d.followsCamera) FloatSlider("Turn amount × %.1f", d.motionScale, 0.5f..3f, 0.1f) { set(d.copy(motionScale = it)) }
            Hint("Hold the phone the way you play and press Level: held that way, it shows face-on, its screen flat toward you.")
            TextButton(onClick = {
                val gravity = d.needsGravity
                if (!gravity) runtime.gyroFeed(control.id)?.restart()
                // Which way is up is kept either way: "Fade when phone rests" counts the phone flat near it.
                runtime.readUp { up ->
                    val now = latest.value.display ?: return@readUp
                    val l = up?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }?.takeIf { it > 0f }
                    val levelUp = if (up != null && l != null) listOf(up[0] / l, up[1] / l, up[2] / l) else now.levelUp
                    val rest = if (!gravity) null
                    else up?.let { GyroPose.tiltFromUp(it[0], it[1], it[2]) }?.toList() ?: return@readUp
                    if (rest != now.rest || levelUp != now.levelUp) onChange(latest.value.copy(display = now.copy(rest = rest, levelUp = levelUp)))
                }
            }) { Text("Level") }
        }
        val items = listOfNotNull(look, draw, strokes, colours, turning)
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
    if (naming && onSavePreset != null) {
        PresetNameDialog("Save as preset", name, onSave = { n -> naming = false; onSavePreset(n) }, onDismiss = { naming = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (control.label.isBlank()) "Delete this ${d.kind.label.lowercase()}?" else "Delete $name?") },
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

/** A trackball's turning: about the sphere's axes or its own, and for each axis the gyro turn that turns it (None: held). */
@Composable
private fun TrackballAxesSettings(d: GyroDisplay, set: (GyroDisplay) -> Unit) {
    Text("Turns about", style = MaterialTheme.typography.bodyMedium)
    Chips(listOf("The sphere's axes" to false, "Its own axes" to true), d.trackballOwnAxes) { on ->
        // Its own axes: the loops' roll eases back once the phone stops, unless roll already eases.
        val roll = d.easeOf(2)
        set(d.copy(trackballOwnAxes = on).let { if (on && !roll.on) it.withEase(2, roll.copy(on = true, onRatchet = false)) else it })
    }
    Hint(
        if (d.trackballOwnAxes) "As the pointer sees it: up and down about its own right, left and right about its own up, so it always moves square to where it aims and never rolls by itself. Moving in a loop leaves it a little rolled (the sphere is curved); Roll's ease back levels that out."
        else "As a game camera (a gimbal): left and right about the sphere's vertical, up and down about its turned right, then roll about the beam.",
    )
    // Its bytes: 0 about up Y (yaw), 1 about right X (pitch), 2 along the beam Z (roll).
    for ((i, name) in listOf(0 to "Yaw", 1 to "Pitch", 2 to "Roll")) {
        val code = d.trackballAxes shr (8 * i) and 0xFF
        fun put(c: Int) = set(d.copy(trackballAxes = (d.trackballAxes and (0xFF shl (8 * i)).inv()) or (c shl (8 * i))))
        // None holds it (a lock saved before None meant it shows as None).
        val none = trackballLocks(d.trackballAxes) and (1 shl i) != 0
        Text("$name axis turns with", style = MaterialTheme.typography.bodyMedium)
        Chips(listOf("Yaw" to 3, "Pitch" to 2, "Roll" to 1, "None" to 0), if (none) 0 else code and 0x1F) {
            put(it or (code and 0x1F.inv() and TRACKBALL_LOCK.inv()))
        }
        if (none) continue
        SwitchRow("Invert $name axis", code and 0x80 != 0) { put((code and 0x80.inv()) or (if (it) 0x80 else 0)) }
        if (!d.trackballOwnAxes) SwitchRow("Stop $name at ±90°", code and TRACKBALL_STOP != 0, if (i == 1) "Off: it goes over the top, left and right staying as seen." else null) {
            put(if (it) code or TRACKBALL_STOP else code and TRACKBALL_STOP.inv())
        }
        AxisEaseSettings(name, d.easeOf(i)) { set(d.withEase(i, it)) }
    }
    Hint("None holds an axis still: nothing turns it, as Camera never rolls. On the pointer a None axis is grey and dashed with a padlock, its gimbal ring faint, and an axis lights up while the gyro turns it.")
    if (d.trackballAxes != TRACKBALL_AS_CAMERA || d.trackballOwnAxes) TextButton(onClick = { set(d.copy(trackballAxes = TRACKBALL_AS_CAMERA, trackballOwnAxes = false)) }) { Text("As the camera") }
}

/** A trackball axis [name] easing back to straight, and its own options. */
@Composable
private fun AxisEaseSettings(name: String, e: AxisEase, set: (AxisEase) -> Unit) {
    SwitchRow("Ease $name axis back", e.on) { set(e.copy(on = it)) }
    if (e.on) Indented {
        Chips(listOf("On ratchet" to true, "When the phone stops" to false), e.onRatchet) { set(e.copy(onRatchet = it)) }
        IntSlider("Delay %d ms", e.delayMs, 0..3000, step = 50) { set(e.copy(delayMs = it)) }
        Chips(listOf("By time" to false, "By speed" to true), e.bySpeed) { set(e.copy(bySpeed = it)) }
        if (e.bySpeed) IntSlider("Speed %d °/s", e.speed, 10..720, step = 10) { set(e.copy(speed = it)) }
        else FloatSlider("Back in %.1f s", e.seconds, 0.1f..5f, 0.1f) { set(e.copy(seconds = it)) }
    }
}

/** Ease back to straight and its options (Phone and Camera; a trackball eases each axis on its own). */
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
/** The laser's way colours are in use: its paint, or its crosshair. */
private fun movesShown(d: GyroDisplay): Boolean = d.kind == GyroDisplayKind.LASER && (d.laserDraw || d.laserSnake || (d.laserCrosshair && !d.laserRifle))

private fun shown(d: GyroDisplay, c: GyroDisplayColor): Boolean = when (c) {
    GyroDisplayColor.BALL_GIMBAL_YAW, GyroDisplayColor.BALL_GIMBAL_PITCH, GyroDisplayColor.BALL_GIMBAL_ROLL -> d.ballShape == BallShape.GIMBAL
    GyroDisplayColor.BALL_LINES -> d.ballShape != BallShape.GIMBAL
    GyroDisplayColor.BALL_BODY -> d.ballShape != BallShape.GIMBAL && d.fillFaces
    GyroDisplayColor.BALL_EQUATOR -> d.ballShape == BallShape.GLOBE && d.equator
    GyroDisplayColor.BALL_MARK -> d.screenMark
    GyroDisplayColor.BALL_POLE -> d.topMark
    GyroDisplayColor.LASER_GRID -> d.laserGrid && !d.laserRifle
    GyroDisplayColor.LASER_X, GyroDisplayColor.LASER_Y, GyroDisplayColor.LASER_Z -> d.laserAxes
    GyroDisplayColor.LASER_MOVE_RIGHT, GyroDisplayColor.LASER_MOVE_LEFT, GyroDisplayColor.LASER_MOVE_UP,
    GyroDisplayColor.LASER_MOVE_DOWN, GyroDisplayColor.LASER_MOVE_TOWARD, GyroDisplayColor.LASER_MOVE_AWAY -> movesShown(d)
    GyroDisplayColor.LASER_RIFLE -> d.laserRifle
    GyroDisplayColor.LASER_OUTLINE -> d.laserDraw && d.laserOutline
    GyroDisplayColor.LASER_SPHERE, GyroDisplayColor.LASER_BEAM, GyroDisplayColor.LASER_DOT -> !d.laserRifle && !d.laserCrosshair
    GyroDisplayColor.LASER_CROSSHAIR_OUTLINE -> d.laserCrosshair && !d.laserRifle && d.crosshairOutline
    GyroDisplayColor.LASER_CROSSHAIR_AIM -> d.laserCrosshair && !d.laserRifle && d.laserAim != LaserFire.NONE
    GyroDisplayColor.LASER_FLASH, GyroDisplayColor.LASER_FLASH_END -> (d.laserCrosshair || d.laserRifle) && d.laserFire != LaserFire.NONE && d.muzzleFlash
    else -> true
}

/** Draw mode's longest fade. */
private const val LASER_LENGTH_MIN = 10
private const val LASER_LENGTH_MAX = 720

/** Snake's Length slider at [p] (0..1, its square) in degrees, to 5°, from [LASER_LENGTH_MIN]. */
private fun lengthDeg(p: Float): Int = maxOf(LASER_LENGTH_MIN, ((p * p * LASER_LENGTH_MAX) / 5f).roundToInt() * 5)
private const val LASER_AXON_HINT = "Seen from 30° round and 30° above, so a beam into the far side shows as a line. Off, face-on: a beam straight in is a dot."
private const val AXON_HINT = "Seen from 30° round and 30° above with the world's axes (yaw toward you red, pitch right green, roll up blue, as the gimbal's rings), as the pointer is, instead of face-on."

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

/**
 * A pointer's Fire display or Aim display pick ([title]): a row showing the picked button that
 * opens a popup listing None and every button as chips wrapped to the screen's width, scrolling if
 * they don't fit, in two tabs: Keyboard & mouse and Gamepad (opening on the picked one's).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ButtonPicker(title: String, selected: Int, hint: String, onPick: (Int) -> Unit) {
    val groups = listOf(
        "Mouse" to com.oleroma.gyrophone.core.engine.LaserFire.MOUSE_CHOICES,
        "Controller" to com.oleroma.gyrophone.core.engine.LaserFire.PAD_CHOICES,
        "Keyboard" to com.oleroma.gyrophone.core.engine.LaserFire.KEY_CHOICES,
    )
    var open by remember { mutableStateOf(false) }
    val name = groups.firstNotNullOfOrNull { (g, list) -> list.firstOrNull { it.second == selected }?.let { "$g ${it.first}" } } ?: "None"
    Searchable(title, *groups.flatMap { g -> g.second.map { it.first } }.toTypedArray()) {
        // As every settings row: its title, the picked button on the right, the tip below.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.OutlinedButton(onClick = { open = true }) { Text(name) }
            }
            Hint(hint)
        }
    }
    if (!open) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            var pad by remember { mutableStateOf(groups[1].second.any { it.second == selected }) }
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !pad, onClick = { pad = false }, label = { Text("Keyboard & mouse") })
                    FilterChip(selected = pad, onClick = { pad = true }, label = { Text("Gamepad") })
                }
                // None turns it off.
                FilterChip(selected = selected == LaserFire.NONE, onClick = {
                    onPick(LaserFire.NONE)
                    open = false
                }, label = { Text("None") }, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((g, list) in if (pad) groups.subList(1, 2) else listOf(groups[0], groups[2])) {
                    Text(g, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((label, value) in list) {
                            FilterChip(selected = selected == value, onClick = {
                                onPick(value)
                                open = false
                            }, label = { Text(label) })
                        }
                    }
                }
            }
            }
        },
        // Picking a button closes it (or tapping outside): no Close button.
        confirmButton = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp),
    )
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
