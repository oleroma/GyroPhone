package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.FingerGestures
import com.oleroma.gyrophone.core.model.GestureAction
import com.oleroma.gyrophone.core.model.GestureActions
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.TouchpadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L
private val L = 1 shl MouseButton.LEFT.bit
private val R = 1 shl MouseButton.RIGHT.bit

private val NO_GLIDE = TouchpadSettings(acceleration = 0f, inertia = false)
private const val CTRL = GestureActions.CTRL shl 8
private const val ALT = GestureActions.ALT shl 8
private const val SHIFT = GestureActions.SHIFT shl 8
private const val WIN = GestureActions.WIN shl 8
private const val TAB = 0x2B
private val M = 1 shl MouseButton.MIDDLE.bit

private fun pad(s: TouchpadSettings = TouchpadSettings(acceleration = 0f)) =
    Touchpad(s).apply { pxPerDp = 1f; minPulseNanos = 40 * MS }

/** Collapses consecutive duplicates: [0,0,1,1,0] -> [0,1,0]. */
private fun List<Int>.edges(): List<Int> = filterIndexed { i, v -> i == 0 || this[i - 1] != v }

/** Lengths (in polls) of each run of equal values. */
private fun List<Int>.runs(): List<Int> {
    val out = ArrayList<Int>()
    for (i in indices) if (i == 0 || this[i - 1] != this[i]) out.add(1) else out[out.size - 1]++
    return out
}

/** Drives a [Touchpad] like a 200 Hz pump: polls every [stepMs] up to each event, recording everything. */
private class Driver(val pad: Touchpad, val stepMs: Long = 5) {
    var t = 0L
    val buttons = ArrayList<Int>()
    val times = ArrayList<Long>()
    var dx = 0
    var dy = 0
    var wheel = 0
    var hwheel = 0
    /** Visible key state per poll: modifiers shl 8 or usage. */
    val keys = ArrayList<Int>()
    /** dx of each poll. */
    val dxs = ArrayList<Int>()
    /** Wheel of each poll. */
    val wheels = ArrayList<Int>()
    /** Wheel that arrived while Ctrl was not visible. */
    var wheelWithoutCtrl = 0
    /** dx while the left button was visible. */
    var dxWhileLeft = 0

    /** Polls from the current time up to (not including) [ms], then advances the time to [ms]. */
    fun until(ms: Long) {
        while (t < ms * MS) { poll(); t += stepMs * MS }
        t = ms * MS
    }

    fun poll() {
        val b = pad.poll(t)
        buttons.add(b); times.add(t)
        dx += pad.dx; dy += pad.dy; wheel += pad.wheel; hwheel += pad.hwheel
        dxs.add(pad.dx); wheels.add(pad.wheel)
        keys.add((pad.keyModifiers shl 8) or pad.key)
        if (pad.keyModifiers and GestureActions.CTRL == 0) wheelWithoutCtrl += pad.wheel
        if (b and L != 0) dxWhileLeft += pad.dx
        pad.drainMotion()
    }

    fun down(ms: Long, x: Float, y: Float, id: Long = 1) { until(ms); pad.down(id, x, y, t) }
    /** A touch button's finger driving the cursor (click-through, touchpad over buttons). */
    fun aim(ms: Long, x: Float, y: Float, id: Long) { until(ms); pad.down(id, x, y, t, aiming = true) }
    fun move(ms: Long, x: Float, y: Float, id: Long = 1) { until(ms); pad.move(id, x, y, t) }
    fun up(ms: Long, id: Long = 1) { until(ms); pad.up(id, t) }

    /** Time (ms) of the first poll that returned [b] at or after [fromMs]. */
    fun firstAt(b: Int, fromMs: Long = 0): Long =
        times.indices.first { times[it] >= fromMs * MS && buttons[it] == b }.let { times[it] / MS }
}

class TouchpadTest {
    @Test fun tapClicksAfterDragWindow() {
        val d = Driver(pad())
        d.down(0, 100f, 100f); d.up(80); d.until(600)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
        assertEquals(80L, d.firstAt(L))
        assertTrue(d.firstAt(0, 80) >= 80 + 220)
    }

    @Test fun tapWithoutTapDragIsShortPulse() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f, tapDrag = false)))
        d.down(0, 100f, 100f); d.up(80); d.until(600)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
        val pressed = d.firstAt(L); val released = d.firstAt(0, pressed)
        assertTrue(released - pressed >= 40)
        assertTrue(released <= 80 + 60)
    }

    private fun doubleTap(jitter: Boolean): Driver {
        val d = Driver(pad())
        d.down(0, 100f, 100f)
        if (jitter) { d.move(20, 105f, 100f); d.move(40, 100f, 104f) }
        d.up(60)
        d.down(120, 102f, 101f)
        if (jitter) { d.move(140, 97f, 101f); d.move(150, 102f, 96f) }
        d.up(170)
        d.until(700)
        return d
    }

    @Test fun doubleTapIsTwoClicks() {
        val d = doubleTap(jitter = false)
        assertEquals(listOf(0, L, 0, L, 0), d.buttons.edges())
        val runs = d.buttons.runs()
        for (i in 1..3) assertTrue("segment $i: ${runs[i]} polls", runs[i] >= 8)
        val first = d.firstAt(L)
        val second = d.firstAt(L, d.firstAt(0, first))
        assertTrue(second - first < 500)
        assertEquals(0, d.dx); assertEquals(0, d.dy)
    }

    @Test fun doubleTapNoMotionWithinSlop() {
        val d = doubleTap(jitter = true)
        assertEquals(listOf(0, L, 0, L, 0), d.buttons.edges())
        assertEquals(0, d.dx); assertEquals(0, d.dy)
    }

    @Test fun tapThenTouchAndMoveDrags() {
        val d = Driver(pad())
        d.down(0, 100f, 100f); d.up(60)
        d.down(120, 100f, 100f)
        for (i in 1..10) d.move(120 + i * 10L, 100f + i * 10f, 100f)
        val lift = 300L
        d.up(lift); d.until(700)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
        // Held throughout the moves.
        for (i in d.times.indices) {
            val ms = d.times[i] / MS
            if (ms in 60 until lift) assertEquals("at $ms ms", L, d.buttons[i])
        }
        assertTrue(d.dxWhileLeft > 0)
        assertEquals(150, d.dx)
        assertTrue(d.firstAt(0, 60) in lift..lift + 50)
    }

    @Test fun tapThenHoldBecomesDrag() {
        val d = Driver(pad())
        d.down(0, 100f, 100f); d.up(60)
        d.down(120, 100f, 100f)
        d.move(400, 130f, 100f)
        d.up(500); d.until(900)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
        assertTrue(d.firstAt(0, 60) >= 500)
        assertTrue(d.dx > 0)
    }

    @Test fun moveIsNotATap() {
        val d = Driver(pad())
        d.down(0, 0f, 0f)
        for (i in 1..10) d.move(i * 10L, i * 10f, 0f)
        d.up(110); d.until(600)
        assertTrue(d.buttons.all { it == 0 })
        assertEquals((100 * 1.5).toInt(), d.dx)
    }

    @Test fun longPressIsNotATap() {
        val d = Driver(pad())
        d.down(0, 0f, 0f); d.up(300); d.until(800)
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun subCountRemainderAccumulates() {
        val d = Driver(pad(TouchpadSettings(sensitivity = 1f, acceleration = 0f)))
        d.down(0, 0f, 0f)
        d.move(10, 15f, 0f)
        var x = 15f
        for (i in 1..100) { x += 0.3f; d.move(10 + i * 2L, x, 0f) }
        d.up(300); d.until(400)
        assertTrue("dx ${d.dx} vs travel $x", kotlin.math.abs(d.dx - x.toInt()) <= 1)
    }

    private fun swipe(accel: Float, spacingMs: Long): Int {
        val d = Driver(pad(TouchpadSettings(acceleration = accel)), stepMs = 1)
        d.down(0, 0f, 0f)
        for (i in 1..10) d.move(i * spacingMs, i * 20f, 0f)
        d.up(10 * spacingMs + 1); d.until(10 * spacingMs + 10)
        return d.dx
    }

    @Test fun accelerationBoostsFastSwipes() {
        assertTrue(swipe(1f, 1) > swipe(1f, 50))
        assertEquals(swipe(0f, 1), swipe(0f, 50))
        assertEquals(300, swipe(0f, 50))
    }

    @Test fun twoFingerTapRightClicks() {
        val d = Driver(pad())
        d.down(0, 100f, 100f, id = 1)
        d.down(20, 200f, 100f, id = 2)
        d.up(90, id = 1); d.up(100, id = 2); d.until(600)
        assertEquals(listOf(0, R, 0), d.buttons.edges())
        assertTrue(d.buttons.none { it and L != 0 })
    }

    private fun scroll(s: TouchpadSettings): Driver {
        val d = Driver(pad(s))
        d.down(0, 100f, 100f, id = 1)
        d.down(5, 200f, 100f, id = 2)
        for (i in 1..24) {
            d.move(5 + i * 10L, 100f, 100f + i * 10f, id = 1)
            d.move(5 + i * 10L + 2, 200f, 100f + i * 10f, id = 2)
        }
        d.up(300, id = 1); d.up(302, id = 2); d.until(700)
        return d
    }

    @Test fun twoFingerScrollEmitsHighResolutionWheel() {
        // 240 dp of travel = 10 notches = 1200 units.
        val d = scroll(NO_GLIDE)
        assertEquals(1200, d.wheel)
        assertEquals(0, d.hwheel)
        assertTrue(d.buttons.all { it == 0 })
        assertEquals(0, d.dx); assertEquals(0, d.dy)
        assertEquals(-1200, scroll(NO_GLIDE.copy(naturalScroll = false)).wheel)
        assertEquals(2400, scroll(NO_GLIDE.copy(scrollSpeed = 2f)).wheel)
        val off = scroll(NO_GLIDE.copy(twoFingerScroll = false))
        assertEquals(0, off.wheel)
        assertTrue(off.buttons.all { it == 0 })
    }

    @Test fun fingerAddedMidMoveScrollsOnlyFromItsDown() {
        val d = Driver(pad(NO_GLIDE))
        d.down(0, 100f, 100f, id = 1)
        for (i in 1..30) d.move(i * 5L, 100f, 100f + i * 10f, id = 1) // cursor 300 px down
        d.down(160, 200f, 400f, id = 2)
        for (i in 1..4) {
            d.move(160 + i * 10L, 100f, 400f + i * 12f, id = 1)
            d.move(160 + i * 10L + 2, 200f, 400f + i * 12f, id = 2)
        }
        d.up(220, id = 1); d.up(222, id = 2); d.until(500)
        // 48 px of two-finger travel = 2 notches; the 300 px cursor swipe adds none.
        assertEquals(240, d.wheel)
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun pressIsReturnedEvenIfPolledLate() {
        val p = pad()
        p.down(1, 0f, 0f, 0); p.up(1, 60 * MS)
        assertEquals(L, p.poll(1000 * MS))
        assertEquals(L, p.poll(1005 * MS))
        assertEquals(L, p.poll(1035 * MS))
        assertEquals(0, p.poll(1040 * MS))
    }

    @Test fun pulseNeedsAPollBetweenTransitions() {
        val p = pad(TouchpadSettings(acceleration = 0f, tapDrag = false))
        p.down(1, 0f, 0f, 0); p.up(1, 60 * MS)
        assertEquals(listOf(L, 0, 0), listOf(1000L, 2000L, 3000L).map { p.poll(it * MS) })
    }

    private fun dragging(): Driver {
        val d = Driver(pad())
        d.down(0, 100f, 100f); d.up(60)
        d.down(120, 100f, 100f)
        d.move(140, 150f, 100f)
        d.until(200)
        return d
    }

    @Test fun cancelReleasesHeldDrag() {
        val d = dragging()
        assertEquals(L, d.buttons.last())
        d.pad.cancel(d.t)
        assertEquals(0, d.pad.fingers)
        d.until(400)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
        // L lasted its minimum pulse: visible since 60 ms, so the release is immediate at 200 ms.
        assertEquals(200L, d.firstAt(0, 60))
    }

    @Test fun cancelHoldsFreshPressForMinimumPulse() {
        val p = pad()
        p.down(1, 0f, 0f, 0); p.up(1, 60 * MS)
        assertEquals(L, p.poll(65 * MS))
        p.cancel(70 * MS)
        assertEquals(L, p.poll(75 * MS))
        assertEquals(L, p.poll(100 * MS))
        assertEquals(0, p.poll(105 * MS))
    }

    @Test fun cancelDropsUnshownClick() {
        val d = Driver(pad())
        d.pad.down(1, 0f, 0f, 0); d.pad.up(1, 60 * MS)
        d.pad.cancel(61 * MS)
        d.t = 61 * MS
        d.until(600)
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun resetClearsAtOnce() {
        val d = dragging()
        d.pad.move(1, 300f, 100f, d.t)
        assertEquals(L, d.pad.poll(d.t))
        d.pad.move(1, 400f, 100f, d.t + MS)
        d.pad.reset()
        assertEquals(0, d.pad.dx); assertEquals(0, d.pad.wheel)
        assertEquals(0, d.pad.poll(d.t + 2 * MS))
        assertEquals(0, d.pad.fingers)
        assertEquals(Long.MAX_VALUE, d.pad.nextDeadline())
    }

    @Test fun disabledIgnoresTouches() {
        val d = Driver(pad(TouchpadSettings(enabled = false)))
        d.down(0, 0f, 0f); d.move(50, 100f, 0f); d.up(60)
        d.down(100, 0f, 0f); d.up(120); d.until(600)
        assertTrue(d.buttons.all { it == 0 })
        assertEquals(0, d.dx)
        assertEquals(0, d.pad.fingers)
    }

    @Test fun tapToClickOffStillMoves() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f, tapToClick = false)))
        d.down(0, 0f, 0f); d.up(60)
        d.down(200, 0f, 0f); d.move(220, 40f, 0f); d.up(240); d.until(600)
        assertTrue(d.buttons.all { it == 0 })
        assertEquals(60, d.dx)
    }

    @Test fun nextDeadlineTracksPendingRelease() {
        val p = pad()
        assertEquals(Long.MAX_VALUE, p.nextDeadline())
        p.down(1, 0f, 0f, 0); p.up(1, 60 * MS)
        assertTrue(p.nextDeadline() <= 60 * MS) // press pending: poll now
        assertEquals(L, p.poll(65 * MS))
        // Press shown; the release waits for the tap-drag window.
        assertEquals(60 * MS + Touchpad.TAP_DRAG_WINDOW_NS, p.nextDeadline())
        assertEquals(0, p.poll(280 * MS))
        assertEquals(Long.MAX_VALUE, p.nextDeadline())

        val q = pad(TouchpadSettings(acceleration = 0f, tapDrag = false))
        q.down(1, 0f, 0f, 0); q.up(1, 60 * MS)
        assertEquals(L, q.poll(65 * MS))
        assertEquals(65 * MS + 40 * MS, q.nextDeadline())
        assertEquals(0, q.poll(105 * MS))
        assertEquals(Long.MAX_VALUE, q.nextDeadline())
    }

    @Test fun minPulseForRates() {
        assertEquals(1_000_000_000L / 30 + 12 * MS, Touchpad.minPulseFor(1_000_000_000L / 30))
        assertEquals(40 * MS, Touchpad.minPulseFor(1_000_000_000L / 90))
    }

    @Test fun unknownPointersAndFullSlotsAreIgnored() {
        val p = pad()
        p.move(9, 10f, 10f, 0); p.up(9, 0)
        for (i in 1..Touchpad.MAX_FINGERS + 2) p.down(i.toLong(), i * 50f, 0f, i * MS)
        assertEquals(Touchpad.MAX_FINGERS, p.fingers)
        for (i in 1..Touchpad.MAX_FINGERS + 2) p.up(i.toLong(), 20 * MS)
        assertEquals(0, p.fingers)
    }

    // ---- high-resolution scroll, inertia, pinch, gestures ----

    /** Fingers [ids] at x = 100, 200, ... moving together by (stepX, stepY) px every 10 ms. */
    private fun Driver.together(ids: List<Long>, from: Long, steps: Int, stepX: Float, stepY: Float, startY: Float = 300f) {
        for ((k, id) in ids.withIndex()) down(from + k, 100f + k * 100f, startY, id)
        for (i in 1..steps) for ((k, id) in ids.withIndex()) {
            move(from + 10 + i * 10L + k, 100f + k * 100f + i * stepX, startY + i * stepY, id)
        }
    }

    @Test fun firstMillimetreAlreadyScrolls() {
        val d = Driver(pad(NO_GLIDE))
        d.down(0, 100f, 100f, id = 1); d.down(2, 200f, 100f, id = 2)
        d.until(200) // past the tap time: any travel scrolls
        d.move(200, 100f, 102f, id = 1); d.move(201, 200f, 102f, id = 2)
        d.until(210)
        assertEquals(10, d.wheel) // 2 dp = 10 units, no notch threshold
    }

    @Test fun horizontalScrollFollowsFingers() {
        val d = Driver(pad(NO_GLIDE))
        d.together(listOf(1L, 2L), 0, 10, 10f, 0f)
        d.up(200, 1); d.up(201, 2); d.until(400)
        // Natural: fingers right = content right = pan left.
        assertEquals(-500, d.hwheel)
        assertEquals(0, d.wheel)
        val off = Driver(pad(NO_GLIDE.copy(horizontalScroll = false)))
        off.together(listOf(1L, 2L), 0, 10, 10f, 0f)
        off.up(200, 1); off.up(201, 2); off.until(400)
        assertEquals(0, off.hwheel)
    }

    private fun fling(s: TouchpadSettings = TouchpadSettings(acceleration = 0f)): Driver {
        val d = Driver(pad(s))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f) // 1.5 dp/ms
        d.up(115, 1); d.up(116, 2)
        return d
    }

    @Test fun flingGlidesAndStops() {
        val d = fling()
        val atLift = d.wheel
        assertEquals(750, atLift) // 150 dp
        d.until(130)
        assertTrue(d.wheel > atLift)
        assertTrue(d.pad.nextDeadline() < Long.MAX_VALUE)
        d.until(4000)
        val glide = d.wheel - atLift
        // v ≈ 7.5 units/ms, τ = 325 ms: about 2400 units.
        assertTrue("glide $glide", glide in 1500..3200)
        assertEquals(Long.MAX_VALUE, d.pad.nextDeadline())
        val before = d.wheel
        d.until(5000)
        assertEquals(before, d.wheel)
        assertEquals(0, fling(NO_GLIDE).apply { until(4000) }.wheel - 750)
    }

    @Test fun touchStopsGlide() {
        val d = fling()
        d.until(150)
        d.down(150, 500f, 500f, id = 9)
        d.until(160)
        val w = d.wheel
        d.until(1000)
        assertEquals(w, d.wheel)
    }

    @Test fun pauseBeforeLiftDoesNotFling() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f)
        d.up(300, 1); d.up(301, 2); d.until(1500)
        assertEquals(750, d.wheel)
    }

    @Test fun staggeredLiftStillFlingsAndLastFingerDoesNotMoveCursor() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f)
        d.up(115, 1)
        d.move(150, 210f, 460f, id = 2)
        d.up(170, 2); d.until(2000)
        assertTrue(d.wheel > 1500)
        assertEquals(0, d.dx); assertEquals(0, d.dy)
    }

    @Test fun wobbleAfterAStopDoesNotFling() {
        // Scroll at 1.5 dp/ms, hold still for 150 ms, then lift one finger; the other wobbles 1 px as it goes.
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f)
        d.up(261, 1)
        d.move(280, 200f, 451f, id = 2)
        d.up(301, 2); d.until(2000)
        assertTrue("${d.wheel}", d.wheel in 750..760) // the scroll plus the 1 px wobble, no glide
        assertEquals(0, d.dx); assertEquals(0, d.dy)
    }

    @Test fun remainingFingerThatScrolledThenStoppedDoesNotFling() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f)
        d.up(115, 1)
        for (i in 11..14) d.move(11 + i * 10L, 200f, 300f + i * 15f, id = 2)
        d.up(225, 2); d.until(2000) // still for 74 ms, lifted inside the lift window
        assertEquals(1050, d.wheel) // 150 dp with two fingers plus 60 dp with one, no glide
    }

    @Test fun remainingFingerMovesCursorAfterLiftWindow() {
        val d = Driver(pad(NO_GLIDE))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f)
        d.up(115, 1)
        d.until(300)
        d.move(300, 260f, 450f, id = 2)
        d.up(310, 2); d.until(600)
        assertEquals(90, d.dx) // 60 dp at sensitivity 1.5
    }

    @Test fun pinchHoldsCtrlAroundZoomWheel() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.down(0, 200f, 300f, id = 1); d.down(2, 300f, 300f, id = 2)
        for (i in 1..10) {
            d.move(10 + i * 10L, 200f - i * 4f, 300f, id = 1)
            d.move(11 + i * 10L, 300f + i * 4f, 300f, id = 2)
        }
        d.up(130, 1); d.up(131, 2); d.until(600)
        assertEquals(240, d.wheel) // 80 dp of spread at 40 dp per notch: zoom in 2 notches
        assertEquals(0, d.wheelWithoutCtrl)
        assertEquals(listOf(0, CTRL, 0), d.keys.edges())
        assertEquals(0, d.hwheel)
        val closing = Driver(pad(TouchpadSettings(acceleration = 0f)))
        closing.down(0, 100f, 300f, id = 1); closing.down(2, 400f, 300f, id = 2)
        for (i in 1..10) {
            closing.move(10 + i * 10L, 100f + i * 4f, 300f, id = 1)
            closing.move(11 + i * 10L, 400f - i * 4f, 300f, id = 2)
        }
        closing.up(130, 1); closing.up(131, 2); closing.until(600)
        assertEquals(-240, closing.wheel)
        assertEquals(0, closing.wheelWithoutCtrl)
    }

    @Test fun pinchOffScrollsInstead() {
        val d = Driver(pad(NO_GLIDE.copy(pinchZoom = false)))
        d.down(0, 200f, 300f, id = 1); d.down(2, 300f, 300f, id = 2)
        for (i in 1..10) {
            d.move(10 + i * 10L, 200f - i * 4f, 300f, id = 1)
            d.move(11 + i * 10L, 300f + i * 4f, 300f, id = 2)
        }
        d.up(130, 1); d.up(131, 2); d.until(600)
        assertTrue(d.keys.all { it == 0 })
    }

    @Test fun threeFingerSwipeUpIsTaskView() {
        val d = Driver(pad())
        d.together(listOf(1L, 2L, 3L), 0, 8, 0f, -10f)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.until(600)
        assertEquals(listOf(0, WIN or TAB, 0), d.keys.edges())
        assertTrue(d.buttons.all { it == 0 })
        assertEquals(0, d.wheel); assertEquals(0, d.dx)
        // Each key state is held for the minimum pulse.
        assertTrue(d.keys.runs().drop(1).dropLast(1).all { it * 5 >= 40 })
    }

    @Test fun threeFingerSwipeDownIsShowDesktop() {
        val d = Driver(pad())
        d.together(listOf(1L, 2L, 3L), 0, 8, 0f, 10f)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.until(600)
        assertEquals(listOf(0, WIN or 0x07, 0), d.keys.edges())
    }

    @Test fun threeFingerSidewaysScrubsAltTab() {
        val d = Driver(pad())
        // Right 50 dp: Alt held, Tab. Then another 90 dp right: Tab again. Then 100 dp back left: Shift+Tab.
        d.together(listOf(1L, 2L, 3L), 0, 5, 10f, 0f)
        for (i in 1..9) for (k in 0..2) d.move(70 + i * 10L + k, 150f + k * 100f + i * 10f, 300f, (k + 1).toLong())
        for (i in 1..10) for (k in 0..2) d.move(170 + i * 10L + k, 240f + k * 100f - i * 10f, 300f, (k + 1).toLong())
        d.until(500)
        assertEquals(ALT, d.keys.last()) // still held while the fingers stay down
        d.up(500, 1); d.up(501, 2); d.up(502, 3); d.until(900)
        assertEquals(listOf(0, ALT, ALT or TAB, ALT, ALT or TAB, ALT, ALT or SHIFT or TAB, ALT, 0), d.keys.edges())
    }

    @Test fun fourFingerSwipesSwitchDesktops() {
        val d = Driver(pad())
        d.together(listOf(1L, 2L, 3L, 4L), 0, 8, -10f, 0f)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.up(203, 4); d.until(600)
        assertEquals(listOf(0, CTRL or WIN or 0x50, 0), d.keys.edges())
    }

    @Test fun threeAndFourFingerTaps() {
        val three = Driver(pad())
        three.down(0, 100f, 100f, 1); three.down(5, 200f, 100f, 2); three.down(10, 300f, 100f, 3)
        three.up(80, 1); three.up(85, 2); three.up(90, 3); three.until(400)
        assertEquals(listOf(0, M, 0), three.buttons.edges())
        val four = Driver(pad())
        for (k in 0..3) four.down(k * 3L, 100f + k * 100f, 100f, k + 1L)
        for (k in 0..3) four.up(80 + k * 3L, k + 1L)
        four.until(400)
        assertEquals(listOf(0, WIN or 0x11, 0), four.keys.edges())
        assertTrue(four.buttons.all { it == 0 })
    }

    // Real fingers land and lift tens of ms apart and wobble a little on the glass.

    @Test fun slowStaggeredThreeFingerTapIsMiddleClick() {
        // 50 ms between fingers: 250 ms from the first down to the last up.
        val d = Driver(pad())
        d.down(0, 100f, 100f, 1); d.down(50, 200f, 100f, 2); d.down(100, 300f, 100f, 3)
        d.up(170, 1); d.up(210, 2); d.up(250, 3); d.until(600)
        assertEquals(listOf(0, M, 0), d.buttons.edges())
        assertTrue(d.keys.all { it == 0 })
    }

    @Test fun slowStaggeredFourFingerTapIsNotifications() {
        val d = Driver(pad())
        for (k in 0..3) d.down(k * 40L, 100f + k * 100f, 100f, k + 1L)
        for (k in 0..3) d.up(200 + k * 40L, k + 1L)
        d.until(700)
        assertEquals(listOf(0, WIN or 0x11, 0), d.keys.edges())
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun threeFingerTapWithWobbleBeforeTheLastFinger() {
        val d = Driver(pad())
        d.down(0, 100f, 100f, 1); d.down(30, 200f, 100f, 2)
        d.move(40, 104f, 103f, 1); d.move(41, 198f, 104f, 2); d.move(50, 103f, 99f, 1)
        d.down(80, 300f, 100f, 3)
        d.move(90, 301f, 102f, 3)
        d.up(150, 1); d.up(170, 2); d.up(190, 3); d.until(600)
        assertEquals(listOf(0, M, 0), d.buttons.edges())
        assertEquals(0, d.wheel); assertEquals(0, d.dx)
    }

    @Test fun threeFingerRestIsNotATap() {
        val d = Driver(pad())
        d.down(0, 100f, 100f, 1); d.down(20, 200f, 100f, 2); d.down(40, 300f, 100f, 3)
        d.up(400, 1); d.up(410, 2); d.up(420, 3); d.until(800)
        assertTrue(d.buttons.all { it == 0 })
        assertTrue(d.keys.all { it == 0 })
    }

    @Test fun thirdFingerAfterTwoStartedScrollingSwipes() {
        val d = Driver(pad(NO_GLIDE))
        d.down(0, 100f, 300f, 1); d.down(20, 200f, 300f, 2)
        // The first two fingers already drift up (a scroll starts) before the third lands.
        for (i in 1..3) { d.move(20 + i * 10L, 100f, 300f - i * 4f, 1); d.move(21 + i * 10L, 200f, 300f - i * 4f, 2) }
        d.down(60, 300f, 290f, 3)
        for (i in 1..8) for (k in 0..2) d.move(60 + i * 10L + k, 100f + k * 100f, 288f - i * 10f, k + 1L)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.until(600)
        assertEquals(listOf(0, WIN or TAB, 0), d.keys.edges())
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun thirdFingerDuringPinchSwipesAndReleasesCtrl() {
        val d = Driver(pad())
        d.down(0, 200f, 300f, 1); d.down(10, 300f, 300f, 2)
        for (i in 1..3) d.move(10 + i * 10L, 300f + i * 5f, 300f, 2) // spread +15 dp: pinch
        d.down(50, 400f, 300f, 3)
        val xs = listOf(200f, 315f, 400f)
        for (i in 1..8) for (k in 0..2) d.move(50 + i * 10L + k, xs[k], 300f + i * 10f, k + 1L)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.until(700)
        assertEquals(0, d.keys.last())
        assertEquals(WIN or 0x07, d.keys.edges().filter { it != 0 }.last())
        assertTrue(d.keys.none { it == WIN or 0x07 or CTRL })
        assertEquals(0, d.wheelWithoutCtrl)
    }

    @Test fun staggeredSlowThreeFingerSwipe() {
        val d = Driver(pad())
        d.down(0, 100f, 300f, 1); d.down(60, 200f, 300f, 2); d.down(120, 300f, 300f, 3)
        for (i in 1..10) for (k in 0..2) d.move(200 + i * 16L + k, 100f + k * 100f, 300f - i * 6f, k + 1L)
        d.up(400, 1); d.up(430, 2); d.up(460, 3); d.until(800)
        assertEquals(listOf(0, WIN or TAB, 0), d.keys.edges())
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun gesturesAreConfigurable() {
        val s = TouchpadSettings(acceleration = 0f,
            gestures3 = FingerGestures.WINDOWS_3.copy(swipeUp = GestureAction.Mouse(MouseButton.BACK)))
        val d = Driver(pad(s))
        d.together(listOf(1L, 2L, 3L), 0, 8, 0f, -10f)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.until(600)
        assertEquals(listOf(0, 1 shl MouseButton.BACK.bit, 0), d.buttons.edges())
        assertTrue(d.keys.all { it == 0 })
    }

    @Test fun gesturesOffThreeFingersScroll() {
        val d = Driver(pad(NO_GLIDE.copy(gestures = false)))
        d.together(listOf(1L, 2L, 3L), 0, 8, 0f, 10f)
        d.up(200, 1); d.up(201, 2); d.up(202, 3); d.until(600)
        assertTrue(d.keys.all { it == 0 })
        assertTrue("wheel ${d.wheel}", d.wheel in 395..400) // 80 dp; thirds round down
    }

    @Test fun cancelReleasesHeldAlt() {
        val d = Driver(pad())
        d.together(listOf(1L, 2L, 3L), 0, 5, 10f, 0f)
        d.until(200)
        assertEquals(ALT, d.keys.last())
        d.pad.cancel(d.t)
        d.until(400)
        assertEquals(0, d.keys.last())
    }

    @Test fun anchoredThumbPinchZooms() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.down(0, 200f, 300f, id = 1); d.down(2, 300f, 300f, id = 2)
        for (i in 1..10) d.move(10 + i * 10L, 300f + i * 8f, 300f, id = 2)
        d.up(130, 2); d.up(131, 1); d.until(600)
        assertEquals(240, d.wheel)
        assertEquals(listOf(0, CTRL, 0), d.keys.edges())
    }

    @Test fun oldProfileJsonGetsGestureDefaults() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(TouchpadSettings.serializer(), """{"scrollSpeed":2.0}""")
        assertEquals(TouchpadSettings(scrollSpeed = 2f), s)
        assertEquals(FingerGestures.WINDOWS_3, s.gestures3)
        val custom = s.copy(gestures4 = s.gestures4.copy(tap = GestureAction.Keys(GestureActions.WIN, 0x04)))
        assertEquals(custom, json.decodeFromString(TouchpadSettings.serializer(), json.encodeToString(TouchpadSettings.serializer(), custom)))
    }

    // ---- Aiming fingers (touch buttons driving the cursor) stay out of the gestures ----

    @Test fun aimingFingerDoesNotTurnAMoveIntoAGesture() {
        val d = Driver(pad())
        d.down(0, 500f, 500f, 1)
        for (i in 1..10) d.move(i * 8L, 500f + i * 8f, 500f, 1)
        d.aim(84, 100f, 100f, 2) // a click-through fire finger lands and stays still
        assertEquals(2, d.pad.fingers) // the gyro's touchpad ratchet still counts it
        for (i in 11..30) d.move(i * 8L, 500f + i * 8f, 500f + (i - 10) * 3f, 1)
        d.until(300)
        assertEquals(360, d.dx) // all 240 dp at sensitivity 1.5
        assertEquals(90, d.dy)
        assertEquals(0, d.wheel); assertEquals(0, d.hwheel)
        assertTrue(d.keys.all { it == 0 })
    }

    @Test fun aimingFingerNeverPinchesAndTheAimLivesOnAfterItLifts() {
        val d = Driver(pad())
        d.down(0, 900f, 500f, 1)
        for (i in 1..10) d.move(i * 8L, 900f - i * 4f, 500f, 1)
        d.aim(84, 200f, 500f, 2) // the thumb now moves toward this finger: the spread shrinks
        for (i in 11..30) d.move(i * 8L, 900f - i * 4f, 500f, 1)
        d.up(250, 2)
        for (i in 32..50) d.move(i * 8L, 900f - i * 4f, 500f, 1)
        d.until(500)
        assertEquals(-300, d.dx) // all 200 dp
        assertEquals(0, d.wheel)
        assertTrue(d.keys.all { it == 0 })
    }

    @Test fun aimingFingerFirstThenABackgroundTapIsALeftClick() {
        val d = Driver(pad())
        d.aim(0, 100f, 100f, 1)
        d.down(40, 600f, 300f, 2)
        d.up(100, 2)
        d.up(150, 1)
        d.until(600)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
    }

    @Test fun twoStillAimingFingersNeverStartASwipe() {
        val d = Driver(pad())
        d.down(0, 1800f, 600f, 1)
        var x = 1800f
        for (f in 1..6) { x -= 12f; d.move(f * 8L, x, 600f, 1) }
        d.aim(55, 300f, 700f, 2); d.aim(60, 2200f, 900f, 3) // fire and aim-down-sights held
        for (f in 8..40) { x -= 12f; d.move(f * 8L, x, 600f, 1) }
        assertEquals(3, d.pad.fingers)
        d.until(400)
        assertEquals(-702, d.dx) // 468 dp
        assertTrue(d.keys.all { it == 0 })
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun aimingFingersOnlyMoveTheCursor() {
        // Two aiming fingers moving together add their motion: no scroll, no tap.
        val d = Driver(pad())
        d.aim(0, 100f, 100f, 1); d.aim(1, 300f, 100f, 2)
        for (i in 1..10) { d.move(i * 10L, 100f, 100f + i * 10f, 1); d.move(i * 10L + 1, 300f, 100f + i * 10f, 2) }
        d.up(120, 1); d.up(121, 2); d.until(500)
        assertEquals(300, d.dy)
        assertEquals(0, d.wheel)
        assertTrue(d.buttons.all { it == 0 })
        assertEquals(0, d.pad.fingers)
    }

    // ---- Smoothness ----

    @Test fun staggeredLiftFlingStartsAtTheSecondLiftWithoutAJump() {
        val d = Driver(pad(), stepMs = 1)
        d.down(0, 100f, 300f, 1); d.down(1, 200f, 300f, 2)
        for (i in 1..10) { d.move(10 + i * 10L, 100f, 300f + i * 15f, 1); d.move(11 + i * 10L, 200f, 300f + i * 15f, 2) }
        d.up(115, 1)
        for (i in 11..14) d.move(11 + i * 10L, 200f, 300f + i * 15f, 2) // the other finger keeps going
        d.up(155, 2)
        d.until(1500)
        val during = (20..111).maxOf { kotlin.math.abs(d.wheels[it]) } // one polled 10 ms frame of scrolling
        val between = (116..154).maxOf { kotlin.math.abs(d.wheels[it]) }
        val atLift = d.wheels[155] + d.wheels[156]
        assertTrue("no freeze between the lifts", between > 0)
        assertTrue("step between the lifts $between vs $during", between <= during)
        assertTrue("step at the second lift $atLift vs $during", kotlin.math.abs(atLift) <= during)
        assertTrue(d.wheel > 1500) // still flings
    }

    private fun slowStart(s: TouchpadSettings): List<Int> {
        val p = pad(s)
        p.down(1, 0f, 0f, 0)
        return (1..20).map { f -> p.move(1, f * 2.4f, 0f, f * 8 * MS); p.dx.also { p.drainMotion() } }
    }

    @Test fun tapToClickOffMovesFromTheFirstMotion() {
        // 0.3 dp/ms: 3.6 counts per 8 ms frame at sensitivity 1.5.
        val frames = slowStart(TouchpadSettings(acceleration = 0f, tapToClick = false))
        assertTrue("$frames", frames.all { it in 3..4 })
        // With tap-to-click on, the first 10 dp still wait (a tap must not move the cursor).
        val tapOn = slowStart(TouchpadSettings(acceleration = 0f))
        assertEquals(listOf(0, 0, 0, 0), tapOn.take(4))
        assertEquals(frames.sum(), tapOn.sum())
    }

    private fun flick(aiming: Boolean): List<Int> {
        val p = pad(TouchpadSettings())
        p.down(1, 0f, 0f, 0, aiming)
        return (1..5).map { f -> p.move(1, f * 16f, 0f, f * 8 * MS); p.dx.also { p.drainMotion() } }
    }

    @Test fun flickHasItsFullGainFromTheFirstFrame() {
        // 2 dp/ms is past the top of the acceleration curve: 16 dp x 1.5 x 2 every frame.
        assertEquals(listOf(48, 48, 48, 48, 48), flick(aiming = false))
        assertEquals(listOf(48, 48, 48, 48, 48), flick(aiming = true))
    }

    @Test fun restAfterAFlickDoesNotAccelerateTheNextSlowStep() {
        fun slowSteps(p: Touchpad, x0: Float, t0: Double): List<Int> {
            var x = x0; var t = t0
            return (1..6).map { x += 3.333f; t += 33.333; p.move(1, x, 0f, (t * MS).toLong()); p.dx.also { p.drainMotion() } }
        }
        val q = pad(TouchpadSettings()); q.down(1, 0f, 0f, 0)
        for (f in 1..10) q.move(1, f * 15f, 0f, (f * 8.333 * MS).toLong()) // 1.8 dp/ms
        q.drainMotion()
        val afterRest = slowSteps(q, 150f, 83.33 + 300.0)
        val r = pad(TouchpadSettings()); r.down(1, 0f, 0f, 0)
        for (f in 1..10) r.move(1, f * 3.333f, 0f, (f * 33.333 * MS).toLong())
        r.drainMotion()
        val slowOnly = slowSteps(r, 33.33f, 333.33)
        for (i in afterRest.indices) assertTrue("$afterRest vs $slowOnly", kotlin.math.abs(afterRest[i] - slowOnly[i]) <= 1)
    }

    @Test fun tapDragKeepsMotionMadeBeforeTheHoldTimer() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f, sensitivity = 1f)))
        d.down(0, 100f, 100f); d.up(60)
        d.down(120, 100f, 100f)
        d.move(200, 108f, 100f) // inside the slop: still a possible double tap
        d.move(320, 110f, 100f) // the hold timer made it a drag before this
        d.move(330, 120f, 100f)
        d.up(340); d.until(700)
        assertEquals(20, d.dx)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
    }

    @Test fun twoFingerTouchThatBecomesAMoveKeepsItsTravel() {
        val p = pad(TouchpadSettings(acceleration = 0f, sensitivity = 1f))
        var dx = 0
        p.down(1, 100f, 100f, 0); p.down(2, 300f, 100f, 20 * MS)
        p.up(2, 60 * MS) // the second finger lifts quickly; the first one stays
        for (f in 1..40) { val t = 60 * MS + f * 8 * MS; p.move(1, 100f + f * 2f, 100f, t); p.poll(t); dx += p.dx; p.drainMotion() }
        p.up(1, 400 * MS); p.poll(500 * MS); dx += p.dx
        assertEquals(80, dx)
    }

    /** Two fingers rest, [f1] moves finger 1 a little (under the scroll and pinch thresholds), finger 2 lifts after the tap window. */
    private fun liftAfterSmallTwoFingerTravel(f1x: Float): Pair<Int, Int> {
        val d = Driver(pad(NO_GLIDE))
        d.down(0, 100f, 300f, id = 1); d.down(2, 200f, 300f, id = 2)
        for (i in 1..5) d.move(10 + i * 10L, 100f + f1x * i / 5, 300f, id = 1)
        d.until(400)
        d.up(400, 2); d.until(450)
        val atLift = d.dx
        for (i in 1..5) d.move(450 + i * 10L, 100f + f1x + i * 2f, 300f, id = 1)
        d.up(520, 1); d.until(600)
        return atLift to d.dx
    }

    @Test fun twoFingerRestThenLiftDoesNotJumpTheCursor() {
        // Drift (centroid 2.5 dp) and spread (8 dp) both stayed under the thresholds: no cursor motion.
        assertEquals(0 to 15, liftAfterSmallTwoFingerTravel(5f)) // then 10 dp at sensitivity 1.5
        assertEquals(0 to 15, liftAfterSmallTwoFingerTravel(8f))
    }

    /** A two-finger scroll; with [batched], pairs of touch frames are processed back to back but keep their event times. */
    private fun scrollTimedByEvents(batched: Boolean): Int {
        val p = pad(TouchpadSettings(acceleration = 0f))
        var wheel = 0
        fun poll(t: Long) { p.poll(t); wheel += p.wheel; p.drainMotion() }
        p.down(1, 100f, 300f, 0); p.down(2, 200f, 300f, 0)
        for (f in 1..12) {
            val ev = f * 8 * MS
            val now = if (!batched) ev else if (f % 2 == 1) (f + 1) * 8 * MS else ev + 100_000L
            p.move(1, 100f, 300f + f * 12f, now, ev); p.move(2, 200f, 300f + f * 12f, now + 1000, ev)
            poll(now + 2000)
        }
        p.up(1, 100 * MS); p.up(2, 100 * MS + 1000)
        for (t in 101..3000L) poll(t * MS)
        return wheel
    }

    @Test fun flingVelocityIsTimedByTheEvents() {
        val even = scrollTimedByEvents(batched = false)
        assertTrue(even > 720 + 1000) // 144 dp of scroll plus a glide
        assertEquals(even, scrollTimedByEvents(batched = true))
    }

    // ---- One finger cancelled by the system ----

    @Test fun cancelledFingerNeverClicks() {
        val one = Driver(pad())
        one.down(0, 100f, 100f); one.until(60); one.pad.cancelFinger(1, one.t); one.up(80); one.until(500)
        assertTrue(one.buttons.all { it == 0 })
        assertEquals(0, one.pad.fingers)
        // A two-finger tap with one finger cancelled: no right click when the other lifts.
        val two = Driver(pad())
        two.down(0, 100f, 100f, 1); two.down(20, 200f, 100f, 2)
        two.until(60); two.pad.cancelFinger(2, two.t)
        two.up(90, 1); two.until(600)
        assertTrue(two.buttons.all { it == 0 })
    }

    @Test fun cancelledFingerEndsAPinchAndTheOtherFingerMovesTheCursor() {
        val d = Driver(pad())
        d.down(0, 200f, 300f, id = 1); d.down(2, 300f, 300f, id = 2)
        for (i in 1..5) d.move(10 + i * 10L, 300f + i * 4f, 300f, id = 2) // spread: pinch, Ctrl held
        d.until(80)
        assertEquals(CTRL, d.keys.last())
        d.pad.cancelFinger(2, d.t)
        for (i in 1..4) d.move(80 + i * 10L, 200f + i * 10f, 300f, id = 1)
        d.up(130, 1); d.until(600)
        assertEquals(0, d.keys.last())
        assertEquals(0, d.wheelWithoutCtrl)
        assertEquals(60, d.dx) // 40 dp at sensitivity 1.5
        assertTrue(d.buttons.all { it == 0 })
    }

    @Test fun cancelledScrollFingerNeverFlings() {
        val d = Driver(pad(TouchpadSettings(acceleration = 0f)))
        d.together(listOf(1L, 2L), 0, 10, 0f, 15f) // 1.5 dp/ms: lifting would fling
        d.until(115); d.pad.cancelFinger(1, d.t)
        d.up(116, 2); d.until(2000)
        assertEquals(750, d.wheel)
        assertEquals(0, d.dx); assertEquals(0, d.dy)
    }

    @Test fun cancelledAimingFingerIsGone() {
        val d = Driver(pad())
        d.aim(0, 100f, 100f, 1)
        d.until(10); d.pad.cancelFinger(1, d.t)
        assertEquals(0, d.pad.fingers)
        d.move(20, 200f, 100f, 1)
        assertEquals(0, d.dx)
    }

    @Test fun aimingFingerFinishesATapsClickSoItsAimNeverDrags() {
        val d = Driver(pad())
        d.down(0, 500f, 500f); d.up(30) // a tap: its click waits for a possible drag
        d.aim(100, 100f, 100f, 2)
        for (i in 1..20) d.move(100 + i * 10L, 100f + i * 5f, 100f, 2)
        d.up(320, 2); d.until(600)
        assertEquals(listOf(0, L, 0), d.buttons.edges())
        assertEquals(0, d.dxWhileLeft)
        assertEquals(150, d.dx)
    }
}
