package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.engine.GyroMouse
import kotlin.math.roundToInt

/**
 * "Use PC controller for gyro ratchet": what the PC needs to ratchet the gyro from a controller plugged
 * into it, without sending its presses back. STATE goes out as STATE_GYRO with each gyro axis's
 * share of the motion ([encodeExt]), so the PC can take out an axis its controller ratchets or
 * put in one it engages; PAD_GYRO ([encodeButtons]) says which controller buttons do that.
 *
 * The gyro thread adds each sample's shares ([add]) and the send loop reads them ([encodeExt])
 * under [lock], together with the engine's motion, so a STATE's totals and shares always match.
 * Mouse and wheel shares are cumulative wrapping i16 totals (the PC takes wrapping differences),
 * pad shares absolute.
 */
class PcGyroFeed {
    /** The user's switch ("Use PC controller for gyro ratchet"). */
    @Volatile var enabled = false

    /** The PC in this session takes STATE_GYRO and PAD_GYRO (WELCOME has [HostFlags.GYRO_GATE] and STATE_HIRES). */
    @Volatile var hostTakes = false

    /** Shares are gathered and STATE_GYRO sent. */
    val active: Boolean get() = enabled && hostTakes

    /** Held by the gyro thread around [add] with the engine's motion, and by the send loop around its snapshot and [encodeExt]. */
    val lock = Any()

    /** The controller buttons and the toggle epoch for PAD_GYRO ([com.oleroma.gyrophone.core.engine.MappingEngine.pcGyroButtons]). */
    @Volatile var buttons: IntArray = IntArray(0)
    @Volatile var epoch: Int = 0

    private val gyro = IntArray(4)
    private val on = Array(3) { IntArray(4) }
    private val onRem = Array(3) { FloatArray(4) }
    private val glide = Array(3) { IntArray(4) }
    private val glideRem = Array(3) { FloatArray(4) }
    private val pad = Array(3) { FloatArray(PAD_AXES) }
    private var axes = 0
    private var mayMove = false
    private var lastAddNs = 0L

    /**
     * One gyro sample under [lock]: [r] what the gyro moved ([axes] on), [p] each axis's share
     * ([GyroMouse.route] with parts).
     */
    fun add(r: GyroMouse.Routed, p: GyroMouse.Parts, axes: Int) {
        gyro[0] += r.dx; gyro[1] += r.dy; gyro[2] += r.wheel; gyro[3] += r.hwheel
        for (i in 0 until 3) {
            sum(on[i], onRem[i], 0, p.onX[i]); sum(on[i], onRem[i], 1, p.onY[i])
            sum(on[i], onRem[i], 2, p.onW[i]); sum(on[i], onRem[i], 3, p.onH[i])
            sum(glide[i], glideRem[i], 0, p.glX[i]); sum(glide[i], glideRem[i], 1, p.glY[i])
            sum(glide[i], glideRem[i], 2, p.glW[i]); sum(glide[i], glideRem[i], 3, p.glH[i])
            p.pad[i].copyInto(pad[i])
        }
        this.axes = axes
        mayMove = true
        lastAddNs = System.nanoTime()
    }

    /** Under [lock]: the gyro moves nothing now (off, "Gyro off", input not allowed), so the PC must not move it either. */
    fun still() {
        for (p in pad) p.fill(0f)
        axes = 0
        mayMove = false
    }

    private fun sum(total: IntArray, rem: FloatArray, k: Int, v: Float) {
        val f = rem[k] + v
        val n = f.roundToInt()
        total[k] += n
        rem[k] = f - n
    }

    /** A new session: totals from zero, like the STATE totals. */
    fun reset() {
        synchronized(lock) {
            gyro.fill(0)
            for (i in 0 until 3) {
                on[i].fill(0); onRem[i].fill(0f); glide[i].fill(0); glideRem[i].fill(0f); pad[i].fill(0f)
            }
            axes = 0
            mayMove = false
        }
    }

    /**
     * Writes the [EXT_SIZE] bytes STATE_GYRO adds after STATE_HIRES, under [lock]: u8 flags
     * ([FLAG_MAY_MOVE]); u8 the gyro axes on ([com.oleroma.gyrophone.core.engine.MappingEngine.gyroBit]s);
     * i16 × 4 the gyro's own motion totals (x, y, wheel, hwheel); per axis (yaw, pitch, roll)
     * i16 × 4 its totals with it on, i16 × 4 its glide totals, i16 × 6 its pad share with it on
     * (lx, ly, rx, ry, lt, rt, ±32767 = full); then i16 × 6 the pad without the gyro ([nonGyro],
     * STATE units).
     */
    fun encodeExt(out: ByteArray, off: Int, nonGyro: IntArray) {
        // No sample for a while: the sensor stopped ("Gyro off", the app paused), so nothing may move.
        val live = mayMove && System.nanoTime() - lastAddNs < STALE_NS
        out.putU8(off, if (live) FLAG_MAY_MOVE else 0)
        out.putU8(off + 1, if (live) axes else 0)
        for (k in 0 until 4) out.putI16(off + 2 + k * 2, gyro[k])
        var o = off + 10
        for (i in 0 until 3) {
            for (k in 0 until 4) out.putI16(o + k * 2, on[i][k])
            for (k in 0 until 4) out.putI16(o + 8 + k * 2, glide[i][k])
            for (k in 0 until PAD_AXES) out.putI16(o + 16 + k * 2, (pad[i][k].coerceIn(-1f, 1f) * PAD_FULL).roundToInt())
            o += AXIS_SIZE
        }
        for (k in 0 until PAD_AXES) out.putI16(o + k * 2, nonGyro.getOrElse(k) { 0 })
    }

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
        const val PAD_AXES = 6
        const val AXIS_SIZE = 28
        const val EXT_SIZE = 10 + 3 * AXIS_SIZE + PAD_AXES * 2
        const val FLAG_MAY_MOVE = 1
        const val FLAG_ENABLED = 1
        const val MAX_BUTTONS = 18
        private const val PAD_FULL = 32767f
        private const val STALE_NS = 100_000_000L
    }
}
