package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GestureActions
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Laptop-style touchpad gestures from raw pointer events (px; engine-clock nanoseconds for the
 * gesture timers, the events' own times for speeds), turned into the surface inputs of [map]
 * ([SurfaceInput], per finger count): taps, double tap, tap-drag (tap then touch and move),
 * holds, releases, the fingers' travel, swipes and pinches. What each does comes from its
 * mappings; by default (see [Bindings.defaultTouchpadMappings]) one finger moves the cursor, tap
 * = left click, double tap = double click, tap-drag = drag with the left button held, two-finger
 * tap = right click, two-finger drag = high-resolution scroll with inertia after a fling,
 * two-finger pinch = Ctrl + wheel zoom, three/four-finger swipes and taps = Windows shortcuts.
 * Not thread-safe; [MappingEngine] calls it under its lock.
 *
 * Travel mapped to the cursor or the wheel is applied here (sensitivity, acceleration, natural
 * scrolling, inertia), as are switching apps and zoom, which hold keys of their own; the other
 * inputs are levels the engine maps ([level]). Wheel output is in 1/[WHEEL_UNIT] notch units
 * (Windows WHEEL_DELTA fractions), so the transport can send smooth scrolling where the host
 * supports it and whole notches elsewhere.
 *
 * Input and key changes go through small queues and are applied by [poll], one per queue per
 * call, only after the visible state has been returned by at least one poll and held for
 * [minPulseNanos]. A report pump that samples state at its own rate therefore never misses a
 * click or key edge.
 */
class Touchpad(settings: TouchpadSettings = TouchpadSettings(), map: SurfaceMap? = null) {
    var settings: TouchpadSettings = settings
    /** What is mapped; by default what [settings]' touchpad switches stand for. */
    var map: SurfaceMap = map ?: SurfaceMap(Bindings.defaultTouchpadMappings(settings))
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
        IDLE, TOUCH, MOVE, TAP_WAIT, TAP_TOUCH, DRAG, MULTI, SCROLL, SCROLL_LIFT, PINCH, SWIPE, IGNORE,
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

    // Switching apps: travel origin of the next step (centroid x, px), and the keys held meanwhile.
    private var originX = 0f
    private var scrubBase = 0
    private var scrubbing = false

    // The current motion (MOVE, DRAG, SCROLL, SWIPE): its finger count (0 = none), travel origin
    // (centroid, px), floating-stick levels (-1..1), whether its swipe fired and it scrolled.
    private var phaseN = 0
    private var phaseX = 0f
    private var phaseY = 0f
    private var levelX = 0f
    private var levelY = 0f
    private var swiped = false
    private var scrolled = false
    // Cursor deadzone (one-finger MOVE): the cursor holds still until the travel passes it, then
    // makes up the travel it held back ([catchX], [catchY] px) over the catch-up time.
    private var dzHolding = false
    private var catching = false
    private var catchX = 0f
    private var catchY = 0f
    private var catchSent = 0f
    private var catchT0 = 0L
    private var catchLastNs = 0L
    private var catchMotion: SurfaceMap.Motion? = null
    // Pinch: finger distance at its start (px) and the level (-1..1, apart = positive).
    private var pinchStart = 0f
    private var pinchLevel = 0f
    // Release: the touch's most fingers and its start. Double tap without tap-drag: the last tap's lift.
    private var touchMax = 0
    private var touchT0 = 0L
    private var lastTapNs = Long.MIN_VALUE / 4

    // Input queue: sets of active inputs (two words of [SurfaceMap] bits) waiting to become
    // visible, oldest first; [want0] / [want1] are the inputs as they should be now.
    private val q0 = LongArray(QUEUE_CAP)
    private val q1 = LongArray(QUEUE_CAP)
    private var qHead = 0
    private var qSize = 0
    private var want0 = 0L
    private var want1 = 0L
    private var vis0 = 0L
    private var vis1 = 0L
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
    /** Background fingers only (not a touch button's aiming finger). */
    val padFingers: Int get() = count

    /** Input changes wait to be shown by [poll]. */
    val queued: Boolean get() = qSize > 0

    /**
     * An input's value as of the last [poll]: 1 / 0, or for Move X / Y and Pinch their level now
     * (-1..1: the travel from where the motion started, a floating stick; the spread change).
     */
    fun level(fingers: Int, input: SurfaceInput): Float {
        if (fingers !in 1..4) return 0f
        return when (input) {
            SurfaceInput.MOTION_X -> if (phaseN == fingers) levelX else 0f
            SurfaceInput.MOTION_Y -> if (phaseN == fingers) levelY else 0f
            SurfaceInput.PINCH -> if (fingers == 2 && state == State.PINCH) pinchLevel else 0f
            else -> {
                val b = SurfaceMap.bit(fingers, input)
                val w = if (b < 64) vis0 else vis1
                if (w and (1L shl (b and 63)) != 0L) 1f else 0f
            }
        }
    }

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
        if (count == 1) { touchMax = 1; touchT0 = now } else touchMax = max(touchMax, count)
        holdCount()
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
                set(1, SurfaceInput.TAP_DRAG, false) // finish the click
                state = State.MULTI; tapCandidate = false; maxFingers = 2
                rebase()
            }
            State.MULTI, State.SWIPE -> {
                // Fingers rarely land together: measure travel from the newest arrangement.
                maxFingers = max(maxFingers, count)
                lastDownNs = now
                rebase()
                if (state == State.SWIPE) restartTravel()
            }
            // Two fingers often start moving before the third lands: that is a swipe, not a scroll or zoom.
            State.SCROLL, State.PINCH -> if (count >= 3 && map.moves(min(count, 4))) startSwipe(now, eventNs)
            State.SCROLL_LIFT -> state = State.SCROLL
            State.DRAG, State.IGNORE -> Unit
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
            State.TOUCH -> if (slot == primary && (!map.taps(1) || pastSlop(slot) || now - t0 > TAP_MAX_NS)) {
                state = State.MOVE
                startRun(slot)
            }
            State.MOVE, State.DRAG -> if (slot == primary) route(1, x - oldX, y - oldY, now, eventNs)
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
            // The fingers' centroid moves by one finger's step over the finger count.
            State.SCROLL, State.SCROLL_LIFT -> route(2, (x - oldX) / count, (y - oldY) / count, now, eventNs)
            State.PINCH -> if (count >= 2) {
                val s = spread()
                if (map.zoom) zoom(s - lastSpread)
                lastSpread = s
                pinchLevels(s)
            }
            State.SWIPE -> route(phaseN, (x - oldX) / count, (y - oldY) / count, now, eventNs)
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
        holdCount()
        if (scrubbing) {
            // Lifting ends the switch (Alt released, the picked window comes to the front).
            endPhase()
            state = if (count == 0) State.IDLE else State.IGNORE
            if (count == 0) touchEnded(now)
            return
        }
        when (state) {
            State.TOUCH -> if (now - t0 <= TAP_MAX_NS && map.taps(1)) tapped(now) else state = State.IDLE
            State.MOVE -> if (slot == primary) {
                if (count == 0 && scrolled) fling(now)
                endPhase()
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.TAP_TOUCH -> if (slot == primary) {
                set(1, SurfaceInput.TAP_DRAG, false)
                if (now - t0 <= TAP_MAX_NS) { pulse(1, SurfaceInput.TAP); pulse(1, SurfaceInput.DOUBLE_TAP) }
                state = State.IDLE
            }
            State.DRAG -> if (slot == primary) {
                set(1, SurfaceInput.TAP_DRAG, false)
                if (count == 0 && scrolled) fling(now)
                endPhase()
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.MULTI -> when {
                count == 0 -> {
                    if (multiTapOpen(now)) multiTap()
                    state = State.IDLE
                }
                // Travel that stayed under the scroll and pinch thresholds is not cursor motion.
                count == 1 && !multiTapOpen(now) -> { rebase(); state = State.MOVE; primary = firstUsed(); startRun(primary) }
                else -> rebase() // still waiting for the last finger or a move
            }
            State.SCROLL -> when (count) {
                0 -> { fling(now); endPhase(); state = State.IDLE }
                // Two fingers seldom lift together: wait a moment for the second before the cursor takes over.
                1 -> { state = State.SCROLL_LIFT; liftNs = now }
                else -> Unit
            }
            // Glides from this lift. A remaining finger that moved after the first lift is judged here
            // (it may have stopped since); one that stayed still is judged from the first lift.
            State.SCROLL_LIFT -> if (count == 0) { fling(if (lastScrollNs > liftNs) now else liftNs, now); endPhase(); state = State.IDLE }
            State.PINCH -> if (count < 2) {
                endKeys()
                endPinch()
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.SWIPE -> if (count == 0) {
                if (scrolled) fling(now)
                endPhase()
                state = State.IDLE
            } else {
                rebase()
                restartTravel()
            }
            State.IGNORE -> if (count == 0) state = State.IDLE
            State.IDLE, State.TAP_WAIT -> Unit
        }
        if (count == 0) touchEnded(now)
    }

    /** Ends the gesture (rotation, profile change, leaving the pad). A visible press still gets a visible release. */
    fun cancel(now: Long) {
        advance(now)
        clearGesture()
        want0 = 0L; want1 = 0L
        enqueue()
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
        holdCount()
        if (count == 0) touchMax = 0 // a cancelled touch never releases
        if (scrubbing) { scrubbing = false; dropKeys() }
        when (state) {
            State.TOUCH, State.MOVE -> if (count == 0) { endPhase(); state = State.IDLE }
            State.TAP_TOUCH, State.DRAG -> if (slot == primary) {
                set(1, SurfaceInput.TAP_DRAG, false) // only the first tap's click, or the drag's button, is released
                endPhase()
                state = if (count == 0) State.IDLE else State.IGNORE
            }
            State.MULTI, State.SCROLL, State.SCROLL_LIFT, State.PINCH, State.SWIPE -> {
                if (state == State.PINCH) { dropKeys(); endPinch() }
                endPhase()
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
        want0 = 0L; want1 = 0L; vis0 = 0L; vis1 = 0L
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
     * Advances timers and the glide, applies at most one queued input change and one key change,
     * and returns the mouse buttons the visible inputs' mappings press ([keyModifiers] / [key]
     * hold the keys: switching apps and zoom, else the mapped ones).
     */
    fun poll(now: Long): Int {
        advance(now)
        if (qSize > 0 && seen && now - visibleSince >= minPulseNanos) {
            vis0 = q0[qHead]; vis1 = q1[qHead]
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
        var mouse = 0; var mods = 0; var mapped = 0
        var w = vis0
        while (w != 0L) {
            val b = java.lang.Long.numberOfTrailingZeros(w); w = w and (w - 1)
            mouse = mouse or map.mouse[b]; mods = mods or map.mods[b]; if (mapped == 0) mapped = map.key[b]
        }
        w = vis1
        while (w != 0L) {
            val b = 64 + java.lang.Long.numberOfTrailingZeros(w); w = w and (w - 1)
            mouse = mouse or map.mouse[b]; mods = mods or map.mods[b]; if (mapped == 0) mapped = map.key[b]
        }
        keyModifiers = ((kVisible shr 8) and 0xFF) or mods
        key = (kVisible and 0xFF).let { if (it != 0) it else mapped }
        return mouse
    }

    fun drainMotion() { dx = 0; dy = 0; wheel = 0; hwheel = 0 }

    /** Earliest time [poll] may change something without new input, or Long.MAX_VALUE. */
    fun nextDeadline(): Long {
        var d = Long.MAX_VALUE
        if (qSize > 0) d = visibleSince + minPulseNanos
        if (kSize > 0) d = min(d, max(kVisibleSince, lastZoomNs) + minPulseNanos)
        if (gliding) d = min(d, glideNs + GLIDE_STEP_NS)
        if (catching) d = min(d, catchLastNs + GLIDE_STEP_NS)
        when (state) {
            State.TAP_WAIT -> d = min(d, tapDeadline)
            State.SCROLL_LIFT -> d = min(d, liftNs + LIFT_WINDOW_NS)
            else -> Unit
        }
        return d
    }

    private fun advance(now: Long) {
        if (gliding) glide(now)
        if (catching) catchUp(now)
        when (state) {
            State.TAP_WAIT -> if (now >= tapDeadline) { set(1, SurfaceInput.TAP_DRAG, false); state = State.IDLE }
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
    private fun startSwipe(now: Long, eventNs: Long) {
        if (state == State.PINCH) { zoomPending = 0; endKeys(); endPinch() }
        state = State.SWIPE
        tapCandidate = false
        maxFingers = max(maxFingers, count)
        rebase()
        startPhase(min(maxFingers, 4), now, eventNs)
    }

    /** MULTI after a move: decides between scroll, pinch, swipe and (later) a tap. */
    private fun decideMulti(now: Long, eventNs: Long) {
        val m = map
        if (count >= 3 && m.moves(min(count, 4))) {
            if (!tapCandidate) {
                state = State.SWIPE
                startPhase(min(maxFingers, 4), now, eventNs)
            }
            return
        }
        if (count == 2) {
            val travel = centroidTravelDp()
            val spread = abs(spread() - downSpread()) / pxPerDp
            val pinches = m.pinches()
            if (pinches && spread > PINCH_LOCK_DP && spread > travel) {
                tapCandidate = false
                startPinch()
                return
            }
            val start = if (tapCandidate) SCROLL_START_DP else SCROLL_LOCK_DP
            if (m.moves(2) && travel > start && (!pinches || travel >= spread)) {
                tapCandidate = false
                startScroll(now, eventNs)
            }
            return
        }
        if (tapCandidate) return
        if (count >= 2 && m.moves(2)) startScroll(now, eventNs)
        else if (count == 1) { state = State.MOVE; primary = firstUsed(); startRun(primary) }
    }

    /** Two (or more, with nothing mapped for them) fingers move: the two-finger motion. */
    private fun startScroll(now: Long, eventNs: Long) {
        state = State.SCROLL
        startPhase(2, now, eventNs)
    }

    /**
     * A motion of [n] fingers starts: its travel is measured from their touch-down points, and the
     * travel before it was recognised is sent now, so short moves and scrolls are not lost.
     */
    private fun startPhase(n: Int, now: Long, eventNs: Long) {
        endPhase()
        phaseN = n
        swiped = false; scrolled = false
        phaseX = downCentroidX(); phaseY = downCentroidY()
        dzHolding = n == 1 && state == State.MOVE && settings.cursorDeadzoneDp > 0f && map.motion[0].cursor
        wheelRem = 0f; hwheelRem = 0f
        velX = 0f; velY = 0f; velAccX = 0f; velAccY = 0f; velSinceNs = eventNs
        route(n, centroidX() - phaseX, centroidY() - phaseY, now, eventNs)
    }

    /** Fingers landed or lifted mid-motion: travel is measured from where they are now. */
    private fun restartTravel() {
        phaseX = centroidX() - levelX * STICK_FULL_DP * pxPerDp
        phaseY = centroidY() - levelY * STICK_FULL_DP * pxPerDp
        originX = centroidX()
    }

    /** The motion ends: its held directions let go, its levels go back to 0, app switching ends. */
    private fun endPhase() {
        dzHolding = false
        val n = phaseN
        if (n in 1..4) for (i in DIR_HOLDS) set(n, i, false)
        phaseN = 0
        levelX = 0f; levelY = 0f
        if (scrubbing) { scrubbing = false; endKeys() }
    }

    /**
     * One step of the [n]-finger motion: the centroid moved by [sdx], [sdy] px. Cursor and wheel
     * mappings follow the step; held directions, swipes, the floating stick and app switching the
     * travel since the motion started.
     */
    private fun route(n: Int, sdx: Float, sdy: Float, now: Long, eventNs: Long) {
        if (n !in 1..4) return
        val m = map.motion[n - 1]
        if (m.cursor) {
            if (dzHolding) passDeadzone(m, now, eventNs) else emit(run, sdx, sdy, eventNs, m)
        }
        if (m.scroll) scrollStep(sdx, sdy, now, eventNs, m)
        val tx = (centroidX() - phaseX) / pxPerDp
        val ty = (centroidY() - phaseY) / pxPerDp
        levelX = (tx / STICK_FULL_DP).coerceIn(-1f, 1f)
        levelY = (ty / STICK_FULL_DP).coerceIn(-1f, 1f)
        set(n, SurfaceInput.HOLD_LEFT, levelX <= -0.5f)
        set(n, SurfaceInput.HOLD_RIGHT, levelX >= 0.5f)
        set(n, SurfaceInput.HOLD_UP, levelY <= -0.5f)
        set(n, SurfaceInput.HOLD_DOWN, levelY >= 0.5f)
        if (!swiped && max(abs(tx), abs(ty)) >= SWIPE_DP) {
            swiped = true
            val horizontal = abs(tx) >= abs(ty)
            pulse(n, when {
                horizontal && tx > 0 -> SurfaceInput.SWIPE_RIGHT
                horizontal -> SurfaceInput.SWIPE_LEFT
                ty > 0 -> SurfaceInput.SWIPE_DOWN
                else -> SurfaceInput.SWIPE_UP
            })
            if (horizontal && m.switchApps) {
                // Alt held, first step now; every further step of travel moves the selection.
                scrubbing = true
                scrubBase = GestureActions.ALT shl 8
                enqueueKey(scrubBase)
                scrubStep(tx > 0)
                originX = centroidX()
            }
        } else if (scrubbing) {
            scrub()
        }
    }

    /**
     * Held in the cursor deadzone: once the travel from the touch-down point (along the one
     * axis mapped to the cursor, else in any direction) passes it, the cursor starts following
     * and makes up all the travel so far over the catch-up time.
     */
    private fun passDeadzone(m: SurfaceMap.Motion, now: Long, eventNs: Long) {
        val tx = centroidX() - phaseX
        val ty = centroidY() - phaseY
        val usesX = m.cxx != 0f || m.cyx != 0f
        val usesY = m.cxy != 0f || m.cyy != 0f
        val d = when {
            usesX && usesY -> sqrt(tx * tx + ty * ty)
            usesX -> abs(tx)
            else -> abs(ty)
        }
        if (d / pxPerDp < settings.cursorDeadzoneDp.coerceAtMost(TouchpadSettings.MAX_CURSOR_DEADZONE_DP)) return
        dzHolding = false
        run.start(eventNs)
        catching = true
        catchX = tx; catchY = ty; catchSent = 0f
        catchT0 = now; catchLastNs = now
        catchMotion = m
        catchUp(now)
    }

    /** Sends the share of the held-back travel due by [now] (no acceleration: it is not finger speed). */
    private fun catchUp(now: Long) {
        val m = catchMotion ?: return
        val dur = settings.catchUpMs.coerceIn(0, TouchpadSettings.MAX_CATCH_UP_MS) * 1_000_000L
        val f = if (dur <= 0L) 1f else ((now - catchT0).toFloat() / dur).coerceIn(0f, 1f)
        val step = f - catchSent
        catchSent = f
        catchLastNs = now
        if (step > 0f) {
            val inX = catchX * step / pxPerDp * settings.sensitivity
            val inY = catchY * step / pxPerDp * settings.verticalSensitivity
            val fx = m.cxx * inX + m.cxy * inY + run.remX
            val ix = fx.toInt(); run.remX = fx - ix; dx += ix
            val fy = m.cyx * inX + m.cyy * inY + run.remY
            val iy = fy.toInt(); run.remY = fy - iy; dy += iy
        }
        if (f >= 1f) { catching = false; catchMotion = null }
    }

    /** One step of motion mapped to the wheel; the velocity (for a fling) is timed by [eventNs]. */
    private fun scrollStep(sdxPx: Float, sdyPx: Float, now: Long, eventNs: Long, m: SurfaceMap.Motion) {
        val k = settings.scrollSpeed * UNITS_PER_DP / pxPerDp
        // Natural: content follows the fingers (finger down = wheel up, finger right = pan left).
        val sign = if (settings.naturalScroll) 1f else -1f
        val sx = sdxPx * k * sign
        val sy = sdyPx * k * sign
        val uy = m.wx * sx + m.wy * sy
        val ux = m.hx * sx + m.hy * sy
        addScroll(ux, uy)
        lastScrollNs = now
        scrolled = true
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
        endPhase()
        state = State.PINCH
        zoomRem = 0f
        pinchStart = downSpread()
        val s = spread()
        if (map.zoom) {
            enqueueKey(GestureActions.CTRL shl 8)
            zoom(s - pinchStart)
        }
        lastSpread = s
        pinchLevels(s)
    }

    /** Pinch in / out are held while the spread changed by half the full distance; [pinchLevel] follows it. */
    private fun pinchLevels(spreadPx: Float) {
        pinchLevel = ((spreadPx - pinchStart) / pxPerDp / STICK_FULL_DP).coerceIn(-1f, 1f)
        set(2, SurfaceInput.PINCH_OUT, pinchLevel >= 0.5f)
        set(2, SurfaceInput.PINCH_IN, pinchLevel <= -0.5f)
    }

    private fun endPinch() {
        pinchLevel = 0f
        set(2, SurfaceInput.PINCH_OUT, false)
        set(2, SurfaceInput.PINCH_IN, false)
    }

    /** Spreading apart = Ctrl + wheel up = zoom in. Held back until Ctrl is visible (see [poll]). */
    private fun zoom(dSpreadPx: Float) {
        zoomRem += dSpreadPx / pxPerDp * ZOOM_UNITS_PER_DP
        val n = zoomRem.toInt()
        zoomRem -= n
        zoomPending += n
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

    private fun multiTap() = pulse(min(maxFingers, 4), SurfaceInput.TAP)

    /** Hold with n fingers is held while exactly n background fingers are down. */
    private fun holdCount() {
        for (n in 1..4) if (n != count) set(n, SurfaceInput.HOLD, false)
        if (count in 1..4) set(count, SurfaceInput.HOLD, true)
    }

    /** Every background finger lifted: the touch's release, if it was quick enough. */
    private fun touchEnded(now: Long) {
        val n = min(touchMax, 4)
        touchMax = 0
        val limit = settings.releaseTapMs.coerceIn(0, TouchpadSettings.MAX_RELEASE_TAP_MS) * 1_000_000L
        if (n >= 1 && (limit <= 0 || now - touchT0 <= limit)) pulse(n, SurfaceInput.RELEASE)
    }

    /** Turns input [input] with [n] fingers on or off (nothing if it is not mapped). */
    private fun set(n: Int, input: SurfaceInput, on: Boolean) {
        if (!map.has(n, input)) return
        val b = SurfaceMap.bit(n, input)
        val m = 1L shl (b and 63)
        if (b < 64) {
            val w = if (on) want0 or m else want0 and m.inv()
            if (w == want0) return
            want0 = w
        } else {
            val w = if (on) want1 or m else want1 and m.inv()
            if (w == want1) return
            want1 = w
        }
        enqueue()
    }

    /** A short press of [input], visible for at least the minimum pulse. */
    private fun pulse(n: Int, input: SurfaceInput) {
        set(n, input, true)
        set(n, input, false)
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
        // What is shown stays until [cancel] / [reset] let it go.
        want0 = vis0; want1 = vis1
        phaseN = 0; levelX = 0f; levelY = 0f; pinchLevel = 0f
        scrubbing = false; scrolled = false; touchMax = 0
        dzHolding = false; catching = false; catchMotion = null
    }

    /**
     * A one-finger tap. With a tap-drag mapping, it is held while a second touch may follow (a
     * drag, or a double tap); else a second tap soon after is a double tap.
     */
    private fun tapped(now: Long) {
        if (map.has(1, SurfaceInput.TAP_DRAG)) {
            set(1, SurfaceInput.TAP_DRAG, true)
            pulse(1, SurfaceInput.TAP)
            state = State.TAP_WAIT
            tapDeadline = now + TAP_DRAG_WINDOW_NS
        } else {
            pulse(1, SurfaceInput.TAP)
            if (now - lastTapNs <= TAP_DRAG_WINDOW_NS + TAP_MAX_NS) {
                pulse(1, SurfaceInput.DOUBLE_TAP)
                lastTapNs = Long.MIN_VALUE / 4
            } else {
                lastTapNs = now
            }
            state = State.IDLE
        }
    }

    /** Queues [want0] / [want1] to become visible after what is queued, unless that already is. */
    private fun enqueue() {
        val i = (qHead + qSize - 1 + QUEUE_CAP) % QUEUE_CAP
        val l0 = if (qSize > 0) q0[i] else vis0
        val l1 = if (qSize > 0) q1[i] else vis1
        if (want0 == l0 && want1 == l1) return
        if (qSize == QUEUE_CAP) { q0[i] = want0; q1[i] = want1 }
        else {
            val j = (qHead + qSize) % QUEUE_CAP
            q0[j] = want0; q1[j] = want1; qSize++
        }
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
        startPhase(1, lastEventNs[slot], lastEventNs[slot])
    }

    /**
     * One cursor step of [r]'s finger, taken at event time [eventNs]. [m] routes the travel
     * (Move X / Y to cursor X / Y); null = straight (an aiming finger).
     */
    private fun emit(r: Run, dxPx: Float, dyPx: Float, eventNs: Long, m: SurfaceMap.Motion? = null) {
        if (dxPx == 0f && dyPx == 0f) return
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
        val inX = ddx * s.sensitivity * gain
        val inY = ddy * s.verticalSensitivity * gain
        val fx = (if (m == null) inX else m.cxx * inX + m.cxy * inY) + r.remX
        val ix = fx.toInt()
        r.remX = fx - ix
        dx += ix
        val fy = (if (m == null) inY else m.cyx * inX + m.cyy * inY) + r.remY
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
        if (state == State.TAP_WAIT) { set(1, SurfaceInput.TAP_DRAG, false); state = State.IDLE }
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
        /** Travel (or pinch spread change) that is a full floating-stick level; held directions press at half of it. */
        const val STICK_FULL_DP = 40f
        private val DIR_HOLDS = listOf(SurfaceInput.HOLD_LEFT, SurfaceInput.HOLD_RIGHT, SurfaceInput.HOLD_UP, SurfaceInput.HOLD_DOWN)
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


        /** Shortest press/release that a pump ticking every [intervalNanos] cannot miss. */
        fun minPulseFor(intervalNanos: Long): Long = max(DEFAULT_MIN_PULSE_NS, intervalNanos + 12_000_000L)
    }
}
