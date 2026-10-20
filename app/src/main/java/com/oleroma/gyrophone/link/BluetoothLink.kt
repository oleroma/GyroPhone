package com.oleroma.gyrophone.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.hid.ReportPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Bluetooth HID through the existing [HidDeviceManager] and [ReportPump], both unchanged. */
class BluetoothLink(
    private val context: Context,
    val hid: HidDeviceManager,
    private val pump: ReportPump,
) : OutputLink {
    override val kind = TransportKind.BLUETOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val state: StateFlow<LinkState> = hid.state
        .map { toLinkState(it) }
        .stateIn(scope, SharingStarted.Eagerly, toLinkState(hid.state.value))

    override fun start() {
        // Registering the HID app needs BLUETOOTH_CONNECT; without it (e.g. switching over from
        // Wi-Fi before granting it) stay Off and let the Connect tab's Retry ask for it.
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            hid.start()
        } else {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted; HID stays off")
        }
        pump.start()
    }

    /** Unregisters the HID app as well, so Windows does not reconnect a second pad behind Wi-Fi. */
    override fun stop() {
        pump.stop()
        hid.stop()
    }

    override fun stats() = LinkStats(pump.sentPerSecond, pump.failedPerSecond)

    private fun toLinkState(s: HidDeviceManager.State): LinkState = when (s) {
        HidDeviceManager.State.Off -> LinkState.Off
        HidDeviceManager.State.Registering, HidDeviceManager.State.Ready -> LinkState.Searching
        is HidDeviceManager.State.Connecting -> LinkState.Connecting(s.name)
        is HidDeviceManager.State.Connected -> LinkState.Connected(s.name, "Bluetooth")
        is HidDeviceManager.State.Failed -> LinkState.Failed(s.reason)
    }

    companion object {
        private const val TAG = "BluetoothLink"
    }
}
