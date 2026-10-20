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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Registers the phone as a Bluetooth Classic HID device (game pad + keyboard + mouse) and sends
 * reports to the connected host. Classic BR/EDR HID is what Windows pairs with natively; the
 * interrupt channel QoS below asks for guaranteed service sized for 1000 reports/s at minimal latency.
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
    private val callbackExecutor = Executors.newSingleThreadExecutor { Thread(it, "hid-callback") }

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    /** False after stop(): a proxy that connects late is closed instead of registering the app. */
    @Volatile private var wanted = false

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

    fun start() {
        val a = adapter ?: run { _state.value = State.Failed("No Bluetooth adapter"); return }
        if (!a.isEnabled) { _state.value = State.Failed("Bluetooth is off"); return }
        wanted = true
        if (hid != null) return
        _state.value = State.Registering
        a.getProfileProxy(context, proxyListener, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        wanted = false
        hid?.let { proxy ->
            host?.let { proxy.disconnect(it) }
            proxy.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }
        hid = null
        host = null
        onHostDisconnected?.invoke()
        _state.value = State.Off
    }

    fun connect(device: BluetoothDevice) {
        val proxy = hid ?: return
        _state.value = State.Connecting(device.name ?: device.address)
        if (!proxy.connect(device)) _state.value = State.Failed("Could not connect to ${device.name}")
    }

    fun disconnect() {
        val proxy = hid ?: return
        host?.let { proxy.disconnect(it) }
    }

    /** Sends one input report (payload without the report ID). Returns false if not delivered. */
    fun send(reportId: Int, payload: ByteArray): Boolean {
        val proxy = hid ?: return false
        val device = host ?: return false
        return proxy.sendReport(device, reportId, payload)
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val device = proxy as BluetoothHidDevice
            // stop() ran before the proxy arrived, or an earlier start() already has one.
            if (!wanted || hid != null) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                return
            }
            hid = device
            val ok = device.registerApp(sdp, null, qos, callbackExecutor, hidCallback)
            if (!ok) _state.value = State.Failed("Another app owns the HID profile")
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            _state.value = State.Off
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "app registered=$registered plugged=${pluggedDevice?.address}")
            if (!registered) {
                if (hid != null) _state.value = State.Failed("HID app unregistered")
                return
            }
            _state.value = State.Ready
            // Reconnect to the last host if the stack remembers one.
            pluggedDevice?.let { connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    _state.value = State.Connected(device.name ?: device.address, device.address)
                    onHostConnected?.invoke()
                }
                BluetoothProfile.STATE_CONNECTING -> _state.value = State.Connecting(device.name ?: device.address)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    onHostDisconnected?.invoke()
                    if (hid != null) _state.value = State.Ready
                }
            }
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
