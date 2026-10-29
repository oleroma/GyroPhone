package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Settings for the pad's gyro displays ([GyroVisualsLayer]); [set] saves a changed [v]. */
@Composable
fun GyroVisualsSettings(v: TouchVisuals, set: (TouchVisuals) -> Unit) {
    Text("Gyro display", style = MaterialTheme.typography.titleSmall)
    Hint("Pictures of the gyro at work on the pad, for you or for anyone watching your screen. All of them are off when not shown.")

    SwitchRow("3D phone", v.gyroPhone) { set(v.copy(gyroPhone = it)) }
    if (v.gyroPhone) Indented {
        Chips(listOf("Gyro" to false, "Gravity" to true), v.gyroPhoneGravity) { set(v.copy(gyroPhoneGravity = it)) }
        Hint(
            if (v.gyroPhoneGravity) "Its tilt follows gravity, so it never drifts; a turn left or right eases back to facing you."
            else "It turns as the gyro moves the mouse (paused axes stay still) and can drift over time.",
        )
        Chips(VisPlace.entries.map { it.label to it }, v.gyroPhonePlace) { set(v.copy(gyroPhonePlace = it)) }
        PercentSlider("Size", v.gyroPhoneSize, 0.4f..2.5f) { set(v.copy(gyroPhoneSize = it)) }
        SwitchRow("Ghost trail", v.gyroPhoneGhosts) { set(v.copy(gyroPhoneGhosts = it)) }
        SwitchRow("Axis arrows", v.gyroPhoneAxes) { set(v.copy(gyroPhoneAxes = it)) }
        Hint("Red, green and blue arrows: the phone's own right, up and screen directions.")
    }

    SwitchRow("Ratchet ring", v.gyroRatchetRing) { set(v.copy(gyroRatchetRing = it)) }
    if (v.gyroRatchetRing) Indented {
        Chips(VisPlace.entries.map { it.label to it }, v.gyroRatchetRingPlace) { set(v.copy(gyroRatchetRingPlace = it)) }
        Hint("Yaw, pitch and roll arcs, lit while that axis moves the mouse and dim while a ratchet (or the gyro mode) pauses it.")
    }

    SwitchRow("Speed graph", v.gyroGraph) { set(v.copy(gyroGraph = it)) }
    if (v.gyroGraph) Indented {
        Chips(listOf(180, 360, 720, 1440).map { "±$it°/s" to it }, v.gyroGraphRange) { set(v.copy(gyroGraphRange = it)) }
        Chips(listOf(2, 3, 5, 8).map { "$it s" to it }, v.gyroGraphSeconds) { set(v.copy(gyroGraphSeconds = it)) }
        Hint("Yaw (red), pitch (green) and roll (blue) speed along the bottom; shaded while a ratchet is held.")
    }

    SwitchRow("Attitude indicator", v.gyroHorizon) { set(v.copy(gyroHorizon = it)) }
    if (v.gyroHorizon) Indented {
        Chips(VisPlace.entries.map { it.label to it }, v.gyroHorizonPlace) { set(v.copy(gyroHorizonPlace = it)) }
        PercentSlider("Size", v.gyroHorizonSize, 0.4f..2.5f) { set(v.copy(gyroHorizonSize = it)) }
        Hint("An aircraft-style horizon: it tilts with roll and moves with pitch against gravity. The tape on top counts degrees turned.")
    }

    SwitchRow("Parallax edge", v.gyroEdge) { set(v.copy(gyroEdge = it)) }
    if (v.gyroEdge) Indented {
        Chips(listOf("Turn" to false, "Tilt" to true), v.gyroEdgeTilt) { set(v.copy(gyroEdgeTilt = it)) }
        Hint(
            if (v.gyroEdgeTilt) "It shifts while the phone is tilted against gravity and stays there."
            else "It slides as the phone turns and springs back once it stops.",
        )
        IntSlider("Layers %d", v.gyroEdgeLayers, 1..5) { set(v.copy(gyroEdgeLayers = it)) }
        PercentSlider("Strength", v.gyroEdgeStrength, 0.2f..4f) { set(v.copy(gyroEdgeStrength = it)) }
        if (!v.gyroEdgeTilt) IntSlider("Spring back %d ms", v.gyroEdgeSpringMs, 100..1500, step = 50) { set(v.copy(gyroEdgeSpringMs = it)) }
        FloatSlider("Line %.1f dp", v.gyroEdgeWidthDp, 0.5f..8f, 0.5f) { set(v.copy(gyroEdgeWidthDp = it)) }
        FloatSlider("Inset %.0f dp", v.gyroEdgeInsetDp, 0f..60f, 1f) { set(v.copy(gyroEdgeInsetDp = it)) }
        SwitchRow("Move with the phone", v.gyroEdgeInvert) { set(v.copy(gyroEdgeInvert = it)) }
        SwitchRow("Tint by ratchet", v.gyroEdgeRatchetTint) { set(v.copy(gyroEdgeRatchetTint = it)) }
        Hint("Off: the frame lags behind the phone, like a window onto a still world. Tint: it dims and takes the live axes' colours while a ratchet pauses some.")
    }

    if (v.gyroPhone || v.gyroHorizon || v.gyroEdge) {
        IntSlider("Level at %d°", v.gyroLevelDeg, 0..180, step = 5) { set(v.copy(gyroLevelDeg = it)) }
        Hint(
            "The real pose shown square on: 0° upright facing you, 90° flat with the screen up, 180° upright facing away. " +
                    "Set it to how you hold the phone.",
        )
        SwitchRow("Reset on ratchet", v.gyroResetOnRatchet) { set(v.copy(gyroResetOnRatchet = it)) }
        Hint("Each ratchet press sets the gyro-mode phone and the heading tape straight again.")
    }
    if (v.anyGyro) PercentSlider("Opacity", v.gyroOpacity, 0.1f..1f) { set(v.copy(gyroOpacity = it)) }
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
