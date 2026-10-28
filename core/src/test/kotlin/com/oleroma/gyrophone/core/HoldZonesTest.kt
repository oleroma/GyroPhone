package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.SurfaceMap
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
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
    fun onReleaseFiresOnceOnLiftInThatZone() {
        val s = TouchpadSettings(holdZones = listOf(HoldZone(), HoldZone(onRelease = true)))
        val f = Finger(s)
        f.down()
        f.go(0f, -50f); assertEquals(0, f.now) // in zone 2: nothing held
        f.up()
        assertEquals(1, f.presses(z2))
        assertEquals(0, f.now)

        val g = Finger(s)
        g.down(); g.go(0f, -50f); g.go(0f, 20f) // back in zone 1
        assertEquals(z1, g.now)
        g.up()
        assertEquals(0, g.presses(z2))
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
        val f = Finger(TouchpadSettings(holdMode = HoldMode.DIRECTION_CHANGE))
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
        val s = TouchpadSettings(latch = true, holdZones = listOf(HoldZone(keep = ZoneKeep.GOING_ON)))
        val f = Finger(s)
        f.down(); f.go(0f, -50f); f.up()
        assertEquals(z1 or z2, f.now)
        f.tap()
        assertEquals(0, f.now)
    }

    @Test
    fun latchedZonesStayUntilTheNextSwipeReachesOne() {
        val f = Finger(TouchpadSettings(latch = true))
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
        val s = TouchpadSettings(holdZones = listOf(HoldZone(onRelease = true)))
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
}
