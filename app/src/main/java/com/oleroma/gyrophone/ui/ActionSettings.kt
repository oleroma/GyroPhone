package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.SurfaceField
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

/** Which gesture timing an action has: a long press its time, a double tap its window. */
private enum class Timing { LONG_PRESS, DOUBLE_TAP }

private fun timingOf(input: BindableInput): Timing? = when (val s = input.sources.firstOrNull()) {
    is InputSource.TouchLongPress -> Timing.LONG_PRESS
    is InputSource.TouchDoubleTap -> Timing.DOUBLE_TAP
    is InputSource.Surface -> when (s.input) {
        SurfaceInput.LONG_PRESS -> Timing.LONG_PRESS
        SurfaceInput.DOUBLE_TAP -> Timing.DOUBLE_TAP
        else -> null
    }
    else -> null
}

/**
 * The ⚙ and Settings of touch control [control] of [ws]: its long press and double tap timings
 * (over the workspace's), its area's options, and the shared settings of a swipe button (its
 * surface over the workspace touchpad) or a stick. [update] changes the control.
 */
internal fun touchControlSettings(
    control: TouchControl,
    ws: Workspace,
    update: ((TouchControl) -> TouchControl) -> Unit,
): MappingSettings {
    val key = ws.id + control.id
    return MappingSettings(
        action = { input ->
            val src = input.sources.firstOrNull()
            when {
                timingOf(input) == Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", "Long press after", control.longPress(ws),
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = control.longPressMs != null,
                        onReset = { update { it.copy(longPressMs = null) } },
                        hint = "How long a press is held to be a long press. A shorter one is a tap.",
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
            control.swipes -> ui {
                SurfaceSharedSettings(
                    buttonSurfaceLevel(control, ws.touchpad, update),
                    key,
                    Bindings.holdZoneCounts(ws.mappings, control.id),
                    touchpad = false,
                )
            }

            control.kind == TouchKind.STICK -> ui { StickSettings(control) { c -> update { c } } }

            else -> null
        },
    )
}

/**
 * The ⚙ and Settings of the workspace touchpad of [ws]: its long press and double tap timings
 * (the workspace's, which buttons follow) and its surface settings, each over the workspace
 * [defaults]. [update] changes the workspace.
 */
internal fun touchpadSettings(
    ws: Workspace,
    defaults: Workspace,
    update: ((Workspace) -> Workspace) -> Unit,
): MappingSettings {
    val key = ws.id + "touchpad"
    val own = WorkspaceDefaults.own(ws)
    fun reset(k: String) = update { WorkspaceDefaults.reset(it, k, defaults) }
    return MappingSettings(
        action = { input ->
            when (timingOf(input)) {
                Timing.LONG_PRESS -> ui {
                    MsOverride(
                        key + "long", "Long press after", ws.longPressMs,
                        Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                        overridden = WorkspaceDefaults.LONG_PRESS in own,
                        onReset = { reset(WorkspaceDefaults.LONG_PRESS) },
                        hint = "The touchpad's, and that of every button and key that has no time of its own.",
                    ) { ms -> update { it.copy(longPressMs = ms) } }
                }

                Timing.DOUBLE_TAP -> ui {
                    MsOverride(
                        key + "double", "Double tap within", ws.doubleTapMs,
                        Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                        overridden = WorkspaceDefaults.DOUBLE_TAP in own,
                        onReset = { reset(WorkspaceDefaults.DOUBLE_TAP) },
                        hint = "The touchpad's, and that of every button and key that has no window of its own.",
                    ) { ms -> update { it.copy(doubleTapMs = ms) } }
                }

                null -> null
            }
        },
        shared = {
            SurfaceSharedSettings(
                SurfaceLevel(
                    eff = ws.touchpad,
                    parentName = "defaults",
                    isOwn = { f -> WorkspaceDefaults.touchpadKey(f) in own },
                    edit = { change -> update { it.copy(touchpad = change(it.touchpad)) } },
                    reset = { f -> reset(WorkspaceDefaults.touchpadKey(f)) },
                    resetAll = {
                        update { w -> SurfaceField.entries.fold(w) { r, f -> WorkspaceDefaults.reset(r, WorkspaceDefaults.touchpadKey(f), defaults) } }
                    },
                ),
                key,
                Bindings.holdZoneCounts(ws.mappings, ""),
                touchpad = true,
            )
        },
    )
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
            ButtonGesture.LONG_PRESS -> ui {
                MsOverride(
                    key + "long", "Long press after", own?.longPressMs ?: ws.longPressMs,
                    Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                    overridden = own?.longPressMs != null,
                    onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = null) } } },
                    hint = "Kept in the preset. Without a time of its own it uses the workspace's (the touchpad's Long press ⚙).",
                ) { ms -> update { p -> p.withTiming(src.keyCode) { it.copy(longPressMs = ms) } } }
            }

            ButtonGesture.DOUBLE_TAP -> ui {
                MsOverride(
                    key + "double", "Double tap within", own?.doubleTapMs ?: ws.doubleTapMs,
                    Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                    overridden = own?.doubleTapMs != null,
                    onReset = { update { p -> p.withTiming(src.keyCode) { it.copy(doubleTapMs = null) } } },
                    hint = "Kept in the preset. Without a window of its own it uses the workspace's (the touchpad's Double tap ⚙).",
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
