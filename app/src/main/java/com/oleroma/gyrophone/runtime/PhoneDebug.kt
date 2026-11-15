package com.oleroma.gyrophone.runtime

import android.content.Context
import com.oleroma.gyrophone.core.debug.DebugTrace
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.link.LinkStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Settings › Performance › **Diagnostics**: one switch for every log on the phone. On, the [DebugTrace] keeps
 * what came in and what it did, what went out, ratchet timings ([GamepadRuntime.ratchetTiming]), the gyro
 * displays' draw times and a link line each second, for the last [keepMinutes] minutes, in memory only;
 * [save] writes them to a file (and the PC saves its side). Off, there is no trace and nothing is measured.
 *
 * The switch follows the PC's (HOST_STATUS `PC_LOGGING`, see [WifiLink]) and the PC follows it (LINK_INFO
 * `DEBUG_TRACE`), so both sides log together; a Save on either side saves both.
 *
 * The input paths only queue raw events ([DebugTrace.queue], [DebugTrace.queueOutput]); the `gp-diag` thread
 * turns them into lines every [DRAIN_MS] ms and adds the link line once a second.
 */
class PhoneDebug(context: Context, private val runtime: GamepadRuntime, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)
    // In the app's external files (Android/data/<package>/files/debug), where adb can pull from without
    // run-as, which works only on a debuggable app; the phone runs the release build.
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug")
    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ON, false))

    /** The Settings switch "Diagnostics". */
    val enabled: StateFlow<Boolean> = _enabled

    private val _keepMinutes = MutableStateFlow(prefs.getInt(KEY_KEEP, DEFAULT_KEEP).coerceIn(MIN_KEEP, MAX_KEEP))

    /** How many minutes Diagnostics keeps (and a Save writes), 1 to 30. */
    val keepMinutes: StateFlow<Int> = _keepMinutes

    /** The log while [enabled]; null otherwise. */
    @Volatile var trace: DebugTrace? = null
        private set

    private var job: Job? = null
    private var worker: Thread? = null

    /** Applies the stored switch; called once the runtime is wired. */
    fun init() {
        // The PC's switch and Save (on the receive thread: handed to a thread of their own).
        runtime.wifi.onPcDiagnostics = { on -> thread(name = "gp-diag-switch", isDaemon = true) { setEnabled(on) } }
        runtime.wifi.onPcSave = { saveInBackground(fromPc = true) }
        if (_enabled.value) turnOn()
    }

    /** The switch, from Settings, adb or the PC. */
    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == _enabled.value && (trace != null) == on) return
        prefs.edit().putBoolean(KEY_ON, on).apply()
        _enabled.value = on
        if (on) turnOn() else turnOff()
    }

    fun setKeepMinutes(minutes: Int) {
        val m = minutes.coerceIn(MIN_KEEP, MAX_KEEP)
        prefs.edit().putInt(KEY_KEEP, m).apply()
        _keepMinutes.value = m
    }

    @Synchronized
    private fun turnOn() {
        if (trace != null) return
        val t = DebugTrace()
        t.note("diagnostics on · GyroPhone $version")
        trace = t
        runtime.engine.trace = t
        runtime.wifi.debugTrace = true
        runtime.ratchetTiming.enabled = true
        job = scope.launch {
            var last: String? = null
            runtime.linkState.collect { s ->
                val line = describe(s)
                if (line != last) t.link(line)
                last = line
            }
        }
        worker = thread(name = "gp-diag", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            var nextSecond = System.nanoTime() + 1_000_000_000L
            try {
                while (trace === t) {
                    Thread.sleep(DRAIN_MS)
                    t.drain()
                    val now = System.nanoTime()
                    if (now >= nextSecond) {
                        nextSecond = now + 1_000_000_000L
                        t.secondTick(if (runtime.linkState.value is LinkState.Connected) linkLine(runtime.linkStats()) else null)
                    }
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    @Synchronized
    private fun turnOff() {
        job?.cancel(); job = null
        worker?.interrupt(); worker = null
        runtime.engine.trace = null
        runtime.wifi.debugTrace = false
        runtime.ratchetTiming.enabled = false
        trace = null
    }

    /**
     * Saves the kept minutes to a file in the app's external files (`files/debug/`) and, unless the PC asked for
     * it ([fromPc]), asks the PC to save its side at the same moment. Returns the file, or null when Diagnostics
     * is off.
     */
    fun save(fromPc: Boolean = false): File? {
        val t = trace ?: return null
        t.note(if (fromPc) "saved (the PC asked)" else "saved")
        val connected = runtime.linkState.value is LinkState.Connected
        if (!fromPc && connected) runtime.wifi.requestDebugSave()
        dir.mkdirs()
        val name = "phone-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val f = File(dir, name)
        val pcSide = if (fromPc) "the PC asked for this save" else "pc asked to save its side: " + if (connected) "yes" else "no"
        f.writeText(t.save("phone", header() + listOf("kept: ${_keepMinutes.value} min", pcSide), _keepMinutes.value * 60_000_000_000L))
        // Keep the newest 20 saves.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
        return f
    }

    /** [save] on its own thread (it writes a file of up to a few MB), then [done] with the file there. */
    fun saveInBackground(fromPc: Boolean = false, done: (File?) -> Unit = {}) {
        thread(name = "gp-diag-save", isDaemon = true) { done(runCatching { save(fromPc) }.getOrNull()) }
    }

    /**
     * What `adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService` prints.
     * Args: `on`, `off`, `save`, `keep <minutes>`, `events <n>` (tools/gp-log.sh wraps them).
     */
    fun dump(args: Array<out String>): String = buildString {
        if ("on" in args) setEnabled(true)
        if ("off" in args) setEnabled(false)
        args.indexOf("keep").takeIf { it >= 0 }?.let { i -> args.getOrNull(i + 1)?.toIntOrNull()?.let { setKeepMinutes(it) } }
        appendLine("GyroPhone $version")
        for (h in header()) appendLine(h)
        val t = trace
        if (t == null) {
            appendLine("diagnostics: off (args: on | off | save | keep <1-30> | events <n>)")
            return@buildString
        }
        appendLine("diagnostics: on, keeping ${_keepMinutes.value} min")
        if ("save" in args) appendLine("saved: " + (save()?.absolutePath ?: "diagnostics are off"))
        t.drain()
        val n = args.indexOf("events").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 60
        appendLine("--- last $n events (ms ago, kind, text)")
        val now = System.nanoTime()
        for (e in t.latest(n)) appendLine("${(now - e.nanos) / 1_000_000}\t${e.kind.tag}\t${e.text}")
        appendLine("--- saves in ${dir.absolutePath}")
        dir.listFiles()?.sortedByDescending { it.name }?.forEach { appendLine(it.name) }
    }

    /** Header lines of a recording or dump (built only at Save or a dump, never on the input path): transport, connection, link, gyro, workspace, layer, output now. */
    private fun header(): List<String> = buildList {
        add("transport: ${runtime.transport.value.name.lowercase()} · ${describe(runtime.linkState.value)}")
        add("connection: " + if (runtime.transport.value == com.oleroma.gyrophone.link.TransportKind.WIFI) runtime.wifi.connectionLine() else "Bluetooth")
        add("link: " + linkLine(runtime.linkStats()))
        val g = runtime.gyroInfo.value
        add("gyro: ${g.source} · ${g.rateHz} Hz · " + if (g.active) "moving the mouse" else "idle")
        val p = runtime.activeProfile.value
        add("workspace: " + (p.workspaces.getOrNull(runtime.workspaceIndex.value)?.name ?: "?"))
        add("controllers: " + runtime.controllers.value.joinToString { it.name }.ifEmpty { "none" })
        val touchFeedback = runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        }.getOrNull()
        add("haptics: ${runtime.hapticsInfo} · system touch feedback " + when (touchFeedback) { 0 -> "OFF (system-style ticks are muted)"; 1 -> "on"; else -> "unknown" } +
                " · plays only while a PC is connected")
        trace?.let { add("output now: " + outputLine(it)) }
        runtime.wifi.debugClock()?.let { (phoneUs, offsetUs) ->
            add("clock-pair: wall_ms=${System.currentTimeMillis()} phone_us=$phoneUs offset_us=$offsetUs")
        }
    }

    companion object {
        private const val KEY_ON = "diagnostics"
        private const val KEY_KEEP = "diagnostics_keep_minutes"
        const val MIN_KEEP = 1
        const val MAX_KEEP = 30
        const val DEFAULT_KEEP = 3
        private const val DRAIN_MS = 50L
        private const val MAX_FILES = 20

        fun describe(s: LinkState): String = when (s) {
            LinkState.Off -> "off"
            LinkState.Searching -> "looking for a PC"
            is LinkState.Pairing -> "pairing with ${s.host}"
            is LinkState.Connecting -> "connecting to ${s.name}"
            is LinkState.Connected -> "connected to ${s.name} (${s.detail})"
            is LinkState.HostSilent -> "${s.name} is not answering"
            is LinkState.Failed -> "failed: ${s.reason}"
        }

        fun linkLine(s: LinkStats): String = buildString {
            append("sent ${s.sentPerSecond}/s")
            if (s.failedPerSecond > 0) append(", failed ${s.failedPerSecond}/s")
            s.hostRxPerSecond?.let { append(" · PC got $it/s") }
            s.lossPercent?.let { append(" · loss ${"%.1f".format(it)}%") }
            if (s.latP50Ms != null && s.latP99Ms != null) append(" · latency ${"%.1f".format(s.latP50Ms)} / ${"%.1f".format(s.latP99Ms)} ms (p50/p99)")
            s.rttMs?.let { append(" · round trip ${"%.1f".format(it)} ms") }
            if (s.usb) append(if (s.hotspot) " · phone hotspot" else if (s.ethernet) " · Ethernet" else if (s.usbDirect) " · USB direct" else " · USB tethering")
            else if (s.band != null && s.band != 0) append(" · ${s.band} GHz")
            s.rssi?.let { if (!s.usb) append(" $it dBm") }
            s.linkMbps?.let { if (!s.usb) append(" $it Mbps") }
            if (s.lowLatencyLock == false) append(" · no low-latency lock")
            if (s.vigemOk == false) append(" · PC has no ViGEm driver")
        }

        fun outputLine(t: DebugTrace): String {
            val s = t.current
            val p = s.gamepad
            val parts = ArrayList<String>()
            val buttons = com.oleroma.gyrophone.core.model.GamepadButton.entries
                .filter { it.bit >= 0 && p.buttons and (1 shl it.bit) != 0 }.joinToString(" ") { it.label }
            if (buttons.isNotEmpty()) parts.add("pad $buttons")
            if (p.hat != com.oleroma.gyrophone.core.hid.Hat.CENTER) parts.add("d-pad ${DebugTrace.hatName(p.hat)}")
            fun pct(v: Int, max: Int) = v * 100 / max
            if (p.lx != 0 || p.ly != 0) parts.add("L ${pct(p.lx, 32767)},${pct(p.ly, 32767)}%")
            if (p.rx != 0 || p.ry != 0) parts.add("R ${pct(p.rx, 32767)},${pct(p.ry, 32767)}%")
            if (p.lt != 0) parts.add("LT ${pct(p.lt, 1023)}%")
            if (p.rt != 0) parts.add("RT ${pct(p.rt, 1023)}%")
            val keys = s.allKeys.map { DebugTrace.keyName(it) } +
                    (0 until 8).filter { s.keyboard.modifiers and (1 shl it) != 0 }.map { DebugTrace.keyName(0xE0 + it) }
            if (keys.isNotEmpty()) parts.add("keys " + keys.joinToString("+"))
            if (s.mouse.buttons != 0) parts.add("mouse buttons ${s.mouse.buttons}")
            return parts.joinToString(" · ").ifEmpty { "nothing held" }
        }
    }
}
