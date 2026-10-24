package com.oleroma.gyrophone.runtime

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.oleroma.gyrophone.core.engine.GyroCalibration
import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownController
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.WorkspaceSet
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
import com.oleroma.gyrophone.sensor.DirectChannelGyroSource
import com.oleroma.gyrophone.sensor.GyroSource
import com.oleroma.gyrophone.wifi.WifiLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val _padPresets = MutableStateFlow(store.padPresets())
    /** The user's own pad presets (the builtins come from [PadPresets.builtins]). */
    val padPresets: StateFlow<List<PadPreset>> = _padPresets

    private val _mappingProfiles = MutableStateFlow<List<MappingProfile>>(emptyList())
    /** Every mapping profile (touch, keyboard and controller mappings); never empty. */
    val mappingProfiles: StateFlow<List<MappingProfile>> = _mappingProfiles

    /**
     * All workspaces live in one profile ([WorkspaceSet]), stored without mappings: each workspace
     * names its [MappingProfile]. See [loadWorkspaceSet] and [loadMappings].
     */
    private var stored: Profile = loadMappings(loadWorkspaceSet())

    private val _activeProfile = MutableStateFlow(MappingProfiles.resolve(stored, _mappingProfiles.value))
    /** The workspace set with each workspace's mapping profile filled in ([MappingProfiles.resolve]). */
    val activeProfile: StateFlow<Profile> = _activeProfile

    private val _workspaceIndex = MutableStateFlow(rememberedWorkspace(_activeProfile.value))
    /** Index of the active workspace in [activeProfile] (always in range). */
    val workspaceIndex: StateFlow<Int> = _workspaceIndex

    val engine = MappingEngine(_activeProfile.value).also { it.workspaceIndex = _workspaceIndex.value }
    private val pump = ReportPump(engine, hid)
    private val gyroMouse = GyroMouse(_activeProfile.value.workspace(_workspaceIndex.value).gyro)
    private val prefs = appContext.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)

    private val _reportRateHz = MutableStateFlow(
        prefs.getInt(KEY_RATE, ReportPump.DEFAULT_RATE_HZ).takeIf { it in ReportPump.RATES_HZ } ?: ReportPump.DEFAULT_RATE_HZ,
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
        prefs.getString(KEY_ORIENTATION, null)?.let { name -> ScreenOrientation.entries.firstOrNull { it.name == name } }
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
    @Volatile var inputAllowed = !_padOnlyInput.value
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _linkState = MutableStateFlow<LinkState>(LinkState.Off)
    /** State of the active link. */
    val linkState: StateFlow<LinkState> = _linkState

    @Volatile private var running = false

    private val active: OutputLink get() = link(_transport.value)

    private fun link(kind: TransportKind): OutputLink = when (kind) {
        TransportKind.BLUETOOTH -> bluetooth
        TransportKind.WIFI -> wifi
    }

    fun linkStats(): LinkStats = active.stats()

    private val _controllers = MutableStateFlow<List<AttachedController>>(emptyList())
    val controllers: StateFlow<List<AttachedController>> = _controllers

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
    @Volatile private var biasX = prefs.getFloat(KEY_BIAS_X, 0f)
    @Volatile private var biasY = prefs.getFloat(KEY_BIAS_Y, 0f)
    @Volatile private var biasZ = prefs.getFloat(KEY_BIAS_Z, 0f)
    @Volatile private var calibration: GyroCalibration? = null
    private val _calibration = MutableStateFlow(storedCalibration())
    val calibrationState: StateFlow<CalibrationState> = _calibration

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
    @Volatile var learnListener: ((InputSource) -> Unit)? = null

    /** When set, the next key-down of a physical keyboard or pad is delivered here as its raw Android keycode and swallowed. */
    @Volatile var keyLearnListener: ((androidKeyCode: Int) -> Unit)? = null

    init {
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

    fun setCapsuleOrder(order: String) {
        prefs.edit().putString(KEY_CAPSULE_ORDER, order).apply()
        _capsuleOrder.value = order
    }

    fun setPadShown(shown: Boolean) { padShown = shown; updateInputAllowed() }

    @Synchronized fun setForeground(resumed: Boolean) {
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
    @Synchronized private fun updateInputAllowed() {
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
    @Synchronized fun setTransport(kind: TransportKind) {
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

    @Synchronized fun start() {
        running = true
        // Nothing polls the engine while no link runs: drop touchpad taps queued meanwhile.
        engine.touchpadCancel()
        active.start()
        avoidDirect = false
        updateGyro()
    }

    @Synchronized fun stop() {
        stopGyro()
        // A calibration cut short must not start the sensor at the next start().
        if (calibration != null) { calibration = null; _calibration.value = storedCalibration() }
        _gyroInfo.value = GyroInfo()
        active.stop()
        engine.reset()
        running = false
    }

    fun setDisplayRotation(degrees: Int) {
        gyroMouse.displayRotation = degrees
        gyroMouse.reset()
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
     * Applies [change] to the active (resolved) workspace set and saves it: layout, device and
     * settings edits go to the set, mapping edits to each workspace's mapping profile
     * ([MappingProfiles.adopt], [MappingProfiles.absorb]). The current workspace stays active (clamped if the edit removed
     * it). Returns the new resolved set.
     */
    fun editActiveProfile(change: (Profile) -> Profile): Profile {
        val before = _activeProfile.value
        val changed = change(before)
        if (changed == before) return before
        // An added workspace gets a profile of its own mappings, or the current workspace's.
        val fallback = before.workspace(_workspaceIndex.value).mappingProfileId
        val (after, withNew) = MappingProfiles.adopt(_mappingProfiles.value, changed, fallback)
        commit(MappingProfiles.strip(after), MappingProfiles.absorb(withNew, before, after))
        return _activeProfile.value
    }

    /**
     * Saves [set] (without mappings; in the background, like every save of the set after start)
     * and [profiles], and makes the resolved set active.
     */
    private fun commit(set: Profile, profiles: List<MappingProfile>) {
        if (profiles != _mappingProfiles.value) {
            _mappingProfiles.value = profiles
            store.saveMappingProfiles(profiles)
        }
        if (set != stored) {
            stored = set
            store.saveLater(set)
            store.setWorkspaceIndex(set.id, _workspaceIndex.value.coerceIn(0, set.workspaces.lastIndex))
        }
        activate(MappingProfiles.resolve(stored, _mappingProfiles.value))
    }

    /** Replaces the mapping profile with [profile]'s id (name and mappings); every workspace using it follows. */
    fun updateMappingProfile(profile: MappingProfile) {
        if (_mappingProfiles.value.none { it.id == profile.id }) return
        commit(stored, _mappingProfiles.value.map { if (it.id == profile.id) profile else it })
    }

    /**
     * Adds a mapping profile named [name] with [from]'s mappings (none: the builtin touch mappings
     * and the standard controller set) and makes workspace [wsIndex] use it. Returns it.
     */
    fun newMappingProfile(name: String, from: MappingProfile?, wsIndex: Int = workspaceIndex.value): MappingProfile {
        val id = MappingProfiles.newId(_mappingProfiles.value.map { it.id })
        val trimmed = name.trim().ifBlank { "Profile" }
        val created = from?.copy(id = id, name = trimmed) ?: MappingProfiles.fresh(id, trimmed, stored)
        commit(useMapping(stored, wsIndex, id), _mappingProfiles.value + created)
        return created
    }

    /** Makes workspace [wsIndex] use mapping profile [id]. */
    fun selectMappingProfile(id: String, wsIndex: Int = workspaceIndex.value) {
        if (_mappingProfiles.value.none { it.id == id }) return
        commit(useMapping(stored, wsIndex, id), _mappingProfiles.value)
    }

    /** Deletes mapping profile [id] unless it is the last; workspaces that used it switch to the first left. */
    fun deleteMappingProfile(id: String) {
        val left = _mappingProfiles.value.filterNot { it.id == id }
        if (left.isEmpty() || left.size == _mappingProfiles.value.size) return
        val set = stored.copy(workspaces = stored.workspaces.map { w ->
            if (w.mappingProfileId == id || w.mappingProfileId == null) w.copy(mappingProfileId = left.first().id) else w
        })
        commit(set, left)
    }

    private fun useMapping(set: Profile, wsIndex: Int, id: String): Profile =
        set.updateWorkspace(wsIndex) { it.copy(mappingProfileId = id) }

    /** Saves [mappings] as a new user pad preset named [name], attached to [device]; returns it. */
    fun savePadPreset(name: String, mappings: List<Mapping>, device: PadDevice? = null): PadPreset {
        val preset = PadPreset(
            PadPresets.newId(_padPresets.value.map { it.id }), name.trim().ifBlank { "My preset" }, mappings,
            device = device?.match, deviceName = device?.name, deviceFamily = device?.family,
        )
        _padPresets.value = _padPresets.value + preset
        store.savePadPresets(_padPresets.value)
        return preset
    }

    /** Removes a user pad preset (a controller set loaded from it is kept). */
    fun deletePadPreset(id: String) {
        _padPresets.value = _padPresets.value.filterNot { it.id == id }
        store.savePadPresets(_padPresets.value)
    }

    /** Live value of a phone-side source (for highlighting pressed controls). */
    fun sourceValue(source: InputSource): Float = engine.sourceValue(source)

    /**
     * Called by the input layer whenever the set of attached controllers changes. Never changes
     * the workspace.
     */
    fun onControllersChanged(list: List<AttachedController>) {
        _controllers.value = list
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
     * The mapping profiles, or on the first start after they were added, profiles made from the
     * mappings [set] still holds ([MappingProfiles.migrate]); the set is then saved without them
     * (a copy as it was is kept beside it). Returns the stored set. An unreadable mapping file is
     * left alone and the set is used as it is, with its own mappings.
     */
    private fun loadMappings(set: Profile): Profile {
        store.mappingProfiles()?.takeIf { it.isNotEmpty() }?.let {
            val before = if (store.mappingMigration < MAPPING_MIGRATION) store.readBackup(set.id, BACKUP_TAG) else null
            if (before == null) {
                store.mappingMigration = MAPPING_MIGRATION
                _mappingProfiles.value = it
                return set
            }
            // The first move (build 0.1.61) lost keyboard and extra-pad mappings: redo it once from
            // the copy it kept, keeping its result beside the file.
            store.backupMappings("v1")
            return migrateMappings(before)
        }
        if (store.hasMappingFile()) {
            _mappingProfiles.value = listOf(MappingProfile("map1", MappingProfiles.DEFAULT_NAME))
            return set
        }
        store.backup(set, BACKUP_TAG)
        return migrateMappings(set)
    }

    private fun migrateMappings(set: Profile): Profile {
        val m = MappingProfiles.migrate(set)
        _mappingProfiles.value = m.profiles
        store.saveMappingProfiles(m.profiles)
        store.save(m.set)
        store.mappingMigration = MAPPING_MIGRATION
        return m.set
    }

    /**
     * Measures the gyro's resting bias for 2 seconds; the phone must lie still meanwhile. A paused
     * gyro runs for it (the pad menu, where it is started, pauses the gyro).
     */
    @Synchronized fun calibrateGyro() {
        if (!running) return
        calibration = GyroCalibration()
        _calibration.value = CalibrationState.Running
        updateGyro()
        if (gyro == null) { calibration = null; _calibration.value = storedCalibration() }
    }

    @Synchronized fun clearGyroCalibration() {
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
        return if (m == 0f) CalibrationState.None else CalibrationState.Calibrated(Math.toDegrees(m.toDouble()).toFloat())
    }

    /** Gyro settings follow the active workspace. */
    private fun applyWorkspaceGyro() {
        gyroMouse.settings = _activeProfile.value.workspace(_workspaceIndex.value).gyro
        gyroMouse.reset()
        updateGyro()
    }

    /** The active workspace's gyro is off completely ([GyroMode.SENSOR_OFF]): the sensor need not run. */
    private fun gyroOff(): Boolean =
        _activeProfile.value.workspace(_workspaceIndex.value).gyro.mode == GyroMode.SENSOR_OFF

    /**
     * Runs the gyro sensor while its samples are used: input is allowed or a calibration runs.
     * Otherwise (only while "send input only while the pad is open" is on and the pad is not on
     * screen, or with the workspace's gyro off completely) it stops, to save CPU and battery. The
     * other gyro modes, toggle and ratchets never stop it, so aiming never waits for the sensor.
     */
    @Synchronized private fun updateGyro() {
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
    @Synchronized private fun onDirectCapped(source: GyroSource, hz: Int) {
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
        private const val KEY_CAPSULE_ORDER = "capsule_order"
        private const val KEY_BIAS_X = "gyro_bias_x"
        private const val KEY_BIAS_Y = "gyro_bias_y"
        private const val KEY_BIAS_Z = "gyro_bias_z"
        /** Version of [MappingProfiles.migrate]'s rules; a phone that ran an older one redoes it from [BACKUP_TAG]. */
        private const val MAPPING_MIGRATION = 2
        private const val BACKUP_TAG = "pre-mappings"
        private const val PAUSED = "Paused while the pad is not open"
        private const val GYRO_OFF = "Off in this workspace"
        private const val UNAVAILABLE = "Unavailable"
    }

    private fun onGyroSample(source: GyroSource, x: Float, y: Float, z: Float, ts: Long) {
        val dt = if (lastGyroTs == 0L) 0f else ((ts - lastGyroTs) / 1e9f).coerceIn(0f, 0.05f)
        lastGyroTs = ts
        calibration?.let { cal ->
            when (val r = cal.add(x, y, z, ts)) {
                null -> Unit
                is GyroCalibration.Result.Done -> { calibration = null; setBias(r.x, r.y, r.z); main.post { updateGyro() } }
                GyroCalibration.Result.Moved -> {
                    calibration = null; _calibration.value = CalibrationState.Moved; main.post { updateGyro() }
                }
            }
        }
        val axes = if (inputAllowed) engine.gyroAxes else 0
        val active = axes != 0
        if (active) {
            val c = gyroMouse.update(x - biasX, y - biasY, z - biasZ, dt)
            // A ratchet on one axis leaves the other moving.
            val dx = if (axes and MappingEngine.GYRO_X != 0) c.dx else 0
            val dy = if (axes and MappingEngine.GYRO_Y != 0) c.dy else 0
            if (dx != 0 || dy != 0) engine.addMouseMotion(dx, dy)
        } else {
            gyroMouse.reset()
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
