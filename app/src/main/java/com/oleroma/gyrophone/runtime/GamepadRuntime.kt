package com.oleroma.gyrophone.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.oleroma.gyrophone.core.engine.CountStream
import com.oleroma.gyrophone.core.engine.GyroCalibration
import com.oleroma.gyrophone.core.engine.GravityPose
import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.engine.GyroPose
import com.oleroma.gyrophone.core.engine.GyroSway
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadGyro
import com.oleroma.gyrophone.core.engine.TiltShake
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.ButtonActivators
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownController
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.LibraryButton
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TriggerReset
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.engine.GyroMath
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.GyroSpace
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.WorkspaceSet
import com.oleroma.gyrophone.ui.TouchVisuals
import com.oleroma.gyrophone.data.ProfileStore
import com.oleroma.gyrophone.core.net.ByeReason
import com.oleroma.gyrophone.core.net.PcGyroFeed
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.sensor.GyroRateWindow
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.hid.ReportPump
import com.oleroma.gyrophone.link.BluetoothLink
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.link.LinkStats
import com.oleroma.gyrophone.link.OutputLink
import com.oleroma.gyrophone.link.TransportKind
import com.oleroma.gyrophone.sensor.AccelSource
import com.oleroma.gyrophone.sensor.DirectChannelGyroSource
import com.oleroma.gyrophone.sensor.GyroSource
import com.oleroma.gyrophone.wifi.WifiLink
import com.oleroma.gyrophone.wifi.WifiPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** A game controller attached to the phone (e.g. a clip-on controller). */
data class AttachedController(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val known: KnownController?,
    /** Android keycodes the device reports having (empty = unknown). */
    val keys: Set<Int> = emptySet(),
    /** Android motion axes the device reports (empty = unknown). */
    val axes: Set<Int> = emptySet(),
)

/**
 * Process-wide wiring: profiles -> mapping engine -> the active output link (Bluetooth HID via
 * the report pump, or Wi-Fi to the PC companion), plus the gyro. Started and stopped by
 * GamepadService; the UI reads its state flows.
 */
class GamepadRuntime(context: Context) {
    // Acceleration graphs on screen ([watchAccel]); first, as the gyro's start check reads it.
    private val accelWatchers = java.util.concurrent.atomic.AtomicInteger()
    private val appContext = context.applicationContext

    val store = ProfileStore(appContext)
    val hid = HidDeviceManager(appContext)

    /** The mapping profiles of a set saved before workspaces owned their mappings; only read by [loadOwnMappings]. */
    private var legacyProfiles: List<MappingProfile> = emptyList()

    /**
     * All workspaces live in one profile ([WorkspaceSet]), each with its own mappings, stored as
     * [WorkspaceMappings.store] keeps them. See [loadWorkspaceSet] and [loadOwnMappings].
     */
    private var stored: Profile = run {
        val existing = store.userProfiles().isNotEmpty()
        resetForTriggers(loadOwnMappings(loadWorkspaceSet()), existing)
    }

    private val _activeProfile = MutableStateFlow(WorkspaceMappings.resolve(stored))

    /** The workspace set as the engine and editors see it ([WorkspaceMappings.resolve]). */
    val activeProfile: StateFlow<Profile> = _activeProfile

    private val _buttonLibrary = MutableStateFlow(store.buttonLibrary())

    /** Buttons saved to reuse in any workspace, with their look and mappings. */
    val buttonLibrary: StateFlow<List<LibraryButton>> = _buttonLibrary

    private val _workspaceIndex = MutableStateFlow(rememberedWorkspace(_activeProfile.value))

    /** Index of the active workspace in [activeProfile] (always in range). */
    val workspaceIndex: StateFlow<Int> = _workspaceIndex

    val engine = MappingEngine(_activeProfile.value).also { it.workspaceIndex = _workspaceIndex.value }
    private val pump = ReportPump(engine, hid)
    private val gyroMouse = GyroMouse(_activeProfile.value.workspace(_workspaceIndex.value).gyro)
    private val prefs = appContext.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)

    private val _reportRateHz = MutableStateFlow(
        prefs.getInt(KEY_RATE, ReportPump.DEFAULT_RATE_HZ).takeIf { it in ReportPump.RATES_HZ }
            ?: ReportPump.DEFAULT_RATE_HZ,
    )
    val reportRateHz: StateFlow<Int> = _reportRateHz

    val bluetooth = BluetoothLink(appContext, hid, pump)
    val wifi = WifiLink(appContext, engine)

    private val _transport = MutableStateFlow(
        prefs.getString(KEY_TRANSPORT, null)?.let { name -> TransportKind.entries.firstOrNull { it.name == name } }
            ?: TransportKind.BLUETOOTH,
    )

    /** Which link carries input to the PC. Only one link runs at a time. */
    val transport: StateFlow<TransportKind> = _transport

    private val _wifiRateHz = MutableStateFlow(
        prefs.getInt(KEY_WIFI_RATE, Wire.DEFAULT_RATE_HZ).takeIf { it in Wire.RATES_HZ } ?: Wire.DEFAULT_RATE_HZ,
    )
    val wifiRateHz: StateFlow<Int> = _wifiRateHz

    private val _gyroPollHz = MutableStateFlow(
        prefs.getInt(KEY_GYRO_POLL, DirectChannelGyroSource.DEFAULT_POLL_HZ)
            .takeIf { it in DirectChannelGyroSource.POLL_RATES_HZ } ?: DirectChannelGyroSource.DEFAULT_POLL_HZ,
    )

    /** How often the gyro direct channel is checked for new samples. */
    val gyroPollHz: StateFlow<Int> = _gyroPollHz

    private val _pcControllerGyro = MutableStateFlow(prefs.getBoolean(KEY_PC_CONTROLLER_GYRO, false))
        .also { wifi.pcGyro.enabled = it.value }

    /** "Use PC controller for gyro ratchet": a controller plugged into the PC ratchets and toggles the gyro ([com.oleroma.gyrophone.core.net.PcGyroFeed]). */
    val pcControllerGyro: StateFlow<Boolean> = _pcControllerGyro

    // The first start with these: the open workspace's own values (the workspace and layer ones are no longer used).
    private val _gyroTightening = MutableStateFlow(
        globalGyroFilter(KEY_GYRO_TIGHTENING) { it.tighteningThreshold },
    ).also { gyroMouse.tightening = it.value }

    /** The app's gyro jitter cutoff (deg/s, 0 = off): below it slow turns move less. Every workspace, and the PC in PC gyro mode. */
    val gyroTightening: StateFlow<Float> = _gyroTightening

    private val _gyroSmoothing = MutableStateFlow(
        globalGyroFilter(KEY_GYRO_SMOOTHING) { it.smoothingThreshold },
    ).also { gyroMouse.smoothing = it.value }

    /** The app's gyro smoothing (deg/s, 0 = off): below it motion is averaged over a short window. */
    val gyroSmoothing: StateFlow<Float> = _gyroSmoothing

    /** The stored value of [key], or the first time the open workspace's [of], stored from then on. */
    private fun globalGyroFilter(key: String, of: (GyroSettings) -> Float): Float {
        if (prefs.contains(key)) return prefs.getFloat(key, 0f).takeIf { it.isFinite() && it >= 0f } ?: 0f
        val v = of(_activeProfile.value.workspace(_workspaceIndex.value).gyro).takeIf { it.isFinite() && it >= 0f } ?: 0f
        prefs.edit().putFloat(key, v).apply()
        return v
    }

    /** Sets [gyroTightening] (deg/s; below 0.05 is off). */
    fun setGyroTightening(degPerSec: Float) {
        val v = if (degPerSec < 0.05f || !degPerSec.isFinite()) 0f else degPerSec
        prefs.edit().putFloat(KEY_GYRO_TIGHTENING, v).apply()
        _gyroTightening.value = v
        gyroMouse.tightening = v
    }

    /** Sets [gyroSmoothing] (deg/s; below 0.05 is off). */
    fun setGyroSmoothing(degPerSec: Float) {
        val v = if (degPerSec < 0.05f || !degPerSec.isFinite()) 0f else degPerSec
        prefs.edit().putFloat(KEY_GYRO_SMOOTHING, v).apply()
        _gyroSmoothing.value = v
        gyroMouse.smoothing = v
    }

    private val _sensCapRws = MutableStateFlow(prefs.getFloat(KEY_SENS_CAP_RWS, DEFAULT_SENS_CAP_RWS))

    /** "Slider cap" with the game known: the top of every gyro sensitivity slider shown in RWS. Typing may go above it. */
    val sensCapRws: StateFlow<Float> = _sensCapRws

    private val _sensCapCounts = MutableStateFlow(prefs.getFloat(KEY_SENS_CAP_COUNTS, DEFAULT_SENS_CAP_COUNTS))

    /** "Slider cap" without a game: the top of every gyro sensitivity slider in counts per degree. */
    val sensCapCounts: StateFlow<Float> = _sensCapCounts

    fun setSensCapRws(v: Float) {
        val c = v.coerceIn(MIN_SENS_CAP_RWS, MAX_SENS_CAP_RWS)
        prefs.edit().putFloat(KEY_SENS_CAP_RWS, c).apply()
        _sensCapRws.value = c
    }

    fun setSensCapCounts(v: Float) {
        val c = v.coerceIn(MIN_SENS_CAP_COUNTS, MAX_SENS_CAP_COUNTS)
        prefs.edit().putFloat(KEY_SENS_CAP_COUNTS, c).apply()
        _sensCapCounts.value = c
    }

    private val _gyroPollUsed = MutableStateFlow(true)

    /** False while the gyro runs on the listener, where [gyroPollHz] has no effect. Kept while paused. */
    val gyroPollUsed: StateFlow<Boolean> = _gyroPollUsed

    private val _screenOrientation = MutableStateFlow(
        prefs.getString(KEY_ORIENTATION, null)
            ?.let { name -> ScreenOrientation.entries.firstOrNull { it.name == name } }
            ?: ScreenOrientation.AUTO,
    )

    /** How the activity may rotate; MainActivity applies it. */
    val screenOrientation: StateFlow<ScreenOrientation> = _screenOrientation

    private val _padOnlyInput = MutableStateFlow(prefs.getBoolean(KEY_PAD_ONLY_INPUT, true))

    /** When on (the default), controller, keyboard and gyro input reach the PC only while the pad is on screen. */
    val padOnlyInput: StateFlow<Boolean> = _padOnlyInput

    private val _idleStopMinutes = MutableStateFlow(prefs.getInt(KEY_IDLE_STOP_MINUTES, DEFAULT_IDLE_STOP_MINUTES))

    /**
     * Minutes the app may stay off screen before GamepadService stops the link and itself; 0 = never.
     * Opening the app again starts it.
     */
    val idleStopMinutes: StateFlow<Int> = _idleStopMinutes

    private val _onScreen = MutableStateFlow(false)

    /** The activity is resumed ([setForeground]); GamepadService times its idle stop from this. */
    val onScreen: StateFlow<Boolean> = _onScreen

    private val _startFullscreen = MutableStateFlow(prefs.getBoolean(KEY_START_FULLSCREEN, false))

    /** Open the pad in fullscreen when the app starts. */
    val startFullscreen: StateFlow<Boolean> = _startFullscreen

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))

    /** When on (the default), the screen stays on, so the phone doesn't lock, while the pad is shown. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

    private val _blackScreenIdle = MutableStateFlow(prefs.getBoolean(KEY_BLACK_SCREEN_IDLE, false))

    /** In play, the pad goes black (touch buttons off) after [blackScreenSeconds] without a touch; a tap brings it back. */
    val blackScreenIdle: StateFlow<Boolean> = _blackScreenIdle

    private val _blackScreenSeconds = MutableStateFlow(prefs.getInt(KEY_BLACK_SCREEN_SECONDS, DEFAULT_BLACK_SCREEN_SECONDS))

    /** How long the pad waits without a touch before going black ([blackScreenIdle]). */
    val blackScreenSeconds: StateFlow<Int> = _blackScreenSeconds

    private val _blackScreenRequests = MutableStateFlow(0)

    /** Goes up each time a Black screen mapping fires ([OutputTarget.BlackScreen]): the pad goes black at once. */
    val blackScreenRequests: StateFlow<Int> = _blackScreenRequests

    private val _cornerAutoHide = MutableStateFlow(prefs.getBoolean(KEY_CORNER_AUTO_HIDE, false))

    /** The ← / → corners fade out after a few seconds in play; four quick taps show them again. */
    val cornerAutoHide: StateFlow<Boolean> = _cornerAutoHide

    private val _editLayoutLocked = MutableStateFlow(prefs.getBoolean(KEY_EDIT_LAYOUT_LOCKED, false))

    /** Edit mode's lock: drags and pinches on the pad don't move or resize controls. */
    val editLayoutLocked: StateFlow<Boolean> = _editLayoutLocked

    private val _touchVisuals = MutableStateFlow(
        prefs.getString(KEY_TOUCH_VISUALS, null)
            ?.let { runCatching { visualsJson.decodeFromString(TouchVisuals.serializer(), it) }.getOrNull() }
            ?: TouchVisuals(),
    )

    /** How the pad shows touches, trails and live mappings. */
    val touchVisuals: StateFlow<TouchVisuals> = _touchVisuals

    fun setTouchVisuals(v: TouchVisuals) {
        prefs.edit().putString(KEY_TOUCH_VISUALS, visualsJson.encodeToString(TouchVisuals.serializer(), v)).apply()
        val old = vis
        if (v.gyroNeedsGravity && !old.gyroNeedsGravity) worldPose.reset()
        if (v.gyroEdge && !old.gyroEdge) gyroSway.reset()
        vis = v
        _touchVisuals.value = v
        // updateGyro starts and stops the accelerometer under this lock too.
        synchronized(this) { updateMotion() }
    }

    /** The parallax edge's settings ([TouchVisuals]): its data is fed by gyro samples only while it is on. */
    @Volatile private var vis = _touchVisuals.value

    /** The pose against the world ([GyroPose.fuse]) for the parallax edge in tilt mode. */
    val worldPose = GyroPose()

    /** The parallax edge's swing. */
    val gyroSway = GyroSway()

    /** The data of the gyro displays on the pad now ([setGyroDisplays]). */
    @Volatile private var gyroFeeds: Array<GyroDisplayFeed> = emptyArray()

    /** Gyro display [id]'s data, or null when it is not on the pad. */
    fun gyroFeed(id: String): GyroDisplayFeed? = gyroFeeds.firstOrNull { it.id == id }

    /**
     * The gyro displays ([TouchKind.DISPLAY] controls) on the pad now, or none when it is not shown:
     * only theirs is gathered. One that stays keeps its data.
     */
    fun setGyroDisplays(controls: List<TouchControl>) {
        val old = gyroFeeds
        val sensors = needsGravity to needsCompass
        val next = controls.mapNotNull { c ->
            val d = c.display ?: return@mapNotNull null
            old.firstOrNull { it.id == c.id && it.display.kind == d.kind }?.also { it.display = d }
                ?: GyroDisplayFeed(c.id, d)
        }
        val same = next.size == old.size && next.indices.all { next[it] === old[it] }
        if (!same) gyroFeeds = next.toTypedArray()
        // A changed setting may need the accelerometer or the magnetometer (or no longer).
        if (!same || sensors != (needsGravity to needsCompass)) synchronized(this) { updateMotion() }
    }

    /**
     * Gravity now, for a gyro display's Level (the sensors may be off, as in edit mode): a few
     * accelerometer readings averaged, as "up" in the phone's frame (right, up, toward the player),
     * given to [onUp] on the main thread; null when there is none within a second.
     */
    fun readUp(onUp: (FloatArray?) -> Unit) {
        val source = appContext.getSystemService(SensorManager::class.java)?.let { AccelSource(it) }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val sum = FloatArray(3)
        var n = 0
        fun finish(up: FloatArray?) {
            source?.stop()
            onUp(up)
        }
        val started = source?.start { x, y, z, _ ->
            if (done.get()) return@start
            val m = gyroMouse.motion(x, y, z)
            sum[0] += m.pitch; sum[1] += -m.yaw; sum[2] += -m.roll
            if (++n >= 8 && done.compareAndSet(false, true)) {
                val up = sum.copyOf()
                main.post { finish(up) }
            }
        } ?: false
        if (!started) {
            onUp(null)
            return
        }
        main.postDelayed({ if (done.compareAndSet(false, true)) finish(null) }, 1000)
    }

    /**
     * A Level displays mapping fired (main thread): every 3D gyro display on the pad and every PC
     * overlay takes the way the phone is held now as level, as each one's Level button does. With
     * gravity that tilt is saved as the display's resting grip; without, its pose starts again from
     * straight. The saved grips change only the workspace's displays: nothing held is let go.
     */
    private fun levelDisplays() {
        wifi.overlay.levelDisplays()
        val rests = HashMap<String, List<Float>?>()
        for (f in gyroFeeds) {
            val d = f.display
            if (!d.kind.pose) continue
            val rest = if (d.needsGravity) f.pose.tiltOnly().toList() else null
            if (rest == null) f.restart()
            f.display = d.copy(rest = rest)
            if (d.rest != rest) rests[f.id] = rest
        }
        if (rests.isEmpty()) return
        val before = _activeProfile.value
        val index = _workspaceIndex.value
        fun level(c: TouchControl): TouchControl {
            val d = c.display ?: return c
            return if (c.id in rests) c.copy(display = d.copy(rest = rests[c.id])) else c
        }
        val changed = before.updateWorkspace(index) { w -> w.copy(touchControls = w.touchControls.map(::level)) }
            .let { it.copy(corners = it.corners.map(::level)) }
        if (changed == before) return
        // Saved like editActiveProfile, but the engine keeps its profile: a display's grip is no mapping.
        val set = WorkspaceMappings.store(WorkspaceMappings.settle(before, changed))
        if (set != stored) {
            stored = set
            store.saveLater(set)
        }
        _activeProfile.value = WorkspaceMappings.resolve(stored)
    }

    /** A Clear displays mapping fired (main thread): every laser pointer on the pad and the PC loses its paint and trail. */
    private fun clearDisplays() {
        wifi.overlay.clearDisplays()
        for (f in gyroFeeds) f.laser?.clear()
    }

    /** Clears laser pointer [id]'s paint and trail (its settings' Clear button; main thread). */
    fun clearLaser(id: String) {
        gyroFeed(id)?.laser?.clear()
    }

    /** Some gyro display holds its pose to gravity (and so needs the accelerometer). */
    private val needsGravity: Boolean get() = vis.gyroNeedsGravity || gyroFeeds.any { it.display.needsGravity } || wifi.overlay.wanted ||
        _activeProfile.value.workspace(_workspaceIndex.value).gyro.gyroSpace != GyroSpace.LOCAL

    /** The phone's pose against gravity, for the World and Player gyro spaces ([GyroSpace]). */
    private val gravityPose = GravityPose()

    /** [gravityPose] followed the gyro last sample (World or Player space). Gyro thread only. */
    private var gravityPoseUsed = false

    /** The gyro space ([GyroSpace]) yaw, pitch and roll are measured in now: the workspace's. */
    private fun gyroSpace(): GyroSpace = _activeProfile.value.workspace(_workspaceIndex.value).gyro.gyroSpace

    /** The workspace's gyro settings, for its PC DualShock 4 axes ([PadGyro]). */
    private fun padGyroSettings(): GyroSettings = _activeProfile.value.workspace(_workspaceIndex.value).gyro

    // The PC DualShock 4's gyro (gyro thread) and accelerometer (accelerometer thread) after the workspace's pad axes.
    private val padGyro = FloatArray(3)
    private val padAccel = FloatArray(3)

    /** The world's up for [GyroMouse.route] (gyro thread). */
    private val upNow = FloatArray(3)

    /** Some gyro display holds its heading to north (and so needs the magnetometer). */
    private val needsCompass: Boolean get() = gyroFeeds.any { it.display.needsCompass } || wifi.overlay.compassWanted

    /** The gyro axes moving the mouse now ([MappingEngine.gyroAxes]), for the ratchet ring and the edge's tint. */
    @Volatile var gyroLiveAxes = 0
        private set

    /** Ratchets held at the last gyro sample: a new one is a ratchet press. */
    private var poseRatchets = 0

    private val _capsuleOrder = MutableStateFlow(prefs.getString(KEY_CAPSULE_ORDER, "") ?: "")

    /** Order of the pad's top capsules: 'W' per workspace slot, 'S' Settings, 'C' Connection (see ui.capsules). */
    val capsuleOrder: StateFlow<String> = _capsuleOrder

    /** The pad is shown in play mode (not the menu, the editor or the Connect tab); set by PadScreen. */
    private var padShown = false

    /** The activity is resumed; set by MainActivity. */
    private var foreground = false

    /**
     * Whether controller, keyboard and gyro input go to the engine. False while [padOnlyInput] is on
     * and the pad is not on screen; the link stays up and sends nothing held.
     */
    @Volatile
    var inputAllowed = !_padOnlyInput.value
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _linkState = MutableStateFlow<LinkState>(LinkState.Off)

    /** State of the active link. */
    val linkState: StateFlow<LinkState> = _linkState

    /** Debug tools: the Settings switch, the debug log, recordings ([PhoneDebug]). */
    val debug = PhoneDebug(appContext, this, scope)

    /** Plays the engine's haptic mappings while a PC is connected. */
    private val haptics = PhoneHaptics(appContext) { _linkState.value is LinkState.Connected }.also { h ->
        h.log = { line -> debug.trace?.add(com.oleroma.gyrophone.core.debug.DebugTrace.Kind.OUT, line) }
    }

    /** For the debug tools' header: the vibrator. */
    val hapticsInfo: String get() = haptics.describe

    @Volatile
    private var running = false

    private val active: OutputLink get() = link(_transport.value)

    private fun link(kind: TransportKind): OutputLink = when (kind) {
        TransportKind.BLUETOOTH -> bluetooth
        TransportKind.WIFI -> wifi
    }

    fun linkStats(): LinkStats = active.stats()

    private val _controllers = MutableStateFlow<List<AttachedController>>(emptyList())
    val controllers: StateFlow<List<AttachedController>> = _controllers

    private val _mouseAttached = MutableStateFlow(false)

    /** A mouse is attached to the phone: the pad captures it while the workspace maps a mouse. */
    val mouseAttached: StateFlow<Boolean> = _mouseAttached

    /** Closes the app's screen and removes it from recents; MainActivity sets it, the notification's Stop calls it. */
    var closeApp: (() -> Unit)? = null

    /** Gets the captured mouse's events (MainActivity's controller input); the pad's view passes them on. */
    @Volatile
    var capturedPointer: ((android.view.MotionEvent) -> Boolean)? = null

    /** The pad holds pointer capture: a mouse's events are its own, not the phone's. */
    @Volatile
    var mouseCaptured = false

    private val _gyroInfo = MutableStateFlow(GyroInfo())
    val gyroInfo: StateFlow<GyroInfo> = _gyroInfo

    /** What the Connect tab shows: the running source (or why none runs), its measured rate, and whether it moves the mouse. */
    data class GyroInfo(val source: String = "Not started", val rateHz: Int = 0, val active: Boolean = false)

    /** Rest-calibration state shown in the pad Menu. */
    sealed interface CalibrationState {
        data object None : CalibrationState
        data object Running : CalibrationState
        data object Moved : CalibrationState

        /** Bias in deg/s, largest axis, for display. */
        data class Calibrated(val degPerSec: Float) : CalibrationState
    }

    // Constant bias (rad/s) subtracted from every gyro sample; set by rest calibration, kept per phone.
    @Volatile
    private var biasX = prefs.getFloat(KEY_BIAS_X, 0f)

    @Volatile
    private var biasY = prefs.getFloat(KEY_BIAS_Y, 0f)

    @Volatile
    private var biasZ = prefs.getFloat(KEY_BIAS_Z, 0f)

    @Volatile
    private var calibration: GyroCalibration? = null
    private val _calibration = MutableStateFlow(storedCalibration())
    val calibrationState: StateFlow<CalibrationState> = _calibration

    /** Measuring a game's full turn ([measureStart]): steady counts out, the gyro moves nothing meanwhile. */
    private val turn = CountStream()

    @Volatile
    private var measuring = false
    private var turnJob: Job? = null
    private val _turnSent = MutableStateFlow(0L)

    /** Counts sent since the measure began ([measureBegin]). */
    val turnSent: StateFlow<Long> = _turnSent
    private val _turnRunning = MutableStateFlow(false)

    /** The measure is streaming counts now. */
    val turnRunning: StateFlow<Boolean> = _turnRunning

    /** Opens a measure: from 0 counts, the gyro paused until [measureEnd]. */
    @Synchronized
    fun measureBegin() {
        synchronized(turn) { turn.reset() }
        measuring = true
        _turnSent.value = 0
    }

    /** Streams [countsPerSecond] to the right until [measureStop]. */
    fun measureStart(countsPerSecond: Float) {
        synchronized(turn) { turn.run(countsPerSecond) }
        streamTurn()
    }

    /** From 0, streams exactly [total] counts at [countsPerSecond] and stops: one full turn when [total] is right. */
    fun measureVerify(total: Long, countsPerSecond: Float) {
        synchronized(turn) { turn.reset(); turn.runTo(total, countsPerSecond) }
        _turnSent.value = 0
        streamTurn()
    }

    fun measureStop() {
        synchronized(turn) { turn.stop() }
    }

    /** Sends [n] counts at once (negative: left) to line the camera up. */
    fun measureNudge(n: Int) {
        val sent = synchronized(turn) { turn.nudge(n); turn.sent }
        engine.addMouseMotion(n, 0)
        _turnSent.value = sent
    }

    /** Closes the measure: the stream stops and the gyro works again. */
    fun measureEnd() {
        measureStop()
        measuring = false
    }

    private fun streamTurn() {
        turnJob?.cancel()
        _turnRunning.value = true
        turnJob = scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(TURN_TICK_MS)
                val now = System.nanoTime()
                val (n, sent, more) = synchronized(turn) { Triple(turn.tick((now - last) / 1e9f), turn.sent, turn.running) }
                last = now
                if (n != 0) engine.addMouseMotion(n, 0)
                _turnSent.value = sent
                if (!more) {
                    _turnRunning.value = false
                    break
                }
            }
        }
    }

    /** Started, switched and stopped on the main thread only (under this object's lock). */
    private var gyro: GyroSource? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * The direct channel ran capped (the microphone privacy toggle caps it to ~50 Hz, a listener
     * to ~200 Hz): the listener runs instead until the next [start] or return to the foreground.
     */
    private var avoidDirect = false

    /** The rate the capped direct channel measured, shown while [avoidDirect]. */
    private var cappedHz = 0

    // Owned by the thread of the running source; reset before each source starts.
    private var lastGyroTs = 0L

    /** Set by the sampling thread on each sample; the main thread checks a fresh direct channel delivers at all. */
    @Volatile private var gyroSamples = 0L
    private val rateWindow = GyroRateWindow()

    /** When set, the next controller input is captured for the mapping editor instead of sent. */
    @Volatile
    var learnListener: ((InputSource) -> Unit)? = null

    /** When set, the next key-down of a physical keyboard or pad is delivered here as its raw Android keycode and swallowed. */
    @Volatile
    var keyLearnListener: ((androidKeyCode: Int) -> Unit)? = null

    init {
        engine.hapticSink = haptics
        // Inside the engine lock: only bump the counter the pad watches.
        engine.onBlackScreen = Runnable { _blackScreenRequests.update { it + 1 } }
        engine.onLevelDisplays = Runnable { main.post { levelDisplays() } }
        engine.onClearDisplays = Runnable { main.post { clearDisplays() } }
        // Every other mouse count turns a trackball 3D shape too, as the camera turns.
        engine.cameraSink = { dx, dy ->
            val overlay = wifi.overlay
            if (overlay.wanted) {
                val deg = GyroDisplayFeed.cameraDegrees(dx, dy, engine.gyroRoutes(), engine.gyroSettings)
                overlay.camera(deg[0], deg[1], deg[2])
            }
            val feeds = gyroFeeds
            if (feeds.any { it.display.followsCamera }) {
                val routes = engine.gyroRoutes()
                val gs = engine.gyroSettings
                for (f in feeds) f.camera(dx, dy, routes, gs)
            }
        }
        hid.reportProvider = { id ->
            when (id) {
                HidDescriptor.REPORT_ID_GAMEPAD -> pump.lastGamepadReport
                HidDescriptor.REPORT_ID_KEYBOARD -> pump.lastKeyboardReport
                HidDescriptor.REPORT_ID_MOUSE -> ByteArray(HidDescriptor.MOUSE_REPORT_SIZE)
                else -> null
            }
        }
        hid.featureProvider = { id ->
            if (id == HidDescriptor.REPORT_ID_MOUSE_FEATURE) byteArrayOf(pump.mouseFeature.toByte()) else null
        }
        hid.onFeature = { id, value -> if (id == HidDescriptor.REPORT_ID_MOUSE_FEATURE) pump.mouseFeature = value }
        hid.onHostConnected = { pump.invalidate() }
        // The next connection starts in whole notches until the host enables the multipliers again.
        hid.onHostDisconnected = { pump.mouseFeature = 0 }
        pump.intervalNanos = 1_000_000_000L / _reportRateHz.value
        engine.touchpadMinPulseNanos = Touchpad.minPulseFor(pump.intervalNanos)
        wifi.rateHz = _wifiRateHz.value
        scope.launch {
            _transport.collectLatest { kind -> link(kind).state.collect { _linkState.value = it } }
        }
        debug.init()
    }

    fun setReportRate(hz: Int) {
        prefs.edit().putInt(KEY_RATE, hz).apply()
        _reportRateHz.value = hz
        pump.intervalNanos = 1_000_000_000L / hz
        engine.touchpadMinPulseNanos = Touchpad.minPulseFor(pump.intervalNanos)
    }

    fun setScreenOrientation(o: ScreenOrientation) {
        prefs.edit().putString(KEY_ORIENTATION, o.name).apply()
        _screenOrientation.value = o
    }

    fun setWifiRate(hz: Int) {
        prefs.edit().putInt(KEY_WIFI_RATE, hz).apply()
        _wifiRateHz.value = hz
        wifi.rateHz = hz
    }

    fun setPcControllerGyro(on: Boolean) {
        prefs.edit().putBoolean(KEY_PC_CONTROLLER_GYRO, on).apply()
        _pcControllerGyro.value = on
        wifi.pcGyro.enabled = on
    }

    /** Applies at once to a running direct channel, without restarting the sensor. */
    @Synchronized
    fun setGyroPollRate(hz: Int) {
        if (hz !in DirectChannelGyroSource.POLL_RATES_HZ) return
        prefs.edit().putInt(KEY_GYRO_POLL, hz).apply()
        _gyroPollHz.value = hz
        (gyro as? DirectChannelGyroSource)?.pollHz = hz
    }

    fun setIdleStopMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_IDLE_STOP_MINUTES, minutes).apply()
        _idleStopMinutes.value = minutes
    }

    fun setPadOnlyInput(on: Boolean) {
        prefs.edit().putBoolean(KEY_PAD_ONLY_INPUT, on).apply()
        _padOnlyInput.value = on
        updateInputAllowed()
    }

    fun setStartFullscreen(on: Boolean) {
        prefs.edit().putBoolean(KEY_START_FULLSCREEN, on).apply()
        _startFullscreen.value = on
    }

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
        _keepScreenOn.value = on
    }

    fun setBlackScreenIdle(on: Boolean) {
        prefs.edit().putBoolean(KEY_BLACK_SCREEN_IDLE, on).apply()
        _blackScreenIdle.value = on
    }

    fun setBlackScreenSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_BLACK_SCREEN_SECONDS, seconds).apply()
        _blackScreenSeconds.value = seconds
    }

    fun setCornerAutoHide(on: Boolean) {
        prefs.edit().putBoolean(KEY_CORNER_AUTO_HIDE, on).apply()
        _cornerAutoHide.value = on
    }

    fun setEditLayoutLocked(on: Boolean) {
        prefs.edit().putBoolean(KEY_EDIT_LAYOUT_LOCKED, on).apply()
        _editLayoutLocked.value = on
    }

    fun setCapsuleOrder(order: String) {
        prefs.edit().putString(KEY_CAPSULE_ORDER, order).apply()
        _capsuleOrder.value = order
    }

    /** A small persisted editor UI state (pane split, card order, recent targets, open sections). */
    fun uiPref(key: String): String? = prefs.getString(KEY_UI_PREFIX + key, null)

    /** Stores [uiPref] [key]; null removes it. */
    fun setUiPref(key: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(KEY_UI_PREFIX + key) else edit.putString(KEY_UI_PREFIX + key, value)
        edit.apply()
    }

    /** Plays [feel] once on the phone, connected or not (the "Try" buttons). */
    fun previewHaptic(feel: HapticFeel, held: Boolean) = haptics.preview(feel, held)

    fun setPadShown(shown: Boolean) {
        // The pad back in play (the app opened, edit mode closed): the compass takes the heading here.
        if (shown && !padShown) aimGyroDisplays()
        padShown = shown; updateInputAllowed()
        wifi.overlay.padShown = shown
        if (!shown) wifi.overlay.clearTouches()
    }

    /** The gyro displays with a compass take the way the phone faces now as heading 0 ([GyroDisplayFeed.aimHere]). */
    private fun aimGyroDisplays() {
        for (f in gyroFeeds) f.aimHere()
    }

    @Synchronized
    fun setForeground(resumed: Boolean) {
        if (resumed && !foreground) aimGyroDisplays()
        foreground = resumed
        _onScreen.value = resumed
        // Back in front: try the capped direct channel again (the toggle may be off by now).
        if (resumed && avoidDirect) {
            avoidDirect = false
            stopGyro()
        }
        updateInputAllowed()
    }

    /**
     * Leaving the pad releases everything held, so no key or button sticks on the PC, and pauses
     * the gyro sensor ([updateGyro]).
     */
    @Synchronized
    private fun updateInputAllowed() {
        val allowed = !_padOnlyInput.value || (padShown && foreground)
        if (allowed != inputAllowed) {
            inputAllowed = allowed
            if (!allowed) engine.reset()
        }
        updateGyro()
    }

    /**
     * Switches the output link: the old one stops (a Wi-Fi session says BYE first), the engine
     * releases everything, then the new one starts if the runtime is running.
     */
    @Synchronized
    fun setTransport(kind: TransportKind) {
        if (kind == _transport.value) return
        prefs.edit().putString(KEY_TRANSPORT, kind.name).apply()
        if (running) {
            val old = active
            if (old is WifiLink) old.stop(ByeReason.TRANSPORT_SWITCH) else old.stop()
        }
        engine.reset()
        _transport.value = kind
        if (running) active.start()
    }

    @Synchronized
    fun start() {
        running = true
        // Nothing polls the engine while no link runs: drop touchpad taps queued meanwhile.
        engine.touchpadCancel()
        active.start()
        avoidDirect = false
        updateGyro()
    }

    @Synchronized
    fun stop() {
        stopGyro()
        stopMotion()
        stopCompass()
        // A calibration cut short must not start the sensor at the next start().
        if (calibration != null) {
            calibration = null; _calibration.value = storedCalibration()
        }
        _gyroInfo.value = GyroInfo()
        active.stop()
        engine.reset()
        running = false
    }

    fun setDisplayRotation(degrees: Int) {
        gyroMouse.displayRotation = degrees
        gyroMouse.reset()
        tiltShake.displayRotation = degrees
        tiltShake.reset()
        // The pose is kept in the screen's frame, which just turned.
        gravityPose.reset()
    }

    private val tiltShake = TiltShake()
    private var accel: AccelSource? = null

    /**
     * Reads the accelerometer while input is on and a tilt or shake mapping is in the workspace or a
     * gyro display holds its pose to gravity.
     */
    private fun updateMotion() {
        if (running && inputAllowed && (engine.motionMapped || needsGravity || wifi.motion.wanted)) startMotion() else stopMotion()
        if (running && inputAllowed && needsCompass) startCompass() else stopCompass()
    }

    private var magnet: AccelSource? = null

    private fun startCompass() {
        if (magnet != null) return
        val sm = appContext.getSystemService(SensorManager::class.java) ?: return
        val source = AccelSource(sm, Sensor.TYPE_MAGNETIC_FIELD)
        if (source.start { x, y, z, _ -> onMagnetSample(x, y, z) }) magnet = source
    }

    private fun stopCompass() {
        magnet?.stop()
        magnet = null
    }

    /** A magnetometer reading (µT, Android sensor frame) for the gyro displays with a compass. */
    private fun onMagnetSample(x: Float, y: Float, z: Float) {
        // The same axes as the gyro's: right, up and toward the player in the phone's frame.
        val m = gyroMouse.motion(x, y, z)
        for (f in gyroFeeds) f.north(m.pitch, -m.yaw, -m.roll)
        if (wifi.overlay.compassWanted) wifi.overlay.north(m.pitch, -m.yaw, -m.roll)
    }

    private fun startMotion() {
        if (accel != null) return
        val sm = appContext.getSystemService(SensorManager::class.java) ?: return
        val source = AccelSource(sm)
        tiltShake.reset()
        if (source.start { x, y, z, ts -> onAccelSample(x, y, z, ts) }) accel = source
    }

    private fun stopMotion() {
        val a = accel ?: return
        a.stop()
        accel = null
        tiltShake.reset()
        engine.setTilt(0f, 0f)
    }

    private fun onAccelSample(x: Float, y: Float, z: Float, ts: Long) {
        val feeds = gyroFeeds
        val spaced = gyroSpace() != GyroSpace.LOCAL
        val overlay = wifi.overlay.wanted
        if (vis.gyroNeedsGravity || feeds.isNotEmpty() || spaced || overlay) {
            // The same axes as the gyro's: right, up and toward the player in the phone's frame.
            val m = gyroMouse.motion(x, y, z)
            val g = SensorManager.GRAVITY_EARTH * RAD_TO_DEG
            if (vis.gyroNeedsGravity) worldPose.setUp(m.pitch, -m.yaw, -m.roll, g)
            if (overlay) wifi.overlay.up(m.pitch, -m.yaw, -m.roll, g)
            for (f in feeds) f.up(m.pitch, -m.yaw, -m.roll, g)
            if (spaced) gravityPose.setUp(m.pitch, -m.yaw, -m.roll, g)
        }
        if (wifi.motion.wanted) {
            // The PC's DualShock 4 accelerometer: in g, right, up and toward the player.
            val m = gyroMouse.motion(x, y, z)
            val g = SensorManager.GRAVITY_EARTH * RAD_TO_DEG
            val p = padAccel
            PadGyro.map(padGyroSettings(), m.yaw / g, m.pitch / g, m.roll / g, p)
            wifi.motion.accel(p[0], p[1], p[2])
        }
        if (!engine.motionMapped) return
        // A layer or a held button may have tilt and shake settings of its own.
        val gs = engine.gyroSettings
        if (gs !== tiltShake.settings) tiltShake.settings = gs
        if (tiltShake.update(x, y, z, ts)) engine.shake()
        engine.setTilt(tiltShake.x, tiltShake.y)
    }

    /** Makes the resolved [profile] active, in the workspace that was last active in it. */
    private fun activate(profile: Profile) {
        store.activeProfileId = profile.id
        val index = rememberedWorkspace(profile)
        engine.reset()
        engine.profile = profile
        engine.workspaceIndex = index
        _activeProfile.value = profile
        _workspaceIndex.value = index
        applyWorkspaceGyro()
    }

    /**
     * Switches the active profile to its workspace [index] (clamped): everything held is released,
     * gyro follows the workspace, and the index is remembered for the profile.
     */
    fun switchWorkspace(index: Int) {
        val profile = _activeProfile.value
        val i = index.coerceIn(0, profile.workspaces.lastIndex)
        if (i == _workspaceIndex.value && i == engine.workspaceIndex) return
        engine.workspaceIndex = i
        _workspaceIndex.value = i
        store.setWorkspaceIndex(profile.id, i)
        applyWorkspaceGyro()
        aimGyroDisplays()
    }

    /** The saved workspace index of [profile], clamped into range. */
    private fun rememberedWorkspace(profile: Profile): Int =
        store.workspaceIndex(profile.id).coerceIn(0, profile.workspaces.lastIndex)

    /**
     * Applies [change] to the active (resolved) workspace set and saves it in the background, each
     * workspace with its own mappings ([WorkspaceMappings.settle]: a pad's mapping edit becomes its
     * workspace's controller set; a new blank workspace starts with no mappings of its own). The current workspace stays active (clamped if the edit
     * removed it). Returns the new resolved set.
     */
    fun editActiveProfile(change: (Profile) -> Profile): Profile {
        val before = _activeProfile.value
        // A workspace setting changed by hand stops following the workspace defaults.
        val changed = WorkspaceDefaults.markOwn(before, change(before))
        if (changed == before) return before
        val set = WorkspaceMappings.store(WorkspaceMappings.settle(before, changed))
        if (set != stored) {
            stored = set
            store.saveLater(set)
            store.setWorkspaceIndex(set.id, _workspaceIndex.value.coerceIn(0, set.workspaces.lastIndex))
        }
        activate(WorkspaceMappings.resolve(stored))
        return _activeProfile.value
    }

    /** Adds [button] to the button library. */
    fun addToLibrary(button: LibraryButton) {
        _buttonLibrary.value = _buttonLibrary.value + button
        store.saveButtonLibrary(_buttonLibrary.value)
    }

    /** Removes library button [id] (buttons already added from it stay). */
    fun removeFromLibrary(id: String) {
        _buttonLibrary.value = _buttonLibrary.value.filterNot { it.id == id }
        store.saveButtonLibrary(_buttonLibrary.value)
    }

    /** Live value of a phone-side source (for highlighting pressed controls). */
    fun sourceValue(source: InputSource): Float = engine.sourceValue(source)

    /**
     * Called by the input layer whenever the set of attached controllers changes. Never changes
     * the workspace.
     */
    fun onControllersChanged(list: List<AttachedController>, mouse: Boolean) {
        _controllers.value = list
        _mouseAttached.value = mouse
    }

    /**
     * The saved workspace set, or on the first start after game profiles were removed, a new one
     * merged from the profile that was active (its workspaces first, so the current one stays
     * current) and every other user profile. Old profile files are left on disk untouched.
     */
    /**
     * On the first start with mapping triggers, a set from before goes back to a fresh install's
     * config, keeping its workspaces' names and layouts ([TriggerReset]); the set as it was is
     * kept beside it as a backup. A fresh install has nothing to reset.
     */
    private fun resetForTriggers(set: Profile, existing: Boolean): Profile {
        if (store.triggerReset) return set
        if (!existing) {
            store.triggerReset = true
            return set
        }
        store.backup(set, TRIGGER_BACKUP_TAG)
        val fresh = WorkspaceMappings.store(WorkspaceSet.merge(BuiltinProfiles.default, emptyList()))
        val reset = TriggerReset.apply(set, fresh)
        store.save(reset)
        store.triggerReset = true
        return reset
    }

    private fun loadWorkspaceSet(): Profile {
        val users = store.userProfiles()
        users.firstOrNull { it.id == WorkspaceSet.ID }?.let {
            store.activeProfileId = it.id
            return it
        }
        val activeId = store.activeProfileId
        val active = users.firstOrNull { it.id == activeId }
            ?: BuiltinProfiles.all.firstOrNull { it.id == activeId }
            ?: users.firstOrNull()
            ?: BuiltinProfiles.default
        val set = WorkspaceSet.merge(active, users.filter { it.id != active.id })
        store.save(set)
        store.setWorkspaceIndex(set.id, store.workspaceIndex(active.id))
        store.activeProfileId = set.id
        return set
    }

    /**
     * The stored set, each workspace with its own mappings. On the first start after workspaces
     * got them, each one gets a copy of the mapping profile it used ([WorkspaceMappings.fromProfiles];
     * the profile file is left on disk untouched and a copy of the set as it was is kept beside
     * it). An unreadable mapping file is left alone and the set is used as it is, to try again
     * next start.
     */
    private fun loadOwnMappings(set: Profile): Profile {
        if (store.ownMappings) return upgradeButtons(set)
        if (store.hasMappingFile() && store.mappingProfiles() == null) return set
        val legacy = upgradeLegacyButtons(loadMappings(set))
        store.backup(legacy, OWN_BACKUP_TAG)
        val own = WorkspaceMappings.store(WorkspaceMappings.fromProfiles(legacy, legacyProfiles))
        store.save(own)
        store.ownMappings = true
        return own
    }

    /**
     * The mapping profiles, or on the first start after they were added, profiles made from the
     * mappings [set] still holds ([MappingProfiles.migrate]). Returns the set as it was stored with
     * them; [loadOwnMappings] then moves them into the workspaces.
     */
    private fun loadMappings(set: Profile): Profile {
        store.mappingProfiles()?.takeIf { it.isNotEmpty() }?.let {
            val before = if (store.mappingMigration < MAPPING_MIGRATION) store.readBackup(set.id, BACKUP_TAG) else null
            if (before == null) {
                store.mappingMigration = MAPPING_MIGRATION
                legacyProfiles = it
                return set
            }
            // The first move (build 0.1.61) lost keyboard and extra-pad mappings: redo it once from
            // the copy it kept, keeping its result beside the file.
            store.backupMappings("v1")
            return migrateMappings(before)
        }
        store.backup(set, BACKUP_TAG)
        return migrateMappings(set)
    }

    /** Turns the old touch button switches into tap and long press mappings ([ButtonActivators]), in profile form. */
    private fun upgradeLegacyButtons(set: Profile): Profile {
        val (upgraded, profiles) = ButtonActivators.upgrade(set, legacyProfiles)
        legacyProfiles = profiles
        return upgraded
    }

    /** Turns any old touch button switches into tap and long press mappings ([ButtonActivators]), saving the set if anything changed. */
    private fun upgradeButtons(set: Profile): Profile {
        val (upgraded, _) = ButtonActivators.upgrade(set, emptyList())
        if (upgraded === set) return set
        store.save(upgraded)
        return upgraded
    }

    private fun migrateMappings(set: Profile): Profile {
        val m = MappingProfiles.migrate(set)
        legacyProfiles = m.profiles
        store.mappingMigration = MAPPING_MIGRATION
        return m.set
    }

    /**
     * Measures the gyro's resting bias for 2 seconds; the phone must lie still meanwhile. A paused
     * gyro runs for it (the pad menu, where it is started, pauses the gyro).
     */
    @Synchronized
    fun calibrateGyro() {
        if (!running) return
        calibration = GyroCalibration()
        _calibration.value = CalibrationState.Running
        updateGyro()
        if (gyro == null) {
            calibration = null; _calibration.value = storedCalibration()
        }
    }

    @Synchronized
    fun clearGyroCalibration() {
        calibration = null
        setBias(0f, 0f, 0f)
        updateGyro()
    }

    private fun setBias(x: Float, y: Float, z: Float) {
        biasX = x; biasY = y; biasZ = z
        prefs.edit().putFloat(KEY_BIAS_X, x).putFloat(KEY_BIAS_Y, y).putFloat(KEY_BIAS_Z, z).apply()
        _calibration.value = storedCalibration()
    }

    private fun storedCalibration(): CalibrationState {
        val m = maxOf(abs(biasX), abs(biasY), abs(biasZ))
        return if (m == 0f) CalibrationState.None else CalibrationState.Calibrated(
            Math.toDegrees(m.toDouble()).toFloat()
        )
    }

    /** Gyro settings follow the active workspace. */
    private fun applyWorkspaceGyro() {
        gyroMouse.settings = _activeProfile.value.workspace(_workspaceIndex.value).gyro
        gyroMouse.reset()
        updateGyro()
    }

    /** The active workspace's gyro is off completely ([GyroMode.SENSOR_OFF]): the sensor need not run. */
    private fun gyroOff(): Boolean {
        val ws = _activeProfile.value.workspace(_workspaceIndex.value)
        // A layer that turns gyro on needs the sensor running before it is used.
        return ws.gyro.mode == GyroMode.SENSOR_OFF && ws.sets.none { s ->
            WorkspaceDefaults.gyroKey("mode") in WorkspaceDefaults.layerOwn(s) && s.settings?.gyro?.mode != GyroMode.SENSOR_OFF
        }
    }

    /**
     * Runs the gyro sensor while its samples are used: input is allowed or a calibration runs.
     * Otherwise (only while "send input only while the pad is open" is on and the pad is not on
     * screen, or with the workspace's gyro off completely) it stops, to save CPU and battery. The
     * other gyro modes, toggle and ratchets never stop it, so aiming never waits for the sensor.
     */
    @Synchronized
    private fun updateGyro() {
        updateMotion()
        // The PC's phone overlay shows the phone turning even where the gyro moves nothing.
        // An acceleration graph on screen reads the gyro too (its live line), moving nothing while input isn't allowed.
        // The PC's DualShock 4 carries the phone's turn whatever the workspace's gyro does.
        // Input not allowed: its gyro rests (the sender is woken so the stop goes out now).
        if (!(running && inputAllowed) && wifi.motion.still()) engine.onChange?.run()
        if (running && ((inputAllowed && (!gyroOff() || wifi.overlay.wanted || wifi.motion.wanted)) || calibration != null || accelWatchers.get() > 0)) {
            startGyro()
        } else if (running) {
            stopGyro()
            if (_gyroInfo.value.source != UNAVAILABLE) {
                _gyroInfo.value = GyroInfo(source = if (gyroOff()) GYRO_OFF else PAUSED)
            }
        }
    }

    private fun startGyro() {
        if (gyro != null) return
        val sm = appContext.getSystemService(SensorManager::class.java) ?: return
        val candidates = GyroSource.candidates(sm, _gyroPollHz.value)
        // While the direct channel is capped the listener goes first; the direct channel stays a last resort.
        val ordered = if (avoidDirect) candidates.sortedBy { it is DirectChannelGyroSource } else candidates
        for (source in ordered) {
            // A new source is a new time base for dt and the rate; reset before its thread starts.
            lastGyroTs = 0L
            rateWindow.reset()
            gyroMouse.reset() // no sub-count remainder or smoothing left from before a pause
            if (source.start { x, y, z, ts -> onGyroSample(source, x, y, z, ts) }) {
                gyro = source
                _gyroPollUsed.value = source is DirectChannelGyroSource
                if (source is DirectChannelGyroSource) {
                    // A direct channel that opens but never delivers (seen with gralloc buffers) would leave
                    // the gyro dead: the rate check below never closes a window without samples.
                    val before = gyroSamples
                    main.postDelayed({ if (gyroSamples == before) onDirectCapped(source, 0) }, SILENT_DIRECT_MS)
                }
                val name = if (avoidDirect && source !is DirectChannelGyroSource) {
                    "${source.name}; " + if (cappedHz == 0) "direct channel gave no samples" else "direct channel limited to ~$cappedHz Hz"
                } else source.name
                _gyroInfo.value = GyroInfo(source = name)
                return
            }
        }
        _gyroInfo.value = GyroInfo(source = UNAVAILABLE)
    }

    /** Stops the running source, waiting briefly for its thread to deliver its last sample. */
    private fun stopGyro() {
        gyro?.stop()
        gyro = null
        // No sample comes to bring the gyro's stick or trigger back to rest, and a last one may
        // have set it after an engine reset. Clearing axes already at rest changes nothing.
        clearGyroPad()
    }

    /** The direct channel [source] stayed below the listener's rate: switch to the listener. */
    @Synchronized
    private fun onDirectCapped(source: GyroSource, hz: Int) {
        // Already avoiding it: the direct channel runs only because the listener failed to start.
        if (gyro !== source || avoidDirect) return
        avoidDirect = true
        cappedHz = hz
        stopGyro()
        startGyro()
    }

    init {
        // Profiles from before Toggle gyro buttons: the gyro corner becomes one in every workspace.
        if (stored.corners.any { it.id == NavCorners.GYRO }) editActiveProfile { NavCorners.gyroCornerToButtons(it) }
        // The PC's phone overlay turned on or off: the gyro and accelerometer may be needed (or no longer).
        wifi.overlay.onWantedChange = Runnable { main.post { updateGyro() } }
        // The PC's pad became a DualShock 4 (or no longer is): its gyro and accelerometer need the sensors.
        wifi.motion.onWantedChange = Runnable { main.post { updateGyro() } }
    }

    companion object {
        private const val KEY_RATE = "report_rate_hz"
        private const val KEY_WIFI_RATE = "wifi_rate_hz"
        private const val KEY_GYRO_POLL = "gyro_poll_hz"
        private const val KEY_PC_CONTROLLER_GYRO = "pc_controller_gyro"
        private const val KEY_GYRO_TIGHTENING = "gyro_tightening"
        private const val KEY_SENS_CAP_RWS = "sens_cap_rws"
        private const val KEY_SENS_CAP_COUNTS = "sens_cap_counts"
        const val DEFAULT_SENS_CAP_RWS = 10f
        const val MIN_SENS_CAP_RWS = 1f
        const val MAX_SENS_CAP_RWS = 50f
        const val DEFAULT_SENS_CAP_COUNTS = 64f
        const val MIN_SENS_CAP_COUNTS = 4f
        const val MAX_SENS_CAP_COUNTS = 250f
        private const val KEY_GYRO_SMOOTHING = "gyro_smoothing"

        /** A sensor time further than this from elapsed realtime (us) is on another clock. */
        private const val SENSOR_CLOCK_SLACK_US = 1_000_000L
        private const val SILENT_DIRECT_MS = 1500L
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_ORIENTATION = "screen_orientation"
        private const val KEY_PAD_ONLY_INPUT = "pad_only_input"
        private const val KEY_IDLE_STOP_MINUTES = "idle_stop_minutes"
        const val DEFAULT_IDLE_STOP_MINUTES = 10

        /** The choices for [idleStopMinutes]; 0 = Off. */
        val IDLE_STOP_CHOICES = listOf(0, 5, 10, 30)
        private const val KEY_START_FULLSCREEN = "start_fullscreen"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_BLACK_SCREEN_IDLE = "black_screen_idle"
        private const val KEY_BLACK_SCREEN_SECONDS = "black_screen_seconds"
        const val DEFAULT_BLACK_SCREEN_SECONDS = 60

        /** The idle times the Black screen setting offers. */
        val BLACK_SCREEN_SECONDS = listOf(30, 60, 120, 300)
        private const val KEY_CORNER_AUTO_HIDE = "corner_auto_hide"
        private const val KEY_EDIT_LAYOUT_LOCKED = "edit_layout_locked"
        private const val KEY_CAPSULE_ORDER = "capsule_order"
        private const val KEY_TOUCH_VISUALS = "touch_visuals"
        private val visualsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private const val KEY_UI_PREFIX = "ui."
        private const val TURN_TICK_MS = 4L
        private const val KEY_BIAS_X = "gyro_bias_x"
        private const val KEY_BIAS_Y = "gyro_bias_y"
        private const val KEY_BIAS_Z = "gyro_bias_z"

        /** Version of [MappingProfiles.migrate]'s rules; a phone that ran an older one redoes it from [BACKUP_TAG]. */
        private const val MAPPING_MIGRATION = 2
        private const val BACKUP_TAG = "pre-mappings"
        private const val OWN_BACKUP_TAG = "pre-own-mappings"
        private const val TRIGGER_BACKUP_TAG = "pre-trigger-reset"
        private const val PAUSED = "Paused while the pad is not open"
        private const val GYRO_OFF = "Off in this workspace"
        private const val UNAVAILABLE = "Unavailable"

        /** [GyroMouse.motion] scales by this (it takes rad/s): gravity in its units. */
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }

    /** The routes and gyro settings [cameraMapNow] was made from, and its value (gyro thread). */
    private var cameraMapRoutes: List<Mapping>? = null
    private var cameraMapGyro: GyroSettings? = null
    private var cameraMapValue = GyroDisplayFeed.DEFAULT_CAMERA_MAP

    /** Which gyro axes move the mouse sideways and up and down now ([GyroDisplayFeed.cameraMapOf]), made again only when they change. */
    private fun cameraMapNow(): Int {
        val routes = engine.gyroRoutes()
        val gs = engine.gyroSettings
        if (routes !== cameraMapRoutes || gs !== cameraMapGyro) {
            cameraMapRoutes = routes
            cameraMapGyro = gs
            cameraMapValue = GyroDisplayFeed.cameraMapOf(routes, gs)
        }
        return cameraMapValue
    }

    /** Feeds one bias-corrected gyro sample to the gyro displays and the parallax edge; [axes] move the mouse now. */
    private fun feedVisuals(v: TouchVisuals, feeds: Array<GyroDisplayFeed>, x: Float, y: Float, z: Float, dt: Float, axes: Int) {
        val ratchets = if (inputAllowed) engine.ratchetsHeld else 0
        val pressed = ratchets and poseRatchets.inv() != 0
        poseRatchets = ratchets
        gyroLiveAxes = axes
        val m = gyroMouse.motion(x, y, z)
        val gs = if (axes != 0) engine.gyroSettings else null
        val map = if (axes != 0) cameraMapNow() else 0
        val out = engine.lastOutput
        for (f in feeds) {
            if (map != 0) f.cameraMap = map
            f.firing = f.display.kind == com.oleroma.gyrophone.core.model.GyroDisplayKind.LASER &&
                inputAllowed && com.oleroma.gyrophone.core.engine.LaserFire.held(f.display.laserFire, out)
            f.feed(m.yaw, m.pitch, m.roll, dt, axes, pressed, ratchets != 0, gs)
        }
        if (dt == 0f || !v.gyroEdge) return
        if (v.gyroEdgeTilt) worldPose.fuse(m.yaw, m.pitch, m.roll, dt, GyroDisplayFeed.HEADING_EASE_S)
        else gyroSway.update(m.yaw, m.pitch, m.roll, dt, v.gyroEdgeSpringMs / 1000f)
    }

    /**
     * The last sample went to the PC ([com.oleroma.gyrophone.core.net.PcGyroFeed.active]): the PC
     * runs the gyro math, so the phone neither routes it nor shows it. Gyro thread only.
     */
    private var pcGyroRouting = false

    /** The gyro deflects game pad axes now ([MappingEngine.setGyroAxes]): they go back to rest when it stops. */
    private var gyroPadSet = false

    private fun clearGyroPad() {
        engine.setGyroAxes(FloatArray(GamepadAxis.entries.size))
        gyroPadSet = false
    }

    /**
     * "Use PC controller for gyro ratchet" with a PC that runs the gyro: one bias-corrected sample
     * ([gx], [gy], [gz] in rad/s, sensor time [ts]) goes to [pc] turned for the screen and in [space],
     * with the phone's own gyro axes [axes] and the gyro settings in effect now, while the gyro may
     * move ([allowed] and not "Gyro off"); otherwise [pc] is told it is still. The phone adds no
     * gyro motion itself: entering this, its gyro mouse motion and stick deflection are cleared once.
     */
    private var pcHeldFor: List<Pair<Int, GyroSettings>>? = null
    private var pcHeldRoutes: List<Mapping>? = null

    private fun pcGyroSample(pc: PcGyroFeed, allowed: Boolean, axes: Int, gx: Float, gy: Float, gz: Float, dt: Float, ts: Long, space: GyroSpace) {
        if (!pcGyroRouting) {
            pcGyroRouting = true
            gyroMouse.reset()
            if (gyroPadSet) clearGyroPad()
        }
        val gs = engine.gyroSettings
        if (!allowed || gs.mode == GyroMode.SENSOR_OFF) {
            // Wake the sender (engine.onChange) so MAY_MOVE going off goes out now, not at the keepalive.
            if (pc.still()) engine.onChange?.run()
            return
        }
        val m = gyroMouse.inSpace(gyroMouse.motion(gx, gy, gz), space, upNow)
        val routes = engine.gyroRoutes()
        val cfg = gyroMouse.config(gs, routes)
        // The PC controller's buttons with their own settings while held: made again only when they or the routes change.
        val held = engine.pcHeldGyro
        if (held !== pcHeldFor || routes !== pcHeldRoutes) {
            pcHeldFor = held; pcHeldRoutes = routes
            pc.held = held.map { (place, s) -> place to GyroMath.Config.of(s, routes) }
            engine.onChange?.run()
        }
        // Sensor time is elapsed realtime on current phones (the clock PING and the clock offset use);
        // a sensor on another clock gets the time it arrived instead.
        val now = WifiPump.phoneUs()
        val sampleUs = (ts / 1000).takeIf { abs(it - now) < SENSOR_CLOCK_SLACK_US } ?: now
        // One lock: the next tail never has this sample with the settings before it.
        synchronized(pc.lock) {
            pc.setConfig(cfg, _gyroTightening.value, _gyroSmoothing.value)
            pc.add(m.yaw, m.pitch, m.roll, dt, axes, sampleUs)
        }
        // Nothing else changes in the engine for a sample here, so wake the sender for it: the tail
        // holds only the last few samples, and a sender waiting for the keepalive would skip most.
        engine.onChange?.run()
    }

    // The acceleration graphs' live line: turning speed (deg/s, smoothed over ~40 ms) of the whole
    // phone and of yaw, pitch and roll (by MotionAxis ordinal), in the workspace's gyro space.
    private val liveRates = FloatArray(4)
    @Volatile private var liveRatesNs = 0L

    /** An acceleration graph is shown: the gyro thread keeps [liveGyroSpeed] up to date until [unwatchAccel]. */
    fun watchAccel() { accelWatchers.incrementAndGet(); main.post { updateGyro() } }
    fun unwatchAccel() { accelWatchers.decrementAndGet(); main.post { updateGyro() } }

    /**
     * The turning speed now (deg/s): the whole phone's ([axis] null) or one axis's, or null when no
     * gyro sample came lately (the sensor is off, or nothing watches).
     */
    fun liveGyroSpeed(axis: MotionAxis?): Float? {
        if (System.nanoTime() - liveRatesNs > 250_000_000L) return null
        return liveRates[if (axis == null || axis == MotionAxis.NONE) 3 else axis.ordinal]
    }

    private fun liveSpeed(yaw: Float, pitch: Float, roll: Float, dt: Float, @Suppress("UNUSED_PARAMETER") ts: Long) {
        val k = if (dt <= 0f) 1f else (dt / 0.04f).coerceAtMost(1f)
        val r = liveRates
        r[0] += k * (abs(yaw) - r[0])
        r[1] += k * (abs(pitch) - r[1])
        r[2] += k * (abs(roll) - r[2])
        r[3] += k * (sqrt(yaw * yaw + pitch * pitch + roll * roll) - r[3])
        liveRatesNs = System.nanoTime()
    }

    private fun onGyroSample(source: GyroSource, x: Float, y: Float, z: Float, ts: Long) {
        gyroSamples++
        val dt = if (lastGyroTs == 0L) 0f else ((ts - lastGyroTs) / 1e9f).coerceIn(0f, 0.05f)
        lastGyroTs = ts
        debug.trace?.gyro((x - biasX) * RAD_TO_DEG, (y - biasY) * RAD_TO_DEG, (z - biasZ) * RAD_TO_DEG)
        calibration?.let { cal ->
            when (val r = cal.add(x, y, z, ts)) {
                null -> Unit
                is GyroCalibration.Result.Done -> {
                    calibration = null; setBias(r.x, r.y, r.z); main.post { updateGyro() }
                }

                GyroCalibration.Result.Moved -> {
                    calibration = null; _calibration.value = CalibrationState.Moved; main.post { updateGyro() }
                }
            }
        }
        val allowed = inputAllowed && !measuring
        val axes = if (allowed) engine.gyroAxes else 0
        val active = axes != 0
        val v = vis
        val feeds = gyroFeeds
        val pc = wifi.pcGyro
        val pcMode = pc.active
        // With the PC doing the gyro math the phone's gyro displays are off: what moves is the PC's to say.
        if (pcMode) gyroLiveAxes = 0
        else if (v.gyroEdge || feeds.isNotEmpty()) feedVisuals(v, feeds, x - biasX, y - biasY, z - biasZ, dt, axes)
        val overlay = wifi.overlay
        if (overlay.wanted && !measuring) {
            // The PC's phone overlay turns its pose from these, with its own settings.
            val m = gyroMouse.motion(x - biasX, y - biasY, z - biasZ)
            overlay.gyro(m.yaw, m.pitch, m.roll, dt, axes, inputAllowed && engine.ratchetsHeld != 0)
            overlay.gyroActive = active
            overlay.cameraMap = cameraMapNow()
            val gs = engine.gyroSettings
            overlay.rws(
                gs.rwsOf(gs.sensitivityOf(MotionAxis.YAW)) ?: 0f, gs.rwsOf(gs.sensitivityOf(MotionAxis.PITCH)) ?: 0f,
                gs.rwsOf(gs.sensitivityOf(MotionAxis.ROLL)) ?: 0f,
            )
        }
        // The PC's DualShock 4 gyro: every sample as measured (bias removed), in deg/s about the
        // screen's right, up and toward-the-player axes, sent at once.
        val motion = wifi.motion
        if (motion.wanted) {
            if (allowed) {
                val m = gyroMouse.motion(x - biasX, y - biasY, z - biasZ)
                val p = padGyro
                PadGyro.map(padGyroSettings(), m.yaw, m.pitch, m.roll, p)
                motion.add(p[0], p[1], p[2], ts / 1000)
                engine.onChange?.run()
            } else if (motion.still()) engine.onChange?.run()
        }
        // World and player space read the phone's pose against gravity.
        val space = gyroSpace()
        if (space != GyroSpace.LOCAL) {
            val m = gyroMouse.motion(x - biasX, y - biasY, z - biasZ)
            gravityPose.rotate(m.yaw, m.pitch, m.roll, dt)
            gravityPose.up(upNow)
            gravityPoseUsed = true
        } else if (gravityPoseUsed) {
            // Not followed in local space: switched back, it starts again from gravity.
            gravityPose.reset()
            gravityPoseUsed = false
        }
        if (accelWatchers.get() > 0) {
            val m = gyroMouse.inSpace(gyroMouse.motion(x - biasX, y - biasY, z - biasZ), space, upNow)
            liveSpeed(m.yaw, m.pitch, m.roll, dt, ts)
        }
        if (pcMode) {
            pcGyroSample(pc, allowed, axes, x - biasX, y - biasY, z - biasZ, dt, ts, space)
        } else {
            pcGyroRouting = false
            // An axis a ratchet just turned off may glide on (inertia) while the others are off.
            if (active || (allowed && gyroMouse.coasting)) {
                // A layer in use may set gyro settings of its own.
                val gs = engine.gyroSettings
                if (gs !== gyroMouse.settings) gyroMouse.settings = gs
                val routes = engine.gyroRoutes()
                val r = gyroMouse.route(x - biasX, y - biasY, z - biasZ, dt, routes, axes, space, upNow)
                if (r.dx != 0 || r.dy != 0 || r.wheel != 0 || r.hwheel != 0) engine.addMouseMotion(r.dx, r.dy, r.wheel, r.hwheel, fromGyro = active)
                engine.setGyroAxes(r.pad)
                gyroPadSet = true
            } else {
                gyroMouse.reset()
                if (gyroPadSet) clearGyroPad()
            }
        }
        if (rateWindow.add(ts)) {
            _gyroInfo.value = _gyroInfo.value.copy(rateHz = rateWindow.rateHz, active = active)
            // Sources are switched on the main thread, never from the sampling thread itself.
            if (rateWindow.capped && source is DirectChannelGyroSource) {
                val hz = rateWindow.rateHz
                main.post { onDirectCapped(source, hz) }
            }
        }
    }
}
