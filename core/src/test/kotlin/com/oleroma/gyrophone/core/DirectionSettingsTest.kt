package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.DirectionTracker
import com.oleroma.gyrophone.core.model.DirSettings
import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.evenPies
import com.oleroma.gyrophone.core.model.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each direction's own settings over the shared ones ([DirSettings]). */
class DirectionSettingsTest {
    private val up = 1 shl Direction.UP.ordinal
    private val right = 1 shl Direction.RIGHT.ordinal

    /** Steps of 1 dp along ([dx], [dy]) [n] times; the swipes fired, or-ed. */
    private fun DirectionTracker.go(s: TouchpadSettings, dx: Float, dy: Float, n: Int): Int {
        var fired = 0
        repeat(n) { fired = fired or step(dx, dy, s) }
        return fired
    }

    @Test
    fun swipeDistanceOfItsOwn() {
        val s = TouchpadSettings(swipeDp = 20f).withDir(Direction.UP) { it.copy(swipeDp = 60f) }
        val t = DirectionTracker().apply { start() }
        assertEquals(0, t.go(s, 0f, -1f, 40))
        assertEquals(up, t.go(s, 0f, -1f, 30))
        t.start()
        assertEquals(right, t.go(s, 1f, 0f, 30))
    }

    @Test
    fun holdDistanceOfItsOwn() {
        val s = TouchpadSettings(holdDp = 20f, holdMode = HoldMode.DIRECTION_CHANGE).withDir(Direction.UP) { it.copy(holdDp = 50f) }
        val t = DirectionTracker().apply { start() }
        t.go(s, 0f, -1f, 30)
        assertEquals(0, t.held)
        t.go(s, 0f, -1f, 30)
        assertEquals(up, t.held)
        t.start()
        t.go(s, 1f, 0f, 30)
        assertEquals(right, t.held)
    }

    @Test
    fun holdPieOfItsOwn() {
        // Swipes in 90° pies; Up's hold only in a 30° one, so a 30° slant holds nothing from the start point.
        val s = TouchpadSettings(holdDp = 20f, pies = List(4) { 90f }).withDir(Direction.UP) { it.copy(holdPie = 30f) }
        val t = DirectionTracker().apply { start() }
        t.go(s, 0.5f, -0.866f, 40)
        assertEquals(0, t.held)
        t.start()
        t.go(s, 0f, -1f, 40)
        assertEquals(up, t.held)
    }

    @Test
    fun sharedValueOfALevelHidesTheParentsDirectionValue() {
        val pad = TouchpadSettings(swipeDp = 20f).withDir(Direction.UP) { it.copy(swipeDp = 60f) }
        assertEquals(60f, pad.with(SurfaceSettings()).swipeDpOf(Direction.UP))
        // The button sets the shared swipe distance: every direction of it uses that.
        assertEquals(30f, pad.with(SurfaceSettings(swipeDp = 30f)).swipeDpOf(Direction.UP))
    }

    @Test
    fun unevenPiesBecomeDirectionsOwn() {
        val s = TouchpadSettings(pies = listOf(60f, 90f, 90f, 120f)).evenPies()
        assertEquals(List(4) { 60f }, s.pies)
        assertNull(s.dir(Direction.UP).swipePie)
        assertEquals(90f, s.pieDeg(Direction.RIGHT, hold = false))
        assertEquals(120f, s.pieDeg(Direction.LEFT, hold = true))
    }
}
