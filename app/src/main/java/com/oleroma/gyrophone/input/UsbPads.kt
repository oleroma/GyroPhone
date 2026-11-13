package com.oleroma.gyrophone.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.InputDevice
import androidx.core.content.ContextCompat
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.input.HidGamepad
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.PhysicalSticks
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime

/**
 * "Read USB controllers directly" (Settings, on by default): GP takes a game pad plugged into the
 * phone's USB port away from Android and reads its HID reports itself, each on a thread of its
 * own ("gp-usb-pad"), straight into the mapping engine. Android's input path (its reader and
 * dispatcher, then the app's screen thread, which a heavy frame can hold) is skipped, and the
 * presses keep coming with GP in the background.
 *
 * A pad is held while hardware input is allowed ([GamepadRuntime.hardwareAllowed]: always, or with
 * **Send input only while the pad is open** and its **Hardware devices too** on, only while the pad
 * is open) and GP is running; otherwise it is let go. While held, the app in front gets nothing
 * from it. Only pads Android has listed as a game pad or joystick are taken (so a keyboard,
 * headset or the like is never touched); the reports become the same Android keys and axes
 * ([HidGamepad]). Android asks once per plug-in whether GP may use it; refused, or failing in
 * any way, the pad stays Android's as before.
 */
class UsbPads(private val context: Context, private val runtime: GamepadRuntime) {

    /** The pad's own screen-side handling, for "Press a key" and learning ([ControllerInput]). */
    interface Ui {
        fun usbKey(pad: Pad, keyCode: Int, pressed: Boolean)
        fun usbAxes(pad: Pad, values: FloatArray)
        fun usbChanged()
    }

    /** A pad GP holds. */
    inner class Pad(
        val id: Int,
        val usb: UsbDevice,
        val name: String,
        val gamepad: HidGamepad,
        val conn: UsbDeviceConnection,
        val iface: UsbInterface,
        val endpoint: UsbEndpoint,
    ) : HidGamepad.Sink {
        private fun has(axis: Int) = axis in gamepad.axes

        val device = PadDevice(
            id, usb.vendorId, usb.productId, name,
            analogLeftTrigger = has(AndroidAxis.LTRIGGER) || has(AndroidAxis.BRAKE),
            analogRightTrigger = has(AndroidAxis.RTRIGGER) || has(AndroidAxis.GAS),
            rightStickRx = PhysicalSticks.rightStickOnRx { has(it) },
        )

        /** The right stick is on Z / RZ (as [ControllerInput]'s Source.zStick). */
        val zStick = has(AndroidAxis.Z) && has(AndroidAxis.RZ) && (
            !has(AndroidAxis.RX) || !has(AndroidAxis.RY) ||
                has(AndroidAxis.LTRIGGER) || has(AndroidAxis.RTRIGGER) || has(AndroidAxis.GAS) || has(AndroidAxis.BRAKE)
            )

        /** [ControllerInput]'s state for it while a listener listens. */
        @Volatile var source: Any? = null

        @Volatile var running = true
        var thread: Thread? = null

        // When the report being decoded arrived (System.nanoTime), and setAxes' arrays, reused.
        private var now = 0L
        private val ids = IntArray(AndroidAxis.polled.size)
        private val vals = FloatArray(AndroidAxis.polled.size)

        fun read() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val buf = ByteArray(maxOf(64, endpoint.maxPacketSize))
            var quickErrors = 0
            while (running) {
                val start = System.nanoTime()
                val n = conn.bulkTransfer(endpoint, buf, buf.size, READ_TIMEOUT_MS)
                if (!running) break
                if (n > 0) {
                    quickErrors = 0
                    now = System.nanoTime()
                    gamepad.decode(buf, n, this)
                } else if (n < 0 && System.nanoTime() - start < 5_000_000L) {
                    // Not a timeout: the pad errs at once (gone, or taken back). Stop after a few.
                    if (++quickErrors >= 20) {
                        Log.w(TAG, "$name: reads keep failing, letting it go")
                        control.post { close(this, failed = true) }
                        break
                    }
                    Thread.sleep(5)
                }
            }
        }

        override fun key(keyCode: Int, down: Boolean) {
            runtime.wifi.pressStamp.note(now)
            if (down) runtime.ratchetTiming.inputDown(now)
            runtime.wifi.overlay.padKey(keyCode, down)
            if (runtime.pressListener != null || runtime.learnListener != null) {
                ui?.usbKey(this, keyCode, down)
                return
            }
            if (runtime.hardwareAllowed) runtime.engine.setKey(device, keyCode, down)
        }

        override fun axes(values: FloatArray) {
            if (runtime.wifi.overlay.wanted) overlayAxes(values)
            if (runtime.pressListener != null || runtime.learnListener != null) {
                ui?.usbAxes(this, values.copyOf())
                return
            }
            if (!runtime.hardwareAllowed) return
            var n = 0
            for (i in values.indices) {
                val v = values[i]
                if (v.isNaN()) continue
                ids[n] = AndroidAxis.polled[i]; vals[n] = v; n++
            }
            runtime.engine.setAxes(device, ids, vals, n)
        }

        private fun overlayAxes(v: FloatArray) {
            fun at(axis: Int) = v[AndroidAxis.polled.indexOf(axis)].let { if (it.isNaN()) 0f else it }
            runtime.wifi.overlay.padAxes(
                at(AndroidAxis.X), at(AndroidAxis.Y),
                if (zStick) at(AndroidAxis.Z) else at(AndroidAxis.RX),
                if (zStick) at(AndroidAxis.RZ) else at(AndroidAxis.RY),
                maxOf(at(AndroidAxis.LTRIGGER), at(AndroidAxis.BRAKE)), maxOf(at(AndroidAxis.RTRIGGER), at(AndroidAxis.GAS)),
                at(AndroidAxis.HAT_X), at(AndroidAxis.HAT_Y),
            )
        }
    }

    @Volatile var ui: Ui? = null

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val prefs = context.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)

    /** Opening, closing and permission run here (they block); each pad reads on its own thread. */
    private val controlThread = HandlerThread("gp-usb").also { it.start() }
    private val control = Handler(controlThread.looper)

    // Control thread only, except [pads] (read anywhere).
    @Volatile private var pads: List<Pad> = emptyList()
    private var started = false

    /** "vid:pid" of every game pad Android has listed: only these are taken. Kept, as a taken pad leaves Android's list. */
    private val knownPads = HashSet<String>(prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty())

    /** Devices (by USB name) asked about since they were plugged in, and ones that could not be read: not tried again until replugged. */
    private val asked = HashSet<String>()
    private val failed = HashSet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> control.post {
                    if (dev != null) {
                        pads.firstOrNull { it.usb.deviceName == dev.deviceName }?.let { close(it, failed = false) }
                        asked.remove(dev.deviceName); failed.remove(dev.deviceName)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Android lists a new pad a moment after it is plugged in.
                    control.postDelayed({ update() }, 600)
                    control.postDelayed({ update() }, 2000)
                }
                ACTION_PERMISSION -> control.post { update() }
            }
        }
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = update()
        override fun onInputDeviceRemoved(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = Unit
    }

    /** The pads GP holds now, for the attached list. */
    fun attached(): List<AttachedController> = pads.map { p ->
        AttachedController(
            deviceId = p.id,
            name = p.name,
            vendorId = p.usb.vendorId,
            productId = p.usb.productId,
            known = KnownLayouts.identify(p.usb.vendorId, p.usb.productId, p.name),
            keys = p.gamepad.keys,
            axes = p.gamepad.axes,
        )
    }

    /** Whether GP holds a pad of this model now (Android's copy of it is on its way out). */
    fun holds(vendorId: Int, productId: Int): Boolean = pads.any { it.usb.vendorId == vendorId && it.usb.productId == productId }

    /** Takes or lets go of pads as the switch, the runtime and hardware input now say. Any thread. */
    fun update() {
        control.post { sync() }
    }

    private fun wanted(): Boolean = runtime.usbDirectOn.value && runtime.isRunning && runtime.hardwareAllowed

    private fun sync() {
        if (!wanted()) {
            for (p in pads) close(p, failed = false)
            return
        }
        if (!started) {
            started = true
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            inputManager.registerInputDeviceListener(inputListener, control)
        }
        learnPads()
        val devices = usbManager?.deviceList?.values.orEmpty()
        for (dev in devices) {
            if (pads.any { it.usb.deviceName == dev.deviceName }) continue
            if (dev.deviceName in failed) continue
            if (key(dev.vendorId, dev.productId) !in knownPads) continue
            if (!usbManager.hasPermission(dev)) {
                if (asked.add(dev.deviceName)) {
                    val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                    val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    usbManager.requestPermission(dev, pi)
                }
                continue
            }
            open(dev)
        }
    }

    /** Remembers every game pad Android lists now. */
    private fun learnPads() {
        var added = false
        for (id in inputManager.inputDeviceIds) {
            val d = inputManager.getInputDevice(id) ?: continue
            if (d.isVirtual || !d.isExternal) continue
            val pad = d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (pad && knownPads.add(key(d.vendorId, d.productId))) added = true
        }
        if (added) prefs.edit().putStringSet(KEY_KNOWN, HashSet(knownPads)).apply()
    }

    private fun open(dev: UsbDevice) {
        val conn = usbManager.openDevice(dev) ?: run { failed += dev.deviceName; return }
        var taken: Pad? = null
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            // HID, and not a boot keyboard or mouse (a pad's own mouse stays Android's).
            if (iface.interfaceClass != UsbConstants.USB_CLASS_HID || iface.interfaceSubclass == 1) continue
            val ep = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT } ?: continue
            if (!conn.claimInterface(iface, true)) continue
            val desc = reportDescriptor(conn, iface.id)
            val gp = desc?.let { HidGamepad(it) }
            if (gp == null || !gp.usable) {
                conn.releaseInterface(iface)
                continue
            }
            val name = nameOf(dev)
            val pad = Pad(ID_BASE + dev.deviceId, dev, name, gp, conn, iface, ep)
            pad.thread = Thread(pad::read, "gp-usb-pad").also { it.start() }
            pads = pads + pad
            taken = pad
            Log.i(TAG, "Reading $name directly (${gp.keys.size} keys, ${gp.axes.size} axes)")
            break
        }
        if (taken == null) {
            conn.close()
            failed += dev.deviceName
        } else ui?.usbChanged()
    }

    private fun close(p: Pad, failed: Boolean) {
        if (p !in pads) return
        p.running = false
        pads = pads - p
        runCatching { p.conn.releaseInterface(p.iface) }
        runCatching { p.conn.close() }
        p.thread?.join(READ_TIMEOUT_MS + 200L)
        runtime.engine.deviceRemoved(p.id)
        runtime.wifi.overlay.clearPad()
        if (failed) this.failed += p.usb.deviceName
        ui?.usbChanged()
    }

    /** The HID report descriptor of interface [iface] (GET_DESCRIPTOR, report), or null. */
    private fun reportDescriptor(conn: UsbDeviceConnection, iface: Int): ByteArray? {
        val len = hidDescriptorLength(conn.rawDescriptors, iface).takeIf { it in 1..4096 } ?: 1024
        val buf = ByteArray(len)
        val n = conn.controlTransfer(0x81, 0x06, 0x2200, iface, buf, len, 1000)
        return if (n > 0) buf.copyOf(n) else null
    }

    /** The report descriptor's length from the HID descriptor of interface [iface] in the raw configuration, or 0. */
    private fun hidDescriptorLength(raw: ByteArray?, iface: Int): Int {
        if (raw == null) return 0
        var i = 0
        var current = -1
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2) break
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x04 && i + 2 < raw.size) current = raw[i + 2].toInt() and 0xFF
            if (type == 0x21 && current == iface && i + 8 < raw.size) {
                return (raw[i + 7].toInt() and 0xFF) or ((raw[i + 8].toInt() and 0xFF) shl 8)
            }
            i += len
        }
        return 0
    }

    /** The name Linux gives it ("<manufacturer> <product>", as Android lists it). */
    private fun nameOf(dev: UsbDevice): String {
        val m = dev.manufacturerName?.trim().orEmpty()
        val p = dev.productName?.trim().orEmpty()
        return when {
            m.isEmpty() -> p.ifEmpty { "USB controller" }
            p.isEmpty() -> m
            p.startsWith(m) -> p
            else -> "$m $p"
        }
    }

    private fun key(vendor: Int, product: Int) = "%04x:%04x".format(vendor, product)

    companion object {
        private const val TAG = "GPUsbPad"
        private const val ACTION_PERMISSION = "com.oleroma.gyrophone.USB_PERMISSION"
        private const val KEY_KNOWN = "usb_known_pads"
        private const val READ_TIMEOUT_MS = 250

        /** Added to the USB device id: apart from Android's input device ids and the other ids GP gives devices. */
        private const val ID_BASE = 1 shl 27
    }
}
