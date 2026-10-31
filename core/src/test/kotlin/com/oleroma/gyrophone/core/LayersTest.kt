package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.layered
import com.oleroma.gyrophone.core.model.withSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Mapping sets: set, hold, override and override hold, Nothing, held inputs across a change. */
class LayersTest {
    private val ms = 1_000_000L
    private val xbox = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val a = 1 shl GamepadButton.A.bit
    private val b = 1 shl GamepadButton.B.bit
    private val x = 1 shl GamepadButton.X.bit

    private fun touch(id: String, t: OutputTarget, set: Int = 0) = Mapping(InputSource.Touch(id), t, set)
    private fun pad(btn: GamepadButton) = OutputTarget.Pad(btn)
    private fun layer(set: Int, override: Boolean = false, held: Boolean = false, switch: Boolean = false) =
        OutputTarget.Layer(set, override, held, switch)

    private var now = 0L

    private fun engine(touch: List<Mapping>, padMappings: List<Mapping> = emptyList(), follow: Set<String> = emptySet()) = MappingEngine(
        single(
            Workspace(
                id = "w", name = "w", mappings = touch,
                touchControls = follow.map { TouchControl(it, TouchKind.BUTTON, "", 0.5f, 0.5f, followLayers = true) },
                pads = listOf(AttachedPad(id = "xb", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13), mappings = padMappings)),
            ),
        ),
    ) { now }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 4 * ms }

    private fun MappingEngine.tap(id: String) {
        setTouch(id, true); now += ms; setTouch(id, false); now += ms
    }

    private fun MappingEngine.buttons(): Int = snapshot().gamepad.buttons

    @Test
    fun mappingSetSwitchesEverythingAndStays() {
        val e = engine(listOf(
            touch("s", layer(1)), touch("s", layer(0), set = 1),
            touch("b", pad(GamepadButton.A)), touch("b", pad(GamepadButton.B), set = 1),
            touch("c", pad(GamepadButton.X)),
        ))
        e.tap("s")
        assertEquals(1, e.layers.set)
        e.setTouch("b", true); assertEquals(b, e.buttons()); e.setTouch("b", false)
        e.setTouch("c", true); assertEquals(0, e.buttons()); e.setTouch("c", false) // set 1 doesn't map it
        e.tap("s")
        assertEquals(0, e.layers.set)
        e.setTouch("b", true); assertEquals(a, e.buttons())
    }

    @Test
    fun theInputHoldingALayerAlsoPressesWhatTheNewSetMapsIt() {
        val e = engine(listOf(
            touch("s", layer(1, held = true)), touch("s", pad(GamepadButton.A)),
            touch("s", pad(GamepadButton.B), set = 1), touch("s", layer(0), set = 1),
        ))
        e.setTouch("s", true)
        assertEquals(1, e.layers.set) // set 1's layer action on s waits: no flipping back
        assertEquals(a or b, e.buttons())
        e.setTouch("s", false)
        assertEquals(0, e.layers.set)
        assertEquals(0, e.buttons())
    }

    @Test
    fun aHeldDirectionZoneHoldingALayerPressesTheNewSetsMappingForIt() {
        fun h(zone: Int, t: OutputTarget, set: Int = 0) = Mapping(InputSource.Surface(1, SurfaceInput.HOLD_UP, zone = zone), t, set)
        val e = MappingEngine(
            single(
                Workspace(
                    id = "w", name = "w", touchpad = com.oleroma.gyrophone.core.model.TouchpadSettings(mapped = true, acceleration = 0f),
                    sets = listOf(com.oleroma.gyrophone.core.model.MappingSet(1)),
                    mappings = listOf(h(0, pad(GamepadButton.A)), h(1, layer(1, held = true)), h(1, pad(GamepadButton.B), set = 1)),
                ),
            ),
        ) { now }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 4 * ms }
        var y = 300f
        e.touchpadDown(1, 100f, y); e.snapshot()
        repeat(50) { now += 2 * ms; y -= 1f; e.touchpadMove(1, 100f, y); e.snapshot() }
        repeat(10) { now += 2 * ms; e.snapshot() }
        assertEquals(1, e.layers.set)
        assertEquals(b, e.buttons())
    }

    @Test
    fun mappingHoldGoesBackOnRelease() {
        val e = engine(listOf(
            touch("s", layer(1, held = true)),
            touch("b", pad(GamepadButton.A)), touch("b", pad(GamepadButton.B), set = 1),
        ))
        e.setTouch("s", true) // set 1 doesn't map s: it still holds the action
        e.setTouch("b", true); assertEquals(b, e.buttons()); e.setTouch("b", false)
        e.setTouch("s", false)
        assertEquals(0, e.layers.set)
        e.setTouch("b", true); assertEquals(a, e.buttons())
    }

    @Test
    fun overrideReplacesOnlyWhatItMaps() {
        val e = engine(listOf(
            touch("s", layer(1, override = true)),
            touch("b", pad(GamepadButton.A)), touch("b", pad(GamepadButton.B), set = 1),
            touch("c", pad(GamepadButton.X)),
        ))
        e.tap("s")
        assertEquals(listOf(1), e.layers.overrides)
        e.setTouch("b", true); e.setTouch("c", true)
        assertEquals(b or x, e.buttons())
        e.setTouch("b", false); e.setTouch("c", false)
        e.tap("s") // pressed again: off
        assertEquals(emptyList<Int>(), e.layers.overrides)
        e.setTouch("b", true); assertEquals(a, e.buttons())
    }

    @Test
    fun overrideHoldWithNothingBlocksAnInput() {
        val e = engine(listOf(
            touch("s", layer(2, override = true, held = true)),
            touch("c", pad(GamepadButton.X)), touch("c", OutputTarget.NoOutput, set = 2),
        ))
        e.setTouch("s", true)
        e.setTouch("c", true); assertEquals(0, e.buttons()); e.setTouch("c", false)
        e.setTouch("s", false)
        e.setTouch("c", true); assertEquals(x, e.buttons())
    }

    @Test
    fun topmostOverrideWins() {
        val m = listOf(
            Mapping(InputSource.Touch("b"), pad(GamepadButton.A)),
            Mapping(InputSource.Touch("b"), pad(GamepadButton.B), 1),
            Mapping(InputSource.Touch("b"), pad(GamepadButton.X), 2),
            Mapping(InputSource.Touch("c"), pad(GamepadButton.Y), 1),
        )
        val top = layered(m, 0, listOf(1, 2))
        assertEquals(setOf(pad(GamepadButton.X), pad(GamepadButton.Y)), top.map { it.target }.toSet())
        val base = m.take(1)
        assertSame(base, layered(base, 0, emptyList()))
    }

    @Test
    fun heldButtonsKeepTheirOutputUntilReleased() {
        val e = engine(listOf(
            touch("s", layer(1)),
            touch("b", pad(GamepadButton.A)), touch("b", pad(GamepadButton.B), set = 1),
        ))
        e.setTouch("b", true)
        e.tap("s")
        assertEquals(a, e.buttons()) // keep: still A
        e.setTouch("b", false); e.buttons()
        e.setTouch("b", true)
        assertEquals(b, e.buttons())
    }

    @Test
    fun buttonsThatFollowLayersTakeTheNewSetAtOnce() {
        val e = engine(listOf(
            touch("s", layer(1)),
            touch("b", pad(GamepadButton.A)), touch("b", pad(GamepadButton.B), set = 1),
        ), follow = setOf("b"))
        e.setTouch("b", true)
        e.tap("s")
        assertEquals(b, e.buttons())
    }

    @Test
    fun theSourceOfASetChangeDoesNotBounceBack() {
        // s maps to "set 0" in set 1: the press that switched to 1 must not also switch back.
        val e = engine(listOf(touch("s", layer(1)), touch("s", layer(0), set = 1)), follow = setOf("s"))
        e.setTouch("s", true)
        e.buttons()
        assertEquals(1, e.layers.set)
        e.setTouch("s", false)
        assertEquals(1, e.layers.set)
    }

    @Test
    fun layerStateIsSharedByControllerAndScreen() {
        val e = engine(
            listOf(touch("b", pad(GamepadButton.A)), touch("b", pad(GamepadButton.B), set = 1)),
            listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_Y), layer(1, held = true))),
        )
        e.setKey(xbox, AndroidKey.BUTTON_Y, true)
        e.setTouch("b", true)
        assertEquals(b, e.buttons())
        e.setTouch("b", false)
        e.setKey(xbox, AndroidKey.BUTTON_Y, false)
        e.setTouch("b", true)
        assertEquals(a, e.buttons())
    }

    @Test
    fun controllerKeysOnlyMappedInAnotherSetAreStillConsumed() {
        val e = engine(emptyList(), listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_B), pad(GamepadButton.B), 1)))
        assertEquals(true, e.setKey(xbox, AndroidKey.BUTTON_B, true))
        assertEquals(0, e.buttons())
    }

    @Test
    fun workspaceReloadGoesBackToBase() {
        val e = engine(listOf(touch("s", layer(1)), touch("b", pad(GamepadButton.B), set = 1)))
        e.tap("s")
        assertEquals(1, e.layers.set)
        e.workspaceIndex = 0
        assertEquals(0, e.layers.set)
    }

    @Test
    fun touchpadHoldLetsGoAfterTheSetUnmapsIt() {
        val hold = Mapping(InputSource.Surface(1, SurfaceInput.HOLD), layer(1, held = true))
        val e = MappingEngine(
            single(Workspace(id = "w", name = "w", mappings = listOf(hold, touch("b", pad(GamepadButton.B), set = 1)))),
        ) { now }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 4 * ms }
        e.touchpadDown(1, 10f, 10f)
        repeat(10) { now += 2 * ms; e.buttons() }
        assertEquals(1, e.layers.set)
        e.touchpadUp(1)
        repeat(10) { now += 2 * ms; e.buttons() }
        assertEquals(0, e.layers.set)
    }



    @Test
    fun aStickHeldThroughASetChangeFollowsTheNewSetAtOnce() {
        val lx = OutputTarget.PadAxis(com.oleroma.gyrophone.core.model.GamepadAxis.LX)
        val rx = OutputTarget.PadAxis(com.oleroma.gyrophone.core.model.GamepadAxis.RX)
        val e = engine(
            listOf(touch("s", layer(1))),
            listOf(
                Mapping(InputSource.Axis(com.oleroma.gyrophone.core.model.AndroidAxis.X), lx),
                Mapping(InputSource.Axis(com.oleroma.gyrophone.core.model.AndroidAxis.X), rx, 1),
            ),
        )
        e.setAxis(xbox, com.oleroma.gyrophone.core.model.AndroidAxis.X, 0.8f)
        assertEquals(true, e.snapshot().gamepad.lx > 0)
        e.tap("s")
        val g = e.snapshot().gamepad
        assertEquals(0, g.lx)
        assertEquals(true, g.rx > 0) // the stick moves what set 1 maps it to, not nothing
    }

    @Test
    fun overlappingHoldsLetGoInAnyOrderEndBackInBase() {
        val e = engine(listOf(touch("p", layer(1, held = true)), touch("q", layer(2, held = true), set = 1)))
        e.setTouch("p", true); assertEquals(1, e.layers.set)
        e.setTouch("q", true); assertEquals(2, e.layers.set)
        e.setTouch("p", false); assertEquals(2, e.layers.set) // q still holds set 2
        e.setTouch("q", false)
        assertEquals(0, e.layers.set) // not set 1: p, which brought it, was let go already
    }

    @Test
    fun twoHoldsOfTheSameLayerStayUntilBothLetGo() {
        val e = engine(listOf(touch("p", layer(1, held = true)), touch("q", layer(1, held = true), set = 1)))
        e.setTouch("p", true); e.setTouch("q", true)
        e.setTouch("p", false)
        assertEquals(1, e.layers.set) // q still holds Layer 1
        e.setTouch("q", false)
        assertEquals(0, e.layers.set)
    }
}
