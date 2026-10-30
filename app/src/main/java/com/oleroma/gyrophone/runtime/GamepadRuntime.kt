package com.oleroma.gyrophone.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.oleroma.gyrophone.core.engine.CountStream
import com.oleroma.gyrophone.core.engine.GyroCalibration
import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.engine.GyroPose
import com.oleroma.gyrophone.core.engine.GyroSway
import com.oleroma.gyrophone.core.engine.MappingEngine
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
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.WorkspaceSet
import com.oleroma.gyrophone.ui.TouchVisuals
import com.oleroma.gyrophone.data.ProfileStore
import com.oleroma.gyrophone.core.net.ByeReason
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A game controller attached to the phone (e.g. GameSir X5). */
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
    private val appContext = context.applicationContext

    val store = ProfileStore(appContext)
    val hid = HidDeviceManager(appContext)

    /** The mapping profiles of a set saved before workspaces owned their mappings; only read by [loadOwnMappings]. */
    private var legacyProfiles: List<MappingProfile> = emptyList()

    /**
     * All workspaces live in one profile ([WorkspaceSet]), each with its own mappings, stored as
     * [WorkspaceMappings.store] keeps them. See [loadWorkspaceSet] and [loadOwnMappings].
     */
    private var stored: Profile = loadOwnMappings(loadWorkspaceSet())

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

    private val _startFullscreen = MutableStateFlow(prefs.getBoolean(KEY_START_FULLSCREEN, false))

    /** Open the pad in fullscreen when the app starts. */
    val startFullscreen: StateFlow<Boolean> = _startFullscreen

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

    /** Some gyro display holds its pose to gravity (and so needs the accelerometer). */
    private val needsGravity: Boolean get() = vis.gyroNeedsGravity || gyroFeeds.any { it.display.needsGravity }

    /** Some gyro display holds its heading to north (and so needs the magnetometer). */
    private val needsCompass: Boolean get() = gyroFeeds.any { it.display.needsCompass }

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

    /** Plays the engine's haptic mappings while a PC is connected. */
    private val haptics = PhoneHaptics(appContext) { _linkState.value is LinkState.Connected }

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
    private val rateWindow = GyroRateWindow()

    /** When set, the next controller input is captured for the mapping editor instead of sent. */
    @Volatile
    var learnListener: ((InputSource) -> Unit)? = null

    /** When set, the next key-down of a physical keyboard or pad is delivered here as its raw Android keycode and swallowed. */
    @Volatile
    var keyLearnListener: ((androidKeyCode: Int) -> Unit)? = null

    init {
        engine.hapticSink = haptics
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

    fun setPadOnlyInput(on: Boolean) {
        prefs.edit().putBoolean(KEY_PAD_ONLY_INPUT, on).apply()
        _padOnlyInput.value = on
        updateInputAllowed()
    }

    fun setStartFullscreen(on: Boolean) {
        prefs.edit().putBoolean(KEY_START_FULLSCREEN, on).apply()
        _startFullscreen.value = on
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
        padShown = shown; updateInputAllowed()
    }

    @Synchronized
    fun setForeground(resumed: Boolean) {
        foreground = resumed
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
    }

    private val tiltShake = TiltShake()
    private var accel: AccelSource? = null

    /**
     * Reads the accelerometer while input is on and a tilt or shake mapping is in the workspace or a
     * gyro display holds its pose to gravity.
     */
    private fun updateMotion() {
        if (running && inputAllowed && (engine.motionMapped || needsGravity)) startMotion() else stopMotion()
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
        if (vis.gyroNeedsGravity || feeds.isNotEmpty()) {
            // The same axes as the gyro's: right, up and toward the player in the phone's frame.
            val m = gyroMouse.motion(x, y, z)
            val g = SensorManager.GRAVITY_EARTH * RAD_TO_DEG
            if (vis.gyroNeedsGravity) worldPose.setUp(m.pitch, -m.yaw, -m.roll, g)
            for (f in feeds) f.up(m.pitch, -m.yaw, -m.roll, g)
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
    }

    /** The saved workspace index of [profile], clamped into range. */
    private fun rememberedWorkspace(profile: Profile): Int =
        store.workspaceIndex(profile.id).coerceIn(0, profile.workspaces.lastIndex)

    /**
     * Applies [change] to the active (resolved) workspace set and saves it in the background, each
     * workspace with its own mappings ([WorkspaceMappings.settle]: a pad's mapping edit becomes its
     * workspace's controller set; a new blank workspace starts with the current one's keyboard,
     * controller and touchpad mappings). The current workspace stays active (clamped if the edit
     * removed it). Returns the new resolved set.
     */
    fun editActiveProfile(change: (Profile) -> Profile): Profile {
        val before = _activeProfile.value
        // A workspace setting changed by hand stops following the workspace defaults.
        val changed = WorkspaceDefaults.markOwn(before, change(before))
        if (changed == before) return before
        val current = before.workspaces.getOrNull(_workspaceIndex.value)
        val set = WorkspaceMappings.store(WorkspaceMappings.settle(before, changed, current))
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
        if (running && ((inputAllowed && !gyroOff()) || calibration != null)) {
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
        val candidates = GyroSource.candidates(sm)
        // While the direct channel is capped the listener goes first; the direct channel stays a last resort.
        val ordered = if (avoidDirect) candidates.sortedBy { it is DirectChannelGyroSource } else candidates
        for (source in ordered) {
            // A new source is a new time base for dt and the rate; reset before its thread starts.
            lastGyroTs = 0L
            rateWindow.reset()
            gyroMouse.reset() // no sub-count remainder or smoothing left from before a pause
            if (source.start { x, y, z, ts -> onGyroSample(source, x, y, z, ts) }) {
                gyro = source
                val name = if (avoidDirect && source !is DirectChannelGyroSource) {
                    "${source.name}; direct channel limited to ~$cappedHz Hz"
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
    }

    companion object {
        private const val KEY_RATE = "report_rate_hz"
        private const val KEY_WIFI_RATE = "wifi_rate_hz"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_ORIENTATION = "screen_orientation"
        private const val KEY_PAD_ONLY_INPUT = "pad_only_input"
        private const val KEY_START_FULLSCREEN = "start_fullscreen"
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
        private const val PAUSED = "Paused while the pad is not open"
        private const val GYRO_OFF = "Off in this workspace"
        private const val UNAVAILABLE = "Unavailable"

        /** [GyroMouse.motion] scales by this (it takes rad/s): gravity in its units. */
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }

    /** Feeds one bias-corrected gyro sample to the gyro displays and the parallax edge; [axes] move the mouse now. */
    private fun feedVisuals(v: TouchVisuals, feeds: Array<GyroDisplayFeed>, x: Float, y: Float, z: Float, dt: Float, axes: Int) {
        val ratchets = if (inputAllowed) engine.ratchetsHeld else 0
        val pressed = ratchets and poseRatchets.inv() != 0
        poseRatchets = ratchets
        gyroLiveAxes = axes
        val m = gyroMouse.motion(x, y, z)
        val gs = if (axes != 0) engine.gyroSettings else null
        for (f in feeds) f.feed(m.yaw, m.pitch, m.roll, dt, axes, pressed, ratchets != 0, gs)
        if (dt == 0f || !v.gyroEdge) return
        if (v.gyroEdgeTilt) worldPose.fuse(m.yaw, m.pitch, m.roll, dt, GyroDisplayFeed.HEADING_EASE_S)
        else gyroSway.update(m.yaw, m.pitch, m.roll, dt, v.gyroEdgeSpringMs / 1000f)
    }

    /** The gyro deflects game pad axes now ([MappingEngine.setGyroAxes]): they go back to rest when it stops. */
    private var gyroPadSet = false

    private fun clearGyroPad() {
        engine.setGyroAxes(FloatArray(GamepadAxis.entries.size))
        gyroPadSet = false
    }

    private fun onGyroSample(source: GyroSource, x: Float, y: Float, z: Float, ts: Long) {
        val dt = if (lastGyroTs == 0L) 0f else ((ts - lastGyroTs) / 1e9f).coerceIn(0f, 0.05f)
        lastGyroTs = ts
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
        if (v.gyroEdge || feeds.isNotEmpty()) feedVisuals(v, feeds, x - biasX, y - biasY, z - biasZ, dt, axes)
        // An axis a ratchet just turned off may glide on (inertia) while the others are off.
        if (active || (allowed && gyroMouse.coasting)) {
            // A layer in use may set gyro settings of its own.
            val gs = engine.gyroSettings
            if (gs !== gyroMouse.settings) gyroMouse.settings = gs
            val routes = engine.gyroRoutes()
            val r = gyroMouse.route(x - biasX, y - biasY, z - biasZ, dt, routes, axes)
            if (r.dx != 0 || r.dy != 0 || r.wheel != 0 || r.hwheel != 0) engine.addMouseMotion(r.dx, r.dy, r.wheel, r.hwheel)
            engine.setGyroAxes(r.pad)
            gyroPadSet = true
        } else {
            gyroMouse.reset()
            if (gyroPadSet) clearGyroPad()
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
