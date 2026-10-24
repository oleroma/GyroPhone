package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.RatchetAxes
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release and double tap mappings of touch buttons, and the ratchet's axes. */
class ButtonEventsTest {
    private val MS = 1_000_000L
    private val A = 0x04; private val B = 0x05; private val C = 0x06

    private var t = 0L

    private fun engine(waits: Boolean): MappingEngine {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, doubleTapWaits = waits)
        val p = single(Workspace(
            id = "w", name = "w", touchControls = listOf(b),
            mappings = listOf(
                Mapping(InputSource.Touch("b"), OutputTarget.Key(A)),
                Mapping(InputSource.TouchRelease("b"), OutputTarget.Key(B)),
                Mapping(InputSource.TouchDoubleTap("b"), OutputTarget.Key(C)),
            ),
        ))
        return MappingEngine(p) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()

    /** A 50 ms tap, then 20 ms for its release pulse to show and end. */
    private fun MappingEngine.tap() {
        buttonDown(1, "b", 10f, 10f); keys(); t += 50 * MS; buttonUp(1); keys(); t += 20 * MS; keys()
    }

    @Test
    fun releaseIsAShortPressWhenTheButtonLetsGo() {
        val e = engine(waits = false)
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
        t += 500 * MS
        e.buttonUp(1)
        assertEquals(setOf(B), e.keys())
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun instantPressAndDoubleTap() {
        val e = engine(waits = false)
        e.tap()
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A, C), e.keys()) // the second press is instant too, plus the double tap
        t += 50 * MS
        e.buttonUp(1)
        t += 20 * MS
        e.keys()
        // A third quick tap starts over: no double tap.
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
    }

    @Test
    fun waitingPressFiresAfterTheDoubleTapWindow() {
        val e = engine(waits = true)
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(emptySet<Int>(), e.keys())
        t += 50 * MS
        e.buttonUp(1)
        assertEquals(setOf(B), e.keys()) // release only
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
        t += MappingEngine.DOUBLE_TAP_NS
        assertEquals(setOf(A), e.keys()) // no second tap came: the press
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun waitingDoubleTapNeverPresses() {
        val e = engine(waits = true)
        e.tap()
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(C), e.keys())
        t += 50 * MS
        e.buttonUp(1)
        repeat(5) { t += 100 * MS; assertTrue(A !in e.keys()) }
    }

    @Test
    fun waitingHoldPressesAfterATap() {
        val e = engine(waits = true)
        e.buttonDown(1, "b", 10f, 10f)
        t += 300 * MS
        assertEquals(setOf(A), e.keys())
    }

    private fun gyroEngine(mode: GyroMode, axes: RatchetAxes, touchpadOn: Boolean = true) = MappingEngine(single(Workspace(
        id = "w", name = "w",
        gyro = GyroSettings(mode = mode, ratchetAxes = axes, touchpadRatchet = true),
        touchpad = TouchpadSettings(enabled = touchpadOn),
    )))

    @Test
    fun ratchetOnOneAxis() {
        val e = gyroEngine(GyroMode.ALWAYS, RatchetAxes.X)
        assertEquals(MappingEngine.GYRO_X or MappingEngine.GYRO_Y, e.gyroAxes)
        e.touchpadDown(1, 10f, 10f)
        assertEquals(MappingEngine.GYRO_Y, e.gyroAxes)
        e.touchpadUp(1)
        val en = gyroEngine(GyroMode.OFF, RatchetAxes.Y)
        assertEquals(0, en.gyroAxes)
        en.touchpadDown(1, 10f, 10f)
        assertEquals(MappingEngine.GYRO_Y, en.gyroAxes)
    }

    @Test
    fun touchpadRatchetWorksWithTheTouchpadOff() {
        val e = gyroEngine(GyroMode.OFF, RatchetAxes.BOTH, touchpadOn = false)
        e.touchpadDown(1, 10f, 10f)
        assertTrue(e.gyroActive)
        e.touchpadMove(1, 300f, 10f)
        assertEquals(0, e.snapshot().mouse.dx)
        e.touchpadUp(1)
        assertEquals(0, e.gyroAxes)
    }
}
