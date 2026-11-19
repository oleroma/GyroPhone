package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.debug.GyroTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GyroTimingTest {
    private val ms = 1_000_000L

    @Test fun nothingToSayBeforeAFullSecond() {
        val t = GyroTiming()
        t.sample(ms / 2, 10 * ms)
        assertNull(t.line(11 * ms))
    }

    @Test fun ageIsThisSecondsAverageAndLongest() {
        val t = GyroTiming()
        var now = 1000 * ms
        // 0.4 ms and 0.8 ms in turn for a second, then one that closes the second.
        for (i in 0 until 1000) { t.sample(if (i % 2 == 0) 400_000 else 800_000, now); now += ms }
        t.sample(400_000, now)
        assertEquals("gyro age 0.60 ms avg, 0.80 max", t.line(now))
        // Long after the last sample it says nothing.
        assertNull(t.line(now + 3000 * ms))
    }

    @Test fun aSensorOnAnotherClockHasNoAge() {
        val t = GyroTiming()
        t.sample(-5 * ms, 1000 * ms)
        t.sample(GyroTiming.MAX_AGE_NS + 1, 2000 * ms)
        assertEquals("gyro age unknown (the sensor's clock differs)", t.line(2000 * ms))
    }

    @Test fun toSendCountsFromTheOldestWaitingSample() {
        val t = GyroTiming()
        var now = 1000 * ms
        assertEquals(0L, t.take())
        // Two samples come before the sender looks: the first one is the one waiting.
        t.sample(0, now)
        t.sample(0, now + ms / 2)
        val taken = t.take()
        assertEquals(now, taken)
        assertEquals(0L, t.take())
        t.sent(taken, now + ms) // 1 ms
        // Not due yet: it waits on, also when a newer sample came meanwhile.
        now += 10 * ms
        t.sample(0, now)
        val again = t.take()
        t.sample(0, now + ms)
        t.putBack(again)
        assertEquals(now, t.take())
        t.sent(now, now + 3 * ms) // 3 ms
        // A send a second later closes the second.
        now += 1000 * ms
        t.sample(0, now)
        t.sent(t.take(), now + 2 * ms) // 2 ms
        assertEquals("sample to send 2.00 ms avg, 3.00 max", t.line(now + 2 * ms)?.substringAfter(" · "))
    }
}
