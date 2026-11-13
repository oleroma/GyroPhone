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
import com.oleroma.gyrophone.core.model.GyroSpace
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.ButtonHow
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
import com.oleroma.gyrophone.core.model.heldSwipe
import com.oleroma.gyrophone.core.model.holdDirection
import com.oleroma.gyrophone.core.model.quickSlot
import com.oleroma.gyrophone.core.model.swipeDirection
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.FingerAxisSettings
import com.oleroma.gyrophone.core.model.fingerOf
import com.oleroma.gyrophone.core.model.withFinger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * What the mapping editor's ⚙ and Settings open for a control: [action] gives an action's own
 * settings (its ⚙, before its name), or null when it has none; [shared] the settings its actions
 * share (the top bar's Settings), null = none, so Settings opens the control's settings instead.
 */
internal class MappingSettings(
    val action: (BindableInput) -> (@Composable () -> Unit)?,
    val shared: (@Composable () -> Unit)?,
    /** Fixed above [shared] on its page (it doesn't scroll): the workspace's rename, duplicate, delete. */
    val sharedTop: (@Composable () -> Unit)? = null,
    /** A touch control's shared settings as categories, for its one settings page; [itemsHeader] goes above them (its Reset all). */
    val items: List<SectionItem> = emptyList(),
    val itemsHeader: (@Composable () -> Unit)? = null,
)

/** [content] as a composable value (a branch of a `when` gives it no expected type to infer that from). */
private fun ui(content: @Composable () -> Unit): @Composable () -> Unit = content

/**
 * Which gesture timing an action has: the one Tap time, shared by taps (a longer press is no
 * tap), long presses and double taps (the longest gap between the taps).
 */
private enum class Timing { LONG_PRESS }

private fun timingOf(input: BindableInput): Timing? = when (val s = input.sources.firstOrNull()) {
    is InputSource.TouchTap, is InputSource.TouchLongPress -> Timing.LONG_PRESS
    is InputSource.TouchDoubleTap -> Timing.LONG_PRESS
    // Area and Outside: the button's own times.
    is InputSource.TouchArea -> when (s.on) {
        Trigger.TAP, Trigger.LONG_PRESS, Trigger.DOUBLE_TAP -> Timing.LONG_PRESS
        else -> null
    }
    // A swipe's Long hold and Double swipe: the surface's long press and double tap times.
    is InputSource.Surface -> when {
        s.on == Trigger.LONG_PRESS || s.on == Trigger.DOUBLE_TAP -> Timing.LONG_PRESS
        s.input == SurfaceInput.TAP || s.input == SurfaceInput.LONG_PRESS || s.input == SurfaceInput.DOUBLE_TAP -> Timing.LONG_PRESS
        else -> null
    }
    else -> null
}

/** [input] is a swipe direction's Long hold or Double swipe (its timing settings are named for swipes). */
private fun isSwipeTiming(input: BindableInput): Boolean {
    val s = input.sources.firstOrNull() as? InputSource.Surface ?: return false
    return s.on != Trigger.HOLD && (s.input.quickSlot >= 0 || s.input.heldSwipe)
}

/** The long press time's name and hint for [input] ([swipe]: a Long hold); [whose]: whose time it is. */
private fun longLabel(@Suppress("UNUSED_PARAMETER") swipe: Boolean) = "Tap time"

private fun longHint(swipe: Boolean, whose: String) =
    if (swipe) "A held direction held longer is a Long hold; two quick swipes this close are a Double swipe. It is $whose Tap time."
    else "$LONG_HINT It is $whose."

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
    // The one Swipe row: each arrow's own settings, both its held and its quick swipe ones.
    if (s == SurfaceInput.HOLD_ANY) return ui { SwipeArrowSettings(level, key, zones) }
    s.swipeDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = false, zones) } }
    s.holdDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = true, zones) } }
    return null
}

/** How a gyro sensitivity of [g]'s workspace reads: as RWS when the game's turn is known, else counts per degree. */
internal fun gyroShow(g: GyroSettings): (Float) -> String = { s ->
    g.rwsOf(s)?.let { "%.2f RWS".format(it) } ?: "%.1f".format(s).removeSuffix(".0").removeSuffix(",0")
}

private const val LONG_HINT = "A shorter press is a tap, a longer one a long press, and a second touch this soon after a lift a double tap."

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
 * The button's gyro while it is held: Sensitivity and Acceleration (every gyro axis's unless the
 * axis has its own). Its Gyro tab.
 */
@Composable
private fun ButtonGyroSensitivity(g: ButtonGyroLevel, key: String) {
    Hint("While this is held.")
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
}

/** The touchpad cursor's sensitivity and acceleration while the button is held. Its Touchpad tab. */
@Composable
private fun ButtonTouchpadSpeed(g: ButtonGyroLevel, key: String) {
    Hint("While this is held.")
    val touchHint = "While this is held${g.where}, the touchpad cursor moves at this speed, for every finger count. Unset, the workspace's."
    val touchOwn = g.own.touchSensitivity != null || g.own.touchScale != null
    val touchReset = { g.edit { it.withTouch(null) } }
    val touchBase = g.inUse.touchSensitivity ?: g.touchpad.sensitivity
    ScaledSens(
        key + "touchScale" + g.set, "Touchpad sensitivity", g.inUse.touchScale, touchBase, { "%.1f".format(it) },
        overridden = touchOwn, onReset = touchReset,
        hint = "$touchHint With Scale parent each finger count's own speed is scaled.",
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
        GyroSpaceRow(
            axis, g.inUse.ownSpace(axis) ?: g.parent.spaceOf(axis), g.own.ownSpace(axis) != null,
            onReset = { g.edit { it.withSpace(axis, null) } },
            hint = "While this is held${g.where}. Unset, the workspace's.",
        ) { sp -> g.edit { it.withSpace(axis, sp) } }
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
    val heldCategories = gyro?.let { g ->
        listOf(
            category(runtime, "buttonHow", "Press", "How its chips press: Held, Once, Toggle or Repeat", prefKey = "button.How") {
                ButtonHowRow(control.how, control.repeat) { h, n -> update { it.copy(how = h, repeat = n) } }
            },
            category(runtime, "buttonGyro", "Gyro", "Gyro sensitivity and acceleration while held", prefKey = "button.Gyro") {
                ButtonGyroSensitivity(g, key)
            },
            category(runtime, "buttonTouchpad", "Touchpad", "Touchpad cursor speed while held", prefKey = "button.Touchpad") {
                ButtonTouchpadSpeed(g, key)
            },
        )
    }
    val items = heldCategories.orEmpty() + when {
        surface != null -> listOf(surfaceCategory(runtime, "Swipes", surface, key, zones, touchpad = false))
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

                src is InputSource.TouchArea && src.kind == TouchAreaKind.ZONE -> ui { AreaOptions(control, update) }

                src is InputSource.Finger -> fingerGear(key, control.fingerOf(src.axis)) { s ->
                    update { it.copy(fingers = it.fingers.withFinger(s)) }
                }

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
                is InputSource.Finger -> fingerGear(key, ws.padFingers.fingerOf(src.axis), level.isOwn(WorkspaceDefaults.PAD_FINGERS)) { s ->
                    level.edit { it.copy(padFingers = it.padFingers.withFinger(s)) }
                }
                else -> null
            } ?: directionGear(surface, key, input, zones, touchpad = true) ?: when (timingOf(input)) {
                Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", longLabel(isSwipeTiming(input)), ws.longPressMs,
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = level.isOwn(WorkspaceDefaults.LONG_PRESS),
                        onReset = { level.reset(WorkspaceDefaults.LONG_PRESS) },
                        children = level.kids(WorkspaceDefaults.LONG_PRESS),
                        hint = longHint(isSwipeTiming(input), "the workspace's: every button and key without a time of its own uses it"),
                    ) { ms -> level.edit { it.copy(longPressMs = ms) } }
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
            children = level.kids(k("Mode")),
            hint = "Unset, it follows the gyro mode in Settings." + if (g.mode == GyroMode.SENSOR_OFF) " Gyro is off there now." else "",
        ) { m -> level.edit { it.copy(gyro = it.gyro.withMode(axis, m)) } }
        GyroSpaceRow(
            axis, g.spaceOf(axis), level.isOwn(k("Space")),
            onReset = { level.reset(k("Space")) },
            children = level.kids(k("Space")),
            hint = "Unset, it follows the Gyro space in Settings (${g.gyroSpace.label}).",
        ) { sp -> level.edit { it.copy(gyro = it.gyro.withSpace(axis, sp)) } }
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
                children = level.kids(k("Sensitivity"), k("Scale")),
            ) { v -> level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, v)) } }
        }
        AccelCurveSetting(
            key + "accel" + axis.name, "${axis.label} acceleration", g.accelOf(axis), g.sensitivityOf(axis), g,
            overridden = level.isOwn(k("Accel")),
            onReset = { level.reset(k("Accel")) },
            children = level.kids(k("Accel")),
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

/** The ⚙ of sideways ([x]) or forward and back tilt at [level]: its range and deadzone (and sideways, the compass lying flat), its own or the shared ones. */
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
            children = level.kids(rangeKey),
            hint = "How far it leans for a full push. Unset, the shared Tilt range in Settings (%.0f°).".format(g.tiltRangeDeg),
        ) { v -> level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXRangeDeg = v) else it.gyro.copy(tiltYRangeDeg = v)) } }
        val dz = g.tiltDeadzoneOf(x)
        OverrideSlider(
            key + "tiltDz" + x, if (dz < 0.5f) "$what deadzone: off" else "$what deadzone %.0f°", dz,
            0f..GyroSettings.MAX_TILT_DEADZONE_DEG, 1f,
            overridden = level.isOwn(dzKey),
            onReset = { level.reset(dzKey) },
            children = level.kids(dzKey),
            hint = "Leaning less than this counts as level. Unset, the shared Tilt deadzone in Settings.",
        ) { v -> level.edit { it.copy(gyro = if (x) it.gyro.copy(tiltXDeadzoneDeg = v) else it.gyro.copy(tiltYDeadzoneDeg = v)) } }
        if (x) {
            val ck = WorkspaceDefaults.gyroKey("tiltCompass")
            OverrideSwitch(
                "Use compass when flat", "Lying flat, sideways tilt (a steering wheel) has no gravity to say where straight is. On, the compass holds straight where the phone went flat; off, the gyro alone carries it, drifting very slowly (Set orientation makes the way it points straight). Laptops, speakers, metal and magnetic cases can bend the compass, so straight may wander.",
                g.tiltCompass, overridden = level.isOwn(ck), onReset = { level.reset(ck) }, children = level.kids(ck),
            ) { v -> level.edit { it.copy(gyro = it.gyro.copy(tiltCompass = v)) } }
        }
    }
}

/** The ⚙ of Shake at [level]: how hard a shake must be (the level's, over its parent's). */
private fun shakeGear(level: WorkspaceLevel, key: String): @Composable () -> Unit = ui {
    val k = WorkspaceDefaults.gyroKey("shakeStrength")
    OverrideSlider(
        key + "shake", "Shake at %.0f m/s²", level.ws.gyro.shakeStrength,
        GyroSettings.MIN_SHAKE_STRENGTH..GyroSettings.MAX_SHAKE_STRENGTH, 1f,
        overridden = level.isOwn(k), onReset = { level.reset(k) }, children = level.kids(k),
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
    children: ChildInfo? = null,
    onSet: (GyroMode) -> Unit,
) {
    OverrideRow("${axis.label}: a ratchet", overridden, onReset, hint, children = children) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in listOf(GyroMode.ALWAYS, GyroMode.OFF)) {
                FilterChip(selected = mode == m, onClick = { onSet(m) }, label = { Text(if (m == GyroMode.ALWAYS) "Disables it" else "Enables it") })
            }
        }
    }
}

/**
 * Gyro [axis]'s space for its mappings ([GyroSpace]): Local, World, and for yaw Player. [space]: the
 * one in effect (Player off yaw shows as Local: it changes nothing there).
 */
@Composable
private fun GyroSpaceRow(
    axis: MotionAxis,
    space: GyroSpace,
    overridden: Boolean,
    onReset: () -> Unit,
    hint: String,
    children: ChildInfo? = null,
    onSet: (GyroSpace) -> Unit,
) {
    val choices = GyroSpace.choicesFor(axis)
    val shown = if (space in choices) space else GyroSpace.LOCAL
    val what = when (shown) {
        GyroSpace.LOCAL -> "Local: ${axis.label.lowercase()} about the phone's own axis."
        GyroSpace.WORLD -> "World: ${axis.label.lowercase()} measured against gravity, however the phone is held."
        GyroSpace.PLAYER -> "Player: yaw blends the phone's yaw and roll by how it leans, so turning stays level when you tilt or lie down."
    }
    OverrideRow("${axis.label}: space", overridden, onReset, "$what $hint", children = children) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (sp in choices) FilterChip(selected = shown == sp, onClick = { onSet(sp) }, label = { Text(sp.label) })
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
    val keyCode = held?.sources?.firstNotNullOfOrNull { ButtonHow.keyOf(it) }
    val howItem = keyCode?.let { k ->
        category(runtime, "heldHow", "Press", "How its chips press: Held, Once, Toggle or Repeat", prefKey = "physical.How") {
            val t = preset.timing(k)
            ButtonHowRow(t?.how, t?.repeat ?: 2) { h, n -> update { p -> p.withTiming(k) { it.copy(how = h, repeat = n) } } }
        }
    }
    val items = listOfNotNull(howItem) + if (level == null) emptyList() else listOf(
        category(runtime, "heldGyro", "Gyro", "Gyro sensitivity and acceleration while held", prefKey = "physical.Held", defaultOpen = true) {
            ButtonGyroSensitivity(level, key)
        },
        category(runtime, "heldTouchpad", "Touchpad", "Touchpad cursor speed while held", prefKey = "physical.HeldTouchpad") {
            ButtonTouchpadSpeed(level, key)
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

/**
 * A button's How ([TouchControl.how], [KeyTiming.how]): what its chips do when they don't set their
 * own. [onSet] gets the new How (null = Held) and Repeat count.
 */
@Composable
private fun ButtonHowRow(how: How?, repeat: Int, onSet: (How?, Int) -> Unit) {
    val shown = how ?: How.HELD
    OverrideRow(
        "How", how != null && how != How.HELD, { onSet(null, 2) },
        "Every chip of this button that doesn't set its own How follows this one (a chip's own How shows ↺ to follow again). " +
            "Toggle also keeps this button's own gyro cards and gyro settings on from one touch to the next. " +
            "Chips that move something (a stick, the mouse, a sensitivity) stay Held, except Toggle on Position and Move.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (h in How.entries) FilterChip(
                selected = shown == h, onClick = { onSet(h.takeUnless { it == How.HELD }, repeat) }, label = { Text(h.label) },
            )
        }
        if (shown == How.REPEAT) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in MapOptions.MIN_REPEAT..MapOptions.MAX_REPEAT) FilterChip(
                selected = repeat == n, onClick = { onSet(How.REPEAT, n) }, label = { Text("×$n") },
            )
        }
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
                ButtonGesture.TAP, ButtonGesture.LONG_PRESS, ButtonGesture.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "long", "Tap time", own?.longPressMs ?: ws.longPressMs,
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = own?.longPressMs != null,
                        onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = null) } } },
                        hint = "$LONG_HINT Kept in the preset. Without a time of its own it uses the workspace's.",
                    ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = ms) } } }
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

/**
 * The ⚙ of a finger position input ([InputSource.Finger]): Live or Locked, and its deadzones
 * ([FingerAxisSettings]) as one slider with two handles. [own]: set at this level ([s] differs
 * from the defaults, for a button).
 */
private fun fingerGear(key: String, s: FingerAxisSettings, own: Boolean = !s.none, onSet: (FingerAxisSettings) -> Unit): @Composable () -> Unit = ui {
    FingerAxisOptions(key, s, own, onSet)
}

@Composable
private fun FingerAxisOptions(key: String, s: FingerAxisSettings, own: Boolean, onSet: (FingerAxisSettings) -> Unit) {
    val along = when (s.axis) {
        FingerAxis.X -> "from the left edge (0 %) to the right edge (100 %)"
        FingerAxis.Y -> "from the bottom edge (0 %) to the top edge (100 %)"
        FingerAxis.DISTANCE -> "from the centre (0 %) to the rim (100 %)"
        FingerAxis.ANGLE -> "around from the top (0 %), clockwise, back to the top (100 %)"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OverrideRow(
            "Value", s.locked, if (s.locked) ({ onSet(s.copy(locked = false)) }) else null,
            hint = if (s.locked) "Locked: the value is where the finger landed, until it lifts; sliding changes nothing."
            else "Live: the value follows the finger as it slides.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !s.locked, onClick = { onSet(s.copy(locked = false)) }, label = { Text("Live") })
                FilterChip(selected = s.locked, onClick = { onSet(s.copy(locked = true)) }, label = { Text("Locked") })
            }
        }
        var a by remember(key, s.axis, s.start) { mutableStateOf(s.start) }
        var b by remember(key, s.axis, s.end) { mutableStateOf(s.end) }
        val deadzoned = s.start != 0f || s.end != 1f
        OverrideRow(
            "Deadzones %.0f %% – %.0f %%".format(a * 100, b * 100), own && deadzoned,
            if (deadzoned) ({ onSet(s.copy(start = 0f, end = 1f)) }) else null,
            hint = "Where on the button the value starts and stops growing, $along. Before the first handle it is 0, " +
                    "past the second it is full, and it grows evenly between them. Every chip of this input uses it; " +
                    "each chip's Range then says what 0 and full send. Tap near a handle to type its exact value.",
        ) {
            TwoHandleSlider(
                a * 100f, b * 100f, 0f..100f, 1f,
                onChange = { x, y -> a = x / 100f; b = y / 100f },
                onDone = { x, y ->
                    // The handles in order: the start is the lower one.
                    val lo = minOf(x, y) / 100f
                    val hi = maxOf(x, y) / 100f
                    a = lo; b = hi
                    onSet(s.copy(start = lo, end = hi))
                },
                fromTitle = "Start (%)", toTitle = "End (%)",
            )
        }
        var sn by remember(key, s.axis, s.snap) { mutableStateOf(s.snap * 100f) }
        OverrideRow(
            if (sn <= 0f) "Snap off" else "Snap %.0f %%".format(sn), own && s.snap > 0f,
            if (s.snap > 0f) ({ onSet(s.copy(snap = 0f)) }) else null,
            hint = "The value goes in steps of this share of the travel (25 %: 0, 25, 50, 75, 100 %; a share that doesn't " +
                    "divide 100 evenly takes the nearest even split). Every chip of this input lands on the same step of " +
                    "its own Range, so they move together, and the ruler on the button marks each step. Off: smooth.",
        ) {
            Slider(
                value = sn.coerceIn(0f, MAX_SNAP_PCT),
                onValueChange = { sn = it.roundToInt().toFloat() },
                valueRange = 0f..MAX_SNAP_PCT,
                onValueChangeFinished = { onSet(s.copy(snap = sn / 100f)) },
                typedRange = 0f..100f,
                typedTitle = "Snap (%)",
            )
        }
    }
}

/** The Snap slider's top (%): a larger step is typed. */
private const val MAX_SNAP_PCT = 50f
