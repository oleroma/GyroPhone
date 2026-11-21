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

    @Test fun saveKeepsTheLast3MinutesWithWallTimes() {
        val t = trace()
        t.note("old")
        now += 181_000_000_000L
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
        // The input paths only queue; nothing is made into text until the diagnostics thread drains.
        assertTrue(texts(t).none { it.startsWith("any device:") })
        t.drain()
        val log = texts(t)
        assertTrue(log.toString(), log.any { it.startsWith("any device: A down → ") && "Gamepad A" in it })
        assertTrue(log.toString(), "pad A down" in log)
        assertTrue(log.toString(), log.any { it.startsWith("layer: Base") })
    }

    @Test fun everyMappingThatPressesIsLoggedOnAndOff() {
        val e = MappingEngine(controllerProfile)
        val t = trace()
        e.trace = t
        e.setKey(AndroidKey.BUTTON_A, true)
        e.snapshot()
        e.snapshot() // still pressing: no second line
        e.setKey(AndroidKey.BUTTON_A, false)
        e.snapshot()
        t.drain()
        val log = texts(t)
        assertEquals(log.toString(), 1, log.count { it.startsWith("on: ") && "A" in it && "Gamepad A" in it })
        assertEquals(log.toString(), 1, log.count { it.startsWith("off: ") && "Gamepad A" in it })
    }

    @Test fun heldInputsListsKeysHeld() {
        val e = MappingEngine(controllerProfile)
        e.setKey(AndroidKey.BUTTON_A, true)
        assertEquals(listOf("A"), e.heldInputs())
        e.setKey(AndroidKey.BUTTON_A, false)
        assertEquals(emptyList<String>(), e.heldInputs())
    }

    @Test fun queuedOutputIsComparedWhenDrained() {
        val t = trace()
        t.queueOutput(HidState(gamepad = GamepadState(buttons = 1 shl GamepadButton.A.bit)))
        assertEquals(emptyList<String>(), texts(t))
        t.drain()
        assertEquals(listOf("pad A down"), texts(t))
    }

    @Test fun drainingFromSeveralThreadsKeepsTheOrder() {
        val t = trace(capacity = 40_000)
        val down = HidState(gamepad = GamepadState(buttons = 1 shl GamepadButton.A.bit))
        val up = HidState()
        val pairs = 5_000
        repeat(pairs) { t.queueOutput(down); t.queueOutput(up) }
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = List(4) { Thread { start.await(); t.drain() } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        val lines = t.latest(pairs * 2 + 1).map { it.text }
        assertEquals(pairs * 2, lines.size)
        lines.forEachIndexed { i, line -> assertEquals("line $i", if (i % 2 == 0) "pad A down" else "pad A up", line) }
    }
}
