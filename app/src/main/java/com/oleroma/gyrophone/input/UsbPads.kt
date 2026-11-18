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
 * ([HidGamepad]). A pad whose generic reading has other buttons or axes than Android listed (one
 * Linux or Android remaps, such as Sony's and Nintendo's) stays Android's. Android asks once per
 * plug-in whether GP may use it; refused, or failing in any way, the pad stays Android's as before.
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

        private val rx = PhysicalSticks.rightStickOnRx { has(it) }

        // On a pad with its right stick on RX / RY, Z / RZ are its analog triggers (the engine reads them as LTRIGGER / RTRIGGER).
        val device = PadDevice(
            id, usb.vendorId, usb.productId, name,
            analogLeftTrigger = has(AndroidAxis.LTRIGGER) || has(AndroidAxis.BRAKE) || rx && has(AndroidAxis.Z),
            analogRightTrigger = has(AndroidAxis.RTRIGGER) || has(AndroidAxis.GAS) || rx && has(AndroidAxis.RZ),
            rightStickRx = rx,
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
            if (runtime.pressListener != null || runtime.learnListener != null) {
                // A key held when the listener started still gets its release.
                if (!down) runtime.engine.setKey(device, keyCode, false)
                ui?.usbKey(this, keyCode, down)
            } else if (runtime.hardwareAllowed) {
                runtime.engine.setKey(device, keyCode, down)
            }
            // The display after the engine has the input.
            runtime.wifi.overlay.padKey(keyCode, down)
        }

        override fun axes(values: FloatArray) {
            if (runtime.pressListener != null || runtime.learnListener != null) {
                // The listener's thread lets go of the axes in the engine ([ControllerInput.usbAxes]).
                ui?.usbAxes(this, values.copyOf())
            } else if (runtime.hardwareAllowed) {
                var n = 0
                for (i in values.indices) {
                    val v = values[i]
                    if (v.isNaN()) continue
                    ids[n] = AndroidAxis.polled[i]; vals[n] = v; n++
                }
                runtime.engine.setAxes(device, ids, vals, n)
            }
            // The display after the engine has the input.
            if (runtime.wifi.overlay.wanted) overlayAxes(values)
            if (runtime.stickWatch.on) watchAxes(values)
        }

        private fun watchAxes(v: FloatArray) {
            fun at(axis: Int) = v[AndroidAxis.polled.indexOf(axis)]
            runtime.stickWatch.set(
                at(AndroidAxis.X), at(AndroidAxis.Y),
                if (zStick) at(AndroidAxis.Z) else at(AndroidAxis.RX),
                if (zStick) at(AndroidAxis.RZ) else at(AndroidAxis.RY),
            )
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

    /** What Android listed for a pad: its key codes (of [HidGamepad.PROBE_KEYS]) and joystick axes (of [HidGamepad.PROBE_AXES]). */
    private data class Listed(val keys: Set<Int>, val axes: Set<Int>)

    /**
     * Every game pad Android has listed, by "vid:pid", with what Android gave it: only these are
     * taken, and only when [HidGamepad.matchesAndroid]. Kept, as a taken pad leaves Android's list.
     */
    private val knownPads = HashMap<String, Listed>().also { m ->
        for (e in prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()) decodeListed(e)?.let { m[it.first] = it.second }
    }

    /**
     * Pads whose generic reading did not match what Android listed, as [encodeListed] gives them:
     * not asked about or opened again (no permission prompt, no blip) while Android lists them so.
     */
    private val mismatched = HashSet<String>(prefs.getStringSet(KEY_MISMATCHED, emptySet()).orEmpty())

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

    /** Whether GP reads any pad directly now (its presses reach GP in the background too). Any thread. */
    val holdsAny: Boolean get() = pads.isNotEmpty()

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
            val k = key(dev.vendorId, dev.productId)
            val listed = knownPads[k] ?: continue
            if (encodeListed(k, listed) in mismatched) continue
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

    /** Remembers every game pad Android lists now, with the keys and axes Android gives it. */
    private fun learnPads() {
        val now = HashMap<String, Listed>()
        for (id in inputManager.inputDeviceIds) {
            val d = inputManager.getInputDevice(id) ?: continue
            if (d.isVirtual || !d.isExternal) continue
            val pad = d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (!pad) continue
            val probe = HidGamepad.PROBE_KEYS
            val has = d.hasKeys(*probe)
            val keys = HashSet<Int>()
            for (i in probe.indices) if (has.getOrElse(i) { false }) keys += probe[i]
            val axes = HashSet<Int>()
            for (a in HidGamepad.PROBE_AXES) if (d.getMotionRange(a, InputDevice.SOURCE_JOYSTICK) != null) axes += a
            // Several devices of one model (or one pad listed twice): all of them.
            val k = key(d.vendorId, d.productId)
            val was = now[k]
            now[k] = if (was == null) Listed(keys, axes) else Listed(was.keys + keys, was.axes + axes)
        }
        var changed = false
        for ((k, v) in now) if (knownPads.put(k, v) != v) changed = true
        if (changed) prefs.edit().putStringSet(KEY_KNOWN, knownPads.mapTo(HashSet()) { (k, v) -> encodeListed(k, v) }).apply()
    }

    private fun encodeListed(k: String, v: Listed) = k + ";" + v.keys.sorted().joinToString(",") + ";" + v.axes.sorted().joinToString(",")

    private fun decodeListed(e: String): Pair<String, Listed>? = runCatching {
        val parts = e.split(';')
        if (parts.size != 3) return null
        fun ints(s: String) = if (s.isEmpty()) emptySet() else s.split(',').mapTo(HashSet()) { it.toInt() }
        parts[0] to Listed(ints(parts[1]), ints(parts[2]))
    }.getOrNull()

    private fun open(dev: UsbDevice) {
        val conn = usbManager.openDevice(dev) ?: run { failed += dev.deviceName; return }
        var taken: Pad? = null
        var mismatch: String? = null
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
            // Read generically it must give what Android gave it: a pad Linux or Android remaps
            // (Sony's, Nintendo's, a vendor key layout) would get the wrong buttons and sticks.
            val k = key(dev.vendorId, dev.productId)
            val listed = knownPads[k]
            if (listed == null || !gp.matchesAndroid(listed.keys, listed.axes)) {
                Log.i(TAG, "$name: Android maps it differently, leaving it to Android")
                if (listed != null) mismatch = encodeListed(k, listed)
                conn.releaseInterface(iface)
                continue
            }
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
            if (mismatch != null && mismatched.add(mismatch)) prefs.edit().putStringSet(KEY_MISMATCHED, HashSet(mismatched)).apply()
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
        /** "vid:pid;keys;axes" per pad (the older "usb_known_pads" had no keys and axes, and is not read). */
        private const val KEY_KNOWN = "usb_pad_layouts"
        /** Pads left to Android as they don't match ([mismatched]), "vid:pid;keys;axes" as Android listed them then. */
        private const val KEY_MISMATCHED = "usb_pad_mismatched"
        private const val READ_TIMEOUT_MS = 250

        /** Added to the USB device id: apart from Android's input device ids and the other ids GP gives devices. */
        private const val ID_BASE = 1 shl 27
    }
}
