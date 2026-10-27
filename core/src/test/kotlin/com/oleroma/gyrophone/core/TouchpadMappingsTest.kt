package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.SurfaceMap
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.withTouchpadMappings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touchpad inputs mapped like buttons ([InputSource.Surface]). */
class TouchpadMappingsTest {
    private val MS = 1_000_000L
    private val D = 0x07; private val Q = 0x14
    private var t = 0L

    private fun m(n: Int, i: SurfaceInput, target: OutputTarget) = Mapping(InputSource.Surface(n, i), target)

    private fun pad(vararg ms: Mapping, s: TouchpadSettings = TouchpadSettings(acceleration = 0f)) =
        Touchpad(s, SurfaceMap(ms.toList())).apply { pxPerDp = 1f; minPulseNanos = 10 * MS }

    /** Polls every 2 ms for [ms], collecting the mouse buttons and key of each poll. */
    private fun Touchpad.run(ms: Long, out: MutableList<Int> = ArrayList()): MutableList<Int> {
        val end = t + ms * MS
        while (t < end) { out.add(poll(t) or (key shl 8)); t += 2 * MS }
        return out
    }

    @Test
    fun heldDirectionIsHeldPastHalfTheDistance() {
        val p = pad(m(1, SurfaceInput.HOLD_RIGHT, OutputTarget.Key(D)), m(1, SurfaceInput.MOTION_X, OutputTarget.MouseAxis(MouseMotion.MOVE_X)))
        p.down(1, 100f, 100f, t)
        p.move(1, 110f, 100f, t); p.run(20)
        p.move(1, 130f, 100f, t)
        assertTrue(p.run(20).any { it shr 8 == D })
        p.move(1, 105f, 100f, t)
        assertEquals(0, p.run(40).last() shr 8)
        p.up(1, t)
    }

    @Test
    fun threeFingerTapPressesItsMapping() {
        val p = pad(m(3, SurfaceInput.TAP, OutputTarget.Key(Q)))
        p.down(1, 100f, 100f, t); p.down(2, 140f, 100f, t); p.down(3, 180f, 100f, t)
        p.run(40)
        p.up(1, t); p.up(2, t); p.up(3, t)
        assertTrue(p.run(40).any { it shr 8 == Q })
    }

    @Test
    fun releaseWithATapTimeFiresOnlyAfterAQuickTouch() {
        val s = TouchpadSettings(acceleration = 0f, releaseTapMs = 100)
        val p = pad(m(1, SurfaceInput.RELEASE, OutputTarget.Mouse(MouseButton.RIGHT)), s = s)
        val r = 1 shl MouseButton.RIGHT.bit
        p.down(1, 100f, 100f, t); p.run(50); p.up(1, t)
        assertTrue(p.run(40).any { it and r != 0 })
        p.down(1, 100f, 100f, t); p.run(300); p.up(1, t)
        assertTrue(p.run(40).none { it and r != 0 })
    }

    @Test
    fun doubleTapWithoutTapDrag() {
        val p = pad(m(1, SurfaceInput.DOUBLE_TAP, OutputTarget.Key(Q)))
        p.down(1, 100f, 100f, t); p.run(30); p.up(1, t)
        assertTrue(p.run(60).none { it shr 8 == Q })
        p.down(1, 100f, 100f, t); p.run(30); p.up(1, t)
        assertTrue(p.run(60).any { it shr 8 == Q })
    }

    @Test
    fun motionAsAFloatingStickInTheEngine() {
        val w = Workspace(
            id = "w", name = "w",
            touchpad = TouchpadSettings(mapped = true),
            mappings = listOf(
                m(1, SurfaceInput.MOTION_X, OutputTarget.PadAxis(GamepadAxis.LX)),
                m(2, SurfaceInput.HOLD, OutputTarget.Pad(GamepadButton.A)),
            ),
        )
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        e.touchpadDown(1, 100f, 100f)
        t += 300 * MS
        e.touchpadMove(1, 120f, 100f) // half of the full distance
        val lx = e.snapshot().gamepad.lx
        assertTrue("lx $lx", lx in 16000..17000)
        e.touchpadDown(2, 200f, 100f)
        e.snapshot(); t += 20 * MS
        assertTrue(e.snapshot().gamepad.buttons and (1 shl GamepadButton.A.bit) != 0)
    }

    @Test
    fun legacySwitchesBecomeMappings() {
        val off = Workspace(id = "w", name = "w", touchpad = TouchpadSettings(tapToClick = false, pinchZoom = false))
            .withTouchpadMappings()
        assertTrue(off.touchpad.mapped)
        assertTrue(off.mappings.none { (it.source as InputSource.Surface).input == SurfaceInput.TAP_DRAG })
        assertTrue(off.mappings.none { it.target == OutputTarget.Zoom })
        val on = Bindings.defaultTouchpadMappings(TouchpadSettings())
        assertTrue(m(3, SurfaceInput.MOTION_X, OutputTarget.SwitchApps) in on)
        assertTrue(m(2, SurfaceInput.TAP, OutputTarget.Mouse(MouseButton.RIGHT)) in on)
    }
}
