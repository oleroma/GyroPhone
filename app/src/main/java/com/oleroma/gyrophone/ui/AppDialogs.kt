package com.oleroma.gyrophone.ui

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/*
 * The app never shows the status or navigation bar, dialogs included: every dialog window hides
 * them as the activity does (a swipe from the edge still shows them for a moment). The app's
 * dialogs use these instead of Compose's Dialog and Material's AlertDialog (same names and
 * parameters, so the screens of this package pick them up without an import).
 */

/** Hides [window]'s system bars; a swipe from the edge shows them for a moment. */
internal fun hideSystemBars(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** The window this composition is in: its dialog's, else the activity's. */
private fun windowOf(view: View): Window? {
    var v: Any? = view
    while (v is View) {
        if (v is DialogWindowProvider) return v.window
        v = v.parent
    }
    return (view.context as? Activity)?.window
}

/** Keeps the system bars hidden in the window this is composed in. */
@Composable
internal fun HideSystemBars() {
    val view = LocalView.current
    SideEffect { windowOf(view)?.let(::hideSystemBars) }
}

/** Compose's Dialog, with the system bars hidden. */
@Composable
internal fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
    HideSystemBars()
    content()
}

/** Material's AlertDialog, with the system bars hidden. */
@Composable
internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = {
        HideSystemBars()
        confirmButton()
    },
    modifier = modifier,
    dismissButton = dismissButton,
    icon = icon,
    title = title,
    text = text,
    shape = shape,
    containerColor = containerColor,
    iconContentColor = iconContentColor,
    titleContentColor = titleContentColor,
    textContentColor = textContentColor,
    tonalElevation = tonalElevation,
    properties = properties,
)
