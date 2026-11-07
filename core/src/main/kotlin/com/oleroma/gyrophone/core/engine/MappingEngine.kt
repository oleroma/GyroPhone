package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.debug.DebugTrace
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.AxisStage
import com.oleroma.gyrophone.core.model.PhysicalSticks
import com.oleroma.gyrophone.core.model.StickTuning
import com.oleroma.gyrophone.core.model.TriggerTuning
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.HapticEdge
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KeyMode
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.StickDiagonal
import com.oleroma.gyrophone.core.model.StickZones
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.FingerAxisSettings
import com.oleroma.gyrophone.core.model.fingerOf
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.heightRatio
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.Triggers
import com.oleroma.gyrophone.core.model.heldSwipe
import com.oleroma.gyrophone.core.model.holdSlot
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.layered
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.HeldButton
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.surfaceSettings
import com.oleroma.gyrophone.core.net.OverlayFeed
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

    /** Per axis id: the tuning of the physical stick it is part of (null = raw), from the pinned preset ([Workspace.stickTunings]). */
    private val axisStick = arrayOfNulls<StickTuning>(AXIS_SLOTS)

    /** [axisStick] for a pad whose right stick is RX / RY ([PadDevice.rightStickRx]). */
    private val axisStickRx = arrayOfNulls<StickTuning>(AXIS_SLOTS)

    /** Per axis id: the tuning of the physical trigger it is part of (null = the defaults). */
    private val axisTrigger = arrayOfNulls<TriggerTuning>(AXIS_SLOTS)

    /**
     * The settings in effect: [ws]'s with the own settings of the layers in use laid over
     * ([WorkspaceDefaults.layered]); set by [applyLayers].
     */
    @Volatile
    private var eff: Workspace = ws

    /**
     * The gyro settings in effect now: the workspace's, or a layer's in use, with the sensitivities
     * of the last pressed button held that has its own ([TouchControl.gyro]) over them.
     */
    val gyroSettings: GyroSettings get() = gyroNow

    @Volatile
    private var gyroNow: GyroSettings = ws.gyro

    /** [gyroNow] without the Gyro sensitivity chips ([sensNow]). */
    private var gyroBase: GyroSettings = ws.gyro

    /**
     * Gyro sensitivity chips ([OutputTarget.GyroSens]) in effect, counts per degree by
     * [MotionAxis] ordinal (NONE = every axis); NaN = none. Set by [refreshSens].
     */
    private val sensNow = FloatArray(MotionAxis.entries.size) { Float.NaN }

    /** Some mapping in use is a Gyro sensitivity chip. */
    private var sensMapped = false

    /** The active Gyro sensitivity chips, by when they became active (the last activated wins). */
    private val sensOrder = HashMap<Mapping, Long>()
    private var sensSeq = 0L
    private val sensSeen = HashSet<Mapping>()

    /** [g] with the Gyro sensitivity chips in effect over it. */
    private fun withSens(g: GyroSettings): GyroSettings {
        if (!sensMapped && sensOrder.isEmpty()) return g
        var r = g
        val all = sensNow[MotionAxis.NONE.ordinal]
        if (!all.isNaN()) r = r.copy(
            sensitivity = all, yawSensitivity = null, pitchSensitivity = null, rollSensitivity = null,
            yawScale = null, pitchScale = null, rollScale = null,
        )
        val yaw = sensNow[MotionAxis.YAW.ordinal]
        if (!yaw.isNaN()) r = r.copy(yawSensitivity = yaw, yawScale = null)
        val pitch = sensNow[MotionAxis.PITCH.ordinal]
        if (!pitch.isNaN()) r = r.copy(pitchSensitivity = pitch, pitchScale = null)
        val roll = sensNow[MotionAxis.ROLL.ordinal]
        if (!roll.isNaN()) r = r.copy(rollSensitivity = roll, rollScale = null)
        return r
    }

    /**
     * Works out the Gyro sensitivity chips in effect from their inputs now ([OutputTarget.GyroSens]):
     * per axis the last activated with a range; [gyroNow] follows when they change. An "every
     * axis" chip activated after an axis's own wins over it there too.
     */
    private fun refreshSens() {
        if (!sensMapped && sensOrder.isEmpty()) return
        sensSeen.clear()
        val best = arrayOfNulls<Mapping>(MotionAxis.entries.size)
        val bestSeq = LongArray(MotionAxis.entries.size) { -1L }
        val vals = FloatArray(MotionAxis.entries.size)
        forEachGroup { ms, dev ->
            for (i in ms.indices) {
                val m = ms[i]
                val t = m.target as? OutputTarget.GyroSens ?: continue
                val r = m.range ?: continue
                val v = abs(axisOf(m, value(m.source, dev)))
                if (!activeOf(m.source, dev, v)) continue
                sensSeen.add(m)
                val seq = sensOrder.getOrPut(m) { ++sensSeq }
                val a = t.axis.ordinal
                if (seq > bestSeq[a]) {
                    bestSeq[a] = seq; best[a] = m; vals[a] = r.of(v)
                }
            }
        }
        if (sensOrder.size != sensSeen.size) sensOrder.keys.retainAll(sensSeen)
        // An axis's own chip counts only if it came after the every-axis one.
        val allSeq = bestSeq[MotionAxis.NONE.ordinal]
        var changed = false
        for (a in MotionAxis.entries) {
            val i = a.ordinal
            val v = if (best[i] == null || (a != MotionAxis.NONE && bestSeq[i] < allSeq)) Float.NaN
            else vals[i].coerceIn(MIN_SENS_CHIP, MAX_SENS_CHIP)
            if (v.toBits() != sensNow[i].toBits()) {
                sensNow[i] = v; changed = true
            }
        }
        if (changed) gyroNow = withSens(gyroBase)
    }

    /**
     * Finger position inputs ([InputSource.Finger]) by touch button id ("" = the touchpad): where
     * the first finger is, 0..1 from the bottom-left ([setFinger]), while it is down.
     */
    private class FingerState {
        var x = 0f
        var y = 0f

        /** Raw values by [FingerAxis] ordinal where the finger landed (Locked inputs). */
        val landed = FloatArray(FingerAxis.entries.size)
    }

    private val fingers = HashMap<String, FingerState>()

    /** The finger each touch button (and "" the touchpad) follows for its finger inputs: its first. */
    private val fingerPointer = HashMap<String, Long>()

    /** Touch buttons ("" = the touchpad) with finger inputs mapped in some set, and every button by id. */
    private var fingerMapped: Set<String> = emptySet()
    private var controlsById: Map<String, TouchControl> = emptyMap()

    // The pad area in px and whether it is in portrait ([setPadArea]), where buttons are placed.
    private var padW = 0f
    private var padH = 0f
    private var padPortrait = false

    /** The pad area is [w] × [h] px, in portrait or not: where finger positions on buttons are worked out. */
    fun setPadArea(w: Float, h: Float, portrait: Boolean) {
        synchronized(lock) { padW = w; padH = h; padPortrait = portrait }
    }

    /**
     * Finger [pointer] at [px], [py] (pad area px) on touch button [id] ("" = the touchpad): [phase]
     * 0 landed, 1 moved, 2 lifted. Only the first finger on it counts; its position goes into
     * [fingers], 0..1 from the bottom-left of the button (or the pad area), kept to its edges.
     */
    private fun fingerTrack(id: String, pointer: Long, px: Float, py: Float, phase: Int) {
        if (id !in fingerMapped) return
        when (phase) {
            0 -> if (fingerPointer.containsKey(id)) return else fingerPointer[id] = pointer
            1 -> if (fingerPointer[id] != pointer) return
            else -> {
                if (fingerPointer[id] != pointer) return
                fingerPointer.remove(id); fingers.remove(id)
                return
            }
        }
        if (padW <= 0f || padH <= 0f) return
        var x: Float
        var y: Float
        if (id.isEmpty()) {
            x = px / padW; y = 1f - py / padH
        } else {
            val c = controlsById[id] ?: return
            val half = c.placeSize(padPortrait) * min(padW, padH) / 2f
            val halfH = if (c.shape == TouchShape.CIRCLE) half else half * c.heightRatio
            if (half <= 0f || halfH <= 0f) return
            x = (px - (c.placeX(padPortrait) * padW - half)) / (2 * half)
            y = 1f - (py - (c.placeY(padPortrait) * padH - halfH)) / (2 * halfH)
        }
        x = x.coerceIn(0f, 1f); y = y.coerceIn(0f, 1f)
        val f = fingers[id] ?: FingerState().also { n ->
            fingers[id] = n
            for (a in FingerAxis.entries) n.landed[a.ordinal] = fingerRaw(a, x, y)
        }
        f.x = x; f.y = y
    }

    /** Finger input settings by touch button id ("" = the touchpad) and round buttons, from the workspace. */
    private var fingerCfgs: Map<String, List<FingerAxisSettings>> = emptyMap()
    private var roundIds: Set<String> = emptySet()

    /** Raw value 0..1 of axis [a] at [x], [y] (0..1 from the bottom-left). */
    private fun fingerRaw(a: FingerAxis, x: Float, y: Float): Float = when (a) {
        FingerAxis.X -> x
        FingerAxis.Y -> y
        FingerAxis.DISTANCE -> min(1f, hypot(x - 0.5f, y - 0.5f) * 2f)
        FingerAxis.ANGLE -> {
            val turn = (atan2(x - 0.5f, y - 0.5f) / (2 * PI)).toFloat()
            if (turn < 0f) turn + 1f else turn
        }
    }

    /** The value of finger input [s] now, 0..1; 0 when no finger is on it. */
    private fun fingerValue(s: InputSource.Finger): Float {
        val f = fingers[s.id] ?: return 0f
        if (s.axis.roundOnly && s.id !in roundIds) return 0f
        val cfg = (if (s.id.isEmpty()) eff.padFingers else fingerCfgs[s.id])?.fingerOf(s.axis) ?: FingerAxisSettings(s.axis)
        val raw = if (cfg.locked) f.landed[s.axis.ordinal] else fingerRaw(s.axis, f.x, f.y)
        return cfg.shape(raw)
    }

    /**
     * Input [src] (value [v] in group [dev]) is active for a chip's range ([Mapping.range]): a finger
     * input while its finger is down (0 included), any other while it is off its rest.
     */
    private fun activeOf(src: InputSource, dev: Dev?, v: Float): Boolean =
        if (src is InputSource.Finger) fingers.containsKey(src.id) && (!src.axis.roundOnly || src.id in roundIds)
        // A held swipe direction is active from the start of its zone (where its axis is 0).
        else if (src is InputSource.Surface && src.on == Trigger.HOLD && src.input.heldSwipe) value(src, dev) >= PRESS_THRESHOLD
        else abs(v) > RANGE_ACTIVE

    /** An output that takes an input's size (an axis), not just on or off. */
    private fun analogTarget(t: OutputTarget): Boolean = t is OutputTarget.PadAxis || t is OutputTarget.GyroSens ||
            t is OutputTarget.HapticStep || (t is OutputTarget.Haptic && t.held)

    /**
     * [v] (the level of mapping [m]'s source) as its axis output takes it: a held swipe direction
     * gives how far through its zone the finger is ([Touchpad.zoneLevel]) to an axis target.
     */
    private fun axisOf(m: Mapping, v: Float): Float {
        val s = m.source
        if (v == 0f || s !is InputSource.Surface || s.on != Trigger.HOLD || !s.input.heldSwipe || !analogTarget(m.target)) return v
        val pad = (if (s.id.isEmpty()) touchpad else surfaces[s.id]) ?: return v
        return pad.zoneLevel(s.fingers, s.input, s.zone) * v
    }

    /** [v] of mapping [m] through its range ([Mapping.range]), as its target takes it. */
    private fun ranged(m: Mapping, dev: Dev?, v: Float): Float {
        val r = m.range ?: return v
        if (!activeOf(m.source, dev, v)) return 0f
        val out = r.of(abs(v))
        return if (v < 0f) -out else out
    }

    /** Physical keys and buttons with settings of their own while held ([physHeldChanged]), and those held now. */
    private var heldPhys: List<HeldButton> = heldOf(ws)
    private val physDown = HashSet<String>()

    private fun heldOf(w: Workspace): List<HeldButton> {
        val mapped = physGyroOf(w).mapTo(HashSet()) { (it.source as InputSource.Gyro).id }
        return w.heldButtons.filter { b -> b.gyro.any { it.any } || HeldButton.GYRO_ID + b.id in mapped }
    }

    /** The physical devices' gyro mappings of [w] (a held button's, [HeldButton.GYRO_ID]). */
    private fun physGyroOf(w: Workspace): List<Mapping> =
        (w.pads.flatMap { it.mappings } + w.keyboard + w.mouse + w.phone).filter { m ->
            (m.source as? InputSource.Gyro)?.id?.startsWith(HeldButton.GYRO_ID) == true
        }.distinct()

    /** Sets [gyroNow] for the settings in effect and the buttons held. */
    private fun updateGyroNow() {
        val sets = buildList {
            add(MappingSet.BASE)
            if (layerSet != MappingSet.BASE) add(layerSet)
            for (o in layerOverrides) if (o != layerSet && o != MappingSet.BASE) add(o)
        }
        var held: ButtonGyro? = null
        var heldTouch: ButtonGyro? = null
        if (pressOrder.isNotEmpty() && (heldPhys.isNotEmpty() || ws.touchControls.any { it.gyro.isNotEmpty() })) for (id in pressOrder) {
            val g = if (id.startsWith(PHYS)) {
                heldPhys.firstOrNull { PHYS + it.id == id }?.gyroIn(sets) ?: continue
            } else {
                val c = ws.touchControls.firstOrNull { it.id == id } ?: continue
                if (c.gyro.isEmpty()) continue
                c.gyroIn(sets)
            }
            if (g.anyGyro) held = g
            if (g.anyTouch) heldTouch = g
        }
        gyroBase = held?.over(eff.gyro) ?: eff.gyro
        gyroNow = withSens(gyroBase)
        pcHeldGyro = if (heldPhys.isEmpty() || ws.pads.isEmpty()) emptyList() else heldPhys.mapNotNull { b ->
            if (!b.id.startsWith(PhysicalDevice.CONTROLLER.name + ":")) return@mapNotNull null
            val g = b.gyroIn(sets).takeIf { it.anyGyro } ?: return@mapNotNull null
            val place = b.sources.firstNotNullOfOrNull { src -> pcPlace(src).takeIf { it >= 0 } } ?: return@mapNotNull null
            // Over the settings in effect with the phone's own held button, if any: the PC's wins while held.
            place to g.over(gyroNow)
        }.distinctBy { it.first }
        val t = heldTouch?.over(eff.touchpad) ?: eff.touchpad
        if (t != touchpad.settings) touchpad.settings = t
        // Every change of the buttons held or the mappings in use comes through here.
        routesCache = null
    }

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

    /** The sets in use before the last layer change: where [OutputTarget.LayerBack] goes. */
    private var backLayers: Layers? = null

    /** The touch group's mappings with options: their shapers and cycle turns ([shaped]). */
    private val touchShapers = HashMap<Mapping, Shaper>()
    private val touchCycles = HashMap<CycleKey, CycleTurn>()

    /** A cycle of mappings: one source in one set. */
    private data class CycleKey(val source: InputSource, val set: Int)

    /** Each cycle mapping in use: its cycle, its place in it and the cycle's size. */
    private class CycleInfo(val key: CycleKey, val index: Int, val size: Int)

    private var cycleInfo: Map<Mapping, CycleInfo> = emptyMap()

    /**
     * The double tap chips told apart by how the second touch goes ([Shaper.Split]): those of a
     * double tap source with a Held chip and another chip. Held ones fire on a hold
     * or a move, the others on a quick lift.
     */
    private var splitInfo: Map<Mapping, Shaper.Split> = emptyMap()

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

    /** A gyro ratchet mapping in use: the input it is part of ([sameInput]) with its target, and the [gyroBit]s it flips. */
    private class Ratchet(val m: Mapping, val key: Pair<InputSource, OutputTarget>, val bits: Int)

    /** The ratchet mappings in use of each group, like [touchMappings] and [padMappings] ([ratchetedAxes]). */
    private var touchRatchets: List<Ratchet> = emptyList()
    private var padRatchets: Array<List<Ratchet>> = emptyArray()

    /** The mappings in use with an event target ([engagedEvents]) of each group, and whether there are any. */
    private var touchEvents: List<Mapping> = emptyList()
    private var padEvents: Array<List<Mapping>> = emptyArray()
    private var eventsMapped = false

    /** The gyro mappings in use ([InputSource.Gyro]), [OutputTarget.NoOutput] kept: the workspace's and every button's. */
    private var gyroInUse: List<Mapping> = emptyList()

    /** [gyroRoutes] as last worked out; null after the mappings in use or the buttons held changed. */
    private var routesCache: List<Mapping>? = null

    /** Game pad axes the gyro deflects ([setGyroAxes]), by [GamepadAxis] ordinal. */
    private val gyroPad = FloatArray(GamepadAxis.entries.size)

    /** The phone's tilt ([setTilt]), -1..1: sideways (+ right) and forward / back (+ back). */
    private var tiltX = 0f
    private var tiltY = 0f

    /**
     * The last shake ([shake]) presses: from [shakeAt] for at least [SHAKE_PULSE_NS] and one
     * snapshot. It ends in [resolveButtons], so an event it pressed sees it let go.
     */
    private var shakeOn = false
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

        /** Mappings with options of this device's group: their shapers and cycle turns ([shaped]). */
        val shapers = HashMap<Mapping, Shaper>()
        val cycles = HashMap<CycleKey, CycleTurn>()

        /** Its toggle / next-key mappings whose source was held at the last snapshot ([keyModePress]). */
        val modeHeld = HashSet<Mapping>()

        /** A snapshot ran its mappings ([accumulate]); one that skipped them saw its key modes let go. */
        var modeSeen = false
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
            modeHeld.clear()
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
    /**
     * Gestures of the touch controls' Inside, Outside and (a stick's) On touch that map one, by
     * [areaKey]: the same as a button's own touch, on its long press and double tap times.
     */
    private val areaGestures = HashMap<String, ButtonGestures>()

    private fun areaKey(id: String, kind: TouchAreaKind) = kind.ordinal.toString() + ":" + id
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

    /**
     * The touch group's toggle / next-key key mappings whose source was held at the last snapshot
     * ([keyModePress]); each device keeps its own ([Dev.modeHeld]).
     */
    private val keyModeHeld = HashSet<Mapping>()

    /** Keys a toggle mapping has turned on, by usage. */
    private val toggledKeys = BooleanArray(256)

    /** Keys held only by toggles and next-keys this snapshot ([holdModeKeys]): not another input. */
    private val modeOnlyKeys = BooleanArray(256)

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

    // Declared before init, which builds them.
    /**
     * The controller's gyro buttons in use, for a controller plugged into the PC ("Use PC
     * controller for gyro"): one entry per button, `(place shl 8) or kind`, the place an
     * [com.oleroma.gyrophone.core.net.OverlayFeed] PAD_ bit index, the kind the [gyroBit]s its
     * ratchets flip plus [PC_GYRO_TOGGLE] for a Toggle gyro. Only plain presses count (no tap,
     * long press or other gesture). Rebuilt with the layers in use.
     */
    @Volatile var pcGyroButtons: IntArray = IntArray(0)
        private set

    /**
     * "Use PC controller for gyro ratchet": the controller buttons (PAD_ place) with gyro settings of
     * their own while held ([HeldButton]), each with the gyro settings in effect while it is held.
     * The PC uses the last pressed of them that is held, over what the phone sends.
     */
    @Volatile var pcHeldGyro: List<Pair<Int, GyroSettings>> = emptyList()
        private set

    /** The touchpad's settings in effect now, a held button's own over them (tests). */
    internal val touchpadSettingsNow: TouchpadSettings get() = touchpad.settings

    /** Goes up each time a Toggle gyro press stops counting (the workspace or its gyro changed): a PC controller's toggle resets too. */
    @Volatile var toggleEpoch: Int = 0
        private set

    private var mouseDx = 0
    private var mouseDy = 0

    /** The part of [mouseDx] and [mouseDy] the live gyro sent ([addMouseMotion] fromGyro). */
    private var gyroDx = 0
    private var gyroDy = 0

    /** Mouse counts of the last snapshot other than the live gyro's, for [cameraSink]. */
    private var cameraDx = 0
    private var cameraDy = 0

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

    /**
     * Called when a [OutputTarget.BlackScreen] mapping fires, inside the engine lock: it must only
     * hand the request on (the phone's pad screen goes black), never call back into the engine.
     */
    @Volatile
    var onBlackScreen: Runnable? = null

    /**
     * Gets the mouse counts each [snapshot] sends other than the live gyro's (touchpad and its
     * glide, touch buttons, controllers, keys, a gyro glide), on the pump's thread and outside the
     * engine lock: what turns the camera besides the gyro (a trackball 3D shape adds them).
     */
    @Volatile
    var cameraSink: ((dx: Int, dy: Int) -> Unit)? = null

    /**
     * The debug log while debugging is on (null otherwise, which costs nothing): input edges with
     * what they map to in the set in use, workspace and layer changes, resets.
     */
    @Volatile
    var trace: DebugTrace? = null
        set(value) {
            field = value
            if (value != null) synchronized(lock) { tracedLayers = ""; traceLayers(value) }
        }

    /** The layers last logged, so only a change is. */
    private var tracedLayers = ""

    private fun traceLayers(t: DebugTrace) {
        val d = buildString {
            append(Describe.setName(layerSet))
            for (o in layerOverrides) if (o != layerSet && o != MappingSet.BASE) append(" + ").append(Describe.setName(o))
        }
        if (d == tracedLayers) return
        tracedLayers = d
        t.state("layer: " + d + (ws.sets.firstOrNull { it.id == layerSet && it.name.isNotBlank() }?.let { " (${it.name})" } ?: ""))
    }

    /** " → targets" of the mappings in [ms] whose source [match]es, for the debug log. */
    private fun traceTargets(ms: List<Mapping>?, match: (InputSource) -> Boolean): String {
        val hits = ms?.filter { match(it.source) }.orEmpty()
        if (hits.isEmpty()) return " → (not mapped)"
        return " → " + hits.joinToString(", ") { m ->
            val gesture = when (val src = m.source) {
                is InputSource.KeyGesture -> src.gesture.label.lowercase() + ": "
                is InputSource.TouchTap -> "tap: "
                is InputSource.TouchDoubleTap -> "double tap: "
                is InputSource.TouchLongPress -> "long press: "
                is InputSource.TouchRelease -> "release: "
                else -> ""
            }
            gesture + Describe.target(m.target)
        }
    }

    private fun traceKey(t: DebugTrace, who: String, keyCode: Int, pressed: Boolean, ms: List<Mapping>?) {
        val name = AndroidKey.names[keyCode] ?: "key $keyCode"
        t.input("$who $name " + (if (pressed) "down" else "up") +
                if (pressed) traceTargets(ms) { mapsKey(it, keyCode) } else "")
    }

    private fun traceTouch(t: DebugTrace, id: String, what: String, withTargets: Boolean) {
        val label = ws.touchControls.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() } ?: id
        t.input("touch \"$label\" $what" + if (withTargets) traceTargets(touchMappings) { src ->
            when (src) {
                is InputSource.Touch -> src.id == id
                is InputSource.TouchTap -> src.id == id
                is InputSource.TouchDoubleTap -> src.id == id
                is InputSource.TouchLongPress -> src.id == id
                is InputSource.TouchRelease -> src.id == id
                else -> false
            }
        } else "")
    }

    /** Loads the active workspace and releases everything held. Under the lock. */
    private fun applyWorkspace() {
        val loaded = profile.workspace(workspaceIndex)
        // The touchpad mappings it does not set come from the Defaults.
        val w = loaded.copy(mappings = Bindings.upgradeSwipes(Inheritance.workspace(loaded, profile.defaults)))
        ws = w
        heldPhys = heldOf(w)
        for (id in physDown) pressOrder.remove(PHYS + id)
        physDown.clear()
        axisStick.fill(null); axisStickRx.fill(null); axisTrigger.fill(null)
        for ((id, axes) in PhysicalSticks.sticks) w.stickTunings[id]?.let { axisStick[axes.first] = it; axisStick[axes.second] = it }
        for ((id, axes) in PhysicalSticks.sticksRx) w.stickTunings[id]?.let { axisStickRx[axes.first] = it; axisStickRx[axes.second] = it }
        for ((id, axes) in PhysicalSticks.triggers) w.triggerTunings[id]?.let { axisTrigger[axes.first] = it; axisTrigger[axes.second] = it }
        // A physical button's gyro mappings while held (its Motion tab) route with the touch buttons' ([gyroRoutes]).
        val physGyro = physGyroOf(w)
        allTouch = if (physGyro.isEmpty()) w.mappings else w.mappings + physGyro
        trace?.state("workspace: ${w.name}")
        motionMapped = w.mappings.any { it.source is InputSource.Tilt || it.source is InputSource.Shake }
        allPads = Array(w.pads.size + 3) {
            when {
                it < w.pads.size -> w.pads[it].mappings
                it == w.pads.size -> w.keyboard
                it == w.pads.size + 1 -> w.mouse
                else -> w.phone
            }.let { l -> if (physGyro.isEmpty()) l else l.filterNot { m -> m.source is InputSource.Gyro } }
        }
        touchMappings = allTouch
        padMappings = allPads
        buttonIds = w.touchControls.filter { it.kind == TouchKind.BUTTON }.map { it.id }
        followLayerIds = w.touchControls.filter { it.followLayers }.mapTo(HashSet()) { it.id }
        fingerCfgs = w.touchControls.filter { it.fingers.isNotEmpty() }.associate { it.id to it.fingers }
        controlsById = w.touchControls.associateBy { it.id }
        fingerMapped = w.mappings.mapNotNullTo(HashSet()) { (it.source as? InputSource.Finger)?.id }
        roundIds = w.touchControls.filter { it.kind == TouchKind.BUTTON && it.shape == TouchShape.CIRCLE }.mapTo(HashSet()) { it.id }
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
                tapWaits = double.isNotEmpty() && shapesOf(w.mappings, InputSource.TouchDoubleTap(c.id)) != shapesOf(w.mappings, InputSource.TouchTap(c.id)),
            )
            tapStates[c.id] = st; tapList.add(st)
        }
        areaGestures.clear()
        for (k in w.mappings.mapNotNull { (it.source as? InputSource.TouchArea)?.takeIf { a -> a.on != Trigger.HOLD }?.copy(on = Trigger.HOLD) }.distinct()) {
            val c = w.touchControls.firstOrNull { it.id == k.id }
            val double = targetsOf(w.mappings, k.copy(on = Trigger.DOUBLE_TAP))
            val st = ButtonGestures(
                (c?.longPress(w) ?: w.longPressMs) * 1_000_000L, (c?.doubleTap(w) ?: w.longPressMs) * 1_000_000L,
                hasDouble = double.isNotEmpty(),
                tapWaits = double.isNotEmpty() && shapesOf(w.mappings, k.copy(on = Trigger.DOUBLE_TAP)) != shapesOf(w.mappings, k.copy(on = Trigger.TAP)),
            )
            areaGestures[areaKey(k.id, k.kind)] = st; tapList.add(st)
        }
        stickCfgs = w.touchControls.filter { it.kind == TouchKind.STICK }.associate { c ->
            c.id to StickCfg(c.deadzone.coerceIn(0f, TouchControl.MAX_DEADZONE), c.zones, BooleanArray(StickDiagonal.entries.size))
        }
        releaseHeld()
        for (d in deviceList) d.pad = resolvePad(d.device)
        layerSet = MappingSet.BASE; layerOverrides.clear(); layerHolds.clear(); backLayers = null
        carried = Array(allPads.size + 1) { HashMap() }; anyCarried = false
        applyLayers()
        touchpad.cancel(clock())
    }

    /** Forgets every held input (keys, axes, touches, sticks, fingers, pulses, latches). */
    private fun releaseHeld() {
        for (d in deviceList) d.clear()
        anyDevice.clear()
        touches.clear(); pressOrder.clear(); physDown.clear(); setTouchHeld.clear(); touchSticks.clear()
        fingers.clear(); fingerPointer.clear(); sensOrder.clear(); sensNow.fill(Float.NaN)
        updateGyroNow()
        for (s in surfaceList) s.reset()
        for (a in areas) a.clear()
        hapticWas.clear(); hapticNow.clear(); hapticSteps.clear()
        bpState.fill(BP_FREE); bpControl.fill(null)
        pulses.clear()
        keyModeHeld.clear(); toggledKeys.fill(false); nextKeys.clear()
        shakeOn = false; shakeShown = true
        clearShapers()
        for (t in tapList) t.reset()
        gyroToggled = false
        toggleEpoch++
        keyOrderSize = 0; inKeyOrder.fill(false)
    }

    private fun resolvePad(device: PadDevice?): Int {
        if (device == null) return -1
        if (device.keyboard) return ws.pads.size
        if (device.mouse) return ws.pads.size + 1
        if (device.phone) return ws.pads.size + 2
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
                if (was != pressed) trace?.let { traceKey(it, device.name + ":", keyCode, pressed, padMappings.getOrNull(d.pad)) }
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
                trace?.let { traceTouch(it, id, "down at ${x.toInt()},${y.toInt()}", withTargets = true) }
                press(id)
                fingerTrack(id, pointer, x, y, 0)
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
                if (slot < 0) return@firingEvents
                fingerTrack(bpControl[slot]!!, pointer, x, y, 1)
                if (bpState[slot] != BP_SWIPE) return@firingEvents
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
                trace?.let { traceTouch(it, id, "up after ${(now - bpDownNs[slot]) / 1_000_000} ms", withTargets = false) }
                fingerTrack(id, pointer, 0f, 0f, 2)
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
                trace?.let { traceTouch(it, id, "cancelled by the system", withTargets = false) }
                fingerTrack(id, pointer, 0f, 0f, 2)
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

    /** Swipe button [id]'s finger offset from its locked start point (dp, [Touchpad.holdOffset]), for its swipe line. */
    fun swipeOffset(id: String, out: FloatArray): Boolean = synchronized(lock) { surfaces[id]?.holdOffset(out) ?: false }

    /**
     * Touch button [id]'s Move X / Y, for its Move rulers: writes their travel ([Touchpad.moveTravelX],
     * px) to [out] (x, y) and returns the Moves mapped ([Touchpad.MOVES_X], [Touchpad.MOVES_Y]), with
     * [MOVES_GLIDING] while inertia carries them on; 0 = no Move mapped.
     */
    fun moveTrack(id: String, out: FloatArray): Int = synchronized(lock) {
        val s = surfaces[id] ?: return 0
        val mapped = s.movesMapped()
        if (mapped == 0) return 0
        out[0] = s.moveTravelX; out[1] = s.moveTravelY
        mapped or if (s.isGliding) MOVES_GLIDING else 0
    }

    /**
     * The touch mappings in use now: the mapping set and overrides in use, with what inputs held
     * through a set change still press instead (for labels that show what is really active).
     */
    val touchMappingsInUse: List<Mapping> get() = synchronized(lock) { touchMappings }

    /** Those of [mappings] whose input is on now (at least half way), under one lock: for live mapping labels. */
    fun activeMappings(mappings: List<Mapping>): List<Mapping> = synchronized(lock) {
        mappings.filter { abs(sourceValue(it.source)) >= 0.5f }
    }

    /**
     * Every input held now, for the debug panel: touch controls pressed (by label), then each
     * device's held keys ("Gamepad: A"), then touchpad fingers.
     */
    fun heldInputs(): List<String> = synchronized(lock) {
        val out = ArrayList<String>()
        for (id in LinkedHashSet(pressOrder).apply { addAll(touches.keys) }) {
            out.add(ws.touchControls.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() } ?: id)
        }
        for (d in deviceList + anyDevice) {
            var code = d.keys.nextSetBit(0)
            while (code >= 0) {
                val name = AndroidKey.names[code] ?: "key $code"
                out.add((d.device?.name?.let { "$it: " } ?: "") + name)
                code = d.keys.nextSetBit(code + 1)
            }
        }
        val fingers = touchpad.fingers
        if (fingers > 0) out.add("touchpad $fingers finger" + if (fingers > 1) "s" else "")
        out
    }

    /** Touch controls currently pressed (including by touchpad-over-buttons commits and zones), for highlighting. */
    fun pressedTouches(): Set<String> = synchronized(lock) {
        resolveIfDue(clock())
        val out = HashSet(touches.keys)
        val zone = areas[TouchAreaKind.ZONE.ordinal]
        out.addAll(zone.since.keys); out.addAll(zone.late.keys)
        out
    }

    /**
     * [pressedTouches] and [latchedTouches] in one lock trip, into the caller's own sets (cleared
     * first), for the 60 Hz PC overlay send.
     */
    fun touchesInto(pressed: MutableSet<String>, latched: MutableSet<String>) {
        synchronized(lock) {
            resolveIfDue(clock())
            pressed.clear(); latched.clear()
            pressed.addAll(touches.keys)
            val zone = areas[TouchAreaKind.ZONE.ordinal]
            pressed.addAll(zone.since.keys); pressed.addAll(zone.late.keys)
            for ((id, s) in surfaces) if (s.latched) latched.add(id)
        }
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
                if (was != pressed) trace?.let { traceKey(it, "any device:", keyCode, pressed, padMappings.flatMap { ms -> ms }) }
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
                    if (setTouchHeld.put(id, now) == null) {
                        trace?.let { traceTouch(it, id, "down", withTargets = true) }
                        press(id)
                    }
                } else {
                    setTouchHeld.remove(id) ?: return@firingEvents
                    trace?.let { traceTouch(it, id, "up", withTargets = false) }
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
                val g = areaGestures[areaKey(id, kind)]
                if (held) {
                    if (!a.since.containsKey(id)) {
                        a.since[id] = a.late.remove(id) ?: now
                        g?.down(now, touchpad.minPulseNanos)
                    }
                } else {
                    val t = a.since.remove(id) ?: return@firingEvents
                    g?.up(now, touchpad.minPulseNanos)
                    if (id !in a.seen || now - t < touchpad.minPulseNanos) a.late[id] = t else a.seen.remove(id)
                }
            }
        }
        onChange?.run()
    }

    /** Lets go every [InputSource.TouchArea] at once (pad left, layout changed), without pulses. */
    fun touchAreasCancel() {
        synchronized(lock) {
            firingEvents(clock()) {
                for (a in areas) a.clear()
                for (g in areaGestures.values) g.reset()
            }
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
     * The first finger on touch button [id] ("" = the workspace touchpad) is at [x], [y]: 0..1 from
     * its bottom-left, kept in the button (a finger slid off stays at the nearest edge). The first
     * call after [fingerUp] is where it landed ([FingerAxisSettings.locked]).
     */
    fun setFinger(id: String, x: Float, y: Float) {
        synchronized(lock) {
            val cx = x.coerceIn(0f, 1f)
            val cy = y.coerceIn(0f, 1f)
            val f = fingers[id]
            if (f != null && f.x == cx && f.y == cy) return
            firingEvents(clock()) {
                val st = f ?: FingerState().also { n ->
                    fingers[id] = n
                    for (a in FingerAxis.entries) n.landed[a.ordinal] = fingerRaw(a, cx, cy)
                }
                st.x = cx; st.y = cy
            }
        }
        onChange?.run()
    }

    /** The finger [setFinger] followed on [id] lifted: its finger inputs go inactive. */
    fun fingerUp(id: String) {
        synchronized(lock) {
            if (!fingers.containsKey(id)) return
            firingEvents(clock()) { fingers.remove(id) }
        }
        onChange?.run()
    }

    /** Where the finger on [id] is now (0..1 from its bottom-left) into [out], or false when none is. */
    fun fingerAt(id: String, out: FloatArray): Boolean = synchronized(lock) {
        val f = fingers[id] ?: return false
        out[0] = f.x; out[1] = f.y
        true
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

    /**
     * Relative mouse motion and scrolling from the gyro, already in mouse counts and wheel units
     * (touchpad motion has its own path); [fromGyro]: the live gyro sent it, not a glide or a measure.
     */
    fun addMouseMotion(dx: Int, dy: Int, wheel: Int = 0, hwheel: Int = 0, fromGyro: Boolean = false) {
        synchronized(lock) {
            mouseDx += dx
            mouseDy += dy
            if (fromGyro) {
                gyroDx += dx
                gyroDy += dy
            }
            this.wheel += wheel
            this.hwheel += hwheel
        }
        onChange?.run()
    }

    /**
     * Where gyro motion goes now: the workspace's gyro mappings in use, each axis a held button
     * maps taken over by that button's (the last pressed of several).
     */
    fun gyroRoutes(): List<Mapping> = synchronized(lock) {
        routesCache ?: buildRoutes().also { routesCache = it }
    }

    private fun buildRoutes(): List<Mapping> {
        val ms = gyroInUse
        if (ms.isEmpty()) return emptyList()
        var out = ms.filter { (it.source as InputSource.Gyro).id.isEmpty() }
        for (id in pressOrder) {
            val own = ms.filter { (it.source as InputSource.Gyro).id == id }
            if (own.isEmpty()) continue
            val axes = own.mapTo(HashSet()) { (it.source as InputSource.Gyro).axis }
            out = out.filterNot { (it.source as InputSource.Gyro).axis in axes } + own
        }
        return out.filter { it.target != OutputTarget.NoOutput }
    }

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
            val now = clock()
            // A shake still pressing ends first, so this one is a new press of its events.
            if (shakeOn) firingEvents(now) { shakeOn = false }
            firingEvents(now) { shakeOn = true; shakeAt = now; shakeShown = false }
            trace?.input("shake")
        }
        onChange?.run()
    }

    /** The shake's press has shown in a snapshot and lasted its pulse. */
    private fun shakeDone(now: Long) = shakeOn && shakeShown && now - shakeAt >= SHAKE_PULSE_NS

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
            trace?.input("touchpad finger $pointer down at ${x.toInt()},${y.toInt()}")
            touchpad.down(pointer, x, y, now, eventNs = eventNanos)
            if ("" in fingerMapped) { fingerTrack("", pointer, x, y, 0); refreshSens() }
        }
        onChange?.run()
    }

    fun touchpadMove(pointer: Long, x: Float, y: Float, eventNanos: Long = clock()) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            touchpad.move(pointer, x, y, now, eventNanos)
            if ("" in fingerMapped) { fingerTrack("", pointer, x, y, 1); refreshSens() }
        }
        onChange?.run()
    }

    fun touchpadUp(pointer: Long) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            trace?.input("touchpad finger $pointer up")
            touchpad.up(pointer, now)
            if ("" in fingerMapped) { fingerTrack("", pointer, 0f, 0f, 2); refreshSens() }
        }
        onChange?.run()
    }

    /** Ends any touchpad gesture (pad left, rotated, profile changed). */
    fun touchpadCancel() {
        synchronized(lock) {
            touchpad.cancel(clock())
            if (fingers.remove("") != null) { fingerPointer.remove(""); refreshSens() }
        }
        onChange?.run()
    }

    /** Removes one background touchpad finger the system cancelled: no click, tap, fling or held gesture key. */
    fun touchpadCancel(pointer: Long) {
        synchronized(lock) {
            val now = clock()
            resolveIfDue(now)
            trace?.input("touchpad finger $pointer cancelled by the system")
            touchpad.cancelFinger(pointer, now)
            if ("" in fingerMapped) { fingerTrack("", pointer, 0f, 0f, 2); refreshSens() }
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
        for (a in areas) if (a.late.isNotEmpty()) for ((id, t) in a.late) if (id in a.seen) d = min(d, t + touchpad.minPulseNanos)
        d = min(d, shapersDeadline(clock()))
        // A shake's press ends by itself: a snapshot then lets it go.
        if (shakeOn) d = min(d, shakeAt + SHAKE_PULSE_NS)
        // A held haptic is renewed from snapshots, so they keep coming while it is held.
        if (hapticHeld != null) d = min(d, clock() + HAPTIC_RENEW_NS)
        d
    }

    /**
     * Whether gyro moves anything now. A ratchet (a held [OutputTarget.GyroPause], old
     * [OutputTarget.GyroHold] or one axis's [OutputTarget.GyroPauseAxis]) pauses an axis in
     * "ratchet disables" (ALWAYS) and engages one in "ratchet enables" (OFF) while held; each
     * further ratchet active on that axis flips it again (two cancel out, [ratchetedAxes]). A
     * [OutputTarget.GyroToggle] press swaps the two until the workspace changes. SENSOR_OFF: never.
     */
    val gyroActive: Boolean get() = gyroAxes != 0

    /** The gyro axes that move now: [gyroBit] of each [MotionAxis]. */
    val gyroAxes: Int
        get() = synchronized(lock) {
            val g = gyroNow
            if (g.mode == GyroMode.SENSOR_OFF) {
                ratchetsHeld = 0; return@synchronized 0
            }
            // Without ratchet mappings in use none is held, even one held when they went away.
            if (!ratchetMapped) ratchetHeldBits = 0
            val ratchets = if (ratchetMapped) ratchetedAxes() else 0
            ratchetsHeld = ratchetHeldBits
            var out = 0
            for (a in MOTION_AXES) {
                // On without a ratchet: "ratchet disables" (ALWAYS) unless toggled, "ratchet enables" only toggled.
                val base = if (g.modeOf(a) == GyroMode.ALWAYS) !gyroToggled else gyroToggled
                if (base != (ratchets and gyroBit(a) != 0)) out = out or gyroBit(a)
            }
            out
        }

    private fun pcGyroButtonsOf(ms: List<Mapping>): IntArray {
        val kinds = LinkedHashMap<Int, Int>()
        val seen = HashSet<Pair<Int, OutputTarget>>()
        for (m in ms) {
            val t = m.target
            val kind = when (t) {
                OutputTarget.GyroPause, OutputTarget.GyroHold -> ALL_GYRO
                is OutputTarget.GyroPauseAxis -> gyroBit(t.axis)
                OutputTarget.GyroToggle -> PC_GYRO_TOGGLE
                else -> continue
            }
            val place = pcPlace(m.source)
            if (place < 0 || !seen.add(place to t)) continue
            // Two ratchets on one button cancel on an axis, like on the phone; two toggles count as one.
            val k = kinds[place] ?: 0
            kinds[place] = if (kind == PC_GYRO_TOGGLE) k or kind else k xor kind
        }
        for (b in heldPhys) {
            if (!b.id.startsWith(PhysicalDevice.CONTROLLER.name + ":") || b.gyro.none { it.anyGyro }) continue
            val place = b.sources.firstNotNullOfOrNull { src -> pcPlace(src).takeIf { it >= 0 } } ?: continue
            kinds[place] = (kinds[place] ?: 0) or PC_GYRO_SETTINGS
        }
        return kinds.map { (place, kind) -> (place shl 8) or kind }.toIntArray()
    }

    /** The PAD_ bit index of a controller press source, -1 for anything else. */
    private fun pcPlace(src: InputSource): Int {
        val bit = when (src) {
            is InputSource.Key -> OverlayFeed.padBit(src.keyCode)
            is InputSource.Axis -> when (src.axis) {
                AndroidAxis.LTRIGGER, AndroidAxis.BRAKE -> OverlayFeed.PAD_L2
                AndroidAxis.RTRIGGER, AndroidAxis.GAS -> OverlayFeed.PAD_R2
                AndroidAxis.HAT_X -> when (src.half) {
                    AxisHalf.NEGATIVE -> OverlayFeed.PAD_LEFT
                    AxisHalf.POSITIVE -> OverlayFeed.PAD_RIGHT
                    AxisHalf.FULL -> 0
                }
                AndroidAxis.HAT_Y -> when (src.half) {
                    AxisHalf.NEGATIVE -> OverlayFeed.PAD_UP
                    AxisHalf.POSITIVE -> OverlayFeed.PAD_DOWN
                    AxisHalf.FULL -> 0
                }
                else -> 0
            }
            else -> 0
        }
        return if (bit == 0) -1 else Integer.numberOfTrailingZeros(bit)
    }

    /** The [gyroBit]s of the axes some held ratchet acts on (flipped or not), as of the last [gyroAxes] read. */
    @Volatile var ratchetsHeld: Int = 0
        private set

    /** [ratchetedAxes]' axes with any ratchet held. */
    private var ratchetHeldBits = 0

    /** The ratchets [ratchetedAxes] counted: each one's group and [Ratchet.key], in two reused lists. */
    private val ratchetSeenDevs = ArrayList<Dev?>()
    private val ratchetSeenKeys = ArrayList<Pair<InputSource, OutputTarget>>()

    /** [s] as the one input it is part of: a trigger's pull axes are its key (they map together). */
    private fun sameInput(s: InputSource): InputSource = when {
        s is InputSource.Axis && s.stage != AxisStage.NONE -> s.copy(axis = when (s.axis) {
            AndroidAxis.BRAKE -> AndroidAxis.LTRIGGER
            AndroidAxis.GAS -> AndroidAxis.RTRIGGER
            else -> s.axis
        })
        s is InputSource.Axis && (s.axis == AndroidAxis.LTRIGGER || s.axis == AndroidAxis.BRAKE) -> InputSource.Key(AndroidKey.BUTTON_L2)
        s is InputSource.Axis && (s.axis == AndroidAxis.RTRIGGER || s.axis == AndroidAxis.GAS) -> InputSource.Key(AndroidKey.BUTTON_R2)
        else -> s
    }

    /**
     * The [gyroBit]s of the axes flipped by the ratchets active now: each active ratchet mapping
     * flips its axes, so an odd number of them on an axis flips it. Several triggers of one
     * button each count (a press ratchets, a swipe on it ratchets back).
     */
    private fun ratchetedAxes(): Int {
        var bits = 0
        var held = 0
        ratchetSeenDevs.clear(); ratchetSeenKeys.clear()
        val now = clock()
        forEachGroup(touchRatchets, padRatchets) { rs, dev ->
            for (i in rs.indices) {
                val r = rs[i]
                // One input's sources (a trigger's axes and its key) count once.
                if (out(r.m, dev, now) < PRESS_THRESHOLD || ratchetSeen(dev, r.key)) continue
                ratchetSeenDevs.add(dev); ratchetSeenKeys.add(r.key)
                bits = bits xor r.bits; held = held or r.bits
            }
        }
        ratchetHeldBits = held
        return bits
    }

    private fun ratchetSeen(dev: Dev?, key: Pair<InputSource, OutputTarget>): Boolean {
        for (i in ratchetSeenKeys.indices) if (ratchetSeenDevs[i] === dev && ratchetSeenKeys[i] == key) return true
        return false
    }

    /** The [gyroBit]s ratchet target [t] flips, 0 for any other target. */
    private fun ratchetBits(t: OutputTarget): Int = when (t) {
        OutputTarget.GyroPause, OutputTarget.GyroHold -> ALL_GYRO
        is OutputTarget.GyroPauseAxis -> gyroBit(t.axis)
        else -> 0
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
            toggleEpoch++
            applyLayerSettings()
        }
        onChange?.run()
    }

    /** Releases everything and goes back to Base, e.g. when the profile changes or the host disconnects. */
    fun reset() {
        synchronized(lock) {
            trace?.state("reset: everything released")
            releaseHeld()
            layerSet = MappingSet.BASE; layerOverrides.clear(); layerHolds.clear(); backLayers = null
            for (c in carried) c.clear()
            anyCarried = false
            applyLayers()
            mouseDx = 0; mouseDy = 0; wheel = 0; hwheel = 0; gyroDx = 0; gyroDy = 0
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
        var dx = 0
        var dy = 0
        val s = synchronized(lock) {
            snapshotLocked().also {
                dx = cameraDx; dy = cameraDy
            }
        }
        // The gyro's part of the motion is what the camera moves did not make.
        trace?.output(s, s.mouse.dx - dx, s.mouse.dy - dy)
        if (hapticDue) {
            hapticDue = false
            hapticSink?.haptics(hapticTicks, hapticHeld, hapticLevel)
        }
        if (dx != 0 || dy != 0) cameraSink?.invoke(dx, dy)
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
        // A device skipped (nothing held) holds no key mode press.
        for (i in deviceList.indices) {
            val d = deviceList[i]
            if (!d.modeSeen) d.modeHeld.clear()
            d.modeSeen = false
        }
        if (!anyDevice.modeSeen) anyDevice.modeHeld.clear()
        anyDevice.modeSeen = false
        shakeShown = true
        for (a in 0 until 4) if (abs(gyroPad[a]) > abs(stick[a])) stick[a] = gyroPad[a]
        trigger[0] = max(trigger[0], abs(gyroPad[GamepadAxis.LT.ordinal]))
        trigger[1] = max(trigger[1], abs(gyroPad[GamepadAxis.RT.ordinal]))
        holdModeKeys()
        endOnNextInput(now)
        endOnSame(now)
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
        cameraDx = mouse.dx - gyroDx; cameraDy = mouse.dy - gyroDy
        mouseDx = 0; mouseDy = 0; wheel = 0; hwheel = 0; gyroDx = 0; gyroDy = 0
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
        val now = clock()
        dev?.modeSeen = true
        for (i in ms.indices) {
            val m = ms[i]
            val raw = axisOf(m, out(m, dev, now, output = true))
            val v = if (m.range == null) raw else ranged(m, dev, raw)
            if (v >= PRESS_THRESHOLD) {
                if (m.options?.endsOnNextInput == true) {
                    val sh = shaperOf(m, dev)
                    if (sh.options.mode == How.TOGGLE && sh.toggledOn(now)) stickies.add(sh, m.target)
                }
                // A swipe direction's own outputs are not another input for its latch.
                if (latchChip(m.source)) stickies.add(null, m.target)
                sameSeen(m, dev, now)
            }
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
                    } else keyModePress(m, t, v >= PRESS_THRESHOLD, dev?.modeHeld ?: keyModeHeld)
                }
                is OutputTarget.Mouse -> if (v >= PRESS_THRESHOLD) accMouse = accMouse or (1 shl t.button.bit)
                // Mouse motion follows swipe steps as they come (swipeMove).
                // Surface motion, switching apps and zoom run in the touchpad (Touchpad.route).
                is OutputTarget.Wheel, is OutputTarget.MouseAxis,
                OutputTarget.GyroHold, OutputTarget.GyroToggle, OutputTarget.GyroPause, is OutputTarget.GyroPauseAxis,
                OutputTarget.SwitchApps, OutputTarget.Zoom, is OutputTarget.Layer, OutputTarget.LayerBack,
                OutputTarget.LetGo, OutputTarget.BlackScreen, OutputTarget.NoOutput -> Unit

                // The gyro reads it on every sample ([refreshSens]).
                is OutputTarget.GyroSens -> Unit

                is OutputTarget.Haptic, is OutputTarget.HapticStep -> {
                    val a = min(1f, abs(v))
                    if (a > HAPTIC_MIN_LEVEL) hapticNow[m] = max(a, hapticNow[m] ?: 0f)
                }
            }
        }
        for (a in 0 until 4) if (abs(groupStick[a]) > abs(stick[a])) stick[a] = groupStick[a]
    }

    /**
     * A toggle or next-key [t] of mapping [m] seen [pressed] in a snapshot: a press flips it.
     * [held] is the group's ([Dev.modeHeld]), so another device that does not hold it does not end the press.
     */
    private fun keyModePress(m: Mapping, t: OutputTarget.Key, pressed: Boolean, held: HashSet<Mapping>) {
        if (!pressed) {
            held.remove(m); return
        }
        if (!held.add(m)) return // still the same press
        when (t.mode) {
            KeyMode.TOGGLE -> toggledKeys[t.usage] = !toggledKeys[t.usage]
            // Pressed again before it was used: it lets go.
            else -> if (nextKeys.remove(t.usage) == null) nextKeys[t.usage] = false
        }
    }

    /**
     * Holds the toggled keys and the next-key ones: one is held until another key that is neither
     * has been pressed, then until all of those are let go.
     */
    private fun holdModeKeys() {
        // Another key is one some other mapping holds: looked for before the toggled keys are added.
        var other = false
        if (nextKeys.isNotEmpty()) for (u in 0 until 256) if (heldKeys[u] && !HidUsage.isModifier(u) && u !in nextKeys) {
            other = true; break
        }
        for (u in 0 until 256) {
            modeOnlyKeys[u] = toggledKeys[u] && !heldKeys[u]
            if (toggledKeys[u]) heldKeys[u] = true
        }
        if (nextKeys.isEmpty()) return
        val it = nextKeys.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (other) e.setValue(true) else if (e.value) {
                it.remove(); continue
            }
            if (!heldKeys[e.key]) modeOnlyKeys[e.key] = true
            heldKeys[e.key] = true
        }
    }

    /**
     * Toggles that are on, each with what it presses (shaper null: only what a swipe direction
     * presses). Kept in two lists that are reused, so a snapshot allocates nothing for them.
     */
    private class Stickies {
        val shapers = ArrayList<Shaper?>()
        val targets = ArrayList<OutputTarget>()
        val size: Int get() = shapers.size
        fun isNotEmpty() = shapers.isNotEmpty()
        fun add(shaper: Shaper?, target: OutputTarget) {
            shapers.add(shaper); targets.add(target)
        }

        fun clear() {
            shapers.clear(); targets.clear()
        }
    }

    /** This snapshot's toggles that end on the next other input ([accumulate]). */
    private val stickies = Stickies()

    /** [endOnNextInput]'s keys of the toggles, reused. */
    private val stickyKeys = BooleanArray(256)

    /** A swipe latch that ends on the next input saw another one pressed. */
    private var latchNextUsed = false

    /**
     * Toggles set to end on the next input ([MapOptions.endsOnNextInput], sticky keys): once
     * another key (not a modifier), mouse button or pad button has been pressed, they end as it is
     * let go, in the same report. Swipe latches set so end the same way.
     */
    private fun endOnNextInput(now: Long) {
        val latch = touchpad.latchEndsOnNext || surfaceList.any { it.latchEndsOnNext }
        var anyToggle = false
        for (i in 0 until stickies.size) if (stickies.shapers[i] != null) anyToggle = true
        if (!anyToggle && !latch) {
            latchNextUsed = false; stickies.clear(); return
        }
        val keys = stickyKeys
        keys.fill(false)
        var pad = 0
        var mouse = 0
        for (i in 0 until stickies.size) when (val t = stickies.targets[i]) {
            is OutputTarget.Key -> if (t.usage in 0..255) keys[t.usage] = true
            is OutputTarget.Pad -> pad = pad or (1 shl t.button.bit)
            is OutputTarget.Mouse -> mouse = mouse or (1 shl t.button.bit)
            else -> Unit
        }
        var other = (accButtons and pad.inv()) != 0 || (accMouse and mouse.inv()) != 0 ||
                accUp || accDown || accLeft || accRight
        // Keys only toggles and next-keys hold are not another input either.
        if (!other) for (u in 0 until 256) if (heldKeys[u] && !keys[u] && !modeOnlyKeys[u] && !HidUsage.isModifier(u)) {
            other = true; break
        }
        for (i in 0 until stickies.size) {
            val sh = stickies.shapers[i] ?: continue
            if (other) sh.nextUsed = true
            else if (sh.nextUsed) {
                sh.letGo(now)
                if (!sh.on(now)) when (val t = stickies.targets[i]) {
                    is OutputTarget.Key -> if (t.usage in 0..255) heldKeys[t.usage] = false
                    is OutputTarget.Pad -> accButtons = accButtons and (1 shl t.button.bit).inv()
                    is OutputTarget.Mouse -> accMouse = accMouse and (1 shl t.button.bit).inv()
                    else -> Unit
                }
            }
        }
        stickies.clear()
        if (latch) {
            if (other) latchNextUsed = true
            else if (latchNextUsed) {
                latchNextUsed = false
                touchpad.endLatch(letGo = false)
                for (sf in surfaceList) sf.endLatch(letGo = false)
            }
        }
    }

    /**
     * Outputs pressed by mappings that are not toggles watching for them ([MapOptions.endsOnSame]):
     * now and at the last snapshot. Lists (few outputs are pressed at once), so adding allocates nothing.
     */
    private var othersNow = ArrayList<OutputTarget>()
    private var othersBefore = ArrayList<OutputTarget>()

    /** Toggles that are on and end when their output is pressed elsewhere; toggles of other mappings that are on. */
    private val sameWatchers = Stickies()
    private val onToggles = Stickies()

    /** What swipe latches that end on their own output pressed elsewhere press. */
    private val latchSameTargets = ArrayList<OutputTarget>()

    /** Mapping [m] (of [dev]'s group) presses its output now: notes it for [endOnSame]. */
    private fun sameSeen(m: Mapping, dev: Dev?, now: Long) {
        val o = m.options
        val toggle = o != null && o.mode == How.TOGGLE
        if (toggle && o!!.endsOnTap && latchChip(m.source)) {
            // A swipe latch's chip: held while latched.
            if (o!!.endsOnSame) latchSameTargets.add(m.target)
            return
        }
        if (toggle) {
            val sh = shaperOf(m, dev)
            if (sh.toggledOn(now)) {
                if (o!!.endsOnSame) {
                    sameWatchers.add(sh, m.target); return
                }
                onToggles.add(sh, m.target)
            }
        }
        othersNow.add(m.target)
    }

    /**
     * Toggles set to end on the same output ([MapOptions.endsOnSame]) end when another mapping
     * starts pressing it (not one that already pressed it when the toggle came on). A toggle of that
     * other mapping that came on with that press ends too, so the press only turns the output off.
     */
    private fun endOnSame(now: Long) {
        if (sameWatchers.isNotEmpty() || latchSameTargets.isNotEmpty()) {
            for (i in 0 until sameWatchers.size) {
                val sh = sameWatchers.shapers[i]!!
                val target = sameWatchers.targets[i]
                if (sh.sameArmed && target in othersNow && target !in othersBefore) {
                    sh.letGo(now)
                    for (j in 0 until onToggles.size) {
                        val t = onToggles.shapers[j]!!
                        if (onToggles.targets[j] == target && !t.sameArmed) t.letGo(now)
                    }
                }
                sh.sameArmed = true
            }
            var latchEnds = false
            for (i in latchSameTargets.indices) {
                val t = latchSameTargets[i]
                if (t in othersNow && t !in othersBefore) latchEnds = true
            }
            if (latchEnds) {
                touchpad.endLatchOnSame()
                for (sf in surfaceList) sf.endLatchOnSame()
            }
        }
        // A toggle seen on: the next snapshot's press of its output is a new one.
        for (i in 0 until onToggles.size) onToggles.shapers[i]!!.sameArmed = true
        sameWatchers.clear(); onToggles.clear(); latchSameTargets.clear()
        val b = othersBefore; othersBefore = othersNow; othersNow = b; othersNow.clear()
    }

    /** Whether [ms] maps analog axis [a] or [b] to a game pad trigger. */
    private fun mapsTriggerAxis(ms: List<Mapping>, a: Int, b: Int): Boolean = ms.any { m ->
        val src = m.source
        val t = m.target
        src is InputSource.Axis && src.stage == AxisStage.NONE && (src.axis == a || src.axis == b) &&
                t is OutputTarget.PadAxis && (t.axis == GamepadAxis.LT || t.axis == GamepadAxis.RT)
    }

    /**
     * Runs [block] for each source group: the touch mappings over the touch state, each attached
     * device over its pad's mappings, and the "any device" source over every pad.
     */
    private inline fun forEachGroup(block: (List<Mapping>, Dev?) -> Unit) = forEachGroup(touchMappings, padMappings, block)

    /** [forEachGroup] over lists made from the mappings in use: [touch] for the touch group, [pads] by pad index. */
    private inline fun <T> forEachGroup(touch: List<T>, pads: Array<List<T>>, block: (List<T>, Dev?) -> Unit) {
        block(touch, null)
        var now = Long.MIN_VALUE
        for (i in deviceList.indices) {
            val d = deviceList[i]
            if (d.pad < 0 || d.pad >= pads.size) continue
            val ms = pads[d.pad]
            if (ms.isEmpty()) continue
            // A toggle (or a delay, a minimum hold) goes on after the device's inputs are let go.
            if (d.active || (d.shapers.isNotEmpty() && busy(d, if (now == Long.MIN_VALUE) clock().also { now = it } else now))) {
                block(ms, d)
            }
        }
        if (anyDevice.active || (anyDevice.shapers.isNotEmpty() && busy(anyDevice, clock()))) for (ms in pads) block(ms, anyDevice)
    }

    /** Some shaper of [d] is on or changes by itself after [now]. */
    private fun busy(d: Dev, now: Long): Boolean = d.shapers.values.any { it.busy(now) }

    /** Whether some group holds a source mapped to [target]. */
    private fun engaged(target: OutputTarget): Boolean {
        var hit = false
        forEachGroup { ms, dev ->
            if (!hit) for (i in ms.indices) {
                val m = ms[i]
                if (m.target == target && out(m, dev, clock()) >= PRESS_THRESHOLD) {
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
        engagedEvents(eventsBefore, now, before = true)
        resolveButtons(now)
        change()
        engagedEvents(eventsAfter, now, before = false)
        if (eventsAfter.isNotEmpty()) for (t in eventsAfter) if (t !in eventsBefore) onPress(t)
        if (eventsBefore.isNotEmpty()) for (t in eventsBefore) if (t is OutputTarget.Layer && t !in eventsAfter) layerReleased(t)
        if (anyCarried) pruneCarried()
        if (heldPhys.isNotEmpty()) physHeldChanged()
        refreshSens()
    }

    /**
     * Physical keys and buttons with settings of their own while held ([Workspace.heldButtons]),
     * and which of them are held: one going down or up joins or leaves [pressOrder] (as
     * [PHYS] + its id), so the last pressed of them and the touch buttons wins.
     */

    private fun physHeldChanged() {
        var changed = false
        for (b in heldPhys) {
            var on = false
            for (src in b.sources) {
                for (i in deviceList.indices) {
                    val d = deviceList[i]
                    if (d.pad >= 0 && value(src, d) >= PRESS_THRESHOLD) { on = true; break }
                }
                if (on) break
            }
            if (on == (b.id in physDown)) continue
            changed = true
            if (on) { physDown.add(b.id); pressOrder.add(PHYS + b.id) } else { physDown.remove(b.id); pressOrder.remove(PHYS + b.id) }
        }
        if (changed) updateGyroNow()
    }

    /** Resolves due holds and tap releases outside an input change (snapshot, touchpad input). */
    private fun resolveIfDue(now: Long) {
        if (buttonsDue(now)) firingEvents(now) {}
    }

    private fun buttonsDue(now: Long): Boolean {
        for (i in pulses.indices) if (pulseDone(pulses[i], now)) return true
        for (i in tapList.indices) if (now >= tapDeadline(tapList[i])) return true
        for (a in areas) if (a.late.isNotEmpty()) for ((id, t) in a.late) if (areaDone(a, id, t, now)) return true
        if (shakeDone(now)) return true
        return shapersDeadline(now) <= now
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

    /** [source]'s targets with their How ([MapOptions.shape]): a tap and its double tap do the same when these match. */
    private fun shapesOf(mappings: List<Mapping>, source: InputSource): Set<Pair<OutputTarget, Pair<How, Int>>> =
        mappings.filter { it.source == source }.mapTo(HashSet()) { it.target to (it.options ?: MapOptions()).shape }

    /** The keys of [mappings] with gesture mappings, and what each needs. */
    private fun keyGestureCfgs(mappings: List<Mapping>): Map<Int, KeyGestureCfg> {
        val codes = mappings.mapNotNullTo(HashSet()) { (it.source as? InputSource.KeyGesture)?.keyCode }
        return codes.associateWith { code ->
            val double = targetsOf(mappings, InputSource.KeyGesture(code, ButtonGesture.DOUBLE_TAP))
            KeyGestureCfg(
                hasDouble = double.isNotEmpty(),
                tapWaits = double.isNotEmpty() && shapesOf(mappings, InputSource.KeyGesture(code, ButtonGesture.DOUBLE_TAP)) !=
                    shapesOf(mappings, InputSource.KeyGesture(code, ButtonGesture.TAP)),
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
        if (shakeDone(now)) shakeOn = false
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

    /**
     * The event targets engaged now. [before]: as the last call left them for mappings with
     * options (their output also changes with time, which must show as an edge), else now.
     */
    private fun engagedEvents(into: HashSet<OutputTarget>, now: Long, before: Boolean) {
        into.clear()
        if (!eventsMapped) return
        forEachGroup(touchEvents, padEvents) { ms, dev ->
            for (i in ms.indices) {
                val m = ms[i]
                val t = m.target
                val on = if (!shaped(m)) value(m.source, dev) >= PRESS_THRESHOLD else {
                    val sh = shaperOf(m, dev)
                    if (before) sh.eventOn else (out(m, dev, now) >= PRESS_THRESHOLD).also { sh.eventOn = it }
                }
                if (on) into.add(t)
            }
        }
    }

    /** [t] fires when it is pressed ([onPress]) rather than being held. */
    private fun isEvent(t: OutputTarget) = t is OutputTarget.Wheel || t == OutputTarget.GyroToggle || t is OutputTarget.Layer ||
            t == OutputTarget.LayerBack || t == OutputTarget.LetGo || t == OutputTarget.BlackScreen

    /** [m]'s shaper in [dev]'s group (the touch group for null). */
    private fun shaperOf(m: Mapping, dev: Dev?): Shaper =
        (dev?.shapers ?: touchShapers).getOrPut(m) { Shaper(shaperOptions(m), derivedTrigger(m.source)) }

    /**
     * [m]'s options as its shaper runs them: an On swipe chip's Toggle that also ends on a tap is
     * the surface's latch ([SurfaceMap.latches]), which keeps the direction held, so the chip itself
     * is Held. Without Tap it is a plain Toggle: the next swipe that way turns it off.
     */
    private fun shaperOptions(m: Mapping): MapOptions {
        val o = m.options ?: return MapOptions()
        val s = m.source
        return if (o.mode == How.TOGGLE && o.endsOnTap && latchChip(s)) o.copy(how = How.HELD, toggle = false) else o
    }

    /** [s] is an On swipe chip of a swipe direction (any zone): a Toggle on it latches the surface. */
    private fun latchChip(s: InputSource) = s is InputSource.Surface && s.on == Trigger.HOLD && s.input.heldSwipe

    /** A swipe trigger the engine works out from the swipe itself ([InputSource.Surface.on]); HOLD = none. */
    private fun derivedTrigger(s: InputSource): Trigger = when (s) {
        is InputSource.Surface -> s.on
        else -> Trigger.HOLD
    }

    /** [m]'s output goes through a [Shaper]: it has options, a swipe trigger, or is a told-apart double tap chip ([splitInfo]). */
    private fun shaped(m: Mapping): Boolean = m.options != null || derivedTrigger(m.source) != Trigger.HOLD || m in splitInfo

    /** [s] is a double tap held from its second press (not a swipe's double swipe, which the engine works out itself). */
    private fun isDoubleTap(s: InputSource): Boolean = when (s) {
        is InputSource.Surface -> s.on == Trigger.HOLD && s.input == SurfaceInput.DOUBLE_TAP
        else -> derivedTrigger(s) == Trigger.HOLD && Triggers.of(s) == Trigger.DOUBLE_TAP
    }

    /**
     * [m]'s output value in [dev]'s group at [now]: its source's value, or with options that
     * shaped as a press (1 or 0) by its toggle, turbo, cycle and delays.
     */
    private fun out(m: Mapping, dev: Dev?, now: Long, output: Boolean = false): Float {
        val src = m.source
        val derived = derivedTrigger(src)
        val v = value(src, dev, output)
        val split = splitInfo[m] ?: Shaper.Split.NONE
        val o = m.options ?: if (derived == Trigger.HOLD && split == Shaper.Split.NONE) return v else NO_OPTIONS
        var on = abs(v) >= PRESS_THRESHOLD
        val sh = shaperOf(m, dev)
        sh.split = split
        if (split != Shaper.Split.NONE) {
            // The second touch: quick (a lift within the Tap time, unmoved) or a hold.
            val surface = when (val b = Triggers.base(src)) {
                is InputSource.Surface -> if (b.id.isEmpty()) touchpad else surfaces[b.id]
                is InputSource.Touch -> surfaces[b.id]
                else -> null
            }
            sh.splitNs = surface?.longPressNanos ?: touchpad.longPressNanos
            sh.moved = surface?.pastTapSlop == true
        }
        if (derived == Trigger.RELEASE && src is InputSource.Surface) {
            // A swipe's Release: the last finger lifted in its zone (not sliding on, not leaving the direction).
            val s = src
            val pad = if (s.id.isEmpty()) touchpad else surfaces[s.id]
            val d = s.input.holdSlot
            on = false
            if (pad != null && d >= 0) {
                if (sh.liftSeq != Long.MIN_VALUE && pad.liftSeq != sh.liftSeq) on = pad.liftedIn(s.fingers, d, s.zone)
                sh.liftSeq = pad.liftSeq
            }
        }
        if (o.cycle) cycleInfo[m]?.let { c ->
            val turn = (dev?.cycles ?: touchCycles).getOrPut(c.key) { CycleTurn() }
            turn.update(on, c.size)
            on = on && turn.turn == c.index
        }
        if (src is InputSource.Surface) {
            val surface = if (src.id.isEmpty()) touchpad else surfaces[src.id]
            if (surface != null) {
                sh.longNs = surface.longPressNanos; sh.doubleNs = surface.doubleTapNanos
            }
        }
        sh.pulseNs = touchpad.minPulseNanos
        if (o.endsOnTap && o.mode == How.TOGGLE) sh.touched(touchedNow(src, dev), now)
        sh.update(on, now)
        return if (sh.on(now)) 1f else 0f
    }

    /** The button or surface [src] is a trigger of is touched (pressed) now. */
    private fun touchedNow(src: InputSource, dev: Dev?): Boolean {
        val b = Triggers.base(src)
        if (b is InputSource.Surface) return (if (b.id.isEmpty()) touchpad else surfaces[b.id])?.touching == true
        if (b is InputSource.Touch) surfaces[b.id]?.let { return it.touching }
        return abs(value(b, dev)) >= PRESS_THRESHOLD
    }

    /** Every toggle that is on and [ends][MapOptions.endsOnLetGo] on a Let go ends, and so do such swipe latches. */
    private fun letGoAll(now: Long) {
        fun go(shapers: Map<Mapping, Shaper>) {
            for ((m, sh) in shapers) {
                val o = m.options ?: continue
                if (o.mode == How.TOGGLE && o.endsOnLetGo) sh.letGo(now)
            }
        }
        go(touchShapers)
        for (d in deviceList) go(d.shapers)
        go(anyDevice.shapers)
        touchpad.endLatch(letGo = true)
        for (sf in surfaceList) sf.endLatch(letGo = true)
    }

    /** When a shaped output next changes by itself (after [now]); Long.MAX_VALUE = none. */
    private fun shapersDeadline(now: Long): Long {
        var d = Long.MAX_VALUE
        if (touchShapers.isNotEmpty()) for (sh in touchShapers.values) d = min(d, sh.deadline(now))
        for (i in deviceList.indices) {
            val dev = deviceList[i]
            if (dev.shapers.isNotEmpty()) for (sh in dev.shapers.values) d = min(d, sh.deadline(now))
        }
        if (anyDevice.shapers.isNotEmpty()) for (sh in anyDevice.shapers.values) d = min(d, sh.deadline(now))
        return d
    }

    private fun clearShapers() {
        touchShapers.clear(); touchCycles.clear()
        for (d in deviceList) {
            d.shapers.clear(); d.cycles.clear()
        }
        anyDevice.shapers.clear(); anyDevice.cycles.clear()
    }

    /** Works out the cycles of the mappings in use and forgets the shapers of mappings no longer in use. */
    private fun updateShapers() {
        val info = HashMap<Mapping, CycleInfo>()
        fun cycles(ms: List<Mapping>) {
            val groups = LinkedHashMap<CycleKey, ArrayList<Mapping>>()
            for (m in ms) if (m.options?.cycle == true) groups.getOrPut(CycleKey(m.source, m.set)) { ArrayList() }.add(m)
            for ((k, list) in groups) list.forEachIndexed { i, m -> info[m] = CycleInfo(k, i, list.size) }
        }
        cycles(touchMappings)
        for (ms in padMappings) cycles(ms)
        cycleInfo = info
        val split = HashMap<Mapping, Shaper.Split>()
        fun splits(ms: List<Mapping>) {
            val groups = LinkedHashMap<InputSource, ArrayList<Mapping>>()
            for (m in ms) if (isDoubleTap(m.source) && m.target != OutputTarget.NoOutput) groups.getOrPut(m.source) { ArrayList() }.add(m)
            for (list in groups.values) {
                val held = list.filter { (it.options?.mode ?: How.HELD) == How.HELD }
                if (held.isEmpty() || held.size == list.size) continue
                for (m in list) split[m] = if (m in held) Shaper.Split.HOLD else Shaper.Split.QUICK
            }
        }
        splits(touchMappings)
        for (ms in padMappings) splits(ms)
        if (split != splitInfo) {
            // A chip that changes how it is told apart starts again.
            touchShapers.keys.removeAll { (splitInfo[it] ?: Shaper.Split.NONE) != (split[it] ?: Shaper.Split.NONE) }
            for (d in deviceList) d.shapers.keys.removeAll { (splitInfo[it] ?: Shaper.Split.NONE) != (split[it] ?: Shaper.Split.NONE) }
            anyDevice.shapers.keys.removeAll { (splitInfo[it] ?: Shaper.Split.NONE) != (split[it] ?: Shaper.Split.NONE) }
        }
        splitInfo = split
        val touchUsed = touchMappings.toHashSet()
        touchShapers.keys.retainAll(touchUsed)
        for (d in deviceList) {
            val used = padMappings.getOrNull(d.pad)?.toHashSet() ?: emptySet()
            d.shapers.keys.retainAll(used)
        }
        val all = padMappings.flatMapTo(HashSet()) { it }
        anyDevice.shapers.keys.retainAll(all)
    }

    private fun onPress(t: OutputTarget) {
        when (t) {
            OutputTarget.GyroToggle -> gyroToggled = !gyroToggled
            is OutputTarget.Wheel -> wheel += t.step * MouseState.WHEEL_UNIT
            is OutputTarget.Layer -> layerPressed(t)
            OutputTarget.LayerBack -> layerBack()
            OutputTarget.LetGo -> letGoAll(clock())
            OutputTarget.BlackScreen -> onBlackScreen?.run()
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
        backLayers = Layers(layerSet, layerOverrides.toList())
        carryHeld(t, t.whileHeld)
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
        backLayers = Layers(layerSet, layerOverrides.toList())
        carryHeld(t, t.whileHeld)
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

    /** Back to the sets in use before the last layer change; the ones left become the way back. */
    private fun layerBack() {
        val b = backLayers ?: return
        backLayers = Layers(layerSet, layerOverrides.toList())
        carryHeld(OutputTarget.LayerBack, whileHeld = false)
        layerHolds.clear()
        layerSet = b.set
        layerOverrides.clear(); layerOverrides.addAll(b.overrides)
        applyLayers()
    }

    /**
     * Before a set change by [t]: every held input remembers what it presses now. Its layer actions
     * always stay until it is released; its buttons, keys and clicks too, and the new set's mappings
     * for it wait for its release, unless it is a touch control that follows layers. The inputs holding [t]
     * itself always keep everything; holding a "... hold" action they also press what the new set
     * maps them to at once (all but its layer actions, which wait, so nothing flips back and forth).
     */
    private fun carryHeld(t: OutputTarget, whileHeld: Boolean) {
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
                val layersOnly = holder && whileHeld && (before == null || before.holdsLayers)
                now[src] = Carried(kept, keep || before?.holdsBack == true, layersOnly)
            }
            carried[g] = now
        }
        anyCarried = carried.any { it.isNotEmpty() }
    }

    /** A held input keeps [m] through a set change: its layer action always, a button-like output when it [keep]s. */
    private fun carries(m: Mapping, keep: Boolean): Boolean {
        val t = m.target
        if (t is OutputTarget.Layer || t == OutputTarget.LayerBack) return true
        if (!keep || continuous(m.source)) return false
        return t is OutputTarget.Pad || t is OutputTarget.Key || t is OutputTarget.Mouse ||
                t is OutputTarget.PadAxis || t == OutputTarget.GyroPause || t == OutputTarget.GyroHold || t is OutputTarget.GyroPauseAxis
    }

    /** An input whose value follows a motion (a full stick or trigger axis, touch motion): it follows the new set at once. */
    private fun continuous(s: InputSource): Boolean = when (s) {
        is InputSource.Axis -> s.half == AxisHalf.FULL && s.stage == AxisStage.NONE
        is InputSource.TouchStick -> s.half == AxisHalf.FULL
        is InputSource.Surface -> s.input.motion
        is InputSource.Gyro -> true
        is InputSource.Tilt -> s.half == AxisHalf.FULL
        is InputSource.Finger -> true
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
        routesCache = null
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
        pcGyroButtons = if (ws.pads.isEmpty()) IntArray(0) else pcGyroButtonsOf(padMappings[0])
        fun ratchets(ms: List<Mapping>) = ms.mapNotNull { m ->
            val b = ratchetBits(m.target)
            if (b == 0) null else Ratchet(m, sameInput(m.source) to m.target, b)
        }
        touchRatchets = ratchets(touchMappings)
        padRatchets = Array(padMappings.size) { ratchets(padMappings[it]) }
        ratchetMapped = touchRatchets.isNotEmpty() || padRatchets.any { it.isNotEmpty() }
        touchEvents = touchMappings.filter { isEvent(it.target) }
        padEvents = Array(padMappings.size) { padMappings[it].filter { m -> isEvent(m.target) } }
        eventsMapped = touchEvents.isNotEmpty() || padEvents.any { it.isNotEmpty() }
        sensMapped = touchMappings.any { it.target is OutputTarget.GyroSens } ||
                padMappings.any { l -> l.any { it.target is OutputTarget.GyroSens } }
        trace?.let { traceLayers(it) }
        touchpad.map = SurfaceMap(touchMappings)
        for ((id, s) in surfaces) s.map = SurfaceMap(touchMappings, id)
        for ((id, st) in tapStates) {
            val double = targetsOf(touchMappings, InputSource.TouchDoubleTap(id))
            st.hasDouble = double.isNotEmpty()
            st.tapWaits = double.isNotEmpty() && double != targetsOf(touchMappings, InputSource.TouchTap(id))
        }
        keyCfgs = Array(padMappings.size) { keyGestureCfgs(padMappings[it]) }
        anyKeyCfgs = keyGestureCfgs(padMappings.flatMap { it })
        updateShapers()
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
        updateGyroNow()
        // One Tap time: the double-tap window is the long-press time.
        touchpad.doubleTapNanos = e.longPressMs.coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS) * 1_000_000L
        touchpad.longPressNanos = e.longPressMs.coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS) * 1_000_000L
        for (c in ws.touchControls) {
            surfaces[c.id]?.let { s ->
                s.settings = c.surfaceSettings(e.touchpad)
                s.doubleTapNanos = c.doubleTap(e) * 1_000_000L; s.longPressNanos = c.longPress(e) * 1_000_000L
            }
            tapStates[c.id]?.let { st -> st.longNs = c.longPress(e) * 1_000_000L; st.doubleNs = c.doubleTap(e) * 1_000_000L }
            for (k in TouchAreaKind.entries) areaGestures[areaKey(c.id, k)]?.let { st ->
                st.longNs = c.longPress(e) * 1_000_000L; st.doubleNs = c.doubleTap(e) * 1_000_000L
            }
        }
        for (d in deviceList) for ((code, g) in d.gestures) keyTimes(code, g)
        for ((code, g) in anyDevice.gestures) keyTimes(code, g)
        updateGyroNow()
    }

    /** Key [code]'s gesture times: its own from the pinned preset, else those in effect ([eff]). */
    private fun keyTimes(code: Int, g: ButtonGestures) {
        val own = ws.keyTimings.firstOrNull { it.keyCode == code }
        g.longNs = (own?.longPressMs ?: eff.longPressMs).coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS) * 1_000_000L
        g.doubleNs = g.longNs
    }

    // ---- Touch presses ----

    private fun press(id: String) {
        val n = touches[id] ?: 0
        touches[id] = n + 1
        if (n == 0) {
            pressOrder.add(id); updateGyroNow()
        }
        if (n == 0 && tapList.isNotEmpty()) controlDown(id, clock())
    }

    private fun release(id: String) {
        val n = touches[id] ?: return
        if (n > 1) {
            touches[id] = n - 1; return
        }
        touches.remove(id)
        pressOrder.remove(id)
        updateGyroNow()
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
        is InputSource.Axis -> if (dev == null) 0f else axisValue(dev, source)
        is InputSource.Touch -> if (touches.containsKey(source.id)) 1f else 0f
        is InputSource.TouchTap -> if (tapStates[source.id]?.on(ButtonGesture.TAP) == true) 1f else 0f
        is InputSource.TouchLongPress -> if (tapStates[source.id]?.on(ButtonGesture.LONG_PRESS) == true) 1f else 0f
        is InputSource.TouchRelease -> if (tapStates[source.id]?.on(ButtonGesture.RELEASE) == true) 1f else 0f
        is InputSource.TouchDoubleTap -> if (tapStates[source.id]?.on(ButtonGesture.DOUBLE_TAP) == true) 1f else 0f
        is InputSource.TouchArea -> if (source.on == Trigger.HOLD) {
            if (areas[source.kind.ordinal].held(source.id)) 1f else 0f
        } else if (Triggers.gesture(source.on)?.let { areaGestures[areaKey(source.id, source.kind)]?.on(it) } == true) 1f else 0f
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
            else if (shakeOn) 1f else 0f

        is InputSource.Finger -> fingerValue(source)
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

    /**
     * Physical axis [s] of [dev]: raw unless it is part of a tuned stick ([StickTuning]: round
     * deadzones, curve, sensitivity, direction pies) or one of its stages ([AxisStage]).
     */
    private fun axisValue(dev: Dev, s: InputSource.Axis): Float {
        val a = s.axis
        // The device's own sticks: on a pad with its right stick on RX / RY, Z / RZ are triggers.
        val rx = dev.device?.rightStickRx == true
        val layout = if (rx) STICKS_RX else STICKS
        val stick = if (a in 0 until AXIS_SLOTS) (if (rx) axisStickRx else axisStick)[a] else null
        return when (s.stage) {
            AxisStage.NONE -> if (stick == null) half(dev.axis(a), s.half) else stickValue(dev, s, stick, layout)
            AxisStage.OUTER -> stickValue(dev, s, stick ?: DEFAULT_STICK, layout)
            AxisStage.PRESS, AxisStage.FULL_PULL -> {
                val t = (if (a in 0 until AXIS_SLOTS) axisTrigger[a] else null) ?: DEFAULT_TRIGGER
                val at = if (s.stage == AxisStage.PRESS) t.press else t.full
                if (dev.axis(a) > 0f && dev.axis(a) >= at) 1f else 0f
            }
        }
    }

    /** [s] (a stick axis, half, or Outer) of [dev], whose sticks are [layout], with stick tuning [t]. */
    private fun stickValue(dev: Dev, s: InputSource.Axis, t: StickTuning, layout: StickLayout): Float {
        val a = s.axis
        val partner = layout.partner.getOrElse(a) { -1 }
        if (partner < 0) return if (s.stage == AxisStage.NONE) half(dev.axis(a), s.half) else 0f
        val own = dev.axis(a)
        val other = dev.axis(partner)
        val r = hypot(own, other)
        val span = (t.outer - t.inner).coerceAtLeast(0.01f)
        val travel = ((r - t.inner) / span).coerceIn(0f, 1f)
        if (travel <= 0f) return 0f
        if (s.stage == AxisStage.OUTER) {
            if (travel < t.outerRing) return 0f
            return if (s.half == AxisHalf.FULL || inPie(s, own, other, t, layout)) 1f else 0f
        }
        val mag = (t.curve.apply(travel) * t.sensitivity).coerceAtMost(1f)
        return when (s.half) {
            AxisHalf.FULL -> own / r * mag
            else -> if (inPie(s, own, other, t, layout)) mag else 0f
        }
    }

    /** Whether a stick at ([own] on [s]'s axis, [other] on its partner) points into [s]'s direction pie. */
    private fun inPie(s: InputSource.Axis, own: Float, other: Float, t: StickTuning, layout: StickLayout): Boolean {
        val along = if (s.half == AxisHalf.NEGATIVE) -own else own
        if (along <= 0f) return false
        val pie = (if (layout.isX[s.axis]) t.pieX else t.pieY).coerceIn(StickTuning.MIN_PIE, StickTuning.MAX_PIE)
        return Math.toDegrees(atan2(abs(other), along).toDouble()) <= pie / 2.0
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

        /** An input whose value is past this is off its rest, so a chip's range applies ([activeOf]). */
        const val RANGE_ACTIVE = 1e-4f

        /** The sensitivities a Gyro sensitivity chip can set, counts per degree. */
        const val MIN_SENS_CHIP = 0f
        const val MAX_SENS_CHIP = 10_000f

        /** [pressOrder]'s prefix of a physical key or button ([HeldButton]). */
        private const val PHYS = HeldButton.GYRO_ID

        /** [moveTrack]: inertia carries the Moves on. */
        const val MOVES_GLIDING = 4
        private val NO_OPTIONS = MapOptions()

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
        private val DEFAULT_STICK = StickTuning()
        private val DEFAULT_TRIGGER = TriggerTuning()

        /** A pad's physical sticks: right stick on Z / RZ ([PhysicalSticks.sticks]) or on RX / RY ([PhysicalSticks.sticksRx]). */
        private val STICKS = StickLayout(PhysicalSticks.sticks)
        private val STICKS_RX = StickLayout(PhysicalSticks.sticksRx)

        private const val MAX_BUTTON_FINGERS = 10

        private const val BP_FREE = 0

        private val MOTION_AXES = arrayOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)

        /** Axis [a]'s bit of [gyroAxes]. */
        fun gyroBit(a: MotionAxis): Int = when (a) {
            MotionAxis.YAW -> 1
            MotionAxis.PITCH -> 2
            MotionAxis.ROLL -> 4
            MotionAxis.NONE -> 0
        }

        /** Every gyro axis's [gyroBit]. */
        const val ALL_GYRO = 7

        /** A [pcGyroButtons] kind bit: the button has gyro settings of its own while held ([pcHeldGyro]). */
        const val PC_GYRO_SETTINGS = 16

        /** A [pcGyroButtons] kind bit: the button toggles the gyro. */
        const val PC_GYRO_TOGGLE = 8


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

/** Per axis id (0..63): the other axis of its physical stick in [sticks] ([partner], or -1), and whether it is the X axis. */
private class StickLayout(sticks: Map<String, Pair<Int, Int>>) {
    val partner = IntArray(64) { -1 }
    val isX = BooleanArray(64)

    init {
        for ((x, y) in sticks.values) { partner[x] = y; partner[y] = x; isX[x] = true }
    }
}
