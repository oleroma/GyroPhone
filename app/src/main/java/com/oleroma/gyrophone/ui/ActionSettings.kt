package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.model.AxisGlide
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.holdDirection
import com.oleroma.gyrophone.core.model.swipeDirection
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.core.model.Where
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults

/**
 * What the mapping editor's ⚙ and Settings open for a control: [action] gives an action's own
 * settings (its ⚙, before its name), or null when it has none; [shared] the settings its actions
 * share (the top bar's Settings), null = none, so Settings opens the control's settings instead.
 */
internal class MappingSettings(
    val action: (BindableInput) -> (@Composable () -> Unit)?,
    val shared: (@Composable () -> Unit)?,
)

/** [content] as a composable value (a branch of a `when` gives it no expected type to infer that from). */
private fun ui(content: @Composable () -> Unit): @Composable () -> Unit = content

private const val TAP_WINDOW = "Tap window"
private const val TAP_HINT = "A press lifted within this is Quick; one held this long is Long."
private const val DOUBLE_WINDOW = "Second tap window"
private const val DOUBLE_HINT = "How soon the second press must come to count as Double. A Tap waits this long when the action has a Double mapping (unless it is set to Don't wait). 0 turns Double off."
private const val RELEASE_WINDOW = "Second tap release window"
private const val RELEASE_HINT = "How long the second press may last and still be a Double Quick. 0: a Double Quick fires as the second press starts."

/**
 * The ⚙ of a Touch direction [input] of a surface at [level] ([zones]: as mapped), or of a
 * Move X / Y ([touchpad]: the workspace's), else null.
 */
private fun directionGear(
    level: SurfaceLevel, key: String, input: BindableInput, zones: IntArray, timings: IntArray, touchpad: Boolean = false,
): (@Composable () -> Unit)? {
    val src = input.sources.firstOrNull() as? InputSource.Surface ?: return null
    val s = src.input
    if (s == SurfaceInput.MOTION_X || s == SurfaceInput.MOTION_Y) {
        val m = MoveSlot.of(src.fingers, s == SurfaceInput.MOTION_X)
        return ui { MoveGearSettings(level, key, m, touchpad) }
    }
    s.swipeDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = false, zones, timings) } }
    s.holdDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = true, zones, timings) } }
    return null
}


/**
 * Gyro [axis]'s inertia, glide curve and glide time in effect ([eff]), each highlighted with a ↺
 * where [own] sets it; [onSet] changes [own]. [unset]: what an unset one follows.
 */
@Composable
private fun AxisGlideSettings(key: String, axis: MotionAxis, eff: GyroSettings, own: AxisGlide, unset: String, onSet: (AxisGlide) -> Unit) {
    val name = axis.label
    val on = eff.inertiaOf(axis)
    OverrideSwitch(
        "$name inertia",
        (if (on) "When a ratchet turns $name off, its mouse and scrolling motion glides on and slows down." else "$name stops as soon as a ratchet turns it off.") +
            " $unset",
        on, own.inertia != null, { onSet(own.copy(inertia = null)) },
    ) { v -> onSet(own.copy(inertia = v)) }
    if (on) {
        InertiaCurveRow(eff.curveOf(axis), own.curve != null, { onSet(own.copy(curve = null)) }) { c -> onSet(own.copy(curve = c)) }
        OverrideSlider(
            key + "glide" + axis.name, "$name glide %.0f ms", eff.glideMsOf(axis), GyroMouse.MIN_GLIDE_MS..GyroMouse.MAX_GLIDE_MS, 5f,
            own.glideMs != null, { onSet(own.copy(glideMs = null)) }, hint = GLIDE_HINT,
        ) { v -> onSet(own.copy(glideMs = v)) }
    }
}

/**
 * The ⚙ and Settings of touch control [control] of [ws]: its press's tap and double windows
 * (over the workspace's), its area's options, a touchpad button's direction settings and shared settings (over the workspace defaults), or a
 * stick's. [update] changes the control.
 */
internal fun touchControlSettings(
    control: TouchControl,
    ws: Workspace,
    set: Int,
    update: ((TouchControl) -> TouchControl) -> Unit,
): MappingSettings {
    val key = ws.id + control.id
    val surface = if (control.swipes) buttonSurfaceLevel(control, ws.touchpad, update) else null
    val zones = Bindings.holdZoneCounts(ws.mappings, control.id)
    val hasArea = ws.mappings.any { it.source == InputSource.Touch(control.id) && it.trigger.where == Where.AREA }
    val timings = intArrayOf(control.longPress(ws), control.doubleTap(ws), control.doubleRelease(ws))
    return MappingSettings(
        action = { input ->
            val src = input.sources.firstOrNull()
            when {
                surface != null && directionGear(surface, key, input, zones, timings) != null -> directionGear(surface, key, input, zones, timings)

                // A button's press: its tap and double windows, and its area's options when an Area mapping uses it.
                src is InputSource.Touch && control.kind == TouchKind.BUTTON -> ui {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MsOverride(
                            key + "long", TAP_WINDOW, control.longPress(ws),
                            Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                            overridden = control.longPressMs != null,
                            onReset = { update { it.copy(longPressMs = null) } },
                            hint = TAP_HINT,
                        ) { ms -> update { it.copy(longPressMs = ms) } }
                        MsOverride(
                            key + "double", DOUBLE_WINDOW, control.doubleTap(ws),
                            Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                            overridden = control.doubleTapMs != null,
                            onReset = { update { it.copy(doubleTapMs = null) } },
                            hint = DOUBLE_HINT,
                        ) { ms -> update { it.copy(doubleTapMs = ms) } }
                        MsOverride(
                            key + "release", RELEASE_WINDOW, control.doubleRelease(ws),
                            Workspace.MIN_DOUBLE_RELEASE_MS..Workspace.MAX_DOUBLE_RELEASE_MS,
                            overridden = control.doubleReleaseMs != null,
                            onReset = { update { it.copy(doubleReleaseMs = null) } },
                            hint = RELEASE_HINT,
                        ) { ms -> update { it.copy(doubleReleaseMs = ms) } }
                        if (hasArea) AreaOptions(control, update)
                    }
                }

                src is InputSource.Touch && hasArea -> ui { AreaOptions(control, update) }

                else -> null
            }
        },
        shared = when {
            surface != null -> ui { SurfaceSharedSettings(surface, key, zones, touchpad = false) }

            control.kind == TouchKind.STICK -> ui { StickSettings(control) { c -> update { c } } }

            else -> null
        },
    )
}

/**
 * The ⚙ and Settings of the workspace touchpad: its settings are the workspace's own
 * ([level]), so Settings shows those, and an action's ⚙ edits them (gesture times, a direction's
 * own settings), each over the defaults.
 */
internal fun touchpadSettings(runtime: GamepadRuntime, level: WorkspaceLevel): MappingSettings {
    val ws = level.ws
    val key = level.key + "touchpad"
    val surface = workspaceSurfaceLevel(level)
    val zones = Bindings.holdZoneCounts(ws.mappings, "")
    val timings = intArrayOf(ws.longPressMs, ws.doubleTapMs, ws.doubleReleaseMs)
    return MappingSettings(
        action = { input ->
            when (val src = input.sources.firstOrNull()) {
                is InputSource.Gyro -> gyroGear(level, key, src.axis)
                is InputSource.Tilt -> tiltGear(level, key, src.axis == StickAxis.X)
                is InputSource.Shake -> shakeGear(level, key)
                else -> null
            } ?: directionGear(surface, key, input, zones, timings, touchpad = true) ?: pressTimings(input, level, key)
        },
        shared = { WorkspaceSettings(runtime, level, key, touchpadOpen = true) },
    )
}

/** A finger count's press: the workspace's tap and double windows, which every press without its own uses. */
private fun pressTimings(input: BindableInput, level: WorkspaceLevel, key: String): (@Composable () -> Unit)? {
    val src = input.sources.firstOrNull() as? InputSource.Surface ?: return null
    if (src.input != SurfaceInput.HOLD) return null
    val ws = level.ws
    return ui {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MsOverride(
                key + "long", TAP_WINDOW, ws.longPressMs,
                Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                overridden = level.isOwn(WorkspaceDefaults.LONG_PRESS),
                onReset = { level.reset(WorkspaceDefaults.LONG_PRESS) },
                hint = "$TAP_HINT The workspace's: every button and key without a window of its own uses it.",
            ) { ms -> level.edit { it.copy(longPressMs = ms) } }
            MsOverride(
                key + "double", DOUBLE_WINDOW, ws.doubleTapMs,
                Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                overridden = level.isOwn(WorkspaceDefaults.DOUBLE_TAP),
                onReset = { level.reset(WorkspaceDefaults.DOUBLE_TAP) },
                hint = "$DOUBLE_HINT The workspace's: every button and key without a window of its own uses it.",
            ) { ms -> level.edit { it.copy(doubleTapMs = ms) } }
            MsOverride(
                key + "release", RELEASE_WINDOW, ws.doubleReleaseMs,
                Workspace.MIN_DOUBLE_RELEASE_MS..Workspace.MAX_DOUBLE_RELEASE_MS,
                overridden = level.isOwn(WorkspaceDefaults.DOUBLE_RELEASE),
                onReset = { level.reset(WorkspaceDefaults.DOUBLE_RELEASE) },
                hint = "$RELEASE_HINT The workspace's: every button and key without a window of its own uses it.",
            ) { ms -> level.edit { it.copy(doubleReleaseMs = ms) } }
        }
    }
}

/**
 * The ⚙ of gyro [axis] at [level]: its sensitivity, its own or the level's shared Sensitivity
 * (↺ follows that again).
 */
private fun gyroGear(level: WorkspaceLevel, key: String, axis: MotionAxis): @Composable () -> Unit = ui {
    val g = level.ws.gyro
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RatchetModeRow(
            axis, g.ownMode(axis) ?: g.mode, g.ownMode(axis) != null,
            onReset = { level.edit { it.copy(gyro = it.gyro.withMode(axis, null)) } },
            hint = "Unset, it follows the gyro mode in Settings." + if (g.mode == GyroMode.SENSOR_OFF) " Gyro is off there now." else "",
        ) { m -> level.edit { it.copy(gyro = it.gyro.withMode(axis, m)) } }
        GyroSensSlider(
            key + "sens" + axis.name, "${axis.label} sensitivity", g.sensitivityOf(axis), g,
            overridden = g.ownSensitivity(axis) != null,
            onReset = { level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, null)) } },
            hint = "How fast ${axis.label.lowercase()} turns; scrolling and sticks go faster or slower with it too. " +
                "Unset, it follows the shared Sensitivity (${g.rwsOf(g.sensitivity)?.let { "%.2f RWS".format(it) } ?: "%.0f".format(g.sensitivity)}) in Settings.",
        ) { v -> level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, v)) } }
        AxisGlideSettings(
            key, axis, g, g.ownGlide(axis), unset = "Unset, it follows the shared Inertia in Settings.",
        ) { a -> level.edit { it.copy(gyro = it.gyro.withGlide(axis, a)) } }
    }
}

/** The ⚙ of sideways ([x]) or forward and back tilt at [level]: its range and deadzone, its own or the shared ones. */
private fun tiltGear(level: WorkspaceLevel, key: String, x: Boolean): @Composable () -> Unit = ui {
    val g = level.ws.gyro
    val what = if (x) "Sideways tilt" else "Forward and back tilt"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OverrideSlider(
            key + "tiltRange" + x, "$what: full at %.0f°", g.tiltRangeOf(x),
            GyroSettings.MIN_TILT_RANGE_DEG..GyroSettings.MAX_TILT_RANGE_DEG, 1f,
            overridden = (if (x) g.tiltXRangeDeg else g.tiltYRangeDeg) != null,
            onReset = { level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXRangeDeg = null) else it.gyro.copy(tiltYRangeDeg = null)) } },
            hint = "How far it leans for a full push. Unset, the shared Tilt range in Settings (%.0f°).".format(g.tiltRangeDeg),
        ) { v -> level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXRangeDeg = v) else it.gyro.copy(tiltYRangeDeg = v)) } }
        val dz = g.tiltDeadzoneOf(x)
        OverrideSlider(
            key + "tiltDz" + x, if (dz < 0.5f) "$what deadzone: off" else "$what deadzone %.0f°", dz,
            0f..GyroSettings.MAX_TILT_DEADZONE_DEG, 1f,
            overridden = (if (x) g.tiltXDeadzoneDeg else g.tiltYDeadzoneDeg) != null,
            onReset = { level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXDeadzoneDeg = null) else it.gyro.copy(tiltYDeadzoneDeg = null)) } },
            hint = "Leaning less than this counts as level. Unset, the shared Tilt deadzone in Settings.",
        ) { v -> level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXDeadzoneDeg = v) else it.gyro.copy(tiltYDeadzoneDeg = v)) } }
    }
}

/** The ⚙ of Shake at [level]: how hard a shake must be (the level's, over its parent's). */
private fun shakeGear(level: WorkspaceLevel, key: String): @Composable () -> Unit = ui {
    val k = WorkspaceDefaults.gyroKey("shakeStrength")
    OverrideSlider(
        key + "shake", "Shake at %.0f m/s²", level.ws.gyro.shakeStrength,
        GyroSettings.MIN_SHAKE_STRENGTH..GyroSettings.MAX_SHAKE_STRENGTH, 1f,
        overridden = level.isOwn(k), onReset = { level.reset(k) },
        hint = "How hard a jolt counts as a shake: higher needs a harder shake.",
    ) { v -> level.edit { it.copy(gyro = it.gyro.copy(shakeStrength = v)) } }
}

/** Whether a ratchet disables or enables gyro [axis] ([mode]), with ↺ ([onReset]) when [overridden]. */
@Composable
private fun RatchetModeRow(
    axis: MotionAxis,
    mode: GyroMode,
    overridden: Boolean,
    onReset: () -> Unit,
    hint: String,
    onSet: (GyroMode) -> Unit,
) {
    OverrideRow("${axis.label}: a ratchet", overridden, onReset, hint) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in listOf(GyroMode.ALWAYS, GyroMode.OFF)) {
                FilterChip(selected = mode == m, onClick = { onSet(m) }, label = { Text(if (m == GyroMode.ALWAYS) "Disables it" else "Enables it") })
            }
        }
    }
}

/**
 * The ⚙ of a physical control's actions in [preset]: a key's tap and double windows,
 * its own over the workspace [ws]'s. [update] changes the preset.
 */
internal fun physicalSettings(
    preset: DevicePreset,
    ws: Workspace,
    update: ((DevicePreset) -> DevicePreset) -> Unit,
) = MappingSettings(
    action = { input ->
        val src = input.sources.firstOrNull() as? InputSource.Key
        val own = src?.let { preset.timing(it.keyCode) }
        val key = preset.id + ":" + src?.keyCode
        if (src == null) null else ui {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MsOverride(
                    key + "long", TAP_WINDOW, own?.longPressMs ?: ws.longPressMs,
                    Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                    overridden = own?.longPressMs != null,
                    onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = null) } } },
                    hint = "$TAP_HINT Kept in the preset. Without a time of its own it uses the workspace's.",
                ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = ms) } } }
                MsOverride(
                    key + "double", DOUBLE_WINDOW, own?.doubleTapMs ?: ws.doubleTapMs,
                    Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                    overridden = own?.doubleTapMs != null,
                    onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(doubleTapMs = null) } } },
                    hint = "$DOUBLE_HINT Kept in the preset. Without a window of its own it uses the workspace's.",
                ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(doubleTapMs = ms) } } }
                MsOverride(
                    key + "release", RELEASE_WINDOW, own?.doubleReleaseMs ?: ws.doubleReleaseMs,
                    Workspace.MIN_DOUBLE_RELEASE_MS..Workspace.MAX_DOUBLE_RELEASE_MS,
                    overridden = own?.doubleReleaseMs != null,
                    onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(doubleReleaseMs = null) } } },
                    hint = "$RELEASE_HINT Kept in the preset. Without a window of its own it uses the workspace's.",
                ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(doubleReleaseMs = ms) } } }
            }
        }
    },
    shared = null,
)

/** An area's options: whether it interrupts what a finger sliding in was doing, and resumes it. */
@Composable
private fun AreaOptions(control: TouchControl, update: ((TouchControl) -> TouchControl) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OverrideSwitch(
            "Area interrupts",
            "A finger that slides into the area stops what it was doing (cursor, stick, " +
                    "another button). One landing on the area but on no other control moves no cursor.",
            control.zoneInterrupt, overridden = false, onReset = null,
        ) { v -> update { it.copy(zoneInterrupt = v) } }
        if (control.zoneInterrupt) {
            OverrideSwitch(
                "Resume on exit",
                "Leaving the area, the finger goes back to what it was doing. Off: it does " +
                        "nothing more until it lifts.",
                control.zoneResume, overridden = false, onReset = null,
            ) { v -> update { it.copy(zoneResume = v) } }
        }
        Text(
            "An area acts while any finger is inside the control, wherever it touched down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
