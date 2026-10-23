package com.oleroma.gyrophone.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.hid.HostReconnect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Registers the phone as a Bluetooth Classic HID device (game pad + keyboard + mouse) and sends
 * reports to the connected host. Classic BR/EDR HID is what Windows pairs with natively; the
 * interrupt channel QoS below asks for guaranteed service sized for 1000 reports/s at minimal latency.
 * When the link to the host drops without the user asking, the last host is reconnected with
 * backoff ([HostReconnect]).
 *
 * Callers must hold BLUETOOTH_CONNECT (requested by MainActivity before the service starts).
 */
@SuppressLint("MissingPermission")
class HidDeviceManager(private val context: Context) {

    sealed interface State {
        data object Off : State
        data object Registering : State
        /** Registered and waiting; pair or connect from the PC, or pick a host in the app. */
        data object Ready : State
        data class Connecting(val name: String) : State
        data class Connected(val name: String, val address: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    /** HID callbacks and reconnect attempts, in order on one thread. */
    private val callbackExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "hid-callback") }

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    /** False after stop(): a proxy that connects late is closed instead of registering the app. */
    @Volatile private var wanted = false
    /** The stack reports the app registered (onAppStatusChanged). */
    @Volatile private var appRegistered = false
    /** registerApp() accepted the request and its onAppStatusChanged has not come yet. */
    @Volatile private var registering = false
    /** The host last connected, which [reconnect] retries. */
    @Volatile private var lastHost: BluetoothDevice? = null
    /** Retry rules; guarded by itself. */
    private val reconnect = HostReconnect()

    /** Supplies the current report bytes when the host polls with GET_REPORT. */
    var reportProvider: ((reportId: Int) -> ByteArray?)? = null
    /** Called after a host connects, so the pump re-sends full state. */
    var onHostConnected: (() -> Unit)? = null
    /** Called when the host disconnects. */
    var onHostDisconnected: (() -> Unit)? = null
    /** Current feature report bytes for GET_REPORT(feature), or null if the ID has none. */
    var featureProvider: ((reportId: Int) -> ByteArray?)? = null
    /** SET_REPORT(feature) from the host: report ID and its value byte. */
    var onFeature: ((reportId: Int, value: Int) -> Unit)? = null

    val isConnected: Boolean get() = host != null

    fun bondedHosts(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()

    /** Registers the app; also the Retry after a refused or dropped registration. */
    fun start() {
        val a = adapter ?: run { _state.value = State.Failed("No Bluetooth adapter"); return }
        wanted = true
        val proxy = hid
        if (proxy != null) {
            // A pending registration answers by itself; a refused or dropped one is tried again.
            if (!appRegistered && !registering) register(proxy)
            return
        }
        // With Bluetooth off the proxy connects, and registers, once the adapter turns on.
        _state.value = if (a.isEnabled) State.Registering else State.Failed("Bluetooth is off")
        a.getProfileProxy(context, ProxyListener(), BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        wanted = false
        synchronized(reconnect) { reconnect.cancel() }
        hid?.let { proxy ->
            host?.let { proxy.disconnect(it) }
            proxy.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }
        hid = null
        host = null
        appRegistered = false
        registering = false
        onHostDisconnected?.invoke()
        _state.value = State.Off
    }

    /** The user picked [device]: it replaces any pending reconnect. */
    fun connect(device: BluetoothDevice) {
        synchronized(reconnect) { reconnect.userConnect() }
        connectNow(device)
    }

    private fun connectNow(device: BluetoothDevice) {
        val proxy = hid ?: return
        _state.value = State.Connecting(device.name ?: device.address)
        if (!proxy.connect(device)) _state.value = State.Failed("Could not connect to ${device.name}")
    }

    /** The user disconnects: that link is not reconnected. */
    fun disconnect() {
        synchronized(reconnect) { reconnect.userDisconnect() }
        val proxy = hid ?: return
        host?.let { proxy.disconnect(it) }
    }

    /** Sends one input report (payload without the report ID). Returns false if not delivered. */
    fun send(reportId: Int, payload: ByteArray): Boolean {
        val proxy = hid ?: return false
        val device = host ?: return false
        return proxy.sendReport(device, reportId, payload)
    }

    /**
     * Registers the app on [proxy]. If the service refuses, a stale registration of ours (one
     * whose callback never came) is dropped and registration tried once more. unregisterApp() does
     * nothing when another app owns the profile, and its result is ignored: it can free our stale
     * slot and still return false.
     */
    private fun register(proxy: BluetoothHidDevice) {
        _state.value = State.Registering
        registering = true
        val ok = proxy.registerApp(sdp, null, qos, callbackExecutor, hidCallback) ||
            run { proxy.unregisterApp(); proxy.registerApp(sdp, null, qos, callbackExecutor, hidCallback) }
        if (!ok) {
            registering = false
            _state.value = State.Failed("Another app owns the HID profile")
        }
    }

    private fun canRetry() = wanted && appRegistered && hid != null

    /** Applies [step] to the retry rules and schedules the attempt it asks for, if any. */
    private fun retryStep(step: HostReconnect.() -> Long) {
        val delayMs: Long
        val generation: Int
        synchronized(reconnect) {
            delayMs = reconnect.step()
            generation = reconnect.generation
        }
        if (delayMs >= 0) callbackExecutor.schedule({ retryHost(generation) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun retryHost(generation: Int) {
        synchronized(reconnect) {
            if (reconnect.generation != generation || reconnect.retrying == null) return
        }
        val proxy = hid
        val device = lastHost
        // The bond goes after the drop when the host removed the phone (virtual cable unplug):
        // connecting to an unbonded host would start pairing instead.
        if (proxy == null || device == null || !canRetry() || host != null ||
            device.bondState != BluetoothDevice.BOND_BONDED
        ) {
            synchronized(reconnect) { reconnect.cancel() }
            return
        }
        Log.i(TAG, "reconnecting to ${device.address}")
        _state.value = State.Connecting(device.name ?: device.address)
        // A failed attempt ends in STATE_DISCONNECTED, which schedules the next one.
        if (!proxy.connect(device)) {
            if (hid != null) _state.value = State.Ready
            retryStep { attemptRefused() }
        }
    }

    /** One per getProfileProxy() call, so a proxy we closed cannot clear the one in use. */
    private inner class ProxyListener : BluetoothProfile.ServiceListener {
        private var mine: BluetoothHidDevice? = null

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val device = proxy as BluetoothHidDevice
            // stop() ran before the proxy arrived, or an earlier start() already has one.
            if (!wanted || hid != null) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                return
            }
            mine = device
            hid = device
            register(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            // Bluetooth turned off; the proxy connects (and registers) again when it is back on.
            if (mine == null || hid !== mine) return
            hid = null
            host = null
            appRegistered = false
            registering = false
            _state.value = State.Off
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "app registered=$registered plugged=${pluggedDevice?.address}")
            appRegistered = registered
            registering = false
            if (hid == null) return // stopped meanwhile
            if (!registered) {
                _state.value = State.Failed("HID app unregistered")
                return
            }
            _state.value = State.Ready
            // Reconnect to the last host if the stack remembers one.
            pluggedDevice?.let { connectNow(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    lastHost = device
                    synchronized(reconnect) { reconnect.connected(device.address) }
                    _state.value = State.Connected(device.name ?: device.address, device.address)
                    onHostConnected?.invoke()
                }
                BluetoothProfile.STATE_CONNECTING -> _state.value = State.Connecting(device.name ?: device.address)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    onHostDisconnected?.invoke()
                    if (hid != null) _state.value = State.Ready
                    // An unexpected drop of the last host, or a failed retry: try it again later.
                    val retry = canRetry() && device.bondState == BluetoothDevice.BOND_BONDED
                    retryStep { disconnected(device.address, retry) }
                }
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            // The host removed the phone (unplug or unpair on the PC). The stack reports the
            // disconnect first, so this cancels the retry that drop scheduled; the host ended the
            // link on purpose and it is not retried.
            Log.i(TAG, "virtual cable unplug from ${device.address}")
            synchronized(reconnect) { reconnect.userDisconnect() }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val proxy = hid ?: return
            val data = when (type) {
                BluetoothHidDevice.REPORT_TYPE_INPUT -> reportProvider?.invoke(id.toInt())
                BluetoothHidDevice.REPORT_TYPE_FEATURE -> featureProvider?.invoke(id.toInt())
                else -> null
            }
            if (data != null) proxy.replyReport(device, type, id, data)
            else proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && data.isNotEmpty()) {
                // Stacks differ on whether the report ID byte leads the data.
                val value = if (data.size >= 2 && data[0] == id) data[1] else data[0]
                Log.i(TAG, "SET_REPORT feature id=$id value=0x%02X".format(value.toInt() and 0xFF))
                onFeature?.invoke(id.toInt(), value.toInt() and 0xFF)
            }
            // Keyboard LED output reports etc. are acknowledged and ignored for now.
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    companion object {
        private const val TAG = "HidDeviceManager"

        private val sdp = BluetoothHidDeviceAppSdpSettings(
            "GyroPhone",
            "Phone game pad, keyboard and mouse",
            "oleroma",
            (BluetoothHidDevice.SUBCLASS1_COMBO.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
            HidDescriptor.bytes,
        )

        /**
         * Outgoing (interrupt channel) QoS, a token bucket in bytes. The values copied from the
         * usual BluetoothHidDevice samples (token rate 800 B/s) cap the link at about 100 small
         * reports/s where the stack or host honours it: every report on the wire is 2 bytes of
         * header + payload, so anything faster queues and arrives late (higher rates felt laggier).
         * Size it for 1000 Hz of all three reports (mouse 8 + game pad 17 + keyboard 10 bytes).
         * Latency is in microseconds: 1250 us = 2 BR/EDR slots, the shortest poll interval.
         */
        private const val TOKEN_RATE_BYTES_PER_SEC = 1000 * (8 + 17 + 10)

        private val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
            TOKEN_RATE_BYTES_PER_SEC,
            128,
            0,
            1250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
    }
}
