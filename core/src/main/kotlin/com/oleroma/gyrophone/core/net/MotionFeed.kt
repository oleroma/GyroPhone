package com.oleroma.gyrophone.core.net

import kotlin.math.roundToInt

/**
 * The phone's raw motion for the PC's virtual DualShock 4 or DualSense: while the PC takes it
 * ([HostFlags.MOTION] in WELCOME) and its pad is a plugged one of them (HOST_STATUS), the gyro thread adds every
 * sample ([add]) with the newest accelerometer reading ([accel]) and the send loop sends MOTION
 * ([encode]) as soon as one is new. The pad's gyro carries the phone's turn as it is, whatever the
 * app's own gyro does with it; the game's gyro settings decide what it moves.
 *
 * MOTION plaintext ([SIZE] bytes, little endian): u8 flags ([FLAG_MAY_MOVE]), u8 count (0..[RING]),
 * u16 newest sample's seq (wrapping), u32 newest sample's phone time (us, low 32 bits), then [RING]
 * slots, oldest first, each u16 us since the sample before it (0 for the first after a stop), i16 x
 * 3 gyro in 1/16 deg/s ([GYRO_SCALE]) and i16 x 3 accelerometer in 1/8192 g ([ACCEL_SCALE]). Both
 * are already in the DualShock 4's own frame and units (x right, y out of its face, z toward the
 * player; turns by the right-hand rule; +1 g on y lying still and flat), mapped from the phone's
 * axes by the workspace ([com.oleroma.gyrophone.core.engine.PadGyro]). Each packet repeats the
 * last few samples, so a lost one loses none.
 *
 * All methods hold [lock]; the gyro and accelerometer threads add, the send loop encodes.
 */
class MotionFeed {
    /** The PC in this session takes MOTION (WELCOME has [HostFlags.MOTION]). */
    @Volatile var hostTakes = false
        set(v) { if (field != v) { field = v; changed() } }

    /** The PC's pad is a plugged DualShock 4 or DualSense (HOST_STATUS output mode and PAD_PLUGGED). */
    @Volatile var padTakesMotion = false
        set(v) { if (field != v) { field = v; changed() } }

    /** MOTION should go out: the runtime keeps the gyro and accelerometer running. */
    val wanted: Boolean get() = hostTakes && padTakesMotion

    /** Runs when [wanted] changes, so the runtime can start or stop the sensors. */
    @Volatile var onWantedChange: Runnable? = null

    val lock = Any()

    private val dtUs = IntArray(RING)
    private val gyro = Array(RING) { IntArray(3) }
    private val accelAt = Array(RING) { IntArray(3) }
    private var next = 0
    private var count = 0
    private var seq = 0
    private var newestUs = 0L
    private var mayMove = false
    private var lastUs = 0L
    private val rem = FloatArray(3)
    private val accelNow = intArrayOf(0, ACCEL_SCALE.toInt(), 0)

    /** Bumped by every [add] and every [still] that stops it: the send loop sends when it moved on. */
    @Volatile var version = 0
        private set

    private var wasWanted = false

    private fun changed() {
        val w = wanted
        if (w == wasWanted) return
        wasWanted = w
        if (!w) reset()
        onWantedChange?.run()
    }

    /** The newest accelerometer reading in g, in the pad's frame (x, y, z). */
    fun accel(x: Float, y: Float, z: Float) {
        synchronized(lock) {
            accelNow[0] = clampI16(x * ACCEL_SCALE)
            accelNow[1] = clampI16(y * ACCEL_SCALE)
            accelNow[2] = clampI16(z * ACCEL_SCALE)
        }
    }

    /**
     * One gyro sample in deg/s about the pad's axes ([x], [y], [z]), measured at [timeUs] (phone
     * clock). Each rate is rounded to 1/16 deg/s with the remainder carried to the next sample, so
     * slow turns are not lost.
     */
    fun add(x: Float, y: Float, z: Float, timeUs: Long) {
        synchronized(lock) {
            val fresh = !mayMove || timeUs - lastUs !in 0..MAX_DT_US
            if (fresh) rem.fill(0f)
            val i = next
            dtUs[i] = if (fresh) 0 else (timeUs - lastUs).toInt()
            val g = gyro[i]
            g[0] = quantize(0, x); g[1] = quantize(1, y); g[2] = quantize(2, z)
            accelNow.copyInto(accelAt[i])
            next = (i + 1) % RING
            if (count < RING) count++
            seq = (seq + 1) and 0xFFFF
            newestUs = timeUs
            lastUs = timeUs
            mayMove = true
            version++
        }
    }

    /** Input is not allowed now: the pad's gyro rests. Returns true if it was moving until now. */
    fun still(): Boolean {
        synchronized(lock) {
            if (!mayMove) return false
            mayMove = false
            count = 0
            rem.fill(0f)
            version++
            return true
        }
    }

    /** A new session or the PC's pad changed: nothing to resend, the next sample starts afresh. */
    fun reset() {
        synchronized(lock) {
            count = 0
            mayMove = false
            rem.fill(0f)
            version++
        }
    }

    /** Writes MOTION's [SIZE] bytes at [off] of [out]; returns [SIZE]. */
    fun encode(out: ByteArray, off: Int): Int {
        synchronized(lock) {
            out.fill(0, off, off + SIZE)
            out.putU8(off, if (mayMove) FLAG_MAY_MOVE else 0)
            out.putU8(off + 1, count)
            out.putU16(off + 2, seq)
            out.putU32(off + 4, newestUs)
            for (k in 0 until count) {
                val i = (next - count + k + RING) % RING
                val o = off + HEADER + k * SAMPLE
                out.putU16(o, dtUs[i].coerceIn(0, 0xFFFF))
                for (a in 0 until 3) {
                    out.putI16(o + 2 + a * 2, gyro[i][a])
                    out.putI16(o + 8 + a * 2, accelAt[i][a])
                }
            }
            return SIZE
        }
    }

    private fun quantize(k: Int, v: Float): Int {
        val f = v * GYRO_SCALE + rem[k]
        if (!f.isFinite()) { rem[k] = 0f; return 0 }
        val q = f.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        rem[k] = (f - q).coerceIn(-1f, 1f)
        return q
    }

    private fun clampI16(v: Float): Int =
        if (!v.isFinite()) 0 else v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    companion object {
        const val RING = 8
        const val HEADER = 8
        const val SAMPLE = 14
        const val SIZE = HEADER + RING * SAMPLE
        const val FLAG_MAY_MOVE = 1
        const val GYRO_SCALE = 16f
        const val ACCEL_SCALE = 8192f
        /** A gap longer than this starts afresh (no time before the sample). */
        const val MAX_DT_US = 50_000L
        /** MOTION goes out at least this often while wanted, so a lost stop still arrives. */
        const val KEEPALIVE_MS = 50L
    }
}
