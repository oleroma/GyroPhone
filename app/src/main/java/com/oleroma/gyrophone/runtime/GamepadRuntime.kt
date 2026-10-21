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
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.Profile
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
import java.util.UUID

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

    private val _profiles = MutableStateFlow(store.all())
    val profiles: StateFlow<List<Profile>> = _profiles

    private val _activeProfile = MutableStateFlow(
        store.activeProfileId.let { id -> _profiles.value.firstOrNull { it.id == id } } ?: BuiltinProfiles.default,
    )
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

    /** Makes [profile] active, in the workspace that was last active in it. */
    fun activate(profile: Profile) {
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

    fun saveProfile(profile: Profile) {
        store.save(profile)
        refreshProfiles()
        if (profile.id == _activeProfile.value.id) activate(profile)
    }

    fun deleteProfile(profile: Profile) {
        store.delete(profile.id)
        refreshProfiles()
        if (profile.id == _activeProfile.value.id) activate(BuiltinProfiles.default)
    }

    /**
     * Applies [change] to the active profile and saves it; a builtin is saved as a custom copy,
     * which becomes active. The current workspace stays active (clamped if the edit removed it).
     * Returns the saved profile.
     */
    fun editActiveProfile(change: (Profile) -> Profile): Profile {
        val active = _activeProfile.value
        var edited = change(active)
        if (edited == active) return active
        if (store.isBuiltin(active)) {
            edited = edited.copy(id = "user." + UUID.randomUUID(), name = active.name + " (custom)")
        }
        store.save(edited)
        store.setWorkspaceIndex(edited.id, _workspaceIndex.value.coerceIn(0, edited.workspaces.lastIndex))
        refreshProfiles()
        activate(edited)
        return edited
    }

    private val _padPresets = MutableStateFlow(store.padPresets())
    /** The user's own pad presets (the builtins come from [PadPresets.builtins]). */
    val padPresets: StateFlow<List<PadPreset>> = _padPresets

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

    /**
     * Replaces the user pad preset with [preset]'s id (name and mappings) and gives every pad that
     * uses it ([PadPresets.uses]) in the saved user profiles the new mappings. Builtin presets and
     * builtin profiles are never changed.
     */
    fun updatePadPreset(preset: PadPreset) {
        val old = _padPresets.value.firstOrNull { it.id == preset.id }
        if (PadPresets.isBuiltin(preset.id) || old == null) return
        _padPresets.value = _padPresets.value.map { if (it.id == preset.id) preset else it }
        store.savePadPresets(_padPresets.value)
        val activeId = _activeProfile.value.id
        for (p in store.userProfiles()) {
            if (p.id == activeId) continue
            val updated = PadPresets.retarget(p, old, preset)
            if (updated !== p) store.save(updated)
        }
        refreshProfiles()
        // A builtin active profile is left alone: changing it would fork a custom copy.
        if (!store.isBuiltin(_activeProfile.value)) editActiveProfile { PadPresets.retarget(it, old, preset) }
    }

    /** Removes a user pad preset (pads that use it keep their mappings and show as Custom). */
    fun deletePadPreset(id: String) {
        _padPresets.value = _padPresets.value.filterNot { it.id == id }
        store.savePadPresets(_padPresets.value)
    }

    /** Applies [preset] to the attached pad [padId] of workspace [wsIndex] (default: the active one). */
    fun applyPadPreset(padId: String, preset: PadPreset, wsIndex: Int = workspaceIndex.value) {
        editActiveProfile { p ->
            p.updateWorkspace(wsIndex) { w ->
                w.copy(pads = w.pads.map { if (it.id == padId) PadPresets.apply(it, preset) else it })
            }
        }
    }

    /** Live value of a phone-side source (for highlighting pressed controls). */
    fun sourceValue(source: InputSource): Float = engine.sourceValue(source)

    private fun refreshProfiles() { _profiles.value = store.all() }

    /**
     * Called by the input layer whenever the set of attached controllers changes. May switch to a
     * profile made for a newly attached controller ([Profile.controllers]); never changes the
     * workspace of the active profile.
     */
    fun onControllersChanged(list: List<AttachedController>) {
        val added = list.filter { c -> _controllers.value.none { it.deviceId == c.deviceId } }
        _controllers.value = list
        // Auto-switch to a profile made for a newly attached controller, but never away from a
        // user profile that is not tied to specific controllers (e.g. a customised builtin).
        for (c in added) {
            val active = _activeProfile.value
            if (active.controllers.any { it.matches(c.vendorId, c.productId, c.name) }) continue
            if (!store.isBuiltin(active) && active.controllers.isEmpty()) continue
            BuiltinProfiles.forController(_profiles.value, c.vendorId, c.productId, c.name)?.let { activate(it) }
        }
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
