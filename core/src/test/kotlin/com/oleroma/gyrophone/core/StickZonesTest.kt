package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.StickDiagonal
import com.oleroma.gyrophone.core.model.StickZones
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.contains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch stick deadzone and radial zones, and rectangular buttons. */
class StickZonesTest {
    private val W = 0x1A; private val A = 0x04; private val S = 0x16; private val D = 0x07; private val Q = 0x14

    private fun wasd(zones: StickZones, deadzone: Float = 0f, extra: List<Mapping> = emptyList()): MappingEngine {
        val stick = TouchControl("move", TouchKind.STICK, "", 0.5f, 0.5f, deadzone = deadzone, zones = zones)
        val mappings = Bindings.applyStickPreset(emptyList(), Bindings.touchInputs(stick), Bindings.StickPreset.WASD) +
            Mapping(InputSource.TouchStick("move", StickAxis.X), OutputTarget.PadAxis(GamepadAxis.LX)) + extra
        return MappingEngine(single(Workspace(id = "w", name = "w", touchControls = listOf(stick), mappings = mappings)))
    }

    private fun MappingEngine.keysAt(x: Float, y: Float): Set<Int> {
        setTouchStick("move", x, y)
        return snapshot().keyboard.keys.toSet()
    }

    private val diag = 0.7071f

    @Test
    fun overlapPressesTwoOnADiagonal() {
        val e = wasd(StickZones.OVERLAP)
        assertEquals(setOf(W, D), e.keysAt(diag, -diag))
        assertEquals(setOf(D), e.keysAt(0.9f, -0.2f))
    }

    @Test
    fun fourZonesPressOneDirection() {
        val e = wasd(StickZones.FOUR)
        assertEquals(setOf(D), e.keysAt(0.8f, -0.6f))
        assertEquals(setOf(W), e.keysAt(0.6f, -0.8f))
        assertEquals(setOf(A), e.keysAt(-1f, 0.1f))
        assertEquals(setOf(S), e.keysAt(0.1f, 1f))
        assertEquals(emptySet<Int>(), e.keysAt(0.2f, 0.2f)) // under the press threshold
    }

    @Test
    fun eightZonesDiagonalWithoutMappingPressesBothNeighbours() {
        val e = wasd(StickZones.EIGHT)
        assertEquals(setOf(W, D), e.keysAt(diag, -diag))
        assertEquals(setOf(D), e.keysAt(0.95f, -0.3f))
        assertEquals(setOf(S, A), e.keysAt(-diag, diag))
    }

    @Test
    fun eightZonesDiagonalWithItsOwnMapping() {
        val e = wasd(StickZones.EIGHT, extra = listOf(Mapping(InputSource.TouchStickDiagonal("move", StickDiagonal.UP_LEFT), OutputTarget.Key(Q))))
        assertEquals(setOf(Q), e.keysAt(-diag, -diag))
        assertEquals(setOf(W, D), e.keysAt(diag, -diag))
        assertEquals(setOf(W), e.keysAt(-0.2f, -0.95f))
    }

    @Test
    fun deadzoneCentresAndRescales() {
        val e = wasd(StickZones.OVERLAP, deadzone = 0.3f)
        e.setTouchStick("move", 0.25f, 0f)
        assertEquals(0, e.snapshot().gamepad.lx)
        e.setTouchStick("move", 0.65f, 0f)
        assertNear(GamepadState.STICK_MAX / 2, e.snapshot().gamepad.lx, GamepadState.STICK_MAX / 100)
        e.setTouchStick("move", 1f, 0f)
        assertNear(GamepadState.STICK_MAX, e.snapshot().gamepad.lx, 1)
    }

    @Test
    fun eightZoneStickListsDiagonals() {
        val c = TouchControl("s", TouchKind.STICK, "", 0.5f, 0.5f)
        // X, Y, four halves, then its press; 8 zones add the diagonals before that.
        assertEquals(7, Bindings.touchInputs(c).size)
        assertEquals(11, Bindings.touchInputs(c.copy(zones = StickZones.EIGHT)).size)
    }

    @Test
    fun wideSquareHitsAlongItsWidthOnly() {
        // 1000 x 500 pad: size 0.4 of the shorter side = 200 px wide, aspect 0.25 = 50 px tall.
        val c = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, 0.4f, TouchShape.SQUARE, aspect = 0.25f)
        assertTrue(c.contains(500f + 95f, 250f, 1000f, 500f, slop = 1f))
        assertFalse(c.contains(500f, 250f + 40f, 1000f, 500f, slop = 1f))
        assertTrue(c.contains(500f, 250f + 24f, 1000f, 500f, slop = 1f))
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)

    @Test
    fun aDiagonalMappedOnlyInAnotherSetLeavesTheTwoHalvesInThisOne() {
        val e = wasd(StickZones.EIGHT, extra = listOf(Mapping(InputSource.TouchStickDiagonal("move", StickDiagonal.UP_RIGHT), OutputTarget.Key(Q), set = 1)))
        assertEquals(setOf(W, D), e.keysAt(diag, -diag)) // Base maps no diagonal: up-right is W + D
    }
}
