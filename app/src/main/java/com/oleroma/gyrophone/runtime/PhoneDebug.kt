package com.oleroma.gyrophone.runtime

import android.content.Context
import com.oleroma.gyrophone.core.debug.DebugTrace
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.link.LinkStats
import com.oleroma.gyrophone.link.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The debugging tools of the phone: the Settings switch, the [DebugTrace] the engine logs into
 * while it is on, the once-a-second link line, recordings ([record]) and the text `dumpsys` prints
 * ([dump]). While the switch is off there is no trace and nothing is logged.
 */
class PhoneDebug(context: Context, private val runtime: GamepadRuntime, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "debug")
    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ON, false))

    /** The Settings switch "Debug tools". */
    val enabled: StateFlow<Boolean> = _enabled

    /** The log while [enabled]; null otherwise. */
    @Volatile var trace: DebugTrace? = null
        private set

    private var job: Job? = null

    /** Applies the stored switch; called once the runtime is wired. */
    fun init() {
        if (_enabled.value) turnOn()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
        _enabled.value = on
        if (on) turnOn() else turnOff()
    }

    @Synchronized
    private fun turnOn() {
        if (trace != null) return
        val t = DebugTrace()
        t.note("debug tools on · GyroPhone $version")
        trace = t
        runtime.engine.trace = t
        runtime.wifi.debugTrace = true
        job = scope.launch {
            launch {
                var last: String? = null
                runtime.linkState.collect { s ->
                    val line = describe(s)
                    if (line != last) t.link(line)
                    last = line
                }
            }
            while (isActive) {
                delay(1000)
                t.secondTick(if (runtime.linkState.value is LinkState.Connected) linkLine(runtime.linkStats()) else null)
            }
        }
    }

    @Synchronized
    private fun turnOff() {
        job?.cancel(); job = null
        runtime.engine.trace = null
        runtime.wifi.debugTrace = false
        trace = null
    }

    /**
     * Saves the last 30 s to a file in the app's files (`files/debug/`), and asks the PC to save
     * its side at the same moment when the link is Wi-Fi or USB. Returns the file, or null when
     * debugging is off.
     */
    fun record(): File? {
        val t = trace ?: return null
        t.note("recording saved")
        val wifi = runtime.transport.value == TransportKind.WIFI && runtime.linkState.value is LinkState.Connected
        if (wifi) runtime.wifi.requestDebugSave()
        dir.mkdirs()
        val name = "phone-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val f = File(dir, name)
        f.writeText(t.save("phone", header() + listOf("pc asked to save its side: " + if (wifi) "yes" else "no")))
        // Keep the newest 20 recordings.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
        return f
    }

    /** What `adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService` prints. */
    fun dump(args: Array<out String>): String = buildString {
        if ("on" in args) setEnabled(true)
        if ("off" in args) setEnabled(false)
        appendLine("GyroPhone $version")
        for (h in header()) appendLine(h)
        val t = trace
        if (t == null) {
            appendLine("debug tools: off (args: on | off | record | events [n])")
            return@buildString
        }
        appendLine("debug tools: on")
        if ("record" in args) appendLine("recording: " + (record()?.absolutePath ?: "debug tools are off"))
        val n = args.indexOf("events").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 60
        appendLine("--- last $n events (ms ago, kind, text)")
        val now = System.nanoTime()
        for (e in t.latest(n)) appendLine("${(now - e.nanos) / 1_000_000}\t${e.kind.tag}\t${e.text}")
        appendLine("--- recordings in ${dir.absolutePath}")
        dir.listFiles()?.sortedByDescending { it.name }?.forEach { appendLine(it.name) }
    }

    /** Header lines of a recording or dump: transport, link, gyro, workspace, layer, output now. */
    private fun header(): List<String> = buildList {
        add("transport: ${runtime.transport.value.name.lowercase()} · ${describe(runtime.linkState.value)}")
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
        private const val KEY_ON = "debug_tools"
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
            if (s.usb) append(if (s.usbDirect) " · USB direct" else " · USB tethering")
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
