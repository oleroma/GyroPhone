package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.engine.GyroMath
import kotlin.math.roundToInt

/**
 * "Use PC controller for gyro ratchet": the phone stops routing the gyro itself and sends its raw
 * samples, and the PC runs the gyro math ([GyroMath], companion GyroMath.cs) with a controller
 * plugged into it ratcheting at each sample's time. STATE goes out as STATE_GYRO with [TAIL_SIZE]
 * bytes after STATE_HIRES ([encodeTail]): the last [RING] samples, per-axis running totals so the PC
 * can fill a longer gap in one step, and the gyro settings the samples are meant for. PAD_GYRO
 * ([encodeButtons]) says which controller buttons ratchet or toggle the gyro.
 *
 * The gyro thread adds each sample ([add]), stops ([still]) and sets the config ([setConfig]); the
 * send loop writes the tail ([encodeTail]). All of them hold [lock], so a tail is never half updated.
 */
class PcGyroFeed {
    /** The user's switch ("Use PC controller for gyro ratchet"). */
    @Volatile var enabled = false

    /** The PC in this session runs the gyro (WELCOME has [HostFlags.PC_GYRO] and STATE_HIRES). */
    @Volatile var hostTakes = false

    /** Samples are sent as STATE_GYRO and the phone does not route the gyro itself. */
    val active: Boolean get() = enabled && hostTakes

    /** Held by [add], [still], [setConfig], [reset] and [encodeTail]; callers may hold it around them too. */
    val lock = Any()

    /** The controller buttons and the toggle epoch for PAD_GYRO ([com.oleroma.gyrophone.core.engine.MappingEngine.pcGyroButtons]). */
    @Volatile var buttons: IntArray = IntArray(0)
    @Volatile var epoch: Int = 0

    /**
     * The phone clock in microseconds, for a sample [add] gets no time for. The app sets it to
     * elapsed realtime, the clock PING and [ClockSync] use; System.nanoTime here (tests).
     */
    @Volatile var clockUs: () -> Long = { System.nanoTime() / 1000 }

    /** A monotonic clock (ns) for "samples flowing"; replaced only by tests. */
    @Volatile var monoNs: () -> Long = { System.nanoTime() }

    // The last RING samples, oldest at (next - count) mod RING.
    private val dtUs = IntArray(RING)
    private val rate = Array(RING) { IntArray(3) }
    private val sampleAxes = IntArray(RING)
    private var next = 0
    private var count = 0
    private var seq = 0
    private var newestUs = 0L

    // The quantizer's carried remainders (in 1/16 deg/s), per axis; the running totals of rate_q * dtUs.
    private val rem = FloatArray(3)
    private val totals = LongArray(3)

    private var mayMove = false
    private var lastAddNs = 0L

    private var cfg = GyroMath.Config()
    private var tightening = 0f
    private var smoothing = 0f

    /**
     * One gyro sample the PC should move by: [yaw], [pitch], [roll] in deg/s (turned for the screen
     * and in the gyro space, [com.oleroma.gyrophone.core.engine.GyroMouse.inSpace]) over [dtSeconds]
     * since the sample before it, with the phone's own gyro axes [axes] on
     * ([com.oleroma.gyrophone.core.engine.MappingEngine.gyroAxes]), measured at [timeUs] (phone clock,
     * microseconds). Each rate is rounded to 1/16 deg/s with the remainder carried to the next sample.
     */
    fun add(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, axes: Int, timeUs: Long = clockUs()) {
        synchronized(lock) {
            val nowNs = monoNs()
            // The first sample after a stop (or after none for a while) has no time before it.
            val fresh = !mayMove || nowNs - lastAddNs >= STALE_NS
            if (fresh) rem.fill(0f)
            val dt = if (fresh) 0 else (dtSeconds * 1e6f).roundToInt().coerceIn(0, MAX_DT_US)
            val i = next
            dtUs[i] = dt
            val r = rate[i]
            r[0] = quantize(0, yaw); r[1] = quantize(1, pitch); r[2] = quantize(2, roll)
            for (k in 0 until 3) totals[k] += r[k].toLong() * dt
            sampleAxes[i] = axes and MAX_AXES
            next = (i + 1) % RING
            if (count < RING) count++
            seq = (seq + 1) and 0xFFFF
            newestUs = timeUs
            mayMove = true
            lastAddNs = nowNs
        }
    }

    /** [v] deg/s on axis [k] in 1/16 deg/s, the rounding carried on; clamped to i16. */
    private fun quantize(k: Int, v: Float): Int {
        val f = v * RATE_SCALE + rem[k]
        if (!f.isFinite()) { rem[k] = 0f; return 0 }
        val q = f.roundToInt()
        rem[k] = f - q
        return q.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /**
     * The gyro moves nothing now (input not allowed, measuring, "Gyro off"), so the PC must not move
     * it either: MAY_MOVE off, the remainders cleared and the next sample starts with no time before it.
     * Returns true if it was moving until now, so the sender has news to send.
     */
    fun still(): Boolean {
        synchronized(lock) {
            val was = mayMove
            mayMove = false
            rem.fill(0f)
            return was
        }
    }

    /** The settings the next samples are for: [c] resolved for what is held now, and the app's [tightening] and [smoothing] (deg/s, 0 = off). */
    fun setConfig(c: GyroMath.Config, tightening: Float, smoothing: Float) {
        synchronized(lock) {
            cfg = c
            this.tightening = tightening
            this.smoothing = smoothing
        }
    }

    /** A new session: no samples, seq and totals from zero. */
    fun reset() {
        synchronized(lock) {
            dtUs.fill(0)
            for (r in rate) r.fill(0)
            sampleAxes.fill(0)
            next = 0; count = 0; seq = 0; newestUs = 0L
            rem.fill(0f); totals.fill(0L)
            mayMove = false
        }
    }

    /**
     * Writes the [TAIL_SIZE] bytes STATE_GYRO adds after STATE_HIRES at [off] (layout in
     * designs/pc-gyro-spec.md): u8 flags ([FLAG_MAY_MOVE], [FLAG_CLOCK]); u8 sample count; u16
     * newest seq; u32 newest sample time (us, plus [clockOffsetUs] when known, so in the PC's
     * clock); [RING] × 9-byte samples oldest first (u16 dtUs, i16 × 3 rate in 1/16 deg/s, u8
     * axes), unused zero; i64 × 3 running totals of rate × dtUs; then the config block.
     */
    fun encodeTail(out: ByteArray, off: Int, clockOffsetUs: Long?) {
        synchronized(lock) {
            // No sample for a while: the sensor stopped ("Gyro off", the app paused), so nothing may move.
            val live = mayMove && monoNs() - lastAddNs < STALE_NS
            var flags = if (live) FLAG_MAY_MOVE else 0
            if (clockOffsetUs != null) flags = flags or FLAG_CLOCK
            out.putU8(off, flags)
            out.putU8(off + 1, count)
            out.putU16(off + 2, seq)
            out.putU32(off + 4, newestUs + (clockOffsetUs ?: 0L))
            for (j in 0 until RING) {
                val o = off + SAMPLES_OFFSET + j * SAMPLE_SIZE
                if (j < count) {
                    val i = (next - count + j + RING) % RING
                    out.putU16(o, dtUs[i])
                    out.putI16(o + 2, rate[i][0]); out.putI16(o + 4, rate[i][1]); out.putI16(o + 6, rate[i][2])
                    out.putU8(o + 8, sampleAxes[i])
                } else {
                    out.fill(0, o, o + SAMPLE_SIZE)
                }
            }
            for (k in 0 until 3) out.putI64(off + TOTALS_OFFSET + k * 8, totals[k])
            encodeConfig(out, off + CONFIG_OFFSET)
        }
    }

    /** The config block: f32 × 3 sensitivity, f32 × 3 glide ms, u8 glide bits, u8 route count, f32 tightening, f32 smoothing, [MAX_ROUTES] × (u8 axis, u8 target, f32 scale). */
    private fun encodeConfig(out: ByteArray, off: Int) {
        val c = cfg
        var glide = 0
        for (k in 0 until 3) {
            out.putF32(off + k * 4, c.sensitivity[k])
            out.putF32(off + 12 + k * 4, c.glideMs[k])
            if (c.inertia[k]) glide = glide or (1 shl k)
            if (c.linear[k]) glide = glide or (1 shl (3 + k))
        }
        val routes = c.routes
        val n = minOf(routes.size, MAX_ROUTES)
        out.putU8(off + 24, glide)
        out.putU8(off + 25, n)
        out.putF32(off + 26, tightening)
        out.putF32(off + 30, smoothing)
        for (j in 0 until MAX_ROUTES) {
            val o = off + ROUTES_OFFSET + j * ROUTE_SIZE
            if (j < n) {
                val r = routes[j]
                out.putU8(o, r.axis)
                out.putU8(o + 1, r.target)
                out.putF32(o + 2, r.scale)
            } else {
                out.fill(0, o, o + ROUTE_SIZE)
            }
        }
    }

    private fun ByteArray.putF32(off: Int, v: Float) = putI32(off, v.toRawBits())

    /**
     * PAD_GYRO plaintext ([Wire.PAD_GYRO_PLAIN]): u8 flags ([FLAG_ENABLED]); u8 toggle epoch;
     * u8 entry count; u8 0; [MAX_BUTTONS] × (u8 place, u8 kind), unused ones 0xFF.
     */
    fun encodeButtons(out: ByteArray, off: Int): Int {
        val b = buttons
        val n = minOf(b.size, MAX_BUTTONS)
        out.putU8(off, if (enabled) FLAG_ENABLED else 0)
        out.putU8(off + 1, epoch and 0xFF)
        out.putU8(off + 2, n)
        out.putU8(off + 3, 0)
        for (i in 0 until MAX_BUTTONS) {
            out.putU8(off + 4 + i * 2, if (i < n) (b[i] shr 8) and 0xFF else 0xFF)
            out.putU8(off + 5 + i * 2, if (i < n) b[i] and 0xFF else 0xFF)
        }
        return Wire.PAD_GYRO_PLAIN
    }

    companion object {
        /** Samples each STATE_GYRO repeats. */
        const val RING = 8
        const val SAMPLE_SIZE = 9
        const val SAMPLES_OFFSET = 8
        const val TOTALS_OFFSET = SAMPLES_OFFSET + RING * SAMPLE_SIZE
        const val CONFIG_OFFSET = TOTALS_OFFSET + 3 * 8
        const val MAX_ROUTES = 12
        const val ROUTE_SIZE = 6
        const val ROUTES_OFFSET = 34
        const val CONFIG_SIZE = ROUTES_OFFSET + MAX_ROUTES * ROUTE_SIZE
        /** Bytes STATE_GYRO adds after STATE_HIRES. */
        const val TAIL_SIZE = CONFIG_OFFSET + CONFIG_SIZE

        /** Rates go out in 1/16 deg/s. */
        const val RATE_SCALE = 16f
        /** A sample's dtUs is at most this (the gyro thread's own clamp). */
        const val MAX_DT_US = 50_000
        private const val MAX_AXES = 7

        /** The gyro may move: input allowed, not measuring, gyro on, samples flowing. */
        const val FLAG_MAY_MOVE = 1
        /** The newest sample time is in the PC's clock (phone time + the [ClockSync] offset). */
        const val FLAG_CLOCK = 2
        const val FLAG_ENABLED = 1
        const val MAX_BUTTONS = 18
        private const val STALE_NS = 100_000_000L
    }
}
