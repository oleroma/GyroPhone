package com.oleroma.gyrophone.core.debug

import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.GamepadButton
import kotlin.math.abs

/**
 * The debug view's event log: what came in (touches, controller keys, layer changes), what went
 * out (game pad buttons and sticks, keys, mouse), and once-a-second link lines, in a ring of the
 * last [capacity] events. Exists only while debugging is on, so nothing here costs anything
 * otherwise. Thread-safe: the engine, the pump and the UI all call it.
 *
 * Times are [clock] nanoseconds (the engine's clock). [save] turns them into wall-clock times.
 */
class DebugTrace(
    val capacity: Int = 16_384,
    private val clock: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val tag: String) {
        /** Something that came in: a touch, a controller key, a gyro switch. */
        IN("in"),
        /** Something the PC is sent: a button, key or mouse button change. */
        OUT("out"),
        /** Continuous output: stick and trigger moves, summed mouse motion and scrolling (gyro included). */
        MOVE("move"),
        /** Mode changes: workspace, layer, gyro on or off, a reset. */
        STATE("state"),
        /** The link: once-a-second stats, connects and drops. */
        LINK("link"),
        /** Anything else worth a line. */
        NOTE("note"),
    }

    class Event(val nanos: Long, val kind: Kind, val text: String)

    private val times = LongArray(capacity)
    private val kinds = arrayOfNulls<Kind>(capacity)
    private val texts = arrayOfNulls<String>(capacity)
    private var head = 0
    private var count = 0

    /** Bumped on every event, so a view only redraws when something happened. */
    @Volatile var version = 0L
        private set

    @Synchronized
    fun add(kind: Kind, text: String, nanos: Long = clock()) {
        times[head] = nanos; kinds[head] = kind; texts[head] = text
        head = (head + 1) % capacity
        if (count < capacity) count++
        version++
    }

    fun input(text: String) = add(Kind.IN, text)
    fun state(text: String) = add(Kind.STATE, text)
    fun link(text: String) = add(Kind.LINK, text)
    fun note(text: String) = add(Kind.NOTE, text)

    /** The newest [max] events, oldest first. */
    @Synchronized
    fun latest(max: Int): List<Event> {
        val n = minOf(max, count)
        return List(n) { i ->
            val at = Math.floorMod(head - n + i, capacity)
            Event(times[at], kinds[at]!!, texts[at]!!)
        }
    }

    /** The newest [max] events of [shown] kinds, oldest first. */
    @Synchronized
    fun latest(max: Int, shown: Set<Kind>): List<Event> {
        val out = ArrayList<Event>(max)
        var i = 0
        while (i < count && out.size < max) {
            val at = Math.floorMod(head - 1 - i, capacity)
            if (kinds[at] in shown) out.add(Event(times[at], kinds[at]!!, texts[at]!!))
            i++
        }
        out.reverse()
        return out
    }

    /** Events of the last [windowNanos], oldest first. */
    @Synchronized
    fun since(windowNanos: Long): List<Event> {
        val from = clock() - windowNanos
        val out = ArrayList<Event>()
        for (i in 0 until count) {
            val at = Math.floorMod(head - count + i, capacity)
            if (times[at] >= from) out.add(Event(times[at], kinds[at]!!, texts[at]!!))
        }
        return out
    }

    @Synchronized
    fun clear() {
        head = 0; count = 0; kinds.fill(null); texts.fill(null); version++
        lastOut = null; outDx = 0; outDy = 0; outGyroDx = 0; outGyroDy = 0; outWheel = 0; outHWheel = 0
        outSnapshots = 0
    }

    // ---- output -----------------------------------------------------------------------------

    private var lastOut: HidState? = null
    private val lastAxisAt = LongArray(6)
    private val shownAxis = IntArray(6)
    private var outDx = 0
    private var outDy = 0
    private var outGyroDx = 0
    private var outGyroDy = 0
    private var outWheel = 0
    private var outHWheel = 0
    private var motionSince = 0L
    private var outSnapshots = 0

    /** The output now, for the overlay: the last snapshot the pump took. */
    @Volatile var current: HidState = HidState()
        private set

    /** Snapshots per second, as the pump takes them (set once a second by [secondTick]). */
    @Volatile var snapshotsPerSecond = 0
        private set

    /**
     * One engine snapshot as a pump sends it: logs button, hat, key and mouse button changes at
     * once, stick and trigger moves at most every [AXIS_EVERY_NANOS] each, and mouse motion summed
     * over [MOTION_EVERY_NANOS]. [gyroDx], [gyroDy]: the part of the motion the gyro made.
     */
    @Synchronized
    fun output(s: HidState, gyroDx: Int = 0, gyroDy: Int = 0, nanos: Long = clock()) {
        current = s
        outSnapshots++
        val old = lastOut ?: HidState()
        lastOut = s
        val p = s.gamepad
        val q = old.gamepad
        val changed = p.buttons xor q.buttons
        if (changed != 0) for (b in GamepadButton.entries) {
            if (b.bit < 0 || changed and (1 shl b.bit) == 0) continue
            add(Kind.OUT, "pad ${b.label} " + if (p.buttons and (1 shl b.bit) != 0) "down" else "up", nanos)
        }
        if (p.hat != q.hat) add(Kind.OUT, "pad d-pad ${hatName(p.hat)}", nanos)
        val axes = intArrayOf(p.lx, p.ly, p.rx, p.ry, p.lt, p.rt)
        for (i in axes.indices) {
            val max = if (i < 4) STICK_MAX else TRIGGER_MAX
            val pct = axes[i] * 100 / max
            if (pct == shownAxis[i]) continue
            val big = abs(pct - shownAxis[i]) >= AXIS_STEP_PCT || pct == 0 || abs(pct) == 100
            if (!big || (nanos - lastAxisAt[i] < AXIS_EVERY_NANOS && pct != 0)) continue
            shownAxis[i] = pct; lastAxisAt[i] = nanos
            add(Kind.MOVE, "pad ${AXIS_NAMES[i]} $pct%", nanos)
        }
        val keys = s.allKeys.toSet() + modifierUsages(s.keyboard.modifiers)
        val oldKeys = old.allKeys.toSet() + modifierUsages(old.keyboard.modifiers)
        for (k in oldKeys) if (k !in keys) add(Kind.OUT, "key ${keyName(k)} up", nanos)
        for (k in keys) if (k !in oldKeys) add(Kind.OUT, "key ${keyName(k)} down", nanos)
        val mb = s.mouse.buttons xor old.mouse.buttons
        for (i in MOUSE_BUTTONS.indices) if (mb and (1 shl i) != 0) {
            add(Kind.OUT, "mouse ${MOUSE_BUTTONS[i]} " + if (s.mouse.buttons and (1 shl i) != 0) "down" else "up", nanos)
        }
        if (s.mouse.hasMotion) {
            if (outDx == 0 && outDy == 0 && outWheel == 0 && outHWheel == 0) motionSince = nanos
            outDx += s.mouse.dx; outDy += s.mouse.dy; outWheel += s.mouse.wheel; outHWheel += s.mouse.hwheel
            outGyroDx += gyroDx; outGyroDy += gyroDy
        }
        if ((outDx != 0 || outDy != 0 || outWheel != 0 || outHWheel != 0) && nanos - motionSince >= MOTION_EVERY_NANOS) {
            flushMotion(nanos)
        }
    }

    private fun flushMotion(nanos: Long) {
        val ms = (nanos - motionSince) / 1_000_000
        val parts = ArrayList<String>(3)
        if (outDx != 0 || outDy != 0) {
            parts.add("move $outDx,$outDy" + if (outGyroDx != 0 || outGyroDy != 0) " (gyro $outGyroDx,$outGyroDy)" else "")
        }
        if (outWheel != 0) parts.add("wheel ${fmt1(outWheel / 120f)}")
        if (outHWheel != 0) parts.add("pan ${fmt1(outHWheel / 120f)}")
        add(Kind.MOVE, "mouse " + parts.joinToString(", ") + " in $ms ms", nanos)
        outDx = 0; outDy = 0; outWheel = 0; outHWheel = 0; outGyroDx = 0; outGyroDy = 0
    }

    // ---- gyro -------------------------------------------------------------------------------

    /** Latest raw gyro sample, deg/s, for the overlay. */
    @Volatile var gyroRaw = FloatArray(3)
        private set
    private var gyroSamples = 0

    /** Gyro samples per second (set once a second by [secondTick]). */
    @Volatile var gyroPerSecond = 0
        private set

    /** One raw gyro sample in deg/s. Only counted and kept for the overlay; not logged. */
    fun gyro(x: Float, y: Float, z: Float) {
        gyroRaw = floatArrayOf(x, y, z)
        synchronized(this) { gyroSamples++ }
    }

    /** Once a second: updates the rates and logs [linkLine] when given. */
    @Synchronized
    fun secondTick(linkLine: String?) {
        snapshotsPerSecond = outSnapshots; outSnapshots = 0
        gyroPerSecond = gyroSamples; gyroSamples = 0
        if (outDx != 0 || outDy != 0 || outWheel != 0 || outHWheel != 0) flushMotion(clock())
        if (linkLine != null) add(Kind.LINK, linkLine + " · snapshots $snapshotsPerSecond/s · gyro $gyroPerSecond/s")
    }

    // ---- saving -----------------------------------------------------------------------------

    /**
     * The last [windowNanos] as text: `#` header lines ([header] plus the clock pair), then one line
     * per event, `<wall ms>\t<side>\t<kind>\t<text>`, so recordings of both sides sort together.
     */
    fun save(side: String, header: List<String>, windowNanos: Long = RECORD_NANOS): String {
        val events = since(windowNanos)
        val nowNanos = clock()
        val nowMs = wallMillis()
        val sb = StringBuilder()
        sb.append("# GyroPhone debug recording, side ").append(side).append('\n')
        sb.append("# saved at wall ms ").append(nowMs).append('\n')
        for (h in header) sb.append("# ").append(h.replace('\n', ' ')).append('\n')
        for (e in events) {
            val ms = nowMs - (nowNanos - e.nanos) / 1_000_000
            sb.append(ms).append('\t').append(side).append('\t').append(e.kind.tag).append('\t').append(e.text).append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val RECORD_NANOS = 30_000_000_000L
        const val AXIS_EVERY_NANOS = 100_000_000L
        const val MOTION_EVERY_NANOS = 100_000_000L
        const val AXIS_STEP_PCT = 5
        private const val STICK_MAX = 32767
        private const val TRIGGER_MAX = 1023
        private val AXIS_NAMES = arrayOf("LX", "LY", "RX", "RY", "LT", "RT")
        private val MOUSE_BUTTONS = arrayOf("left", "right", "middle", "back", "forward")

        fun keyName(usage: Int): String = HidUsage.names[usage] ?: "0x%02X".format(usage)

        fun hatName(hat: Int): String = when (hat) {
            Hat.UP -> "up"; Hat.UP_RIGHT -> "up-right"; Hat.RIGHT -> "right"; Hat.DOWN_RIGHT -> "down-right"
            Hat.DOWN -> "down"; Hat.DOWN_LEFT -> "down-left"; Hat.LEFT -> "left"; Hat.UP_LEFT -> "up-left"
            else -> "centre"
        }

        private fun modifierUsages(mods: Int): List<Int> =
            (0 until 8).filter { mods and (1 shl it) != 0 }.map { HidUsage.LEFT_CTRL + it }

        fun fmt1(v: Float): String = ((v * 10).toInt() / 10f).toString()
    }
}
