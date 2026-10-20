package com.oleroma.gyrophone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.oleroma.gyrophone.input.ControllerInput
import com.oleroma.gyrophone.link.TransportKind
import com.oleroma.gyrophone.runtime
import com.oleroma.gyrophone.service.GamepadService

class MainActivity : ComponentActivity() {

    private lateinit var controllerInput: ControllerInput

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (canStartService()) GamepadService.start(this)
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
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            listOf("Connect", "Pad", "Controller", "Profiles").forEachIndexed { i, title ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> ConnectScreen(runtime, onRetry = { permissionRequest.launch(permissions) })
                            1 -> PadScreen(runtime)
                            2 -> ControllerScreen(runtime)
                            else -> ProfilesScreen(runtime)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controllerInput.register()
        updateRotation()
    }

    override fun onPause() {
        controllerInput.unregister()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controllerInput.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controllerInput.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    private fun updateRotation() {
        val degrees = when (display?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 90
        }
        runtime.setDisplayRotation(degrees)
    }

    private fun canStartService() = hasBluetooth() || runtime.transport.value == TransportKind.WIFI

    private fun hasBluetooth() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
