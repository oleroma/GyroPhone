package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.SurfaceMap
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MoveMode
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Move pies against a wobbly finger: the heading lock with its turn, the pie margin, and a swipe hold stopping the Moves. */
class MoveLockTest {
    private val ms = 1_000_000L

    private val base = TouchpadSettings(
        acceleration = 0f, tapToClick = false, sensitivity = 1f, moveMode = MoveMode.PIE, movePieX = 120f, movePieY = 120f,
    )

    /** One finger in steps every 2 ms; [dx] / [dy] add up the cursor's travel. */
    private inner class Finger(s: TouchpadSettings, map: SurfaceMap? = null) {
        val pad = Touchpad(s, map).apply { pxPerDp = 1f }
        var t = 0L
        var x = 100f
        var y = 300f
        var dx = 0
        var dy = 0

        fun down() = pad.down(1, x, y, t)

        fun step(sx: Float, sy: Float) {
            t += 2 * ms
            x += sx; y += sy
            pad.move(1, x, y, t)
            pad.poll(t)
            dx += pad.dx; dy += pad.dy
            pad.drainMotion()
        }
    }

    /** Sideways 61 dp in 2 dp steps, with a 4 dp wobble down and back up midway; Larger axis pies. */
    private fun wobblySideways(f: Finger) {
        f.down()
        repeat(15) { f.step(2f, 0f) }
        f.step(1.5f, 4f); f.step(1.5f, -4f)
        repeat(14) { f.step(2f, 0f) }
    }

    @Test
    fun withoutTheLockAWobbleDropsSidewaysTravel() {
        val f = Finger(base.copy(movePieX = 90f, movePieY = 90f, moveLock = false, movePieMargin = 0f))
        wobblySideways(f)
        assertTrue("dx ${f.dx}", f.dx < 61)
    }

    @Test
    fun theLockKeepsAllSidewaysTravelThroughAWobble() {
        val f = Finger(base.copy(movePieX = 90f, movePieY = 90f))
        wobblySideways(f)
        assertEquals(61, f.dx)
    }

    @Test
    fun aVerticalStrokeWithASidewaysWiggleNeverMovesX() {
        val wiggle = { f: Finger ->
            f.down()
            repeat(5) { f.step(0f, -3f) }
            // A 2.5 dp wiggle sideways, then back.
            f.step(2.5f, -0.5f); f.step(-2.5f, -0.5f)
            repeat(5) { f.step(0f, -3f) }
        }
        val f = Finger(base).also(wiggle)
        assertEquals(0, f.dx)
        assertEquals(-31, f.dy)
        val unlocked = Finger(base.copy(moveLock = false, movePieMargin = 0f)).also(wiggle)
        assertTrue("dx ${unlocked.dx}", unlocked.dx > 0)
    }

    @Test
    fun aRealTurnLosesNothing() {
        val f = Finger(base)
        f.down()
        repeat(10) { f.step(2f, 0f) }
        assertEquals(20, f.dx)
        // Turned down: the first 6 dp wait for the turn, then go to Move Y at once.
        repeat(2) { f.step(0f, 2f) }
        assertEquals(0, f.dy)
        f.step(0f, 2f)
        assertEquals(6, f.dy)
        repeat(5) { f.step(0f, 2f) }
        assertEquals(16, f.dy)
        assertEquals(20, f.dx)
    }

    @Test
    fun theMarginKeepsAMovingMovePastItsPie() {
        // 60° pies: Move X up to 30° off its axis; a 40° slant keeps it with the margin only.
        val s = base.copy(movePieX = 60f, movePieY = 60f, moveLock = false)
        val slant = { f: Finger -> f.down(); repeat(10) { f.step(2f, 0f) }; repeat(10) { f.step(2f, 1.68f) } }
        val with = Finger(s.copy(movePieMargin = 15f)).also(slant)
        val without = Finger(s.copy(movePieMargin = 0f)).also(slant)
        assertEquals(40, with.dx)
        assertTrue("dx ${without.dx}", without.dx < 40)
    }

    @Test
    fun aSwipeHoldStopsTheMovesUntilTheLift() {
        val s = base.copy(moveMode = MoveMode.FREE, holdDp = 20f)
        val map = SurfaceMap(
            Bindings.defaultTouchpadMappings(s) +
                Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP), OutputTarget.Mouse(MouseButton.LEFT)),
        )
        val f = Finger(s, map)
        f.down()
        repeat(12) { f.step(0f, -2f) } // past the hold distance: held
        val held = f.dx to f.dy
        repeat(10) { f.step(3f, 0f) }
        assertEquals(held, f.dx to f.dy)
        f.t += 2 * ms; f.pad.up(1, f.t)
        f.t += 100 * ms; f.down()
        repeat(5) { f.step(2f, 0f) }
        assertEquals(held.first + 10, f.dx)

        val off = Finger(s.copy(holdStopsMove = false), map)
        off.down()
        repeat(12) { off.step(0f, -2f) }
        repeat(10) { off.step(3f, 0f) }
        assertEquals(30, off.dx)
    }
}
