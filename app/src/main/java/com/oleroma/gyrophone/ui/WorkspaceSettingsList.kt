package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroOrientation
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.RatchetAxes

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults

import com.oleroma.gyrophone.runtime.GamepadRuntime

/** Slider top: higher than the old 60, for fast flicks. */
internal const val MAX_GYRO_SENS = 150f

/**
 * One level of workspace settings ([WorkspaceDefaults.keys]): [ws] in effect over [parent], which
 * keys it sets itself ([isOwn], highlighted with a ↺), and edits. [parentName] names what a reset
 * goes back to. A workspace sits over the workspace defaults, the defaults over the built-in values.
 */
internal class WorkspaceLevel(
    val ws: Workspace,
    val parentName: String,
    val isOwn: (String) -> Boolean,
    val edit: ((Workspace) -> Workspace) -> Unit,
    val reset: (String) -> Unit,
    val resetAll: () -> Unit,
    /** The keys shown at this level: a layer sets only [WorkspaceDefaults.layerKeys]. */
    val shows: (String) -> Boolean = { true },
    /** Names this level for slider drafts: one per workspace and layer. */
    val key: String = ws.id,
) {
    /** Any of [keys] is set here; [resetKeys] sets them all back. */
    fun ownAny(keys: List<String>) = keys.any(isOwn)
    fun resetKeys(keys: List<String>): () -> Unit = { keys.forEach(reset) }
}

/** Workspace [ws]'s settings over [defaults]; [update] changes the workspace. */
internal fun workspaceLevel(ws: Workspace, defaults: Workspace, update: ((Workspace) -> Workspace) -> Unit): WorkspaceLevel {
    val own = WorkspaceDefaults.own(ws)
    return WorkspaceLevel(
        ws = ws,
        parentName = "Defaults",
        isOwn = { it in own },
        edit = update,
        reset = { k -> update { WorkspaceDefaults.reset(it, k, defaults) } },
        resetAll = { update { WorkspaceDefaults.resetAll(it, defaults) } },
    )
}

/**
 * Layer [setId] of workspace [ws]: its own settings over the workspace's (Base); only
 * [WorkspaceDefaults.layerKeys] show. [update] changes the workspace.
 */
internal fun layerLevel(ws: Workspace, setId: Int, update: ((Workspace) -> Workspace) -> Unit): WorkspaceLevel {
    val set = ws.sets.firstOrNull { it.id == setId }
    val own = WorkspaceDefaults.layerOwn(set)
    fun editSet(change: (Workspace, MappingSet) -> MappingSet) =
        update { w -> w.copy(sets = w.sets.map { if (it.id == setId) change(w, it) else it }) }
    return WorkspaceLevel(
        ws = WorkspaceDefaults.overlay(ws, set),
        parentName = "Base",
        isOwn = { it in own },
        edit = { change ->
            editSet { w, s ->
                val before = WorkspaceDefaults.overlay(w, s)
                WorkspaceDefaults.editLayer(s, before, change(before))
            }
        },
        reset = { k -> editSet { _, s -> WorkspaceDefaults.resetLayer(s, k) } },
        resetAll = { editSet { _, s -> s.copy(settings = null) } },
        shows = { it in WorkspaceDefaults.layerKeys },
        key = ws.id + "/layer" + setId,
    )
}

/** The settings of workspace [ws] at mapping set [setId]: Base is the workspace's own, else that layer's. */
internal fun setLevel(ws: Workspace, defaults: Workspace, setId: Int, update: ((Workspace) -> Workspace) -> Unit): WorkspaceLevel =
    if (setId != MappingSet.BASE && ws.sets.any { it.id == setId }) layerLevel(ws, setId, update)
    else workspaceLevel(ws, defaults, update)

/** The Defaults of the active profile over the built-in values. */
internal fun defaultsLevel(runtime: GamepadRuntime, profile: Profile): WorkspaceLevel {
    val d = profile.defaults
    val builtin = WorkspaceDefaults.builtin
    return WorkspaceLevel(
        ws = d,
        parentName = "built-in",
        isOwn = { !WorkspaceDefaults.same(it, d, builtin) },
        edit = { change -> runtime.editActiveProfile { it.copy(defaults = change(it.defaults)) } },
        reset = { k -> runtime.editActiveProfile { it.copy(defaults = WorkspaceDefaults.take(k, it.defaults, builtin)) } },
        resetAll = { runtime.editActiveProfile { p -> p.copy(defaults = WorkspaceDefaults.keys.fold(p.defaults) { r, k -> WorkspaceDefaults.take(k, r, builtin) }) } },
    )
}

private fun g(p: String) = WorkspaceDefaults.gyroKey(p)

/**
 * Every setting of one [level] (a workspace, one of its layers, or the Defaults): gyro, buttons,
 * haptics, and the touchpad and gestures, each highlighted with a ↺ where it differs from the
 * parent, as categories in the order the person put them ([ReorderableSections]); [extra] ones
 * join them. [touchpadOpen]: the touchpad's open (its ⚙ Settings in the mapping editor). [key]
 * restarts slider drafts when the level changes.
 */
@Composable
internal fun WorkspaceSettings(
    runtime: GamepadRuntime,
    level: WorkspaceLevel,
    key: String,
    touchpadOpen: Boolean = false,
    extra: List<SectionItem> = emptyList(),
) {
    val ws = level.ws
    fun editWs(change: (Workspace) -> Workspace) = level.edit(change)
    fun own(k: String) = level.isOwn(k)
    fun reset(k: String): () -> Unit = { level.reset(k) }

    val layer = !level.shows(WorkspaceDefaults.CONTROL_OPACITY)
    ResetAllButton(level.parentName, WorkspaceDefaults.keys.any { level.shows(it) && level.isOwn(it) }) { level.resetAll() }
    val items = ArrayList(extra)
    items += SectionItem("gyro") { card, header ->
        RememberedSection(runtime, "workspace.Gyro", "Gyro", card, defaultOpen = true, subtitle = ws.gyro.mode.label, headerModifier = header) {
            OverrideRow(
                "Gyro to mouse", own(g("mode")), reset(g("mode")),
                hint = when (ws.gyro.mode) {
                    GyroMode.ALWAYS -> "Gyro moves the mouse; a ratchet pauses it while held."
                    GyroMode.SENSOR_OFF -> "Gyro is off completely: the sensor is not read (saves battery), and ratchets and Toggle gyro do nothing."
                    else -> "Gyro is off; a ratchet turns it on while held."
                } + if (ws.gyro.mode == GyroMode.SENSOR_OFF) "" else
                    " Map Ratchet to a touch button, the workspace's one-finger Hold or a controller button; Toggle gyro swaps the two modes.",
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(GyroMode.ALWAYS, GyroMode.OFF, GyroMode.SENSOR_OFF).forEach { mode ->
                        FilterChip(selected = ws.gyro.mode == mode, onClick = {
                            editWs { it.copy(gyro = it.gyro.copy(mode = mode)) }
                        }, label = { Text(mode.label) })
                    }
                }
            }
            OverrideRow(
                "Ratchet acts on", own(g("ratchetAxes")), reset(g("ratchetAxes")),
                hint = "Only X: a ratchet stops or starts only the sideways motion (re-centre left and right while up and down keeps going).",
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RatchetAxes.entries.forEach { a ->
                        FilterChip(
                            selected = ws.gyro.ratchetAxes == a,
                            onClick = { editWs { it.copy(gyro = it.gyro.copy(ratchetAxes = a)) } },
                            label = { Text(a.label) },
                        )
                    }
                }
            }
            OverrideSlider(
                key + "gs", "Sensitivity %.0f", ws.gyro.sensitivity, 0f..MAX_GYRO_SENS, 1f, own(g("sensitivity")), reset(g("sensitivity")),
                hint = "Mouse counts per degree turned. Yaw, pitch and roll each follow it unless their ⚙ in the mapping editor sets their own; scrolling and sticks go faster or slower with it too.",
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(sensitivity = v)) } }
            OverrideRow("Held as", own(g("orientation")), reset(g("orientation"))) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GyroOrientation.entries.forEach { o ->
                        FilterChip(
                            selected = ws.gyro.orientation == o,
                            onClick = { editWs { it.copy(gyro = it.gyro.copy(orientation = o)) } },
                            label = { Text(o.label) })
                    }
                }
            }
            // Both filters default to off: raw gyro. Calibration only subtracts a fixed resting bias
            // and suits the phone, not a layer: the whole workspace's only.
            run {
                val tight = ws.gyro.tighteningThreshold
                OverrideSlider(
                    key + "tight", if (tight < 0.05f) "Jitter cutoff off" else "Jitter cutoff %.1f°/s", tight, 0f..5f, 0.1f,
                    own(g("tighteningThreshold")), reset(g("tighteningThreshold")),
                ) { v -> editWs { it.copy(gyro = it.gyro.copy(tighteningThreshold = if (v < 0.05f) 0f else v)) } }
                val smooth = ws.gyro.smoothingThreshold
                OverrideSlider(
                    key + "smooth", if (smooth < 0.05f) "Smoothing off" else "Smoothing below %.1f°/s", smooth, 0f..10f, 0.1f,
                    own(g("smoothingThreshold")), reset(g("smoothingThreshold")),
                ) { v -> editWs { it.copy(gyro = it.gyro.copy(smoothingThreshold = if (v < 0.05f) 0f else v)) } }
            }
            if (!layer) {
                val cal by runtime.calibrationState.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (val c = cal) {
                            GamepadRuntime.CalibrationState.None -> "Not calibrated (phone's own drift correction only)"
                            GamepadRuntime.CalibrationState.Running -> "Calibrating: keep the phone still…"
                            GamepadRuntime.CalibrationState.Moved -> "The phone moved. Lay it flat and try again."
                            is GamepadRuntime.CalibrationState.Calibrated -> "Calibrated (removes %.2f°/s drift)".format(c.degPerSec)
                        },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    )
                    if (cal is GamepadRuntime.CalibrationState.Calibrated) {
                        TextButton(onClick = { runtime.clearGyroCalibration() }) { Text("Clear") }
                    }
                    TextButton(
                        onClick = { runtime.calibrateGyro() },
                        enabled = cal != GamepadRuntime.CalibrationState.Running
                    ) {
                        Text("Calibrate")
                    }
                }
            }
        }
    }
    if (!layer) items += SectionItem("controls") { card, header ->
        RememberedSection(
            runtime,
            "workspace.Controls",
            "Controls",
            card,
            subtitle = "How visible the touch controls are",
            headerModifier = header,
        ) {
            OverrideSlider(
                key + "opacity", "Control opacity %.0f%%", ws.controlOpacity * 100, Profile.MIN_CONTROL_OPACITY * 100..100f, 1f,
                own(WorkspaceDefaults.CONTROL_OPACITY), reset(WorkspaceDefaults.CONTROL_OPACITY),
            ) { v -> editWs { it.copy(controlOpacity = v / 100f) } }
        }
    }
    items += SectionItem("haptics") { card, header ->
        RememberedSection(
            runtime,
            "workspace.Haptics",
            "Haptics",
            card,
            subtitle = "How haptic mappings feel and when ticks play",
            headerModifier = header,
        ) {
            OverrideRow(
                "Feel", own(WorkspaceDefaults.HAPTICS), reset(WorkspaceDefaults.HAPTICS),
                hint = "How haptic mappings without a feel of their own feel. They only play while a PC is connected.",
            ) {
                HapticFeelEditor(
                    ws.haptics,
                    held = false,
                    onChange = { f -> editWs { it.copy(haptics = f) } },
                    onTry = { runtime.previewHaptic(ws.haptics, false) },
                )
            }
            OverrideRow(
                "Ticks play", own(WorkspaceDefaults.HAPTIC_EDGE), reset(WorkspaceDefaults.HAPTIC_EDGE),
                hint = "When a source presses or lets go; for an area, when a finger enters or leaves it. A tick mapping can set its own.",
            ) {
                HapticEdgeChips(ws.hapticEdge, onSelect = { e -> if (e != null) editWs { it.copy(hapticEdge = e) } })
            }
        }
    }
    items += SectionItem("touchpad") { card, header ->
        TouchpadSection(
            runtime, level, key, if (touchpadOpen) "touchpad.Touchpad" else "workspace.Touchpad",
            open = touchpadOpen, card = card, header = header,
        )
    }
    ReorderableSections(runtime, "workspace", items)
}

/** The touchpad and gesture settings of [level]: gesture times, then the touchpad's own. */
@Composable
private fun TouchpadSection(
    runtime: GamepadRuntime,
    level: WorkspaceLevel,
    key: String,
    prefKey: String,
    open: Boolean,
    card: Modifier,
    header: Modifier,
) {
    val ws = level.ws
    RememberedSection(
        runtime,
        prefKey,
        "Touchpad & gestures",
        card,
        defaultOpen = open,
        headerModifier = header,
        subtitle = "Long press and double tap times, speed, scrolling and directions",
    ) {
        MsOverride(
            key + "long", "Long press after", ws.longPressMs, Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
            level.isOwn(WorkspaceDefaults.LONG_PRESS), { level.reset(WorkspaceDefaults.LONG_PRESS) },
            hint = "For the Tap and Long press of the touchpad, touch buttons, controller buttons and keyboard keys without a time of their own.",
        ) { ms -> level.edit { it.copy(longPressMs = ms) } }
        MsOverride(
            key + "double", "Double tap within", ws.doubleTapMs, Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
            level.isOwn(WorkspaceDefaults.DOUBLE_TAP), { level.reset(WorkspaceDefaults.DOUBLE_TAP) },
            hint = "The same, for Double tap windows.",
        ) { ms -> level.edit { it.copy(doubleTapMs = ms) } }
        SurfaceSharedSettings(
            workspaceSurfaceLevel(level),
            key + "tp",
            Bindings.holdZoneCounts(ws.mappings, ""),
            touchpad = true,
            resetAll = false,
        )
        Text(
            "Touchpad buttons use these too, unless they set their own (their Mappings, ⚙ Settings).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
