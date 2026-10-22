package com.oleroma.gyrophone.runtime

import android.content.Context
import android.hardware.SensorManager
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
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.WorkspaceSet
import com.oleroma.gyrophone.data.ProfileStore
import com.oleroma.gyrophone.core.net.ByeReason
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.hid.ReportPump
import com.oleroma.gyrophone.link.BluetoothLink
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.link.LinkStats
import com.oleroma.gyrophone.link.OutputLink
import com.oleroma.gyrophone.link.TransportKind
import com.oleroma.gyrophone.sensor.GyroSource
import com.oleroma.gyrophone.wifi.WifiLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    private val _profiles = MutableStateFlow(store.all())
    val profiles: StateFlow<List<Profile>> = _profiles

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

    data class GyroInfo(val source: String = "Not started", val rateHz: Int = 0, val active: Boolean = false)

    private var gyro: GyroSource? = null
    private var lastGyroTs = 0L
    private var rateWindowStart = 0L
    private var rateCount = 0

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
        startGyro()
    }

    @Synchronized fun stop() {
        gyro?.stop(); gyro = null
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

    /** Saves [set] (without mappings) and [profiles], and makes the resolved set active. */
    private fun commit(set: Profile, profiles: List<MappingProfile>) {
        if (profiles != _mappingProfiles.value) {
            _mappingProfiles.value = profiles
            store.saveMappingProfiles(profiles)
        }
        if (set != stored) {
            stored = set
            store.save(set)
            store.setWorkspaceIndex(set.id, _workspaceIndex.value.coerceIn(0, set.workspaces.lastIndex))
            refreshProfiles()
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

    private fun refreshProfiles() { _profiles.value = store.all() }

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
     * (a copy as it was is kept beside it) and pad mappings that no profile kept become pad
     * presets. Returns the stored set. An unreadable mapping file is left alone and the set is
     * used as it is, with its own mappings.
     */
    private fun loadMappings(set: Profile): Profile {
        store.mappingProfiles()?.takeIf { it.isNotEmpty() }?.let {
            _mappingProfiles.value = it
            return set
        }
        if (store.hasMappingFile()) {
            _mappingProfiles.value = listOf(MappingProfile("map1", MappingProfiles.DEFAULT_NAME))
            return set
        }
        val m = MappingProfiles.migrate(set, _padPresets.value)
        if (m.presets.isNotEmpty()) {
            _padPresets.value = _padPresets.value + m.presets
            store.savePadPresets(_padPresets.value)
        }
        _mappingProfiles.value = m.profiles
        store.saveMappingProfiles(m.profiles)
        store.backup(set, "pre-mappings")
        store.save(m.set)
        return m.set
    }

    /** Gyro settings follow the active workspace. */
    private fun applyWorkspaceGyro() {
        gyroMouse.settings = _activeProfile.value.workspace(_workspaceIndex.value).gyro
        gyroMouse.reset()
    }

    private fun startGyro() {
        if (gyro != null) return
        val sm = appContext.getSystemService(SensorManager::class.java) ?: return
        val candidates = listOfNotNull(GyroSource.best(sm))
        for (source in candidates) {
            if (source.start(::onGyroSample)) {
                gyro = source
                _gyroInfo.value = GyroInfo(source = source.name)
                return
            }
        }
        _gyroInfo.value = GyroInfo(source = "Unavailable")
    }

    companion object {
        private const val KEY_RATE = "report_rate_hz"
        private const val KEY_WIFI_RATE = "wifi_rate_hz"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_ORIENTATION = "screen_orientation"
    }

    private fun onGyroSample(x: Float, y: Float, z: Float, ts: Long) {
        val dt = if (lastGyroTs == 0L) 0f else ((ts - lastGyroTs) / 1e9f).coerceIn(0f, 0.05f)
        lastGyroTs = ts
        val active = engine.gyroActive
        if (active) {
            val c = gyroMouse.update(x, y, z, dt)
            if (c.dx != 0 || c.dy != 0) engine.addMouseMotion(c.dx, c.dy)
        } else {
            gyroMouse.reset()
        }
        rateCount++
        if (ts - rateWindowStart >= 1_000_000_000L) {
            _gyroInfo.value = _gyroInfo.value.copy(rateHz = rateCount, active = active)
            rateWindowStart = ts
            rateCount = 0
        }
    }
}
