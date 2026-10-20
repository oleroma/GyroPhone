package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.TouchpadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L
private val L = 1 shl MouseButton.LEFT.bit
private val R = 1 shl MouseButton.RIGHT.bit

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
        dx += pad.dx; dy += pad.dy; wheel += pad.wheel
        if (b and L != 0) dxWhileLeft += pad.dx
        pad.drainMotion()
    }

    fun down(ms: Long, x: Float, y: Float, id: Long = 1) { until(ms); pad.down(id, x, y, t) }
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

    @Test fun twoFingerScrollEmitsNotches() {
        val d = scroll(TouchpadSettings(acceleration = 0f))
        assertEquals(10, d.wheel)
        assertTrue(d.buttons.all { it == 0 })
        assertEquals(0, d.dx); assertEquals(0, d.dy)
        assertEquals(-10, scroll(TouchpadSettings(acceleration = 0f, naturalScroll = false)).wheel)
        assertEquals(20, scroll(TouchpadSettings(acceleration = 0f, scrollSpeed = 2f)).wheel)
        val off = scroll(TouchpadSettings(acceleration = 0f, twoFingerScroll = false))
        assertEquals(0, off.wheel)
        assertTrue(off.buttons.all { it == 0 })
    }

    @Test fun fingerAddedMidMoveScrollsOnlyFromItsDown() {
        val d = Driver(pad())
        d.down(0, 100f, 100f, id = 1)
        for (i in 1..30) d.move(i * 5L, 100f, 100f + i * 10f, id = 1) // cursor 300 px down
        d.down(160, 200f, 400f, id = 2)
        for (i in 1..4) {
            d.move(160 + i * 10L, 100f, 400f + i * 12f, id = 1)
            d.move(160 + i * 10L + 2, 200f, 400f + i * 12f, id = 2)
        }
        d.up(220, id = 1); d.up(222, id = 2); d.until(500)
        // 48 px of two-finger travel = 2 notches; the 300 px cursor swipe adds none.
        assertEquals(2, d.wheel)
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
}
