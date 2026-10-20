package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns raw phone-side input (controller keys/axes, touch controls, gyro) into the HID state the
 * host sees, according to the active [Profile]. Thread-safe: input arrives from the UI thread and
 * the sensor thread, and the report pump reads [snapshot] from its own thread.
 */
class MappingEngine(profile: Profile) {

    private val lock = Any()

    var profile: Profile = profile
        set(value) = synchronized(lock) {
            field = value
            gyroToggled = false
            keyOrder.clear()
        }

    private val keys = HashSet<Int>()
    private val axes = HashMap<Int, Float>()
    private val touches = HashSet<String>()
    private val touchSticks = HashMap<Pair<String, StickAxis>, Float>()

    private var gyroToggled = false
    private var mouseDx = 0
    private var mouseDy = 0
    private var wheel = 0

    /** Keyboard usages in press order, so the 6-key array is stable while keys are held. */
    private val keyOrder = LinkedHashSet<Int>()

    /** Returns true if the profile maps this key, so the caller can consume the event. */
    fun setKey(keyCode: Int, pressed: Boolean): Boolean = synchronized(lock) {
        val changed = if (pressed) keys.add(keyCode) else keys.remove(keyCode)
        val mapped = profile.mappings.filter { it.source == InputSource.Key(keyCode) }
        if (changed && pressed) onPress(mapped.map { it.target })
        mapped.isNotEmpty()
    }

    fun setAxis(axis: Int, value: Float) = synchronized(lock) {
        if (abs(value) < AXIS_EPSILON) axes.remove(axis) else axes[axis] = value
    }

    fun setTouch(id: String, pressed: Boolean) = synchronized(lock) {
        val changed = if (pressed) touches.add(id) else touches.remove(id)
        if (changed && pressed) {
            onPress(profile.mappings.filter { it.source == InputSource.Touch(id) }.map { it.target })
        }
    }

    /** Touch stick deflection, each axis in -1..1 (Y positive = down, like the HID game pad). */
    fun setTouchStick(id: String, x: Float, y: Float) = synchronized(lock) {
        touchSticks[id to StickAxis.X] = x
        touchSticks[id to StickAxis.Y] = y
    }

    /** Relative mouse motion from the gyro, already in mouse counts. */
    fun addMouseMotion(dx: Int, dy: Int) = synchronized(lock) {
        mouseDx += dx
        mouseDy += dy
    }

    /** Whether gyro motion should currently move the mouse. */
    val gyroActive: Boolean
        get() = synchronized(lock) {
            when (profile.gyro.mode) {
                GyroMode.OFF -> false
                GyroMode.ALWAYS -> true
                GyroMode.HOLD_OR_TOGGLE -> gyroToggled ||
                    profile.mappings.any { it.target == OutputTarget.GyroHold && value(it.source) >= PRESS_THRESHOLD }
            }
        }

    /** Releases everything, e.g. when the profile changes or the host disconnects. */
    fun reset() = synchronized(lock) {
        keys.clear(); axes.clear(); touches.clear(); touchSticks.clear(); keyOrder.clear()
        gyroToggled = false; mouseDx = 0; mouseDy = 0; wheel = 0
    }

    /** Computes the current HID state and drains accumulated relative mouse motion. */
    fun snapshot(): HidState = synchronized(lock) {
        var buttons = 0
        var up = false; var down = false; var left = false; var right = false
        val stick = FloatArray(4)
        val trigger = FloatArray(2)
        var mouseButtons = 0
        val heldKeys = HashSet<Int>()

        for (m in profile.mappings) {
            val v = value(m.source)
            when (val t = m.target) {
                is OutputTarget.Pad -> if (v >= PRESS_THRESHOLD) when (t.button) {
                    GamepadButton.DPAD_UP -> up = true
                    GamepadButton.DPAD_DOWN -> down = true
                    GamepadButton.DPAD_LEFT -> left = true
                    GamepadButton.DPAD_RIGHT -> right = true
                    else -> buttons = buttons or (1 shl t.button.bit)
                }
                is OutputTarget.PadAxis -> {
                    val scaled = v * t.scale
                    when (t.axis) {
                        GamepadAxis.LT -> trigger[0] = max(trigger[0], abs(scaled))
                        GamepadAxis.RT -> trigger[1] = max(trigger[1], abs(scaled))
                        else -> stick[t.axis.ordinal] += scaled
                    }
                }
                is OutputTarget.Key -> if (v >= PRESS_THRESHOLD) heldKeys.add(t.usage)
                is OutputTarget.Mouse -> if (v >= PRESS_THRESHOLD) mouseButtons = mouseButtons or (1 shl t.button.bit)
                is OutputTarget.Wheel, OutputTarget.GyroHold, OutputTarget.GyroToggle -> Unit
            }
        }

        keyOrder.retainAll(heldKeys)
        keyOrder.addAll(heldKeys)
        var modifiers = 0
        val array = ArrayList<Int>(6)
        for (usage in keyOrder) {
            if (HidUsage.isModifier(usage)) modifiers = modifiers or HidUsage.modifierBit(usage)
            else if (array.size < 6) array.add(usage)
        }

        val gamepad = GamepadState(
            buttons = buttons,
            hat = Hat.fromDpad(up, down, left, right),
            lx = toStick(stick[0]), ly = toStick(stick[1]),
            rx = toStick(stick[2]), ry = toStick(stick[3]),
            lt = toTrigger(trigger[0]), rt = toTrigger(trigger[1]),
        )
        val mouse = MouseState(mouseButtons, mouseDx, mouseDy, wheel)
        mouseDx = 0; mouseDy = 0; wheel = 0
        HidState(gamepad, KeyboardState(modifiers, array), mouse)
    }

    private fun onPress(targets: List<OutputTarget>) {
        for (t in targets) when (t) {
            OutputTarget.GyroToggle -> gyroToggled = !gyroToggled
            is OutputTarget.Wheel -> wheel += t.step
            else -> Unit
        }
    }

    /** Current value of a source: digital 0/1, analog -1..1 (or 0..1 for a half). */
    private fun value(source: InputSource): Float = when (source) {
        is InputSource.Key -> if (source.keyCode in keys) 1f else 0f
        is InputSource.Touch -> if (source.id in touches) 1f else 0f
        is InputSource.Axis -> half(axes[source.axis] ?: 0f, source.half)
        is InputSource.TouchStick -> half(touchSticks[source.id to source.axis] ?: 0f, source.half)
    }

    private fun half(v: Float, half: AxisHalf): Float = when (half) {
        AxisHalf.FULL -> v
        AxisHalf.POSITIVE -> max(v, 0f)
        AxisHalf.NEGATIVE -> max(-v, 0f)
    }

    private fun toStick(v: Float): Int = (v.coerceIn(-1f, 1f) * GamepadState.STICK_MAX).toInt()
    private fun toTrigger(v: Float): Int = (v.coerceIn(0f, 1f) * GamepadState.TRIGGER_MAX).toInt()

    companion object {
        const val PRESS_THRESHOLD = 0.5f
        private const val AXIS_EPSILON = 1e-4f
    }
}
