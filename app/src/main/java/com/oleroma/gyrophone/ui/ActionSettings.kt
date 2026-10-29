package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchAreaKind
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
    is InputSource.Surface -> when (s.input) {
        SurfaceInput.TAP, SurfaceInput.LONG_PRESS -> Timing.LONG_PRESS
        SurfaceInput.DOUBLE_TAP -> Timing.DOUBLE_TAP
        else -> null
    }
    else -> null
}

/** The ⚙ of a Swipe or Swipe hold [input] of a surface at [level] ([zones]: as mapped), else null. */
private fun directionGear(level: SurfaceLevel, key: String, input: BindableInput, zones: IntArray): (@Composable () -> Unit)? {
    val s = (input.sources.firstOrNull() as? InputSource.Surface)?.input ?: return null
    s.swipeDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = false, zones) } }
    s.holdDirection?.let { d -> return ui { DirectionSettings(level, key, d, hold = true, zones) } }
    return null
}

private const val LONG_HINT = "How long a press is held to be a long press; a shorter one is a tap."

/**
 * A button's gyro sensitivities in mapping set [set] of [ws] ([TouchControl.gyro]): the ones it
 * uses there (its own in [set] over its Base ones) and the workspace's gyro below them.
 */
private class ButtonGyroLevel(val control: TouchControl, ws: Workspace, val set: Int, val update: ((TouchControl) -> TouchControl) -> Unit) {
    private val sets = if (set == MappingSet.BASE) listOf(MappingSet.BASE) else listOf(MappingSet.BASE, set)
    val parent: GyroSettings = WorkspaceDefaults.layered(ws, MappingSet.BASE, sets.drop(1)).gyro
    val inUse: ButtonGyro = control.gyroIn(sets)
    val own: ButtonGyro = control.gyro.firstOrNull { it.set == set } ?: ButtonGyro(set)
    val where = if (set == MappingSet.BASE) "" else " in this layer"
    fun edit(change: (ButtonGyro) -> ButtonGyro) = update { it.withGyro(set, change) }
}

/** The button's Sensitivity while held: every gyro axis's unless the axis has its own. */
@Composable
private fun ButtonGyroSensitivity(g: ButtonGyroLevel, key: String) {
    OverrideSlider(
        key + "gyro" + g.set, "Gyro sensitivity %.0f", g.inUse.sensitivity ?: g.parent.sensitivity, 0f..MAX_GYRO_SENS, 1f,
        overridden = g.own.sensitivity != null,
        onReset = { g.edit { it.copy(sensitivity = null) } },
        hint = "While this is held (the last pressed of several), the gyro turns at this sensitivity$where, whatever this button maps: " +
            "a lower one aims finer. Yaw, pitch and roll follow it unless their ⚙ sets their own. Unset, the workspace's.",
    ) { v -> g.edit { it.copy(sensitivity = v) } }
}

/** The ⚙ of gyro [axis] of a button: its own sensitivity, else the button's Sensitivity, else the workspace's. */
private fun buttonGyroGear(g: ButtonGyroLevel, key: String, axis: MotionAxis): @Composable () -> Unit = ui {
    OverrideSlider(
        key + "gyro" + g.set + axis.name, "${axis.label} sensitivity %.0f", g.inUse.sensitivityOf(axis, g.parent), 0f..MAX_GYRO_SENS, 1f,
        overridden = g.own.own(axis) != null,
        onReset = { g.edit { it.with(axis, null) } },
        hint = "While this is held: mouse counts per degree of ${axis.label.lowercase()}$where. Unset, it follows this button's " +
            "Gyro sensitivity (in Settings), else the workspace's.",
    ) { v -> g.edit { it.with(axis, v) } }
}

/**
 * The ⚙ and Settings of touch control [control] of [ws]: its long press and double tap timings
 * (over the workspace's), its area's options, its gyro sensitivities in mapping set [set], a
 * touchpad button's direction settings and shared settings (over the workspace defaults), or a
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
    val gyro = if (control.kind == TouchKind.BUTTON) ButtonGyroLevel(control, ws, set, update) else null
    return MappingSettings(
        action = { input ->
            val src = input.sources.firstOrNull()
            when {
                gyro != null && src is InputSource.Gyro -> buttonGyroGear(gyro, key, src.axis)

                surface != null && directionGear(surface, key, input, zones) != null -> directionGear(surface, key, input, zones)

                timingOf(input) == Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", "Long press after", control.longPress(ws),
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = control.longPressMs != null,
                        onReset = { update { it.copy(longPressMs = null) } },
                        hint = LONG_HINT,
                    ) { ms -> update { it.copy(longPressMs = ms) } }
                }

                timingOf(input) == Timing.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "double", "Double tap within", control.doubleTap(ws),
                        Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                        overridden = control.doubleTapMs != null,
                        onReset = { update { it.copy(doubleTapMs = null) } },
                        hint = "The longest gap between the taps. A tap waits this long when a double tap does something else.",
                    ) { ms -> update { it.copy(doubleTapMs = ms) } }
                }

                src is InputSource.TouchArea && src.kind == TouchAreaKind.ZONE -> ui { AreaOptions(control, update) }

                else -> null
            }
        },
        shared = when {
            surface != null -> ui {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (gyro != null) ButtonGyroSensitivity(gyro, key)
                    SurfaceSharedSettings(surface, key, zones, touchpad = false)
                }
            }

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
    return MappingSettings(
        action = { input ->
            val gyro = input.sources.firstOrNull() as? InputSource.Gyro
            if (gyro != null) gyroGear(level, key, gyro.axis)
            else directionGear(surface, key, input, zones) ?: when (timingOf(input)) {
                Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", "Long press after", ws.longPressMs,
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = level.isOwn(WorkspaceDefaults.LONG_PRESS),
                        onReset = { level.reset(WorkspaceDefaults.LONG_PRESS) },
                        hint = "$LONG_HINT The workspace's: every button and key without a time of its own uses it.",
                    ) { ms -> level.edit { it.copy(longPressMs = ms) } }
                }

                Timing.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "double", "Double tap within", ws.doubleTapMs,
                        Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                        overridden = level.isOwn(WorkspaceDefaults.DOUBLE_TAP),
                        onReset = { level.reset(WorkspaceDefaults.DOUBLE_TAP) },
                        hint = "The workspace's: every button and key without a window of its own uses it.",
                    ) { ms -> level.edit { it.copy(doubleTapMs = ms) } }
                }

                null -> null
            }
        },
        shared = { WorkspaceSettings(runtime, level, key, touchpadOpen = true) },
    )
}

/**
 * The ⚙ of gyro [axis] at [level]: its sensitivity, its own or the level's shared Sensitivity
 * (↺ follows that again).
 */
private fun gyroGear(level: WorkspaceLevel, key: String, axis: MotionAxis): @Composable () -> Unit = ui {
    val g = level.ws.gyro
    OverrideSlider(
        key + "sens" + axis.name, "${axis.label} sensitivity %.0f", g.sensitivityOf(axis), 0f..MAX_GYRO_SENS, 1f,
        overridden = g.ownSensitivity(axis) != null,
        onReset = { level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, null)) } },
        hint = "Mouse counts per degree of ${axis.label.lowercase()}; scrolling and sticks go faster or slower with it too. " +
            "Unset, it follows the shared Sensitivity (${"%.0f".format(g.sensitivity)}) in Settings.",
    ) { v -> level.edit { it.copy(gyro = it.gyro.withSensitivity(axis, v)) } }
}

/**
 * The ⚙ of a physical control's actions in [preset]: a key's long press and double tap timings,
 * its own over the workspace [ws]'s. [update] changes the preset.
 */
internal fun physicalSettings(
    preset: DevicePreset,
    ws: Workspace,
    update: ((DevicePreset) -> DevicePreset) -> Unit,
) = MappingSettings(
    action = { input ->
        val src = input.sources.firstOrNull() as? InputSource.KeyGesture
        val own = src?.let { preset.timing(it.keyCode) }
        val key = preset.id + ":" + src?.keyCode
        when (src?.gesture) {
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
