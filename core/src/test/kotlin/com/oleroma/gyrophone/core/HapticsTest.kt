package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Haptic targets and the one-finger cursor deadzone. */
class HapticsTest {
    private val MS = 1_000_000L
    private var t = 0L
    private val strong = HapticFeel(system = false, strength = 200, lengthMs = 30)

    private class Calls {
        var ticks = 0
        var held: HapticFeel? = null
        var level = 0f
        var calls = 0
    }

    private fun engine(vararg ms: Mapping): Pair<MappingEngine, Calls> {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f)
        val s = TouchControl("s", TouchKind.STICK, "", 0.2f, 0.5f)
        val w = Workspace(id = "w", name = "w", touchControls = listOf(b, s), mappings = ms.toList(), haptics = strong)
        val e = MappingEngine(single(w)) { t }
        val c = Calls()
        e.hapticSink = com.oleroma.gyrophone.core.engine.HapticSink { ticks, held, level ->
            c.ticks += ticks.size; c.held = held; c.level = level; c.calls++
        }
        return e to c
    }

    @Test
    fun tickOnPressAndHeldWhileHeld() {
        val (e, c) = engine(
            Mapping(InputSource.Touch("b"), OutputTarget.Haptic()),
            Mapping(InputSource.Touch("b"), OutputTarget.Haptic(held = true)),
        )
        e.snapshot()
        e.setTouch("b", true)
        e.snapshot()
        assertEquals(1, c.ticks)
        assertEquals(strong, c.held)
        e.snapshot()
        assertEquals(1, c.ticks) // one tick per press
        e.setTouch("b", false)
        e.snapshot()
        assertNull(c.held)
        val calls = c.calls
        e.snapshot()
        assertEquals(calls, c.calls) // nothing held: no more calls
    }

    @Test
    fun heldFollowsAnAxisAndStepsTick() {
        val (e, c) = engine(
            Mapping(InputSource.TouchStick("s", StickAxis.X), OutputTarget.Haptic(held = true)),
            Mapping(InputSource.TouchStick("s", StickAxis.X), OutputTarget.HapticStep(percent = 25)),
        )
        e.setTouchStick("s", 0.5f, 0f)
        e.snapshot()
        assertEquals(0.5f, c.level, 0.01f)
        assertEquals(1, c.ticks) // to step 2 of 25 %: one tick per snapshot
        e.setTouchStick("s", 0.6f, 0f)
        e.snapshot()
        assertEquals(1, c.ticks) // same step
        e.setTouchStick("s", 0.8f, 0f)
        e.snapshot()
        assertEquals(2, c.ticks)
        e.setTouchStick("s", 0f, 0f)
        e.snapshot()
        assertEquals(3, c.ticks) // back to rest
        assertNull(c.held)
    }

    @Test
    fun cursorDeadzoneHoldsThenCatchesUp() {
        val p = Touchpad(TouchpadSettings(acceleration = 0f, tapToClick = false, cursorDeadzoneDp = 20f, catchUpMs = 0, sensitivity = 1f))
            .apply { pxPerDp = 1f }
        p.down(1, 100f, 100f, t)
        p.move(1, 110f, 100f, t + 10 * MS)
        p.poll(t + 10 * MS)
        assertEquals(0, p.dx) // inside the deadzone
        p.move(1, 125f, 100f, t + 20 * MS)
        p.poll(t + 20 * MS)
        assertEquals(25, p.dx) // all the travel at once
        p.drainMotion()
        p.move(1, 130f, 100f, t + 30 * MS)
        p.poll(t + 30 * MS)
        assertEquals(5, p.dx)
    }

    @Test
    fun cursorDeadzoneRearmsAfterARest() {
        val s = TouchpadSettings(acceleration = 0f, tapToClick = false, cursorDeadzoneDp = 20f, catchUpMs = 0, sensitivity = 1f,
            deadzoneRearmMs = 200, deadzoneStillDp = 3f)
        val p = Touchpad(s).apply { pxPerDp = 1f }
        p.down(1, 100f, 100f, 0)
        p.move(1, 130f, 100f, 10 * MS); p.poll(10 * MS)
        assertEquals(30, p.dx); p.drainMotion()
        p.move(1, 132f, 100f, 50 * MS) // within the still margin
        p.poll(300 * MS) // rested 250 ms: armed again
        p.drainMotion()
        p.move(1, 142f, 100f, 310 * MS); p.poll(310 * MS)
        assertEquals(0, p.dx) // 10 dp from the rest point: held
        p.move(1, 155f, 100f, 320 * MS); p.poll(320 * MS)
        assertEquals(23, p.dx) // past 20 dp from 132: all of it at once
    }

    @Test
    fun cursorCatchUpSpreadsOverTime() {
        val p = Touchpad(TouchpadSettings(acceleration = 0f, tapToClick = false, cursorDeadzoneDp = 20f, catchUpMs = 100, sensitivity = 1f))
            .apply { pxPerDp = 1f }
        p.down(1, 100f, 100f, 0)
        p.move(1, 140f, 100f, 10 * MS)
        p.poll(10 * MS)
        assertTrue(p.dx < 5)
        var total = p.dx
        p.drainMotion()
        for (ms in 20L..200L step 10) { p.poll(ms * MS); total += p.dx; p.drainMotion() }
        assertEquals(40, total)
    }
}
