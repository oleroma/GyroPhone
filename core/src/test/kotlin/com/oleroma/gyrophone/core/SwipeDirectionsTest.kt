package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.SurfaceMap
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe directions: pies, activation lengths, hold modes and the latch ([TouchpadSettings] direction settings). */
class SwipeDirectionsTest {
    private val ms = 1_000_000L
    private val up = 1 shl MouseButton.LEFT.bit
    private val right = 1 shl MouseButton.RIGHT.bit
    private val down = 1 shl MouseButton.MIDDLE.bit
    private val left = 1 shl MouseButton.BACK.bit

    private fun m(i: SurfaceInput, b: MouseButton) = Mapping(InputSource.Surface(1, i), OutputTarget.Mouse(b))

    private val holdMap = SurfaceMap(listOf(
        m(SurfaceInput.HOLD_UP, MouseButton.LEFT), m(SurfaceInput.HOLD_RIGHT, MouseButton.RIGHT),
        m(SurfaceInput.HOLD_DOWN, MouseButton.MIDDLE), m(SurfaceInput.HOLD_LEFT, MouseButton.BACK),
    ))
    private val swipeMap = SurfaceMap(listOf(
        m(SurfaceInput.SWIPE_UP, MouseButton.LEFT), m(SurfaceInput.SWIPE_RIGHT, MouseButton.RIGHT),
        m(SurfaceInput.SWIPE_DOWN, MouseButton.MIDDLE), m(SurfaceInput.SWIPE_LEFT, MouseButton.BACK),
    ))

    /** One finger driven in 1 dp steps every 2 ms, polled after each step. */
    private inner class Finger(s: TouchpadSettings, map: SurfaceMap) {
        val pad = Touchpad(s.copy(acceleration = 0f), map).apply { pxPerDp = 1f; minPulseNanos = 4 * ms }
        var t = 0L
        var x = 100f
        var y = 100f

        /** Every poll's buttons. */
        val seen = ArrayList<Int>()

        fun poll(): Int = pad.poll(t).also { seen.add(it) }

        fun down() {
            pad.down(1, x, y, t); poll()
        }

        /** Travels ([dx], [dy]) dp in 1 dp steps. */
        fun go(dx: Float, dy: Float) {
            val n = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)).toInt()
            for (i in 1..n) {
                t += 2 * ms
                x += dx / n; y += dy / n
                pad.move(1, x, y, t); poll()
            }
            settle()
        }

        fun up() {
            t += 2 * ms; pad.up(1, t); settle()
        }

        fun tap() {
            down(); t += 50 * ms; up()
        }

        /** Polls for a while with no input (queued changes show). */
        fun settle(forMs: Long = 30) {
            repeat((forMs / 2).toInt()) { t += 2 * ms; poll() }
        }

        val now: Int get() = poll()

        /** How many separate presses of [b] were seen. */
        fun presses(b: Int): Int = seen.indices.count { i -> seen[i] and b != 0 && (i == 0 || seen[i - 1] and b == 0) }
    }

    @Test
    fun swipeFiresOnEachDirectionChange() {
        val f = Finger(TouchpadSettings(), swipeMap)
        f.down(); f.go(50f, 0f)
        assertEquals(1, f.presses(right))
        f.go(60f, 0f) // the same way: nothing more
        assertEquals(1, f.presses(right))
        f.go(0f, -50f)
        assertEquals(1, f.presses(up))
        f.up()
        assertEquals(0, f.presses(left) + f.presses(down))
    }

    @Test
    fun swipeLengthIsConfigurable() {
        val f = Finger(TouchpadSettings(swipeDp = 15f), swipeMap)
        f.down(); f.go(20f, 0f)
        assertEquals(1, f.presses(right))
        val g = Finger(TouchpadSettings(swipeDp = 60f), swipeMap)
        g.down(); g.go(50f, 0f)
        assertEquals(0, g.presses(right))
    }

    @Test
    fun fromStartReleasesWhenSlidingBack() {
        val f = Finger(TouchpadSettings(), holdMap)
        f.down(); f.go(30f, 0f)
        assertEquals(right, f.now)
        f.go(-15f, 0f)
        assertEquals(0, f.now)
    }

    @Test
    fun classicFromStartHoldsDiagonalsPerAxis() {
        val f = Finger(TouchpadSettings(), holdMap)
        f.down(); f.go(30f, -30f)
        assertEquals(up or right, f.now)
    }

    @Test
    fun draggedStartPointLetsGoAfterTheReleaseTravel() {
        val f = Finger(TouchpadSettings(dragStart = true, releaseDp = 10f), holdMap)
        f.down(); f.go(100f, 0f)
        assertEquals(right, f.now)
        f.go(-12f, 0f) // far from where it landed, but back past the release travel
        assertEquals(0, f.now)
        f.go(-25f, 0f)
        assertEquals(left, f.now)
    }

    @Test
    fun directionChangeHoldsUntilAnotherDirection() {
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE), holdMap)
        f.down(); f.go(30f, 0f)
        assertEquals(right, f.now)
        f.go(-15f, 0f) // back toward the start, short of the hold length: still right
        assertEquals(right, f.now)
        f.go(-15f, 0f)
        assertEquals(left, f.now)
        f.go(-100f, 0f)
        assertEquals(left, f.now)
        f.up()
        assertEquals(0, f.now)
        assertEquals(1, f.presses(right))
    }

    @Test
    fun gapsKeepTheHeldDirectionAndFireNoSwipe() {
        val pies = listOf(60f, 60f, 60f, 60f)
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, pies = pies), holdMap)
        f.down(); f.go(0f, -30f)
        assertEquals(up, f.now)
        f.go(40f, -40f) // 45°: between the up and right pies
        assertEquals(up, f.now)
        val g = Finger(TouchpadSettings(pies = pies), swipeMap)
        g.down(); g.go(60f, -60f)
        assertEquals(0, g.seen.fold(0) { a, b -> a or b }.toLong())
    }

    @Test
    fun overlappingPiesHoldBoth() {
        val pies = listOf(135f, 135f, 135f, 135f)
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, pies = pies), holdMap)
        f.down(); f.go(30f, -30f)
        assertEquals(up or right, f.now)
        f.go(0f, -40f) // straight up: only the up pie
        assertEquals(up, f.now)
    }

    @Test
    fun narrowPiesFromStartLeaveDiagonalsEmpty() {
        val f = Finger(TouchpadSettings(pies = listOf(60f, 60f, 60f, 60f)), holdMap)
        f.down(); f.go(30f, -30f)
        assertEquals(0, f.now)
    }

    @Test
    fun latchKeepsTheDirectionUntilATap() {
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, latch = true), holdMap)
        f.down(); f.go(30f, 0f); f.up()
        assertEquals(right, f.now)
        assertTrue(f.pad.latched)
        f.tap()
        assertEquals(0, f.now)
        assertTrue(!f.pad.latched)
    }

    @Test
    fun latchGoesOnWithTheSameDirectionAndSwitchesToAnother() {
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, latch = true), holdMap)
        f.down(); f.go(30f, 0f); f.up()
        f.down(); f.go(30f, 0f)
        assertEquals(right, f.now)
        f.up()
        assertEquals(right, f.now)
        assertEquals(1, f.presses(right)) // never let go in between
        f.down(); f.go(0f, 30f)
        assertEquals(down, f.now)
        f.up()
        assertEquals(down, f.now)
    }

    @Test
    fun latchFromStartStaysUntilTheFingerPressesSomething() {
        val f = Finger(TouchpadSettings(latch = true), holdMap)
        f.down(); f.go(30f, 0f); f.up()
        assertEquals(right, f.now)
        f.down(); f.go(12f, 0f) // under the hold length: the latch stays
        assertEquals(right, f.now)
        f.go(0f, 30f) // the finger presses down: that replaces the latch
        assertEquals(down, f.now)
    }

    @Test
    fun latchLetsGoAfterItsTimer() {
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, latch = true, latchReleaseMs = 200), holdMap)
        f.down(); f.go(30f, 0f); f.up()
        assertEquals(right, f.now)
        f.settle(100)
        assertEquals(right, f.now)
        f.settle(150)
        assertEquals(0, f.now)
    }

    @Test
    fun noLatchWhenOff() {
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE), holdMap)
        f.down(); f.go(30f, 0f); f.up()
        assertEquals(0, f.now)
    }
}
