package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.AimStatsAnalyzer
import com.oleroma.gyrophone.core.engine.AimStatsData
import com.oleroma.gyrophone.core.engine.AimStatsRecorder
import com.oleroma.gyrophone.core.engine.LogHist
import com.oleroma.gyrophone.core.engine.StatKey
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimStatsTest {
    private val ms = 1_000_000L

    /** 1 kHz samples from [fromMs] to [toMs], yaw given by [yaw] (°/s) at each ms. */
    private fun AimStatsAnalyzer.run(fromMs: Int, toMs: Int, on: Boolean = true, yaw: (Int) -> Float) {
        for (t in fromMs until toMs) sample(t * ms, yaw(t), 0f, on)
    }

    @Test
    fun steadyTrackIsNoShake() {
        val a = AimStatsAnalyzer()
        val fire = StatKey.of(StatKey.KEY, AndroidKey.BUTTON_R2)
        a.fireKey = fire
        a.event(1000 * ms, fire, true)
        a.event(1100 * ms, fire, false)
        a.run(1, 2000) { 30f }
        a.process()
        val s = a.data.inputs[fire]!!
        assertEquals(1, s.presses)
        assertTrue("before ${s.before.quantile(0.5)}", s.before.quantile(0.5)!! < 0.01)
        assertTrue(s.after.quantile(0.5)!! < 0.01)
        // Turning 30°/s when pressed.
        val sp = s.speedAtPress.quantile(0.5)!!
        assertTrue("speed $sp", sp in 25.0..36.0)
    }

    @Test
    fun twitchBeforePressCounts() {
        val a = AimStatsAnalyzer()
        val fire = StatKey.LEFT_CLICK
        a.event(1000 * ms, fire, true)
        // 40 ms at 25°/s off the line just before the press: 1° off.
        a.run(1, 2000) { t -> if (t in 960 until 1000) 25f else 0f }
        a.process()
        val s = a.data.inputs[fire]!!
        val before = s.before.quantile(0.5)!!
        assertTrue("before $before", before in 0.85..1.2)
        // All sideways (yaw), none up/down.
        assertTrue(s.beforeSide.quantile(0.5)!! in 0.85..1.2)
        assertTrue(s.beforeUp.quantile(0.5)!! < 0.01)
    }

    @Test
    fun kickAfterPressIsThePeak() {
        val a = AimStatsAnalyzer()
        val fire = StatKey.LEFT_CLICK
        a.event(1000 * ms, fire, true)
        // Out 0.5° and back within 100 ms of the press.
        a.run(1, 2000) { t -> when (t) { in 1001..1050 -> 10f; in 1051..1100 -> -10f; else -> 0f } }
        a.process()
        val after = a.data.inputs[fire]!!.after.quantile(0.5)!!
        assertTrue("after $after", after in 0.42..0.6)
    }

    @Test
    fun speedsSplitByFireAndAim() {
        val a = AimStatsAnalyzer()
        a.event(1000 * ms, StatKey.RIGHT_CLICK, true)
        a.event(2000 * ms, StatKey.RIGHT_CLICK, false)
        a.run(1, 3500) { t -> if (t in 1000 until 2000) 20f else 200f }
        a.process()
        val aim = a.data.aiming.quantile(0.5)!!
        val move = a.data.moving.quantile(0.5)!!
        assertTrue("aim $aim", aim in 17.0..23.0)
        assertTrue("move $move", move in 170.0..230.0)
        assertTrue(a.data.firing.total == 0.0)
        assertTrue(a.data.seconds in 3.0..3.5)
    }

    @Test
    fun gyroOffIsNotCounted() {
        val a = AimStatsAnalyzer()
        a.event(1000 * ms, StatKey.LEFT_CLICK, true)
        a.run(1, 2000, on = false) { 50f }
        a.process()
        assertEquals(0.0, a.data.seconds, 0.0)
        assertNull(a.data.inputs[StatKey.LEFT_CLICK])
    }

    @Test
    fun recorderTriggersClicksAndRoundTrip() {
        var now = 0L
        val r = AimStatsRecorder { now }
        val a = AimStatsAnalyzer()
        now = 1000 * ms
        r.axis(AndroidAxis.RTRIGGER, 0.3f)
        r.axis(AndroidAxis.RTRIGGER, 0.6f) // pressed
        r.axis(AndroidAxis.RTRIGGER, 0.45f) // still pressed (hysteresis)
        r.mouseButtons(1)
        r.axis(AndroidAxis.X, 1f) // not a trigger
        now = 1100 * ms
        r.axis(AndroidAxis.RTRIGGER, 0.1f)
        r.mouseButtons(0)
        r.touch("fire_btn", true)
        for (t in 1 until 2000) r.gyro(t * ms, 0f, 0f, true)
        r.drain(a)
        a.process()
        val trig = StatKey.of(StatKey.TRIGGER, AndroidAxis.RTRIGGER)
        assertEquals(1, a.data.inputs[trig]?.presses)
        assertEquals(1, a.data.inputs[StatKey.LEFT_CLICK]?.presses)
        assertEquals(1, a.data.inputs[StatKey.touch("fire_btn")]?.presses)
        assertEquals(3, a.data.inputs.size)
        val back = AimStatsData.decode(a.data.encode())!!
        assertEquals(a.data.inputs.keys, back.inputs.keys)
        assertEquals(a.data.seconds, back.seconds, 1e-9)
    }

    @Test
    fun histQuantiles() {
        val h = LogHist.speed()
        for (v in 1..100) h.add(v.toDouble())
        val med = h.quantile(0.5)!!
        val p90 = h.quantile(0.9)!!
        assertTrue("median $med", med in 44.0..56.0)
        assertTrue("p90 $p90", p90 in 82.0..98.0)
    }
}
