package com.oleroma.gyrophone.input

import android.content.Context
import android.hardware.input.InputManager
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
import kotlin.math.abs

/**
 * Feeds key and joystick events from devices attached to the phone (GameSir X5, any XInput/HID
 * pad, a USB or Bluetooth keyboard) into the mapping engine, and tracks which controllers are
 * attached. Each event carries its device, so only pads attached to the active workspace act.
 * Android only delivers these to the foreground activity, so MainActivity forwards them.
 */
class ControllerInput(context: Context, private val runtime: GamepadRuntime) : InputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(InputManager::class.java)

    fun register() {
        inputManager.registerInputDeviceListener(this, null)
        refresh()
    }

    fun unregister() = inputManager.unregisterInputDeviceListener(this)

    /** Returns true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
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
        val mapped = runtime.engine.setKey(padDevice(device), event.keyCode, pressed)
        return mapped || fromPad
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) return false
        val device = event.device ?: return false
        val learn = runtime.learnListener
        if (learn == null && !runtime.inputAllowed) return true
        val pad = padDevice(device)
        for (axis in AndroidAxis.polled) {
            val range = device.getMotionRange(axis, event.source) ?: continue
            var v = event.getAxisValue(axis)
            if (abs(v) <= range.flat) v = 0f
            if (learn != null) {
                if (abs(v) > LEARN_THRESHOLD) {
                    runtime.learnListener = null
                    val half = when {
                        isTrigger(axis) -> AxisHalf.FULL
                        v > 0 -> AxisHalf.POSITIVE
                        else -> AxisHalf.NEGATIVE
                    }
                    learn(InputSource.Axis(axis, half))
                    return true
                }
                continue
            }
            runtime.engine.setAxis(pad, axis, v)
        }
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) {
        // Releases whatever that device held, then drops it from the attached list.
        runtime.engine.deviceRemoved(deviceId)
        refresh()
    }
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        val list = inputManager.inputDeviceIds.toList().mapNotNull { id ->
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
        runtime.onControllersChanged(list)
    }

    /**
     * The engine's view of [device]: it picks the active workspace's attached pad by model, or the
     * keyboard mappings for a full keyboard that is not also a game pad.
     */
    private fun padDevice(device: InputDevice) = PadDevice(
        device.id, device.vendorId, device.productId, device.name,
        keyboard = device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            device.sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD,
    )

    private fun isTrigger(axis: Int) = axis == AndroidAxis.LTRIGGER || axis == AndroidAxis.RTRIGGER ||
        axis == AndroidAxis.GAS || axis == AndroidAxis.BRAKE

    companion object {
        private const val LEARN_THRESHOLD = 0.6f

        /** Keycodes probed with InputDevice.hasKeys: every known controller key plus BUTTON_1..16. */
        private val KEY_CANDIDATES: IntArray =
            (AndroidKey.names.keys + (AndroidKey.BUTTON_1 until AndroidKey.BUTTON_1 + 16)).distinct().toIntArray()
    }
}
