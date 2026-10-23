package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.hid.HostReconnect
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import com.oleroma.gyrophone.core.hid.ReportScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSchedulerTest {
    private val sent = ArrayList<Pair<Int, ByteArray>>()
    private var accept = true
    private val s = ReportScheduler { id, r -> sent.add(id to r); accept }
    private var now = 0L
    private val interval = 16_666_667L

    private fun tick(state: HidState): Int? {
        val before = sent.size
        s.tick(state, now, 0)
        now += interval
        assertTrue("one report per tick", sent.size - before <= 1)
        return if (sent.size > before) sent.last().first else null
    }

    private fun pad(buttons: Int = 0, lx: Int = 0) = GamepadState(buttons = buttons, lx = lx)
    private fun moving(g: GamepadState, keyboard: KeyboardState = KeyboardState()) =
        HidState(gamepad = g, keyboard = keyboard, mouse = MouseState(dx = 3, dy = 1))

    /** The first tick after start or reset always sends the (empty) keyboard report. */
    private fun prime(state: HidState) = assertEquals(HidDescriptor.REPORT_ID_KEYBOARD, tick(state))

    @Test fun padButtonEdgeGoesAheadOfMouseMotion() {
        prime(moving(pad(lx = 100)))
        // Stick moving and gyro aiming: pad and mouse alternate.
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(moving(pad(lx = 100))))
        assertEquals(HidDescriptor.REPORT_ID_MOUSE, tick(moving(pad(lx = 200))))
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(moving(pad(lx = 300))))
        // It is the mouse's turn, but A went down: the edge goes first...
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(moving(pad(buttons = 1, lx = 400))))
        // ...and the mouse gets the next tick with everything it accumulated.
        assertEquals(HidDescriptor.REPORT_ID_MOUSE, tick(moving(pad(buttons = 1, lx = 500))))
        val m = sent.last().second
        assertEquals(9, (m[1].toInt() and 0xFF) or (m[2].toInt() shl 8))
        // The release is an edge too.
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(moving(pad(buttons = 1, lx = 600))))
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(moving(pad(buttons = 0, lx = 600))))
    }

    @Test fun analogOnlyPadChangesStillAlternateWithMouse() {
        prime(moving(pad()))
        val ids = (1..6).map { tick(moving(pad(lx = it * 100))) }
        assertEquals(
            listOf(HidDescriptor.REPORT_ID_GAMEPAD, HidDescriptor.REPORT_ID_MOUSE).let { it + it + it },
            ids,
        )
    }

    @Test fun keyboardAndMouseButtonEdgesStillComeFirst() {
        prime(moving(pad()))
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(moving(pad())))
        tick(HidState(gamepad = pad(buttons = 1), mouse = MouseState(buttons = 1)))
        assertEquals(HidDescriptor.REPORT_ID_MOUSE, sent.last().first)
        val keyDown = HidState(gamepad = pad(buttons = 3), keyboard = KeyboardState(keys = listOf(4)), mouse = MouseState(buttons = 1))
        assertEquals(HidDescriptor.REPORT_ID_KEYBOARD, tick(keyDown))
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(keyDown))
    }

    @Test fun padTapShownInAKeyboardTickStillReachesTheHost() {
        prime(HidState())
        val w = KeyboardState(keys = listOf(26))
        // A key and a quick A tap in the same snapshot: the keyboard takes the tick...
        assertEquals(HidDescriptor.REPORT_ID_KEYBOARD, tick(HidState(gamepad = pad(buttons = 1), keyboard = w)))
        // ...and A, already released, still goes out, then its release.
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(HidState(keyboard = w)))
        assertEquals(1, sent.last().second[0].toInt())
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(HidState(keyboard = w)))
        assertEquals(0, sent.last().second[0].toInt())
        assertNull(tick(HidState(keyboard = w)))
    }

    @Test fun padReleaseGapShownInAKeyboardTickStillReachesTheHost() {
        prime(HidState())
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(HidState(gamepad = pad(buttons = 1))))
        // A lifts and goes down again (double tap) while the keyboard takes the tick.
        assertEquals(HidDescriptor.REPORT_ID_KEYBOARD, tick(HidState(keyboard = KeyboardState(keys = listOf(4)))))
        val again = HidState(gamepad = pad(buttons = 1), keyboard = KeyboardState(keys = listOf(4)))
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(again))
        assertEquals(0, sent.last().second[0].toInt())
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(again))
        assertEquals(1, sent.last().second[0].toInt())
        assertNull(tick(again))
    }

    @Test fun resetResendsHeldStateIncludingMouseButtons() {
        val held = HidState(gamepad = pad(buttons = 2), keyboard = KeyboardState(keys = listOf(4)), mouse = MouseState(buttons = 1))
        repeat(4) { tick(held) }
        assertNull(tick(held))
        s.reset()
        val ids = (1..4).map { tick(held) }
        assertEquals(
            listOf(HidDescriptor.REPORT_ID_KEYBOARD, HidDescriptor.REPORT_ID_MOUSE, HidDescriptor.REPORT_ID_GAMEPAD, null),
            ids,
        )
        assertEquals(HidDescriptor.REPORT_ID_MOUSE, sent[4].first)
        assertEquals(1, sent[4].second[0].toInt()) // the held left button
    }

    @Test fun refusedReportIsRetried() {
        prime(HidState())
        accept = false
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(HidState(gamepad = pad(buttons = 1))))
        accept = true
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(HidState(gamepad = pad(buttons = 1))))
        assertNull(tick(HidState(gamepad = pad(buttons = 1))))
    }

    @Test fun idleLinkGetsOneGamepadRefreshPerSecond() {
        val idle = HidState(gamepad = pad(buttons = 1))
        prime(idle)
        assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, tick(idle))
        var refreshes = 0
        var ticks = 0
        while (now < 3_100_000_000L) {
            val id = tick(idle)
            ticks++
            if (id != null) {
                assertEquals(HidDescriptor.REPORT_ID_GAMEPAD, id)
                refreshes++
            }
        }
        assertEquals(3, refreshes)
        assertTrue(ticks > 150)
    }

    @Test fun refreshNeverRepeatsMouseReports() {
        prime(HidState(mouse = MouseState(buttons = 1, dx = 5)))
        assertEquals(HidDescriptor.REPORT_ID_MOUSE, tick(HidState(mouse = MouseState(buttons = 1))))
        sent.clear()
        while (now < 5_000_000_000L) tick(HidState(mouse = MouseState(buttons = 1)))
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.none { it.first == HidDescriptor.REPORT_ID_MOUSE })
    }

    @Test fun refreshRepeatsHeldKeysAndOneReleaseThenOnlyThePad() {
        val down = HidState(keyboard = KeyboardState(keys = listOf(4)))
        tick(down); tick(down)
        sent.clear()
        while (now < 4_100_000_000L) tick(down)
        val ids = sent.map { it.first }
        assertEquals(
            listOf(HidDescriptor.REPORT_ID_KEYBOARD, HidDescriptor.REPORT_ID_GAMEPAD).let { it + it },
            ids,
        )
        sent.clear()
        val up = HidState()
        tick(up) // the release itself
        while (now < 10_200_000_000L) tick(up)
        val keyboards = sent.filter { it.first == HidDescriptor.REPORT_ID_KEYBOARD }
        // The release, then one repeat of it to heal a dropped one; never more while no key is held.
        assertEquals(2, keyboards.size)
        assertTrue(keyboards.all { r -> r.second.all { it.toInt() == 0 } })
    }

    @Test fun refreshWaitsWhileOtherReportsFlow() {
        var x = 0
        while (now < 3_000_000_000L) tick(HidState(mouse = MouseState(dx = ++x % 2 + 1)))
        // The first keyboard and pad reports, then mouse only: no refresh while motion flows.
        assertEquals(1, sent.count { it.first == HidDescriptor.REPORT_ID_KEYBOARD })
        assertEquals(1, sent.count { it.first == HidDescriptor.REPORT_ID_GAMEPAD })
    }

    @Test fun inputTickRespectsOneInterval() {
        val tickAt = 1_000L + interval
        // The last report is a whole interval old: tick at once (the caller clamps to now).
        assertEquals(0L + interval, ReportScheduler.inputTickAt(tickAt, 0L, interval))
        // A report went out 5 ms ago: wait until it is one interval old.
        assertEquals(995L + interval, ReportScheduler.inputTickAt(tickAt, 995L, interval))
        // Never later than the periodic tick, never earlier than one interval after a report.
        assertEquals(tickAt, ReportScheduler.inputTickAt(tickAt, 1_000L, interval))
        assertTrue(ReportScheduler.inputTickAt(tickAt, ReportScheduler.NEVER, interval) < 0)
    }
}

class HostReconnectTest {
    private val r = HostReconnect()

    @Test fun unexpectedDropRetriesWithBackoffForAboutAMinute() {
        r.connected("A")
        val delays = generateSequence { r.disconnected("A", canRetry = true) }.takeWhile { it >= 0 }.toList()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L), delays)
        assertNull(r.retrying)
    }

    @Test fun connectingResetsTheBackoff() {
        r.connected("A")
        r.disconnected("A", true); r.disconnected("A", true)
        r.connected("A")
        assertNull(r.retrying)
        assertEquals(1_000L, r.disconnected("A", true))
        assertEquals("A", r.retrying)
    }

    @Test fun userDisconnectIsNeverRetried() {
        r.connected("A")
        r.userDisconnect()
        assertEquals(-1L, r.disconnected("A", true))
        assertNull(r.retrying)
        // Until the next connection.
        r.connected("A")
        assertEquals(1_000L, r.disconnected("A", true))
    }

    @Test fun userDisconnectDuringRetriesCancelsThem() {
        r.connected("A")
        r.disconnected("A", true)
        val g = r.generation
        r.userDisconnect()
        assertTrue(r.generation != g)
        assertNull(r.retrying)
        assertEquals(-1L, r.attemptRefused())
    }

    @Test fun userConnectToAnotherHostCancels() {
        r.connected("A")
        r.disconnected("A", true)
        val g = r.generation
        r.userConnect()
        assertTrue(r.generation != g)
        assertNull(r.retrying)
        // That host failing is not retried, and A is not either.
        assertEquals(-1L, r.disconnected("B", true))
        assertNull(r.retrying)
    }

    @Test fun otherHostDropLeavesRetryAlone() {
        r.connected("A")
        r.disconnected("A", true)
        val g = r.generation
        assertEquals(-1L, r.disconnected("B", true))
        assertEquals(g, r.generation)
        assertEquals("A", r.retrying)
    }

    @Test fun notRetriedWhenNoLongerWantedOrRegistered() {
        r.connected("A")
        assertEquals(-1L, r.disconnected("A", canRetry = false))
        assertNull(r.retrying)
        assertEquals(-1L, r.disconnected("B", true))
    }

    @Test fun refusedAttemptMovesToTheNextDelay() {
        r.connected("A")
        assertEquals(1_000L, r.disconnected("A", true))
        val g = r.generation
        assertEquals(2_000L, r.attemptRefused())
        assertTrue(r.generation != g)
        r.cancel()
        assertEquals(-1L, r.attemptRefused())
    }

    @Test fun neverConnectedHostIsNotRetried() {
        assertEquals(-1L, r.disconnected("A", true))
        assertFalse(r.retrying != null)
    }
}
