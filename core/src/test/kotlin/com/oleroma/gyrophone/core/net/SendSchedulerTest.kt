package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.net.SendScheduler.Companion.KEEPALIVE
import com.oleroma.gyrophone.core.net.SendScheduler.Companion.NONE
import com.oleroma.gyrophone.core.net.SendScheduler.Companion.NORMAL
import com.oleroma.gyrophone.core.net.SendScheduler.Companion.REPEAT
import org.junit.Assert.assertEquals
import org.junit.Test

class SendSchedulerTest {
    private val ms = 1_000_000L

    /** Polls and, when something is due, marks it sent: the pump's loop body. */
    private fun SendScheduler.step(now: Long, dirty: Boolean = false, discrete: Boolean = false): Int {
        val k = poll(now, dirty, discrete)
        if (k != NONE) markSent(now, k, discrete && k == NORMAL)
        return k
    }

    @Test fun firstPacketAndChangesGoOutImmediately() {
        val s = SendScheduler(1000)
        assertEquals(KEEPALIVE, s.step(0))
        assertEquals(NORMAL, s.step(5 * ms, dirty = true))
        assertEquals(NONE, s.step(5 * ms + 1, dirty = false))
    }

    @Test fun capOfOnePacketPerMillisecondAt1000Hz() {
        val s = SendScheduler(1000)
        s.step(0, dirty = true)
        assertEquals(NONE, s.step(ms / 2, dirty = true))
        assertEquals(ms, s.nextDeadline())
        assertEquals(NORMAL, s.step(ms, dirty = true))
    }

    @Test fun sendableAtIsTheEndOfTheRateLimit() {
        val s = SendScheduler(250)
        assertEquals(Long.MIN_VALUE, s.sendableAt())
        s.step(10 * ms, dirty = true)
        assertEquals(14 * ms, s.sendableAt())
        // Before it nothing goes out, however dirty; from it a change does.
        assertEquals(NONE, s.step(14 * ms - 1, dirty = true, discrete = true))
        assertEquals(NORMAL, s.step(s.sendableAt(), dirty = true, discrete = true))
        s.reset()
        assertEquals(Long.MIN_VALUE, s.sendableAt())
    }

    @Test fun repeatsFollowADiscreteChangeAt2And6Ms() {
        val s = SendScheduler(1000)
        s.step(0, dirty = true, discrete = true)
        assertEquals(2 * ms, s.nextDeadline())
        assertEquals(NONE, s.step(2 * ms - 1))
        assertEquals(REPEAT, s.step(2 * ms))
        assertEquals(6 * ms, s.nextDeadline())
        assertEquals(NONE, s.step(5 * ms))
        assertEquals(REPEAT, s.step(6 * ms))
        assertEquals(26 * ms, s.nextDeadline())
        assertEquals(NONE, s.step(25 * ms))
        assertEquals(KEEPALIVE, s.step(26 * ms))
    }

    @Test fun aNewDiscreteChangeReplacesPendingRepeats() {
        val s = SendScheduler(1000)
        s.step(0, dirty = true, discrete = true)
        assertEquals(NORMAL, s.step(3 * ms, dirty = true, discrete = true))
        assertEquals(NONE, s.step(4 * ms))
        assertEquals(REPEAT, s.step(5 * ms))
        assertEquals(REPEAT, s.step(9 * ms))
        assertEquals(NONE, s.step(10 * ms))
    }

    @Test fun mouseOnlyChangesDoNotScheduleRepeats() {
        val s = SendScheduler(1000)
        s.step(0, dirty = true, discrete = false)
        assertEquals(20 * ms, s.nextDeadline())
        assertEquals(NONE, s.step(2 * ms))
    }

    @Test fun keepaliveAfter20MsOfSilence() {
        val s = SendScheduler(1000)
        s.step(0)
        assertEquals(NONE, s.step(19 * ms))
        assertEquals(KEEPALIVE, s.step(20 * ms))
        assertEquals(KEEPALIVE, s.step(40 * ms))
    }

    @Test fun lowerRatesCapHarder() {
        val s250 = SendScheduler(250)
        s250.step(0, dirty = true, discrete = true)
        assertEquals(NONE, s250.step(2 * ms, dirty = true))
        assertEquals(4 * ms, s250.nextDeadline())
        assertEquals(NORMAL, s250.step(4 * ms, dirty = true))

        val s500 = SendScheduler(500)
        s500.step(0, dirty = true, discrete = true)
        assertEquals(NONE, s500.step(ms))
        assertEquals(REPEAT, s500.step(2 * ms)) // +2 ms repeat lands exactly on the 2 ms cap
        assertEquals(NONE, s500.step(3 * ms, dirty = true))
        assertEquals(NORMAL, s500.step(4 * ms, dirty = true))

        // Sustained changes never exceed the rate.
        for ((rate, interval) in listOf(250 to 4 * ms, 500 to 2 * ms, 1000 to ms)) {
            val s = SendScheduler(rate)
            var sent = 0
            var t = 0L
            while (t < 1000 * ms) { if (s.step(t, dirty = true) != NONE) sent++; t += 100_000 }
            assertEquals("rate $rate", (1000 * ms / interval).toInt(), sent)
        }
    }

    @Test fun flagsPerKind() {
        assertEquals(StateFlags.REPEAT, SendScheduler.flagsFor(REPEAT))
        assertEquals(StateFlags.KEEPALIVE, SendScheduler.flagsFor(KEEPALIVE))
        assertEquals(0, SendScheduler.flagsFor(NORMAL))
    }
}
