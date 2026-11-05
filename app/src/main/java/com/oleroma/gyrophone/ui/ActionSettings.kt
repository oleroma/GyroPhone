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
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.PhysicalSticks
import com.oleroma.gyrophone.core.model.StickCurve
import com.oleroma.gyrophone.core.model.StickTuning
import com.oleroma.gyrophone.core.model.TriggerTuning
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.holdDirection
import com.oleroma.gyrophone.core.model.swipeDirection
import com.oleroma.gyrophone.runtime.GamepadRuntime
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
    /** A touch control's shared settings as categories, for its one settings page; [itemsHeader] goes above them (its Reset all). */
    val items: List<SectionItem> = emptyList(),
    val itemsHeader: (@Composable () -> Unit)? = null,
)

/** [content] as a composable value (a branch of a `when` gives it no expected type to infer that from). */
private fun ui(content: @Composable () -> Unit): @Composable () -> Unit = content

/**
 * Which gesture timing an action has: a long press its time (a tap too: a longer press is no tap),
 * a double tap its window.
 */
private enum class Timing { LONG_PRESS, DOUBLE_TAP }

private fun timingOf(input: BindableInput): Timing? = when (val s = input.sources.firstOrNull()) {
    is InputSource.TouchTap, is InputSource.TouchLongPress -> Timing.LONG_PRESS
    is InputSource.TouchDoubleTap -> Timing.DOUBLE_TAP
    // Area and Outside: the button's own times.
    is InputSource.TouchArea -> when (s.on) {
        Trigger.TAP, Trigger.LONG_PRESS -> Timing.LONG_PRESS
        Trigger.DOUBLE_TAP -> Timing.DOUBLE_TAP
        else -> null
    }
    // A swipe's Long hold and Double swipe: the surface's long press and double tap times.
    is InputSource.Surface -> when {
        s.on == Trigger.LONG_PRESS -> Timing.LONG_PRESS
        s.on == Trigger.DOUBLE_TAP -> Timing.DOUBLE_TAP
        s.input == SurfaceInput.TAP || s.input == SurfaceInput.LONG_PRESS -> Timing.LONG_PRESS
        s.input == SurfaceInput.DOUBLE_TAP -> Timing.DOUBLE_TAP
        else -> null
    }
    else -> null
}

/** [input] is a swipe direction's Long hold or Double swipe (its timing settings are named for swipes). */
private fun isSwipeTiming(input: BindableInput): Boolean {
    val s = input.sources.firstOrNull() as? InputSource.Surface ?: return false
    return s.on != Trigger.HOLD && (s.input.swipeDirection != null || s.input.holdDirection != null)
}

/** The long press time's name and hint for [input] ([swipe]: a Long hold); [whose]: whose time it is. */
private fun longLabel(swipe: Boolean) = if (swipe) "Long hold after" else "Long press after"

private fun longHint(swipe: Boolean, whose: String) =
    if (swipe) "How long a held direction is held before its Long hold fires. It is $whose long press time, shared by its taps and long presses."
    else "$LONG_HINT It is $whose."

private fun doubleLabel(swipe: Boolean) = if (swipe) "Double swipe within" else "Double tap within"

private fun doubleHint(swipe: Boolean, whose: String) =
    if (swipe) "The longest gap between the two quick swipes of a Double swipe. It is $whose double tap window, shared by its double taps."
    else "The longest gap between the taps. A tap waits this long when a double tap does something else. It is $whose."

/**
 * The ⚙ of a Swipe or Swipe hold [input] of a surface at [level] ([zones]: as mapped), or of a
 * Move X / Y ([touchpad]: the workspace's), else null.
 */
private fun directionGear(level: SurfaceLevel, key: String, input: BindableInput, zones: IntArray, touchpad: Boolean = false): (@Composable () -> Unit)? {
    val src = input.sources.firstOrNull() as? InputSource.Surface ?: return null
    // A Long hold or Double swipe: its timing instead ([timingOf]).
    if (src.on != Trigger.HOLD) return null
    val s = src.input
    if (s == SurfaceInput.MOTION_X || s == SurfaceInput.MOTION_Y) {
        val m = MoveSlot.of(src.fingers, s == SurfaceInput.MOTION_X)
        return ui { MoveGearSettings(level, key, m, touchpad) }
    }
    s.swipeDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = false, zones) } }
    s.holdDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = true, zones) } }
    return null
}

/** How a gyro sensitivity of [g]'s workspace reads: as RWS when the game's turn is known, else counts per degree. */
internal fun gyroShow(g: GyroSettings): (Float) -> String = { s ->
    g.rwsOf(s)?.let { "%.2f RWS".format(it) } ?: "%.1f".format(s).removeSuffix(".0").removeSuffix(",0")
}

private const val LONG_HINT = "How long a press is held to be a long press; a shorter one is a tap."

/**
 * A button's gyro sensitivities in mapping set [set] of [ws] ([TouchControl.gyro]): the ones it
 * uses there (its own in [set] over its Base ones) and the workspace's gyro below them.
 */
private class ButtonGyroLevel(
    ws: Workspace,
    val set: Int,
    all: List<ButtonGyro>,
    val edit: ((ButtonGyro) -> ButtonGyro) -> Unit,
) {
    private val sets = if (set == MappingSet.BASE) listOf(MappingSet.BASE) else listOf(MappingSet.BASE, set)
    private val layered = WorkspaceDefaults.layered(ws, MappingSet.BASE, sets.drop(1))
    val parent: GyroSettings = layered.gyro
    val touchpad: TouchpadSettings = layered.touchpad
    val inUse: ButtonGyro = sets.fold(ButtonGyro()) { r, s -> all.firstOrNull { it.set == s }?.let { r.under(it) } ?: r }
    val own: ButtonGyro = all.firstOrNull { it.set == set } ?: ButtonGyro(set)
    val where = if (set == MappingSet.BASE) "" else " in this layer"

    companion object {
        fun of(control: TouchControl, ws: Workspace, set: Int, update: ((TouchControl) -> TouchControl) -> Unit) =
            ButtonGyroLevel(ws, set, control.gyro) { change -> update { it.withGyro(set, change) } }
    }
}

/**
 * The button's settings while held: its gyro Sensitivity and Acceleration (every gyro axis's
 * unless the axis has its own) and the touchpad cursor's sensitivity and acceleration.
 */
@Composable
private fun ButtonGyroSensitivity(g: ButtonGyroLevel, key: String) {
    GyroSensSlider(
        key + "gyro" + g.set, "Gyro sensitivity", g.inUse.sensitivity ?: g.parent.sensitivity, g.parent,
        overridden = g.own.sensitivity != null,
        onReset = { g.edit { it.copy(sensitivity = null) } },
        hint = "While this is held (the last pressed of several), the gyro turns at this sensitivity${g.where}, whatever this button maps: " +
            "a lower one aims finer. Yaw, pitch and roll follow it unless their ⚙ sets their own. Unset, the workspace's.",
    ) { v -> g.edit { it.copy(sensitivity = v) } }
    AccelCurveSetting(
        key + "gyroAccel" + g.set, "Gyro acceleration", g.inUse.accel ?: g.parent.accel ?: GyroCurve.OFF,
        g.inUse.sensitivity ?: g.parent.sensitivity, g.parent,
        overridden = g.own.accel != null,
        onReset = { g.edit { it.copy(accel = null) } },
        hint = "While this is held${g.where}: Off aims without acceleration (for example while aiming down sights). " +
            "Unset, the workspace's. $ACCEL_HINT",
    ) { c -> g.edit { it.copy(accel = c) } }
    val touchHint = "While this is held${g.where}, the touchpad cursor moves at this speed, for every finger count. Unset, the workspace's."
    val touchOwn = g.own.touchSensitivity != null || g.own.touchScale != null
    val touchReset = { g.edit { it.withTouch(null) } }
    val touchBase = g.inUse.touchSensitivity ?: g.touchpad.sensitivity
    ScaledSens(
        key + "touchScale" + g.set, "Touchpad sensitivity", g.inUse.touchScale, touchBase, { "%.1f".format(it) },
        overridden = touchOwn, onReset = touchReset,
        hint = "$touchHint With × Parent each finger count's own speed is scaled.",
        onMode = { scaled ->
            val now = g.inUse.touchScale?.let { it * touchBase } ?: touchBase
            g.edit { if (scaled) it.withTouchScale(1f) else it.withTouch(now) }
        },
        onScale = { f -> g.edit { it.withTouchScale(f) } },
    ) {
        OverrideSlider(
            key + "touchSens" + g.set, "Touchpad sensitivity %.1f", g.inUse.touchSensitivity ?: g.touchpad.sensitivity,
            0f..MAX_SURFACE_SENS, 0.1f,
            overridden = touchOwn, onReset = touchReset, hint = touchHint,
        ) { v -> g.edit { it.withTouch(v) } }
    }
    OverrideSlider(
        key + "touchAcc" + g.set, "Touchpad acceleration %.1f", g.inUse.touchAcceleration ?: g.touchpad.acceleration,
        0f..2f, 0.1f,
        overridden = g.own.touchAcceleration != null,
        onReset = { g.edit { it.copy(touchAcceleration = null) } },
        hint = "While this is held${g.where}: the touchpad cursor's extra speed for fast swipes (0 = none). Unset, the workspace's.",
    ) { v -> g.edit { it.copy(touchAcceleration = v) } }
}

/** The ⚙ of gyro [axis] of a button: its ratchet mode and sensitivity while held, its own or else the workspace's. */
private fun buttonGyroGear(g: ButtonGyroLevel, key: String, axis: MotionAxis): @Composable () -> Unit = ui {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RatchetModeRow(
            axis, g.inUse.ownMode(axis) ?: g.parent.ownMode(axis) ?: g.parent.mode, g.own.ownMode(axis) != null,
            onReset = { g.edit { it.withMode(axis, null) } },
            hint = "While this is held${g.where}. Unset, the workspace's.",
        ) { m -> g.edit { it.withMode(axis, m) } }
        val hint = "While this is held: how fast ${axis.label.lowercase()} turns${g.where}. Unset, it follows this button's " +
            "Gyro sensitivity (in Settings), else the workspace's."
        val own = g.own.own(axis) != null || g.own.ownScale(axis) != null
        val reset = { g.edit { it.with(axis, null).withScale(axis, null) } }
        ScaledSens(
            key + "gyroScale" + g.set + axis.name, "${axis.label} sensitivity", g.inUse.ownScale(axis),
            g.inUse.own(axis) ?: g.inUse.sensitivity ?: g.parent.exactOf(axis), gyroShow(g.parent),
            overridden = own, onReset = reset, hint = hint,
            onMode = { scaled ->
                val now = g.inUse.sensitivityOf(axis, g.parent)
                g.edit { if (scaled) it.withScale(axis, 1f) else it.with(axis, now) }
            },
            onScale = { f -> g.edit { it.withScale(axis, f) } },
        ) {
            GyroSensSlider(
                key + "gyro" + g.set + axis.name, "${axis.label} sensitivity", g.inUse.sensitivityOf(axis, g.parent), g.parent,
                overridden = own, onReset = reset, hint = hint,
            ) { v -> g.edit { it.with(axis, v) } }
        }
        AccelCurveSetting(
            key + "gyroAccel" + g.set + axis.name, "${axis.label} acceleration", g.inUse.accelOf(axis, g.parent),
            g.inUse.sensitivityOf(axis, g.parent), g.parent,
            overridden = g.own.ownAccel(axis) != null,
            onReset = { g.edit { it.withAccel(axis, null) } },
            hint = "While this is held${g.where}. Unset, it follows this button's Gyro acceleration (in Settings), else the workspace's.",
            perAxis = true,
            axis = axis,
        ) { c -> g.edit { it.withAccel(axis, c) } }
        val eff = g.inUse.over(g.parent)
        AxisGlideSettings(
            key + "gyro" + g.set, axis, eff, g.own.ownGlide(axis),
            unset = "While this is held${g.where}. Unset, the workspace's.",
        ) { a -> g.edit { it.withGlide(axis, a) } }
    }
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
 * The ⚙ and Settings of touch control [control] of [ws]: its long press and double tap timings
 * (over the workspace's), its area's options, its gyro sensitivities in mapping set [set], a
 * touchpad button's direction settings and shared settings (over the workspace defaults), or a
 * stick's. [update] changes the control.
 */
internal fun touchControlSettings(
    runtime: GamepadRuntime,
    control: TouchControl,
    ws: Workspace,
    set: Int,
    update: ((TouchControl) -> TouchControl) -> Unit,
): MappingSettings {
    val key = ws.id + control.id
    val surface = if (control.swipes) buttonSurfaceLevel(control, ws.touchpad, update) else null
    val zones = Bindings.holdZoneCounts(ws.mappings, control.id)
    val gyro = if (control.kind == TouchKind.BUTTON) ButtonGyroLevel.of(control, ws, set, update) else null
    // The settings its actions share, as categories: its gyro, a touchpad button's surface, a stick's own.
    val gyroCategory = gyro?.let { g ->
        category(runtime, "buttonGyro", "While held", "Gyro sensitivity and acceleration, touchpad speed", prefKey = "button.Gyro") {
            ButtonGyroSensitivity(g, key)
        }
    }
    val items = listOfNotNull(gyroCategory) + when {
        surface != null -> surfaceCategories(runtime, surface, key, zones, touchpad = false)
        control.kind == TouchKind.STICK -> listOf(
            category(runtime, "stick", "Stick", "Deadzone and directions", defaultOpen = true) { StickSettings(control) { c -> update { c } } },
        )
        else -> emptyList()
    }
    val header: (@Composable () -> Unit)? = surface?.let { sl ->
        ui { NotSearching { ResetAllButton(sl.parentName, sl.anyOwn) { sl.resetAll() } } }
    }
    return MappingSettings(
        action = { input ->
            val src = input.sources.firstOrNull()
            when {
                gyro != null && src is InputSource.Gyro -> buttonGyroGear(gyro, key, src.axis)

                surface != null && directionGear(surface, key, input, zones) != null -> directionGear(surface, key, input, zones)

                timingOf(input) == Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", longLabel(isSwipeTiming(input)), control.longPress(ws),
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = control.longPressMs != null,
                        onReset = { update { it.copy(longPressMs = null) } },
                        hint = if (isSwipeTiming(input)) longHint(true, "this button's") else LONG_HINT,
                    ) { ms -> update { it.copy(longPressMs = ms) } }
                }

                timingOf(input) == Timing.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "double", doubleLabel(isSwipeTiming(input)), control.doubleTap(ws),
                        Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                        overridden = control.doubleTapMs != null,
                        onReset = { update { it.copy(doubleTapMs = null) } },
                        hint = if (isSwipeTiming(input)) doubleHint(true, "this button's")
                        else "The longest gap between the taps. A tap waits this long when a double tap does something else.",
                    ) { ms -> update { it.copy(doubleTapMs = ms) } }
                }

                src is InputSource.TouchArea && src.kind == TouchAreaKind.ZONE -> ui { AreaOptions(control, update) }

                else -> null
            }
        },
        shared = if (items.isEmpty()) null else ui {
            header?.invoke()
            ReorderableSections(runtime, "control", items)
        },
        items = items,
        itemsHeader = header,
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
    return MappingSettings(
        action = { input ->
            when (val src = input.sources.firstOrNull()) {
                is InputSource.Gyro -> gyroGear(level, key, src.axis)
                is InputSource.Tilt -> tiltGear(level, key, src.axis == StickAxis.X)
                is InputSource.Shake -> shakeGear(level, key)
                else -> null
            } ?: directionGear(surface, key, input, zones, touchpad = true) ?: when (timingOf(input)) {
                Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", longLabel(isSwipeTiming(input)), ws.longPressMs,
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = level.isOwn(WorkspaceDefaults.LONG_PRESS),
                        onReset = { level.reset(WorkspaceDefaults.LONG_PRESS) },
                        hint = longHint(isSwipeTiming(input), "the workspace's: every button and key without a time of its own uses it"),
                    ) { ms -> level.edit { it.copy(longPressMs = ms) } }
                }

                Timing.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "double", doubleLabel(isSwipeTiming(input)), ws.doubleTapMs,
                        Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                        overridden = level.isOwn(WorkspaceDefaults.DOUBLE_TAP),
                        onReset = { level.reset(WorkspaceDefaults.DOUBLE_TAP) },
                        hint = doubleHint(isSwipeTiming(input), "the workspace's: every button and key without a window of its own uses it"),
                    ) { ms -> level.edit { it.copy(doubleTapMs = ms) } }
                }

                null -> null
            }
        },
        shared = { WorkspaceSettings(runtime, level, key) },
    )
}

/**
 * The ⚙ of gyro [axis] at [level]: its sensitivity, its own or the level's shared Sensitivity
 * (↺ follows that again).
 */
private fun gyroGear(level: WorkspaceLevel, key: String, axis: MotionAxis): @Composable () -> Unit = ui {
    val g = level.ws.gyro
    // level.ws has the parent's values filled in, so what this level sets comes from its own keys.
    fun k(prop: String) = WorkspaceDefaults.gyroKey(axis.name.lowercase() + prop)
    val ownGlide = g.ownGlide(axis).let { a ->
        AxisGlide(
            a.inertia.takeIf { level.isOwn(k("Inertia")) },
            a.glideMs.takeIf { level.isOwn(k("GlideMs")) },
            a.curve.takeIf { level.isOwn(k("Curve")) },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RatchetModeRow(
            axis, g.ownMode(axis) ?: g.mode, level.isOwn(k("Mode")),
            onReset = { level.reset(k("Mode")) },
            hint = "Unset, it follows the gyro mode in Settings." + if (g.mode == GyroMode.SENSOR_OFF) " Gyro is off there now." else "",
        ) { m -> level.edit { it.copy(gyro = it.gyro.withMode(axis, m)) } }
        val sensHint = "How fast ${axis.label.lowercase()} turns; scrolling and sticks go faster or slower with it too. " +
            "Unset, it follows the shared Sensitivity (${gyroShow(g)(g.sensitivity)}) in Settings."
        val sensOwn = level.isOwn(k("Sensitivity")) || level.isOwn(k("Scale"))
        val resetSens = { level.reset(k("Scale")); level.reset(k("Sensitivity")) }
        ScaledSens(
            key + "scale" + axis.name, "${axis.label} sensitivity", g.ownScale(axis), g.exactOf(axis), gyroShow(g),
            overridden = sensOwn, onReset = resetSens, hint = sensHint,
            onMode = { scaled ->
                if (scaled) {
                    // The factor multiplies the parent's exact value: this level's own one goes.
                    if (level.isOwn(k("Sensitivity"))) level.reset(k("Sensitivity"))
                    level.edit { it.copy(gyro = it.gyro.withScale(axis, 1f)) }
                } else {
                    val now = g.sensitivityOf(axis)
                    level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, now)) }
                }
            },
            onScale = { f -> level.edit { it.copy(gyro = it.gyro.withScale(axis, f)) } },
        ) {
            GyroSensSlider(
                key + "sens" + axis.name, "${axis.label} sensitivity", g.sensitivityOf(axis), g,
                overridden = sensOwn, onReset = resetSens, hint = sensHint,
            ) { v -> level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, v)) } }
        }
        AccelCurveSetting(
            key + "accel" + axis.name, "${axis.label} acceleration", g.accelOf(axis), g.sensitivityOf(axis), g,
            overridden = level.isOwn(k("Accel")),
            onReset = { level.reset(k("Accel")) },
            hint = "Unset, it follows the shared Acceleration in Settings. $ACCEL_HINT",
            perAxis = true,
            axis = axis,
        ) { c -> level.edit { it.copy(gyro = it.gyro.withAccel(axis, c)) } }
        AxisGlideSettings(
            key, axis, g, ownGlide, unset = "Unset, it follows the shared Inertia in Settings.",
        ) { a ->
            when {
                a.inertia == null && ownGlide.inertia != null -> level.reset(k("Inertia"))
                a.curve == null && ownGlide.curve != null -> level.reset(k("Curve"))
                a.glideMs == null && ownGlide.glideMs != null -> level.reset(k("GlideMs"))
                // Only the field set changes; the ones this level doesn't set keep the parent's.
                else -> level.edit { it.copy(gyro = it.gyro.withGlide(axis, it.gyro.ownGlide(axis).under(a))) }
            }
        }
    }
}

/** The ⚙ of sideways ([x]) or forward and back tilt at [level]: its range and deadzone, its own or the shared ones. */
private fun tiltGear(level: WorkspaceLevel, key: String, x: Boolean): @Composable () -> Unit = ui {
    val g = level.ws.gyro
    val what = if (x) "Sideways tilt" else "Forward and back tilt"
    val rangeKey = WorkspaceDefaults.gyroKey(if (x) "tiltXRangeDeg" else "tiltYRangeDeg")
    val dzKey = WorkspaceDefaults.gyroKey(if (x) "tiltXDeadzoneDeg" else "tiltYDeadzoneDeg")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OverrideSlider(
            key + "tiltRange" + x, "$what: full at %.0f°", g.tiltRangeOf(x),
            GyroSettings.MIN_TILT_RANGE_DEG..GyroSettings.MAX_TILT_RANGE_DEG, 1f,
            overridden = level.isOwn(rangeKey),
            onReset = { level.reset(rangeKey) },
            hint = "How far it leans for a full push. Unset, the shared Tilt range in Settings (%.0f°).".format(g.tiltRangeDeg),
        ) { v -> level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXRangeDeg = v) else it.gyro.copy(tiltYRangeDeg = v)) } }
        val dz = g.tiltDeadzoneOf(x)
        OverrideSlider(
            key + "tiltDz" + x, if (dz < 0.5f) "$what deadzone: off" else "$what deadzone %.0f°", dz,
            0f..GyroSettings.MAX_TILT_DEADZONE_DEG, 1f,
            overridden = level.isOwn(dzKey),
            onReset = { level.reset(dzKey) },
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
 * The ⚙ of a physical control's actions in [preset]: a key's long press and double tap timings,
 * its own over the workspace [ws]'s. [update] changes the preset.
 */
internal fun physicalSettings(
    runtime: GamepadRuntime,
    preset: DevicePreset,
    ws: Workspace,
    held: PhysicalHeld?,
    set: Int,
    update: ((DevicePreset) -> DevicePreset) -> Unit,
): MappingSettings {
    val level = held?.let { h ->
        ButtonGyroLevel(ws, set, preset.held(h.id)?.gyro.orEmpty()) { change -> update { p -> p.withHeld(h.id, h.sources, set, change) } }
    }
    val key = preset.id + ":" + held?.id
    val items = if (level == null) emptyList() else listOf(
        category(runtime, "heldGyro", "While held", "Gyro sensitivity and acceleration, touchpad speed", prefKey = "physical.Held", defaultOpen = true) {
            ButtonGyroSensitivity(level, key)
        },
    )
    return physicalActionSettings(preset, ws, update).let { a ->
        MappingSettings(
            // A gyro axis of its Motion tab: its ratchet, sensitivity (or factor), acceleration and glide while held.
            action = { input ->
                val src = input.sources.firstOrNull()
                if (level != null && src is InputSource.Gyro) buttonGyroGear(level, key, src.axis) else a.action(input)
            },
            shared = if (items.isEmpty()) null else ui { ReorderableSections(runtime, "physical", items) },
            items = items,
        )
    }
}

/** A physical key or button that can have settings while held: its preset id ("CONTROLLER:lt") and the inputs that press it. */
internal class PhysicalHeld(val id: String, val sources: List<InputSource>)

private fun physicalActionSettings(
    preset: DevicePreset,
    ws: Workspace,
    update: ((DevicePreset) -> DevicePreset) -> Unit,
) = MappingSettings(
    action = { input ->
        val axis = input.sources.firstOrNull() as? InputSource.Axis
        val stickId = axis?.let { a -> PhysicalSticks.sticks.entries.firstOrNull { a.axis == it.value.first || a.axis == it.value.second }?.key }
        val triggerId = axis?.let { a -> PhysicalSticks.triggers.entries.firstOrNull { a.axis == it.value.first || a.axis == it.value.second }?.key }
        val src = input.sources.firstOrNull() as? InputSource.KeyGesture
        val own = src?.let { preset.timing(it.keyCode) }
        val key = preset.id + ":" + src?.keyCode
        when {
            stickId != null -> ui { StickTuningSettings(preset, stickId, update) }
            triggerId != null -> ui { TriggerTuningSettings(preset, triggerId, update) }
            else -> when (src?.gesture) {
                ButtonGesture.TAP, ButtonGesture.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", "Long press after", own?.longPressMs ?: ws.longPressMs,
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = own?.longPressMs != null,
                        onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = null) } } },
                        hint = "$LONG_HINT Kept in the preset. Without a time of its own it uses the workspace's.",
                    ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = ms) } } }
                }

                ButtonGesture.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "double", "Double tap within", own?.doubleTapMs ?: ws.doubleTapMs,
                        Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                        overridden = own?.doubleTapMs != null,
                        onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(doubleTapMs = null) } } },
                        hint = "Kept in the preset. Without a window of its own it uses the workspace's.",
                    ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(doubleTapMs = ms) } } }
                }

                else -> null
            }
        }
    },
    shared = null,
)

/** A physical stick's tuning in [preset] (stick [id]): deadzones, curve, sensitivity, Outer ring and direction pies. */
@Composable
private fun StickTuningSettings(preset: DevicePreset, id: String, update: ((DevicePreset) -> DevicePreset) -> Unit) {
    val t = preset.stick(id)
    val d = StickTuning()
    fun set(f: (StickTuning) -> StickTuning) = update { p -> p.withStick(id, f(p.stick(id))) }
    val key = preset.id + ":" + id + ":"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "The whole stick's feel, kept in the preset. Every mapping of this stick follows it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OverrideSlider(
            key + "inner", "Inner deadzone %.0f%%", t.inner * 100, 0f..40f, 1f, t.inner != d.inner,
            { set { it.copy(inner = d.inner) } },
            "Nothing happens until the stick is pushed this far, the same in every direction.",
        ) { v -> set { it.copy(inner = v / 100) } }
        OverrideSlider(
            key + "outer", "Outer deadzone %.0f%%", t.outer * 100, 60f..100f, 1f, t.outer != d.outer,
            { set { it.copy(outer = d.outer) } },
            "From this far on the stick counts as pushed all the way.",
        ) { v -> set { it.copy(outer = (v / 100).coerceAtLeast(t.inner + 0.05f)) } }
        OverrideRow(
            "Curve", t.curve != d.curve, { set { it.copy(curve = d.curve) } },
            "Soft: finer aim near the centre. Aggressive: quicker off the centre.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in StickCurve.entries) {
                    FilterChip(selected = t.curve == c, onClick = { set { it.copy(curve = c) } }, label = { Text(c.label) })
                }
            }
        }
        OverrideSlider(
            key + "sens", "Sensitivity %.0f%%", t.sensitivity * 100, 25f..200f, 5f, t.sensitivity != d.sensitivity,
            { set { it.copy(sensitivity = d.sensitivity) } },
            "Above 100% the stick reaches full output before it is pushed all the way.",
        ) { v -> set { it.copy(sensitivity = v / 100) } }
        OverrideSlider(
            key + "ring", "Outer ring %.0f%%", t.outerRing * 100, 30f..100f, 1f, t.outerRing != d.outerRing,
            { set { it.copy(outerRing = d.outerRing) } },
            "The Outer cards fire while the stick is pushed past this, for example Shift to sprint.",
        ) { v -> set { it.copy(outerRing = v / 100) } }
        OverrideSlider(
            key + "pieX", "Left and right pies %.0f°", t.pieX, StickTuning.MIN_PIE..StickTuning.MAX_PIE, 5f, t.pieX != d.pieX,
            { set { it.copy(pieX = d.pieX) } },
            "How wide Left and Right are. Wider than 90° overlaps Up and Down, so a diagonal presses both.",
        ) { v -> set { it.copy(pieX = v) } }
        OverrideSlider(
            key + "pieY", "Up and down pies %.0f°", t.pieY, StickTuning.MIN_PIE..StickTuning.MAX_PIE, 5f, t.pieY != d.pieY,
            { set { it.copy(pieY = d.pieY) } },
            "How wide Up and Down are.",
        ) { v -> set { it.copy(pieY = v) } }
    }
}

/** A physical trigger's tuning in [preset] (trigger [id]): where Press and Full pull fire. */
@Composable
private fun TriggerTuningSettings(preset: DevicePreset, id: String, update: ((DevicePreset) -> DevicePreset) -> Unit) {
    val t = preset.trigger(id)
    val d = TriggerTuning()
    fun set(f: (TriggerTuning) -> TriggerTuning) = update { p -> p.withTrigger(id, f(p.trigger(id))) }
    // Press stays at or below Full pull: setting one past the other moves the other along.
    fun press(v: Float) = set { it.copy(press = v, full = it.full.coerceIn(v.coerceAtLeast(0.5f), 1f)) }
    fun full(v: Float) = set { it.copy(full = v, press = it.press.coerceAtMost(v)) }
    val key = preset.id + ":" + id + ":"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OverrideSlider(
            key + "press", "Press at %.0f%%", t.press * 100, 1f..100f, 1f, t.press != d.press,
            { press(d.press) },
            "The Press card fires once the trigger is pulled this far.",
        ) { v -> press(v / 100) }
        OverrideSlider(
            key + "full", "Full pull at %.0f%%", t.full * 100, 50f..100f, 1f, t.full != d.full,
            { full(d.full) },
            "The Full pull card fires from here, on top of Press.",
        ) { v -> full(v / 100) }
    }
}

/** An area's options: whether it interrupts what a finger sliding in was doing, and resumes it. */
@Composable
private fun AreaOptions(control: TouchControl, update: ((TouchControl) -> TouchControl) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OverrideSwitch(
            "Inside interrupts",
            "A finger that slides inside stops what it was doing (cursor, stick, " +
                    "another button). One landing inside but on no other control moves no cursor.",
            control.zoneInterrupt, overridden = false, onReset = null,
        ) { v -> update { it.copy(zoneInterrupt = v) } }
        if (control.zoneInterrupt) {
            OverrideSwitch(
                "Resume on exit",
                "Leaving, the finger goes back to what it was doing. Off: it does " +
                        "nothing more until it lifts.",
                control.zoneResume, overridden = false, onReset = null,
            ) { v -> update { it.copy(zoneResume = v) } }
        }
        Text(
            "Inside acts while any finger is inside the control, wherever it touched down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
