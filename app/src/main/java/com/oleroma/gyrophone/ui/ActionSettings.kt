package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace

/**
 * What the mapping editor's ⚙ and Settings open for a control: [action] gives an action's own
 * settings (its ⚙, before its name), or null when it has none; [shared] the settings its actions
 * share (the top bar's Settings), null = none, so Settings opens the control's settings instead.
 */
internal class MappingSettings(
    val action: (BindableInput) -> (@Composable () -> Unit)?,
    val shared: (@Composable () -> Unit)?,
)

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
                timingOf(input) == Timing.LONG_PRESS -> {
                    {
                        MsOverride(
                            key + "long", "Long press after", control.longPress(ws),
                            Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                            overridden = control.longPressMs != null,
                            onReset = { update { it.copy(longPressMs = null) } },
                            hint = "How long a press is held to be a long press. A shorter one is a tap.",
                        ) { ms -> update { it.copy(longPressMs = ms) } }
                    }
                }

                timingOf(input) == Timing.DOUBLE_TAP -> {
                    {
                        MsOverride(
                            key + "double", "Double tap within", control.doubleTap(ws),
                            Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                            overridden = control.doubleTapMs != null,
                            onReset = { update { it.copy(doubleTapMs = null) } },
                            hint = "The longest gap between the taps. A tap waits this long when a double tap does something else.",
                        ) { ms -> update { it.copy(doubleTapMs = ms) } }
                    }
                }

                src is InputSource.TouchArea && src.kind == TouchAreaKind.ZONE -> {
                    { AreaOptions(control, update) }
                }

                else -> null
            }
        },
        shared = when {
            control.swipes -> {
                {
                    SurfaceSharedSettings(
                        buttonSurfaceLevel(control, ws.touchpad, update),
                        key,
                        Bindings.holdZoneCounts(ws.mappings, control.id),
                        touchpad = false,
                    )
                }
            }

            control.kind == TouchKind.STICK -> {
                { StickSettings(control) { c -> update { c } } }
            }

            else -> null
        },
    )
}

/**
 * The ⚙ and Settings of the workspace touchpad of [ws]: its long press and double tap timings
 * (the workspace's, which buttons follow) over the built-in ones, and its surface settings over
 * [parent]. [update] changes the workspace.
 */
internal fun touchpadSettings(
    ws: Workspace,
    parent: TouchpadSettings,
    update: ((Workspace) -> Workspace) -> Unit,
): MappingSettings {
    val key = ws.id + "touchpad"
    return MappingSettings(
        action = { input ->
            when (timingOf(input)) {
                Timing.LONG_PRESS -> {
                    {
                        MsOverride(
                            key + "long", "Long press after", ws.longPressMs,
                            Workspace.MIN_LONG_PRESS_MS..Workspace.MAX_LONG_PRESS_MS,
                            overridden = ws.longPressMs != Workspace.DEFAULT_LONG_PRESS_MS,
                            onReset = { update { it.copy(longPressMs = Workspace.DEFAULT_LONG_PRESS_MS) } },
                            hint = "The touchpad's, and every button's that has no time of its own.",
                        ) { ms -> update { it.copy(longPressMs = ms) } }
                    }
                }

                Timing.DOUBLE_TAP -> {
                    {
                        MsOverride(
                            key + "double", "Double tap within", ws.doubleTapMs,
                            Workspace.MIN_DOUBLE_TAP_MS..Workspace.MAX_DOUBLE_TAP_MS,
                            overridden = ws.doubleTapMs != Workspace.DEFAULT_DOUBLE_TAP_MS,
                            onReset = { update { it.copy(doubleTapMs = Workspace.DEFAULT_DOUBLE_TAP_MS) } },
                            hint = "The touchpad's, and every button's that has no window of its own.",
                        ) { ms -> update { it.copy(doubleTapMs = ms) } }
                    }
                }

                null -> null
            }
        },
        shared = {
            SurfaceSharedSettings(
                touchpadSurfaceLevel(ws.touchpad, parent, "default") { change -> update { it.copy(touchpad = change(it.touchpad)) } },
                key,
                Bindings.holdZoneCounts(ws.mappings, ""),
                touchpad = true,
            )
        },
    )
}

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
