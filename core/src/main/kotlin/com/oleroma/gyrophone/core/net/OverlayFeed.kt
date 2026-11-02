package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.engine.GyroPose
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
 * the gyro thread turns [pose]; the Wi-Fi pump encodes OVERLAY ([encodeFrame]) about 60 times a
 * second and the layout in OVERLAY_LAYOUT pieces ([layoutSnapshot], [encodeLayoutPiece]) when it
 * changes and every few seconds.
 *
 * OVERLAY plaintext (96 bytes, little endian): u32 phone time (us); i16 × 4 pose quaternion w, x,
 * y, z (× 32767; phone frame x right, y up, z out of the screen, to the player's view); u16 layout
 * version; u8 flags ([FLAG_POSE], [FLAG_PAD_SHOWN], [FLAG_GYRO_ACTIVE]); u8 finger count; 5 ×
 * (u16 x, u16 y) fingers (0..65535 of the pad area); 16 bytes pressed bitmap and 16 bytes latched
 * bitmap (bit i = control i of the layout); 8 × (u8 control index or 0xFF, i8 x, i8 y) stick
 * knobs (-127..127 of the radius, y down); 4 bytes reserved.
 *
 * OVERLAY_LAYOUT plaintext (200 bytes): u16 layout version; u8 piece index; u8 piece count; u8
 * length; then [PIECE_DATA] bytes of the layout blob. The blob: u8 format (1); u16 width px; u16
 * height px; str8 workspace; str8 layer; u8 control count; per control u8 kind, u8 flags (1 =
 * round), i16 x, y, w, h (× [COORD_SCALE] of the pad area's width / height), u8 opacity (0..255),
 * str8 label. str8 = u8 byte length + UTF-8.
 */
class OverlayFeed {
    /** The PC's overlay is on and the phone should send its data; turning on starts [pose] afresh. */
    @Volatile var wanted = false
        set(v) {
            if (v == field) return
            field = v
            if (v) pose.reset()
            onWantedChange?.run()
        }

    /** Runs (on the link's thread) when [wanted] changes, so the runtime can start or stop the sensors. */
    @Volatile var onWantedChange: Runnable? = null

    /** The phone's pose against the world, held to gravity, heading eased back to facing the player. */
    val pose = GyroPose()

    /** When the gyro last turned [pose] ([System.nanoTime]); a pose older than [POSE_STALE_NS] is not live. */
    @Volatile private var poseAtNs = Long.MIN_VALUE

    /** The gyro turned [pose] just now. */
    fun poseTurned() {
        poseAtNs = System.nanoTime()
    }

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
    private val quat = FloatArray(4)

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

    /** Encodes OVERLAY into [out] at [off] and returns its length ([Wire.OVERLAY_PLAIN]). */
    fun encodeFrame(out: ByteArray, off: Int, phoneTimeUs: Int, pressed: Set<String>, latched: Set<String>): Int {
        out.fill(0, off, off + Wire.OVERLAY_PLAIN)
        out.putU32(off, phoneTimeUs)
        val at = poseAtNs
        val live = at != Long.MIN_VALUE && System.nanoTime() - at < POSE_STALE_NS
        if (live) pose.quaternion(quat) else { quat[0] = 1f; quat[1] = 0f; quat[2] = 0f; quat[3] = 0f }
        for (i in 0 until 4) out.putI16(off + 4 + i * 2, (quat[i].coerceIn(-1f, 1f) * Q_SCALE).roundToInt())
        synchronized(lock) {
            out.putU16(off + 12, version)
            var flags = 0
            if (live) flags = flags or FLAG_POSE
            if (padShown) flags = flags or FLAG_PAD_SHOWN
            if (gyroActive) flags = flags or FLAG_GYRO_ACTIVE
            out.putU8(off + 14, flags)
            out.putU8(off + 15, fingerCount)
            for (i in 0 until fingerCount) {
                out.putU16(off + 16 + i * 4, unit16(fingerXY[i * 2]))
                out.putU16(off + 18 + i * 4, unit16(fingerXY[i * 2 + 1]))
            }
            fun bits(ids: Set<String>, at: Int) {
                for (id in ids) {
                    val i = index[id] ?: continue
                    out[at + (i shr 3)] = (out[at + (i shr 3)].toInt() or (1 shl (i and 7))).toByte()
                }
            }
            bits(pressed, off + 36)
            bits(uiHeld, off + 36)
            bits(latched, off + 52)
            var n = 0
            for (k in 0 until MAX_STICKS) out.putU8(off + 68 + k * 3, NO_CONTROL)
            for ((id, v) in sticks) {
                if (n >= MAX_STICKS) break
                val i = index[id] ?: continue
                val at = off + 68 + n * 3
                out.putU8(at, i)
                out[at + 1] = (v[0].coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
                out[at + 2] = (v[1].coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
                n++
            }
        }
        return Wire.OVERLAY_PLAIN
    }

    companion object {
        const val FLAG_POSE = 1
        const val FLAG_PAD_SHOWN = 2
        const val FLAG_GYRO_ACTIVE = 4
        const val MAX_FINGERS = 5
        const val MAX_CONTROLS = 128
        const val MAX_STICKS = 8
        const val NO_CONTROL = 0xFF
        const val FORMAT = 1
        const val COORD_SCALE = 8192f
        const val PIECE_DATA = Wire.OVERLAY_LAYOUT_PLAIN - 5
        const val MAX_PIECES = 255
        private const val Q_SCALE = 32767f
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
