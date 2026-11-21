package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.FlickStick
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.StickTuning
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flick stick: a flick turns to face the stick's way, rotating it turns with it, by the game's full turn. */
class FlickStickTest {
    @Test fun flickFacesTheWayItPoints() {
        val f = FlickStick()
        assertEquals(0f, f.update(1f, 0f, 0.5f), 0f) // not out far enough
        assertEquals(90f, f.update(1f, 0f, 1f), 1e-3f) // right
        assertTrue(f.out)
        assertEquals(0f, f.update(1f, 0f, 1f), 1e-3f) // held there: no more turn
        assertEquals(90f, f.update(0f, 1f, 1f), 1e-3f) // rotated to down: 90 more
        assertEquals(0f, f.update(0f, 1f, 0.8f), 0f) // between release and engage: still out, same way
        assertTrue(f.out)
        assertEquals(0f, f.update(0f, 0f, 0f), 0f) // let go
        assertFalse(f.out)
        assertEquals(-90f, f.update(-1f, 0f, 1f), 1e-3f) // a new flick left
    }

    @Test fun rotationWrapsTheShortWay() {
        val f = FlickStick()
        f.update(-0.1f, 1f, 1f) // just left of straight down
        // Crossing down to just right of it is a small turn left, not nearly a full turn.
        val d = f.update(0.1f, 1f, 1f)
        assertTrue(d < 0f && d > -20f)
    }

    @Test fun countsKeepTheirRest() {
        val f = FlickStick()
        assertEquals(0, f.counts(0.4f, 1f))
        assertEquals(0, f.counts(0.4f, 1f))
        assertEquals(1, f.counts(0.4f, 1f)) // 1.2 counts so far
    }

    private val turn = GyroSettings(gameCountsPer360 = 3600f) // 10 counts a degree

    @Test fun touchFlickStickTurnsTheMouseAndNotItsMappings() {
        val s = TouchControl("s", TouchKind.STICK, "", 0.5f, 0.5f, flick = true)
        val w = Workspace(
            id = "w", name = "w", touchControls = listOf(s), gyro = turn,
            mappings = listOf(Mapping(InputSource.TouchStick("s", StickAxis.X), OutputTarget.PadAxis(GamepadAxis.RX))),
        )
        val e = MappingEngine(single(w))
        e.setTouchStick("s", 1f, 0f)
        val snap = e.snapshot()
        assertEquals(900, snap.mouse.dx) // 90° right
        assertEquals(0f, e.sourceValue(InputSource.TouchStick("s", StickAxis.X)), 0f)
        e.setTouchStick("s", 0f, 0f)
        assertEquals(0, e.snapshot().mouse.dx)
    }

    @Test fun withoutTheGameTurnItDoesNothing() {
        val s = TouchControl("s", TouchKind.STICK, "", 0.5f, 0.5f, flick = true)
        val e = MappingEngine(single(Workspace(id = "w", name = "w", touchControls = listOf(s))))
        e.setTouchStick("s", 1f, 0f)
        assertEquals(0, e.snapshot().mouse.dx)
    }

    @Test fun padFlickStick() {
        val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
        val w = Workspace(
            id = "w", name = "w", gyro = turn,
            pads = listOf(
                AttachedPad(
                    id = "p", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13),
                    mappings = listOf(Mapping(InputSource.Axis(AndroidAxis.Z), OutputTarget.PadAxis(GamepadAxis.RX))),
                ),
            ),
            stickTunings = mapOf("rs" to StickTuning(flick = true)),
        )
        val e = MappingEngine(single(w))
        e.setAxis(pad, AndroidAxis.RZ, -1f) // up: straight ahead, no turn
        assertEquals(0, e.snapshot().mouse.dx)
        e.setAxes(pad, intArrayOf(AndroidAxis.Z, AndroidAxis.RZ), floatArrayOf(1f, 0f), 2) // rotated to the right
        assertEquals(900, e.snapshot().mouse.dx)
        assertEquals(0f, e.sourceValue(InputSource.Axis(AndroidAxis.Z)), 0f)
    }
}
