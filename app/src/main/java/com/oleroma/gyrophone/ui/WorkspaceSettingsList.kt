package com.oleroma.gyrophone.ui

import com.oleroma.gyrophone.core.engine.GyroMouse
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

import com.oleroma.gyrophone.core.model.ChildOverride
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.GyroSpace
import com.oleroma.gyrophone.core.model.OverridingChildren
import com.oleroma.gyrophone.core.model.Profile

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults

import com.oleroma.gyrophone.runtime.GamepadRuntime

/** Slider top: high, for fast flicks. */
internal const val MAX_GYRO_SENS = 250f

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
    /** The children below this level that set any of these keys themselves (none for a level without children). */
    val children: (List<String>) -> List<ChildOverride> = { emptyList() },
    /** Makes every child follow this level again for these keys. */
    val resetChildren: (List<String>) -> Unit = {},
) {
    /** The children setting any of [keys] themselves, for a [ChildLine]; null = none. */
    fun kids(keys: List<String>): ChildInfo? =
        children(keys).takeIf { it.isNotEmpty() }?.let { c -> ChildInfo(c) { resetChildren(keys) } }

    fun kids(vararg keys: String): ChildInfo? = kids(keys.toList())

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
        children = { k -> OverridingChildren.ofWorkspace(ws, k) },
        resetChildren = { k -> update { OverridingChildren.resetWorkspace(it, k) } },
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
        children = { k -> OverridingChildren.ofLayer(ws, setId, k) },
        resetChildren = { k -> update { OverridingChildren.resetLayer(it, setId, k) } },
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
        children = { k -> OverridingChildren.ofDefaults(profile, k) },
        resetChildren = { k -> runtime.editActiveProfile { OverridingChildren.resetDefaults(it, k) } },
    )
}

private fun g(p: String) = WorkspaceDefaults.gyroKey(p)

/** One of the PC DualShock 4's gyro axes in the workspace settings: its phone axis and Invert, and how to set them. */
private class PadAxisRow(
    val title: String, val prop: String, val src: MotionAxis, val invert: Boolean,
    val set: (GyroSettings, MotionAxis?, Boolean?) -> GyroSettings,
)

/**
 * Every setting of one [level] (a workspace, one of its layers, or the Defaults): gyro, buttons,
 * haptics, and the touchpad and gestures, each highlighted with a ↺ where it differs from the
 * parent, as categories in the order the person put them ([ReorderableSections]); [extra] ones
 * join them. [key]
 * restarts slider drafts when the level changes.
 */
@Composable
internal fun WorkspaceSettings(
    runtime: GamepadRuntime,
    level: WorkspaceLevel,
    key: String,
    extra: List<SectionItem> = emptyList(),
) {
    val ws = level.ws
    fun editWs(change: (Workspace) -> Workspace) = level.edit(change)
    fun own(k: String) = level.isOwn(k)
    fun reset(k: String): () -> Unit = { level.reset(k) }
    fun kids(vararg k: String) = level.kids(*k)

    val layer = !level.shows(WorkspaceDefaults.CONTROL_OPACITY)
    NotSearching {
        // The jitter cutoff and smoothing are no longer shown or used here, so an old own value of theirs does not light the button.
        val retired = setOf(g("tighteningThreshold"), g("smoothingThreshold"))
        ResetAllButton(level.parentName, WorkspaceDefaults.keys.any { it !in retired && level.shows(it) && level.isOwn(it) }) { level.resetAll() }
    }
    val items = ArrayList(extra)
    items += SectionItem("gyro", "Gyro") { card, header ->
        RememberedSection(
            runtime, "workspace.Gyro", "Gyro", card, defaultOpen = true,
            subtitle = ws.gyro.mode.label + " · sensitivity, acceleration, game, gyro space, inertia, calibration", headerModifier = header,
        ) {
            OverrideRow(
                "Gyro to mouse", own(g("mode")), reset(g("mode")), children = kids(g("mode")),
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
            if (level.shows(g("gameCountsPer360"))) {
                GameTurnRow(runtime, level, own(g("gameCountsPer360")), kids(g("gameName"), g("gameCountsPer360"))) {
                    level.reset(g("gameCountsPer360")); level.reset(g("gameName"))
                }
            }
            if (level.shows(g("gyroSpace"))) {
                OverrideRow(
                    "Gyro space", own(g("gyroSpace")), reset(g("gyroSpace")), children = kids(g("gyroSpace")),
                    hint = when (ws.gyro.gyroSpace) {
                        GyroSpace.LOCAL -> "Turns are measured about the phone's own axes: yaw through the screen, pitch across it, roll along it. " +
                            "With the phone tilted, turning left and right mixes yaw and roll."
                        GyroSpace.WORLD -> "Turns are measured against gravity: yaw is turning around the vertical, however the phone is tilted; " +
                            "pitch is tilting around the flat axis across the screen, roll around the flat axis along it."
                        GyroSpace.PLAYER -> "Yaw blends the phone's yaw and roll by how gravity lies: like World, but more forgiving when you lean or lie down. " +
                            "Pitch and roll stay the phone's."
                    },
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GyroSpace.entries.forEach { sp ->
                            FilterChip(selected = ws.gyro.gyroSpace == sp, onClick = {
                                editWs { it.copy(gyro = it.gyro.copy(gyroSpace = sp)) }
                            }, label = { Text(sp.label) })
                        }
                    }
                }
            }
            if (level.shows(g("padYaw"))) {
                // The PC's DualShock 4 or DualSense gyro: which phone axis each pad axis follows, and Invert.
                val g0 = ws.gyro
                listOf(
                    PadAxisRow("PC pad yaw", "padYaw", g0.padYaw, g0.padYawInvert) { s, a, i ->
                        s.copy(padYaw = a ?: s.padYaw, padYawInvert = i ?: s.padYawInvert)
                    },
                    PadAxisRow("PC pad pitch", "padPitch", g0.padPitch, g0.padPitchInvert) { s, a, i ->
                        s.copy(padPitch = a ?: s.padPitch, padPitchInvert = i ?: s.padPitchInvert)
                    },
                    PadAxisRow("PC pad roll", "padRoll", g0.padRoll, g0.padRollInvert) { s, a, i ->
                        s.copy(padRoll = a ?: s.padRoll, padRollInvert = i ?: s.padRollInvert)
                    },
                ).forEachIndexed { n, row ->
                    OverrideRow(
                        row.title, own(g(row.prop)) || own(g(row.prop + "Invert")),
                        { level.reset(g(row.prop)); level.reset(g(row.prop + "Invert")) },
                        children = kids(g(row.prop), g(row.prop + "Invert")),
                        hint = if (n == 0) "With the companion's Gamepad output on DualShock 4 or DualSense, the pad's gyro and accelerometer carry the phone's turn " +
                            "for games with their own controller gyro, whatever the gyro mouse does. Pick the phone axis each pad axis follows: " +
                            "by default yaw turns through the screen, pitch across it and roll along it. Turn the gyro mouse off where the game aims with it." else null,
                    ) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (MotionAxis.shown + MotionAxis.NONE).forEach { ax ->
                                FilterChip(selected = row.src == ax, onClick = {
                                    editWs { it.copy(gyro = row.set(it.gyro, ax, null)) }
                                }, label = { Text(ax.label) })
                            }
                            FilterChip(selected = row.invert, onClick = {
                                editWs { it.copy(gyro = row.set(it.gyro, null, !row.invert)) }
                            }, label = { Text("Invert") })
                        }
                    }
                }
            }
            GyroSensSlider(
                key + "gs", "Sensitivity", ws.gyro.sensitivity, ws.gyro, own(g("sensitivity")), reset(g("sensitivity")),
                hint = "Yaw, pitch and roll each follow it unless their ⚙ in the mapping editor sets their own; scrolling and sticks go faster or slower with it too.",
                children = kids(g("sensitivity")),
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(sensitivity = v)) } }
            AccelCurveSetting(
                key + "gaccel", "Acceleration", ws.gyro.accel ?: GyroCurve.OFF, ws.gyro.sensitivity, ws.gyro,
                own(g("accel")), reset(g("accel")),
                hint = "Yaw, pitch and roll each follow it unless their ⚙ in the mapping editor sets their own; a button can set its own while held. $ACCEL_HINT",
                children = kids(g("accel")),
            ) { c -> editWs { it.copy(gyro = it.gyro.copy(accel = c)) } }
            OverrideSwitch(
                "Inertia",
                (if (ws.gyro.inertia) "When a ratchet turns gyro off, the mouse and scrolling glide on and slow down; turning it on again stops the glide."
                else "Gyro motion stops as soon as a ratchet turns it off.") + " Sticks never glide. Yaw, pitch and roll follow it unless their ⚙ sets their own.",
                ws.gyro.inertia, own(g("inertia")), reset(g("inertia")), children = kids(g("inertia")),
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(inertia = v)) } }
            InertiaCurveRow(ws.gyro.inertiaCurve, own(g("inertiaCurve")), reset(g("inertiaCurve")), kids(g("inertiaCurve"))) { c ->
                editWs { it.copy(gyro = it.gyro.copy(inertiaCurve = c)) }
            }
            OverrideSlider(
                key + "gglide", "Glide %.0f ms", ws.gyro.glideMs, GyroMouse.MIN_GLIDE_MS..GyroMouse.MAX_GLIDE_MS, 5f,
                own(g("glideMs")), reset(g("glideMs")), hint = GLIDE_HINT, children = kids(g("glideMs")),
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(glideMs = v)) } }
            if (!layer) GyroCalibration(runtime)
        }
    }
    items += SectionItem("tilt", "Tilt & shake") { card, header ->
        RememberedSection(
            runtime, "workspace.Tilt", "Tilt & shake", card,
            subtitle = "How far a full tilt leans, its deadzone, how hard a shake is", headerModifier = header,
        ) {
            OverrideSlider(
                key + "tiltRange", "Tilt range %.0f°", ws.gyro.tiltRangeDeg,
                GyroSettings.MIN_TILT_RANGE_DEG..GyroSettings.MAX_TILT_RANGE_DEG, 1f, own(g("tiltRangeDeg")), reset(g("tiltRangeDeg")), children = kids(g("tiltRangeDeg")),
                hint = "How far the phone leans for a full tilt. Sideways and forward-and-back tilt follow it unless their ⚙ sets their own.",
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(tiltRangeDeg = v)) } }
            OverrideSlider(
                key + "tiltDz", if (ws.gyro.tiltDeadzoneDeg < 0.5f) "Tilt deadzone: off" else "Tilt deadzone %.0f°", ws.gyro.tiltDeadzoneDeg,
                0f..GyroSettings.MAX_TILT_DEADZONE_DEG, 1f, own(g("tiltDeadzoneDeg")), reset(g("tiltDeadzoneDeg")), children = kids(g("tiltDeadzoneDeg")),
                hint = "Leaning less than this counts as level.",
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(tiltDeadzoneDeg = v)) } }
            OverrideSlider(
                key + "shake", "Shake at %.0f m/s²", ws.gyro.shakeStrength,
                GyroSettings.MIN_SHAKE_STRENGTH..GyroSettings.MAX_SHAKE_STRENGTH, 1f, own(g("shakeStrength")), reset(g("shakeStrength")), children = kids(g("shakeStrength")),
                hint = "How hard a jolt counts as a shake.",
            ) { v -> editWs { it.copy(gyro = it.gyro.copy(shakeStrength = v)) } }
        }
    }
    if (!layer) items += SectionItem("controls", "Buttons") { card, header ->
        RememberedSection(
            runtime,
            "workspace.Controls",
            "Buttons",
            card,
            subtitle = "How visible the touch controls are",
            headerModifier = header,
        ) {
            OverrideSlider(
                key + "opacity", "Control opacity %.0f%%", ws.controlOpacity * 100, Profile.MIN_CONTROL_OPACITY * 100..100f, 1f,
                own(WorkspaceDefaults.CONTROL_OPACITY), reset(WorkspaceDefaults.CONTROL_OPACITY), children = kids(WorkspaceDefaults.CONTROL_OPACITY),
            ) { v -> editWs { it.copy(controlOpacity = v / 100f) } }
        }
    }
    items += SectionItem("haptics", "Haptics") { card, header ->
        RememberedSection(
            runtime,
            "workspace.Haptics",
            "Haptics",
            card,
            subtitle = "How haptic mappings feel and when ticks play",
            headerModifier = header,
        ) {
            OverrideRow(
                "Feel", own(WorkspaceDefaults.HAPTICS), reset(WorkspaceDefaults.HAPTICS), children = kids(WorkspaceDefaults.HAPTICS),
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
                "Ticks play", own(WorkspaceDefaults.HAPTIC_EDGE), reset(WorkspaceDefaults.HAPTIC_EDGE), children = kids(WorkspaceDefaults.HAPTIC_EDGE),
                hint = "When a source presses or lets go; for Inside, when a finger enters or leaves the button. A tick mapping can set its own.",
            ) {
                HapticEdgeChips(ws.hapticEdge, onSelect = { e -> if (e != null) editWs { it.copy(hapticEdge = e) } })
            }
        }
    }
    items += SectionItem("gestures", "Taps") { card, header ->
        RememberedSection(
            runtime, "workspace.Gestures", "Taps", card,
            subtitle = "Tap time", headerModifier = header,
        ) {
            MsOverride(
                key + "long", "Tap time", ws.longPressMs, Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                level.isOwn(WorkspaceDefaults.LONG_PRESS), { level.reset(WorkspaceDefaults.LONG_PRESS) }, children = kids(WorkspaceDefaults.LONG_PRESS),
                hint = "One time for every gesture: a shorter press is a tap, a longer one a long press, and a second touch this " +
                        "soon after a lift a double tap (on a double tap with a Held chip and others, a quick lift fires the others, " +
                        "staying down or moving the Held one). For the touchpad, touch buttons, controller buttons and keys without a time of their own.",
            ) { ms -> level.edit { it.copy(longPressMs = ms) } }
        }
    }
    // The touchpad right after the gyro.
    items.add(items.indexOfFirst { it.id == "gyro" } + 1, surfaceCategory(runtime, "Touchpad", workspaceSurfaceLevel(level), key + "tp", Bindings.holdZoneCounts(ws.mappings, ""), touchpad = true))
    ReorderableSections(runtime, "workspace", items)
}

/**
 * The end of a workspace's Gyro tab: drift calibration. It only subtracts a fixed resting bias and
 * suits the phone, not a layer, so only the whole workspace shows it. The jitter cutoff and
 * smoothing are the app's (App › Gyro), the same in every workspace.
 */
@Composable
private fun GyroCalibration(runtime: GamepadRuntime) {
    SettingsGroupTitle("Calibration")
    Hint("Jitter cutoff and smoothing are in App › Gyro: one setting for every workspace.")
    val cal by runtime.calibrationState.collectAsState()
    Searchable("Calibrate", "gyro drift calibration") { Row(verticalAlignment = Alignment.CenterVertically) {
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
    } }
}
