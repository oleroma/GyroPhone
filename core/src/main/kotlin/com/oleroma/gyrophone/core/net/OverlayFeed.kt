package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.model.AndroidKey
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
 * OVERLAY plaintext (152 bytes, little endian): u32 phone time (us); f32 × 3 yaw, pitch, roll
 * turned since the last OVERLAY (degrees, as [com.oleroma.gyrophone.core.engine.GyroMouse.motion]
 * gives them); u16 the time those samples cover (10 us units); i16 × 3 the newest accelerometer
 * reading in the phone frame, right, up, toward the player ([G_SCALE] = 1 g); i16 × 3 the newest
 * magnetometer reading in that frame (unit length × 32767); u8 the gyro axes moving the mouse
 * ([com.oleroma.gyrophone.core.engine.MappingEngine.gyroBit]); u8 ratchet presses so far (wraps);
 * u16 layout version; u8 flags ([FLAG_POSE], [FLAG_PAD_SHOWN], [FLAG_GYRO_ACTIVE], [FLAG_UP],
 * [FLAG_NORTH], [FLAG_RATCHET_HELD], [FLAG_TOTALS], [FLAG_REAL_WORLD]); u8 finger count; 5 × (u16 x, u16 y) fingers (0..65535 of the
 * pad area); 16 bytes pressed bitmap and 16 bytes latched bitmap (bit i = control i of the layout);
 * 8 × (u8 control index or 0xFF, i8 x, i8 y) stick knobs (-127..127 of the radius, y down); f32 × 3
 * yaw, pitch, roll the phone would have turned to send the mouse counts sent since the last OVERLAY by
 * anything but the live gyro ([camera]: touchpad, buttons, glides..., for a trackball); f32 × 3 the
 * game's camera degrees per phone degree on yaw, pitch, roll (real world sensitivity, 0 = unknown);
 * then the controller attached to the phone (for the PC phone overlay's handles): u8 flags
 * ([PAD_ATTACHED]); u8 left and u8 right trigger (0..255); u8 Set orientation presses so far
 * (wraps at 256: a change starts every PC overlay shown again from the new base orientation, [levelDisplays]); u32 buttons held ([PAD_SOUTH]..,
 * the D-pad from keys or the hat); i8 × 4 left x, y, right x, y stick (-127..127, y down); u8 Clear
 * displays presses so far (wraps at 256: a change clears every PC laser pointer, [clearDisplays]); u8 × 2
 * the gyro axis on mouse X and on mouse Y ([cameraMap], [com.oleroma.gyrophone.core.engine.GyroDisplayFeed.cameraMapOf]:
 * an overlay turning with the camera turns as the game's camera, by those); 1 byte 0.
 *
 * OVERLAY_LAYOUT plaintext (200 bytes): u16 layout version; u8 piece index; u8 piece count; u8
 * length; then [PIECE_DATA] bytes of the layout blob. The blob: u8 format (1); u16 width px; u16
 * height px; str8 workspace; str8 layer; u8 control count; per control u8 kind, u8 flags (1 =
 * round), i16 x, y, w, h (× [COORD_SCALE] of the pad area's width / height), u8 opacity (0..255),
 * str8 label. str8 = u8 byte length + UTF-8.
 *
 * Totals ([FLAG_TOTALS], only while HOST_STATUS has [HostStatusFlags.OVERLAY_TOTALS]; older PCs get
 * the per-frame form above): the yaw, pitch, roll and the camera degrees are running totals since
 * the feed was cleared ([wanted] off, which a session's end also does), kept in doubles and sent
 * modulo 360 in -180..180; the time covered is a running total too, modulo 65536 units (0.65536 s).
 * The PC takes the difference from the last frame it got (the angles to the nearest equivalent
 * modulo 360, the time modulo 65536), so a lost OVERLAY no longer loses its turn. Only more than
 * 180 degrees on one axis, or 0.65 s of gyro time, between two frames that arrive is misread.
 */
class OverlayFeed {
    /** The PC's overlay is on and the phone should send its data. */
    @Volatile var wanted = false
        set(v) {
            if (v == field) return
            field = v
            if (!v) compassWanted = false
            synchronized(lock) {
                clearMotion()
                // Axes are fed only while wanted, so they would be stale when it turns on again.
                if (!v) padAxes.fill(0f)
            }
            onWantedChange?.run()
        }

    /** The PC's overlay holds its heading by the compass: the magnetometer runs too ([HostStatusFlags.OVERLAY_COMPASS]). */
    @Volatile var compassWanted = false
        set(v) {
            if (v == field) return
            field = v
            onWantedChange?.run()
        }

    /** The PC takes running totals ([HostStatusFlags.OVERLAY_TOTALS]): OVERLAY carries [FLAG_TOTALS]. */
    @Volatile var totalsWanted = false

    /** The PC follows every gyro sample ([HostStatusFlags2.OVERLAY_SAMPLES]): OVERLAY_SAMPLES goes before each OVERLAY. */
    @Volatile var samplesWanted = false

    /** Runs (on the link's thread) when [wanted] or [compassWanted] changes, so the runtime can start or stop the sensors. */
    @Volatile var onWantedChange: Runnable? = null

    /** The pad is on screen in play (not edit mode, other pages or other apps). */
    @Volatile var padShown = false

    /** Controls the pad screen itself holds (workspace corners, touched sticks): lit with the engine's pressed ones. */
    @Volatile var uiHeld: Set<String> = emptySet()

    /** The gyro moves the mouse now. */
    @Volatile var gyroActive = false

    /** World or Player gyro space: the motion comes about the screen's own axes ([FLAG_REAL_WORLD]). */
    @Volatile var realWorld = false

    private val lock = Any()
    private var layout: OverlayLayout? = null
    private var version = 0
    private var blob = ByteArray(0)
    private var index: Map<String, Int> = emptyMap()
    private val fingerXY = FloatArray(MAX_FINGERS * 2)
    private var fingerCount = 0
    private val sticks = LinkedHashMap<String, FloatArray>()

    // Each gyro sample's turn since the last OVERLAY_SAMPLES (under lock, while [samplesWanted]): yaw, pitch, roll
    // (degrees) and seconds; the last takes on any over [MAX_SAMPLES].
    private val samples = FloatArray(MAX_SAMPLES * 4)
    private var sampleCount = 0
    // OVERLAY_SAMPLES was sent: the turn up to then is held for the OVERLAY that follows, so the two match.
    private var held = false
    private val heldTurn = FloatArray(4)

    // Motion since the last OVERLAY (under lock).
    private var turnYaw = 0f
    private var turnPitch = 0f
    private var turnRoll = 0f
    private var turnSeconds = 0f
    private var gyroAtNs = Long.MIN_VALUE
    private var axes = 0
    private var ratchetHeld = false
    private var ratchetPresses = 0
    private var levelPresses = 0
    private var clearPresses = 0

    /** Which gyro axis moves the mouse sideways (low byte) and up and down (next byte), set by the gyro thread. */
    @Volatile var cameraMap: Int = com.oleroma.gyrophone.core.engine.GyroDisplayFeed.DEFAULT_CAMERA_MAP
    private val upXYZ = FloatArray(3)
    private var hasUp = false
    private val northXYZ = FloatArray(3)
    private var hasNorth = false
    private val cameraDeg = FloatArray(3)
    private val rwsGain = FloatArray(3)
    // Running totals (under lock): turn and camera degrees modulo 360, seconds modulo TIME_WRAP.
    private val totalTurn = DoubleArray(3)
    private val totalCamera = DoubleArray(3)
    private var totalSeconds = 0.0

    // The attached controller (under lock): buttons held from keys, sticks, triggers and hat from axes.
    @Volatile var padAttached = false
        set(v) {
            field = v
            if (!v) clearPad()
        }
    private var padKeys = 0
    private val padAxes = FloatArray(8)

    /** Set orientation: every PC overlay shown starts again, level and facing ahead, from the base orientation the frames now come in. */
    fun levelDisplays() {
        synchronized(lock) { levelPresses = (levelPresses + 1) and 0xFF }
    }

    /** A Clear displays mapping fired: the PC clears every laser pointer overlay's paint and trail. */
    fun clearDisplays() {
        synchronized(lock) { clearPresses = (clearPresses + 1) and 0xFF }
    }

    /** A key of an attached controller went down or up (Android key code). */
    fun padKey(keyCode: Int, down: Boolean) {
        val bit = padBit(keyCode)
        if (bit == 0) return
        synchronized(lock) { padKeys = if (down) padKeys or bit else padKeys and bit.inv() }
    }

    /** An attached controller's sticks (-1..1, y down), triggers (0..1) and hat (-1..1). */
    fun padAxes(lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Float, hatY: Float) {
        synchronized(lock) {
            padAxes[0] = lx; padAxes[1] = ly; padAxes[2] = rx; padAxes[3] = ry
            padAxes[4] = lt; padAxes[5] = rt; padAxes[6] = hatX; padAxes[7] = hatY
        }
    }

    /** Lets go of every controller button and axis (a controller went away, or focus left). */
    fun clearPad() {
        synchronized(lock) { padKeys = 0; padAxes.fill(0f) }
    }

    private fun clearMotion() {
        turnYaw = 0f; turnPitch = 0f; turnRoll = 0f; turnSeconds = 0f
        held = false
        sampleCount = 0
        samples.fill(0f)
        cameraDeg.fill(0f)
        totalTurn.fill(0.0); totalCamera.fill(0.0); totalSeconds = 0.0
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
            if (samplesWanted) {
                val i = minOf(sampleCount, MAX_SAMPLES - 1) * 4
                samples[i] += yaw * dtSeconds; samples[i + 1] += pitch * dtSeconds; samples[i + 2] += roll * dtSeconds
                samples[i + 3] += dtSeconds
                if (sampleCount < MAX_SAMPLES) sampleCount++
            }
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

    /** The layout version now, or -1 with no layout; read without allocating before a [layoutSnapshot]. */
    fun layoutVersion(): Int = synchronized(lock) { if (layout == null) -1 else version }

    /** The layout version and blob now, for one round of OVERLAY_LAYOUT pieces. */
    fun layoutSnapshot(): Pair<Int, ByteArray>? = synchronized(lock) { if (layout == null) null else version to blob }

    /** Encodes OVERLAY into [out] at [off] and returns its length ([Wire.OVERLAY_PLAIN]); the motion since the last one starts again from 0. */
    /**
     * OVERLAY_SAMPLES (plaintext [Wire.OVERLAY_SAMPLES_PLAIN] bytes, little endian), sent just before the OVERLAY of the
     * same [phoneTimeUs]: u32 that phone time; u8 sample count (up to [MAX_SAMPLES]); 3 bytes 0; then per sample i16
     * yaw, pitch, roll turned (thousandths of a degree) and u16 its time (10 us units), oldest first. They add up to
     * that OVERLAY's turn (its motion is held from here to it). The PC turns the pose sample by sample, so a pointer
     * paints and its Snake follows every gyro sample, as on the phone.
     */
    fun encodeSamples(out: ByteArray, off: Int, phoneTimeUs: Int): Int {
        out.fill(0, off, off + Wire.OVERLAY_SAMPLES_PLAIN)
        out.putU32(off, phoneTimeUs)
        synchronized(lock) {
            val n = sampleCount
            out.putU8(off + 4, n)
            for (k in 0 until n) {
                val p = off + 8 + k * 8
                for (a in 0 until 3) out.putI16(p + a * 2, (samples[k * 4 + a] * 1000f).roundToInt().coerceIn(-32767, 32767))
                out.putU16(p + 6, (samples[k * 4 + 3] * 100_000f).roundToInt().coerceIn(0, 0xFFFF))
            }
            sampleCount = 0
            samples.fill(0f)
            // The turn so far goes with the OVERLAY that follows; what comes meanwhile waits for the next.
            heldTurn[0] += turnYaw; heldTurn[1] += turnPitch; heldTurn[2] += turnRoll; heldTurn[3] += turnSeconds
            turnYaw = 0f; turnPitch = 0f; turnRoll = 0f; turnSeconds = 0f
            held = true
        }
        return Wire.OVERLAY_SAMPLES_PLAIN
    }

    fun encodeFrame(out: ByteArray, off: Int, phoneTimeUs: Int, pressed: Set<String>, latched: Set<String>): Int {
        out.fill(0, off, off + Wire.OVERLAY_PLAIN)
        out.putU32(off, phoneTimeUs)
        synchronized(lock) {
            // After OVERLAY_SAMPLES: this frame's turn is the one held then (what came since goes in the next).
            var keepYaw = 0f; var keepPitch = 0f; var keepRoll = 0f; var keepSeconds = 0f
            if (held) {
                keepYaw = turnYaw; keepPitch = turnPitch; keepRoll = turnRoll; keepSeconds = turnSeconds
                turnYaw = heldTurn[0]; turnPitch = heldTurn[1]; turnRoll = heldTurn[2]; turnSeconds = heldTurn[3]
                heldTurn.fill(0f)
                held = false
            }
            val at = gyroAtNs
            val live = at != Long.MIN_VALUE && System.nanoTime() - at < POSE_STALE_NS
            val totals = totalsWanted
            totalTurn[0] = wrap(totalTurn[0] + turnYaw)
            totalTurn[1] = wrap(totalTurn[1] + turnPitch)
            totalTurn[2] = wrap(totalTurn[2] + turnRoll)
            totalSeconds = (totalSeconds + turnSeconds) % TIME_WRAP
            for (i in 0 until 3) totalCamera[i] = wrap(totalCamera[i] + cameraDeg[i])
            if (totals) {
                for (i in 0 until 3) out.putU32(off + 4 + i * 4, java.lang.Float.floatToRawIntBits(totalTurn[i].toFloat()))
                out.putU16(off + 16, (totalSeconds * 100_000.0).roundToInt() and 0xFFFF)
            } else {
                out.putU32(off + 4, java.lang.Float.floatToRawIntBits(turnYaw))
                out.putU32(off + 8, java.lang.Float.floatToRawIntBits(turnPitch))
                out.putU32(off + 12, java.lang.Float.floatToRawIntBits(turnRoll))
                out.putU16(off + 16, (turnSeconds * 100_000f).roundToInt().coerceIn(0, 0xFFFF))
            }
            turnYaw = keepYaw; turnPitch = keepPitch; turnRoll = keepRoll; turnSeconds = keepSeconds
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
            if (totals) flags = flags or FLAG_TOTALS
            if (realWorld) flags = flags or FLAG_REAL_WORLD
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
                val cam = if (totals) totalCamera[i].toFloat() else cameraDeg[i]
                out.putU32(off + 112 + i * 4, java.lang.Float.floatToRawIntBits(cam))
                out.putU32(off + 124 + i * 4, java.lang.Float.floatToRawIntBits(rwsGain[i]))
            }
            cameraDeg.fill(0f)
            out.putU8(off + 139, levelPresses)
            out.putU8(off + 148, clearPresses)
            val map = cameraMap
            out.putU8(off + 149, map and 0xFF)
            out.putU8(off + 150, map shr 8 and 0xFF)
            if (padAttached) {
                var buttons = padKeys
                if (padAxes[6] < -0.5f) buttons = buttons or PAD_LEFT
                if (padAxes[6] > 0.5f) buttons = buttons or PAD_RIGHT
                if (padAxes[7] < -0.5f) buttons = buttons or PAD_UP
                if (padAxes[7] > 0.5f) buttons = buttons or PAD_DOWN
                val lt = padAxes[4].coerceIn(0f, 1f)
                val rt = padAxes[5].coerceIn(0f, 1f)
                // The analog pull where there is one; a trigger with only a key is all or nothing.
                fun pull(v: Float, bit: Int) = if (v > 0f) (v * 255f).roundToInt() else if (buttons and bit != 0) 255 else 0
                out.putU8(off + 136, PAD_ATTACHED)
                out.putU8(off + 137, pull(lt, PAD_L2))
                out.putU8(off + 138, pull(rt, PAD_R2))
                if (lt >= TRIGGER_PRESSED) buttons = buttons or PAD_L2
                if (rt >= TRIGGER_PRESSED) buttons = buttons or PAD_R2
                out.putU32(off + 140, buttons)
                for (i in 0 until 4) out[off + 144 + i] = (padAxes[i].coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
            }
        }
        return Wire.OVERLAY_PLAIN
    }

    companion object {
        /** OVERLAY_SAMPLES holds at most this many samples (the phone's ~950 Hz over a 60 Hz frame is about 16). */
        const val MAX_SAMPLES = 32

        const val FLAG_POSE = 1
        const val FLAG_PAD_SHOWN = 2
        const val FLAG_GYRO_ACTIVE = 4
        const val FLAG_UP = 8
        const val FLAG_NORTH = 16
        const val FLAG_RATCHET_HELD = 32
        /** The turn, time and camera degrees are running totals (see the class comment). */
        const val FLAG_TOTALS = 64

        /**
         * World or Player gyro space ([realWorld]): the turn, up and north are about the screen's own
         * axes, the PC's poses take no saved grip, and the live gyro's camera turn (in that space) is
         * already in the camera degrees.
         */
        const val FLAG_REAL_WORLD = 128
        const val PAD_ATTACHED = 1

        // Controller buttons in OVERLAY, by place (Switch layout on the PC): the face buttons by side.
        const val PAD_SOUTH = 1
        const val PAD_EAST = 2
        const val PAD_WEST = 4
        const val PAD_NORTH = 8
        const val PAD_L1 = 16
        const val PAD_R1 = 32
        const val PAD_L2 = 64
        const val PAD_R2 = 128
        const val PAD_MINUS = 256
        const val PAD_PLUS = 512
        const val PAD_HOME = 1024
        const val PAD_CAPTURE = 2048
        const val PAD_L3 = 4096
        const val PAD_R3 = 8192
        const val PAD_UP = 16384
        const val PAD_DOWN = 32768
        const val PAD_LEFT = 65536
        const val PAD_RIGHT = 131072
        /** An analog trigger pulled this far lights as pressed. */
        const val TRIGGER_PRESSED = 0.1f

        /** The OVERLAY button of Android key [keyCode], or 0. */
        fun padBit(keyCode: Int): Int = when (keyCode) {
            AndroidKey.BUTTON_A -> PAD_SOUTH
            AndroidKey.BUTTON_B -> PAD_EAST
            AndroidKey.BUTTON_X -> PAD_WEST
            AndroidKey.BUTTON_Y -> PAD_NORTH
            AndroidKey.BUTTON_L1 -> PAD_L1
            AndroidKey.BUTTON_R1 -> PAD_R1
            AndroidKey.BUTTON_L2 -> PAD_L2
            AndroidKey.BUTTON_R2 -> PAD_R2
            AndroidKey.BUTTON_SELECT, AndroidKey.BACK -> PAD_MINUS
            AndroidKey.BUTTON_START -> PAD_PLUS
            AndroidKey.BUTTON_MODE -> PAD_HOME
            AndroidKey.BUTTON_THUMBL -> PAD_L3
            AndroidKey.BUTTON_THUMBR -> PAD_R3
            AndroidKey.DPAD_UP -> PAD_UP
            AndroidKey.DPAD_DOWN -> PAD_DOWN
            AndroidKey.DPAD_LEFT -> PAD_LEFT
            AndroidKey.DPAD_RIGHT -> PAD_RIGHT
            else -> 0
        }
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
        /** The time total wraps here: 65536 units of 10 us. */
        private const val TIME_WRAP = 0.65536
        private const val NAME_MAX = 48
        private const val LABEL_MAX = 32

        /** [d] degrees as the same angle in -180..180. */
        private fun wrap(d: Double): Double {
            val r = d % 360.0
            return if (r >= 180.0) r - 360.0 else if (r < -180.0) r + 360.0 else r
        }

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
