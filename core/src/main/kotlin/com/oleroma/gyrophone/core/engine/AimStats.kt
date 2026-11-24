package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.AndroidAxis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the engine tells the Stats page about presses: every edge of a physical key, a trigger
 * crossing its press point, a touch button, and the Left / Right click the engine sends.
 * Called on the input threads inside their own work, so an implementation only stores numbers.
 */
interface InputTap {
    fun key(keyCode: Int, down: Boolean)
    fun axis(axis: Int, value: Float)
    fun touch(id: String, down: Boolean)
    /** The mouse buttons of each snapshot (the sender's thread). */
    fun mouseButtons(buttons: Int)
}

/**
 * The inputs Stats tells apart, as one Long: the kind in the high half, the key in the low half.
 * Touch buttons go by their id's hash (names are looked up on the page from the workspace).
 */
object StatKey {
    const val KEY = 1L
    const val TRIGGER = 2L
    const val TOUCH = 3L
    /** What the engine sends: Left (1) or Right (2) click, whatever input makes it. */
    const val CLICK = 4L

    fun of(kind: Long, value: Int): Long = (kind shl 32) or (value.toLong() and 0xFFFF_FFFFL)
    fun kind(key: Long): Long = key ushr 32
    fun value(key: Long): Int = key.toInt()
    fun touch(id: String): Long = of(TOUCH, id.hashCode())

    val LEFT_CLICK = of(CLICK, 1)
    val RIGHT_CLICK = of(CLICK, 2)
}

/**
 * Records gyro samples and press edges for Stats while it collects. Writers only copy numbers into
 * ready-made rings (no allocation, no text); [drain] runs on the Stats thread and hands them on.
 * The gyro ring has one writer (the gyro thread); press edges come from several threads and take a
 * short lock. A reader that falls a whole ring behind skips the lost part.
 */
class AimStatsRecorder(private val clock: () -> Long) : InputTap {
    private val gTs = LongArray(G_SIZE)
    private val gYaw = FloatArray(G_SIZE)
    private val gPitch = FloatArray(G_SIZE)
    private val gOn = BooleanArray(G_SIZE)
    @Volatile private var gHead = 0L
    private var gTail = 0L

    private val eLock = Any()
    private val eTs = LongArray(E_SIZE)
    private val eKey = LongArray(E_SIZE)
    private val eDown = BooleanArray(E_SIZE)
    @Volatile private var eHead = 0L
    private var eTail = 0L

    /** Trigger state by axis id (engine lock: one writer at a time). */
    private val triggerOn = BooleanArray(32)
    private var mouse = 0

    /** One gyro sample: [yaw], [pitch] in °/s about the screen, [on] while the gyro drives something. */
    fun gyro(ts: Long, yaw: Float, pitch: Float, on: Boolean) {
        val h = gHead
        val i = (h and G_MASK).toInt()
        gTs[i] = ts; gYaw[i] = yaw; gPitch[i] = pitch; gOn[i] = on
        gHead = h + 1
    }

    private fun event(key: Long, down: Boolean) {
        val t = clock()
        synchronized(eLock) {
            val h = eHead
            val i = (h and E_MASK).toInt()
            eTs[i] = t; eKey[i] = key; eDown[i] = down
            eHead = h + 1
        }
    }

    override fun key(keyCode: Int, down: Boolean) = event(StatKey.of(StatKey.KEY, keyCode), down)

    override fun axis(axis: Int, value: Float) {
        if (!AndroidAxis.isTrigger(axis) || axis >= triggerOn.size) return
        val was = triggerOn[axis]
        val now = if (was) value >= TRIGGER_OFF else value >= TRIGGER_ON
        if (now == was) return
        triggerOn[axis] = now
        event(StatKey.of(StatKey.TRIGGER, axis), now)
    }

    override fun touch(id: String, down: Boolean) = event(StatKey.touch(id), down)

    override fun mouseButtons(buttons: Int) {
        val b = buttons and 3
        val changed = b xor mouse
        if (changed == 0) return
        mouse = b
        if (changed and 1 != 0) event(StatKey.LEFT_CLICK, b and 1 != 0)
        if (changed and 2 != 0) event(StatKey.RIGHT_CLICK, b and 2 != 0)
    }

    /** Hands everything recorded since the last call to [into] (the Stats thread only). */
    fun drain(into: AimStatsAnalyzer) {
        val gh = gHead
        if (gh - gTail > G_SIZE) gTail = gh - G_SIZE
        while (gTail < gh) {
            val i = (gTail and G_MASK).toInt()
            into.sample(gTs[i], gYaw[i], gPitch[i], gOn[i])
            gTail++
        }
        synchronized(eLock) {
            val eh = eHead
            if (eh - eTail > E_SIZE) eTail = eh - E_SIZE
            while (eTail < eh) {
                val i = (eTail and E_MASK).toInt()
                into.event(eTs[i], eKey[i], eDown[i])
                eTail++
            }
        }
    }

    companion object {
        /** About 8 s of samples at the OnePlus 15's ~950 Hz: the Stats thread drains every quarter second. */
        private const val G_SIZE = 8192
        private const val G_MASK = (G_SIZE - 1).toLong()
        private const val E_SIZE = 1024
        private const val E_MASK = (E_SIZE - 1).toLong()
        /** A trigger counts as pressed from half its pull, as mappings press (TriggerTuning). */
        const val TRIGGER_ON = 0.5f
        const val TRIGGER_OFF = 0.4f
    }
}

/**
 * Counts in log-spaced bins from [low] to [high] (values under [low] go in the first bin), for
 * typical (median) and high (90th percentile) values without keeping every value.
 */
@Serializable
class LogHist(val low: Double, val high: Double, val counts: DoubleArray) {
    val total: Double get() = counts.sum()

    fun add(v: Double, weight: Double = 1.0) {
        val n = counts.size
        val i = if (v <= low) 0 else ((ln(v / low) / ln(high / low)) * n).toInt().coerceIn(0, n - 1)
        counts[i] += weight
    }

    /** The value [q] (0..1) of the way through, or null when empty. */
    fun quantile(q: Double): Double? {
        val t = total
        if (t <= 0.0) return null
        val goal = q * t
        var run = 0.0
        val n = counts.size
        for (i in 0 until n) {
            val c = counts[i]
            if (c > 0 && run + c >= goal) {
                val f = ((goal - run) / c).coerceIn(0.0, 1.0)
                // Within the bin, spread evenly on the log scale; the first bin starts at 0.
                val a = if (i == 0) 0.0 else low * exp(ln(high / low) * i / n)
                val b = low * exp(ln(high / low) * (i + 1) / n)
                return if (i == 0) b * f else a * exp(ln(b / a) * f)
            }
            run += c
        }
        return high
    }

    fun clear() = counts.fill(0.0)

    companion object {
        fun speed() = LogHist(1.0, 2000.0, DoubleArray(64))
        fun degrees() = LogHist(0.005, 50.0, DoubleArray(56))
    }
}

/** How one input's presses moved the aim, in degrees off the turn you were making. */
@Serializable
class PressShake(
    var presses: Int = 0,
    /** Off your line in the last 80 ms before the press counted: where a single shot lands. */
    val before: LogHist = LogHist.degrees(),
    /** The most it threw you off in the 150 ms after the press. */
    val after: LogHist = LogHist.degrees(),
    /** The same for the 150 ms after letting go. */
    val release: LogHist = LogHist.degrees(),
    /** [before], [after] and [release] split: sideways (yaw) and up/down (pitch) on their own. */
    val beforeSide: LogHist = LogHist.degrees(),
    val beforeUp: LogHist = LogHist.degrees(),
    val afterSide: LogHist = LogHist.degrees(),
    val afterUp: LogHist = LogHist.degrees(),
    val releaseSide: LogHist = LogHist.degrees(),
    val releaseUp: LogHist = LogHist.degrees(),
    /** The sharpest jerk (°/s off your turn) from 80 ms before to 150 ms after the press. */
    val twitch: LogHist = LogHist.speed(),
    /** How fast you were turning just before pressing (°/s). */
    val speedAtPress: LogHist = LogHist.speed(),
)

/** Turning speeds while firing, aiming and otherwise (seconds per bin) and the shake of every input. */
@Serializable
class AimStatsData(
    /** Seconds with the gyro on. */
    var seconds: Double = 0.0,
    val firing: LogHist = LogHist.speed(),
    val aiming: LogHist = LogHist.speed(),
    val moving: LogHist = LogHist.speed(),
    val inputs: MutableMap<Long, PressShake> = HashMap(),
) {
    fun clear() {
        seconds = 0.0
        firing.clear(); aiming.clear(); moving.clear()
        inputs.clear()
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }
        fun decode(text: String): AimStatsData? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * Turns samples and press edges into [AimStatsData], a quarter second behind so each press has the
 * gyro from before and after it. Runs on the Stats thread only.
 *
 * Shake is measured against the turn you were making: the average turn over 250 to 80 ms before the
 * press is the line, and what the phone did beyond that line counts. A steady track is no shake.
 */
class AimStatsAnalyzer(var data: AimStatsData = AimStatsData()) {
    /** Which input fires and which aims ([StatKey]); their presses decide Firing and Aiming time. */
    var fireKey: Long = StatKey.LEFT_CLICK
    var aimKey: Long = StatKey.RIGHT_CLICK

    private val hTs = LongArray(H_SIZE)
    private val hYaw = FloatArray(H_SIZE)
    private val hPitch = FloatArray(H_SIZE)
    private val hOn = BooleanArray(H_SIZE)
    private var hHead = 0L
    /** Samples up to here are counted in the speeds. */
    private var counted = 0L

    private class Edge(val ts: Long, val key: Long, val down: Boolean)

    private val edges = ArrayDeque<Edge>()
    private val windows = ArrayList<Edge>()
    private var fireHeld = false
    private var aimHeld = false

    /** Changed since [clean] (worth saving). */
    var dirty = false
        private set

    fun clean() { dirty = false }

    fun sample(ts: Long, yaw: Float, pitch: Float, on: Boolean) {
        if (hHead > 0 && ts <= hTs[((hHead - 1) and H_MASK).toInt()]) return
        val i = (hHead and H_MASK).toInt()
        hTs[i] = ts; hYaw[i] = yaw; hPitch[i] = pitch; hOn[i] = on
        hHead++
        if (hHead - counted > H_SIZE) counted = hHead - H_SIZE
    }

    fun event(ts: Long, key: Long, down: Boolean) {
        edges.addLast(Edge(ts, key, down))
    }

    /** Drops what is in flight (another workspace starts with a clean slate). */
    fun forget() {
        counted = hHead
        edges.clear(); windows.clear()
        fireHeld = false; aimHeld = false
    }

    /** Counts everything at least [DELAY_NS] older than the newest sample. */
    fun process() {
        if (hHead == 0L) return
        val newest = hTs[((hHead - 1) and H_MASK).toInt()]
        val until = newest - DELAY_NS
        // Speeds and press edges in time order.
        while (true) {
            val sTs = if (counted < hHead) hTs[(counted and H_MASK).toInt()] else Long.MAX_VALUE
            val e = edges.firstOrNull()
            val eTs = e?.ts ?: Long.MAX_VALUE
            if (eTs <= sTs && eTs <= until) {
                edges.removeFirst(); edge(e!!)
            } else if (sTs <= until) {
                countSpeed(counted); counted++
            } else break
        }
        // Presses whose after-window is in.
        var i = 0
        while (i < windows.size) {
            val w = windows[i]
            if (w.ts + AFTER_NS <= newest) {
                measure(w); windows.removeAt(i)
            } else i++
        }
    }

    private fun edge(e: Edge) {
        if (e.key == fireKey) fireHeld = e.down
        if (e.key == aimKey) aimHeld = e.down
        windows.add(e)
    }

    private fun countSpeed(n: Long) {
        if (n == 0L) return
        val i = (n and H_MASK).toInt()
        val p = ((n - 1) and H_MASK).toInt()
        if (!hOn[i]) return
        val dt = (hTs[i] - hTs[p]) / 1e9
        if (dt <= 0.0 || dt > 0.05) return
        val speed = sqrt((hYaw[i] * hYaw[i] + hPitch[i] * hPitch[i]).toDouble())
        val hist = if (fireHeld) data.firing else if (aimHeld) data.aiming else data.moving
        hist.add(speed, dt)
        data.seconds += dt
        dirty = true
    }

    /** The oldest sample still kept. */
    private fun oldest(): Long = maxOf(0L, hHead - H_SIZE)

    private fun measure(w: Edge) {
        val t0 = w.ts
        val from = t0 - TREND_FROM_NS
        // Find the first sample at or after [from]; the whole window must be kept and the gyro on.
        var n = oldest()
        if (hHead - n < 8 || hTs[(n and H_MASK).toInt()] >= from) return
        while (n < hHead && hTs[(n and H_MASK).toInt()] < from) n++
        if (n == 0L) return
        // The line: the average turn from 250 to 80 ms before.
        var ty = 0.0; var tp = 0.0; var tt = 0.0
        var k = n
        while (k < hHead) {
            val i = (k and H_MASK).toInt()
            val ts = hTs[i]
            if (ts >= t0 - BEFORE_NS) break
            if (!hOn[i]) return
            val dt = (ts - hTs[((k - 1) and H_MASK).toInt()]) / 1e9
            ty += hYaw[i] * dt; tp += hPitch[i] * dt; tt += dt
            k++
        }
        if (tt < 0.1) return
        ty /= tt; tp /= tt
        // Off the line from 80 ms before to 150 ms after.
        var oy = 0.0; var op = 0.0
        var before = 0.0; var after = 0.0; var twitch = 0.0
        var beforeSide = 0.0; var beforeUp = 0.0; var afterSide = 0.0; var afterUp = 0.0
        var reachedPress = false
        while (k < hHead) {
            val i = (k and H_MASK).toInt()
            val ts = hTs[i]
            if (ts > t0 + AFTER_NS) break
            if (!hOn[i]) return
            val dt = ((ts - hTs[((k - 1) and H_MASK).toInt()]) / 1e9).coerceAtMost(0.05)
            val dy = hYaw[i] - ty; val dp = hPitch[i] - tp
            if (!reachedPress && ts > t0) {
                before = sqrt(oy * oy + op * op)
                beforeSide = abs(oy); beforeUp = abs(op)
                reachedPress = true
                // After the press counts from where the press left you.
                oy = 0.0; op = 0.0
            }
            oy += dy * dt; op += dp * dt
            if (reachedPress) {
                after = maxOf(after, sqrt(oy * oy + op * op))
                afterSide = maxOf(afterSide, abs(oy)); afterUp = maxOf(afterUp, abs(op))
            }
            twitch = maxOf(twitch, sqrt(dy * dy + dp * dp))
            k++
        }
        if (!reachedPress) return
        val s = data.inputs.getOrPut(w.key) { PressShake() }
        if (w.down) {
            s.presses++
            s.before.add(before)
            s.after.add(after)
            s.beforeSide.add(beforeSide); s.beforeUp.add(beforeUp)
            s.afterSide.add(afterSide); s.afterUp.add(afterUp)
            s.twitch.add(twitch)
            s.speedAtPress.add(sqrt(ty * ty + tp * tp))
        } else {
            s.release.add(after)
            s.releaseSide.add(afterSide); s.releaseUp.add(afterUp)
        }
        dirty = true
    }

    companion object {
        /** About 4 s of samples at ~950 Hz. */
        private const val H_SIZE = 4096
        private const val H_MASK = (H_SIZE - 1).toLong()
        /** Counting waits this long behind the newest sample, so late press edges still sort in. */
        const val DELAY_NS = 250_000_000L
        const val TREND_FROM_NS = 250_000_000L
        const val BEFORE_NS = 80_000_000L
        const val AFTER_NS = 150_000_000L
    }
}
