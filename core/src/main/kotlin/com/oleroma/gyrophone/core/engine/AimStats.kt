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
    /** The mouse of each snapshot sent (the sender's thread): its buttons and the cursor move in pixels. */
    fun mouse(buttons: Int, dx: Int, dy: Int)
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

    /** Fire presses split by whether Aim was held at the press (the page's own cards, not inputs). */
    const val FIRE_SPLIT = 5L

    val LEFT_CLICK = of(CLICK, 1)
    val RIGHT_CLICK = of(CLICK, 2)
    val FIRE_AIMED = of(FIRE_SPLIT, 1)
    val FIRE_HIP = of(FIRE_SPLIT, 2)

    /** Your hand alone: the same windows at moments with no press near, while aiming or not. */
    const val BASELINE = 6L
    val HAND_AIMING = of(BASELINE, 1)
    val HAND_MOVING = of(BASELINE, 2)
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

    /** Cursor moves (sender thread; a short lock, as snapshots may come from more than one thread). */
    private val mLock = Any()
    private val mTs = LongArray(M_SIZE)
    private val mDx = IntArray(M_SIZE)
    private val mDy = IntArray(M_SIZE)
    @Volatile private var mHead = 0L
    private var mTail = 0L

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

    override fun mouse(buttons: Int, dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) {
            val t = clock()
            synchronized(mLock) {
                val h = mHead
                val i = (h and M_MASK).toInt()
                mTs[i] = t; mDx[i] = dx; mDy[i] = dy
                mHead = h + 1
            }
        }
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
        synchronized(mLock) {
            val mh = mHead
            if (mh - mTail > M_SIZE) mTail = mh - M_SIZE
            while (mTail < mh) {
                val i = (mTail and M_MASK).toInt()
                into.cursor(mTs[i], mDx[i], mDy[i])
                mTail++
            }
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
        private const val M_SIZE = 8192
        private const val M_MASK = (M_SIZE - 1).toLong()
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
    /** The shake around each press from 1 s before to 1 s after, averaged over presses. */
    val curve: PressCurve = PressCurve(),
)

/**
 * The shake around many presses, lined up at the press: for each 20 ms step from 1 s before to 1 s
 * after, the sum and the sum of squares of how far the aim was off its smooth path, with its sign, on
 * four channels ([SIDE], [UP]: the gyro's sideways (yaw) and up/down (pitch) turn in degrees; [X], [Y]:
 * the cursor GP sent, in pixels). From these: the average press curve (what comes back the same way
 * every press, the press's signature) and the spread around it (what differs from press to press).
 * Smooth path: the aim averaged over 150 ms either side, so your own aiming is not counted as shake.
 */
@Serializable
class PressCurve(
    var n: Int = 0,
    /** How many of the [n] came while Aim was held (which hand-alone level to compare with). */
    var aimed: Int = 0,
    val side: DoubleArray = DoubleArray(BINS),
    val sideSq: DoubleArray = DoubleArray(BINS),
    val up: DoubleArray = DoubleArray(BINS),
    val upSq: DoubleArray = DoubleArray(BINS),
    val x: DoubleArray = DoubleArray(BINS),
    val xSq: DoubleArray = DoubleArray(BINS),
    val y: DoubleArray = DoubleArray(BINS),
    val ySq: DoubleArray = DoubleArray(BINS),
) {
    fun sum(ch: Int) = when (ch) { SIDE -> side; UP -> up; X -> x; else -> y }
    fun sumSq(ch: Int) = when (ch) { SIDE -> sideSq; UP -> upSq; X -> xSq; else -> ySq }

    /** The average offset at step [b] (signed): the press's own curve. */
    fun mean(ch: Int, b: Int): Double = if (n == 0) 0.0 else sum(ch)[b] / n

    /** The typical size of the offset at step [b] (root mean square). */
    fun rms(ch: Int, b: Int): Double = if (n == 0) 0.0 else sqrt(sumSq(ch)[b] / n)

    /** How much the offset at step [b] differs between presses (standard deviation). */
    fun sd(ch: Int, b: Int): Double {
        val m = mean(ch, b)
        return sqrt(maxOf(0.0, rms(ch, b).let { it * it } - m * m))
    }

    /**
     * Your hand's own shake, from a hand-alone curve: the typical size of the offset in the steps
     * within 0.3 s of those moments (no press within 0.5 s, so none is in them).
     */
    fun level(ch: Int): Double {
        if (n == 0) return 0.0
        var t = 0.0; var c = 0
        for (b in CENTER - LEVEL_BINS..CENTER + LEVEL_BINS) { t += sumSq(ch)[b] / n; c++ }
        return sqrt(t / c)
    }

    companion object {
        const val SIDE = 0
        const val UP = 1
        const val X = 2
        const val Y = 3
        /** 101 steps of 20 ms: step [CENTER] is the press, step 0 is 1 s before, the last 1 s after. */
        const val BINS = 101
        const val CENTER = 50
        const val BIN_MS = 20
        const val LEVEL_BINS = 15
        fun ms(b: Int) = (b - CENTER) * BIN_MS
    }
}

/**
 * One moment of a press curve split into what made the shake (typical size, in degrees or pixels):
 * [natural], your hand alone; [kick], the press's signature (the same every press); [jitter], extra
 * shake around presses that differs each time. [total] is everything (squares add up: total² ≈
 * natural² + kick² + jitter²).
 */
class ShakeParts(val total: Double, val natural: Double, val kick: Double, val jitter: Double) {
    /** All the press adds. */
    val press: Double get() = sqrt(kick * kick + jitter * jitter)

    companion object {
        /** Step [b] of [c] on channel [ch], with [natural] your hand-alone level for it. */
        fun of(c: PressCurve, ch: Int, b: Int, natural: Double): ShakeParts {
            val m = c.mean(ch, b)
            val v = c.sd(ch, b).let { it * it }
            // The average of n presses still holds v/n of chance: take it out of the kick.
            val kick2 = maxOf(0.0, m * m - v / maxOf(1, c.n))
            val jitter2 = maxOf(0.0, v - natural * natural)
            return ShakeParts(c.rms(ch, b), minOf(natural, sqrt(v)), sqrt(kick2), sqrt(jitter2))
        }

        /** Your hand-alone level for [c]'s presses: aiming and not aiming mixed as its presses were. */
        fun natural(c: PressCurve, aiming: PressCurve?, moving: PressCurve?, ch: Int): Double {
            val f = if (c.n == 0) 0.0 else c.aimed.toDouble() / c.n
            val a = aiming?.takeIf { it.n > 0 }?.level(ch)
            val m = moving?.takeIf { it.n > 0 }?.level(ch)
            val la = a ?: m ?: 0.0
            val lm = m ?: a ?: 0.0
            return sqrt(f * la * la + (1 - f) * lm * lm)
        }

        /** The step where the press adds the most on channels [a] and [b] together (natural levels [na], [nb]). */
        fun worst(c: PressCurve, a: Int, na: Double, b: Int, nb: Double): Int {
            var best = PressCurve.CENTER; var bv = -1.0
            for (i in 0 until PressCurve.BINS) {
                val s = of(c, a, i, na).press; val u = of(c, b, i, nb).press
                val v = s * s + u * u
                if (v > bv) { bv = v; best = i }
            }
            return best
        }
    }
}

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

    private class Edge(val ts: Long, val key: Long, val down: Boolean, val aimed: Boolean = false)

    private val edges = ArrayDeque<Edge>()
    private val windows = ArrayList<Edge>()
    /** Presses (and hand-alone moments) waiting for their whole curve, 1 s on either side. */
    private val curves = ArrayDeque<Edge>()
    private var fireHeld = false
    private var aimHeld = false
    private var fireAimed = false

    /** The newest press edges' times, so a baseline moment can be kept clear of them. */
    private val recentEdges = LongArray(64)
    private var recentHead = 0
    private val candidates = ArrayDeque<Edge>()
    private var lastCandidate = -CANDIDATE_NS

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

    // The cursor's path: where it was (pixels added up) after each move, by time.
    private val pTs = LongArray(H_SIZE)
    private val pX = DoubleArray(H_SIZE)
    private val pY = DoubleArray(H_SIZE)
    private var pHead = 0L
    private var px = 0.0
    private var py = 0.0

    /** A cursor move GP sent at [ts], in pixels. */
    fun cursor(ts: Long, dx: Int, dy: Int) {
        if (pHead > 0 && ts < pTs[((pHead - 1) and H_MASK).toInt()]) return
        px += dx; py += dy
        val i = (pHead and H_MASK).toInt()
        pTs[i] = ts; pX[i] = px; pY[i] = py
        pHead++
    }

    /** Drops what is in flight (another workspace starts with a clean slate). */
    fun forget() {
        counted = hHead
        edges.clear(); windows.clear(); candidates.clear(); curves.clear()
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
        // Baseline moments, once every edge up to 300 ms after them is in and none was near.
        while (true) {
            val c = candidates.firstOrNull() ?: break
            if (c.ts + CLEAR_NS > until || c.ts + AFTER_NS > newest) break
            candidates.removeFirst()
            if (clear(c.ts)) { measure(c); curves.addLast(c) }
        }
        // Curves, once the second after is in (in time order: presses and moments are added in order).
        while (true) {
            val c = curves.firstOrNull() ?: break
            if (c.ts + CURVE_NS + SMOOTH_NS + BIN_NS > newest) break
            curves.removeFirst()
            curve(c)
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

    private fun clear(t: Long): Boolean {
        for (x in recentEdges) if (x != 0L && x > t - CLEAR_NS && x < t + CLEAR_NS) return false
        return true
    }

    private fun edge(e: Edge) {
        recentEdges[recentHead] = e.ts
        recentHead = (recentHead + 1) % recentEdges.size
        val aimed = aimHeld
        if (e.key == fireKey) {
            // Fire while aiming or from the hip: decided at the press, its release goes with it.
            if (e.down) fireAimed = aimHeld
            val split = Edge(e.ts, if (fireAimed) StatKey.FIRE_AIMED else StatKey.FIRE_HIP, e.down, aimed)
            windows.add(split)
            if (e.down) curves.addLast(split)
            fireHeld = e.down
        }
        if (e.key == aimKey) aimHeld = e.down
        val w = Edge(e.ts, e.key, e.down, aimed)
        windows.add(w)
        if (e.down) curves.addLast(w)
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
        // A baseline moment every 0.7 s while not firing (kept only if no press comes near it).
        if (!fireHeld && hTs[i] - lastCandidate >= CANDIDATE_NS) {
            lastCandidate = hTs[i]
            candidates.addLast(Edge(hTs[i], if (aimHeld) StatKey.HAND_AIMING else StatKey.HAND_MOVING, true, aimHeld))
        }
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

    // Scratch for [curve] (the Stats thread's own; no allocation per press).
    private val cTs = LongArray(H_SIZE)
    /** Per sample of the span: the path on each channel ([PressCurve.SIDE] ..). */
    private val cPath = Array(4) { DoubleArray(H_SIZE) }
    private val bSum = Array(4) { DoubleArray(PressCurve.BINS) }
    private val bN = IntArray(PressCurve.BINS)
    private val run = DoubleArray(4)

    /**
     * Adds the shake around [w] to its input's [PressCurve]: the aim's path (turn added up) from 1 s
     * before to 1 s after, minus that path smoothed over 150 ms either side, averaged per 20 ms step.
     * Skipped when the gyro was off or samples are missing in the span.
     */
    private fun curve(w: Edge) {
        val t0 = w.ts
        val from = t0 - CURVE_NS - SMOOTH_NS - BIN_NS
        val to = t0 + CURVE_NS + SMOOTH_NS + BIN_NS
        var k = oldest()
        if (hHead - k < 8 || hTs[(k and H_MASK).toInt()] >= from) return
        while (k < hHead && hTs[(k and H_MASK).toInt()] < from) k++
        // The path, sample by sample: the gyro's turn added up, and where the cursor was at that time.
        var m = 0
        var y = 0.0; var p = 0.0
        var q = maxOf(0L, pHead - H_SIZE)
        while (q < pHead && pTs[(q and H_MASK).toInt()] < from) q++
        var cx = if (q > 0 && q - 1 >= pHead - H_SIZE) pX[((q - 1) and H_MASK).toInt()] else 0.0
        var cy = if (q > 0 && q - 1 >= pHead - H_SIZE) pY[((q - 1) and H_MASK).toInt()] else 0.0
        while (k < hHead) {
            val i = (k and H_MASK).toInt()
            val ts = hTs[i]
            if (ts > to) break
            if (!hOn[i]) return
            val dt = (ts - hTs[((k - 1) and H_MASK).toInt()]) / 1e9
            if (dt <= 0.0 || dt > 0.05) return
            y += hYaw[i] * dt; p += hPitch[i] * dt
            while (q < pHead && pTs[(q and H_MASK).toInt()] <= ts) {
                val j = (q and H_MASK).toInt()
                cx = pX[j]; cy = pY[j]; q++
            }
            cTs[m] = ts
            cPath[PressCurve.SIDE][m] = y; cPath[PressCurve.UP][m] = p
            cPath[PressCurve.X][m] = cx; cPath[PressCurve.Y][m] = cy
            m++; k++
        }
        if (m < 16 || cTs[m - 1] < to - 50_000_000L) return
        // Offset from the smooth path (a running mean over ±150 ms), per 20 ms step.
        for (s in bSum) s.fill(0.0)
        bN.fill(0); run.fill(0.0)
        var lo = 0; var hi = 0
        for (j in 0 until m) {
            val t = cTs[j]
            while (hi < m && cTs[hi] <= t + SMOOTH_NS) { for (ch in 0 until 4) run[ch] += cPath[ch][hi]; hi++ }
            while (cTs[lo] < t - SMOOTH_NS) { for (ch in 0 until 4) run[ch] -= cPath[ch][lo]; lo++ }
            val b = Math.floorDiv(t - t0 + BIN_NS / 2, BIN_NS).toInt() + PressCurve.CENTER
            if (b < 0 || b >= PressCurve.BINS) continue
            val c = hi - lo
            for (ch in 0 until 4) bSum[ch][b] += cPath[ch][j] - run[ch] / c
            bN[b]++
        }
        for (b in 0 until PressCurve.BINS) if (bN[b] == 0) return
        val curve = data.inputs.getOrPut(w.key) { PressShake() }.curve
        curve.n++
        if (w.aimed) curve.aimed++
        for (ch in 0 until 4) {
            val sum = curve.sum(ch); val sq = curve.sumSq(ch)
            for (b in 0 until PressCurve.BINS) {
                val v = bSum[ch][b] / bN[b]
                sum[b] += v; sq[b] += v * v
            }
        }
        dirty = true
    }

    companion object {
        /** About 8 s of samples at ~950 Hz (a curve needs 2.4 s, plus the wait behind the newest). */
        private const val H_SIZE = 8192
        private const val H_MASK = (H_SIZE - 1).toLong()
        /** Counting waits this long behind the newest sample, so late press edges still sort in. */
        const val DELAY_NS = 250_000_000L
        const val TREND_FROM_NS = 250_000_000L
        const val BEFORE_NS = 80_000_000L
        const val AFTER_NS = 150_000_000L
        /** A baseline moment needs no press edge this close before or after it. */
        const val CLEAR_NS = 500_000_000L
        /** Press curves span this long either side of the press, in 20 ms steps. */
        const val CURVE_NS = 1_000_000_000L
        const val BIN_NS = PressCurve.BIN_MS * 1_000_000L
        /** Shake is the offset from the aim's path averaged over this long either side. */
        const val SMOOTH_NS = 150_000_000L
        const val CANDIDATE_NS = 700_000_000L
    }
}
