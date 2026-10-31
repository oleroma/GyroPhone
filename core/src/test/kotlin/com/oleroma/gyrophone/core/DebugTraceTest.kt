package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.debug.DebugTrace
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTraceTest {
    private var now = 1_000_000_000L
    private fun trace(capacity: Int = 100) = DebugTrace(capacity, clock = { now }, wallMillis = { 5_000_000L + now / 1_000_000 })
    private fun texts(t: DebugTrace) = t.latest(1000).map { it.text }

    @Test fun ringKeepsTheNewest() {
        val t = trace(capacity = 3)
        for (i in 1..5) t.note("e$i")
        assertEquals(listOf("e3", "e4", "e5"), texts(t))
    }

    @Test fun outputLogsEdgesAndSumsMotion() {
        val t = trace()
        val a = 1 shl GamepadButton.A.bit
        t.output(HidState(gamepad = GamepadState(buttons = a), keyboard = KeyboardState(keys = listOf(0x1A))))
        t.output(HidState(mouse = MouseState(dx = 3, dy = -1)), gyroDx = 3, gyroDy = -1)
        now += 50_000_000
        t.output(HidState(mouse = MouseState(dx = 2, buttons = 1)))
        now += 60_000_000
        t.output(HidState(mouse = MouseState(dx = 1)))
        assertEquals(
            listOf("pad A down", "key W down", "pad A up", "key W up", "mouse left down", "mouse left up", "mouse move 6,-1 (gyro 3,-1) in 110 ms"),
            texts(t),
        )
    }

    @Test fun motionIsItsOwnKindAndCanBeFilteredOut() {
        val t = trace()
        t.output(HidState(gamepad = GamepadState(buttons = 1, lx = 32767)))
        val shown = t.latest(10, DebugTrace.Kind.entries.toSet() - DebugTrace.Kind.MOVE).map { it.text }
        assertEquals(listOf("pad A down"), shown)
        assertEquals(listOf("pad LX 100%"), t.latest(10, setOf(DebugTrace.Kind.MOVE)).map { it.text })
    }

    @Test fun sticksAreThrottledButReachZero() {
        val t = trace()
        t.output(HidState(gamepad = GamepadState(lx = 16384)))
        now += 10_000_000
        t.output(HidState(gamepad = GamepadState(lx = 20000))) // too soon
        now += 10_000_000
        t.output(HidState(gamepad = GamepadState(lx = 0))) // centring always shows
        assertEquals(listOf("pad LX 50%", "pad LX 0%"), texts(t))
    }

    @Test fun saveKeepsTheLast30sWithWallTimes() {
        val t = trace()
        t.note("old")
        now += 31_000_000_000L
        t.note("new")
        val lines = t.save("phone", listOf("x: y")).lines().filter { it.isNotEmpty() }
        assertTrue(lines[0].startsWith("# GyroPhone debug recording, side phone"))
        assertEquals("# x: y", lines[2])
        assertEquals(listOf("${5_000_000L + now / 1_000_000}\tphone\tnote\tnew"), lines.drop(3))
    }

    @Test fun engineLogsWhatAnInputMapsTo() {
        val e = MappingEngine(controllerProfile)
        val t = trace()
        e.trace = t
        e.setKey(AndroidKey.BUTTON_A, true)
        e.snapshot()
        val log = texts(t)
        assertTrue(log.toString(), log.any { it.startsWith("any device: A down → ") && "Gamepad A" in it })
        assertTrue(log.toString(), "pad A down" in log)
        assertTrue(log.toString(), log.any { it.startsWith("layer: Base") })
    }
}
