package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.DirField
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.MoveField
import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.SurfaceField
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.ownSurface
import com.oleroma.gyrophone.core.model.surfaceSettings
import kotlin.math.roundToInt

/**
 * Touch surface settings at one level over its parent: [eff] in effect, which ones it sets itself
 * ([isOwn], and per direction [isDirOwn]; shown highlighted with a ↺), and edits ([edit] gets the
 * settings in effect changed; the changed ones, shared or per direction, become its own).
 * [parentName] names what a reset goes back to. [setZones] sets its length zones (shared, and per
 * direction or null), which are always its own.
 */
internal class SurfaceLevel(
    val eff: TouchpadSettings,
    val parentName: String,
    val isOwn: (SurfaceField) -> Boolean,
    val isDirOwn: (Direction, DirField) -> Boolean,
    val edit: ((TouchpadSettings) -> TouchpadSettings) -> Unit,
    val reset: (SurfaceField) -> Unit,
    val resetDir: (Direction, DirField) -> Unit,
    val resetAll: () -> Unit,
    val setZones: (List<HoldZone>, List<List<HoldZone>>?) -> Unit,
    val isMoveOwn: (MoveSlot, MoveField) -> Boolean,
    val resetMove: (MoveSlot, MoveField) -> Unit,
) {
    val anyOwn: Boolean
        get() = SurfaceField.entries.any(isOwn) || Direction.entries.any(::anyDirOwn) ||
            MoveSlot.entries.any { m -> MoveField.of(m).any { isMoveOwn(m, it) } }

    /** Direction [d] sets any of its own settings. */
    fun anyDirOwn(d: Direction) = DirField.entries.any { isDirOwn(d, it) }
}

/** A touchpad button's surface over the workspace touchpad [pad]; [update] changes the button. */
internal fun buttonSurfaceLevel(
    control: TouchControl,
    pad: TouchpadSettings,
    update: ((TouchControl) -> TouchControl) -> Unit,
): SurfaceLevel {
    val own = control.ownSurface
    return SurfaceLevel(
        eff = control.surfaceSettings(pad),
        parentName = "Workspace",
        isOwn = { it.button && it.isOwn(own) },
        isDirOwn = { d, f -> f.isOwn(own, d) },
        edit = { change ->
            update { c ->
                val was = c.surfaceSettings(pad)
                c.copy(surface = SurfaceField.edited(c.ownSurface, was, change(was)))
            }
        },
        reset = { f -> update { c -> c.copy(surface = f.drop(c.ownSurface)) } },
        resetDir = { d, f -> update { c -> c.copy(surface = f.drop(c.ownSurface, d)) } },
        resetAll = { update { c -> c.copy(surface = SurfaceField.dropAll(c.ownSurface)) } },
        setZones = { a, b -> update { c -> c.copy(surface = c.ownSurface.copy(holdZones = a, dirHoldZones = b)) } },
        isMoveOwn = { m, f -> f.isOwn(own, m) },
        resetMove = { m, f -> update { c -> c.copy(surface = f.drop(c.ownSurface, m)) } },
    )
}

/** The touchpad settings of workspace settings [level] (a workspace's, or the defaults'): its keys of them. */
internal fun workspaceSurfaceLevel(level: WorkspaceLevel) = SurfaceLevel(
    eff = level.ws.touchpad,
    parentName = level.parentName,
    isOwn = { f -> level.isOwn(WorkspaceDefaults.touchpadKey(f)) },
    isDirOwn = { d, f -> level.isOwn(WorkspaceDefaults.dirKey(d, f)) },
    edit = { change -> level.edit { it.copy(touchpad = change(it.touchpad)) } },
    reset = { f -> level.reset(WorkspaceDefaults.touchpadKey(f)) },
    resetDir = { d, f -> level.reset(WorkspaceDefaults.dirKey(d, f)) },
    resetAll = { level.resetKeys(WorkspaceDefaults.touchpadKeys)() },
    setZones = { a, b -> level.edit { it.copy(touchpad = it.touchpad.copy(holdZones = a, dirHoldZones = b)) } },
    isMoveOwn = { m, f -> level.isOwn(WorkspaceDefaults.moveKey(m, f)) },
    resetMove = { m, f -> level.reset(WorkspaceDefaults.moveKey(m, f)) },
)

/**
 * A setting that may differ from its parent's: [overridden] ones are highlighted and get a ↺
 * that sets them back to the parent's value ([onReset]; null = no parent).
 */
@Composable
internal fun OverrideRow(
    title: String,
    overridden: Boolean,
    onReset: (() -> Unit)?,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (overridden) colors.primaryContainer.copy(alpha = 0.5f) else Color.Transparent, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f).padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (overridden) colors.primary else colors.onSurface,
            )
            if (overridden && onReset != null) {
                TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("↺ Reset")
                }
            }
            trailing?.invoke()
        }
        content()
        if (hint != null) {
            Text(
                hint,
                Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * A slider setting: [format] shows the value; it snaps to [step] and hands the value to [onDone]
 * once the drag ends; [onLive] gets it while it is dragged (null once it ends), for a live
 * preview. [key] restarts the draft when the setting's owner changes.
 */
@Composable
internal fun OverrideSlider(
    key: String,
    format: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    overridden: Boolean,
    onReset: (() -> Unit)?,
    hint: String? = null,
    onLive: ((Float?) -> Unit)? = null,
    onDone: (Float) -> Unit,
) {
    var v by remember(key, value) { mutableStateOf(value) }
    OverrideRow(format.format(v), overridden, onReset, hint) {
        Slider(
            value = v,
            onValueChange = {
                v = (it / step).roundToInt() * step
                onLive?.invoke(v)
            },
            valueRange = range,
            onValueChangeFinished = {
                onDone(v)
                onLive?.invoke(null)
            },
        )
    }
}

/** A switch setting, highlighted when [overridden]. */
@Composable
internal fun OverrideSwitch(
    title: String,
    hint: String?,
    checked: Boolean,
    overridden: Boolean,
    onReset: (() -> Unit)?,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) = OverrideRow(title, overridden, onReset, hint, trailing = {
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
})

/** A time in ms ([title] shows it), set in steps of 10 ms; highlighted when [overridden]. */
@Composable
internal fun MsOverride(
    key: String,
    title: String,
    ms: Int,
    range: IntRange,
    overridden: Boolean,
    onReset: (() -> Unit)?,
    hint: String? = null,
    onSet: (Int) -> Unit,
) = OverrideSlider(
    key, "$title %.0f ms", ms.toFloat(), range.first.toFloat()..range.last.toFloat(), 10f,
    overridden, onReset, hint,
) { onSet(it.roundToInt()) }

/** "Reset all to …": every setting of a level back to its parent's, once confirmed. */
@Composable
internal fun ResetAllButton(parentName: String, enabled: Boolean, onClick: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }, enabled = enabled) { Text("↺ Reset all") }
    if (asking) ConfirmResetAll("Every setting here goes back to $parentName's.", { asking = false }) { asking = false; onClick() }
}

/** Asks before a Reset all, which [text] explains: [onConfirm] or [onDismiss]. */
@Composable
internal fun ConfirmResetAll(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset all?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Reset all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
