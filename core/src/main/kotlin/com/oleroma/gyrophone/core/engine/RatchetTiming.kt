package com.oleroma.gyrophone.core.engine

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * "Ratchet timing" (Settings › Debugging): measures each ratchet press made during a quick swing
 * and back, so it shows who was late, the player or the app.
 *
 * Fed with every gyro sample ([sample], on the gyro thread) and with the time of every button
 * or touch going down ([inputDown], any thread, the input event's own time). When a ratchet starts
 * being held, the press is the newest input down before it; [SETTLE_NS] later the swing around
 * it is looked at and a [Result] added:
 * - [Result.playerMs]: the press against the swing's turn-around (the moment the phone's turn on
 *   the faster of yaw and pitch reversed); positive = pressed after the turn.
 * - [Result.appMs]: from the press event to the first gyro sample the ratchet held back.
 * - [Result.backCounts] / [Result.backDeg]: the gyro mouse motion still sent after the turn-around
 *   and before the ratchet stopped it (the camera moving back), in counts and, when the game's
 *   counts per 360° are known, in camera degrees.
 *
 * All times are in [System.nanoTime]'s clock (input events' own clock); the caller turns the
 * sensor's timestamps into it.
 */
class RatchetTiming(private val capacity: Int = 4096) {
    /** One measured press. Null fields: not measurable (no press event, no swing found). */
    data class Result(
        val playerMs: Float?,
        val appMs: Float?,
        val backCounts: Int?,
        val backDeg: Float?,
        val swing: Boolean,
    )

    @Volatile var enabled = false
        set(v) {
            field = v
            if (!v) clear()
        }

    private val lastDown = AtomicLong(0)

    // The samples of about the last few seconds, a ring (gyro thread only).
    private val t = LongArray(capacity)
    private val yaw = FloatArray(capacity)
    private val pitch = FloatArray(capacity)
    private val dx = IntArray(capacity)
    private val dy = IntArray(capacity)
    private var head = 0
    private var count = 0

    private var wasHeld = false

    private class Pending(val pressNs: Long, val appliedNs: Long, val countsPer360: Float)

    private var pending: Pending? = null

    private val results = ArrayDeque<Result>()

    /** Goes up with every new result (for a display to redraw). */
    @Volatile var version = 0
        private set

    /** A button, key or finger went down at [eventNs]. */
    fun inputDown(eventNs: Long) {
        if (enabled && eventNs != 0L) lastDown.set(eventNs)
    }

    /**
     * One gyro sample measured at [sampleNs]: the turn [yawDps] and [pitchDps] (deg/s, as the gyro
     * mouse takes them), the mouse counts the gyro sent for it ([sentDx], [sentDy]), whether a
     * ratchet is held now ([ratchetHeld]) and the game's counts per 360° (0 = unknown).
     */
    fun sample(sampleNs: Long, yawDps: Float, pitchDps: Float, sentDx: Int, sentDy: Int, ratchetHeld: Boolean, countsPer360: Float) {
        if (!enabled) return
        t[head] = sampleNs; yaw[head] = yawDps; pitch[head] = pitchDps; dx[head] = sentDx; dy[head] = sentDy
        head = (head + 1) % capacity
        if (count < capacity) count++
        if (ratchetHeld && !wasHeld && pending == null) {
            val down = lastDown.get()
            val press = if (down != 0L && sampleNs - down in -PRESS_WINDOW_NS..PRESS_WINDOW_NS) down else 0L
            pending = Pending(press, sampleNs, countsPer360)
        }
        wasHeld = ratchetHeld
        val p = pending
        if (p != null && sampleNs - p.appliedNs >= SETTLE_NS) {
            pending = null
            add(measure(p))
        }
    }

    /** The results so far, newest first. */
    fun results(): List<Result> = synchronized(results) { results.toList() }

    fun clear() {
        synchronized(results) { results.clear() }
        pending = null; count = 0; head = 0; wasHeld = false
        lastDown.set(0)
        version++
    }

    private fun add(r: Result) {
        synchronized(results) {
            results.addFirst(r)
            while (results.size > KEPT) results.removeLast()
        }
        version++
    }

    /** The i-th sample, oldest first. */
    private fun idx(i: Int) = (head - count + i + capacity) % capacity

    private fun measure(p: Pending): Result {
        val ref = if (p.pressNs != 0L) p.pressNs else p.appliedNs
        val appMs = if (p.pressNs != 0L) (p.appliedNs - p.pressNs) / 1e6f else null
        // The samples around the press.
        val from = ref - LOOK_BACK_NS
        val to = ref + LOOK_AHEAD_NS
        val ids = ArrayList<Int>()
        for (i in 0 until count) {
            val k = idx(i)
            if (t[k] in from..to) ids.add(k)
        }
        if (ids.size < 8) return Result(null, appMs, null, null, swing = false)
        // The faster axis just before the press is the swing's.
        var sumYaw = 0f; var sumPitch = 0f
        for (k in ids) if (t[k] in ref - AXIS_NS..ref) { sumYaw += abs(yaw[k]); sumPitch += abs(pitch[k]) }
        val useYaw = sumYaw >= sumPitch
        fun rate(k: Int) = if (useYaw) yaw[k] else pitch[k]
        var peak = 0f
        for (k in ids) peak = maxOf(peak, abs(rate(k)))
        if (peak < MIN_SWING_DPS) return Result(null, appMs, null, null, swing = false)
        // Turn-arounds: the rate (lightly smoothed) going from one side of +-h to the other; the
        // moment it crossed zero between them is the turn.
        val h = peak * HYSTERESIS
        var side = 0
        var zeroNs = 0L
        var prev = 0f
        var prevT = 0L
        var best = Long.MIN_VALUE
        var bestDist = Long.MAX_VALUE
        val sm = FloatArray(ids.size)
        for (j in ids.indices) {
            val a = rate(ids[maxOf(0, j - 1)]); val b = rate(ids[j]); val c = rate(ids[minOf(ids.lastIndex, j + 1)])
            sm[j] = (a + b + c) / 3f
        }
        for (j in ids.indices) {
            val v = sm[j]
            val tj = t[ids[j]]
            if (j > 0 && (prev > 0f) != (v > 0f)) {
                // Linear crossing time between the two samples.
                val f = if (prev != v) prev / (prev - v) else 0f
                zeroNs = prevT + ((tj - prevT) * f.coerceIn(0f, 1f)).toLong()
            }
            val s = if (v > h) 1 else if (v < -h) -1 else 0
            if (s != 0) {
                if (side != 0 && s != side && zeroNs != 0L) {
                    val d = abs(zeroNs - ref)
                    if (d < bestDist) { bestDist = d; best = zeroNs }
                }
                side = s
            }
            prev = v; prevT = tj
        }
        if (best == Long.MIN_VALUE || bestDist > MAX_APEX_DISTANCE_NS) return Result(null, appMs, null, null, swing = false)
        val playerMs = if (p.pressNs != 0L) (p.pressNs - best) / 1e6f else null
        // What the gyro still sent after the turn, until the ratchet stopped it.
        var back = 0
        for (k in ids) if (t[k] > best && t[k] < p.appliedNs) back += if (useYaw) dx[k] else dy[k]
        val counts = abs(back)
        val deg = if (p.countsPer360 > 0f) counts * 360f / p.countsPer360 else null
        return Result(playerMs, appMs, counts, deg, swing = true)
    }

    companion object {
        /** Results kept. */
        const val KEPT = 10

        /** How long after a ratchet starts it is measured (the turn may come after an early press). */
        const val SETTLE_NS = 300_000_000L

        /** A press counts for a ratchet that started this close to it. */
        const val PRESS_WINDOW_NS = 300_000_000L
        const val LOOK_BACK_NS = 400_000_000L
        const val LOOK_AHEAD_NS = 280_000_000L
        const val AXIS_NS = 150_000_000L
        const val MAX_APEX_DISTANCE_NS = 250_000_000L

        /** Slower than this (deg/s at its fastest) is no swing. */
        const val MIN_SWING_DPS = 90f

        /** The share of the swing's top speed the turn must pass on both sides. */
        const val HYSTERESIS = 0.1f

        /** The middle of [values], or null when empty. */
        fun median(values: List<Float>): Float? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
        }
    }
}
