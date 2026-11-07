package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.SurfaceMap
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.Triggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe any way ([SurfaceInput.HOLD_ANY]): straight-line distance from the touch-down point. */
class SwipeAnyWayTest {
    private val ms = 1_000_000L
    private val left = 1 shl MouseButton.LEFT.bit
    private val right = 1 shl MouseButton.RIGHT.bit
    private val middle = 1 shl MouseButton.MIDDLE.bit

    private fun any(zone: Int, b: MouseButton, input: SurfaceInput = SurfaceInput.HOLD_ANY) =
        Mapping(InputSource.Surface(1, input, zone = zone), OutputTarget.Mouse(b))

    private inner class Finger(s: TouchpadSettings, map: SurfaceMap) {
        val pad = Touchpad(s.copy(acceleration = 0f), map).apply { pxPerDp = 1f; minPulseNanos = 4 * ms }
        var t = 0L
        var x = 300f
        var y = 300f
        val seen = ArrayList<Int>()

        fun poll(): Int = pad.poll(t).also { seen.add(it) }
        fun down() {
            pad.down(1, x, y, t); poll()
        }

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

        fun settle() = repeat(15) { t += 2 * ms; poll() }
        fun presses(b: Int): Int = seen.indices.count { i -> seen[i] and b != 0 && (i == 0 || seen[i - 1] and b == 0) }
        val now: Int get() = poll()
    }

    @Test
    fun heldPastTheHoldDistanceAnyWay() {
        val map = SurfaceMap(listOf(any(0, MouseButton.LEFT)))
        for ((dx, dy) in listOf(30f to 0f, -30f to 0f, 0f to 30f, 21f to -21f)) {
            val f = Finger(TouchpadSettings(), map)
            f.down()
            f.go(dx * 0.5f, dy * 0.5f)
            assertEquals("not yet at ($dx, $dy)", 0, f.now and left)
            f.go(dx * 0.5f, dy * 0.5f)
            assertEquals("held at ($dx, $dy)", left, f.now and left)
            f.up()
            assertEquals(0, f.now and left)
        }
    }

    @Test
    fun zonesGoByStraightLineDistance() {
        // 20 dp hold distance, zone 1 to 40 dp, zone 2 to 60 dp, zone 3 on.
        val s = TouchpadSettings(holdZones = listOf(HoldZone(lengthDp = 20f)))
        val map = SurfaceMap(listOf(any(0, MouseButton.LEFT), any(1, MouseButton.RIGHT), any(2, MouseButton.MIDDLE)))
        val f = Finger(s, map)
        f.down()
        f.go(18f, 18f) // ~25 dp
        assertEquals(left, f.now and (left or right or middle))
        f.go(0f, -36f) // (18, -18): still ~25 dp from the start: zone 1
        assertEquals(left, f.now and (left or right or middle))
        f.go(-50f, 0f) // (-32, -18): ~37 dp
        assertEquals(left, f.now and (left or right or middle))
        f.go(-15f, 0f) // (-47, -18): ~50 dp
        assertEquals(right, f.now and (left or right or middle))
        f.go(-20f, -10f) // ~70 dp
        assertEquals(middle, f.now and (left or right or middle))
        f.go(60f, 25f) // (-7, -3): back near the start
        assertEquals(0, f.now and (left or right or middle))
    }

    @Test
    fun anyWayWorksWithDirectionChangeMode() {
        val map = SurfaceMap(listOf(any(0, MouseButton.LEFT)))
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE), map)
        f.down()
        f.go(25f, 0f)
        assertEquals(left, f.now and left)
        // Back to the start: any way still measures from the start point.
        f.go(-25f, 0f)
        assertEquals(0, f.now and left)
    }

    @Test
    fun quickSwipeAnyWayPulsesWithEachQuickSwipe() {
        val quick = Triggers.with(InputSource.Surface(1, SurfaceInput.HOLD_ANY), Trigger.TAP)
        assertEquals(InputSource.Surface(1, SurfaceInput.SWIPE_ANY), quick)
        assertEquals(InputSource.Surface(1, SurfaceInput.HOLD_ANY), Triggers.base(quick!!))
        assertEquals(Trigger.TAP, Triggers.of(quick))
        val map = SurfaceMap(listOf(Mapping(quick, OutputTarget.Mouse(MouseButton.LEFT))))
        val f = Finger(TouchpadSettings(), map)
        f.down()
        f.go(50f, 0f)
        f.up()
        assertEquals(1, f.presses(left))
        val g = Finger(TouchpadSettings(), map)
        g.down()
        g.go(0f, -50f)
        g.up()
        assertEquals(1, g.presses(left))
    }

    @Test
    fun directionsAndAnyWayTogether() {
        val map = SurfaceMap(listOf(any(0, MouseButton.LEFT), any(0, MouseButton.RIGHT, SurfaceInput.HOLD_UP)))
        val f = Finger(TouchpadSettings(), map)
        f.down()
        f.go(25f, 0f)
        assertEquals(left, f.now and (left or right))
        f.go(-25f, -25f)
        assertEquals(left or right, f.now and (left or right))
    }

    @Test
    fun swipeRowSlotsAndZoneCounts() {
        val slots = Bindings.swipeSlots(1, "b")
        assertEquals(listOf("Any", "↑", "→", "↓", "←"), slots.map { it.label })
        val counts = Bindings.holdZoneCounts(listOf(Mapping(InputSource.Surface(1, SurfaceInput.HOLD_ANY, "b", zone = 2), OutputTarget.NoOutput)), "b")
        assertEquals(3, counts[4])
        assertTrue(Triggers.supports(InputSource.Surface(1, SurfaceInput.HOLD_ANY, "b")))
        assertEquals("Quick swipe", Triggers.label(InputSource.Surface(1, SurfaceInput.HOLD_ANY), Trigger.TAP))
    }
}
