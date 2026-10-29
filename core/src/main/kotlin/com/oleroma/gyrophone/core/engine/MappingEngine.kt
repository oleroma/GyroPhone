package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.HapticEdge
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KeyMode
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.RatchetAxes
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.StickDiagonal
import com.oleroma.gyrophone.core.model.StickZones
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.layered
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.surfaceSettings
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    /**
     * The settings in effect: [ws]'s with the own settings of the layers in use laid over
     * ([WorkspaceDefaults.layered]); set by [applyLayers].
     */
    @Volatile
    private var eff: Workspace = ws

    /** The gyro settings in effect now: the workspace's, or a layer's in use. */
    val gyroSettings: GyroSettings get() = eff.gyro

    /** Touch mappings of the active workspace in use (the touch source group): see [applyLayers]. */
    private var touchMappings: List<Mapping> = emptyList()

    /** Mappings in use of each attached pad of the active workspace, by pad index, then the keyboard's. */
    private var padMappings: Array<List<Mapping>> = emptyArray()

    /** Every set's mappings: the touch group's, then each pad's like [padMappings]. */
    private var allTouch: List<Mapping> = emptyList()
    private var allPads: Array<List<Mapping>> = emptyArray()

    // Mapping sets ([OutputTarget.Layer]): the set in use, the overrides laid over it (bottom to
    // top), and what each held "... hold" action goes back to on release.
    private var layerSet = MappingSet.BASE
    private val layerOverrides = ArrayList<Int>()

    private class LayerHold(val set: Int, val overrides: List<Int>, val added: Boolean)

    private val layerHolds = HashMap<OutputTarget.Layer, LayerHold>()

    /**
     * Inputs held through a set change, per group (0 = touch, then 1 + pad index), by source: the
     * old mappings they keep until released, and whether the new set's are held back meanwhile
     * ([holdsLayers]: only its layer actions, for the input holding a "... hold" action).
     */
    private class Carried(val mappings: List<Mapping>, val holdsBack: Boolean, val holdsLayers: Boolean = false)

    private var carried: Array<HashMap<InputSource, Carried>> = emptyArray()
    private var anyCarried = false

    /**
     * By [padMappings] index: the list maps a left (LTRIGGER / BRAKE) or right (RTRIGGER / GAS)
     * analog trigger axis to a game pad trigger, so that side's L2 / R2 key duplicates it.
     */
    private var mapsLeftTriggerAxis = BooleanArray(0)
    private var mapsRightTriggerAxis = BooleanArray(0)

    /** Some mapping in use is a gyro ratchet ([gyroAxes] runs per gyro sample: no search without one). */
    private var ratchetMapped = false

    /** The gyro mappings in use ([InputSource.Gyro]), [OutputTarget.NoOutput] kept: the workspace's and every button's. */
    private var gyroInUse: List<Mapping> = emptyList()

    /** Game pad axes the gyro deflects ([setGyroAxes]), by [GamepadAxis] ordinal. */
    private val gyroPad = FloatArray(GamepadAxis.entries.size)

    /** The phone's tilt ([setTilt]), -1..1: sideways (+ right) and forward / back (+ back). */
    private var tiltX = 0f
    private var tiltY = 0f

    /** When the last shake came ([shake]); it shows at least [SHAKE_PULSE_NS] and in one snapshot. */
    private var shakeAt = Long.MIN_VALUE
    private var shakeShown = true

    /** Some tilt or shake mapping is in any set of the workspace: the runtime reads the accelerometer. */
    @Volatile var motionMapped = false
        private set

    /**
     * Input state of one device: its keys, its axes and the pad of the workspace it acts as.
     * [keys] is the real level (events, gyro hold); HID output reads [keyForOutput], which makes
     * every key edge show in at least one snapshot, so a tap or a short release between two pump
     * ticks is not lost.
     */
    private class Dev(var device: PadDevice?) {
        /** Index into [padMappings]; -1 = not attached here (the device does nothing). */
        var pad = -1
        val keys = BitSet()

        /** Gestures of its keys that have gesture mappings, by key code (also in [tapList]). */
        val gestures = HashMap<Int, ButtonGestures>()
        val axes = FloatArray(AXIS_SLOTS)
        private var axisCount = 0

        /** Keys pressed / released since the last snapshot, and what that snapshot showed. */
        private val pressedSince = BitSet()
        private val releasedSince = BitSet()
        private val shown = BitSet()
        private val scratch = BitSet()

        val active: Boolean
            get() = axisCount > 0 || !keys.isEmpty || !pressedSince.isEmpty || !shown.isEmpty ||
                    (gestures.isNotEmpty() && gestures.values.any { it.anyOn })
        val hasAxes: Boolean get() = axisCount > 0

        fun key(code: Int): Boolean = code >= 0 && keys.get(code)
        fun axis(a: Int): Float = if (a in 0 until AXIS_SLOTS) axes[a] else 0f

        /** Shown down: up if released since (even if pressed again). Shown up: down if pressed since. */
        fun keyForOutput(code: Int): Boolean {
            if (code < 0) return false
            return if (shown.get(code)) keys.get(code) && !releasedSince.get(code)
            else keys.get(code) || pressedSince.get(code)
        }

        /** A snapshot showed [keyForOutput]: remember it and start the next interval. */
        fun shownInSnapshot() {
            // shown = if (shown) keys && !released else keys || pressed
            scratch.clear(); scratch.or(keys); scratch.or(pressedSince); scratch.andNot(shown)
            shown.and(keys); shown.andNot(releasedSince); shown.or(scratch)
            pressedSince.clear(); releasedSince.clear()
        }

        fun setKey(code: Int, pressed: Boolean) {
            if (code < 0) return
            keys.set(code, pressed)
            if (pressed) pressedSince.set(code) else releasedSince.set(code)
        }

        fun setAxis(a: Int, value: Float) {
            if (a !in 0 until AXIS_SLOTS) return
            val v = if (abs(value) < AXIS_EPSILON) 0f else value
            if (axes[a] == 0f && v != 0f) axisCount++ else if (axes[a] != 0f && v == 0f) axisCount--
            axes[a] = v
        }

        /** Whether [setAxis] of the first [n] pairs would change anything. */
        fun differs(ids: IntArray, values: FloatArray, n: Int): Boolean {
            for (i in 0 until n) {
                val a = ids[i]
                if (a !in 0 until AXIS_SLOTS) continue
                val v = if (abs(values[i]) < AXIS_EPSILON) 0f else values[i]
                if (axes[a] != v) return true
            }
            return false
        }

        fun clearAxes() {
            axes.fill(0f); axisCount = 0
        }

        fun clear() {
            keys.clear(); axes.fill(0f); axisCount = 0
            pressedSince.clear(); releasedSince.clear(); shown.clear()
        }
    }

    private val devices = HashMap<Int, Dev>()

    /** Same as [devices]' values, for allocation-free iteration. */
    private val deviceList = ArrayList<Dev>()

    /** The legacy "any device" source: acts on every pad of the workspace. */
    private val anyDevice = Dev(null)

    /** Pressed touch controls with a press count (setTouch, button fingers, tap pulses). */
    private val touches = HashMap<String, Int>()

    /** Touch controls held, first pressed first: the last one's gyro mappings win ([gyroRoutes]). */
    private val pressOrder = LinkedHashSet<String>()

    /** Controls held through [setTouch], with their press time. */
    private val setTouchHeld = HashMap<String, Long>()
    private val touchSticks = HashMap<String, StickState>()

    /** Deadzone and zones of a touch stick; [diagonalMapped] by [StickDiagonal] ordinal. */
    private class StickCfg(val deadzone: Float, val zones: StickZones, val diagonalMapped: BooleanArray)

    private var stickCfgs: Map<String, StickCfg> = emptyMap()

    /**
     * A touch stick past its deadzone: [x], [y] rescaled, [mag] their length (0..1). With zones,
     * [dirs] holds the active directions (DIR_* bits) and the halves read [mag] from it.
     */
    private class StickState {
        var x = 0f;
        var y = 0f;
        var mag = 0f
        var zoned = false
        var dirs = 0
    }

    // Fingers on touch buttons (see buttonDown), fixed slots so pointer events allocate little.
    private val bpPointer = LongArray(MAX_BUTTON_FINGERS)
    private val bpControl = arrayOfNulls<String>(MAX_BUTTON_FINGERS)
    private val bpState = IntArray(MAX_BUTTON_FINGERS)
    private val bpDownX = FloatArray(MAX_BUTTON_FINGERS)
    private val bpDownY = FloatArray(MAX_BUTTON_FINGERS)
    private val bpDownNs = LongArray(MAX_BUTTON_FINGERS)

    /** The workspace's touch buttons: each takes the touchpad's mappings it does not map itself. */
    private var buttonIds: List<String> = emptyList()

    /** Touch controls that take the new set's mappings at once when it changes while they are held. */
    private var followLayerIds: Set<String> = emptySet()

    /**
     * Gestures (tap, double tap, long press, release) of the touch buttons that map one, by id,
     * and every gesture state in play: those and the keys' ([Dev.gestures]).
     */
    private val tapStates = HashMap<String, ButtonGestures>()
    private val tapList = ArrayList<ButtonGestures>()

    /** What a key with gesture mappings needs: whether it has a double tap and its tap waits. */
    private class KeyGestureCfg(val hasDouble: Boolean, val tapWaits: Boolean)

    /** Per pad of [padMappings]: the keys with gesture mappings; [anyKeyCfgs] for the "any device" source. */
    private var keyCfgs: Array<Map<Int, KeyGestureCfg>> = emptyArray()
    private var anyKeyCfgs: Map<Int, KeyGestureCfg> = emptyMap()

    /**
     * The touch buttons: a touch surface each, a touchpad of their own that every finger on the
     * button feeds ([InputSource.Surface] with the button's id).
     */
    private var surfaces: Map<String, Touchpad> = emptyMap()
    private var surfaceList: Array<Touchpad> = emptyArray()

    /** A still tap committed on lift: pressed until a snapshot has shown it for the minimum pulse. */
    private class Pulse(val id: String, val startNs: Long) {
        var seen = false
    }

    private val pulses = ArrayList<Pulse>()

    /** Toggle / next-key key mappings whose source was held at the last snapshot ([keyModePress]). */
    private val keyModeHeld = HashSet<Mapping>()

    /** Keys a toggle mapping has turned on. */
    private val toggledKeys = LinkedHashSet<Int>()

    /** Keys held for the next other key: true once one was pressed (let go with it). */
    private val nextKeys = LinkedHashMap<Int, Boolean>()

    /**
     * Held [InputSource.TouchArea]s of one kind, set by the pad screen ([setTouchArea]). One let go
     * before a snapshot showed it for the minimum pulse stays held in [late] until one has, so a
     * finger sliding quickly across a zone still presses it.
     */
    private class AreaHolds {
        /** Held ids with the time they went on. */
        val since = HashMap<String, Long>()

        /** Ids a snapshot has shown since they went on. */
        val seen = HashSet<String>()

        /** Let go too soon: id to the time it went on. */
        val late = HashMap<String, Long>()
        fun held(id: String) = since.containsKey(id) || late.containsKey(id)
        fun clear() {
            since.clear(); seen.clear(); late.clear()
        }
    }

    private val areas = Array(TouchAreaKind.entries.size) { AreaHolds() }


    private var gyroToggled = false
    private var mouseDx = 0
    private var mouseDy = 0

    /** A physical mouse's movement not yet whole counts (or wheel units), carried to the next. */
    private val mouseRest = FloatArray(4)

    /** Wheel from bindings, in 1/120 notch. */
    private var wheel = 0

    /** Sideways wheel from swipes, in 1/120 notch. */
    private var hwheel = 0
    private val touchpad = Touchpad(profile.workspace(0).touchpad)

    // Snapshot scratch, reused so the 1000 Hz pump allocates little.
    private val stick = FloatArray(4)
    private val groupStick = FloatArray(4)
    private val trigger = FloatArray(2)
    private var accButtons = 0
    private var accUp = false;
    private var accDown = false;
    private var accLeft = false;
    private var accRight = false
    private var accMouse = 0
    private val heldKeys = BooleanArray(256)

    /** Keyboard usages in press order, so the 6-key array is stable while keys are held. */
    private val keyOrder = IntArray(256)
    private val inKeyOrder = BooleanArray(256)
    private var keyOrderSize = 0

    private val eventsBefore = HashSet<OutputTarget>()
    private val eventsAfter = HashSet<OutputTarget>()

    // Haptic mappings with their source's value (0..1) in the last snapshot and this one, the
    // step each per-step one was at, and what [snapshot] hands the sink.
    private var hapticWas = HashMap<Mapping, Float>()
    private var hapticNow = HashMap<Mapping, Float>()
    private val hapticSteps = HashMap<Mapping, Int>()
    private val hapticTicks = ArrayList<HapticFeel>()
    private var hapticHeld: HapticFeel? = null
    private var hapticLevel = 0f
    private var hapticDue = false

    /**
     * Gets the haptic targets ([OutputTarget.Haptic], [OutputTarget.HapticStep]) from each
     * [snapshot], on the pump's thread and outside the engine lock: ticks since the last snapshot
     * and the held feel (null = none) with its level (0..1, how far an axis is pushed). It is
     * called while something is held (so it can keep vibrating), on a tick, and once when a hold
     * ends. The list is reused: read it during the call only.
     */
    @Volatile
    var hapticSink: HapticSink? = null

    init {
        applyWorkspace()
    }

    /**
     * Called after every input mutation, outside the engine lock, so a transport can wake its
     * sender without polling. The callback may call [snapshot].
     */
    @Volatile
    var onChange: Runnable? = null

    /** Loads the active workspace and releases everything held. Under the lock. */
    private fun applyWorkspace() {
        val loaded = profile.workspace(workspaceIndex)
        // The touchpad mappings it does not set come from the Defaults.
        val w = loaded.copy(mappings = Bindings.upgradeSwipes(Inheritance.workspace(loaded, profile.defaults)))
        ws = w
        allTouch = w.mappings
        motionMapped = w.mappings.any { it.source is InputSource.Tilt || it.source is InputSource.Shake }
        allPads = Array(w.pads.size + 2) {
            when {
                it < w.pads.size -> w.pads[it].mappings
                it == w.pads.size -> w.keyboard
                else -> w.mouse
            }
        }
        touchMappings = allTouch
        padMappings = allPads
        buttonIds = w.touchControls.filter { it.kind == TouchKind.BUTTON }.map { it.id }
        followLayerIds = w.touchControls.filter { it.followLayers }.mapTo(HashSet()) { it.id }
        val swipers = w.touchControls.filter { it.swipes }
        surfaces = swipers.associate { c ->
            c.id to Touchpad(c.surfaceSettings(w.touchpad), SurfaceMap(w.mappings, c.id)).also {
                it.pxPerDp = touchpad.pxPerDp; it.minPulseNanos = touchpad.minPulseNanos
                it.doubleTapNanos = c.doubleTap(w) * 1_000_000L; it.longPressNanos = c.longPress(w) * 1_000_000L
            }
        }
        surfaceList = surfaces.values.toTypedArray()
        tapStates.clear(); tapList.clear()
        for (d in deviceList) d.gestures.clear()
        anyDevice.gestures.clear()
        val withEvents = HashSet<String>()
        for (m in w.mappings) {
            when (val src = m.source) {
                is InputSource.TouchTap -> withEvents.add(src.id)
                is InputSource.TouchLongPress -> withEvents.add(src.id)
                is InputSource.TouchRelease -> withEvents.add(src.id)
                is InputSource.TouchDoubleTap -> withEvents.add(src.id)
                else -> Unit
            }
        }
        for (c in w.touchControls) {
            if (c.kind != TouchKind.BUTTON || c.id !in withEvents) continue
            val double = targetsOf(w.mappings, InputSource.TouchDoubleTap(c.id))
            val st = ButtonGestures(
                c.longPress(w) * 1_000_000L, c.doubleTap(w) * 1_000_000L,
                hasDouble = double.isNotEmpty(),
                tapWaits = double.isNotEmpty() && double != targetsOf(w.mappings, InputSource.TouchTap(c.id)),
            )
            tapStates[c.id] = st; tapList.add(st)
        }
        stickCfgs = w.touchControls.filter { it.kind == TouchKind.STICK }.associate { c ->
            c.id to StickCfg(c.deadzone.coerceIn(0f, TouchControl.MAX_DEADZONE), c.zones, BooleanArray(StickDiagonal.entries.size))
        }
        releaseHeld()
        for (d in deviceList) d.pad = resolvePad(d.device)
        layerSet = MappingSet.BASE; layerOverrides.clear(); layerHolds.clear()
        carried = Array(allPads.size + 1) { HashMap() }; anyCarried = false
        applyLayers()
        touchpad.cancel(clock())
    }

    /** Forgets every held input (keys, axes, touches, sticks, fingers, pulses, latches). */
    private fun releaseHeld() {
        for (d in deviceList) d.clear()
        anyDevice.clear()
        touches.clear(); pressOrder.clear(); setTouchHeld.clear(); touchSticks.clear()
        for (s in surfaceList) s.reset()
        for (a in areas) a.clear()
        hapticWas.clear(); hapticNow.clear(); hapticSteps.clear()
        bpState.fill(BP_FREE); bpControl.fill(null)
        pulses.clear()
        keyModeHeld.clear(); toggledKeys.clear(); nextKeys.clear()
        for (t in tapList) t.reset()
        gyroToggled = false
        keyOrderSize = 0; inKeyOrder.fill(false)
    }

    private fun resolvePad(device: PadDevice?): Int {
        if (device == null) return -1
        if (device.keyboard) return ws.pads.size
        if (device.mouse) return ws.pads.size + 1
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
            dropGestures(d)
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
            val now = clock()
            firingEvents(now) {
                val d = deviceFor(device)
                val was = d.key(keyCode)
                d.setKey(keyCode, pressed)
                if (was != pressed) keyEdge(d, keyCode, pressed, now)
                pad = d.pad
            }
            pad >= 0 && allPads[pad].any { mapsKey(it.source, keyCode) }
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

    /**
     * The first [n] axes of one joystick event ([axes] ids, [values]) as one change, so a snapshot
     * never sees half of it. Wakes the sender only if a value changed. Allocation-free.
     */
    fun setAxes(device: PadDevice, axes: IntArray, values: FloatArray, n: Int) {
        synchronized(lock) {
            val d = devices[device.id]
            if (d != null && d.device == device && !d.differs(axes, values, n)) return
            firingEvents(clock()) {
                val dev = deviceFor(device)
                for (i in 0 until n) dev.setAxis(axes[i], values[i])
            }
        }
        onChange?.run()
    }

    /** Zeroes every axis of a device and keeps its keys (its joystick events were cancelled). */
    fun clearAxes(deviceId: Int) {
        synchronized(lock) {
            val d = devices[deviceId]
            if (d == null || !d.hasAxes) return
            firingEvents(clock()) { d.clearAxes() }
        }
        onChange?.run()
    }

    /** Releases everything a device holds (it was disconnected). */
    fun deviceRemoved(deviceId: Int) {
        synchronized(lock) {
            firingEvents(clock()) {
                val d = devices.remove(deviceId)
                if (d != null) {
                    deviceList.remove(d); dropGestures(d)
                }
            }
        }
        onChange?.run()
    }

    /**
     * A finger landed on touch control [id] (BUTTON kind) at [x], [y] px of the pad area: a finger
     * of the button's own surface, which presses it at once (its one-finger hold) and drives the
     * touch inputs it maps or takes from the touchpad until it lifts. [pointer] is the finger id;
     * [eventNanos] is the touch's event time (same clock as [clock]), used only for cursor speed.
     */
    fun buttonDown(pointer: Long, id: String, x: Float, y: Float, eventNanos: Long = clock()) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                if (slotOf(pointer) >= 0) return@firingEvents
                val slot = bpState.indexOf(BP_FREE)
                if (slot < 0) return@firingEvents
                bpPointer[slot] = pointer; bpControl[slot] = id
                bpDownX[slot] = x; bpDownY[slot] = y; bpDownNs[slot] = now
                press(id)
                val surface = surfaces[id]
                if (surface != null) {
                    bpState[slot] = BP_SWIPE
                    surface.down(pointer, x, y, now, eventNs = eventNanos)
                } else bpState[slot] = BP_PRESSED
            }
        }
        onChange?.run()
    }

    /** A finger that went down through [buttonDown] moved (px of the pad area); [eventNanos] as there. */
    fun buttonMove(pointer: Long, x: Float, y: Float, eventNanos: Long = clock()) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                val slot = slotOf(pointer)
                if (slot < 0 || bpState[slot] != BP_SWIPE) return@firingEvents
                surfaces[bpControl[slot]]?.move(pointer, x, y, now, eventNanos)
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
                if (bpState[slot] == BP_SWIPE) surfaces[id]?.up(pointer, now)
                // A quick tap stays pressed until a snapshot has shown it for the minimum pulse,
                // so a pump that ticks between its down and up still sends it.
                if (now - bpDownNs[slot] < touchpad.minPulseNanos) pulses.add(Pulse(id, bpDownNs[slot])) else release(id)
                bpState[slot] = BP_FREE; bpControl[slot] = null
            }
        }
        onChange?.run()
    }

    /**
     * A finger that went down through [buttonDown] was cancelled by the system: what it pressed is
     * released and its surface finger ends without a tap.
     */
    fun buttonCancel(pointer: Long) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                val slot = slotOf(pointer)
                if (slot < 0) return@firingEvents
                val id = bpControl[slot]!!
                if (bpState[slot] == BP_SWIPE) surfaces[id]?.cancelFinger(pointer, now)
                release(id)
                bpState[slot] = BP_FREE; bpControl[slot] = null
            }
        }
        onChange?.run()
    }

    /** Touch controls whose swipe holds are latched, for highlighting. */
    fun latchedTouches(): Set<String> = synchronized(lock) {
        val out = HashSet<String>()
        for ((id, s) in surfaces) if (s.latched) out.add(id)
        out
    }

    /** Swipe button [id]'s held directions and zones as mapped in the set in use, for its swipe line ([Touchpad.guide]). */
    fun swipeGuide(id: String): SwipeGuide? = synchronized(lock) { surfaces[id]?.guide() }

    /**
     * The touch mappings in use now: the mapping set and overrides in use, with what inputs held
     * through a set change still press instead (for labels that show what is really active).
     */
    val touchMappingsInUse: List<Mapping> get() = synchronized(lock) { touchMappings }

    /** Touch controls currently pressed (including by touchpad-over-buttons commits and zones), for highlighting. */
    fun pressedTouches(): Set<String> = synchronized(lock) {
        resolveIfDue(clock())
        val out = HashSet(touches.keys)
        val zone = areas[TouchAreaKind.ZONE.ordinal]
        out.addAll(zone.since.keys); out.addAll(zone.late.keys)
        out
    }

    /**
     * A key from the "any device" source, evaluated against every pad of the workspace. Returns
     * true if one of them maps it. App code uses the [PadDevice] overload.
     */
    fun setKey(keyCode: Int, pressed: Boolean): Boolean {
        val mapped = synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                val was = anyDevice.key(keyCode)
                anyDevice.setKey(keyCode, pressed)
                if (was != pressed) keyEdge(anyDevice, keyCode, pressed, now)
            }
            allPads.any { ms -> ms.any { mapsKey(it.source, keyCode) } }
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
                    setTouchHeld.remove(id) ?: return@firingEvents
                    release(id)
                }
            }
        }
        onChange?.run()
    }

    /**
     * Holds or lets go [InputSource.TouchArea] ([id], [kind]): the pad screen tracks where fingers
     * are and calls this on each change. A hold shorter than the minimum pulse is stretched.
     */
    fun setTouchArea(id: String, kind: TouchAreaKind, held: Boolean) {
        synchronized(lock) {
            val now = clock()
            firingEvents(now) {
                val a = areas[kind.ordinal]
                if (held) {
                    if (!a.since.containsKey(id)) a.since[id] = a.late.remove(id) ?: now
                } else {
                    val t = a.since.remove(id) ?: return@firingEvents
                    if (id !in a.seen || now - t < touchpad.minPulseNanos) a.late[id] = t else a.seen.remove(id)
                }
            }
        }
        onChange?.run()
    }

    /** Lets go every [InputSource.TouchArea] at once (pad left, layout changed), without pulses. */
    fun touchAreasCancel() {
        synchronized(lock) {
            firingEvents(clock()) { for (a in areas) a.clear() }
        }
        onChange?.run()
    }

    /** Touch stick deflection, each axis in -1..1 (Y positive = down, like the HID game pad). */
    fun setTouchStick(id: String, x: Float, y: Float) {
        synchronized(lock) {
            firingEvents(clock()) {
                val v = touchSticks.getOrPut(id) { StickState() }
                val cfg = stickCfgs[id]
                var mag = hypot(x, y)
                // Axes pass through as given (the pad screen keeps the knob in the circle).
                var k = 1f
                val dz = cfg?.deadzone ?: 0f
                if (dz > 0f) k = if (mag <= dz) 0f else min(1f, (mag - dz) / (1f - dz)) / mag
                v.x = x * k; v.y = y * k; mag = min(1f, mag * k)
                v.mag = mag
                v.zoned = cfg != null && cfg.zones != StickZones.OVERLAP
                v.dirs = if (!v.zoned || mag == 0f) 0 else zoneDirs(v.x, v.y, cfg!!)
            }
        }
        onChange?.run()
    }

    /**
     * A physical mouse ([PadDevice.mouse]) moved [dx], [dy] counts: its Move X / Move Y mappings
     * ([AndroidAxis.MOUSE_X], [AndroidAxis.MOUSE_Y] axis sources) in the set in use move the PC
     * mouse or scroll ([OutputTarget.MouseAxis], times its scale; scrolling a notch per
     * [MOUSE_COUNTS_PER_NOTCH] counts). Unmapped, it does nothing.
     */
    fun mouseMove(device: PadDevice, dx: Float, dy: Float) {
        synchronized(lock) {
            val pad = deviceFor(device).pad
            val ms = padMappings.getOrNull(pad) ?: return
            for (i in ms.indices) {
                val m = ms[i]
                val src = m.source as? InputSource.Axis ?: continue
                val t = m.target as? OutputTarget.MouseAxis ?: continue
                val v = when (src.axis) {
                    AndroidAxis.MOUSE_X -> dx
                    AndroidAxis.MOUSE_Y -> dy
                    else -> continue
                } * t.scale
                val k = t.motion.ordinal
                // Scrolling goes the way the mouse does: moving it up scrolls up, like the wheel.
                val add = when (t.motion) {
                    MouseMotion.MOVE_X, MouseMotion.MOVE_Y -> v
                    MouseMotion.WHEEL -> -v * MouseState.WHEEL_UNIT / MOUSE_COUNTS_PER_NOTCH
                    MouseMotion.HWHEEL -> v * MouseState.WHEEL_UNIT / MOUSE_COUNTS_PER_NOTCH
                }
                val total = mouseRest[k] + add
                val whole = total.toInt()
                mouseRest[k] = total - whole
                when (t.motion) {
                    MouseMotion.MOVE_X -> mouseDx += whole
                    MouseMotion.MOVE_Y -> mouseDy += whole
                    MouseMotion.WHEEL -> wheel += whole
                    MouseMotion.HWHEEL -> hwheel += whole
                }
            }
        }
        onChange?.run()
    }

    /** Relative mouse motion and scrolling from the gyro, already in mouse counts and wheel units (touchpad motion has its own path). */
    fun addMouseMotion(dx: Int, dy: Int, wheel: Int = 0, hwheel: Int = 0) {
        synchronized(lock) {
            mouseDx += dx
            mouseDy += dy
            this.wheel += wheel
            this.hwheel += hwheel
        }
        onChange?.run()
    }

    /**
     * Where gyro motion goes now: the workspace's gyro mappings in use, each axis a held button
     * maps taken over by that button's (the last pressed of several). Empty with no gyro mapping
     * in use at all ([gyroMapped] false): gyro then moves the mouse as its settings' axes say.
     */
    fun gyroRoutes(): List<Mapping> = synchronized(lock) {
        val ms = gyroInUse
        if (ms.isEmpty()) return@synchronized emptyList()
        var out = ms.filter { (it.source as InputSource.Gyro).id.isEmpty() }
        for (id in pressOrder) {
            val own = ms.filter { (it.source as InputSource.Gyro).id == id }
            if (own.isEmpty()) continue
            val axes = own.mapTo(HashSet()) { (it.source as InputSource.Gyro).axis }
            out = out.filterNot { (it.source as InputSource.Gyro).axis in axes } + own
        }
        out.filter { it.target != OutputTarget.NoOutput }
    }

    /** Some gyro mapping is in use ([gyroRoutes]); without one gyro moves the mouse by its settings' axes. */
    val gyroMapped: Boolean get() = gyroInUse.isNotEmpty()

    /** How far the phone is tilted: [x] sideways (+ right), [y] forward and back (+ back), each -1..1. */
    fun setTilt(x: Float, y: Float) {
        synchronized(lock) {
            if (x == tiltX && y == tiltY) return
            firingEvents(clock()) { tiltX = x; tiltY = y }
        }
        onChange?.run()
    }

    /** The phone was shaken: shake mappings press once. */
    fun shake() {
        synchronized(lock) {
            firingEvents(clock()) { shakeAt = clock(); shakeShown = false }
        }
        onChange?.run()
    }

    /** How far the gyro deflects each game pad axis now (by [GamepadAxis] ordinal, -1..1; triggers 0..1). */
    fun setGyroAxes(values: FloatArray) {
        synchronized(lock) {
            if (values.contentEquals(gyroPad)) return
            values.copyInto(gyroPad, endIndex = minOf(values.size, gyroPad.size))
        }
        onChange?.run()
    }

    /**
     * Background-of-the-pad touchpad finger, position in px of the pad area. [eventNanos] is the
     * touch's event time (same clock as [clock]), used only for cursor and scroll speed.
     */
    fun touchpadDown(pointer: Long, x: Float, y: Float, eventNanos: Long = clock()) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.down(pointer, x, y, now, eventNs = eventNanos)
        }
        onChange?.run()
    }

    fun touchpadMove(pointer: Long, x: Float, y: Float, eventNanos: Long = clock()) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.move(pointer, x, y, now, eventNanos)
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
            touchpad.cancel(clock())
        }
        onChange?.run()
    }

    /** Removes one background touchpad finger the system cancelled: no click, tap, fling or held gesture key. */
    fun touchpadCancel(pointer: Long) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.cancelFinger(pointer, now)
        }
        onChange?.run()
    }

    /** px per dp of the touch surface. */
    var touchpadPxPerDp: Float
        get() = synchronized(lock) { touchpad.pxPerDp }
        set(value) {
            synchronized(lock) { touchpad.pxPerDp = value; for (s in surfaceList) s.pxPerDp = value }
        }

    /** Shortest visible click press/release; see [Touchpad.minPulseFor]. Also times button taps. */
    var touchpadMinPulseNanos: Long
        get() = synchronized(lock) { touchpad.minPulseNanos }
        set(value) {
            synchronized(lock) { touchpad.minPulseNanos = value; for (s in surfaceList) s.minPulseNanos = value }
        }

    /**
     * When the engine next needs a snapshot without new input (Long.MAX_VALUE = never): touchpad
     * timers, a tap's release.
     */
    fun touchpadDeadlineNanos(): Long = synchronized(lock) {
        var d = touchpad.nextDeadline()
        for (s in surfaceList) d = min(d, s.nextDeadline())
        for (i in pulses.indices) d = min(d, pulses[i].startNs + touchpad.minPulseNanos)
        for (i in tapList.indices) d = min(d, tapDeadline(tapList[i]))
        for (a in areas) for ((id, t) in a.late) if (id in a.seen) d = min(d, t + touchpad.minPulseNanos)
        // A held haptic is renewed from snapshots, so they keep coming while it is held.
        if (hapticHeld != null) d = min(d, clock() + HAPTIC_RENEW_NS)
        d
    }

    /**
     * Whether gyro motion should currently move the mouse. A ratchet (a held [OutputTarget.GyroPause]
     * or old [OutputTarget.GyroHold] source) pauses ALWAYS and engages OFF while held. A
     * [OutputTarget.GyroToggle] press swaps the two until the workspace changes. SENSOR_OFF: never.
     */
    val gyroActive: Boolean get() = gyroAxes != 0

    /**
     * The cursor axes gyro moves now: bit 1 = X, bit 2 = Y. A ratchet flips only its
     * [GyroSettings.ratchetAxes]; the other axis stays as the mode has it without one.
     */
    val gyroAxes: Int
        get() = synchronized(lock) {
            val g = eff.gyro
            if (g.mode == GyroMode.SENSOR_OFF) return@synchronized 0
            val ratchet = ratchetMapped && (engaged(OutputTarget.GyroPause) || engaged(OutputTarget.GyroHold))
            // On without a ratchet: "ratchet disables" (ALWAYS) unless toggled, "ratchet enables" only toggled.
            val base = if (g.mode == GyroMode.ALWAYS) !gyroToggled else gyroToggled
            val flipped = base != ratchet
            val x = if (g.ratchetAxes == RatchetAxes.Y) base else flipped
            val y = if (g.ratchetAxes == RatchetAxes.X) base else flipped
            (if (x) GYRO_X else 0) or (if (y) GYRO_Y else 0)
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
            applyLayerSettings()
        }
        onChange?.run()
    }

    /** Releases everything and goes back to Base, e.g. when the profile changes or the host disconnects. */
    fun reset() {
        synchronized(lock) {
            releaseHeld()
            layerSet = MappingSet.BASE; layerOverrides.clear(); layerHolds.clear()
            for (c in carried) c.clear()
            anyCarried = false
            applyLayers()
            mouseDx = 0; mouseDy = 0; wheel = 0; hwheel = 0
            gyroPad.fill(0f)
            touchpad.reset()
            for (s in surfaceList) s.reset()
        }
        onChange?.run()
    }

    /**
     * Computes the current HID state and drains accumulated relative mouse motion. A device key
     * pressed or released since the last snapshot shows its edge here even if it already flipped
     * back, so only the pumps may call this (OutputLink).
     */
    fun snapshot(): HidState {
        val s = synchronized(lock) { snapshotLocked() }
        if (hapticDue) {
            hapticDue = false
            hapticSink?.haptics(hapticTicks, hapticHeld, hapticLevel)
        }
        return s
    }

    private fun snapshotLocked(): HidState {
        val now = clock()
        resolveIfDue(now)

        accButtons = 0; accMouse = 0
        accUp = false; accDown = false; accLeft = false; accRight = false
        stick.fill(0f); trigger.fill(0f); heldKeys.fill(false)
        // The touchpad's next input change shows first, so its mappings read it below; one that
        // engages a wheel or gyro toggle fires it.
        var padButtons = 0
        if (touchpad.queued) firingEvents(now) { padButtons = touchpad.poll(now) } else padButtons = touchpad.poll(now)
        for (s in surfaceList) {
            padButtons = padButtons or if (s.queued) {
                var b = 0; firingEvents(now) { b = s.poll(now) }; b
            } else s.poll(now)
        }
        if (anyCarried) pruneCarried()
        hapticNow.clear()
        forEachGroup { ms, dev -> accumulate(ms, dev) }
        shakeShown = true
        for (a in 0 until 4) if (abs(gyroPad[a]) > abs(stick[a])) stick[a] = gyroPad[a]
        trigger[0] = max(trigger[0], abs(gyroPad[GamepadAxis.LT.ordinal]))
        trigger[1] = max(trigger[1], abs(gyroPad[GamepadAxis.RT.ordinal]))
        holdModeKeys()
        collectHaptics()
        for (i in deviceList.indices) deviceList[i].shownInSnapshot()
        anyDevice.shownInSnapshot()
        for (i in pulses.indices) pulses[i].seen = true
        for (a in areas) {
            if (a.since.isNotEmpty()) a.seen.addAll(a.since.keys)
            if (a.late.isNotEmpty()) a.seen.addAll(a.late.keys)
        }
        for (i in tapList.indices) {
            tapList[i].markSeen()
        }

        // Touchpad gestures (Alt+Tab, Ctrl zoom, Win shortcuts) hold keys like any binding.
        if (touchpad.key != 0) heldKeys[touchpad.key and 0xFF] = true
        var surfaceMods = 0
        for (s in surfaceList) {
            if (s.key != 0) heldKeys[s.key and 0xFF] = true
            surfaceMods = surfaceMods or s.keyModifiers
            mouseDx += s.dx; mouseDy += s.dy; wheel += s.wheel; hwheel += s.hwheel
            s.drainMotion()
        }
        var n = 0
        for (i in 0 until keyOrderSize) {
            val u = keyOrder[i]
            if (heldKeys[u]) keyOrder[n++] = u else inKeyOrder[u] = false
        }
        keyOrderSize = n
        for (u in 0 until 256) if (heldKeys[u] && !inKeyOrder[u]) {
            keyOrder[keyOrderSize++] = u; inKeyOrder[u] = true
        }
        var modifiers = 0
        val array = ArrayList<Int>(6)
        // Wi-Fi carries every key: a list of its own only once a seventh is held.
        var all: ArrayList<Int>? = null
        for (i in 0 until keyOrderSize) {
            val usage = keyOrder[i]
            if (HidUsage.isModifier(usage)) modifiers = modifiers or HidUsage.modifierBit(usage)
            else if (array.size < 6) array.add(usage)
            else (all ?: ArrayList(array).also { all = it }).add(usage)
        }
        modifiers = modifiers or touchpad.keyModifiers or surfaceMods

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
            mouseDx + touchpad.dx, mouseDy + touchpad.dy, wheel + touchpad.wheel, hwheel + touchpad.hwheel,
        )
        mouseDx = 0; mouseDy = 0; wheel = 0; hwheel = 0
        touchpad.drainMotion()
        return HidState(gamepad, KeyboardState(modifiers, array), mouse, all ?: array)
    }

    /**
     * Haptics of this snapshot: a tick that crossed half since the last one (up, down or either
     * way by its [HapticEdge]), a per-step one that moved to another step, and the held one pushed
     * furthest (by strength × level).
     */
    private fun collectHaptics() {
        hapticTicks.clear()
        var held: HapticFeel? = null
        var level = 0f
        for ((m, v) in hapticNow) {
            when (val t = m.target) {
                is OutputTarget.Haptic -> {
                    val feel = t.feel ?: eff.haptics
                    if (t.held) {
                        if (held == null || feel.strength * v > held.strength * level) {
                            held = feel; level = v
                        }
                    } else if (v >= PRESS_THRESHOLD && (hapticWas[m] ?: 0f) < PRESS_THRESHOLD &&
                        (t.edge ?: eff.hapticEdge) != HapticEdge.RELEASE
                    ) {
                        hapticTicks.add(feel)
                    }
                }

                is OutputTarget.HapticStep -> {
                    val pct = t.percent.coerceIn(
                        OutputTarget.HapticStep.MIN_STEP_PERCENT,
                        OutputTarget.HapticStep.MAX_STEP_PERCENT
                    )
                    val step = (v * 100f / pct + 1e-3f).toInt()
                    if (step != (hapticSteps.put(m, step) ?: 0)) hapticTicks.add(t.feel ?: eff.haptics)
                }

                else -> Unit
            }
        }
        // Ticks on release that dropped under half since the last snapshot (gone from hapticNow when at rest).
        for ((m, was) in hapticWas) {
            val t = m.target
            if (t is OutputTarget.Haptic && !t.held && was >= PRESS_THRESHOLD &&
                (hapticNow[m] ?: 0f) < PRESS_THRESHOLD && (t.edge ?: eff.hapticEdge) != HapticEdge.PRESS
            ) {
                hapticTicks.add(t.feel ?: eff.haptics)
            }
        }
        // Per-step ones back at 0 since the last snapshot tick on the way down too.
        if (hapticSteps.isNotEmpty()) {
            val it = hapticSteps.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in hapticNow) {
                    if (e.value != 0) hapticTicks.add((e.key.target as OutputTarget.HapticStep).feel ?: eff.haptics)
                    it.remove()
                }
            }
        }
        hapticDue = hapticTicks.isNotEmpty() || held != null || hapticHeld != null
        hapticHeld = held
        hapticLevel = level
        val w = hapticWas; hapticWas = hapticNow; hapticNow = w
    }

    /** Adds one source group's outputs; its stick axes sum, then merge by largest deflection. */
    private fun accumulate(ms: List<Mapping>, dev: Dev?) {
        groupStick.fill(0f)
        val device = dev?.device
        // A pad with an analog trigger may set its L2 / R2 key early: when this group also maps
        // that side's analog axis to a trigger, the axis wins and the key adds nothing to LT / RT.
        val pad = dev?.pad ?: -1
        val dupL2 = device?.analogLeftTrigger == true && pad in mapsLeftTriggerAxis.indices && mapsLeftTriggerAxis[pad]
        val dupR2 =
            device?.analogRightTrigger == true && pad in mapsRightTriggerAxis.indices && mapsRightTriggerAxis[pad]
        for (i in ms.indices) {
            val m = ms[i]
            val v = value(m.source, dev, output = true)
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
                    val src = m.source
                    val dup = src is InputSource.Key &&
                            ((dupL2 && src.keyCode == AndroidKey.BUTTON_L2) || (dupR2 && src.keyCode == AndroidKey.BUTTON_R2))
                    when (t.axis) {
                        GamepadAxis.LT -> if (!dup) trigger[0] = max(trigger[0], abs(scaled))
                        GamepadAxis.RT -> if (!dup) trigger[1] = max(trigger[1], abs(scaled))
                        else -> groupStick[t.axis.ordinal] += scaled
                    }
                }

                is OutputTarget.Key -> if (t.usage in 0..255) {
                    if (t.mode == KeyMode.HOLD) {
                        if (v >= PRESS_THRESHOLD) heldKeys[t.usage] = true
                    } else keyModePress(m, t, v >= PRESS_THRESHOLD)
                }
                is OutputTarget.Mouse -> if (v >= PRESS_THRESHOLD) accMouse = accMouse or (1 shl t.button.bit)
                // Mouse motion follows swipe steps as they come (swipeMove).
                // Surface motion, switching apps and zoom run in the touchpad (Touchpad.route).
                is OutputTarget.Wheel, is OutputTarget.MouseAxis,
                OutputTarget.GyroHold, OutputTarget.GyroToggle, OutputTarget.GyroPause,
                OutputTarget.SwitchApps, OutputTarget.Zoom, is OutputTarget.Layer, OutputTarget.NoOutput -> Unit

                is OutputTarget.Haptic, is OutputTarget.HapticStep -> {
                    val a = min(1f, abs(v))
                    if (a > HAPTIC_MIN_LEVEL) hapticNow[m] = max(a, hapticNow[m] ?: 0f)
                }
            }
        }
        for (a in 0 until 4) if (abs(groupStick[a]) > abs(stick[a])) stick[a] = groupStick[a]
    }

    /** A toggle or next-key [t] of mapping [m] seen [pressed] in a snapshot: a press flips it. */
    private fun keyModePress(m: Mapping, t: OutputTarget.Key, pressed: Boolean) {
        if (!pressed) {
            keyModeHeld.remove(m); return
        }
        if (!keyModeHeld.add(m)) return // still the same press
        when (t.mode) {
            KeyMode.TOGGLE -> if (!toggledKeys.remove(t.usage)) toggledKeys.add(t.usage)
            // Pressed again before it was used: it lets go.
            else -> if (nextKeys.remove(t.usage) == null) nextKeys[t.usage] = false
        }
    }

    /**
     * Holds the toggled keys and the next-key ones: one is held until another key that is neither
     * has been pressed, then until all of those are let go.
     */
    private fun holdModeKeys() {
        for (u in toggledKeys) heldKeys[u] = true
        if (nextKeys.isEmpty()) return
        var other = false
        for (u in 0 until 256) if (heldKeys[u] && u !in nextKeys && !HidUsage.isModifier(u)) {
            other = true; break
        }
        val it = nextKeys.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (other) e.setValue(true) else if (e.value) {
                it.remove(); continue
            }
            heldKeys[e.key] = true
        }
    }

    /** Whether [ms] maps analog axis [a] or [b] to a game pad trigger. */
    private fun mapsTriggerAxis(ms: List<Mapping>, a: Int, b: Int): Boolean = ms.any { m ->
        val src = m.source
        val t = m.target
        src is InputSource.Axis && (src.axis == a || src.axis == b) &&
                t is OutputTarget.PadAxis && (t.axis == GamepadAxis.LT || t.axis == GamepadAxis.RT)
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
                if (m.target == target && value(m.source, dev) >= PRESS_THRESHOLD) {
                    hit = true; break
                }
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
        for (t in eventsBefore) if (t is OutputTarget.Layer && t !in eventsAfter) layerReleased(t)
        if (anyCarried) pruneCarried()
    }

    /** Resolves due holds and tap releases outside an input change (snapshot, touchpad input). */
    private fun resolveIfDue(now: Long) {
        if (buttonsDue(now)) firingEvents(now) {}
    }

    private fun buttonsDue(now: Long): Boolean {
        for (i in pulses.indices) if (pulseDone(pulses[i], now)) return true
        for (i in tapList.indices) if (now >= tapDeadline(tapList[i])) return true
        for (a in areas) for ((id, t) in a.late) if (areaDone(a, id, t, now)) return true
        return false
    }

    private fun areaDone(a: AreaHolds, id: String, t: Long, now: Long) =
        id in a.seen && now - t >= touchpad.minPulseNanos

    /** When [t] next changes without input: a press turns long, a waiting tap fires, a pulse ends. */
    private fun tapDeadline(t: ButtonGestures): Long = t.deadline(touchpad.minPulseNanos)

    /** Due long presses, waiting taps and gesture pulses. */
    private fun resolveTaps(now: Long) {
        val minPulse = touchpad.minPulseNanos
        for (i in tapList.indices) tapList[i].resolve(now, minPulse)
    }

    /** Touch button [id] went down (its press count left 0). */
    private fun controlDown(id: String, now: Long) {
        tapStates[id]?.down(now, touchpad.minPulseNanos)
    }

    /** Touch button [id] let go (its press count reached 0). */
    private fun controlUp(id: String, now: Long) {
        tapStates[id]?.up(now, touchpad.minPulseNanos)
    }

    /** [source] is key [code], pressed or one of its gestures. */
    private fun mapsKey(source: InputSource, code: Int): Boolean =
        (source is InputSource.Key && source.keyCode == code) || (source is InputSource.KeyGesture && source.keyCode == code)

    /** The distinct targets [source] maps to in [mappings]. */
    private fun targetsOf(mappings: List<Mapping>, source: InputSource): Set<OutputTarget> =
        mappings.filter { it.source == source }.mapTo(HashSet()) { it.target }

    /** The keys of [mappings] with gesture mappings, and what each needs. */
    private fun keyGestureCfgs(mappings: List<Mapping>): Map<Int, KeyGestureCfg> {
        val codes = mappings.mapNotNullTo(HashSet()) { (it.source as? InputSource.KeyGesture)?.keyCode }
        return codes.associateWith { code ->
            val double = targetsOf(mappings, InputSource.KeyGesture(code, ButtonGesture.DOUBLE_TAP))
            KeyGestureCfg(
                hasDouble = double.isNotEmpty(),
                tapWaits = double.isNotEmpty() && double != targetsOf(
                    mappings,
                    InputSource.KeyGesture(code, ButtonGesture.TAP)
                ),
            )
        }
    }

    /** Key [code] of [d] went down or up: drives its gestures when it has gesture mappings. */
    private fun keyEdge(d: Dev, code: Int, pressed: Boolean, now: Long) {
        val cfg = (if (d === anyDevice) anyKeyCfgs else keyCfgs.getOrNull(d.pad))?.get(code) ?: return
        val g = d.gestures.getOrPut(code) {
            ButtonGestures(0, 0, cfg.hasDouble, cfg.tapWaits).also { keyTimes(code, it); tapList.add(it) }
        }
        if (pressed) g.down(now, touchpad.minPulseNanos) else g.up(now, touchpad.minPulseNanos)
    }

    /** Forgets [d]'s key gestures (it went away or became another device). */
    private fun dropGestures(d: Dev) {
        if (d.gestures.isEmpty()) return
        tapList.removeAll(d.gestures.values.toSet())
        d.gestures.clear()
    }

    private fun pulseDone(p: Pulse, now: Long) = p.seen && now - p.startNs >= touchpad.minPulseNanos

    /** Shown taps release; waiting presses and release pulses resolve. */
    private fun resolveButtons(now: Long) {
        resolveTaps(now)
        var i = 0
        while (i < pulses.size) {
            val p = pulses[i]
            if (pulseDone(p, now)) {
                pulses.removeAt(i); release(p.id)
            } else i++
        }
        for (a in areas) {
            if (a.late.isEmpty()) continue
            val iter = a.late.entries.iterator()
            while (iter.hasNext()) {
                val (id, t) = iter.next()
                if (areaDone(a, id, t, now)) {
                    iter.remove(); if (!a.since.containsKey(id)) a.seen.remove(id)
                }
            }
        }
    }

    private fun engagedEvents(into: HashSet<OutputTarget>) {
        into.clear()
        forEachGroup { ms, dev ->
            for (i in ms.indices) {
                val m = ms[i]
                val t = m.target
                if ((t is OutputTarget.Wheel || t == OutputTarget.GyroToggle || t is OutputTarget.Layer) && value(
                        m.source,
                        dev
                    ) >= PRESS_THRESHOLD
                ) into.add(t)
            }
        }
    }

    private fun onPress(t: OutputTarget) {
        when (t) {
            OutputTarget.GyroToggle -> gyroToggled = !gyroToggled
            is OutputTarget.Wheel -> wheel += t.step * MouseState.WHEEL_UNIT
            is OutputTarget.Layer -> layerPressed(t)
            else -> Unit
        }
    }

    // ---- Mapping sets ----

    /** The mapping set in use and the overrides laid over it, bottom to top. */
    data class Layers(val set: Int, val overrides: List<Int>)

    /** The mapping set in use and its overrides (Base with none until a layer action). */
    val layers: Layers get() = synchronized(lock) { Layers(layerSet, layerOverrides.toList()) }

    private fun layerPressed(t: OutputTarget.Layer) {
        if (t.set !in MappingSet.BASE..MappingSet.MAX) return
        carryHeld(t)
        when {
            t.override && t.whileHeld -> {
                val added = t.set !in layerOverrides
                if (added) layerOverrides.add(t.set)
                layerHolds[t] = LayerHold(layerSet, layerOverrides.toList(), added)
            }

            t.override -> if (!layerOverrides.remove(t.set)) layerOverrides.add(t.set)
            else -> {
                if (t.whileHeld) layerHolds[t] = LayerHold(layerSet, layerOverrides.toList(), false)
                layerSet = t.set
                layerOverrides.clear()
            }
        }
        applyLayers()
    }

    /** A "... hold" action let go: back to where it came from, unless a set change came since. */
    private fun layerReleased(t: OutputTarget.Layer) {
        val h = layerHolds.remove(t) ?: return
        carryHeld(t)
        if (t.override) {
            if (h.added) layerOverrides.remove(t.set)
        } else {
            // Holds made in this one's set (a later hold, or one to the same set) go back to where this one came from.
            var sameSetHeld = false
            for (e in layerHolds.entries) {
                val o = e.value
                if (e.key.override || o.set != t.set) continue
                e.setValue(LayerHold(h.set, h.overrides, o.added))
                if (e.key.set == t.set) sameSetHeld = true
            }
            if (layerSet == t.set && !sameSetHeld) {
                layerSet = h.set
                layerOverrides.clear(); layerOverrides.addAll(h.overrides)
            }
        }
        applyLayers()
    }

    /**
     * Before a set change by [t]: every held input remembers what it presses now. Its layer actions
     * always stay until it is released; its buttons, keys and clicks too, and the new set's mappings
     * for it wait for its release, unless it is a touch control that follows layers. The inputs holding [t]
     * itself always keep everything; holding a "... hold" action they also press what the new set
     * maps them to at once (all but its layer actions, which wait, so nothing flips back and forth).
     */
    private fun carryHeld(t: OutputTarget.Layer) {
        for (g in carried.indices) {
            val ms = if (g == 0) touchMappings else padMappings[g - 1]
            val bySource = HashMap<InputSource, ArrayList<Mapping>>()
            for (i in ms.indices) {
                val m = ms[i]
                if (groupHolds(g, m.source)) bySource.getOrPut(m.source) { ArrayList() }.add(m)
            }
            val was = carried[g]
            val now = HashMap<InputSource, Carried>()
            for ((src, list) in bySource) {
                val holder = list.any { it.target == t }
                val keep = holder || MappingProfiles.touchId(src) !in followLayerIds
                val kept = list.filter { carries(it, keep) }
                // A stick or trigger axis (or touch motion) held past half keeps nothing: it follows the new set.
                if (kept.isEmpty() && (!keep || continuous(src))) continue
                val before = was[src]
                val layersOnly = holder && t.whileHeld && (before == null || before.holdsLayers)
                now[src] = Carried(kept, keep || before?.holdsBack == true, layersOnly)
            }
            carried[g] = now
        }
        anyCarried = carried.any { it.isNotEmpty() }
    }

    /** A held input keeps [m] through a set change: its layer action always, a button-like output when it [keep]s. */
    private fun carries(m: Mapping, keep: Boolean): Boolean {
        val t = m.target
        if (t is OutputTarget.Layer) return true
        if (!keep || continuous(m.source)) return false
        return t is OutputTarget.Pad || t is OutputTarget.Key || t is OutputTarget.Mouse ||
                t is OutputTarget.PadAxis || t == OutputTarget.GyroPause || t == OutputTarget.GyroHold
    }

    /** An input whose value follows a motion (a full stick or trigger axis, touch motion): it follows the new set at once. */
    private fun continuous(s: InputSource): Boolean = when (s) {
        is InputSource.Axis -> s.half == AxisHalf.FULL
        is InputSource.TouchStick -> s.half == AxisHalf.FULL
        is InputSource.Surface -> s.input.motion
        is InputSource.Gyro -> true
        is InputSource.Tilt -> s.half == AxisHalf.FULL
        else -> false
    }

    /** Whether source [s] is held in group [g] (0 = touch, else 1 + pad index). */
    private fun groupHolds(g: Int, s: InputSource): Boolean {
        if (g == 0) return value(s, null) >= PRESS_THRESHOLD
        for (i in deviceList.indices) {
            val d = deviceList[i]
            if (d.pad == g - 1 && value(s, d) >= PRESS_THRESHOLD) return true
        }
        return value(s, anyDevice) >= PRESS_THRESHOLD
    }

    /** Carried inputs that let go take the set in use again. */
    private fun pruneCarried() {
        var changed = false
        for (g in carried.indices) {
            val c = carried[g]
            if (c.isEmpty()) continue
            val it = c.keys.iterator()
            while (it.hasNext()) if (!groupHolds(g, it.next())) {
                it.remove(); changed = true
            }
        }
        if (!changed) return
        anyCarried = carried.any { it.isNotEmpty() }
        applyLayers()
    }

    /**
     * Puts the mappings in use into every group: the set in use with its overrides, without the
     * carried inputs' new mappings they hold back, plus what they carry. Rebuilds what is compiled
     * from them: the touch surfaces, trigger duplicates and gesture waits.
     */
    private fun applyLayers() {
        fun inUse(all: List<Mapping>, c: HashMap<InputSource, Carried>?, keepNothing: Boolean = false): List<Mapping> {
            val ms = layered(all, layerSet, layerOverrides, keepNothing)
            if (c.isNullOrEmpty()) return ms
            val out = ms.filterNot { m ->
                val k = c[m.source] ?: return@filterNot false
                if (k.holdsLayers) m.target is OutputTarget.Layer else k.holdsBack
            } + c.values.flatMap { it.mappings }
            // A mapping the holder keeps and the new set also has (an override falling through) counts once.
            return if (c.values.any { it.holdsLayers }) out.distinct() else out
        }
        // Buttons take the touchpad's mappings in use of the inputs they map in none of the layers in use.
        val touchInUse = inUse(allTouch, carried.getOrNull(0), keepNothing = true)
        gyroInUse = touchInUse.filter { it.source is InputSource.Gyro }
        touchMappings = Inheritance.toButtons(touchInUse, buttonIds).filter { it.target != OutputTarget.NoOutput }
        padMappings = Array(allPads.size) { inUse(allPads[it], carried.getOrNull(it + 1)) }
        mapsLeftTriggerAxis =
            BooleanArray(padMappings.size) { mapsTriggerAxis(padMappings[it], AndroidAxis.LTRIGGER, AndroidAxis.BRAKE) }
        mapsRightTriggerAxis =
            BooleanArray(padMappings.size) { mapsTriggerAxis(padMappings[it], AndroidAxis.RTRIGGER, AndroidAxis.GAS) }
        // A diagonal zone only where the set in use maps it; else that zone presses its two halves.
        for (c in stickCfgs.values) c.diagonalMapped.fill(false)
        for (m in touchMappings) {
            val src = m.source
            if (src is InputSource.TouchStickDiagonal) stickCfgs[src.id]?.diagonalMapped?.set(src.diagonal.ordinal, true)
        }
        ratchetMapped = (padMappings.asSequence() + sequenceOf(touchMappings)).any { ms ->
            ms.any { it.target == OutputTarget.GyroPause || it.target == OutputTarget.GyroHold }
        }
        touchpad.map = SurfaceMap(touchMappings)
        for ((id, s) in surfaces) s.map = SurfaceMap(touchMappings, id)
        for ((id, st) in tapStates) {
            val double = targetsOf(touchMappings, InputSource.TouchDoubleTap(id))
            st.hasDouble = double.isNotEmpty()
            st.tapWaits = double.isNotEmpty() && double != targetsOf(touchMappings, InputSource.TouchTap(id))
        }
        keyCfgs = Array(padMappings.size) { keyGestureCfgs(padMappings[it]) }
        anyKeyCfgs = keyGestureCfgs(padMappings.flatMap { it })
        for (d in deviceList) for ((code, g) in d.gestures) {
            val cfg = keyCfgs.getOrNull(d.pad)?.get(code) ?: continue
            g.hasDouble = cfg.hasDouble; g.tapWaits = cfg.tapWaits
        }
        for ((code, g) in anyDevice.gestures) {
            val cfg = anyKeyCfgs[code] ?: continue
            g.hasDouble = cfg.hasDouble; g.tapWaits = cfg.tapWaits
        }
        applyLayerSettings()
    }

    /**
     * Puts the settings of the layers in use ([eff]) into effect: the touchpad's and the touchpad
     * buttons' surfaces, and the gesture times of touch buttons and keys without their own.
     */
    private fun applyLayerSettings() {
        val e = WorkspaceDefaults.layered(ws, layerSet, layerOverrides)
        eff = e
        touchpad.settings = e.touchpad
        touchpad.doubleTapNanos = e.doubleTapMs.coerceIn(Workspace.MIN_DOUBLE_TAP_MS, Workspace.MAX_DOUBLE_TAP_MS) * 1_000_000L
        touchpad.longPressNanos = e.longPressMs.coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS) * 1_000_000L
        for (c in ws.touchControls) {
            surfaces[c.id]?.let { s ->
                s.settings = c.surfaceSettings(e.touchpad)
                s.doubleTapNanos = c.doubleTap(e) * 1_000_000L; s.longPressNanos = c.longPress(e) * 1_000_000L
            }
            tapStates[c.id]?.let { st -> st.longNs = c.longPress(e) * 1_000_000L; st.doubleNs = c.doubleTap(e) * 1_000_000L }
        }
        for (d in deviceList) for ((code, g) in d.gestures) keyTimes(code, g)
        for ((code, g) in anyDevice.gestures) keyTimes(code, g)
    }

    /** Key [code]'s gesture times: its own from the pinned preset, else those in effect ([eff]). */
    private fun keyTimes(code: Int, g: ButtonGestures) {
        val own = ws.keyTimings.firstOrNull { it.keyCode == code }
        g.longNs = (own?.longPressMs ?: eff.longPressMs).coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS) * 1_000_000L
        g.doubleNs = (own?.doubleTapMs ?: eff.doubleTapMs).coerceIn(Workspace.MIN_DOUBLE_TAP_MS, Workspace.MAX_DOUBLE_TAP_MS) * 1_000_000L
    }

    // ---- Touch presses ----

    private fun press(id: String) {
        val n = touches[id] ?: 0
        touches[id] = n + 1
        if (n == 0) pressOrder.add(id)
        if (n == 0 && tapList.isNotEmpty()) controlDown(id, clock())
    }

    private fun release(id: String) {
        val n = touches[id] ?: return
        if (n > 1) {
            touches[id] = n - 1; return
        }
        touches.remove(id)
        pressOrder.remove(id)
        if (tapList.isNotEmpty()) controlUp(id, clock())
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
            is InputSource.Key, is InputSource.KeyGesture, is InputSource.Axis -> {
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

    /**
     * A source's value in one group: keys and axes from [dev] (none for the touch group). [output]:
     * for a snapshot's HID output, where every key edge since the last snapshot shows.
     */
    private fun value(source: InputSource, dev: Dev?, output: Boolean = false): Float = when (source) {
        is InputSource.Key -> if (dev != null && (if (output) dev.keyForOutput(source.keyCode) else dev.key(source.keyCode))) 1f else 0f
        is InputSource.KeyGesture -> if (dev?.gestures?.get(source.keyCode)?.on(source.gesture) == true) 1f else 0f
        is InputSource.Axis -> if (dev == null) 0f else half(dev.axis(source.axis), source.half)
        is InputSource.Touch -> if (touches.containsKey(source.id)) 1f else 0f
        is InputSource.TouchTap -> if (tapStates[source.id]?.on(ButtonGesture.TAP) == true) 1f else 0f
        is InputSource.TouchLongPress -> if (tapStates[source.id]?.on(ButtonGesture.LONG_PRESS) == true) 1f else 0f
        is InputSource.TouchRelease -> if (tapStates[source.id]?.on(ButtonGesture.RELEASE) == true) 1f else 0f
        is InputSource.TouchDoubleTap -> if (tapStates[source.id]?.on(ButtonGesture.DOUBLE_TAP) == true) 1f else 0f
        is InputSource.TouchArea -> if (areas[source.kind.ordinal].held(source.id)) 1f else 0f
        is InputSource.Surface ->
            if (source.id.isEmpty()) touchpad.level(source.fingers, source.input, source.zone)
            else surfaces[source.id]?.level(source.fingers, source.input, source.zone) ?: 0f

        is InputSource.TouchStick -> {
            val v = touchSticks[source.id]
            when {
                v == null -> 0f
                v.zoned && source.half != AxisHalf.FULL -> {
                    val bit = when (source.axis) {
                        StickAxis.X -> if (source.half == AxisHalf.POSITIVE) DIR_RIGHT else DIR_LEFT
                        StickAxis.Y -> if (source.half == AxisHalf.POSITIVE) DIR_DOWN else DIR_UP
                    }
                    if (v.dirs and bit != 0) v.mag else 0f
                }

                else -> half(if (source.axis == StickAxis.X) v.x else v.y, source.half)
            }
        }
        // Upgraded to surface inputs when the workspace loads (Bindings.upgradeSwipes).
        is InputSource.TouchSwipe -> 0f
        // Gyro motion goes out through gyroRoutes, not the snapshot's sources.
        is InputSource.Gyro -> 0f
        is InputSource.Tilt ->
            if (source.id.isNotEmpty() && !touches.containsKey(source.id)) 0f
            else half(if (source.axis == StickAxis.X) tiltX else tiltY, source.half)

        is InputSource.Shake ->
            if (source.id.isNotEmpty() && !touches.containsKey(source.id)) 0f
            else if (shakeAt != Long.MIN_VALUE && (!shakeShown || clock() - shakeAt < SHAKE_PULSE_NS)) 1f else 0f
        is InputSource.TouchStickDiagonal -> {
            val v = touchSticks[source.id]
            if (v != null && v.dirs and (DIR_DIAGONAL shl source.diagonal.ordinal) != 0) v.mag else 0f
        }
    }

    /** Directions of zoned stick [cfg] deflected to ([x], [y]), Y down. */
    private fun zoneDirs(x: Float, y: Float, cfg: StickCfg): Int {
        val angle = atan2(y, x).toDouble()
        return if (cfg.zones == StickZones.FOUR) {
            FOUR_ZONES[Math.floorMod((angle / (PI / 2)).roundToInt(), 4)]
        } else {
            val zone = Math.floorMod((angle / (PI / 4)).roundToInt(), 8)
            val diagonal = EIGHT_ZONE_DIAGONAL[zone]
            when {
                diagonal == null -> EIGHT_ZONES[zone]
                cfg.diagonalMapped[diagonal.ordinal] -> DIR_DIAGONAL shl diagonal.ordinal
                else -> EIGHT_ZONES[zone]
            }
        }
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

        /** How long a shake presses at least. */
        const val SHAKE_PULSE_NS = 120_000_000L

        /** A physical mouse mapped to scrolling: this many counts of movement make one wheel notch. */
        const val MOUSE_COUNTS_PER_NOTCH = 20f

        /** A held haptic is renewed at least this often (see [hapticSink]). */
        const val HAPTIC_RENEW_NS = 100_000_000L

        /** A source at or under this counts as not engaging its haptics (an axis at rest). */
        const val HAPTIC_MIN_LEVEL = 0.02f

        private const val DIR_RIGHT = 1
        private const val DIR_DOWN = 2
        private const val DIR_LEFT = 4
        private const val DIR_UP = 8

        /** Shifted left by a [StickDiagonal] ordinal. */
        private const val DIR_DIAGONAL = 16

        /** Zone 0 is right, then clockwise on screen (Y grows down). */
        private val FOUR_ZONES = intArrayOf(DIR_RIGHT, DIR_DOWN, DIR_LEFT, DIR_UP)

        /** The halves each of the 8 zones presses (a diagonal's two when it has no mapping of its own). */
        private val EIGHT_ZONES = intArrayOf(
            DIR_RIGHT, DIR_RIGHT or DIR_DOWN, DIR_DOWN, DIR_DOWN or DIR_LEFT,
            DIR_LEFT, DIR_LEFT or DIR_UP, DIR_UP, DIR_UP or DIR_RIGHT,
        )
        private val EIGHT_ZONE_DIAGONAL = arrayOf(
            null, StickDiagonal.DOWN_RIGHT, null, StickDiagonal.DOWN_LEFT,
            null, StickDiagonal.UP_LEFT, null, StickDiagonal.UP_RIGHT,
        )
        private const val AXIS_EPSILON = 1e-4f

        /** Android axis ids are 0..47 (MotionEvent.AXIS_GENERIC_16). */
        private const val AXIS_SLOTS = 64

        private const val MAX_BUTTON_FINGERS = 10

        private const val BP_FREE = 0

        /** [gyroAxes] bits. */
        const val GYRO_X = 1
        const val GYRO_Y = 2


        /** Pressed: a finger on a control without a surface. */
        private const val BP_PRESSED = 2

        /** Pressed, and a finger of the button's own surface. */
        private const val BP_SWIPE = 5

        /** Swipe distance (dp × sensitivity) that deflects a swipe axis fully as a floating stick. */
        const val SWIPE_FULL = 72f

        /** Wheel units (1/120 notch) per mouse count of swipe: a notch every 20 dp at sensitivity 1.5. */
        const val SWIPE_WHEEL_PER_COUNT = 4f

    }
}
