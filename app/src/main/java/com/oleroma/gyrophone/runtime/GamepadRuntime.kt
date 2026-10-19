package com.oleroma.gyrophone.runtime

import android.content.Context
import android.hardware.SensorManager
import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownController
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.data.ProfileStore
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.hid.ReportPump
import com.oleroma.gyrophone.sensor.GyroSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A game controller attached to the phone (e.g. GameSir X5). */
data class AttachedController(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val known: KnownController?,
)

/**
 * Process-wide wiring: profiles -> mapping engine -> report pump -> Bluetooth HID, plus the gyro.
 * Started and stopped by GamepadService; the UI reads its state flows.
 */
class GamepadRuntime(context: Context) {
    private val appContext = context.applicationContext

    val store = ProfileStore(appContext)
    val hid = HidDeviceManager(appContext)

    private val _profiles = MutableStateFlow(store.all())
    val profiles: StateFlow<List<Profile>> = _profiles

    private val _activeProfile = MutableStateFlow(
        _profiles.value.firstOrNull { it.id == store.activeProfileId } ?: BuiltinProfiles.virtualGamepad,
    )
    val activeProfile: StateFlow<Profile> = _activeProfile

    val engine = MappingEngine(_activeProfile.value)
    private val pump = ReportPump(engine, hid)
    private val gyroMouse = GyroMouse(_activeProfile.value.gyro)

    private val _controllers = MutableStateFlow<List<AttachedController>>(emptyList())
    val controllers: StateFlow<List<AttachedController>> = _controllers

    private val _gyroInfo = MutableStateFlow(GyroInfo())
    val gyroInfo: StateFlow<GyroInfo> = _gyroInfo

    data class GyroInfo(val source: String = "Not started", val rateHz: Int = 0, val active: Boolean = false)

    private var gyro: GyroSource? = null
    @Volatile private var displayRotation = 90
    private var lastGyroTs = 0L
    private var rateWindowStart = 0L
    private var rateCount = 0

    /** When set, the next controller input is captured for the mapping editor instead of sent. */
    @Volatile var learnListener: ((InputSource) -> Unit)? = null

    init {
        hid.reportProvider = { id ->
            when (id) {
                HidDescriptor.REPORT_ID_GAMEPAD -> pump.lastGamepadReport
                HidDescriptor.REPORT_ID_KEYBOARD -> pump.lastKeyboardReport
                HidDescriptor.REPORT_ID_MOUSE -> ByteArray(HidDescriptor.MOUSE_REPORT_SIZE)
                else -> null
            }
        }
        hid.onHostConnected = { pump.invalidate() }
    }

    fun start() {
        hid.start()
        pump.start()
        startGyro()
    }

    fun stop() {
        gyro?.stop(); gyro = null
        pump.stop()
        hid.stop()
        engine.reset()
    }

    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
        applyGyroSettings(_activeProfile.value)
    }

    fun activate(profile: Profile) {
        store.activeProfileId = profile.id
        _activeProfile.value = profile
        engine.reset()
        engine.profile = profile
        applyGyroSettings(profile)
    }

    fun saveProfile(profile: Profile) {
        store.save(profile)
        refreshProfiles()
        if (profile.id == _activeProfile.value.id) activate(profile)
    }

    fun deleteProfile(profile: Profile) {
        store.delete(profile.id)
        refreshProfiles()
        if (profile.id == _activeProfile.value.id) activate(BuiltinProfiles.virtualGamepad)
    }

    private fun refreshProfiles() { _profiles.value = store.all() }

    /** Called by the input layer whenever the set of attached controllers changes. */
    fun onControllersChanged(list: List<AttachedController>) {
        val added = list.filter { c -> _controllers.value.none { it.deviceId == c.deviceId } }
        _controllers.value = list
        // Auto-switch to a profile made for a newly attached controller.
        for (c in added) {
            val active = _activeProfile.value
            if (active.controllers.any { it.matches(c.vendorId, c.productId, c.name) }) continue
            BuiltinProfiles.forController(_profiles.value, c.vendorId, c.productId, c.name)?.let { activate(it) }
        }
    }

    private fun applyGyroSettings(profile: Profile) {
        val g = profile.gyro
        gyroMouse.settings = if (g.autoOrient) GyroMouse.defaultsForRotation(displayRotation, g) else g
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
