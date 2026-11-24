package com.oleroma.gyrophone.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.oleroma.gyrophone.core.engine.AimStatsAnalyzer
import com.oleroma.gyrophone.core.engine.AimStatsData
import com.oleroma.gyrophone.core.engine.AimStatsRecorder
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.StatKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Stats page's collecting: while [collecting], the gyro thread and the engine copy plain numbers
 * into an [AimStatsRecorder] and a background thread of its own works them out for the workspace in
 * use ([workspace]) four times a second. Off, nothing is recorded and the thread only loads and saves.
 * Each workspace's stats are a file in `stats/`; its Fire and Aim inputs are app preferences (Reset
 * leaves them).
 */
class AimStats(context: Context, private val engine: MappingEngine, private val workspace: () -> String?) {
    private val prefs = context.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "stats")

    /** Set only while collecting: the gyro thread writes its samples here. */
    @Volatile var recorder: AimStatsRecorder? = null
        private set

    private val _collecting = MutableStateFlow(prefs.getBoolean(KEY_COLLECT, false))
    val collecting: StateFlow<Boolean> = _collecting

    /** A copy of a workspace's stats for the page, renewed each second while it is open. */
    data class View(val workspaceId: String, val data: AimStatsData, val fire: Long, val aim: Long)

    private val _view = MutableStateFlow<View?>(null)
    val view: StateFlow<View?> = _view
    private val watchers = AtomicInteger()

    private val thread = HandlerThread("GP stats", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    private val handler = Handler(thread.looper)

    // The Stats thread's own.
    private val analyzer = AimStatsAnalyzer()
    private var wsId: String? = null
    private var lastSave = 0L
    private var lastShown = 0L

    private val tick = object : Runnable {
        override fun run() {
            val rec = recorder ?: return
            follow()
            rec.drain(analyzer)
            analyzer.process()
            val now = SystemClock.elapsedRealtime()
            if (analyzer.dirty && now - lastSave >= SAVE_MS) save()
            if (watchers.get() > 0 && now - lastShown >= SHOW_MS) show()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        if (_collecting.value) start()
    }

    fun setCollecting(on: Boolean) {
        if (_collecting.value == on) return
        _collecting.value = on
        prefs.edit().putBoolean(KEY_COLLECT, on).apply()
        if (on) start() else stop()
    }

    private fun start() {
        val rec = AimStatsRecorder { SystemClock.elapsedRealtimeNanos() }
        handler.post {
            follow()
            analyzer.forget()
            recorder = rec
            engine.inputTap = rec
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_MS)
        }
    }

    private fun stop() {
        val rec = recorder
        engine.inputTap = null
        recorder = null
        handler.post {
            handler.removeCallbacks(tick)
            if (rec != null) {
                rec.drain(analyzer)
                analyzer.process()
            }
            analyzer.forget()
            save()
            show()
        }
    }

    /** The page is open: keep its [view] fresh (call [unwatch] when it closes). */
    fun watch() {
        watchers.incrementAndGet()
        handler.post { follow(); show() }
    }

    fun unwatch() {
        watchers.decrementAndGet()
    }

    /** Which input fires ([fire]) or aims in the workspace in use; a [StatKey]. */
    fun setRoles(fire: Long?, aim: Long?) = handler.post {
        follow()
        val ws = wsId ?: return@post
        val e = prefs.edit()
        if (fire != null) { e.putLong(KEY_FIRE + ws, fire); analyzer.fireKey = fire }
        if (aim != null) { e.putLong(KEY_AIM + ws, aim); analyzer.aimKey = aim }
        e.apply()
        show()
    }

    /** Clears the workspace in use's stats (its Fire and Aim inputs stay). */
    fun reset() = handler.post {
        follow()
        analyzer.data.clear()
        analyzer.forget()
        wsId?.let { file(it).delete() }
        analyzer.clean()
        show()
    }

    /** Switches to the workspace in use: the last one's stats are saved, this one's loaded. */
    private fun follow() {
        val ws = workspace() ?: return
        if (ws == wsId) return
        if (wsId != null) save()
        wsId = ws
        analyzer.data = load(ws) ?: AimStatsData()
        analyzer.fireKey = prefs.getLong(KEY_FIRE + ws, StatKey.LEFT_CLICK)
        analyzer.aimKey = prefs.getLong(KEY_AIM + ws, StatKey.RIGHT_CLICK)
        analyzer.forget()
        analyzer.clean()
    }

    private fun file(ws: String) = File(dir, ws.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun load(ws: String): AimStatsData? = runCatching {
        file(ws).takeIf { it.exists() }?.readText()?.let { AimStatsData.decode(it) }
    }.getOrNull()

    private fun save() {
        lastSave = SystemClock.elapsedRealtime()
        val ws = wsId ?: return
        if (!analyzer.dirty) return
        runCatching {
            dir.mkdirs()
            val f = file(ws)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(analyzer.data.encode())
            tmp.renameTo(f)
        }
        analyzer.clean()
    }

    private fun show() {
        lastShown = SystemClock.elapsedRealtime()
        val ws = wsId ?: return
        // A copy: the page reads it while this thread goes on counting.
        val copy = AimStatsData.decode(analyzer.data.encode()) ?: return
        _view.value = View(ws, copy, analyzer.fireKey, analyzer.aimKey)
    }

    companion object {
        private const val KEY_COLLECT = "gp_stats_collect"
        private const val KEY_FIRE = "gp_stats_fire_"
        private const val KEY_AIM = "gp_stats_aim_"
        private const val TICK_MS = 250L
        private const val SAVE_MS = 15_000L
        private const val SHOW_MS = 1_000L
    }
}
