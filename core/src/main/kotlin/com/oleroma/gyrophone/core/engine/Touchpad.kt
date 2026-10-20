package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.TouchpadSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Laptop-style touchpad gestures from raw pointer events (px, engine-clock nanoseconds):
 * one finger moves the cursor, tap = left click, double tap = double click, tap then touch
 * and move = drag with the left button held, two-finger tap = right click (three = middle),
 * two-finger drag = wheel. Not thread-safe; [MappingEngine] calls it under its lock.
 *
 * Button changes go through a small queue and are applied by [poll], one per call, only after
 * the visible state has been returned by at least one poll and held for [minPulseNanos]. A
 * report pump that samples state at its own rate therefore never misses a click edge.
 */
class Touchpad(settings: TouchpadSettings = TouchpadSettings()) {
    var settings: TouchpadSettings = settings
    /** Display density (px per dp); thresholds and sensitivity are in dp. */
    var pxPerDp: Float = 1f
    var minPulseNanos: Long = DEFAULT_MIN_PULSE_NS

    /** Relative output accumulated since the last [drainMotion], in mouse counts / wheel notches. */
    var dx = 0; private set
    var dy = 0; private set
    var wheel = 0; private set

    private enum class State { IDLE, TOUCH, MOVE, TAP_WAIT, TAP_TOUCH, DRAG, MULTI, SCROLL, IGNORE }

    // Finger slots, fixed size so pointer events allocate nothing.
    private val ids = LongArray(MAX_FINGERS)
    private val used = BooleanArray(MAX_FINGERS)
    private val downX = FloatArray(MAX_FINGERS)
    private val downY = FloatArray(MAX_FINGERS)
    private val lastX = FloatArray(MAX_FINGERS)
    private val lastY = FloatArray(MAX_FINGERS)
    private var count = 0

    private var state = State.IDLE
    private var primary = 0
    /** Down time of the gesture's first finger. */
    private var t0 = 0L
    private var tapDeadline = 0L
    private var maxFingers = 0
    /** A multi-finger touch that may still end as a right/middle click. */
    private var tapCandidate = false

    private var remX = 0f
    private var remY = 0f
    /** Scroll travel not yet turned into a notch, in dp (scaled by scrollSpeed, signed). */
    private var wheelRem = 0f
    /** Finger speed EMA in dp/ms, for acceleration. */
    private var speed = 0f
    private var lastMoveNs = 0L

    // Pulse queue: button masks waiting to become visible, oldest first.
    private val queue = IntArray(QUEUE_CAP)
    private var qHead = 0
    private var qSize = 0
    private var visible = 0
    private var visibleSince = Long.MIN_VALUE / 4
    private var seen = true

    val fingers: Int get() = count

    fun down(pointer: Long, x: Float, y: Float, now: Long) {
        advance(now)
        val known = slotOf(pointer)
        if (known >= 0) { move(pointer, x, y, now); return }
        if (!settings.enabled) return
        val slot = freeSlot()
        if (slot < 0) return
        ids[slot] = pointer; used[slot] = true
        downX[slot] = x; downY[slot] = y; lastX[slot] = x; lastY[slot] = y
        count++
        when (state) {
            State.IDLE -> { state = State.TOUCH; primary = slot; t0 = now }
            State.TOUCH -> { state = State.MULTI; tapCandidate = true; maxFingers = 2 }
            State.MOVE -> {
                // A finger added mid-move only scrolls; measure from here, so the cursor travel
                // so far does not count as scroll travel.
                rebase()
                state = State.MULTI; tapCandidate = false; maxFingers = 2
            }
            State.TAP_WAIT -> { state = State.TAP_TOUCH; primary = slot; t0 = now }
            State.TAP_TOUCH -> {
                enqueue(0) // finish the click
                state = State.MULTI; tapCandidate = false; maxFingers = 2
            }
            State.MULTI -> maxFingers = max(maxFingers, count)
            State.DRAG, State.SCROLL, State.IGNORE -> Unit
        }
    }

    fun move(pointer: Long, x: Float, y: Float, now: Long) {
        advance(now)
        val slot = slotOf(pointer)
        if (slot < 0) return
        val oldX = lastX[slot]; val oldY = lastY[slot]
        lastX[slot] = x; lastY[slot] = y
        when (state) {
            State.TOUCH -> if (slot == primary && (pastSlop(slot) || now - t0 > TAP_MAX_NS)) {
                // Buffered in-tap motion is emitted once, so no distance is lost.
                state = State.MOVE
                startRun()
                emit(x - downX[slot], y - downY[slot], now)
            }
            State.MOVE, State.DRAG -> if (slot == primary) emit(x - oldX, y - oldY, now)
            State.TAP_TOUCH -> if (slot == primary && pastSlop(slot)) {
                state = State.DRAG
                startRun()
                emit(x - downX[slot], y - downY[slot], now)
            }
            State.MULTI -> {
                if (pastSlop(slot)) tapCandidate = false
                if (!tapCandidate) {
                    if (settings.twoFingerScroll && count >= 2) {
                        state = State.SCROLL
                        wheelRem = 0f
                        // Travel inside the slop counts too, so short scrolls are not lost.
                        for (i in 0 until MAX_FINGERS) if (used[i]) emitScroll(lastY[i] - downY[i])
                    } else if (count == 1) {
                        state = State.MOVE; primary = slot
                        startRun()
                    }
                }
            }
            State.SCROLL -> emitScroll(y - oldY)
            State.IDLE, State.TAP_WAIT, State.IGNORE -> Unit
        }
    }

    fun up(pointer: Long, now: Long) {
        advance(now)
        val slot = slotOf(pointer)
        if (slot < 0) return
        used[slot] = false
        count--
        when (state) {
            State.TOUCH -> if (now - t0 <= TAP_MAX_NS && settings.tapToClick) tapped(now) else state = State.IDLE
            State.MOVE -> if (slot == primary) state = if (count == 0) State.IDLE else State.IGNORE
            State.TAP_TOUCH -> if (slot == primary) {
                if (now - t0 <= TAP_MAX_NS) { enqueue(0); enqueue(LEFT); enqueue(0) } else enqueue(0)
                state = State.IDLE
            }
            State.DRAG -> if (slot == primary) {
                enqueue(0)
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.MULTI -> when {
                count == 0 -> {
                    if (tapCandidate && settings.tapToClick && now - t0 <= TAP_MAX_NS) {
                        enqueue(if (maxFingers >= 3) MIDDLE else RIGHT)
                        enqueue(0)
                    }
                    state = State.IDLE
                }
                count == 1 && !tapCandidate -> { state = State.MOVE; primary = firstUsed(); startRun() }
                else -> Unit // still waiting for the last finger or a move
            }
            State.SCROLL -> when (count) {
                0 -> state = State.IDLE
                1 -> { state = State.MOVE; primary = firstUsed(); startRun() }
                else -> Unit
            }
            State.IGNORE -> if (count == 0) state = State.IDLE
            State.IDLE, State.TAP_WAIT -> Unit
        }
    }

    /** Ends the gesture (rotation, profile change, leaving the pad). A visible press still gets a visible release. */
    fun cancel(now: Long) {
        advance(now)
        clearGesture()
        if (visible != 0) enqueue(0)
    }

    /** Hard reset: everything released and cleared immediately (host disconnect, transport switch). */
    fun reset() {
        clearGesture()
        visible = 0
        visibleSince = Long.MIN_VALUE / 4
        seen = true
        drainMotion()
    }

    /** Advances timers, applies at most one queued button change, returns the buttons to report now. */
    fun poll(now: Long): Int {
        advance(now)
        if (qSize > 0 && seen && now - visibleSince >= minPulseNanos) {
            visible = queue[qHead]
            qHead = (qHead + 1) % QUEUE_CAP
            qSize--
            visibleSince = now
            seen = false
        }
        seen = true
        return visible
    }

    fun drainMotion() { dx = 0; dy = 0; wheel = 0 }

    /** Earliest time [poll] may change something without new input, or Long.MAX_VALUE. */
    fun nextDeadline(): Long {
        var d = Long.MAX_VALUE
        if (qSize > 0) d = visibleSince + minPulseNanos
        if (state == State.TAP_WAIT) d = min(d, tapDeadline)
        return d
    }

    private fun advance(now: Long) {
        when (state) {
            State.TAP_WAIT -> if (now >= tapDeadline) { enqueue(0); state = State.IDLE }
            // Tap then hold without moving: drag, the button is already held.
            State.TAP_TOUCH -> if (now - t0 > TAP_MAX_NS) { state = State.DRAG; startRun() }
            State.MULTI -> if (tapCandidate && now - t0 > TAP_MAX_NS) tapCandidate = false
            else -> Unit
        }
    }

    /** Forgets fingers, gesture and queued (not yet visible) button changes. */
    private fun clearGesture() {
        for (i in 0 until MAX_FINGERS) used[i] = false
        count = 0
        state = State.IDLE
        tapCandidate = false
        remX = 0f; remY = 0f; wheelRem = 0f
        qSize = 0
    }

    private fun tapped(now: Long) {
        if (settings.tapDrag) {
            enqueue(LEFT)
            state = State.TAP_WAIT
            tapDeadline = now + TAP_DRAG_WINDOW_NS
        } else {
            enqueue(LEFT); enqueue(0)
            state = State.IDLE
        }
    }

    private fun enqueue(mask: Int) {
        val last = if (qSize > 0) queue[(qHead + qSize - 1) % QUEUE_CAP] else visible
        if (mask == last) return
        if (qSize == QUEUE_CAP) queue[(qHead + qSize - 1) % QUEUE_CAP] = mask
        else { queue[(qHead + qSize) % QUEUE_CAP] = mask; qSize++ }
    }

    /** Makes every finger's current position its down point. */
    private fun rebase() {
        for (i in 0 until MAX_FINGERS) if (used[i]) { downX[i] = lastX[i]; downY[i] = lastY[i] }
    }

    private fun startRun() {
        speed = 0f; lastMoveNs = t0
        remX = 0f; remY = 0f
    }

    private fun emit(dxPx: Float, dyPx: Float, now: Long) {
        val ddx = dxPx / pxPerDp
        val ddy = dyPx / pxPerDp
        val dt = (now - lastMoveNs).coerceIn(1_000_000L, 50_000_000L)
        lastMoveNs = now
        val inst = sqrt(ddx * ddx + ddy * ddy) / (dt / 1e6f)
        speed = 0.5f * speed + 0.5f * inst
        val s = settings
        val gain = 1f + s.acceleration *
            ((speed - ACCEL_LOW_DP_PER_MS) / (ACCEL_HIGH_DP_PER_MS - ACCEL_LOW_DP_PER_MS)).coerceIn(0f, 1f)
        val fx = ddx * s.sensitivity * gain + remX
        val ix = fx.toInt()
        remX = fx - ix
        dx += ix
        val fy = ddy * s.sensitivity * gain + remY
        val iy = fy.toInt()
        remY = fy - iy
        dy += iy
    }

    /** One finger's vertical move while scrolling; the wheel follows the fingers' centroid. */
    private fun emitScroll(fingerDyPx: Float) {
        if (count <= 0) return
        val dp = fingerDyPx / count / pxPerDp * settings.scrollSpeed
        // Natural: finger down = wheel positive (scroll up).
        wheelRem += if (settings.naturalScroll) dp else -dp
        val n = (wheelRem / SCROLL_DP_PER_NOTCH).toInt()
        wheelRem -= n * SCROLL_DP_PER_NOTCH
        wheel += n
    }

    private fun pastSlop(slot: Int): Boolean {
        val ddx = lastX[slot] - downX[slot]
        val ddy = lastY[slot] - downY[slot]
        val slop = TAP_SLOP_DP * pxPerDp
        return ddx * ddx + ddy * ddy > slop * slop
    }

    private fun slotOf(pointer: Long): Int {
        for (i in 0 until MAX_FINGERS) if (used[i] && ids[i] == pointer) return i
        return -1
    }

    private fun freeSlot(): Int {
        for (i in 0 until MAX_FINGERS) if (!used[i]) return i
        return -1
    }

    private fun firstUsed(): Int {
        for (i in 0 until MAX_FINGERS) if (used[i]) return i
        return 0
    }

    companion object {
        const val TAP_MAX_NS = 180_000_000L          // touch shorter than this (and inside slop) is a tap
        const val TAP_DRAG_WINDOW_NS = 220_000_000L  // after a tap: second touch within this = drag / double click
        const val TAP_SLOP_DP = 10f                  // movement allowed inside a tap, per finger, from its down point
        const val SCROLL_DP_PER_NOTCH = 24f          // at scrollSpeed 1
        const val ACCEL_LOW_DP_PER_MS = 0.25f        // below: gain 1
        const val ACCEL_HIGH_DP_PER_MS = 1.5f        // above: gain 1 + acceleration
        const val DEFAULT_MIN_PULSE_NS = 40_000_000L
        const val MAX_FINGERS = 4
        private const val QUEUE_CAP = 8

        private val LEFT = 1 shl MouseButton.LEFT.bit
        private val RIGHT = 1 shl MouseButton.RIGHT.bit
        private val MIDDLE = 1 shl MouseButton.MIDDLE.bit

        /** Shortest press/release that a pump ticking every [intervalNanos] cannot miss. */
        fun minPulseFor(intervalNanos: Long): Long = max(DEFAULT_MIN_PULSE_NS, intervalNanos + 12_000_000L)
    }
}
