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
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.PhysicalSticks
import com.oleroma.gyrophone.core.model.PressTargets
import kotlin.math.abs
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
        /**
         * The event source [flat] and [zStick] were read for (0 = not yet): the device's ranges are
         * looked up once, not on every event, and again when the device changes.
         */
        var rangesFor = 0
        /** Each [AndroidAxis.polled] axis's flat (the deadzone the device reports); NaN where it has none. */
        val flat = FloatArray(AndroidAxis.polled.size)
        /** The right stick is on Z / RZ rather than RX / RY, for the PC overlay ([overlayAxes]). */
        var zStick = false
        /** "Press a key": each [AndroidAxis.polled] axis pushed past half way, -1 / 0 / 1. */
        val pushed = IntArray(AndroidAxis.polled.size)

        fun readRanges(device: InputDevice, source: Int) {
            val polled = AndroidAxis.polled
            for (i in polled.indices) flat[i] = device.getMotionRange(polled[i], source)?.flat ?: Float.NaN
            // Pads on Generic.kl put the right stick on RX / RY (and sometimes triggers on Z / RZ).
            // So Z / RZ are the stick only without RX / RY, or when the triggers have axes of their own.
            // The engine's PadDevice.rightStickRx uses the same rule (PhysicalSticks.rightStickOnRx).
            fun has(axis: Int) = device.getMotionRange(axis) != null
            val triggers = has(AndroidAxis.LTRIGGER) || has(AndroidAxis.RTRIGGER) || has(AndroidAxis.GAS) || has(AndroidAxis.BRAKE)
            zStick = has(AndroidAxis.Z) && has(AndroidAxis.RZ) && (!has(AndroidAxis.RX) || !has(AndroidAxis.RY) || triggers)
            rangesFor = source
        }
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
                val src = sources.valueAt(i)
                src.pad = padDevice(d)
                src.rangesFor = 0 // its axes may have changed too: read again on the next event
            }
        }
        refresh()
    }

    fun unregister() = inputManager.unregisterInputDeviceListener(this)

    /** Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual) return false
        if (isRocker(event, device)) return onRocker(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        runtime.wifi.pressStamp.note(event.eventTime * 1_000_000L) // KeyEvent has no public ns time; uptime is the nanoTime clock
        // The PC's phone overlay draws the controller's presses on its handles.
        if (isPad(device)) runtime.wifi.overlay.padKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
        // A captured mouse's back / forward buttons come as keys too: they are its buttons already.
        // A game pad that also says it is a mouse (many do) keeps its keys: they are its buttons.
        if (runtime.mouseCaptured && mouseSource(device) &&
            device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC && !isPad(device)
        ) return true
        val pressed = event.action == KeyEvent.ACTION_DOWN
        runtime.pressListener?.let { listen ->
            val target = PressTargets.ofPhoneKey(event.keyCode, isPad(device), mouse = isMouse(device))
            listen("phone:${device.id}:key:${event.keyCode}", target, pressed)
            return true
        }
        runtime.learnListener?.let { learn ->
            if (pressed) {
                runtime.learnListener = null
                val kind = kindOf(device)
                // A mouse's back and forward buttons come as keys while the pointer isn't captured.
                val code = if (kind == PhysicalDevice.MOUSE) when (event.keyCode) {
                    AndroidKey.BACK -> AndroidKey.MOUSE_BACK
                    PressTargets.KEYCODE_FORWARD -> AndroidKey.MOUSE_FORWARD
                    else -> event.keyCode
                } else event.keyCode
                learn(kind, InputSource.Key(code))
            }
            return true
        }
        // Unmapped pad buttons must do nothing, not fall back to BACK / focus navigation.
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            runtime.controllers.value.any { it.deviceId == device.id }
        // Hardware held back with the pad closed: nothing is sent, and a keyboard types into the app as usual.
        if (!runtime.hardwareAllowed) return fromPad
        val mapped = runtime.engine.setKey(source(device).pad, event.keyCode, pressed)
        // Pad closed: a keyboard's keys are sent and still type into the app (its search and name boxes).
        if (!runtime.inputAllowed) return fromPad
        return mapped || fromPad
    }

    /** Whether [event] is the phone's own volume rocker (a built-in key device, not an attached pad or keyboard). */
    private fun isRocker(event: KeyEvent, device: InputDevice): Boolean =
        (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            !device.isExternal && !isPad(device) && device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC

    /**
     * The volume rocker while the pad is open (or with the pad closed while hardware devices aren't held
     * back, [GamepadRuntime.padOnlyHardware] off): a key of the phone's own device ([PHONE_PAD]),
     * mapped by the workspace's phone mappings (unmapped, the PC's volume keys). Consumed, so the
     * phone's volume doesn't change or show its slider. Otherwise, or while learning a key, it is the
     * phone's volume as usual. Android's repeats of a held key are
     * swallowed: the key stays held, as any button.
     */
    private fun onRocker(event: KeyEvent): Boolean {
        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (!runtime.hardwareAllowed || runtime.learnListener != null || runtime.pressListener != null) {
            // A press taken while the pad was open still gets its release.
            if (!pressed) runtime.engine.setKey(PHONE_PAD, event.keyCode, false)
            return false
        }
        if (pressed && event.repeatCount > 0) return true
        runtime.wifi.pressStamp.note(event.eventTime * 1_000_000L)
        runtime.engine.setKey(PHONE_PAD, event.keyCode, pressed)
        return true
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && (runtime.pressListener != null || runtime.learnListener != null)) {
            return onListenMouse(event)
        }
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Focus left the window (shade, dialog, split screen): the stick's return to rest goes
            // to another window, so let go of this device's axes. Its keys get their own cancel.
            if (runtime.learnListener == null) runtime.engine.clearAxes(event.deviceId)
            runtime.wifi.overlay.clearPad()
            return true
        }
        if (event.action != MotionEvent.ACTION_MOVE) return false
        // The device itself only the first time (and after it changes): its ranges are kept in its Source.
        val src = sources.get(event.deviceId) ?: source(event.device ?: return false)
        if (src.rangesFor != event.source) src.readRanges(event.device ?: return false, event.source)
        val learn = runtime.learnListener
        val listen = runtime.pressListener
        val listening: Any? = learn ?: listen
        if (listening !== src.learnFor) {
            src.learnFor = listening
            src.last.copyInto(src.rest)
            src.pushed.fill(0)
        }
        val polled = AndroidAxis.polled
        var n = 0
        var learnedAxis = -1
        var learned: AxisHalf? = null
        for (i in polled.indices) {
            val axis = polled[i]
            val flat = src.flat[i]
            if (flat.isNaN()) continue
            val v = AndroidAxis.deadzone(axis, event.getAxisValue(axis), flat)
            src.last[i] = v
            if (listen != null && learn == null) {
                if (!src.seen) { src.rest[i] = v; continue }
                // Past half way from where it rested: a press of that way (a trigger resting at -1 counts from there).
                val moved = v - src.rest[i]
                val way = if (abs(moved) <= PRESS_TRAVEL) 0 else if (moved > 0) 1 else -1
                val was = src.pushed[i]
                if (way != was) {
                    src.pushed[i] = way
                    if (was != 0) listen("phone:${event.deviceId}:axis:$axis:$was", null, false)
                    if (way != 0) listen("phone:${event.deviceId}:axis:$axis:$way", PressTargets.ofPhoneAxis(axis, way > 0, src.zStick), true)
                }
                continue
            }
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
        if (runtime.wifi.overlay.wanted) overlayAxes(src.zStick, src.last)
        if (learn != null) {
            if (learned != null) {
                runtime.learnListener = null
                src.learnFor = null
                learn(PhysicalDevice.CONTROLLER, InputSource.Axis(learnedAxis, learned))
            }
            return true
        }
        if (listen != null) return true
        // Hardware held back with the pad closed: nothing is sent ([last] still follows the device for learning).
        if (runtime.hardwareAllowed) runtime.engine.setAxes(src.pad, axisIds, axisValues, n)
        return true
    }

    /**
     * A mouse's buttons and wheel while "Press a key" or "Press a button" listens (the pointer isn't
     * captured then): to [GamepadRuntime.pressListener] as the mouse's, or to the learn listener as
     * the mouse's keys. Movement is left alone. Returns true if consumed.
     */
    private fun onListenMouse(event: MotionEvent): Boolean {
        val listen = runtime.pressListener
        val learn = runtime.learnListener
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val (button, key) = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> MouseButton.LEFT to AndroidKey.MOUSE_LEFT
                    MotionEvent.BUTTON_SECONDARY -> MouseButton.RIGHT to AndroidKey.MOUSE_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> MouseButton.MIDDLE to AndroidKey.MOUSE_MIDDLE
                    MotionEvent.BUTTON_BACK -> MouseButton.BACK to AndroidKey.MOUSE_BACK
                    MotionEvent.BUTTON_FORWARD -> MouseButton.FORWARD to AndroidKey.MOUSE_FORWARD
                    else -> return false
                }
                val down = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                if (listen != null) {
                    listen("phone:${event.deviceId}:mouse:${button.bit}", OutputTarget.Mouse(button), down)
                } else if (learn != null && down) {
                    runtime.learnListener = null
                    learn(PhysicalDevice.MOUSE, InputSource.Key(key))
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v == 0f) return false
                val up = v > 0f
                if (listen != null) {
                    // A notch is a press and a release at once.
                    val id = "phone:${event.deviceId}:wheel:$up"
                    listen(id, PressTargets.ofWheel(up), true)
                    listen(id, null, false)
                } else if (learn != null) {
                    runtime.learnListener = null
                    learn(PhysicalDevice.MOUSE, InputSource.Key(if (up) AndroidKey.MOUSE_WHEEL_UP else AndroidKey.MOUSE_WHEEL_DOWN))
                }
                return true
            }
        }
        return false
    }

    /** Which of the device presets' views [d] belongs in: a mouse, a full keyboard, or a controller. */
    private fun kindOf(d: InputDevice): PhysicalDevice = when {
        isMouse(d) -> PhysicalDevice.MOUSE
        d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && d.sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD ->
            PhysicalDevice.KEYBOARD
        else -> PhysicalDevice.CONTROLLER
    }

    /** A mouse (not a game pad that also says it is one, nor a keyboard with a touchpad). */
    private fun isMouse(d: InputDevice): Boolean =
        mouseSource(d) && !isPad(d) && d.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC

    /**
     * [d] is a mouse, captured or not: pointer capture turns a mouse's SOURCE_MOUSE into
     * SOURCE_MOUSE_RELATIVE. Counting only SOURCE_MOUSE made the capture let the mouse go, which
     * brought it back and captured it again, some 30 times a second: each switch resets the
     * device in Android's input system and delays every controller key (a pad's hidden mouse
     * interface, such as the 8BitDo Ultimate Mobile's, did it).
     */
    private fun mouseSource(d: InputDevice): Boolean =
        d.supportsSource(InputDevice.SOURCE_MOUSE) || d.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    /**
     * Sends [v] (the [AndroidAxis.polled] axes) to the PC's phone overlay, for its handles; [zStick]:
     * the right stick is on Z / RZ ([Source.zStick]).
     */
    private fun overlayAxes(zStick: Boolean, v: FloatArray) {
        val rx = if (zStick) v[POLLED_Z] else v[POLLED_RX]
        val ry = if (zStick) v[POLLED_RZ] else v[POLLED_RY]
        runtime.wifi.overlay.padAxes(
            v[POLLED_X], v[POLLED_Y], rx, ry,
            maxOf(v[POLLED_LTRIGGER], v[POLLED_BRAKE]), maxOf(v[POLLED_RTRIGGER], v[POLLED_GAS]),
            v[POLLED_HAT_X], v[POLLED_HAT_Y],
        )
    }

    /**
     * An event of the mouse the pad captured (View.requestPointerCapture while the workspace maps
     * a mouse): its buttons and wheel notches go to the engine as the mouse's keys, its movement
     * through MappingEngine.mouseMove. Returns true if consumed.
     */
    fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val pad = mice.get(device.id) ?: mousePad(device).also { mice.put(device.id, it) }
        if (!runtime.hardwareAllowed) return true
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
                runtime.wifi.pressStamp.note(event.eventTimeNanos)
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
        if (d != null) sources.get(deviceId)?.let {
            it.pad = padDevice(d)
            it.rangesFor = 0 // its axes may have changed: read again on the next event
        }
        refresh()
    }

    private fun refresh() {
        val ids = inputManager.inputDeviceIds.toList()
        val mouse = ids.any { id ->
            val d = inputManager.getInputDevice(id)
            // A game pad saying it is a mouse too is not one: it would capture the pointer and lose its keys.
            d != null && !d.isVirtual && mouseSource(d) && !isPad(d)
        }
        val list = ids.mapNotNull { id ->
            val d = inputManager.getInputDevice(id) ?: return@mapNotNull null
            if (d.isVirtual || !isPad(d)) return@mapNotNull null
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
        runtime.wifi.overlay.padAttached = list.isNotEmpty()
        runtime.onControllersChanged(list, mouse)
    }

    /** Whether [d] is a game pad or joystick. */
    private fun isPad(d: InputDevice): Boolean =
        d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

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
        rightStickRx = PhysicalSticks.rightStickOnRx { device.getMotionRange(it) != null },
    )

    /** The engine's view of a mouse: its own device (id apart from the same device's keys), with the mouse mappings. */
    private fun mousePad(device: InputDevice) =
        PadDevice(MOUSE_ID_BASE + device.id, device.vendorId, device.productId, device.name, mouse = true)

    companion object {
        /** "Press a key": how far from rest a stick or trigger is a press of that way. */
        private const val PRESS_TRAVEL = 0.5f

        /** Added to a device's id for its mouse part, so a keyboard with a touchpad keeps its keys apart. */
        private const val MOUSE_ID_BASE = 1 shl 28

        /** The phone's own keys (the volume rocker) as the engine sees them: one device, apart from every Android device id. */
        private val PHONE_PAD = PadDevice(1 shl 29, 0, 0, "Phone", phone = true)

        // Places in AndroidAxis.polled.
        private val POLLED_X = AndroidAxis.polled.indexOf(AndroidAxis.X)
        private val POLLED_Y = AndroidAxis.polled.indexOf(AndroidAxis.Y)
        private val POLLED_Z = AndroidAxis.polled.indexOf(AndroidAxis.Z)
        private val POLLED_RX = AndroidAxis.polled.indexOf(AndroidAxis.RX)
        private val POLLED_RY = AndroidAxis.polled.indexOf(AndroidAxis.RY)
        private val POLLED_RZ = AndroidAxis.polled.indexOf(AndroidAxis.RZ)
        private val POLLED_HAT_X = AndroidAxis.polled.indexOf(AndroidAxis.HAT_X)
        private val POLLED_HAT_Y = AndroidAxis.polled.indexOf(AndroidAxis.HAT_Y)
        private val POLLED_LTRIGGER = AndroidAxis.polled.indexOf(AndroidAxis.LTRIGGER)
        private val POLLED_RTRIGGER = AndroidAxis.polled.indexOf(AndroidAxis.RTRIGGER)
        private val POLLED_GAS = AndroidAxis.polled.indexOf(AndroidAxis.GAS)
        private val POLLED_BRAKE = AndroidAxis.polled.indexOf(AndroidAxis.BRAKE)

        /** Keycodes probed with InputDevice.hasKeys: every known controller key plus BUTTON_1..16. */
        private val KEY_CANDIDATES: IntArray =
            (AndroidKey.names.keys + (AndroidKey.BUTTON_1 until AndroidKey.BUTTON_1 + 16)).distinct().toIntArray()
    }
}
