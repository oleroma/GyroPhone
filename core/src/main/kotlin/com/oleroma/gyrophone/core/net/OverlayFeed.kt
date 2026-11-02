package com.oleroma.gyrophone.core.net

import kotlin.math.roundToInt

/** One control as the PC's phone overlay draws it. Centre and size are fractions of the pad area. */
data class OverlayControl(
    val id: String,
    val kind: Int,
    val round: Boolean,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val label: String,
    /** 0..1, the workspace's control opacity (or the corners'). */
    val opacity: Float,
) {
    companion object {
        const val BUTTON = 0
        const val STICK = 1
        const val CORNER = 2
        const val DISPLAY = 3
    }
}

/**
 * What the phone's screen shows, for the PC's phone overlay: the pad area's size in pixels (its
 * aspect, and portrait or landscape), the workspace and mapping set names, and the controls from
 * back to front.
 */
data class OverlayLayout(
    val widthPx: Int,
    val heightPx: Int,
    val workspace: String,
    val layer: String,
    val controls: List<OverlayControl>,
)

/**
 * The data of the PC's phone overlay, gathered while the PC asks for it ([wanted], from
 * HOST_STATUS [HostStatusFlags.OVERLAY]). The pad screen sets the layout, fingers and stick knobs;
 * the sensor threads add the phone's motion ([gyro], [up], [north]); the Wi-Fi pump encodes
 * OVERLAY ([encodeFrame]) about 60 times a second and the layout in OVERLAY_LAYOUT pieces
 * ([layoutSnapshot], [encodeLayoutPiece]) when it changes and every few seconds. The PC turns the
 * motion into a pose with its own settings (gravity, compass, reset on ratchet, ease back).
 *
 * OVERLAY plaintext (136 bytes, little endian): u32 phone time (us); f32 × 3 yaw, pitch, roll
 * turned since the last OVERLAY (degrees, as [com.oleroma.gyrophone.core.engine.GyroMouse.motion]
 * gives them); u16 the time those samples cover (10 us units); i16 × 3 the newest accelerometer
 * reading in the phone frame, right, up, toward the player ([G_SCALE] = 1 g); i16 × 3 the newest
 * magnetometer reading in that frame (unit length × 32767); u8 the gyro axes moving the mouse
 * ([com.oleroma.gyrophone.core.engine.MappingEngine.gyroBit]); u8 ratchet presses so far (wraps);
 * u16 layout version; u8 flags ([FLAG_POSE], [FLAG_PAD_SHOWN], [FLAG_GYRO_ACTIVE], [FLAG_UP],
 * [FLAG_NORTH], [FLAG_RATCHET_HELD]); u8 finger count; 5 × (u16 x, u16 y) fingers (0..65535 of the
 * pad area); 16 bytes pressed bitmap and 16 bytes latched bitmap (bit i = control i of the layout);
 * 8 × (u8 control index or 0xFF, i8 x, i8 y) stick knobs (-127..127 of the radius, y down); f32 × 3
 * yaw, pitch, roll the phone would have turned to send the mouse counts sent since the last OVERLAY by
 * anything but the live gyro ([camera]: touchpad, buttons, glides..., for a trackball); f32 × 3 the
 * game's camera degrees per phone degree on yaw, pitch, roll (real world sensitivity, 0 = unknown).
 *
 * OVERLAY_LAYOUT plaintext (200 bytes): u16 layout version; u8 piece index; u8 piece count; u8
 * length; then [PIECE_DATA] bytes of the layout blob. The blob: u8 format (1); u16 width px; u16
 * height px; str8 workspace; str8 layer; u8 control count; per control u8 kind, u8 flags (1 =
 * round), i16 x, y, w, h (× [COORD_SCALE] of the pad area's width / height), u8 opacity (0..255),
 * str8 label. str8 = u8 byte length + UTF-8.
 */
class OverlayFeed {
    /** The PC's overlay is on and the phone should send its data. */
    @Volatile var wanted = false
        set(v) {
            if (v == field) return
            field = v
            if (!v) compassWanted = false
            synchronized(lock) { clearMotion() }
            onWantedChange?.run()
        }

    /** The PC's overlay holds its heading by the compass: the magnetometer runs too ([HostStatusFlags.OVERLAY_COMPASS]). */
    @Volatile var compassWanted = false
        set(v) {
            if (v == field) return
            field = v
            onWantedChange?.run()
        }

    /** Runs (on the link's thread) when [wanted] or [compassWanted] changes, so the runtime can start or stop the sensors. */
    @Volatile var onWantedChange: Runnable? = null

    /** The pad is on screen in play (not edit mode, other pages or other apps). */
    @Volatile var padShown = false

    /** Controls the pad screen itself holds (workspace corners, touched sticks): lit with the engine's pressed ones. */
    @Volatile var uiHeld: Set<String> = emptySet()

    /** The gyro moves the mouse now. */
    @Volatile var gyroActive = false

    private val lock = Any()
    private var layout: OverlayLayout? = null
    private var version = 0
    private var blob = ByteArray(0)
    private var index: Map<String, Int> = emptyMap()
    private val fingerXY = FloatArray(MAX_FINGERS * 2)
    private var fingerCount = 0
    private val sticks = LinkedHashMap<String, FloatArray>()

    // Motion since the last OVERLAY (under lock).
    private var turnYaw = 0f
    private var turnPitch = 0f
    private var turnRoll = 0f
    private var turnSeconds = 0f
    private var gyroAtNs = Long.MIN_VALUE
    private var axes = 0
    private var ratchetHeld = false
    private var ratchetPresses = 0
    private val upXYZ = FloatArray(3)
    private var hasUp = false
    private val northXYZ = FloatArray(3)
    private var hasNorth = false
    private val cameraDeg = FloatArray(3)
    private val rwsGain = FloatArray(3)

    private fun clearMotion() {
        turnYaw = 0f; turnPitch = 0f; turnRoll = 0f; turnSeconds = 0f
        cameraDeg.fill(0f)
        gyroAtNs = Long.MIN_VALUE
        hasUp = false; hasNorth = false
        ratchetHeld = false
    }

    /**
     * One gyro sample (gyro thread): rates in deg/s ([com.oleroma.gyrophone.core.engine.GyroMouse.motion])
     * over [dtSeconds]; [axesMoving] the axes moving the mouse; a ratchet is [ratchet] held.
     */
    fun gyro(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, axesMoving: Int, ratchet: Boolean) {
        synchronized(lock) {
            turnYaw += yaw * dtSeconds
            turnPitch += pitch * dtSeconds
            turnRoll += roll * dtSeconds
            turnSeconds += dtSeconds
            gyroAtNs = System.nanoTime()
            axes = axesMoving
            if (ratchet && !ratchetHeld) ratchetPresses = (ratchetPresses + 1) and 0xFF
            ratchetHeld = ratchet
        }
    }

    /** An accelerometer reading in the phone frame (right, up, toward the player), [g] = 1 g in its units. */
    /**
     * Mouse counts sent by anything but the live gyro, as the phone turn (degrees on yaw, pitch,
     * roll) that would send them ([com.oleroma.gyrophone.core.engine.GyroDisplayFeed.cameraDegrees]),
     * and the game's camera degrees per phone degree on each axis (0 = unknown).
     */
    fun camera(yaw: Float, pitch: Float, roll: Float) {
        synchronized(lock) {
            cameraDeg[0] += yaw; cameraDeg[1] += pitch; cameraDeg[2] += roll
        }
    }

    /** The game's camera degrees per phone degree on yaw, pitch, roll (0 = unknown). */
    fun rws(yaw: Float, pitch: Float, roll: Float) {
        synchronized(lock) {
            rwsGain[0] = yaw; rwsGain[1] = pitch; rwsGain[2] = roll
        }
    }

    fun up(x: Float, y: Float, z: Float, g: Float) {
        if (g == 0f) return
        synchronized(lock) {
            upXYZ[0] = x / g; upXYZ[1] = y / g; upXYZ[2] = z / g
            hasUp = true
        }
    }

    /** A magnetometer reading in the phone frame, any unit. */
    fun north(x: Float, y: Float, z: Float) {
        val l = kotlin.math.sqrt(x * x + y * y + z * z)
        if (l == 0f) return
        synchronized(lock) {
            northXYZ[0] = x / l; northXYZ[1] = y / l; northXYZ[2] = z / l
            hasNorth = true
        }
    }

    /** Sets the layout shown; a different one gets a new version. */
    fun setLayout(l: OverlayLayout) {
        synchronized(lock) {
            if (l == layout) return
            layout = l
            version = (version + 1) and 0xFFFF
            blob = encodeLayout(l)
            index = l.controls.take(MAX_CONTROLS).withIndex().associate { (i, c) -> c.id to i }
        }
    }

    /** Fingers on the screen now, as x, y pairs (fractions of the pad area); only the first [MAX_FINGERS]. */
    fun setFingers(xy: FloatArray, count: Int) {
        synchronized(lock) {
            fingerCount = count.coerceAtMost(MAX_FINGERS)
            System.arraycopy(xy, 0, fingerXY, 0, fingerCount * 2)
        }
    }

    /** Touch stick [id]'s knob, each axis -1..1 (y down); 0, 0 = let go. */
    fun setStick(id: String, x: Float, y: Float) {
        synchronized(lock) {
            if (x == 0f && y == 0f) sticks.remove(id) else sticks.getOrPut(id) { FloatArray(2) }.let { it[0] = x; it[1] = y }
        }
    }

    /** Lets go of every finger and stick (the pad left the screen or reset). */
    fun clearTouches() {
        synchronized(lock) {
            fingerCount = 0
            sticks.clear()
        }
    }

    /** The layout version and blob now, for one round of OVERLAY_LAYOUT pieces. */
    fun layoutSnapshot(): Pair<Int, ByteArray>? = synchronized(lock) { if (layout == null) null else version to blob }

    /** Encodes OVERLAY into [out] at [off] and returns its length ([Wire.OVERLAY_PLAIN]); the motion starts again from 0. */
    fun encodeFrame(out: ByteArray, off: Int, phoneTimeUs: Int, pressed: Set<String>, latched: Set<String>): Int {
        out.fill(0, off, off + Wire.OVERLAY_PLAIN)
        out.putU32(off, phoneTimeUs)
        synchronized(lock) {
            val at = gyroAtNs
            val live = at != Long.MIN_VALUE && System.nanoTime() - at < POSE_STALE_NS
            out.putU32(off + 4, java.lang.Float.floatToRawIntBits(turnYaw))
            out.putU32(off + 8, java.lang.Float.floatToRawIntBits(turnPitch))
            out.putU32(off + 12, java.lang.Float.floatToRawIntBits(turnRoll))
            out.putU16(off + 16, (turnSeconds * 100_000f).roundToInt().coerceIn(0, 0xFFFF))
            turnYaw = 0f; turnPitch = 0f; turnRoll = 0f; turnSeconds = 0f
            for (i in 0 until 3) {
                out.putI16(off + 18 + i * 2, (upXYZ[i] * G_SCALE).roundToInt().coerceIn(-32767, 32767))
                out.putI16(off + 24 + i * 2, (northXYZ[i] * 32767f).roundToInt().coerceIn(-32767, 32767))
            }
            out.putU8(off + 30, axes)
            out.putU8(off + 31, ratchetPresses)
            out.putU16(off + 32, version)
            var flags = 0
            if (live) flags = flags or FLAG_POSE
            if (padShown) flags = flags or FLAG_PAD_SHOWN
            if (gyroActive) flags = flags or FLAG_GYRO_ACTIVE
            if (hasUp) flags = flags or FLAG_UP
            if (hasNorth) flags = flags or FLAG_NORTH
            if (ratchetHeld) flags = flags or FLAG_RATCHET_HELD
            out.putU8(off + 34, flags)
            out.putU8(off + 35, fingerCount)
            for (i in 0 until fingerCount) {
                out.putU16(off + 36 + i * 4, unit16(fingerXY[i * 2]))
                out.putU16(off + 38 + i * 4, unit16(fingerXY[i * 2 + 1]))
            }
            fun bits(ids: Set<String>, at: Int) {
                for (id in ids) {
                    val i = index[id] ?: continue
                    out[at + (i shr 3)] = (out[at + (i shr 3)].toInt() or (1 shl (i and 7))).toByte()
                }
            }
            bits(pressed, off + 56)
            bits(uiHeld, off + 56)
            bits(latched, off + 72)
            var n = 0
            for (k in 0 until MAX_STICKS) out.putU8(off + 88 + k * 3, NO_CONTROL)
            for ((id, v) in sticks) {
                if (n >= MAX_STICKS) break
                val i = index[id] ?: continue
                val p = off + 88 + n * 3
                out.putU8(p, i)
                out[p + 1] = (v[0].coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
                out[p + 2] = (v[1].coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
                n++
            }
            for (i in 0 until 3) {
                out.putU32(off + 112 + i * 4, java.lang.Float.floatToRawIntBits(cameraDeg[i]))
                out.putU32(off + 124 + i * 4, java.lang.Float.floatToRawIntBits(rwsGain[i]))
            }
            cameraDeg.fill(0f)
        }
        return Wire.OVERLAY_PLAIN
    }

    companion object {
        const val FLAG_POSE = 1
        const val FLAG_PAD_SHOWN = 2
        const val FLAG_GYRO_ACTIVE = 4
        const val FLAG_UP = 8
        const val FLAG_NORTH = 16
        const val FLAG_RATCHET_HELD = 32
        /** The accelerometer's 1 g in OVERLAY. */
        const val G_SCALE = 8192f
        const val MAX_FINGERS = 5
        const val MAX_CONTROLS = 128
        const val MAX_STICKS = 8
        const val NO_CONTROL = 0xFF
        const val FORMAT = 1
        const val COORD_SCALE = 8192f
        const val PIECE_DATA = Wire.OVERLAY_LAYOUT_PLAIN - 5
        const val MAX_PIECES = 255
        private const val POSE_STALE_NS = 250_000_000L
        private const val NAME_MAX = 48
        private const val LABEL_MAX = 32

        private fun unit16(v: Float): Int = (v.coerceIn(0f, 1f) * 65535f).roundToInt()

        private fun coord(v: Float): Int = (v * COORD_SCALE).roundToInt().coerceIn(-32768, 32767)

        /** The layout blob (see the class comment), cut to [MAX_CONTROLS] controls and [MAX_PIECES] pieces. */
        fun encodeLayout(l: OverlayLayout): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun u8(v: Int) = out.write(v and 0xFF)
            fun u16(v: Int) { u8(v); u8(v shr 8) }
            fun str(s: String, max: Int) {
                val b = WireCodec.utf8Truncated(s, max)
                u8(b.size); out.write(b)
            }
            u8(FORMAT)
            u16(l.widthPx.coerceIn(0, 0xFFFF))
            u16(l.heightPx.coerceIn(0, 0xFFFF))
            str(l.workspace, NAME_MAX)
            str(l.layer, NAME_MAX)
            val cs = l.controls.take(MAX_CONTROLS)
            u8(cs.size)
            for (c in cs) {
                u8(c.kind)
                u8(if (c.round) 1 else 0)
                u16(coord(c.x)); u16(coord(c.y)); u16(coord(c.w)); u16(coord(c.h))
                u8((c.opacity.coerceIn(0f, 1f) * 255f).roundToInt())
                str(c.label, LABEL_MAX)
            }
            val b = out.toByteArray()
            return if (b.size <= PIECE_DATA * MAX_PIECES) b else b.copyOf(PIECE_DATA * MAX_PIECES)
        }

        /** Pieces [blob] takes. */
        fun pieceCount(blob: ByteArray): Int = maxOf(1, (blob.size + PIECE_DATA - 1) / PIECE_DATA)

        /** Encodes OVERLAY_LAYOUT piece [piece] of [blob] (layout [version]) into [out] at [off]; returns its length. */
        fun encodeLayoutPiece(out: ByteArray, off: Int, version: Int, blob: ByteArray, piece: Int): Int {
            out.fill(0, off, off + Wire.OVERLAY_LAYOUT_PLAIN)
            val count = pieceCount(blob)
            val start = piece * PIECE_DATA
            val len = (blob.size - start).coerceIn(0, PIECE_DATA)
            out.putU16(off, version)
            out.putU8(off + 2, piece)
            out.putU8(off + 3, count)
            out.putU8(off + 4, len)
            System.arraycopy(blob, start, out, off + 5, len)
            return Wire.OVERLAY_LAYOUT_PLAIN
        }
    }
}
