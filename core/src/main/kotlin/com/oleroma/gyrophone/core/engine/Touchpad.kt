package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GestureAction
import com.oleroma.gyrophone.core.model.GestureActions
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.TouchpadGesture
import com.oleroma.gyrophone.core.model.TouchpadSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Laptop-style touchpad gestures from raw pointer events (px; engine-clock nanoseconds for the
 * gesture timers, the events' own times for speeds): one finger moves the cursor, tap = left
 * click, double tap = double click, tap then touch and move = drag with the left button held,
 * two-finger tap = right click, two-finger drag = high-resolution scroll on both axes with
 * inertia after a fling, two-finger pinch = Ctrl + wheel zoom, three/four-finger swipes and taps
 * = the profile's [TouchpadSettings.gestures3] / [TouchpadSettings.gestures4] (Windows shortcuts
 * by default). Not thread-safe; [MappingEngine] calls it under its lock.
 *
 * Wheel output is in 1/[WHEEL_UNIT] notch units (Windows WHEEL_DELTA fractions), so the transport
 * can send smooth scrolling where the host supports it and whole notches elsewhere.
 *
 * Button and key changes go through small queues and are applied by [poll], one per queue per
 * call, only after the visible state has been returned by at least one poll and held for
 * [minPulseNanos]. A report pump that samples state at its own rate therefore never misses a
 * click or key edge.
 */
class Touchpad(settings: TouchpadSettings = TouchpadSettings()) {
    var settings: TouchpadSettings = settings
    /** Display density (px per dp); thresholds and sensitivity are in dp. */
    var pxPerDp: Float = 1f
    var minPulseNanos: Long = DEFAULT_MIN_PULSE_NS

    /** Relative output accumulated since the last [drainMotion]: mouse counts, wheel in 1/120 notch. */
    var dx = 0; private set
    var dy = 0; private set
    var wheel = 0; private set
    var hwheel = 0; private set

    /** Keyboard state the gestures hold right now (as of the last [poll]): HID modifier bits and one usage (0 = none). */
    var keyModifiers = 0; private set
    var key = 0; private set

    private enum class State {
        IDLE, TOUCH, MOVE, TAP_WAIT, TAP_TOUCH, DRAG, MULTI, SCROLL, SCROLL_LIFT, PINCH, SWIPE, SCRUB, IGNORE,
    }

    // Finger slots, fixed size so pointer events allocate nothing.
    private val ids = LongArray(MAX_FINGERS)
    private val used = BooleanArray(MAX_FINGERS)
    private val downX = FloatArray(MAX_FINGERS)
    private val downY = FloatArray(MAX_FINGERS)
    private val lastX = FloatArray(MAX_FINGERS)
    private val lastY = FloatArray(MAX_FINGERS)
    /** Event times of each finger's down point (moved by [rebase]) and of its last position. */
    private val baseEventNs = LongArray(MAX_FINGERS)
    private val lastEventNs = LongArray(MAX_FINGERS)
    private var count = 0

    private var state = State.IDLE
    private var primary = 0
    /** Down time of the gesture's first finger. */
    private var t0 = 0L
    /** Down time of the newest finger (multi-finger taps are timed from it). */
    private var lastDownNs = 0L
    private var tapDeadline = 0L
    private var maxFingers = 0
    /** A multi-finger touch that has not moved or changed into something else (see [multiTapOpen]). */
    private var tapCandidate = false
    /** Slots that reported a move in the current touch frame (MULTI). */
    private var movedMask = 0

    /** One cursor-moving finger: its speed for acceleration and its sub-count remainders. */
    private class Run {
        /** Finger speed EMA in dp/ms; [fresh] until the run's first step seeds it. */
        var speed = 0f
        var fresh = true
        /** Event time of the last step. */
        var lastNs = 0L
        var remX = 0f
        var remY = 0f

        fun start(atNs: Long) { speed = 0f; fresh = true; lastNs = atNs; remX = 0f; remY = 0f }
    }

    /** The background cursor finger's run (MOVE, DRAG). */
    private val run = Run()

    // Scrolling, in wheel units (1/120 notch); remainders keep sub-unit travel.
    private var wheelRem = 0f
    private var hwheelRem = 0f
    /** Scroll velocity EMA in wheel units per ms, and the travel / time it is being sampled over. */
    private var velX = 0f
    private var velY = 0f
    private var velAccX = 0f
    private var velAccY = 0f
    private var velSinceNs = 0L
    private var lastScrollNs = 0L
    /** When the first of two scrolling fingers lifted (SCROLL_LIFT). */
    private var liftNs = 0L

    // Inertia after a fling.
    private var gliding = false
    private var glideVx = 0f
    private var glideVy = 0f
    private var glideNs = 0L

    // Pinch: zoom travel not yet released into [wheel] (waits until Ctrl is visible).
    private var lastSpread = 0f
    private var zoomRem = 0f
    private var zoomPending = 0
    private var lastZoomNs = Long.MIN_VALUE / 4

    // Swipes: travel origin of the fingers' centroid (px), and the scrub base.
    private var originX = 0f
    private var originY = 0f
    private var scrubBase = 0

    // Button pulse queue: masks waiting to become visible, oldest first.
    private val queue = IntArray(QUEUE_CAP)
    private var qHead = 0
    private var qSize = 0
    private var visible = 0
    private var visibleSince = Long.MIN_VALUE / 4
    private var seen = true

    // Key queue: packed (modifiers shl 8 | usage) states, same rules as the button queue.
    private val kQueue = IntArray(KEY_QUEUE_CAP)
    private var kHead = 0
    private var kSize = 0
    private var kVisible = 0
    private var kVisibleSince = Long.MIN_VALUE / 4
    private var kSeen = true

    // Aiming fingers (a touch button's finger driving the cursor) live outside the gesture state
    // machine: each moves the cursor on its own, so they never tap, scroll, pinch or swipe, and
    // background fingers keep their gestures while one is down.
    private val aimIds = LongArray(MAX_FINGERS)
    private val aimUsed = BooleanArray(MAX_FINGERS)
    private val aimX = FloatArray(MAX_FINGERS)
    private val aimY = FloatArray(MAX_FINGERS)
    private val aimRun = Array(MAX_FINGERS) { Run() }
    private var aimCount = 0

    /** Fingers down, aiming ones included. */
    val fingers: Int get() = count + aimCount

    /**
     * A finger landed. An [aiming] finger (a touch button's finger driving the cursor) only
     * moves the cursor, from its first motion: it never taps, drags or joins a multi-finger gesture.
     * [eventNs] is the touch's own time (same clock as [now]), used only for speeds; [now] runs the timers.
     */
    fun down(pointer: Long, x: Float, y: Float, now: Long, aiming: Boolean = false, eventNs: Long = now) {
        advance(now)
        gliding = false // any touch stops a glide at once
        if (aiming) { aimDown(pointer, x, y, now, eventNs); return }
        movedMask = 0
        val known = slotOf(pointer)
        if (known >= 0 || aimSlotOf(pointer) >= 0) { move(pointer, x, y, now, eventNs); return }
        if (!settings.enabled) return
        val slot = freeSlot()
        if (slot < 0) return
        ids[slot] = pointer; used[slot] = true
        downX[slot] = x; downY[slot] = y; lastX[slot] = x; lastY[slot] = y
        baseEventNs[slot] = eventNs; lastEventNs[slot] = eventNs
        count++
        when (state) {
            State.IDLE -> { state = State.TOUCH; primary = slot; t0 = now }
            State.TOUCH -> { state = State.MULTI; tapCandidate = true; maxFingers = 2; lastDownNs = now }
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
                rebase()
            }
            State.MULTI, State.SWIPE -> {
                // Fingers rarely land together: measure travel from the newest arrangement.
                maxFingers = max(maxFingers, count)
                lastDownNs = now
                rebase()
            }
            // Two fingers often start moving before the third lands: that is a swipe, not a scroll or zoom.
            State.SCROLL, State.PINCH -> if (count >= 3 && settings.gestures) startSwipe()
            State.SCROLL_LIFT -> state = State.SCROLL
            State.DRAG, State.SCRUB, State.IGNORE -> Unit
        }
    }

    /** A finger moved; [eventNs] as in [down]. */
    fun move(pointer: Long, x: Float, y: Float, now: Long, eventNs: Long = now) {
        advance(now)
        val a = aimSlotOf(pointer)
        if (a >= 0) {
            val dxPx = x - aimX[a]; val dyPx = y - aimY[a]
            aimX[a] = x; aimY[a] = y
            emit(aimRun[a], dxPx, dyPx, eventNs)
            return
        }
        val slot = slotOf(pointer)
        if (slot < 0) return
        val oldX = lastX[slot]; val oldY = lastY[slot]
        lastX[slot] = x; lastY[slot] = y
        lastEventNs[slot] = eventNs
        when (state) {
            // With tap-to-click off there is no tap to wait for: the cursor moves from the first motion.
            State.TOUCH -> if (slot == primary && (!settings.tapToClick || pastSlop(slot) || now - t0 > TAP_MAX_NS)) {
                state = State.MOVE
                startRun(slot)
            }
            State.MOVE, State.DRAG -> if (slot == primary) emit(run, x - oldX, y - oldY, eventNs)
            State.TAP_TOUCH -> if (slot == primary && pastSlop(slot)) {
                state = State.DRAG
                startRun(slot)
            }
            State.MULTI -> {
                if (pastSlop(slot)) tapCandidate = false
                // One touch frame moves the fingers one after another: decide once all moving
                // fingers have reported (or one reports twice), so the first finger of a two-finger
                // scroll does not look like a pinch.
                val bit = 1 shl slot
                val again = movedMask and bit != 0
                movedMask = if (again) bit else movedMask or bit
                if (again || movedMask == usedMask()) {
                    if (!again) movedMask = 0
                    decideMulti(now, eventNs)
                }
            }
            // After the first of two scrolling fingers lifts, the other keeps scrolling until it lifts too.
            State.SCROLL, State.SCROLL_LIFT -> emitScroll(x - oldX, y - oldY, now, eventNs)
            State.PINCH -> if (count >= 2) {
                val s = spread()
                zoom(s - lastSpread)
                lastSpread = s
            }
            State.SWIPE -> swipe()
            State.SCRUB -> scrub()
            State.IDLE, State.TAP_WAIT, State.IGNORE -> Unit
        }
    }

    fun up(pointer: Long, now: Long) {
        advance(now)
        val a = aimSlotOf(pointer)
        if (a >= 0) { aimUsed[a] = false; aimCount--; return }
        val slot = slotOf(pointer)
        if (slot < 0) return
        used[slot] = false
        count--
        movedMask = 0
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
                    if (settings.tapToClick && multiTapOpen(now)) multiTap()
                    state = State.IDLE
                }
                // Travel that stayed under the scroll and pinch thresholds is not cursor motion.
                count == 1 && !multiTapOpen(now) -> { rebase(); state = State.MOVE; primary = firstUsed(); startRun(primary) }
                else -> rebase() // still waiting for the last finger or a move
            }
            State.SCROLL -> when (count) {
                0 -> { fling(now); state = State.IDLE }
                // Two fingers seldom lift together: wait a moment for the second before the cursor takes over.
                1 -> { state = State.SCROLL_LIFT; liftNs = now }
                else -> Unit
            }
            // Glides from this lift. A remaining finger that moved after the first lift is judged here
            // (it may have stopped since); one that stayed still is judged from the first lift.
            State.SCROLL_LIFT -> if (count == 0) { fling(if (lastScrollNs > liftNs) now else liftNs, now); state = State.IDLE }
            State.PINCH -> if (count < 2) {
                endKeys()
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.SWIPE -> if (count == 0) state = State.IDLE else rebase()
            State.SCRUB -> {
                // Lifting ends the switch (Alt released, the picked window comes to the front).
                endKeys()
                state = if (count == 0) State.IDLE else State.IGNORE
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
        if (kVisible != 0) enqueueKey(0)
    }

    /**
     * Removes one finger the system cancelled (an unintended touch): it never clicks, taps or flings,
     * and keys its gesture holds are released. A finger left behind moves the cursor from where it is.
     */
    fun cancelFinger(pointer: Long, now: Long) {
        advance(now)
        val a = aimSlotOf(pointer)
        if (a >= 0) { aimUsed[a] = false; aimCount--; return }
        val slot = slotOf(pointer)
        if (slot < 0) return
        used[slot] = false
        count--
        movedMask = 0
        when (state) {
            State.TOUCH, State.MOVE -> if (count == 0) state = State.IDLE
            State.TAP_TOUCH, State.DRAG -> if (slot == primary) {
                enqueue(0) // only the first tap's click, or the drag's button, is released
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.MULTI, State.SCROLL, State.SCROLL_LIFT, State.PINCH, State.SWIPE, State.SCRUB -> {
                if (state == State.PINCH || state == State.SCRUB) dropKeys()
                tapCandidate = false
                rebase()
                when (count) {
                    0 -> state = State.IDLE
                    1 -> { state = State.MOVE; primary = firstUsed(); startRun(primary) }
                    else -> state = State.MULTI // the fingers left decide again
                }
            }
            State.IGNORE -> if (count == 0) state = State.IDLE
            State.IDLE, State.TAP_WAIT -> Unit
        }
    }

    /** Hard reset: everything released and cleared immediately (host disconnect, transport switch). */
    fun reset() {
        clearGesture()
        visible = 0
        visibleSince = Long.MIN_VALUE / 4
        seen = true
        kVisible = 0
        kVisibleSince = Long.MIN_VALUE / 4
        kSeen = true
        keyModifiers = 0; key = 0
        lastZoomNs = Long.MIN_VALUE / 4
        drainMotion()
    }

    /**
     * Advances timers and the glide, applies at most one queued button change and one key change,
     * and returns the buttons to report now ([keyModifiers] / [key] hold the keys).
     */
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

        // Zoom wheel goes out only while Ctrl is visible, and a key change waits a pulse after the
        // last of it, so no zoom step reaches the PC as a plain scroll.
        if (zoomPending != 0 && (kVisible shr 8) and GestureActions.CTRL != 0) {
            wheel += zoomPending
            zoomPending = 0
            lastZoomNs = now
        }
        if (kSize > 0 && kSeen && now - kVisibleSince >= minPulseNanos && now - lastZoomNs >= minPulseNanos) {
            kVisible = kQueue[kHead]
            kHead = (kHead + 1) % KEY_QUEUE_CAP
            kSize--
            kVisibleSince = now
            kSeen = false
        }
        kSeen = true
        keyModifiers = (kVisible shr 8) and 0xFF
        key = kVisible and 0xFF
        return visible
    }

    fun drainMotion() { dx = 0; dy = 0; wheel = 0; hwheel = 0 }

    /** Earliest time [poll] may change something without new input, or Long.MAX_VALUE. */
    fun nextDeadline(): Long {
        var d = Long.MAX_VALUE
        if (qSize > 0) d = visibleSince + minPulseNanos
        if (kSize > 0) d = min(d, max(kVisibleSince, lastZoomNs) + minPulseNanos)
        if (gliding) d = min(d, glideNs + GLIDE_STEP_NS)
        when (state) {
            State.TAP_WAIT -> d = min(d, tapDeadline)
            State.SCROLL_LIFT -> d = min(d, liftNs + LIFT_WINDOW_NS)
            else -> Unit
        }
        return d
    }

    private fun advance(now: Long) {
        if (gliding) glide(now)
        when (state) {
            State.TAP_WAIT -> if (now >= tapDeadline) { enqueue(0); state = State.IDLE }
            // Tap then hold without moving: drag, the button is already held.
            State.TAP_TOUCH -> if (now - t0 > TAP_MAX_NS) { state = State.DRAG; startRun(primary) }
            State.MULTI -> if (tapCandidate && !multiTapOpen(now)) tapCandidate = false
            State.SCROLL_LIFT -> if (now - liftNs > LIFT_WINDOW_NS) {
                // The finger stayed: it moves the cursor from where it is now.
                rebase()
                state = State.MOVE; primary = firstUsed()
                startRun(primary)
            }
            else -> Unit
        }
    }

    /**
     * Fingers of a multi-finger tap land and lift tens of ms apart, so the tap is timed from the
     * newest finger, within an overall limit that keeps a resting hand from clicking. Three and
     * four fingers get longer, as they take longer to put down and lift.
     */
    private fun multiTapOpen(now: Long): Boolean =
        tapCandidate && now - t0 <= MULTI_TAP_MAX_NS &&
            now - lastDownNs <= if (maxFingers >= 3) MULTI_TAP_HOLD_NS else TAP_MAX_NS

    /** Leaves a scroll or pinch for a three/four-finger swipe measured from here. */
    private fun startSwipe() {
        if (state == State.PINCH) { zoomPending = 0; endKeys() }
        state = State.SWIPE
        tapCandidate = false
        maxFingers = max(maxFingers, count)
        rebase()
    }

    /** MULTI after a move: decides between scroll, pinch, swipe and (later) a tap. */
    private fun decideMulti(now: Long, eventNs: Long) {
        val s = settings
        if (count >= 3 && s.gestures) {
            if (!tapCandidate) {
                state = State.SWIPE
                swipe()
            }
            return
        }
        if (count == 2) {
            val travel = centroidTravelDp()
            val spread = abs(spread() - downSpread()) / pxPerDp
            if (s.pinchZoom && spread > PINCH_LOCK_DP && spread > travel) {
                tapCandidate = false
                startPinch()
                return
            }
            val start = if (tapCandidate) SCROLL_START_DP else SCROLL_LOCK_DP
            if (s.twoFingerScroll && travel > start && (!s.pinchZoom || travel >= spread)) {
                tapCandidate = false
                startScroll(now, eventNs)
            }
            return
        }
        if (tapCandidate) return
        if (count >= 2 && s.twoFingerScroll) startScroll(now, eventNs)
        else if (count == 1) { state = State.MOVE; primary = firstUsed(); startRun(primary) }
    }

    private fun startScroll(now: Long, eventNs: Long) {
        state = State.SCROLL
        wheelRem = 0f; hwheelRem = 0f
        velX = 0f; velY = 0f; velAccX = 0f; velAccY = 0f; velSinceNs = eventNs
        // Travel before the scroll was recognised counts too, so short scrolls are not lost.
        for (i in 0 until MAX_FINGERS) if (used[i]) emitScroll(lastX[i] - downX[i], lastY[i] - downY[i], now, eventNs)
    }

    /** One finger's move while scrolling; the wheel follows the fingers' centroid. The velocity is timed by [eventNs]. */
    private fun emitScroll(fdxPx: Float, fdyPx: Float, now: Long, eventNs: Long) {
        if (count <= 0) return
        val k = settings.scrollSpeed * UNITS_PER_DP / count / pxPerDp
        // Natural: content follows the fingers (finger down = wheel up, finger right = pan left).
        val sign = if (settings.naturalScroll) 1f else -1f
        val uy = fdyPx * k * sign
        val ux = if (settings.horizontalScroll) -fdxPx * k * sign else 0f
        addScroll(ux, uy)
        lastScrollNs = now
        velAccX += ux; velAccY += uy
        val dt = eventNs - velSinceNs
        if (dt >= VEL_SAMPLE_NS) {
            val ms = dt / 1e6f
            // After a pause the old velocity is stale: the new sample replaces it.
            val w = if (dt > FLING_MAX_PAUSE_NS) 1f else VEL_ALPHA
            velX = w * (velAccX / ms) + (1 - w) * velX
            velY = w * (velAccY / ms) + (1 - w) * velY
            velAccX = 0f; velAccY = 0f; velSinceNs = eventNs
        }
    }

    private fun addScroll(ux: Float, uy: Float) {
        hwheelRem += ux
        val h = hwheelRem.toInt()
        hwheelRem -= h
        hwheel += h
        wheelRem += uy
        val w = wheelRem.toInt()
        wheelRem -= w
        wheel += w
    }

    /** Starts the glide, from [startNs], if the fingers lifted (at [at]) while still moving fast. */
    private fun fling(at: Long, startNs: Long = at) {
        if (!settings.inertia || at - lastScrollNs > FLING_MAX_PAUSE_NS) return
        val speedDp = sqrt(velX * velX + velY * velY) / UNITS_PER_DP / settings.scrollSpeed.coerceAtLeast(0.05f)
        if (speedDp < FLING_MIN_DP_PER_MS) return
        gliding = true
        glideVx = velX; glideVy = velY
        glideNs = startNs
    }

    /** Exponential decay: v(t) = v0·e^(−t/τ); travel over dt = v·τ·(1 − e^(−dt/τ)). */
    private fun glide(now: Long) {
        val dtMs = (now - glideNs) / 1e6f
        if (dtMs <= 0f) return
        val tau = settings.inertiaGlideMs.coerceIn(TouchpadSettings.MIN_GLIDE_MS, TouchpadSettings.MAX_GLIDE_MS)
        val decay = exp(-dtMs / tau)
        val k = tau * (1f - decay)
        addScroll(glideVx * k, glideVy * k)
        glideVx *= decay; glideVy *= decay
        glideNs = now
        if (sqrt(glideVx * glideVx + glideVy * glideVy) < GLIDE_STOP_UNITS_PER_MS) gliding = false
    }

    private fun startPinch() {
        state = State.PINCH
        zoomRem = 0f
        enqueueKey(GestureActions.CTRL shl 8)
        val s = spread()
        zoom(s - downSpread())
        lastSpread = s
    }

    /** Spreading apart = Ctrl + wheel up = zoom in. Held back until Ctrl is visible (see [poll]). */
    private fun zoom(dSpreadPx: Float) {
        zoomRem += dSpreadPx / pxPerDp * ZOOM_UNITS_PER_DP
        val n = zoomRem.toInt()
        zoomRem -= n
        zoomPending += n
    }

    private fun swipe() {
        val cx = centroidX(); val cy = centroidY()
        val tx = (cx - downCentroidX()) / pxPerDp
        val ty = (cy - downCentroidY()) / pxPerDp
        if (max(abs(tx), abs(ty)) < SWIPE_DP) return
        val gesture = if (abs(tx) >= abs(ty)) {
            if (tx > 0) TouchpadGesture.SWIPE_RIGHT else TouchpadGesture.SWIPE_LEFT
        } else {
            if (ty > 0) TouchpadGesture.SWIPE_DOWN else TouchpadGesture.SWIPE_UP
        }
        val action = settings.gesturesFor(maxFingers).action(gesture)
        val horizontal = gesture == TouchpadGesture.SWIPE_LEFT || gesture == TouchpadGesture.SWIPE_RIGHT
        if (action == GestureAction.SwitchApps && horizontal) {
            // Alt held, first step now; every further step of travel moves the selection.
            state = State.SCRUB
            scrubBase = GestureActions.ALT shl 8
            enqueueKey(scrubBase)
            scrubStep(tx > 0)
            originX = cx
        } else {
            run(action)
            state = State.IGNORE // fires once per gesture
        }
    }

    private fun scrub() {
        val cx = centroidX()
        val step = SCRUB_STEP_DP * pxPerDp
        while (cx - originX >= step) { scrubStep(true); originX += step }
        while (originX - cx >= step) { scrubStep(false); originX -= step }
    }

    private fun scrubStep(forward: Boolean) {
        val mods = if (forward) 0 else GestureActions.SHIFT
        enqueueKey(scrubBase or (mods shl 8) or KEY_TAB)
        enqueueKey(scrubBase)
    }

    private fun multiTap() {
        if (maxFingers >= 3 && settings.gestures) {
            run(settings.gesturesFor(maxFingers).tap)
        } else {
            enqueue(if (maxFingers >= 3) MIDDLE else RIGHT)
            enqueue(0)
        }
    }

    private fun run(action: GestureAction) {
        when (action) {
            GestureAction.None -> Unit
            is GestureAction.Mouse -> { enqueue(1 shl action.button.bit); enqueue(0) }
            is GestureAction.Keys -> {
                enqueueKey(((action.modifiers and 0xFF) shl 8) or (action.key and 0xFF))
                enqueueKey(0)
            }
            GestureAction.SwitchApps -> {
                enqueueKey((GestureActions.ALT shl 8) or KEY_TAB)
                enqueueKey(0)
            }
        }
    }

    /** Releases every key a gesture holds, after anything still queued. */
    private fun endKeys() {
        enqueueKey(0)
        zoomRem = 0f
    }

    /** Drops queued key changes and unsent zoom, and releases a visible key. */
    private fun dropKeys() {
        kSize = 0
        zoomRem = 0f; zoomPending = 0
        if (kVisible != 0) enqueueKey(0)
    }

    /** Forgets fingers, gesture, glide and queued (not yet visible) changes. */
    private fun clearGesture() {
        for (i in 0 until MAX_FINGERS) { used[i] = false; aimUsed[i] = false }
        count = 0
        aimCount = 0
        state = State.IDLE
        tapCandidate = false
        run.remX = 0f; run.remY = 0f; wheelRem = 0f; hwheelRem = 0f
        gliding = false
        zoomRem = 0f; zoomPending = 0
        qSize = 0
        kSize = 0
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

    private fun enqueueKey(packed: Int) {
        val last = if (kSize > 0) kQueue[(kHead + kSize - 1) % KEY_QUEUE_CAP] else kVisible
        if (packed == last) return
        if (kSize == KEY_QUEUE_CAP) kQueue[(kHead + kSize - 1) % KEY_QUEUE_CAP] = packed
        else { kQueue[(kHead + kSize) % KEY_QUEUE_CAP] = packed; kSize++ }
    }

    /** Makes every finger's current position its down point. */
    private fun rebase() {
        for (i in 0 until MAX_FINGERS) if (used[i]) {
            downX[i] = lastX[i]; downY[i] = lastY[i]; baseEventNs[i] = lastEventNs[i]
        }
    }

    /**
     * Finger [slot] starts moving the cursor. Its travel since its down point, not sent while the
     * touch was undecided, is emitted once, so no distance is lost.
     */
    private fun startRun(slot: Int) {
        run.start(baseEventNs[slot])
        val bx = lastX[slot] - downX[slot]; val by = lastY[slot] - downY[slot]
        if (bx != 0f || by != 0f) emit(run, bx, by, lastEventNs[slot])
    }

    /** One cursor step of [r]'s finger, taken at event time [eventNs]. */
    private fun emit(r: Run, dxPx: Float, dyPx: Float, eventNs: Long) {
        val ddx = dxPx / pxPerDp
        val ddy = dyPx / pxPerDp
        // Event times may repeat or step back a little: such a step counts as the shortest one.
        val raw = eventNs - r.lastNs
        if (eventNs > r.lastNs) r.lastNs = eventNs
        val dtMs = raw.coerceIn(MIN_STEP_NS, MAX_STEP_NS) / 1e6f
        val inst = sqrt(ddx * ddx + ddy * ddy) / dtMs
        // Time-based EMA, seeded by the first step of a run and after a pause, so neither a
        // standing start nor an old flick skews the speed.
        r.speed = if (r.fresh || raw > MAX_STEP_NS) inst else r.speed + (1f - exp(-dtMs / SPEED_TAU_MS)) * (inst - r.speed)
        r.fresh = false
        val s = settings
        val gain = 1f + s.acceleration *
            ((r.speed - ACCEL_LOW_DP_PER_MS) / (ACCEL_HIGH_DP_PER_MS - ACCEL_LOW_DP_PER_MS)).coerceIn(0f, 1f)
        val fx = ddx * s.sensitivity * gain + r.remX
        val ix = fx.toInt()
        r.remX = fx - ix
        dx += ix
        val fy = ddy * s.sensitivity * gain + r.remY
        val iy = fy.toInt()
        r.remY = fy - iy
        dy += iy
    }

    private fun centroidX(): Float { var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += lastX[i]; return s / max(count, 1) }
    private fun centroidY(): Float { var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += lastY[i]; return s / max(count, 1) }
    private fun downCentroidX(): Float { var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += downX[i]; return s / max(count, 1) }
    private fun downCentroidY(): Float { var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += downY[i]; return s / max(count, 1) }

    private fun centroidTravelDp(): Float {
        val tx = centroidX() - downCentroidX()
        val ty = centroidY() - downCentroidY()
        return sqrt(tx * tx + ty * ty) / pxPerDp
    }

    /** Distance between the first two fingers, now / at their down points (px). */
    private fun spread(): Float = pairDistance(lastX, lastY)
    private fun downSpread(): Float = pairDistance(downX, downY)

    private fun pairDistance(xs: FloatArray, ys: FloatArray): Float {
        var a = -1; var b = -1
        for (i in 0 until MAX_FINGERS) if (used[i]) { if (a < 0) a = i else if (b < 0) b = i }
        if (b < 0) return 0f
        val ddx = xs[a] - xs[b]; val ddy = ys[a] - ys[b]
        return sqrt(ddx * ddx + ddy * ddy)
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

    private fun aimSlotOf(pointer: Long): Int {
        for (i in 0 until MAX_FINGERS) if (aimUsed[i] && aimIds[i] == pointer) return i
        return -1
    }

    /** An aiming finger landed: it records where it is, and finishes a tap's click so its aim never drags. */
    private fun aimDown(pointer: Long, x: Float, y: Float, now: Long, eventNs: Long) {
        if (aimSlotOf(pointer) >= 0 || slotOf(pointer) >= 0) { move(pointer, x, y, now, eventNs); return }
        if (!settings.enabled) return
        val a = aimUsed.indexOf(false)
        if (a < 0) return
        if (state == State.TAP_WAIT) { enqueue(0); state = State.IDLE }
        aimIds[a] = pointer; aimUsed[a] = true
        aimX[a] = x; aimY[a] = y
        aimRun[a].start(eventNs)
        aimCount++
    }

    private fun usedMask(): Int {
        var m = 0
        for (i in 0 until MAX_FINGERS) if (used[i]) m = m or (1 shl i)
        return m
    }

    private fun firstUsed(): Int {
        for (i in 0 until MAX_FINGERS) if (used[i]) return i
        return 0
    }

    companion object {
        const val TAP_MAX_NS = 180_000_000L          // touch shorter than this (and inside slop) is a tap
        const val TAP_DRAG_WINDOW_NS = 220_000_000L  // after a tap: second touch within this = drag / double click
        /** Three/four-finger tap: newest finger down to last up; any multi-finger tap: first down to last up. */
        const val MULTI_TAP_HOLD_NS = 250_000_000L
        const val MULTI_TAP_MAX_NS = 400_000_000L
        const val TAP_SLOP_DP = 10f                  // movement allowed inside a tap, per finger, from its down point
        const val SCROLL_DP_PER_NOTCH = 24f          // at scrollSpeed 1
        /** Wheel units per notch (Windows WHEEL_DELTA). */
        const val WHEEL_UNIT = 120
        const val UNITS_PER_DP = WHEEL_UNIT / SCROLL_DP_PER_NOTCH
        /** Two-finger centroid travel that starts a scroll while a tap is still possible / after. */
        const val SCROLL_START_DP = 6f
        const val SCROLL_LOCK_DP = 1f
        /** Change of finger distance that locks a two-finger touch to pinch zoom. */
        const val PINCH_LOCK_DP = 8f
        /** Pinch: one zoom notch per this much change of finger distance. */
        const val ZOOM_DP_PER_NOTCH = 40f
        const val ZOOM_UNITS_PER_DP = WHEEL_UNIT / ZOOM_DP_PER_NOTCH
        /** Three/four-finger centroid travel that fires a swipe, and each further Alt+Tab step. */
        const val SWIPE_DP = 40f
        const val SCRUB_STEP_DP = 80f
        /** A lift within this of the last scroll movement, faster than the minimum, flings. */
        const val FLING_MAX_PAUSE_NS = 60_000_000L
        const val FLING_MIN_DP_PER_MS = 0.25f
        const val GLIDE_STOP_UNITS_PER_MS = 0.05f
        const val GLIDE_STEP_NS = 4_000_000L
        /** After one of two scrolling fingers lifts, the other may follow within this and still fling. */
        const val LIFT_WINDOW_NS = 120_000_000L
        private const val VEL_SAMPLE_NS = 8_000_000L
        private const val VEL_ALPHA = 0.5f
        const val ACCEL_LOW_DP_PER_MS = 0.25f        // below: gain 1
        const val ACCEL_HIGH_DP_PER_MS = 1.5f        // above: gain 1 + acceleration
        /** Speed steps are timed within these; a longer gap is a pause that re-seeds the speed. */
        private const val MIN_STEP_NS = 1_000_000L
        private const val MAX_STEP_NS = 50_000_000L
        /** Time constant of the speed EMA (about half weight per 120 Hz frame). */
        private const val SPEED_TAU_MS = 10f
        const val DEFAULT_MIN_PULSE_NS = 40_000_000L
        const val MAX_FINGERS = 4
        private const val QUEUE_CAP = 8
        private const val KEY_QUEUE_CAP = 16
        private const val KEY_TAB = 0x2B

        private val LEFT = 1 shl MouseButton.LEFT.bit
        private val RIGHT = 1 shl MouseButton.RIGHT.bit
        private val MIDDLE = 1 shl MouseButton.MIDDLE.bit

        /** Shortest press/release that a pump ticking every [intervalNanos] cannot miss. */
        fun minPulseFor(intervalNanos: Long): Long = max(DEFAULT_MIN_PULSE_NS, intervalNanos + 12_000_000L)
    }
}
