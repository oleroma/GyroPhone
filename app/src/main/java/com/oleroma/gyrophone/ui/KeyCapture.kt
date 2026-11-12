package com.oleroma.gyrophone.ui

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PressCombo
import com.oleroma.gyrophone.runtime.GamepadRuntime

/**
 * "Press a key": waits for keys, mouse buttons, the wheel, controller buttons, sticks and
 * triggers, on devices attached to the phone or on the PC (which reads them only meanwhile and
 * never holds them back from Windows or the game). Everything pressed together joins, in order
 * (Ctrl, then C: Ctrl + C), and [onKeys] gets it once all of it is let go. A press [accepts]
 * refuses (a key on an analog action) adds nothing and says so.
 */
@Composable
internal fun KeyCapturePanel(
    runtime: GamepadRuntime,
    accepts: (OutputTarget) -> Boolean,
    onKeys: (List<OutputTarget>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf<String?>(null) }
    var got by remember { mutableStateOf<List<OutputTarget>>(emptyList()) }
    val latestOnKeys by rememberUpdatedState(onKeys)
    val latestAccepts by rememberUpdatedState(accepts)
    val combo = remember { PressCombo() }

    ListenInThisWindow(runtime)
    DisposableEffect(Unit) {
        val listener: (String, OutputTarget?, Boolean) -> Unit = { id, t, down ->
            if (down) {
                val ok = t?.takeIf { latestAccepts(it) }
                message = when {
                    t == null -> "That one has nothing to map to. Try another key or button."
                    ok == null -> Describe.shortTarget(t) + " can't go on this action."
                    else -> null
                }
                combo.press(id, ok)
                got = combo.targets.toList()
            } else if (combo.release(id)) {
                runtime.pressListener = null
                latestOnKeys(combo.targets.toList())
            }
        }
        runtime.pressListener = listener
        onDispose { if (runtime.pressListener === listener) runtime.pressListener = null }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (got.isEmpty()) "Press a key or button…" else got.joinToString(" + ") { Describe.shortTarget(it) },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                message ?: "On a keyboard, mouse or controller, attached to the phone or the PC. " +
                    "Hold several for a combo: it's set when you let go.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                // A release that never came (the PC went away mid-press): what is shown can still be used.
                if (got.isNotEmpty()) FilledTonalButton(onClick = { latestOnKeys(got) }) { Text("Use") }
            }
        }
    }
}

/**
 * While shown in a dialog: the dialog window's keys and motion events (a controller's sticks, a
 * mouse's buttons and wheel) go to the controller input first, as the activity's do; a mouse's
 * clicks are what is being pressed, not taps on the dialog. In the activity's own window nothing
 * is needed.
 */
@Composable
internal fun ListenInThisWindow(runtime: GamepadRuntime) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val orig: Window.Callback? = window?.callback
        if (window == null || orig == null) return@DisposableEffect onDispose { }
        val hook = object : Window.Callback by orig {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                runtime.forwardKey?.invoke(event) == true || orig.dispatchKeyEvent(event)

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                runtime.forwardMotion?.invoke(event) == true || orig.dispatchGenericMotionEvent(event)

            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                event.isFromSource(InputDevice.SOURCE_MOUSE) || orig.dispatchTouchEvent(event)
        }
        window.callback = hook
        onDispose { if (window.callback === hook) window.callback = orig }
    }
}
