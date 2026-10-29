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
import com.oleroma.gyrophone.core.model.SurfaceField
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.ownSurface
import com.oleroma.gyrophone.core.model.surfaceSettings
import kotlin.math.roundToInt

/**
 * Touch surface settings at one level over its parent: [eff] in effect, which ones it sets itself
 * ([isOwn], shown highlighted with a ↺), and edits ([edit] gets the settings in effect changed;
 * the changed ones become its own). [parentName] names what a reset goes back to.
 */
internal class SurfaceLevel(
    val eff: TouchpadSettings,
    val parentName: String,
    val isOwn: (SurfaceField) -> Boolean,
    val edit: ((TouchpadSettings) -> TouchpadSettings) -> Unit,
    val reset: (SurfaceField) -> Unit,
    val resetAll: () -> Unit,
) {
    val anyOwn: Boolean get() = SurfaceField.entries.any(isOwn)
}

/** A swipe button's surface over the workspace touchpad [pad]; [update] changes the button. */
internal fun buttonSurfaceLevel(
    control: TouchControl,
    pad: TouchpadSettings,
    update: ((TouchControl) -> TouchControl) -> Unit,
): SurfaceLevel {
    val own = control.ownSurface
    return SurfaceLevel(
        eff = control.surfaceSettings(pad),
        parentName = "touchpad",
        isOwn = { it.button && it.isOwn(own) },
        edit = { change ->
            update { c ->
                val was = c.surfaceSettings(pad)
                c.copy(surface = SurfaceField.edited(c.ownSurface, was, change(was)))
            }
        },
        reset = { f -> update { c -> c.copy(surface = f.drop(c.ownSurface)) } },
        resetAll = { update { c -> c.copy(surface = SurfaceField.dropAll(c.ownSurface)) } },
    )
}

/** The workspace touchpad [tp] over [parent] (its defaults); [update] changes it. */
internal fun touchpadSurfaceLevel(
    tp: TouchpadSettings,
    parent: TouchpadSettings,
    parentName: String,
    update: ((TouchpadSettings) -> TouchpadSettings) -> Unit,
) = SurfaceLevel(
    eff = tp,
    parentName = parentName,
    isOwn = { !it.same(tp, parent) },
    edit = update,
    reset = { f -> update { f.take(it, parent) } },
    resetAll = { update { SurfaceField.takeAll(it, parent) } },
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
 * once the drag ends. [key] restarts the draft when the setting's owner changes.
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
    onDone: (Float) -> Unit,
) {
    var v by remember(key, value) { mutableStateOf(value) }
    OverrideRow(format.format(v), overridden, onReset, hint) {
        Slider(
            value = v,
            onValueChange = { v = (it / step).roundToInt() * step },
            valueRange = range,
            onValueChangeFinished = { onDone(v) },
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

/** "Reset all to …": every setting of a level back to its parent's. */
@Composable
internal fun ResetAllButton(parentName: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text("↺ Reset all to $parentName") }
}
