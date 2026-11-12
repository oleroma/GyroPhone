package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.RestFade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RestDetectorTest {
    private val ms = 1_000_000L
    private val feeds = arrayOf(
        GyroDisplayFeed("phone", GyroDisplay.create(GyroDisplayKind.PHONE).copy(levelUp = listOf(0f, 0f, 1f))),
        GyroDisplayFeed("never", GyroDisplay.create(GyroDisplayKind.PHONE)),
    )

    /** [forMs] of samples every 5 ms turning at [dps] (or by [dpsAt]), lying tipped [tipDeg] from screen up. */
    private fun run(r: RestDetector, from: Long, forMs: Long, tipDeg: Float = 0f, dpsAt: (Long) -> Float = { 0.1f }): Long {
        var t = from
        val a = Math.toRadians(tipDeg.toDouble())
        while (t < from + forMs * ms) {
            r.gyro(0f, dpsAt(t), 0f, 0.005f, t)
            r.up(0f, sin(a).toFloat() * 9.8f, cos(a).toFloat() * 9.8f, 9.8f, t, feeds)
            t += 5 * ms
        }
        return t
    }

    @Test
    fun restsAfterTheQuietTimeOnly() {
        val r = RestDetector()
        var t = run(r, 0, 2900)
        assertFalse(r.resting("phone", t))
        t = run(r, t, 200)
        assertTrue(r.resting("phone", t))
        assertFalse(r.resting("never", t)) // never leveled
        assertTrue(r.liveDps(t)!! < 0.2f)
    }

    @Test
    fun heldNeverRests() {
        val r = RestDetector()
        // A steady hand: mostly calm, over the threshold for a moment every half second.
        val t = run(r, 0, 10_000) { if (it / ms % 500 < 20) 1.5f else 0.2f }
        assertFalse(r.resting("phone", t))
    }

    @Test
    fun offLevelTouchedOrStaleDoesNotRest() {
        val r = RestDetector()
        var t = run(r, 0, 5000, tipDeg = 20f)
        assertFalse(r.resting("phone", t))
        t = run(r, t, 5000, tipDeg = 5f)
        assertTrue(r.resting("phone", t))
        r.fingers(true, t)
        assertFalse(r.resting("phone", t + 10_000 * ms))
        r.fingers(false, t)
        assertFalse(r.resting("phone", t + 10 * ms))
        t = run(r, t, 3100)
        assertTrue(r.resting("phone", t))
        r.key(96, true, t)
        assertFalse(r.resting("phone", t))
        r.key(96, false, t)
        // No gyro for a while: can't tell it's still.
        assertFalse(r.resting("phone", t + 4000 * ms))
        r.settings = RestFade(on = false)
        t = run(r, t, 5000)
        assertFalse(r.resting("phone", t))
    }

    @Test
    fun fadesDownOverTimeAndBackAtOnce() {
        val s = RestFade(fadeTo = 0.3f, fadeMs = 500)
        var f = 1f
        f = RestDetector.step(f, true, 250f, s)
        assertEquals(0.65f, f, 1e-4f)
        f = RestDetector.step(f, true, 250f, s)
        assertEquals(0.3f, f, 1e-4f)
        f = RestDetector.step(f, true, 250f, s)
        assertEquals(0.3f, f, 1e-4f)
        assertEquals(1f, RestDetector.step(f, false, 16f, s), 0f)
    }
}
