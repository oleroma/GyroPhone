package com.oleroma.gyrophone.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.runtime.AttachedController
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs

/**
 * Feeds key and joystick events from devices attached to the phone (GameSir X5, any XInput/HID
 * pad, a USB or Bluetooth keyboard) into the mapping engine, and tracks which controllers are
 * attached. Android only delivers these to the foreground activity, so MainActivity forwards them.
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
        runtime.learnListener?.let { learn ->
            if (pressed) { runtime.learnListener = null; learn(InputSource.Key(event.keyCode)) }
            return true
        }
        return runtime.engine.setKey(event.keyCode, pressed)
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) return false
        val device = event.device ?: return false
        val learn = runtime.learnListener
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
            runtime.engine.setAxis(axis, v)
        }
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        val list = inputManager.inputDeviceIds.toList().mapNotNull { id ->
            val d = inputManager.getInputDevice(id) ?: return@mapNotNull null
            val isPad = d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (d.isVirtual || !isPad) return@mapNotNull null
            AttachedController(d.id, d.name, d.vendorId, d.productId, KnownLayouts.identify(d.vendorId, d.productId, d.name))
        }
        runtime.onControllersChanged(list)
    }

    private fun isTrigger(axis: Int) = axis == AndroidAxis.LTRIGGER || axis == AndroidAxis.RTRIGGER ||
        axis == AndroidAxis.GAS || axis == AndroidAxis.BRAKE

    companion object {
        private const val LEARN_THRESHOLD = 0.6f
    }
}
