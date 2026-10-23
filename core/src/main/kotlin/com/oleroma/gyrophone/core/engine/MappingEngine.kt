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
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw phone-side input (controller keys/axes, touch controls, gyro) into the HID state the
 * host sees, according to the active [Workspace] of [profile]. The pad screen's empty background
 * feeds a [Touchpad] whose cursor motion, wheel and clicks are added to the mouse output whatever
 * the gyro mode. Thread-safe: input arrives from the UI thread and the sensor thread, and the
 * report pump reads [snapshot] from its own thread. [clock] (nanoseconds) times touchpad gestures
 * and touch buttons.
 *
 * Input is evaluated in source groups that merge into one PC output: the workspace's touch
 * mappings over the touch state, each connected device over the mappings of the attached pad it
 * matches (a device no pad matches does nothing), and each keyboard over the keyboard mappings.
 * Buttons, keys and mouse buttons OR together; a stick axis takes the group with the largest
 * deflection, a trigger the largest value. The legacy [setKey] / [setAxis] overloads without a
 * device are one "any device" source evaluated against every pad of the workspace.
 */
class MappingEngine(profile: Profile, private val clock: () -> Long = System::nanoTime) {

    private val lock = Any()

    private var current: Profile = profile

    var profile: Profile
        get() = current
        set(value) {
            synchronized(lock) {
                current = value
                applyWorkspace()
            }
            onChange?.run()
        }

    /** Index of the active workspace in [profile]; out-of-range values are clamped. */
    var workspaceIndex: Int = 0
        set(value) {
            synchronized(lock) {
                field = value
                applyWorkspace()
            }
            onChange?.run()
        }

    /** The active workspace of [profile]. */
    val workspace: Workspace get() = synchronized(lock) { ws }

    private var ws: Workspace = profile.workspace(0)
    /** Touch mappings of the active workspace (the touch source group). */
    private var touchMappings: List<Mapping> = emptyList()
    /** Mappings of each attached pad of the active workspace, by pad index, then the keyboard's. */
    private var padMappings: Array<List<Mapping>> = emptyArray()
    /** Touch controls whose mappings are all keyboard modifiers, when sticky modifiers are on. */
    private var modifierIds: Set<String> = emptySet()

    /** Input state of one device: its keys, its axes and the pad of the workspace it acts as. */
    private class Dev(var device: PadDevice?) {
        /** Index into [padMappings]; -1 = not attached here (the device does nothing). */
        var pad = -1
        val keys = BitSet()
        val axes = FloatArray(AXIS_SLOTS)
        private var axisCount = 0

        val active: Boolean get() = axisCount > 0 || !keys.isEmpty

        fun key(code: Int): Boolean = code >= 0 && keys.get(code)
        fun axis(a: Int): Float = if (a in 0 until AXIS_SLOTS) axes[a] else 0f

        fun setKey(code: Int, pressed: Boolean) {
            if (code >= 0) keys.set(code, pressed)
        }

        fun setAxis(a: Int, value: Float) {
            if (a !in 0 until AXIS_SLOTS) return
            val v = if (abs(value) < AXIS_EPSILON) 0f else value
            if (axes[a] == 0f && v != 0f) axisCount++ else if (axes[a] != 0f && v == 0f) axisCount--
            axes[a] = v
        }

        fun clear() {
            keys.clear(); axes.fill(0f); axisCount = 0
        }
    }

    private val devices = HashMap<Int, Dev>()
    /** Same as [devices]' values, for allocation-free iteration. */
    private val deviceList = ArrayList<Dev>()
    /** The legacy "any device" source: acts on every pad of the workspace. */
    private val anyDevice = Dev(null)

    /** Pressed touch controls with a press count (setTouch, button fingers, tap pulses). */
    private val touches = HashMap<String, Int>()
    /** Controls held through [setTouch], with their press time. */
    private val setTouchHeld = HashMap<String, Long>()
    private val touchSticks = HashMap<String, FloatArray>()

    // Fingers on touch buttons (see buttonDown), fixed slots so pointer events allocate little.
    private val bpPointer = LongArray(MAX_BUTTON_FINGERS)
    private val bpControl = arrayOfNulls<String>(MAX_BUTTON_FINGERS)
    private val bpState = IntArray(MAX_BUTTON_FINGERS)
    private val bpDownX = FloatArray(MAX_BUTTON_FINGERS)
    private val bpDownY = FloatArray(MAX_BUTTON_FINGERS)
    private val bpDownNs = LongArray(MAX_BUTTON_FINGERS)
    /** Pressed at once on touch-down (touchpadOverButtons off), so a quick release is a tap. */
    private val bpImmediate = BooleanArray(MAX_BUTTON_FINGERS)
    /** Click-through touch buttons of the workspace: pressed and driving the touchpad at once. */
    private var clickThroughIds: Set<String> = emptySet()

    /** A still tap committed on lift: pressed until a snapshot has shown it for the minimum pulse. */
    private class Pulse(val id: String, val startNs: Long) { var seen = false }
    private val pulses = ArrayList<Pulse>()

    /** Sticky-modifier state of a modifier control. */
    private class Latch { var state = LATCH_NONE; var tapNs = 0L }
    private val latches = HashMap<String, Latch>()
    /** Modifier controls held while a non-modifier key was pressed (a chord, not a tap). */
    private val chorded = HashSet<String>()

    private var gyroToggled = false
    private var mouseDx = 0
    private var mouseDy = 0
    /** Wheel from bindings, in 1/120 notch. */
    private var wheel = 0
    private val touchpad = Touchpad(profile.workspace(0).touchpad)

    // Snapshot scratch, reused so the 1000 Hz pump allocates little.
    private val stick = FloatArray(4)
    private val groupStick = FloatArray(4)
    private val trigger = FloatArray(2)
    private var accButtons = 0
    private var accUp = false; private var accDown = false; private var accLeft = false; private var accRight = false
    private var accMouse = 0
    private val heldKeys = BooleanArray(256)
    /** Keyboard usages in press order, so the 6-key array is stable while keys are held. */
    private val keyOrder = IntArray(256)
    private val inKeyOrder = BooleanArray(256)
    private var keyOrderSize = 0

    private val eventsBefore = HashSet<OutputTarget>()
    private val eventsAfter = HashSet<OutputTarget>()

    init { applyWorkspace() }

    /**
     * Called after every input mutation, outside the engine lock, so a transport can wake its
     * sender without polling. The callback may call [snapshot].
     */
    @Volatile var onChange: Runnable? = null

    /** Loads the active workspace and releases everything held. Under the lock. */
    private fun applyWorkspace() {
        val w = profile.workspace(workspaceIndex)
        ws = w
        touchMappings = w.mappings
        padMappings = Array(w.pads.size + 1) { if (it < w.pads.size) w.pads[it].mappings else w.keyboard }
        modifierIds = if (w.stickyModifiers) modifierControls(w.mappings) else emptySet()
        clickThroughIds = w.touchControls.filter { it.kind == TouchKind.BUTTON && it.clickThrough }.mapTo(HashSet()) { it.id }
        releaseHeld()
        for (d in deviceList) d.pad = resolvePad(d.device)
        touchpad.settings = w.touchpad
        touchpad.cancel(clock())
    }

    /** Forgets every held input (keys, axes, touches, sticks, fingers, pulses, latches). */
    private fun releaseHeld() {
        for (d in deviceList) d.clear()
        anyDevice.clear()
        touches.clear(); setTouchHeld.clear(); touchSticks.clear()
        bpState.fill(BP_FREE); bpControl.fill(null)
        pulses.clear(); latches.clear(); chorded.clear()
        gyroToggled = false
        keyOrderSize = 0; inKeyOrder.fill(false)
    }

    private fun resolvePad(device: PadDevice?): Int {
        if (device == null) return -1
        if (device.keyboard) return ws.pads.size
        return ws.pads.indexOfFirst { it.matches(device.vendorId, device.productId, device.name) }
    }

    private fun deviceFor(device: PadDevice): Dev {
        val d = devices[device.id]
        if (d == null) {
            val n = Dev(device)
            n.pad = resolvePad(device)
            devices[device.id] = n
            deviceList.add(n)
            return n
        }
        if (d.device != device) {
            // Another device under the same id: what the old one held does not carry over.
            d.clear()
            d.device = device
            d.pad = resolvePad(device)
        }
        return d
    }

    /**
     * A key of a physical device. Only the active workspace's [com.oleroma.gyrophone.core.model.AttachedPad]
     * matching [device] (Workspace.padFor) maps it; an unattached device does nothing. Returns true
     * when that pad maps the key, so the caller can consume the event.
     */
    fun setKey(device: PadDevice, keyCode: Int, pressed: Boolean): Boolean {
        val mapped = synchronized(lock) {
            var pad = -1
            firingEvents(clock()) {
                val d = deviceFor(device)
                d.setKey(keyCode, pressed)
                pad = d.pad
            }
            pad >= 0 && padMappings[pad].any { it.source == InputSource.Key(keyCode) }
        }
        onChange?.run()
        return mapped
    }

    /** An axis of a physical device; routed like [setKey]. */
    fun setAxis(device: PadDevice, axis: Int, value: Float) {
        synchronized(lock) {
            firingEvents(clock()) { deviceFor(device).setAxis(axis, value) }
        }
        onChange?.run()
    }

    /** Releases everything a device holds (it was disconnected). */
    fun deviceRemoved(deviceId: Int) {
        synchronized(lock) {
            firingEvents(clock()) {
                val d = devices.remove(deviceId)
                if (d != null) deviceList.remove(d)
            }
        }
        onChange?.run()
    }

    /**
     * A finger landed on touch control [id] (BUTTON kind) at [x], [y] px of the pad area. With
     * the workspace's touchpadOverButtons off it presses at once; on, it moves the cursor at once
     * and presses the button only as a tap (a still lift within the workspace's buttonTapMs). A
     * click-through control presses at once and its finger also moves the cursor until it lifts.
     * [pointer] is the finger id.
     */
    fun buttonDown(pointer: Long, id: String, x: Float, y: Float) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                if (slotOf(pointer) >= 0) return@firingEvents
                val slot = bpState.indexOf(BP_FREE)
                if (slot < 0) return@firingEvents
                bpPointer[slot] = pointer; bpControl[slot] = id
                bpDownX[slot] = x; bpDownY[slot] = y; bpDownNs[slot] = now
                bpImmediate[slot] = id in clickThroughIds || !ws.touchpadOverButtons
                if (id in clickThroughIds) {
                    bpState[slot] = BP_THROUGH
                    press(id)
                    touchpad.down(pointer, x, y, now, aiming = true)
                } else if (bpImmediate[slot]) {
                    bpState[slot] = BP_PRESSED
                    press(id)
                } else {
                    // Touchpad over buttons: a cursor finger at once, a key only if it taps.
                    bpState[slot] = BP_PENDING
                    touchpad.down(pointer, x, y, now, aiming = true)
                }
            }
        }
        onChange?.run()
    }

    /** A finger that went down through [buttonDown] moved (px of the pad area). */
    fun buttonMove(pointer: Long, x: Float, y: Float) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                val slot = slotOf(pointer)
                if (slot < 0) return@firingEvents
                when (bpState[slot]) {
                    BP_PENDING -> {
                        touchpad.move(pointer, x, y, now)
                        val ddx = x - bpDownX[slot]; val ddy = y - bpDownY[slot]
                        val slop = BUTTON_SLOP_DP * touchpad.pxPerDp
                        // Moved too far to be a tap: only a touchpad finger now.
                        if (ddx * ddx + ddy * ddy > slop * slop) bpState[slot] = BP_TOUCHPAD
                    }
                    BP_TOUCHPAD, BP_THROUGH -> touchpad.move(pointer, x, y, now)
                    else -> Unit
                }
            }
        }
        onChange?.run()
    }

    /** A finger that went down through [buttonDown] lifted. */
    fun buttonUp(pointer: Long) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                val slot = slotOf(pointer)
                if (slot < 0) return@firingEvents
                val id = bpControl[slot]!!
                when (bpState[slot]) {
                    // Still and short: the tap commits now. Longer is only a touchpad touch.
                    BP_PENDING -> {
                        touchpad.up(pointer, now)
                        if (now - bpDownNs[slot] <= ws.buttonTapMs * 1_000_000L) {
                            if (id in modifierIds) modifierTap(id, now) else {
                                pulses.add(Pulse(id, now))
                                press(id)
                            }
                        }
                    }
                    BP_PRESSED, BP_THROUGH -> {
                        if (bpState[slot] == BP_THROUGH) touchpad.up(pointer, now)
                        release(id)
                        if (id in modifierIds) modifierUp(id, bpImmediate[slot] && now - bpDownNs[slot] < BUTTON_HOLD_NS, now)
                    }
                    BP_TOUCHPAD -> touchpad.up(pointer, now)
                }
                bpState[slot] = BP_FREE; bpControl[slot] = null
            }
        }
        onChange?.run()
    }

    /** Touch controls whose modifier is latched or locked (sticky modifiers), for highlighting. */
    fun latchedTouches(): Set<String> = synchronized(lock) {
        val out = HashSet<String>()
        for ((id, l) in latches) if (l.state != LATCH_NONE) out.add(id)
        out
    }

    /** Touch controls currently pressed (including by touchpad-over-buttons commits), for highlighting. */
    fun pressedTouches(): Set<String> = synchronized(lock) {
        resolveIfDue(clock())
        HashSet(touches.keys)
    }

    /**
     * A key from the "any device" source, evaluated against every pad of the workspace. Returns
     * true if one of them maps it. App code uses the [PadDevice] overload.
     */
    fun setKey(keyCode: Int, pressed: Boolean): Boolean {
        val mapped = synchronized(lock) {
            firingEvents(clock()) { anyDevice.setKey(keyCode, pressed) }
            val src = InputSource.Key(keyCode)
            padMappings.any { ms -> ms.any { it.source == src } }
        }
        onChange?.run()
        return mapped
    }

    /** An axis from the "any device" source; see [setKey]. */
    fun setAxis(axis: Int, value: Float) {
        synchronized(lock) {
            firingEvents(clock()) { anyDevice.setAxis(axis, value) }
        }
        onChange?.run()
    }

    /** A touch control pressed or released directly (no tap/hold handling; buttons use [buttonDown]). */
    fun setTouch(id: String, pressed: Boolean) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                if (pressed) {
                    if (setTouchHeld.put(id, now) == null) press(id)
                } else {
                    val t = setTouchHeld.remove(id) ?: return@firingEvents
                    release(id)
                    if (id in modifierIds) modifierUp(id, now - t < BUTTON_HOLD_NS, now)
                }
            }
        }
        onChange?.run()
    }

    /** Touch stick deflection, each axis in -1..1 (Y positive = down, like the HID game pad). */
    fun setTouchStick(id: String, x: Float, y: Float) {
        synchronized(lock) {
            firingEvents(clock()) {
                val v = touchSticks.getOrPut(id) { FloatArray(2) }
                v[0] = x; v[1] = y
            }
        }
        onChange?.run()
    }

    /** Relative mouse motion from the gyro, already in mouse counts (touchpad motion has its own path). */
    fun addMouseMotion(dx: Int, dy: Int) {
        synchronized(lock) {
            mouseDx += dx
            mouseDy += dy
        }
        onChange?.run()
    }

    /** Background-of-the-pad touchpad finger, position in px of the pad area. */
    fun touchpadDown(pointer: Long, x: Float, y: Float) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.down(pointer, x, y, now)
        }
        onChange?.run()
    }

    fun touchpadMove(pointer: Long, x: Float, y: Float) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.move(pointer, x, y, now)
        }
        onChange?.run()
    }

    fun touchpadUp(pointer: Long) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.up(pointer, now)
        }
        onChange?.run()
    }

    /** Ends any touchpad gesture (pad left, rotated, profile changed). */
    fun touchpadCancel() {
        synchronized(lock) {
            // Button fingers that became touchpad fingers are gone with the gesture.
            for (i in 0 until MAX_BUTTON_FINGERS) {
                if (bpState[i] == BP_TOUCHPAD || bpState[i] == BP_PENDING) { bpState[i] = BP_FREE; bpControl[i] = null }
            }
            // Click-through fingers keep their button; only the cursor part ends.
            for (i in 0 until MAX_BUTTON_FINGERS) if (bpState[i] == BP_THROUGH) bpState[i] = BP_PRESSED
            touchpad.cancel(clock())
        }
        onChange?.run()
    }

    /** px per dp of the touch surface. */
    var touchpadPxPerDp: Float
        get() = synchronized(lock) { touchpad.pxPerDp }
        set(value) { synchronized(lock) { touchpad.pxPerDp = value } }

    /** Shortest visible click press/release; see [Touchpad.minPulseFor]. Also times button taps. */
    var touchpadMinPulseNanos: Long
        get() = synchronized(lock) { touchpad.minPulseNanos }
        set(value) { synchronized(lock) { touchpad.minPulseNanos = value } }

    /**
     * When the engine next needs a snapshot without new input (Long.MAX_VALUE = never): touchpad
     * timers, a tap's release.
     */
    fun touchpadDeadlineNanos(): Long = synchronized(lock) {
        var d = touchpad.nextDeadline()
        for (i in pulses.indices) d = min(d, pulses[i].startNs + touchpad.minPulseNanos)
        d
    }

    /**
     * Whether gyro motion should currently move the mouse. A ratchet (a held [OutputTarget.GyroPause]
     * source, or a touchpad finger with [com.oleroma.gyrophone.core.model.GyroSettings.touchpadRatchet])
     * flips always on / always off while held, and pauses hold / toggle. A [OutputTarget.GyroToggle]
     * press flips always on / always off until the workspace changes.
     */
    val gyroActive: Boolean
        get() = synchronized(lock) {
            val g = ws.gyro
            val ratchet = (g.touchpadRatchet && touchpad.fingers > 0) || engaged(OutputTarget.GyroPause)
            when (g.mode) {
                GyroMode.OFF -> gyroToggled != ratchet
                GyroMode.ALWAYS -> gyroToggled == ratchet
                GyroMode.HOLD_OR_TOGGLE -> !ratchet && (gyroToggled || engaged(OutputTarget.GyroHold))
            }
        }

    /**
     * Replaces the active workspace's gyro settings (the gyro corner switching its mode) without
     * releasing what is held. A toggle press made before no longer flips the new mode.
     */
    fun setGyro(settings: GyroSettings) {
        synchronized(lock) {
            if (settings == ws.gyro) return
            ws = ws.copy(gyro = settings)
            current = current.updateWorkspace(workspaceIndex) { it.copy(gyro = settings) }
            gyroToggled = false
        }
        onChange?.run()
    }

    /** Releases everything, e.g. when the profile changes or the host disconnects. */
    fun reset() {
        synchronized(lock) {
            releaseHeld()
            mouseDx = 0; mouseDy = 0; wheel = 0
            touchpad.reset()
        }
        onChange?.run()
    }

    /** Computes the current HID state and drains accumulated relative mouse motion. */
    fun snapshot(): HidState = synchronized(lock) {
        val now = clock()
        resolveIfDue(now)

        accButtons = 0; accMouse = 0
        accUp = false; accDown = false; accLeft = false; accRight = false
        stick.fill(0f); trigger.fill(0f); heldKeys.fill(false)
        forEachGroup { ms, dev -> accumulate(ms, dev) }
        for (i in pulses.indices) pulses[i].seen = true

        // Touchpad gestures (Alt+Tab, Ctrl zoom, Win shortcuts) hold keys like any binding.
        val padButtons = touchpad.poll(now)
        if (touchpad.key != 0) heldKeys[touchpad.key and 0xFF] = true
        var n = 0
        for (i in 0 until keyOrderSize) {
            val u = keyOrder[i]
            if (heldKeys[u]) keyOrder[n++] = u else inKeyOrder[u] = false
        }
        keyOrderSize = n
        for (u in 0 until 256) if (heldKeys[u] && !inKeyOrder[u]) { keyOrder[keyOrderSize++] = u; inKeyOrder[u] = true }
        var modifiers = 0
        val array = ArrayList<Int>(6)
        for (i in 0 until keyOrderSize) {
            val usage = keyOrder[i]
            if (HidUsage.isModifier(usage)) modifiers = modifiers or HidUsage.modifierBit(usage)
            else if (array.size < 6) array.add(usage)
        }
        modifiers = modifiers or touchpad.keyModifiers

        val gamepad = GamepadState(
            buttons = accButtons,
            hat = Hat.fromDpad(accUp, accDown, accLeft, accRight),
            lx = toStick(stick[0]), ly = toStick(stick[1]),
            rx = toStick(stick[2]), ry = toStick(stick[3]),
            lt = toTrigger(trigger[0]), rt = toTrigger(trigger[1]),
        )
        // Touchpad output is never gated by gyroActive.
        val mouse = MouseState(
            accMouse or padButtons,
            mouseDx + touchpad.dx, mouseDy + touchpad.dy, wheel + touchpad.wheel, touchpad.hwheel,
        )
        mouseDx = 0; mouseDy = 0; wheel = 0
        touchpad.drainMotion()
        HidState(gamepad, KeyboardState(modifiers, array), mouse)
    }

    /** Adds one source group's outputs; its stick axes sum, then merge by largest deflection. */
    private fun accumulate(ms: List<Mapping>, dev: Dev?) {
        groupStick.fill(0f)
        for (i in ms.indices) {
            val m = ms[i]
            val v = value(m.source, dev)
            when (val t = m.target) {
                is OutputTarget.Pad -> if (v >= PRESS_THRESHOLD) when (t.button) {
                    GamepadButton.DPAD_UP -> accUp = true
                    GamepadButton.DPAD_DOWN -> accDown = true
                    GamepadButton.DPAD_LEFT -> accLeft = true
                    GamepadButton.DPAD_RIGHT -> accRight = true
                    else -> accButtons = accButtons or (1 shl t.button.bit)
                }
                is OutputTarget.PadAxis -> {
                    val scaled = v * t.scale
                    when (t.axis) {
                        GamepadAxis.LT -> trigger[0] = max(trigger[0], abs(scaled))
                        GamepadAxis.RT -> trigger[1] = max(trigger[1], abs(scaled))
                        else -> groupStick[t.axis.ordinal] += scaled
                    }
                }
                is OutputTarget.Key -> if (v >= PRESS_THRESHOLD && t.usage in 0..255) heldKeys[t.usage] = true
                is OutputTarget.Mouse -> if (v >= PRESS_THRESHOLD) accMouse = accMouse or (1 shl t.button.bit)
                is OutputTarget.Wheel, OutputTarget.GyroHold, OutputTarget.GyroToggle, OutputTarget.GyroPause -> Unit
            }
        }
        for (a in 0 until 4) if (abs(groupStick[a]) > abs(stick[a])) stick[a] = groupStick[a]
    }

    /**
     * Runs [block] for each source group: the touch mappings over the touch state, each attached
     * device over its pad's mappings, and the "any device" source over every pad.
     */
    private inline fun forEachGroup(block: (List<Mapping>, Dev?) -> Unit) {
        block(touchMappings, null)
        for (i in deviceList.indices) {
            val d = deviceList[i]
            if (d.pad >= 0 && d.active) block(padMappings[d.pad], d)
        }
        if (anyDevice.active) for (ms in padMappings) block(ms, anyDevice)
    }

    /** Whether some group holds a source mapped to [target]. */
    private fun engaged(target: OutputTarget): Boolean {
        var hit = false
        forEachGroup { ms, dev ->
            if (!hit) for (i in ms.indices) {
                val m = ms[i]
                if (m.target == target && value(m.source, dev) >= PRESS_THRESHOLD) { hit = true; break }
            }
        }
        return hit
    }

    /**
     * Applies an input change and fires event-style targets (wheel steps, gyro toggle) that it
     * newly engages. A target fires once per engagement however many held sources (of any group)
     * map to it, so an input written to several sources (trigger axis + key) cannot double-fire.
     * Due button holds and tap releases are resolved first.
     */
    private inline fun firingEvents(now: Long, change: () -> Unit) {
        engagedEvents(eventsBefore)
        resolveButtons(now)
        change()
        engagedEvents(eventsAfter)
        for (t in eventsAfter) if (t !in eventsBefore) onPress(t)
    }

    /** Resolves due holds and tap releases outside an input change (snapshot, touchpad input). */
    private fun resolveIfDue(now: Long) {
        if (buttonsDue(now)) firingEvents(now) {}
    }

    private fun buttonsDue(now: Long): Boolean {
        for (i in pulses.indices) if (pulseDone(pulses[i], now)) return true
        return false
    }

    private fun pulseDone(p: Pulse, now: Long) = p.seen && now - p.startNs >= touchpad.minPulseNanos

    /** Shown taps release. */
    private fun resolveButtons(now: Long) {
        var i = 0
        while (i < pulses.size) {
            val p = pulses[i]
            if (pulseDone(p, now)) { pulses.removeAt(i); release(p.id) } else i++
        }
    }

    private fun engagedEvents(into: HashSet<OutputTarget>) {
        into.clear()
        forEachGroup { ms, dev ->
            for (i in ms.indices) {
                val m = ms[i]
                val t = m.target
                if ((t is OutputTarget.Wheel || t == OutputTarget.GyroToggle) && value(m.source, dev) >= PRESS_THRESHOLD) into.add(t)
            }
        }
    }

    private fun onPress(t: OutputTarget) {
        when (t) {
            OutputTarget.GyroToggle -> gyroToggled = !gyroToggled
            is OutputTarget.Wheel -> wheel += t.step * MouseState.WHEEL_UNIT
            else -> Unit
        }
    }

    // ---- Touch presses and sticky modifiers ----

    private fun press(id: String) {
        val n = touches[id] ?: 0
        touches[id] = n + 1
        if (n == 0 && modifierIds.isNotEmpty() && id !in modifierIds) nonModifierPressed()
    }

    private fun release(id: String) {
        val n = touches[id] ?: return
        if (n > 1) { touches[id] = n - 1; return }
        touches.remove(id)
        if (modifierIds.isNotEmpty() && id !in modifierIds) nonModifierReleased()
    }

    /** A latched modifier now applies to this key; held modifiers are being used as a chord. */
    private fun nonModifierPressed() {
        for (l in latches.values) if (l.state == LATCH_ON) l.state = LATCH_USED
        // A modifier finger that could still be a tap is part of this chord: held now, and still
        // moving the cursor.
        for (i in 0 until MAX_BUTTON_FINGERS) {
            val c = bpControl[i]
            if (bpState[i] == BP_PENDING && c != null && c in modifierIds) {
                bpState[i] = BP_THROUGH
                press(c)
            }
        }
        for (m in modifierIds) if (touches.containsKey(m)) chorded.add(m)
    }

    /** Once no non-modifier key is down any more, latches that were used are spent. */
    private fun nonModifierReleased() {
        for (id in touches.keys) if (id !in modifierIds) return
        for (l in latches.values) if (l.state == LATCH_USED) l.state = LATCH_NONE
    }

    /** Modifier control [id] was released after a press; [tap] = short and nothing else was pressed meanwhile. */
    private fun modifierUp(id: String, tap: Boolean, now: Long) {
        if (touches.containsKey(id)) return // another finger still holds it
        val wasChord = chorded.remove(id)
        if (tap && !wasChord) modifierTap(id, now)
        else latches[id]?.let { if (it.state == LATCH_ON || it.state == LATCH_USED) it.state = LATCH_NONE }
    }

    /** Tap = latch for the next key; a second tap soon after = lock; a tap on a locked or latched one = off. */
    private fun modifierTap(id: String, now: Long) {
        val l = latches.getOrPut(id) { Latch() }
        l.state = when (l.state) {
            LATCH_LOCKED -> LATCH_NONE
            LATCH_ON -> if (now - l.tapNs <= STICKY_DOUBLE_TAP_NS) LATCH_LOCKED else LATCH_NONE
            else -> LATCH_ON
        }
        l.tapNs = now
    }

    private fun slotOf(pointer: Long): Int {
        for (i in 0 until MAX_BUTTON_FINGERS) if (bpState[i] != BP_FREE && bpPointer[i] == pointer) return i
        return -1
    }

    /**
     * Current value of a source: digital 0/1, analog -1..1 (or 0..1 for a half). Key and axis
     * sources take the largest value over all devices, attached or not.
     */
    fun sourceValue(source: InputSource): Float = synchronized(lock) {
        when (source) {
            is InputSource.Key, is InputSource.Axis -> {
                var best = value(source, anyDevice)
                for (d in deviceList) {
                    val v = value(source, d)
                    if (abs(v) > abs(best)) best = v
                }
                best
            }
            else -> value(source, null)
        }
    }

    /** A source's value in one group: keys and axes from [dev] (none for the touch group). */
    private fun value(source: InputSource, dev: Dev?): Float = when (source) {
        is InputSource.Key -> if (dev != null && dev.key(source.keyCode)) 1f else 0f
        is InputSource.Axis -> if (dev == null) 0f else half(dev.axis(source.axis), source.half)
        is InputSource.Touch -> if (touches.containsKey(source.id) || latched(source.id)) 1f else 0f
        is InputSource.TouchStick -> {
            val v = touchSticks[source.id]
            if (v == null) 0f else half(if (source.axis == StickAxis.X) v[0] else v[1], source.half)
        }
    }

    private fun latched(id: String): Boolean {
        if (latches.isEmpty()) return false
        val l = latches[id] ?: return false
        return l.state != LATCH_NONE
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
        /** Android axis ids are 0..47 (MotionEvent.AXIS_GENERIC_16). */
        private const val AXIS_SLOTS = 64

        /** Touchpad over buttons: a finger that moves further than this becomes a touchpad finger. */
        const val BUTTON_SLOP_DP = 10f
        /** The longest sticky-modifier tap of a key that presses at once. */
        const val BUTTON_HOLD_NS = 300_000_000L
        /** Sticky modifiers: a second tap within this of the first locks the modifier. */
        const val STICKY_DOUBLE_TAP_NS = 400_000_000L
        private const val MAX_BUTTON_FINGERS = 10

        private const val BP_FREE = 0
        /** Touchpad over buttons: a touchpad finger that is still a tap candidate. */
        private const val BP_PENDING = 1
        private const val BP_PRESSED = 2
        private const val BP_TOUCHPAD = 3
        /** Click-through: pressed and a touchpad finger. */
        private const val BP_THROUGH = 4

        private const val LATCH_NONE = 0
        /** Latched for the next non-modifier key. */
        private const val LATCH_ON = 1
        /** Latched and applied to a key that is still down; spent when it lifts. */
        private const val LATCH_USED = 2
        private const val LATCH_LOCKED = 3

        /** Touch controls whose mappings are all keyboard modifiers. */
        private fun modifierControls(mappings: List<Mapping>): Set<String> {
            val all = HashSet<String>()
            val not = HashSet<String>()
            for (m in mappings) {
                val s = m.source as? InputSource.Touch ?: continue
                val t = m.target
                if (t is OutputTarget.Key && HidUsage.isModifier(t.usage)) all.add(s.id) else not.add(s.id)
            }
            all.removeAll(not)
            return all
        }
    }
}
