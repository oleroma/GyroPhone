package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.Output
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.TriggerUpgrade
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch directions: their triggers, a swipe as a repeating Fire once, a latch as Sticky. */
class TouchDirectionsTest {
    private val MS = 1_000_000L
    private val A = 0x04
    private val B = 0x05
    private var t = 0L

    private fun right(target: OutputTarget, trigger: Trigger = Trigger.DEFAULT) =
        Mapping(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT), target, trigger = trigger)

    private fun engine(vararg ms: Mapping, touchpad: TouchpadSettings = TouchpadSettings(acceleration = 0f)): MappingEngine {
        val w = Workspace(id = "w", name = "w", touchpad = touchpad, mappings = ms.toList())
        val p = Profile(id = "p", name = "p", workspaces = listOf(w), defaults = WorkspaceDefaults.builtin.copy(mappings = emptyList()))
        return MappingEngine(p) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()

    /** Polls every 5 ms for [ms], collecting the keys seen. */
    private fun MappingEngine.run(ms: Int): List<Set<Int>> = (0 until ms / 5).map { t += 5 * MS; keys() }

    private fun MappingEngine.drag(toX: Float) {
        touchpadDown(1, 100f, 100f); run(10)
        var x = 100f
        while (x < toX) {
            x += 5f; t += 5 * MS; touchpadMove(1, x, 100f); keys()
        }
    }

    @Test
    fun aDragIsHeldAndASwipeFiresOnLeaving() {
        val e = engine(right(OutputTarget.Key(A)), right(OutputTarget.Key(B), Trigger.RELEASE))
        e.drag(160f)
        assertTrue(A in e.keys())
        assertTrue(B !in e.keys())
        e.touchpadUp(1)
        val seen = e.run(40)
        assertTrue(seen.any { B in it }) // Release as the direction lets go
        assertEquals(emptySet<Int>(), seen.last())
    }

    @Test
    fun swipesBecomeRepeatingFireOnce() {
        val old = Mapping(InputSource.Surface(1, SurfaceInput.SWIPE_RIGHT), OutputTarget.Key(A))
        assertEquals(listOf(right(OutputTarget.Key(A), Trigger.SWIPE)), TriggerUpgrade.mappings(listOf(old)))
        val e = engine(right(OutputTarget.Key(A), Trigger.SWIPE))
        e.drag(400f)
        val seen = ArrayList<Set<Int>>()
        seen += e.run(40)
        // More than one press over a long drag: it repeats.
        e.touchpadUp(1)
        seen += e.run(40)
        assertEquals(emptySet<Int>(), seen.last())
    }

    @Test
    fun aLatchBecomesSticky() {
        val w = Workspace(
            id = "w", name = "w", touchpad = TouchpadSettings(latch = true),
            mappings = listOf(right(OutputTarget.Key(A)), Mapping(InputSource.Surface(1, SurfaceInput.MOTION_X), OutputTarget.MouseAxis(MouseMotion.MOVE_X))),
        )
        val up = TriggerUpgrade.workspace(w)
        assertEquals(right(OutputTarget.Key(A), Trigger(output = Output.STICKY)), up.mappings.first())
        assertEquals(false, up.touchpad.latch)
        assertTrue(TriggerUpgrade.workspace(up) === up)
    }
}
