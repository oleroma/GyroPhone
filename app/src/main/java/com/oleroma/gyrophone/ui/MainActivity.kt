package com.oleroma.gyrophone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.input.ControllerInput
import com.oleroma.gyrophone.link.TransportKind
import com.oleroma.gyrophone.runtime
import com.oleroma.gyrophone.service.GamepadService

class MainActivity : ComponentActivity() {

    private lateinit var controllerInput: ControllerInput

    private var lastRotation = -1
    /** 180-degree flips change no Configuration, so onConfigurationChanged alone misses them. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display?.displayId) updateRotation()
        }
    }

    /** Re-asks for unbuffered dispatch once a focus change has finished overwriting the decor's request. */
    private val reassertUnbuffered = Runnable { TouchStream.requestUnbuffered(window.decorView) }

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (canStartService()) GamepadService.start(this)
        // The service may already run (Wi-Fi start, then Bluetooth picked): start HID now it is allowed.
        if (hasBluetooth() && runtime.transport.value == TransportKind.BLUETOOTH) runtime.hid.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        controllerInput = ControllerInput(this, runtime)

        // Wi-Fi needs no runtime permission; Bluetooth HID needs BLUETOOTH_CONNECT first.
        if (canStartService()) GamepadService.start(this) else permissionRequest.launch(permissions)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val orientation by runtime.screenOrientation.collectAsStateWithLifecycle()
                LaunchedEffect(orientation) { requestedOrientation = orientation.activityValue }
                Surface(Modifier.fillMaxSize()) {
                    // No tabs: the app opens on the pad; Connection and Settings open from its capsules.
                    // Full-screen pad: the ← and → corners pressed together; Back also leaves.
                    var fullscreen by rememberSaveable { mutableStateOf(runtime.startFullscreen.value) }
                    BackHandler(enabled = fullscreen) { fullscreen = false }
                    PadTab(
                        runtime,
                        fullscreen = fullscreen,
                        onFullscreen = { fullscreen = it },
                        onRetry = { permissionRequest.launch(permissions) },
                    )
                }
            }
        }
        // Any View focus change (Compose focus, leaving touch mode on a D-pad or keyboard key)
        // makes every ancestor recompute its unbuffered request from its children, which drops
        // the decor's own request. The listener fires before that recompute, so re-ask posted.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            val decor = window.decorView
            decor.removeCallbacks(reassertUnbuffered)
            decor.post(reassertUnbuffered)
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.setForeground(true)
        controllerInput.register()
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        lastRotation = -1
        updateRotation()
    }

    override fun onPause() {
        runtime.setForeground(false)
        controllerInput.unregister()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    // dispatchKeyEvent is public Activity API; lint flags it only because AndroidX's
    // ComponentActivity marks its own override restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A key may just have left touch mode and moved focus (see onCreate): re-ask at once,
        // before the stick's next MOVE events. A no-op when the request is unchanged.
        TouchStream.requestUnbuffered(window.decorView)
        return controllerInput.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controllerInput.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Entering touch mode can move focus too; same cheap re-ask as for keys.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) TouchStream.requestUnbuffered(window.decorView)
        TouchStream.cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
        TouchStream.pointerCancelled = event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        TouchStream.eventNanos = event.eventTimeNanos
        return super.dispatchTouchEvent(event)
    }

    // Joystick (and, in play mode, touch) MOVE events as they arrive, not once per frame. Asked
    // once attached, so it reaches ViewRootImpl at once. Any View focus change in the window
    // resets the decor's request (ViewGroup.onDescendantUnbufferedRequested rebuilds it from the
    // children, which never asked), so it is re-asserted after every focus change (onCreate's
    // listener, key and touch-down dispatch) and when the window regains focus.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TouchStream.requestUnbuffered(window.decorView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) TouchStream.requestUnbuffered(window.decorView)
    }

    private fun updateRotation() {
        val degrees = when (display?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 90
        }
        // onDisplayChanged also fires on refresh-rate changes: only a real rotation resets the gyro filters.
        if (degrees == lastRotation) return
        lastRotation = degrees
        runtime.setDisplayRotation(degrees)
    }

    private fun canStartService() = hasBluetooth() || runtime.transport.value == TransportKind.WIFI

    private fun hasBluetooth() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
