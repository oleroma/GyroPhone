package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.GestureActions
import com.oleroma.gyrophone.core.model.InertiaCurve
import com.oleroma.gyrophone.core.model.MotionFeel
import com.oleroma.gyrophone.core.model.MoveMode
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import kotlin.math.abs
import kotlin.math.atan2
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

    // How the motion of each finger count works ([TouchpadSettings.feel]), worked out once per settings.
    private var feelOf: TouchpadSettings? = null
    private val feels = arrayOfNulls<MotionFeel>(4)

    /** The finger count of the motion now or last (for a glide after the lift). */
    private var motionN = 1

    /** How the motion of [n] fingers works: the shared settings with its Move X's and Y's own. */
    private fun feel(n: Int = motionN): MotionFeel {
        val s = settings
        if (s !== feelOf) {
            feelOf = s; feels.fill(null)
        }
        val i = n.coerceIn(1, 4) - 1
        return feels[i] ?: s.feel(i + 1).also { feels[i] = it }
    }

    /** What is mapped; by default what [settings]' touchpad switches stand for. */
    var map: SurfaceMap = map ?: SurfaceMap(Bindings.defaultTouchpadMappings(settings))

    /** Display density (px per dp); thresholds and sensitivity are in dp. */
    var pxPerDp: Float = 1f
    var minPulseNanos: Long = DEFAULT_MIN_PULSE_NS

    /** The longest gap between the taps of a double tap (and before a tap-drag's touch). */
    var doubleTapNanos: Long = DEFAULT_DOUBLE_TAP_NS

    /** How long still fingers must stay down to be a long press. */
    var longPressNanos: Long = DEFAULT_LONG_PRESS_NS

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

        fun start(atNs: Long) {
            speed = 0f; fresh = true; lastNs = atNs; remX = 0f; remY = 0f
        }
    }

    /** The background cursor finger's run (MOVE, DRAG). */
    private val run = Run()

    // Scrolling, in wheel units (1/120 notch); remainders keep sub-unit travel.
    private var wheelRem = 0f
    private var hwheelRem = 0f

    /**
     * Move X / Y velocity EMA (the travel they take, px per ms) for a fling, the travel / time it
     * is being sampled over, and when they last moved.
     */
    private var velX = 0f
    private var velY = 0f
    private var velAccX = 0f
    private var velAccY = 0f
    private var velSinceNs = 0L
    private var lastMoveNs = 0L

    /** When the first of two scrolling fingers lifted (SCROLL_LIFT). */
    private var liftNs = 0L

    // Inertia after a fling: Move X / Y's velocity (px per ms), each axis's glide time and curve
    // and, for a linear one, its slowing (px per ms²), the motion's cursor and wheel mappings.
    private var gliding = false
    private var glideVx = 0f
    private var glideVy = 0f
    private var glideNs = 0L
    private var glideTauX = 0f
    private var glideTauY = 0f
    private var glideLinearX = false
    private var glideLinearY = false
    private var glideDecX = 0f
    private var glideDecY = 0f
    private var glideMotion: SurfaceMap.Motion? = null

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
    // (centroid, px), floating-stick levels (-1..1), whether its swipe fired and its Moves moved.
    private var phaseN = 0

    // Travel since the motion started (px), as Move X / Y take it; and the recent travel that
    // judges its heading for them (about [MotionFeel.moveSampleDp] long, [gateMoves]).
    private var trX = 0f
    private var trY = 0f
    private var winX = 0f
    private var winY = 0f
    private var levelX = 0f
    private var levelY = 0f

    /** The motion's swipe directions and held directions; [swiped]: one of its swipes fired. */
    private val dirs = DirectionTracker()
    private var swiped = false

    // Latch ([TouchpadSettings.latch]): held directions kept after the fingers lifted, their
    // finger count, and when they let go by themselves (Long.MAX_VALUE = not timed now).
    private var latchN = 0
    private var latchMask = 0
    private val latchZones = IntArray(4)
    private var latchUntil = Long.MAX_VALUE

    /** A finger the system cancelled is being removed: its motion never latches. */
    private var cancelling = false

    /** The time of the latest event or poll. */
    private var nowNs = 0L
    private var moved = false

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

    // Where the deadzone is measured from (centroid, px), and re-arming: where the finger came to
    // rest and since when (an event time).
    private var dzX = 0f
    private var dzY = 0f
    private var rearmable = false
    private var stillX = 0f
    private var stillY = 0f
    private var stillNs = 0L

    // Pinch: finger distance at its start (px) and the level (-1..1, apart = positive).
    private var pinchStart = 0f
    private var pinchLevel = 0f

    // Release: the touch's most fingers. Double tap without tap-drag: the last tap's finger count
    // and lift; a tap waiting out the double-tap window ([SurfaceMap.tapWaits]): its finger count (0 = none).
    private var touchMax = 0
    private var lastTapN = 0
    private var lastTapNs = Long.MIN_VALUE / 4
    private var pendingTapN = 0

    /** One finger's double tap is held: from its second touch down until every finger lifts (double tap and drag). */
    private var doubleHeld = false

    // Long press: the finger count armed (0 = none: moved, or a double tap's second touch), since
    // when, and whether it fired.
    private var longN = 0
    private var longT0 = 0L
    private var longOn = false

    // Inputs (three words of [SurfaceMap] bits): [want0] / [want1] / [want2] as they should be
    // now, [vis0] / [vis1] / [vis2] as the last [poll] showed them. Each change of an input waits
    // in [flips] (input bits, oldest first) until it may show: once that input and those that share
    // a target with it ([SurfaceMap.peers]) have shown their last change for the minimum pulse
    // ([shownSince]) and have no older change waiting. So a quick press is never lost and one
    // output never merges two presses, while inputs that press other things change at once.
    private var want0 = 0L
    private var want1 = 0L
    private var want2 = 0L
    private var vis0 = 0L
    private var vis1 = 0L
    private var vis2 = 0L
    private val flips = IntArray(FLIP_CAP)
    private var flipCount = 0
    private val shownSince = LongArray(SurfaceMap.BITS) { Long.MIN_VALUE / 4 }
    private val blocked = LongArray(3)

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
    val queued: Boolean get() = flipCount > 0

    /** Held directions are latched (kept after the fingers lifted). */
    val latched: Boolean get() = latchMask != 0

    /**
     * An input's value as of the last [poll]: 1 / 0, or for Move X / Y and Pinch their level now
     * (-1..1: the travel from where the motion started, a floating stick; the spread change).
     * [zone]: a held direction's length zone.
     */
    fun level(fingers: Int, input: SurfaceInput, zone: Int = 0): Float {
        if (fingers !in 1..4 || zone !in 0 until TouchpadSettings.MAX_HOLD_ZONES) return 0f
        return when (input) {
            SurfaceInput.MOTION_X -> if (phaseN == fingers) levelX else 0f
            SurfaceInput.MOTION_Y -> if (phaseN == fingers) levelY else 0f
            SurfaceInput.PINCH -> if (fingers == 2 && state == State.PINCH) pinchLevel else 0f
            else -> {
                val b = SurfaceMap.bit(fingers, input, zone)
                val w = when {
                    b < 64 -> vis0
                    b < 128 -> vis1
                    else -> vis2
                }
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
        if (aiming) {
            aimDown(pointer, x, y, now, eventNs); return
        }
        movedMask = 0
        val known = slotOf(pointer)
        if (known >= 0 || aimSlotOf(pointer) >= 0) {
            move(pointer, x, y, now, eventNs); return
        }
        if (!settings.enabled) return
        val slot = freeSlot()
        if (slot < 0) return
        ids[slot] = pointer; used[slot] = true
        downX[slot] = x; downY[slot] = y; lastX[slot] = x; lastY[slot] = y
        baseEventNs[slot] = eventNs; lastEventNs[slot] = eventNs
        count++
        latchUntil = Long.MAX_VALUE // a latch never times out under a finger
        touchMax = if (count == 1) 1 else max(touchMax, count)
        holdCount()
        if (count in 1..4) pulse(count, SurfaceInput.PRESS)
        armLong(if (state == State.TAP_WAIT) 0 else count, now)
        when (state) {
            State.IDLE -> {
                state = State.TOUCH; primary = slot; t0 = now
                // A touch soon after a one-finger tap: the double tap, held while it stays down
                // (moving drags with it held). With tap-drag mapped, its own states do this.
                if (latchMask == 0 && !map.has(1, SurfaceInput.TAP_DRAG) && map.has(1, SurfaceInput.DOUBLE_TAP) &&
                    lastTapN == 1 && now - lastTapNs <= doubleTapNanos
                ) {
                    doubleHeld = true
                    pendingTapN = 0; lastTapN = 0
                    longN = 0 // never a long press too
                    set(1, SurfaceInput.DOUBLE_TAP, true)
                }
            }

            State.TOUCH -> {
                state = State.MULTI; tapCandidate = true; maxFingers = 2; lastDownNs = now
            }

            State.MOVE -> {
                // A finger added mid-move only scrolls; measure from here, so the cursor travel
                // so far does not count as scroll travel.
                rebase()
                state = State.MULTI; tapCandidate = false; maxFingers = 2
            }

            State.TAP_WAIT -> {
                state = State.TAP_TOUCH; primary = slot; t0 = now
            }

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
            val dxPx = x - aimX[a];
            val dyPx = y - aimY[a]
            aimX[a] = x; aimY[a] = y
            emit(aimRun[a], dxPx, dyPx, eventNs)
            return
        }
        val slot = slotOf(pointer)
        if (slot < 0) return
        val oldX = lastX[slot];
        val oldY = lastY[slot]
        lastX[slot] = x; lastY[slot] = y
        lastEventNs[slot] = eventNs
        if (longN > 0 && !longOn && pastSlop(slot)) longN = 0
        when (state) {
            // With tap-to-click off there is no tap to wait for: the cursor moves from the first motion.
            // A latch waits for a tap too: the tap lets it go.
            State.TOUCH -> if (slot == primary && ((!map.taps(1) && latchMask == 0) || pastSlop(slot) || now - t0 > TAP_MAX_NS)) {
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
        if (a >= 0) {
            aimUsed[a] = false; aimCount--; return
        }
        val slot = slotOf(pointer)
        if (slot < 0) return
        used[slot] = false
        count--
        movedMask = 0
        holdCount()
        armLong(0, now)
        // The double tap's touch lifted: it ends, and is not a tap too.
        val endedDouble = doubleHeld && count == 0
        if (endedDouble) {
            doubleHeld = false
            set(1, SurfaceInput.DOUBLE_TAP, false)
        }
        if (scrubbing) {
            // Lifting ends the switch (Alt released, the picked window comes to the front).
            endPhase()
            state = if (count == 0) State.IDLE else State.IGNORE
            if (count == 0) touchEnded(now)
            return
        }
        when (state) {
            State.TOUCH -> {
                state = State.IDLE
                if (!endedDouble && now - t0 <= TAP_MAX_NS) {
                    // A tap on a latch lets it go, and is used up by that.
                    if (latchMask != 0) releaseLatch() else if (map.taps(1)) tapped(1, now)
                }
            }

            State.MOVE -> if (slot == primary) {
                if (count == 0 && moved) fling(now)
                endPhase()
                state = if (count == 0) State.IDLE else State.IGNORE
            }

            State.TAP_TOUCH -> if (slot == primary) {
                set(1, SurfaceInput.TAP_DRAG, false)
                // The second tap: a double tap (never a tap too, when one is mapped).
                if (now - t0 <= TAP_MAX_NS) pulse(
                    1,
                    if (map.has(1, SurfaceInput.DOUBLE_TAP)) SurfaceInput.DOUBLE_TAP else SurfaceInput.TAP
                )
                state = State.IDLE
            }

            State.DRAG -> if (slot == primary) {
                set(1, SurfaceInput.TAP_DRAG, false)
                if (count == 0 && moved) fling(now)
                endPhase()
                state = if (count == 0) State.IDLE else State.IGNORE
            }

            State.MULTI -> when {
                count == 0 -> {
                    val tap = multiTapOpen(now) && !endedDouble
                    state = State.IDLE
                    if (tap) {
                        if (latchMask != 0) releaseLatch() else tapped(min(maxFingers, 4), now)
                    }
                }
                // Travel that stayed under the scroll and pinch thresholds is not cursor motion.
                count == 1 && !multiTapOpen(now) -> {
                    rebase(); state = State.MOVE; primary = firstUsed(); startRun(primary)
                }

                else -> rebase() // still waiting for the last finger or a move
            }

            State.SCROLL -> when (count) {
                0 -> {
                    fling(now); endPhase(); state = State.IDLE
                }
                // Two fingers seldom lift together: wait a moment for the second before the cursor takes over.
                1 -> {
                    state = State.SCROLL_LIFT; liftNs = now
                }

                else -> Unit
            }
            // Glides from this lift. A remaining finger that moved after the first lift is judged here
            // (it may have stopped since); one that stayed still is judged from the first lift.
            State.SCROLL_LIFT -> if (count == 0) {
                fling(if (lastMoveNs > liftNs) now else liftNs, now); endPhase(); state = State.IDLE
            }

            State.PINCH -> if (count < 2) {
                endKeys()
                endPinch()
                state = if (count == 0) State.IDLE else State.IGNORE
            }

            State.SWIPE -> if (count == 0) {
                if (moved) fling(now)
                endPhase()
                state = State.IDLE
            } else {
                rebase()
                restartTravel()
            }

            State.IGNORE -> if (count == 0) state = State.IDLE
            State.IDLE, State.TAP_WAIT -> Unit
        }
        if (count == 0) {
            touchEnded(now)
            armLatchTimer(now)
        }
    }

    /** Ends the gesture (rotation, profile change, leaving the pad). A visible press still gets a visible release. */
    fun cancel(now: Long) {
        advance(now)
        clearGesture()
        for (b in 0 until SurfaceMap.BITS) if (shown(b)) set(b, false)
        if (kVisible != 0) enqueueKey(0)
    }

    /**
     * Removes one finger the system cancelled (an unintended touch): it never clicks, taps or flings,
     * and keys its gesture holds are released. A finger left behind moves the cursor from where it is.
     */
    fun cancelFinger(pointer: Long, now: Long) {
        advance(now)
        val a = aimSlotOf(pointer)
        if (a >= 0) {
            aimUsed[a] = false; aimCount--; return
        }
        val slot = slotOf(pointer)
        if (slot < 0) return
        cancelling = true
        try {
            cancelFingerSlot(slot)
        } finally {
            cancelling = false
        }
        if (count == 0) armLatchTimer(now)
    }

    private fun cancelFingerSlot(slot: Int) {
        used[slot] = false
        count--
        movedMask = 0
        holdCount()
        armLong(0, nowNs)
        if (doubleHeld && count == 0) {
            doubleHeld = false
            set(1, SurfaceInput.DOUBLE_TAP, false)
        }
        if (count == 0) touchMax = 0 // a cancelled touch never releases
        if (scrubbing) {
            scrubbing = false; dropKeys()
        }
        when (state) {
            State.TOUCH, State.MOVE -> if (count == 0) {
                endPhase(); state = State.IDLE
            }

            State.TAP_TOUCH, State.DRAG -> if (slot == primary) {
                set(1, SurfaceInput.TAP_DRAG, false) // only the first tap's click, or the drag's button, is released
                endPhase()
                state = if (count == 0) State.IDLE else State.IGNORE
            }

            State.MULTI, State.SCROLL, State.SCROLL_LIFT, State.PINCH, State.SWIPE -> {
                if (state == State.PINCH) {
                    dropKeys(); endPinch()
                }
                endPhase()
                tapCandidate = false
                rebase()
                when (count) {
                    0 -> state = State.IDLE
                    1 -> {
                        state = State.MOVE; primary = firstUsed(); startRun(primary)
                    }

                    else -> state = State.MULTI // the fingers left decide again
                }
            }

            State.IGNORE -> if (count == 0) state = State.IDLE
            State.IDLE, State.TAP_WAIT -> Unit
        }
    }

    /**
     * One finger's swipes, held directions and zones, for the pad's swipe line; null when none is mapped.
     */
    fun guide(): SwipeGuide? {
        val m = map
        val zones = IntArray(4) { i ->
            val d = Direction.entries[i]
            val any = (0 until m.zoneCount(1, d)).any { z -> m.has(1, HOLDS[i], z) }
            if (any) m.zoneCount(1, d) else 0
        }
        var swipes = 0
        for (i in 0 until 4) if (m.has(1, SWIPES[i])) swipes = swipes or (1 shl i)
        return if (swipes == 0 && zones.all { it == 0 }) null else SwipeGuide(settings, zones, swipes)
    }

    /** Hard reset: everything released and cleared immediately (host disconnect, transport switch). */
    fun reset() {
        clearGesture()
        want0 = 0L; want1 = 0L; want2 = 0L; vis0 = 0L; vis1 = 0L; vis2 = 0L
        flipCount = 0
        shownSince.fill(Long.MIN_VALUE / 4)
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
        if (flipCount > 0) showDue(now)

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
        var mouse = 0;
        var mods = 0;
        var mapped = 0
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
        w = vis2
        while (w != 0L) {
            val b = 128 + java.lang.Long.numberOfTrailingZeros(w); w = w and (w - 1)
            mouse = mouse or map.mouse[b]; mods = mods or map.mods[b]; if (mapped == 0) mapped = map.key[b]
        }
        keyModifiers = ((kVisible shr 8) and 0xFF) or mods
        key = (kVisible and 0xFF).let { if (it != 0) it else mapped }
        return mouse
    }

    fun drainMotion() {
        dx = 0; dy = 0; wheel = 0; hwheel = 0
    }

    /** Earliest time [poll] may change something without new input, or Long.MAX_VALUE. */
    fun nextDeadline(): Long {
        var d = Long.MAX_VALUE
        for (i in 0 until flipCount) d = min(d, dueAt(flips[i]))
        if (kSize > 0) d = min(d, max(kVisibleSince, lastZoomNs) + minPulseNanos)
        if (gliding) d = min(d, glideNs + GLIDE_STEP_NS)
        if (latchMask != 0) d = min(d, latchUntil)
        if (catching) d = min(d, catchLastNs + GLIDE_STEP_NS)
        if (rearmable && !dzHolding && phaseN == 1) d = min(d, stillNs + feel().deadzoneRearmMs * 1_000_000L)
        if (longN > 0 && !longOn && map.has(longN, SurfaceInput.LONG_PRESS)) d = min(d, longT0 + longPressNanos)
        if (pendingTapN != 0) {
            d = min(d, lastTapNs + doubleTapNanos + 1)
            if (count > 0) d = min(d, max(t0 + TAP_MAX_NS, lastDownNs + MULTI_TAP_HOLD_NS) + 1)
        }
        when (state) {
            State.TAP_WAIT -> d = min(d, tapDeadline)
            State.SCROLL_LIFT -> d = min(d, liftNs + LIFT_WINDOW_NS)
            else -> Unit
        }
        return d
    }

    private fun advance(now: Long) {
        nowNs = now
        if (latchMask != 0 && now >= latchUntil) releaseLatch()
        if (gliding) glide(now)
        if (catching) catchUp(now)
        if (rearmable && !dzHolding && !catching && phaseN == 1 && now - stillNs >= feel().deadzoneRearmMs * 1_000_000L) {
            // Stood still long enough: the cursor waits for the deadzone again, measured from here.
            dzHolding = true
            dzX = trX; dzY = trY
        }
        if (longN > 0 && !longOn && now - longT0 >= longPressNanos && map.has(longN, SurfaceInput.LONG_PRESS)) {
            longOn = true
            set(longN, SurfaceInput.LONG_PRESS, true)
        }
        when (state) {
            State.TAP_WAIT -> if (now >= tapDeadline) {
                set(1, SurfaceInput.TAP_DRAG, false); state = State.IDLE
            }
            // Tap then hold without moving: drag, the button is already held.
            State.TAP_TOUCH -> if (now - t0 > TAP_MAX_NS) {
                state = State.DRAG; startRun(primary)
            }

            State.MULTI -> if (tapCandidate && !multiTapOpen(now)) tapCandidate = false
            State.SCROLL_LIFT -> if (now - liftNs > LIFT_WINDOW_NS) {
                // The finger stayed: it moves the cursor from where it is now.
                rebase()
                state = State.MOVE; primary = firstUsed()
                startRun(primary)
            }

            else -> Unit
        }
        settlePendingTap(now)
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
        if (state == State.PINCH) {
            zoomPending = 0; endKeys(); endPinch()
        }
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
        else if (count == 1) {
            state = State.MOVE; primary = firstUsed(); startRun(primary)
        }
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
        // A latch of the same finger count goes on as this motion's held directions; another one lets go.
        var carried = 0
        if (latchMask != 0) {
            if (latchN == n) {
                carried = latchMask
                latchN = 0; latchMask = 0; latchUntil = Long.MAX_VALUE
            } else releaseLatch()
        }
        dirs.start(carried, latchZones)
        zoneCounts(n)
        swiped = false; moved = false
        trX = 0f; trY = 0f; winX = 0f; winY = 0f
        headed = false; waitX = 0f; waitY = 0f
        moving = 0; turnX = 0f; turnY = 0f; turnLen = 0f; holdStopped = false
        motionN = n
        dzHolding = n == 1 && state == State.MOVE && feel(n).cursorDeadzoneDp > 0f && map.motion[0].cursor
        rearmable = dzHolding && feel(n).deadzoneRearmMs > 0
        dzX = 0f; dzY = 0f
        stillX = centroidX(); stillY = centroidY(); stillNs = now
        wheelRem = 0f; hwheelRem = 0f
        velX = 0f; velY = 0f; velAccX = 0f; velAccY = 0f; velSinceNs = eventNs
        route(n, centroidX() - downCentroidX(), centroidY() - downCentroidY(), now, eventNs)
    }

    /** Fingers landed or lifted mid-motion: travel goes on from here (steps are per finger, so the centroid's jump is not travel). */
    private fun restartTravel() {
        originX = centroidX()
    }

    /**
     * Which of the step goes on to Move X and Y ([MotionFeel.modeX], [MoveMode]): a Move moves
     * while the heading of the last [MotionFeel.moveSampleDp] of travel is its way, always when
     * free. A motion's first sample waits for its heading, then goes to the Moves it heads for at
     * once, so none of it is lost. With [MotionFeel.moveLock] the Moves moving ([moving]) change
     * only once the heading has pointed elsewhere for [MotionFeel.moveTurnDp] on end; that travel
     * then goes to the Moves turned to. A Move moving now keeps going [MotionFeel.movePieMargin]
     * past its pie. Once a swipe hold pressed ([holdStopped]), nothing moves. Writes the step to
     * [moveX] / [moveY].
     */
    private fun gateMoves(sdx: Float, sdy: Float) {
        moveX = sdx; moveY = sdy
        val f = feel()
        if (holdStopped) {
            moveX = 0f; moveY = 0f
            return
        }
        if (f.free) return
        winX += sdx; winY += sdy
        val len = sqrt(winX * winX + winY * winY)
        val cap = f.moveSampleDp.coerceIn(TouchpadSettings.MIN_SAMPLE_DP, TouchpadSettings.MAX_SAMPLE_DP) * pxPerDp
        val known = headed || len >= cap
        if (len > cap) {
            winX *= cap / len; winY *= cap / len
        }
        if (!known) {
            // A free Move goes on at once; the others wait for the heading.
            if (f.modeX != MoveMode.FREE) { waitX += sdx; moveX = 0f }
            if (f.modeY != MoveMode.FREE) { waitY += sdy; moveY = 0f }
            return
        }
        val heading = (if (heads(f, true)) MOVE_X else 0) or (if (heads(f, false)) MOVE_Y else 0)
        if (!headed) {
            headed = true
            moving = heading
            moveX += waitX; moveY += waitY
            waitX = 0f; waitY = 0f
        } else if (heading == moving) {
            turnX = 0f; turnY = 0f; turnLen = 0f
        } else {
            val turn = if (f.moveLock) f.moveTurnDp.coerceIn(0f, TouchpadSettings.MAX_MOVE_TURN_DP) * pxPerDp else 0f
            turnX += sdx; turnY += sdy; turnLen += sqrt(sdx * sdx + sdy * sdy)
            if (turnLen >= turn) {
                // Turned: the Moves it turned to take the whole turn's travel.
                if (moving and MOVE_X == 0) moveX = turnX
                if (moving and MOVE_Y == 0) moveY = turnY
                moving = heading
                turnX = 0f; turnY = 0f; turnLen = 0f
            }
        }
        if (moving and MOVE_X == 0) moveX = 0f
        if (moving and MOVE_Y == 0) moveY = 0f
    }

    /**
     * The recent travel ([winX], [winY]) heads Move X's way ([x]) or Move Y's, as its mode has it;
     * one moving now ([moving]) by its pie widened by the margin.
     */
    private fun heads(f: MotionFeel, x: Boolean): Boolean {
        val ax = abs(winX)
        val ay = abs(winY)
        return when (if (x) f.modeX else f.modeY) {
            MoveMode.FREE -> true
            MoveMode.LARGER_AXIS -> if (x) ax >= ay else ay > ax
            MoveMode.PIE -> {
                // Off its axis, 0..90 degrees.
                val off = Math.toDegrees(atan2(if (x) ay else ax, if (x) ax else ay).toDouble()).toFloat()
                val pie = (if (x) f.pieX else f.pieY).coerceIn(TouchpadSettings.MIN_PIE_DEG, TouchpadSettings.MAX_PIE_DEG)
                val margin = if (moving and (if (x) MOVE_X else MOVE_Y) != 0) {
                    f.movePieMargin.coerceIn(0f, TouchpadSettings.MAX_MOVE_PIE_MARGIN)
                } else 0f
                off <= pie / 2f + margin
            }
        }
    }

    /** A mapped swipe hold of [n] fingers pressed this motion: with [MotionFeel.holdStopsMove], its Moves stop. */
    private fun stopMovesOnHold(n: Int) {
        if (holdStopped || !feel(n).holdStopsMove) return
        for (d in Direction.entries) {
            val z = dirs.zoneAt(d)
            if (z >= 0 && map.has(n, HOLDS[d.ordinal], z)) {
                holdStopped = true
                waitX = 0f; waitY = 0f
                return
            }
        }
    }

    private var moveX = 0f
    private var moveY = 0f

    // The motion's heading is known ([gateMoves]); until then, the travel Move X / Y wait with.
    private var headed = false
    private var waitX = 0f
    private var waitY = 0f

    // The Moves moving now ([MOVE_X], [MOVE_Y]); the travel of a turn to others not yet confirmed
    // (px, and its length); a swipe hold stopped the Moves until the lift.
    private var moving = 0
    private var turnX = 0f
    private var turnY = 0f
    private var turnLen = 0f
    private var holdStopped = false

    /**
     * The motion ends: its held directions let go (or, as the last finger lifts with the latch
     * on, stay held: [latchMask]), the last finger's lift is noted for swipe Releases ([liftedIn]), its
     * levels go back to 0, app switching ends.
     */
    private fun endPhase() {
        dzHolding = false
        rearmable = false
        val n = phaseN
        if (n in 1..4) {
            val lift = count == 0 && !cancelling
            var keep = 0
            if (lift) for (d in Direction.entries) if (settings.latchOf(d) && dirs.zones(d) != 0) keep = keep or (1 shl d.ordinal)
            if (keep != 0) {
                for (d in Direction.entries) latchZones[d.ordinal] = dirs.zones(d)
                latchN = n; latchMask = keep; latchUntil = Long.MAX_VALUE
            }
            holds(n, if (keep != 0) latchZones else null)
            if (lift) {
                liftN = n
                for (d in Direction.entries) liftZone[d.ordinal] = dirs.zoneAt(d)
                liftSeq++
            }
        }
        dirs.start()
        phaseN = 0
        levelX = 0f; levelY = 0f
        if (scrubbing) {
            scrubbing = false; endKeys()
        }
    }

    /**
     * One step of the [n]-finger motion: the centroid moved by [sdx], [sdy] px. Cursor and wheel
     * mappings follow the step; held directions, swipes, the floating stick and app switching the
     * travel since the motion started.
     */
    private fun route(n: Int, rawX: Float, rawY: Float, now: Long, eventNs: Long) {
        if (n !in 1..4) return
        val m = map.motion[n - 1]
        gateMoves(rawX, rawY)
        val sdx = moveX
        val sdy = moveY
        trX += sdx; trY += sdy
        track(sdx, sdy, now, eventNs)
        if (rearmable) {
            val sx = centroidX() - stillX;
            val sy = centroidY() - stillY
            val still = feel().deadzoneStillDp.coerceIn(0f, TouchpadSettings.MAX_STILL_DP) * pxPerDp
            if (sx * sx + sy * sy > still * still) {
                stillX = centroidX(); stillY = centroidY(); stillNs = now
            }
        }
        if (m.cursor) {
            if (dzHolding) passDeadzone(m, now, eventNs) else emit(run, sdx, sdy, eventNs, m)
        }
        if (m.scroll) scrollStep(sdx, sdy, m)
        val tx = trX / pxPerDp
        val ty = trY / pxPerDp
        levelX = (tx / STICK_FULL_DP).coerceIn(-1f, 1f)
        levelY = (ty / STICK_FULL_DP).coerceIn(-1f, 1f)
        zoneCounts(n)
        // Swipes and held directions judge their own way, by their pies.
        val fired = dirs.step(rawX / pxPerDp, rawY / pxPerDp, settings)
        holdZones(n)
        stopMovesOnHold(n)
        for (d in Direction.entries) if (fired and (1 shl d.ordinal) != 0) pulse(n, SWIPES[d.ordinal])
        val sideways = fired and (RIGHT_BIT or LEFT_BIT)
        if (fired != 0 && !swiped) {
            swiped = true
            if (sideways != 0 && m.switchApps) {
                // Alt held, first step now; every further step of travel moves the selection.
                scrubbing = true
                scrubBase = GestureActions.ALT shl 8
                enqueueKey(scrubBase)
                scrubStep(sideways and RIGHT_BIT != 0)
                originX = centroidX()
            }
        } else if (scrubbing) {
            scrub()
        }
    }

    /** Held directions of [n] fingers: exactly the zones in [zones] (by [Direction.ordinal], masks of 1 shl zone); null = none. */
    private fun holds(n: Int, zones: IntArray?) {
        for (d in Direction.entries) {
            val m = zones?.get(d.ordinal) ?: 0
            for (z in 0 until TouchpadSettings.MAX_HOLD_ZONES) set(n, HOLDS[d.ordinal], m and (1 shl z) != 0, z)
        }
    }

    /** Held directions of [n] fingers: the zones the motion holds now. */
    private fun holdZones(n: Int) {
        for (d in Direction.entries) {
            val m = dirs.zones(d)
            for (z in 0 until TouchpadSettings.MAX_HOLD_ZONES) set(n, HOLDS[d.ordinal], m and (1 shl z) != 0, z)
        }
    }

    /** The motion's directions have the zones [n] fingers have mapped. */
    private fun zoneCounts(n: Int) {
        for (d in Direction.entries) {
            dirs.zoneCount[d.ordinal] = map.zoneCount(n, d)
            dirs.releaseOnly[d.ordinal] = map.releaseOnly(n, d)
        }
    }

    /** Counts the lifts of the last finger after a motion ([liftedIn]). */
    var liftSeq = 0L
        private set
    private var liftN = 0
    private val liftZone = IntArray(4) { -1 }

    /**
     * The last lift ([liftSeq]) was of [fingers] with held direction [d] in its [zone] (the zone the
     * finger was in, FROM_START, or got to, DIRECTION_CHANGE): its swipe Release fires.
     */
    fun liftedIn(fingers: Int, d: Direction, zone: Int): Boolean =
        liftN == fingers && liftZone[d.ordinal] == zone

    /** The latched directions let go. */
    private fun releaseLatch() {
        if (latchMask == 0) return
        holds(latchN, null)
        latchN = 0; latchMask = 0; latchUntil = Long.MAX_VALUE
    }

    /** The fingers all lifted with a latch on: it lets go by itself after [TouchpadSettings.latchReleaseMs]. */
    private fun armLatchTimer(now: Long) {
        if (latchMask == 0 || latchUntil != Long.MAX_VALUE) return
        val ms = settings.latchReleaseMs.coerceIn(0, TouchpadSettings.MAX_LATCH_RELEASE_MS)
        if (ms > 0) latchUntil = now + ms * 1_000_000L
    }

    /**
     * Held in the cursor deadzone: once the travel from the touch-down point (along the one
     * axis mapped to the cursor, else in any direction) passes it, the cursor starts following
     * and makes up all the travel so far over the catch-up time.
     */
    private fun passDeadzone(m: SurfaceMap.Motion, now: Long, eventNs: Long) {
        val tx = trX - dzX
        val ty = trY - dzY
        val usesX = m.cxx != 0f || m.cyx != 0f
        val usesY = m.cxy != 0f || m.cyy != 0f
        val d = when {
            usesX && usesY -> sqrt(tx * tx + ty * ty)
            usesX -> abs(tx)
            else -> abs(ty)
        }
        if (d / pxPerDp < feel().cursorDeadzoneDp.coerceAtMost(TouchpadSettings.MAX_CURSOR_DEADZONE_DP)) return
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
        val dur = feel().catchUpMs.coerceIn(0, TouchpadSettings.MAX_CATCH_UP_MS) * 1_000_000L
        val f = if (dur <= 0L) 1f else ((now - catchT0).toFloat() / dur).coerceIn(0f, 1f)
        val step = f - catchSent
        catchSent = f
        catchLastNs = now
        if (step > 0f) {
            val inX = catchX * step / pxPerDp * feel().sensitivityX
            val inY = catchY * step / pxPerDp * feel().sensitivityY
            val fx = m.cxx * inX + m.cxy * inY + run.remX
            val ix = fx.toInt(); run.remX = fx - ix; dx += ix
            val fy = m.cyx * inX + m.cyy * inY + run.remY
            val iy = fy.toInt(); run.remY = fy - iy; dy += iy
        }
        if (f >= 1f) {
            catching = false; catchMotion = null
        }
    }

    /** Move X / Y took [sdx], [sdy] px: their velocity (for a fling), timed by [eventNs]. */
    private fun track(sdx: Float, sdy: Float, now: Long, eventNs: Long) {
        if (sdx != 0f || sdy != 0f) {
            lastMoveNs = now
            moved = true
        }
        velAccX += sdx; velAccY += sdy
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

    /** One step of motion mapped to the wheel. */
    private fun scrollStep(sdxPx: Float, sdyPx: Float, m: SurfaceMap.Motion) {
        val f = feel()
        // Natural: content follows the fingers (finger down = wheel up, finger right = pan left).
        val sx = sdxPx * f.scrollSpeedX * UNITS_PER_DP / pxPerDp * if (f.naturalScrollX) 1f else -1f
        val sy = sdyPx * f.scrollSpeedY * UNITS_PER_DP / pxPerDp * if (f.naturalScrollY) 1f else -1f
        addScroll(m.hx * sx + m.hy * sy, m.wx * sx + m.wy * sy)
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

    /**
     * Starts the glide, from [startNs], if the fingers lifted (at [at]) while still moving fast:
     * each Move X / Y with inertia ([MotionFeel.inertiaX]) goes on at its velocity, slowing down
     * by its own glide time and curve, through the motion's cursor and wheel mappings.
     */
    private fun fling(at: Long, startNs: Long = at) {
        val f = feel()
        // Still in the cursor deadzone, the cursor never moved: it does not glide either.
        if ((!f.inertiaX && !f.inertiaY) || dzHolding || at - lastMoveNs > FLING_MAX_PAUSE_NS) return
        val vx = if (f.inertiaX) velX else 0f
        val vy = if (f.inertiaY) velY else 0f
        if (sqrt(vx * vx + vy * vy) / pxPerDp < FLING_MIN_DP_PER_MS) return
        val m = map.motion[motionN.coerceIn(1, 4) - 1]
        if (!m.cursor && !m.scroll) return
        gliding = true
        glideMotion = m
        glideVx = vx; glideVy = vy
        glideNs = startNs
        glideTauX = f.glideMsX.coerceIn(TouchpadSettings.MIN_GLIDE_MS, TouchpadSettings.MAX_GLIDE_MS)
        glideTauY = f.glideMsY.coerceIn(TouchpadSettings.MIN_GLIDE_MS, TouchpadSettings.MAX_GLIDE_MS)
        glideLinearX = f.curveX == InertiaCurve.LINEAR
        glideLinearY = f.curveY == InertiaCurve.LINEAR
        // Linear: stops after 2·T, so it travels as far as the smooth one (v0·T).
        glideDecX = abs(vx) / (2f * glideTauX)
        glideDecY = abs(vy) / (2f * glideTauY)
        run.start(startNs)
    }

    /** One step of the glide: each axis's travel since the last, sent as Move X / Y's travel would be. */
    private fun glide(now: Long) {
        val dtMs = (now - glideNs) / 1e6f
        if (dtMs <= 0f) return
        glideNs = now
        val gx = glideStep(glideVx, glideTauX, glideLinearX, glideDecX, dtMs)
        glideVx = glideNext
        val gy = glideStep(glideVy, glideTauY, glideLinearY, glideDecY, dtMs)
        glideVy = glideNext
        val m = glideMotion
        if (m != null) {
            if (m.cursor) emit(run, gx, gy, now, m)
            if (m.scroll) scrollStep(gx, gy, m)
        }
        val stop = GLIDE_STOP_DP_PER_MS * pxPerDp
        if (m == null || (abs(glideVx) < stop && abs(glideVy) < stop)) {
            gliding = false; glideMotion = null
        }
    }

    /** The velocity after the last [glideStep]. */
    private var glideNext = 0f

    /**
     * Travel (px) over [dtMs] of one axis gliding at [v] (px per ms): smooth, v(t) = v·e^(−t/τ),
     * travel v·τ·(1 − e^(−dt/τ)); [linear], slowing by [dec] until it stops. Its new velocity goes to [glideNext].
     */
    private fun glideStep(v: Float, tau: Float, linear: Boolean, dec: Float, dtMs: Float): Float {
        if (v == 0f) {
            glideNext = 0f; return 0f
        }
        if (!linear) {
            val decay = exp(-dtMs / tau)
            glideNext = v * decay
            return v * tau * (1f - decay)
        }
        val speed = abs(v)
        val left = speed / dec // ms until it stops
        val t = min(dtMs, left)
        val next = speed - dec * t
        glideNext = if (t >= left) 0f else next * kotlin.math.sign(v)
        return (speed + next) / 2f * t * kotlin.math.sign(v)
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
        while (cx - originX >= step) {
            scrubStep(true); originX += step
        }
        while (originX - cx >= step) {
            scrubStep(false); originX -= step
        }
    }

    private fun scrubStep(forward: Boolean) {
        val mods = if (forward) 0 else GestureActions.SHIFT
        enqueueKey(scrubBase or (mods shl 8) or KEY_TAB)
        enqueueKey(scrubBase)
    }


    /** Hold with n fingers is held while exactly n background fingers are down. */
    private fun holdCount() {
        for (n in 1..4) if (n != count) set(n, SurfaceInput.HOLD, false)
        if (count in 1..4) set(count, SurfaceInput.HOLD, true)
    }

    /** Every background finger lifted: the touch's release. */
    @Suppress("UNUSED_PARAMETER")
    private fun touchEnded(now: Long) {
        val n = min(touchMax, 4)
        touchMax = 0
        if (n >= 1) pulse(n, SurfaceInput.RELEASE)
    }

    /**
     * Turns input [input] with [n] fingers on or off (on only if it is mapped: one on stays
     * free to go off after a mapping set change unmapped it).
     */
    private fun set(n: Int, input: SurfaceInput, on: Boolean, zone: Int = 0) {
        if (on && !map.has(n, input, zone)) return
        set(SurfaceMap.bit(n, input, zone), on)
    }

    /** Input bit [b] should be [on]: its change waits its turn to show. */
    private fun set(b: Int, on: Boolean) {
        val m = 1L shl (b and 63)
        if (b < 64) {
            val w = if (on) want0 or m else want0 and m.inv()
            if (w == want0) return
            want0 = w
        } else if (b < 128) {
            val w = if (on) want1 or m else want1 and m.inv()
            if (w == want1) return
            want1 = w
        } else {
            val w = if (on) want2 or m else want2 and m.inv()
            if (w == want2) return
            want2 = w
        }
        if (flipCount == FLIP_CAP) {
            // Never expected: the oldest change shows now, early, rather than one being lost.
            flipShown(flips[0], nowNs)
            System.arraycopy(flips, 1, flips, 0, --flipCount)
        }
        flips[flipCount++] = b
    }

    private fun shown(b: Int): Boolean {
        val w = when {
            b < 64 -> vis0
            b < 128 -> vis1
            else -> vis2
        }
        return w and (1L shl (b and 63)) != 0L
    }

    private fun flipShown(b: Int, now: Long) {
        val m = 1L shl (b and 63)
        when {
            b < 64 -> vis0 = vis0 xor m
            b < 128 -> vis1 = vis1 xor m
            else -> vis2 = vis2 xor m
        }
        shownSince[b] = now
    }

    /** When input [b]'s next change may show: its own and its peers' last change have shown the minimum pulse. */
    private fun dueAt(b: Int): Long {
        var t = shownSince[b]
        val p = map.peers[b]
        if (p != null) for (k in 0 until 3) {
            var w = p[k]
            while (w != 0L) {
                val i = java.lang.Long.numberOfTrailingZeros(w); w = w and (w - 1)
                t = max(t, shownSince[(k shl 6) + i])
            }
        }
        return t + minPulseNanos
    }

    /** Shows every waiting change that is due, in order: one that waits holds back its input's and its peers' later ones. */
    private fun showDue(now: Long) {
        blocked[0] = 0L; blocked[1] = 0L; blocked[2] = 0L
        var kept = 0
        for (i in 0 until flipCount) {
            val b = flips[i]
            if (blocked[b shr 6] and (1L shl (b and 63)) == 0L && dueAt(b) <= now) {
                flipShown(b, now)
                continue
            }
            flips[kept++] = b
            blocked[b shr 6] = blocked[b shr 6] or (1L shl (b and 63))
            val p = map.peers[b]
            if (p != null) for (k in 0 until 3) blocked[k] = blocked[k] or p[k]
        }
        flipCount = kept
    }

    /** A short press of [input], visible for at least the minimum pulse. */
    private fun pulse(n: Int, input: SurfaceInput, zone: Int = 0) {
        set(n, input, true, zone)
        set(n, input, false, zone)
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
        for (i in 0 until MAX_FINGERS) {
            used[i] = false; aimUsed[i] = false
        }
        count = 0
        aimCount = 0
        state = State.IDLE
        tapCandidate = false
        run.remX = 0f; run.remY = 0f; wheelRem = 0f; hwheelRem = 0f
        gliding = false; glideMotion = null
        zoomRem = 0f; zoomPending = 0
        flipCount = 0
        kSize = 0
        // What is shown stays until [cancel] / [reset] let it go.
        want0 = vis0; want1 = vis1; want2 = vis2
        phaseN = 0; levelX = 0f; levelY = 0f; pinchLevel = 0f
        scrubbing = false; moved = false; touchMax = 0
        dzHolding = false; catching = false; catchMotion = null; rearmable = false
        pendingTapN = 0; lastTapN = 0; longN = 0; longOn = false; doubleHeld = false
        dirs.start(); latchN = 0; latchMask = 0; latchUntil = Long.MAX_VALUE
    }

    /**
     * A tap with [n] fingers (the touch already back in IDLE). One finger with a tap-drag mapping:
     * held while a second touch may follow (a drag, or a double tap). Else a tap with the same
     * fingers that started within the double-tap window after the last one's lift is a double
     * tap (and not a tap, when a double tap is mapped); a tap whose double tap does something else
     * waits out that window ([SurfaceMap.tapWaits]).
     */
    private fun tapped(n: Int, now: Long) {
        if (n == 1 && map.has(1, SurfaceInput.TAP_DRAG)) {
            set(1, SurfaceInput.TAP_DRAG, true)
            pulse(1, SurfaceInput.TAP)
            state = State.TAP_WAIT
            tapDeadline = now + doubleTapNanos
            return
        }
        if (map.has(n, SurfaceInput.DOUBLE_TAP) && n == lastTapN && t0 - lastTapNs <= doubleTapNanos) {
            pendingTapN = 0
            lastTapN = 0
            pulse(n, SurfaceInput.DOUBLE_TAP)
            return
        }
        firePendingTap()
        lastTapN = n; lastTapNs = now
        if (map.tapWaits(n)) pendingTapN = n else pulse(n, SurfaceInput.TAP)
    }

    /** The waiting tap fires now. */
    private fun firePendingTap() {
        if (pendingTapN == 0) return
        pulse(pendingTapN, SurfaceInput.TAP)
        pendingTapN = 0
    }

    /**
     * A waiting tap fires once its window has passed, unless a touch that started inside it may
     * still be its double tap (still a tap candidate).
     */
    private fun settlePendingTap(now: Long) {
        if (pendingTapN == 0) return
        val candidate = count > 0 && t0 - lastTapNs <= doubleTapNanos &&
                ((state == State.TOUCH && now - t0 <= TAP_MAX_NS) || (state == State.MULTI && multiTapOpen(now)))
        if (!candidate && now - lastTapNs > doubleTapNanos) firePendingTap()
    }

    /** Ends a long press and arms one for [n] fingers from [now] (0 = none). */
    private fun armLong(n: Int, now: Long) {
        if (longOn) {
            set(longN, SurfaceInput.LONG_PRESS, false); longOn = false
        }
        longN = if (n in 1..4) n else 0
        longT0 = now
    }

    private fun enqueueKey(packed: Int) {
        val last = if (kSize > 0) kQueue[(kHead + kSize - 1) % KEY_QUEUE_CAP] else kVisible
        if (packed == last) return
        if (kSize == KEY_QUEUE_CAP) kQueue[(kHead + kSize - 1) % KEY_QUEUE_CAP] = packed
        else {
            kQueue[(kHead + kSize) % KEY_QUEUE_CAP] = packed; kSize++
        }
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
        r.speed =
            if (r.fresh || raw > MAX_STEP_NS) inst else r.speed + (1f - exp(-dtMs / SPEED_TAU_MS)) * (inst - r.speed)
        r.fresh = false
        val f = feel()
        val fast = ((r.speed - ACCEL_LOW_DP_PER_MS) / (ACCEL_HIGH_DP_PER_MS - ACCEL_LOW_DP_PER_MS)).coerceIn(0f, 1f)
        val inX = ddx * f.sensitivityX * (1f + f.accelerationX * fast)
        val inY = ddy * f.sensitivityY * (1f + f.accelerationY * fast)
        val fx = (if (m == null) inX else m.cxx * inX + m.cxy * inY) + r.remX
        val ix = fx.toInt()
        r.remX = fx - ix
        dx += ix
        val fy = (if (m == null) inY else m.cyx * inX + m.cyy * inY) + r.remY
        val iy = fy.toInt()
        r.remY = fy - iy
        dy += iy
    }

    private fun centroidX(): Float {
        var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += lastX[i]; return s / max(count, 1)
    }

    private fun centroidY(): Float {
        var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += lastY[i]; return s / max(count, 1)
    }

    private fun downCentroidX(): Float {
        var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += downX[i]; return s / max(count, 1)
    }

    private fun downCentroidY(): Float {
        var s = 0f; for (i in 0 until MAX_FINGERS) if (used[i]) s += downY[i]; return s / max(count, 1)
    }

    private fun centroidTravelDp(): Float {
        val tx = centroidX() - downCentroidX()
        val ty = centroidY() - downCentroidY()
        return sqrt(tx * tx + ty * ty) / pxPerDp
    }

    /** Distance between the first two fingers, now / at their down points (px). */
    private fun spread(): Float = pairDistance(lastX, lastY)
    private fun downSpread(): Float = pairDistance(downX, downY)

    private fun pairDistance(xs: FloatArray, ys: FloatArray): Float {
        var a = -1;
        var b = -1
        for (i in 0 until MAX_FINGERS) if (used[i]) {
            if (a < 0) a = i else if (b < 0) b = i
        }
        if (b < 0) return 0f
        val ddx = xs[a] - xs[b];
        val ddy = ys[a] - ys[b]
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
        if (aimSlotOf(pointer) >= 0 || slotOf(pointer) >= 0) {
            move(pointer, x, y, now, eventNs); return
        }
        if (!settings.enabled) return
        val a = aimUsed.indexOf(false)
        if (a < 0) return
        if (state == State.TAP_WAIT) {
            set(1, SurfaceInput.TAP_DRAG, false); state = State.IDLE
        }
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

        /** Three/four-finger centroid travel per further Alt+Tab step after the first. */

        /** Travel (or pinch spread change) that is a full floating-stick level; pinch in / out press at half of it. */
        const val STICK_FULL_DP = 40f

        /** Axis locks judge a step's direction by about this much of the latest travel. */

        /** Held directions and swipes by [Direction.ordinal]. */
        private val HOLDS =
            arrayOf(SurfaceInput.HOLD_UP, SurfaceInput.HOLD_RIGHT, SurfaceInput.HOLD_DOWN, SurfaceInput.HOLD_LEFT)
        private val SWIPES =
            arrayOf(SurfaceInput.SWIPE_UP, SurfaceInput.SWIPE_RIGHT, SurfaceInput.SWIPE_DOWN, SurfaceInput.SWIPE_LEFT)
        private const val MOVE_X = 1
        private const val MOVE_Y = 2
        private val RIGHT_BIT = 1 shl Direction.RIGHT.ordinal
        private val LEFT_BIT = 1 shl Direction.LEFT.ordinal
        const val SCRUB_STEP_DP = 80f

        /** A lift within this of the last scroll movement, faster than the minimum, flings. */
        const val FLING_MAX_PAUSE_NS = 60_000_000L
        const val FLING_MIN_DP_PER_MS = 0.25f
        /** A glide stops below this speed, each axis (dp per ms: 1/400 notch per ms at scroll speed 1). */
        const val GLIDE_STOP_DP_PER_MS = 0.01f
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
        const val DEFAULT_DOUBLE_TAP_NS = Workspace.DEFAULT_DOUBLE_TAP_MS * 1_000_000L
        const val DEFAULT_LONG_PRESS_NS = Workspace.DEFAULT_LONG_PRESS_MS * 1_000_000L
        const val MAX_FINGERS = 4

        /** Input changes that may wait to show at once. */
        private const val FLIP_CAP = 64
        private const val KEY_QUEUE_CAP = 16
        private const val KEY_TAB = 0x2B


        /** Shortest press/release that a pump ticking every [intervalNanos] cannot miss. */
        fun minPulseFor(intervalNanos: Long): Long = max(DEFAULT_MIN_PULSE_NS, intervalNanos + 12_000_000L)
    }
}
