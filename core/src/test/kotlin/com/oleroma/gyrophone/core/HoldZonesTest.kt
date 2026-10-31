package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.SurfaceMap
import com.oleroma.gyrophone.core.engine.SwipeGuide
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.ZoneKeep
import org.junit.Assert.assertEquals
import org.junit.Test

/** Length zones of held directions ([TouchpadSettings.holdZone]). */
class HoldZonesTest {
    private val ms = 1_000_000L
    private val z1 = 1 shl MouseButton.LEFT.bit
    private val z2 = 1 shl MouseButton.RIGHT.bit
    private val z3 = 1 shl MouseButton.MIDDLE.bit
    private val all = z1 or z2 or z3

    private fun m(zone: Int, b: MouseButton) =
        Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, zone = zone), OutputTarget.Mouse(b))

    /** Hold ↑ in three zones: 20..40 dp, 40..60 dp, from 60 dp on (the hold distance is 20). */
    private val map = SurfaceMap(listOf(m(0, MouseButton.LEFT), m(1, MouseButton.RIGHT), m(2, MouseButton.MIDDLE)))

    /** [map] latched: a Toggle On swipe chip on each zone. */
    private val latchMap = SurfaceMap(listOf(m(0, MouseButton.LEFT), m(1, MouseButton.RIGHT), m(2, MouseButton.MIDDLE)) +
        (0..2).map { Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, zone = it), OutputTarget.NoOutput, options = MapOptions(how = How.TOGGLE, endsOnTap = true)) })

    private inner class Finger(s: TouchpadSettings, map: SurfaceMap = this.map) {
        val pad = Touchpad(s.copy(acceleration = 0f), map).apply { pxPerDp = 1f; minPulseNanos = 4 * ms }
        var t = 0L
        var x = 100f
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

        fun tap() {
            down(); t += 50 * ms; up()
        }

        fun settle(forMs: Long = 30) {
            repeat((forMs / 2).toInt()) { t += 2 * ms; poll() }
        }

        val now: Int get() = poll() and all

        fun presses(b: Int): Int = seen.indices.count { i -> seen[i] and b != 0 && (i == 0 || seen[i - 1] and b == 0) }
    }

    @Test
    fun fromStartFollowsTheZoneTheFingerIsIn() {
        val f = Finger(TouchpadSettings())
        f.down()
        f.go(0f, -10f); assertEquals(0, f.now)
        f.go(0f, -20f); assertEquals(z1, f.now)
        f.go(0f, -20f); assertEquals(z2, f.now)
        f.go(0f, -20f); assertEquals(z3, f.now)
        f.go(0f, 100f); assertEquals(0, f.now) // past zone 3 there is no more
        f.go(0f, -95f); assertEquals(z3, f.now)
        f.go(0f, 20f); assertEquals(z2, f.now) // sliding back goes back a zone
        f.up(); assertEquals(0, f.now)
    }

    @Test
    fun keepPressedHoldsOnlyGoingForward() {
        val s = TouchpadSettings(holdZones = listOf(HoldZone(keep = ZoneKeep.GOING_ON), HoldZone(keep = ZoneKeep.GOING_ON)))
        val f = Finger(s)
        f.down()
        f.go(0f, -30f); assertEquals(z1, f.now)
        f.go(0f, -20f); assertEquals(z1 or z2, f.now)
        f.go(0f, -20f); assertEquals(all, f.now)
        f.go(0f, 20f); assertEquals(z1 or z2, f.now)
        f.go(0f, 20f); assertEquals(z1, f.now)
        f.up(); assertEquals(0, f.now)
    }

    @Test
    fun untilLiftStaysHeldWhereverTheFingerGoes() {
        val s = TouchpadSettings(holdZones = listOf(HoldZone(), HoldZone(keep = ZoneKeep.UNTIL_LIFT)))
        val f = Finger(s)
        f.down()
        f.go(0f, -50f); assertEquals(z2, f.now)
        f.go(0f, -20f); assertEquals(z2 or z3, f.now)
        f.go(0f, 40f); assertEquals(z1 or z2, f.now) // back in zone 1: zone 2 stays
        f.go(0f, 40f); assertEquals(z2, f.now) // back at the start
        f.go(-40f, 0f); assertEquals(z2, f.now)
        f.up(); assertEquals(0, f.now)
    }

    @Test
    fun untilLiftStaysThroughADirectionChange() {
        val s = TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, holdZones = listOf(HoldZone(), HoldZone(keep = ZoneKeep.UNTIL_LIFT)))
        val f = Finger(s)
        f.down()
        f.go(0f, -50f); assertEquals(z2, f.now)
        f.go(0f, 40f) // down takes over
        assertEquals(z2, f.now)
        f.up(); assertEquals(0, f.now)
    }

    @Test
    fun zoneLengthsAreTheirOwnOrTheFirstZones() {
        val s = TouchpadSettings(holdZones = listOf(HoldZone(lengthDp = 30f)))
        val f = Finger(s)
        f.down()
        f.go(0f, -45f); assertEquals(z1, f.now) // zone 1: 20..50
        f.go(0f, -10f); assertEquals(z2, f.now) // zone 2: 50..80 (the first zone's length)
        f.go(0f, -30f); assertEquals(z3, f.now)
    }

    @Test
    fun eachDirectionCanHaveItsOwnZones() {
        val longUp = listOf(HoldZone(lengthDp = 100f))
        val s = TouchpadSettings(holdZones = listOf(HoldZone(lengthDp = 10f)), dirHoldZones = listOf(longUp, emptyList(), emptyList(), emptyList()))
        val f = Finger(s)
        f.down()
        f.go(0f, -50f); assertEquals(z1, f.now)
    }

    @Test
    fun directionChangeNeverGoesBackAZone() {
        // Larger-axis pies: the sideways wobble is no travel up.
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE, holdPieX = 90f, holdPieY = 90f))
        f.down()
        f.go(0f, -30f); assertEquals(z1, f.now)
        f.go(0f, -20f); assertEquals(z2, f.now)
        f.go(10f, 0f) // a short sideways wobble activates nothing
        assertEquals(z2, f.now)
        f.go(0f, -20f); assertEquals(z3, f.now)
        f.up(); assertEquals(0, f.now)
    }

    @Test
    fun dragStartHasOnlyTheFirstZone() {
        val f = Finger(TouchpadSettings(dragStart = true))
        f.down()
        f.go(0f, -80f); assertEquals(z1, f.now)
    }

    @Test
    fun latchKeepsTheHeldZonesUntilATap() {
        val s = TouchpadSettings(holdZones = listOf(HoldZone(keep = ZoneKeep.GOING_ON)))
        val f = Finger(s, latchMap)
        f.down(); f.go(0f, -50f); f.up()
        assertEquals(z1 or z2, f.now)
        f.tap()
        assertEquals(0, f.now)
    }

    @Test
    fun latchedZonesStayUntilTheNextSwipeReachesOne() {
        val f = Finger(TouchpadSettings(), latchMap)
        f.down(); f.go(0f, -70f); f.up()
        assertEquals(z3, f.now)
        f.down(); f.go(0f, -12f)
        assertEquals(z3, f.now) // not yet at the hold distance
        f.go(0f, -10f)
        assertEquals(z1, f.now) // a new swipe picks its zone again
    }

    @Test
    fun oneZoneIsAPlainHeldDirection() {
        val one = SurfaceMap(listOf(m(0, MouseButton.LEFT)))
        val s = TouchpadSettings(holdZones = listOf(HoldZone(keep = ZoneKeep.UNTIL_LIFT)))
        val f = Finger(s, one)
        f.down(); f.go(0f, -80f)
        assertEquals(z1, f.now) // zone settings count only with two zones or more
    }

    @Test
    fun emptyZonesCloseUpInEverySet() {
        val a = Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, "b", zone = 0), OutputTarget.Key(4))
        val c = Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, "b", zone = 2), OutputTarget.Key(5))
        val other = Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, "b", zone = 1), OutputTarget.Key(6), set = 1)
        assertEquals(listOf(a, c, other), Bindings.compactZones(listOf(a, c, other))) // zone 1 is used in set 1
        val out = Bindings.compactZones(listOf(a, c))
        assertEquals(1, (out[1].source as InputSource.Surface).zone)
        assertEquals(listOf(1, 1, 3, 1), Bindings.holdZoneCounts(listOf(a, c, other), "b").toList().let {
            listOf(it[Direction.RIGHT.ordinal], it[Direction.DOWN.ordinal], it[Direction.UP.ordinal], it[Direction.LEFT.ordinal])
        })
    }

    @Test
    fun aQuickSwipeReachesTheFarZoneAtOnceWithARealPulse() {
        // The real minimum pulse (40 ms): one input's change waits for its own last change to have
        // shown that long, not for every other input's.
        val f = Finger(TouchpadSettings())
        f.pad.minPulseNanos = 40 * ms
        f.down()
        repeat(14) { f.t += 2 * ms; f.y -= 5f; f.pad.move(1, f.x, f.y, f.t); f.poll() } // 70 dp up in 28 ms
        f.t += 2 * ms
        assertEquals(true, f.poll() and z3 != 0)
        f.settle(60)
        assertEquals(z3, f.now) // the zones passed on the way each showed and let go
        assertEquals(1, f.presses(z1))
        assertEquals(1, f.presses(z2))
    }

    @Test
    fun swipeGuideMarksSwipeHoldAndZoneEnds() {
        val swipeUp = Mapping(InputSource.Surface(1, SurfaceInput.SWIPE_UP), OutputTarget.Mouse(MouseButton.BACK))
        val g = Touchpad(TouchpadSettings(), SurfaceMap(listOf(m(0, MouseButton.LEFT), m(1, MouseButton.RIGHT), m(2, MouseButton.MIDDLE), swipeUp))).guide()!!
        val out = FloatArray(SwipeGuide.MAX_MARKS)
        val kinds = IntArray(SwipeGuide.MAX_MARKS)
        // Straight up: Swipe after 40, Hold after 20, then each zone ends 20 dp on.
        assertEquals(5, g.marks(0f, -5f, out, kinds))
        assertEquals(listOf(40f, 20f, 40f, 60f, 80f), out.take(5))
        assertEquals(listOf(SwipeGuide.SWIPE, SwipeGuide.HOLD, SwipeGuide.ZONE_END, SwipeGuide.ZONE_END, SwipeGuide.ZONE_END), kinds.take(5))
        // Slanted 60° off up: holds twice as far along the line, the swipe not.
        assertEquals(5, g.marks(0.866f * 10f, -5f, out, kinds))
        assertEquals(40f, out[0], 0.1f)
        assertEquals(40f, out[1], 0.1f)
        // Down has nothing mapped.
        assertEquals(0, g.marks(0f, 5f, out, kinds))
        // From start the line follows its direction: a slanted finger counts as its travel up.
        assertEquals(true, g.onAxis)
        assertEquals(Direction.UP, g.direction(0.866f * 10f, -5f))
        assertEquals(5f, g.along(Direction.UP, 0.866f * 10f, -5f), 0.001f)
        assertEquals(null, g.direction(0f, 5f))
    }

    @Test
    fun swipeGuideSingleZoneAndDragStart() {
        val out = FloatArray(SwipeGuide.MAX_MARKS)
        val kinds = IntArray(SwipeGuide.MAX_MARKS)
        assertEquals(null, Touchpad(TouchpadSettings(), SurfaceMap(emptyList())).guide())
        // One zone: only Hold after.
        val one = Touchpad(TouchpadSettings(), SurfaceMap(listOf(m(0, MouseButton.LEFT)))).guide()!!
        assertEquals(1, one.marks(0f, -5f, out, kinds))
        assertEquals(SwipeGuide.HOLD, kinds[0])
        // Drag start: the start point trails by Hold after + release (20 + 10) at most, and jumps to the finger once let go.
        val drag = Touchpad(TouchpadSettings(dragStart = true), SurfaceMap(listOf(m(0, MouseButton.LEFT)))).guide()!!
        val start = floatArrayOf(0f, 0f)
        assertEquals(true, drag.drag(start, 0f, -50f, false))
        assertEquals(-20f, start[1], 0.01f)
        assertEquals(false, drag.drag(start, 0f, -5f, true))
        assertEquals(-5f, start[1], 0.01f)
    }
}
