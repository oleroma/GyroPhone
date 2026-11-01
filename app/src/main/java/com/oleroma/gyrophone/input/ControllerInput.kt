package com.oleroma.gyrophone.input

import android.content.Context
import android.hardware.input.InputManager
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime

/**
 * Feeds key and joystick events from devices attached to the phone (a clip-on controller, any XInput/HID
 * pad, a USB or Bluetooth keyboard) into the mapping engine, and tracks which controllers are
 * attached. Each event carries its device, so only pads attached to the active workspace act.
 * Android only delivers these to the foreground activity, so MainActivity forwards them.
 */
class ControllerInput(context: Context, private val runtime: GamepadRuntime) : InputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(InputManager::class.java)

    /** One device the engine has seen input from, kept so joystick events do not allocate. */
    private class Source(var pad: PadDevice) {
        /** Last value of each [AndroidAxis.polled] axis. */
        val last = FloatArray(AndroidAxis.polled.size)
        /** [last] when learning started: learn mode needs a move away from where an axis rests. */
        val rest = FloatArray(AndroidAxis.polled.size)
        /** The learn listener [rest] belongs to. */
        var learnFor: Any? = null
        /** [last] holds a real event (until then an axis that rests at -1 would read as moved). */
        var seen = false
    }

    private val sources = SparseArray<Source>()

    /** The engine's view of each device a captured mouse event came from, by device id. */
    private val mice = SparseArray<PadDevice>()

    /** Wheel travel of the captured mouse not yet a whole notch: vertical, horizontal. */
    private val wheelRest = FloatArray(2)
    // One joystick event's axes for MappingEngine.setAxes, reused.
    private val axisIds = IntArray(AndroidAxis.polled.size)
    private val axisValues = FloatArray(AndroidAxis.polled.size)

    fun register() {
        inputManager.registerInputDeviceListener(this, null)
        // Devices that went away or changed while the listener was off.
        val present = inputManager.inputDeviceIds
        for (i in sources.size() - 1 downTo 0) {
            val id = sources.keyAt(i)
            val d = if (id in present) inputManager.getInputDevice(id) else null
            if (d == null) {
                runtime.engine.deviceRemoved(id)
                mice.get(id)?.let { runtime.engine.deviceRemoved(it.id) }
                mice.remove(id)
                sources.removeAt(i)
            } else {
                sources.valueAt(i).pad = padDevice(d)
            }
        }
        refresh()
    }

    fun unregister() = inputManager.unregisterInputDeviceListener(this)

    /** Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        // A captured mouse's back / forward buttons come as keys too: they are its buttons already.
        if (runtime.mouseCaptured && device.supportsSource(InputDevice.SOURCE_MOUSE) &&
            device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) return true
        val pressed = event.action == KeyEvent.ACTION_DOWN
        runtime.keyLearnListener?.let { learn ->
            if (pressed) { runtime.keyLearnListener = null; learn(event.keyCode) }
            return true
        }
        runtime.learnListener?.let { learn ->
            if (pressed) { runtime.learnListener = null; learn(InputSource.Key(event.keyCode)) }
            return true
        }
        // Unmapped pad buttons must do nothing, not fall back to BACK / focus navigation.
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            runtime.controllers.value.any { it.deviceId == device.id }
        // Pad not on screen: nothing is sent, and a keyboard types into the app as usual.
        if (!runtime.inputAllowed) return fromPad
        val mapped = runtime.engine.setKey(source(device).pad, event.keyCode, pressed)
        return mapped || fromPad
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Focus left the window (shade, dialog, split screen): the stick's return to rest goes
            // to another window, so let go of this device's axes. Its keys get their own cancel.
            if (runtime.learnListener == null) runtime.engine.clearAxes(event.deviceId)
            return true
        }
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val src = source(device)
        val learn = runtime.learnListener
        if (learn !== src.learnFor) {
            src.learnFor = learn
            src.last.copyInto(src.rest)
        }
        val polled = AndroidAxis.polled
        var n = 0
        var learnedAxis = -1
        var learned: AxisHalf? = null
        for (i in polled.indices) {
            val axis = polled[i]
            val range = device.getMotionRange(axis, event.source) ?: continue
            val v = AndroidAxis.deadzone(axis, event.getAxisValue(axis), range.flat)
            src.last[i] = v
            if (learn != null) {
                if (!src.seen) { src.rest[i] = v; continue }
                if (learned == null) {
                    learned = AndroidAxis.learnedHalf(axis, v, src.rest[i])
                    learnedAxis = axis
                }
                continue
            }
            axisIds[n] = axis; axisValues[n] = v; n++
        }
        src.seen = true
        if (learn != null) {
            if (learned != null) {
                runtime.learnListener = null
                src.learnFor = null
                learn(InputSource.Axis(learnedAxis, learned))
            }
            return true
        }
        // Pad not on screen: nothing is sent ([last] still follows the device for learning).
        if (runtime.inputAllowed) runtime.engine.setAxes(src.pad, axisIds, axisValues, n)
        return true
    }

    /**
     * An event of the mouse the pad captured (View.requestPointerCapture while the workspace maps
     * a mouse): its buttons and wheel notches go to the engine as the mouse's keys, its movement
     * through MappingEngine.mouseMove. Returns true if consumed.
     */
    fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val pad = mice.get(device.id) ?: mousePad(device).also { mice.put(device.id, it) }
        if (!runtime.inputAllowed) return true
        val engine = runtime.engine
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // Relative: each history entry is a move of its own.
                var dx = event.x
                var dy = event.y
                for (h in 0 until event.historySize) {
                    dx += event.getHistoricalX(h); dy += event.getHistoricalY(h)
                }
                if (dx != 0f || dy != 0f) engine.mouseMove(pad, dx, dy)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val key = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> AndroidKey.MOUSE_LEFT
                    MotionEvent.BUTTON_SECONDARY -> AndroidKey.MOUSE_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> AndroidKey.MOUSE_MIDDLE
                    MotionEvent.BUTTON_BACK -> AndroidKey.MOUSE_BACK
                    MotionEvent.BUTTON_FORWARD -> AndroidKey.MOUSE_FORWARD
                    else -> return true
                }
                engine.setKey(pad, key, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            }
            MotionEvent.ACTION_SCROLL -> {
                notches(pad, 0, event.getAxisValue(MotionEvent.AXIS_VSCROLL), AndroidKey.MOUSE_WHEEL_UP, AndroidKey.MOUSE_WHEEL_DOWN)
                notches(pad, 1, event.getAxisValue(MotionEvent.AXIS_HSCROLL), AndroidKey.MOUSE_WHEEL_RIGHT, AndroidKey.MOUSE_WHEEL_LEFT)
            }
        }
        return true
    }

    /** Wheel travel [v] on axis [i]: each whole notch a press and release of [up] (positive) or [down]. */
    private fun notches(pad: PadDevice, i: Int, v: Float, up: Int, down: Int) {
        if (v == 0f) return
        val total = wheelRest[i] + v
        val whole = total.toInt()
        wheelRest[i] = total - whole
        repeat(kotlin.math.abs(whole)) {
            val key = if (whole > 0) up else down
            runtime.engine.setKey(pad, key, true)
            runtime.engine.setKey(pad, key, false)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) {
        // Releases whatever that device held, then drops it from the attached list.
        runtime.engine.deviceRemoved(deviceId)
        sources.remove(deviceId)
        mice.get(deviceId)?.let { runtime.engine.deviceRemoved(it.id) }
        mice.remove(deviceId)
        refresh()
    }
    override fun onInputDeviceChanged(deviceId: Int) {
        val d = inputManager.getInputDevice(deviceId)
        if (d != null) sources.get(deviceId)?.pad = padDevice(d)
        refresh()
    }

    private fun refresh() {
        val ids = inputManager.inputDeviceIds.toList()
        val mouse = ids.any { id ->
            val d = inputManager.getInputDevice(id)
            d != null && !d.isVirtual && d.supportsSource(InputDevice.SOURCE_MOUSE)
        }
        val list = ids.mapNotNull { id ->
            val d = inputManager.getInputDevice(id) ?: return@mapNotNull null
            val isPad = d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (d.isVirtual || !isPad) return@mapNotNull null
            val has = d.hasKeys(*KEY_CANDIDATES)
            AttachedController(
                deviceId = d.id,
                name = d.name,
                vendorId = d.vendorId,
                productId = d.productId,
                known = KnownLayouts.identify(d.vendorId, d.productId, d.name),
                keys = KEY_CANDIDATES.filterIndexed { i, _ -> has.getOrElse(i) { false } }.toSet(),
                axes = d.motionRanges.map { it.axis }.toSet(),
            )
        }
        runtime.onControllersChanged(list, mouse)
    }

    private fun source(device: InputDevice): Source =
        sources.get(device.id) ?: Source(padDevice(device)).also { sources.put(device.id, it) }

    /**
     * The engine's view of [device]: it picks the active workspace's attached pad by model, or the
     * keyboard mappings for a full keyboard that is not also a game pad. An analog trigger axis on
     * a side makes the engine ignore that side's L2 / R2 key for the game pad trigger.
     */
    private fun padDevice(device: InputDevice) = PadDevice(
        device.id, device.vendorId, device.productId, device.name,
        keyboard = device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            device.sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD,
        analogLeftTrigger = device.getMotionRange(AndroidAxis.LTRIGGER) != null ||
            device.getMotionRange(AndroidAxis.BRAKE) != null,
        analogRightTrigger = device.getMotionRange(AndroidAxis.RTRIGGER) != null ||
            device.getMotionRange(AndroidAxis.GAS) != null,
    )

    /** The engine's view of a mouse: its own device (id apart from the same device's keys), with the mouse mappings. */
    private fun mousePad(device: InputDevice) =
        PadDevice(MOUSE_ID_BASE + device.id, device.vendorId, device.productId, device.name, mouse = true)

    companion object {
        /** Added to a device's id for its mouse part, so a keyboard with a touchpad keeps its keys apart. */
        private const val MOUSE_ID_BASE = 1 shl 28

        /** Keycodes probed with InputDevice.hasKeys: every known controller key plus BUTTON_1..16. */
        private val KEY_CANDIDATES: IntArray =
            (AndroidKey.names.keys + (AndroidKey.BUTTON_1 until AndroidKey.BUTTON_1 + 16)).distinct().toIntArray()
    }
}
