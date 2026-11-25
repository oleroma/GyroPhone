package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.AimStatsAnalyzer
import com.oleroma.gyrophone.core.engine.AimStatsData
import com.oleroma.gyrophone.core.engine.AimStatsRecorder
import com.oleroma.gyrophone.core.engine.LogHist
import com.oleroma.gyrophone.core.engine.PressCurve
import com.oleroma.gyrophone.core.engine.ShakeParts
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
    fun fireSplitsByAim() {
        val a = AimStatsAnalyzer()
        a.event(500 * ms, StatKey.RIGHT_CLICK, true)
        a.event(1000 * ms, StatKey.LEFT_CLICK, true)
        a.event(1100 * ms, StatKey.LEFT_CLICK, false)
        a.event(1200 * ms, StatKey.RIGHT_CLICK, false)
        a.event(2000 * ms, StatKey.LEFT_CLICK, true)
        a.event(2100 * ms, StatKey.LEFT_CLICK, false)
        a.run(1, 3000) { 0f }
        a.process()
        assertEquals(1, a.data.inputs[StatKey.FIRE_AIMED]?.presses)
        assertEquals(1, a.data.inputs[StatKey.FIRE_HIP]?.presses)
        assertEquals(2, a.data.inputs[StatKey.LEFT_CLICK]?.presses)
        assertEquals(1.0, a.data.inputs[StatKey.FIRE_AIMED]!!.release.total, 0.0)
    }

    @Test
    fun handAloneSkipsPresses() {
        val a = AimStatsAnalyzer()
        // A press at 2 s: moments near it are left out; the rest count, while aiming from 3 s.
        a.event(2000 * ms, StatKey.of(StatKey.KEY, AndroidKey.BUTTON_A), true)
        a.event(2050 * ms, StatKey.of(StatKey.KEY, AndroidKey.BUTTON_A), false)
        a.event(3000 * ms, StatKey.RIGHT_CLICK, true)
        // Fed and worked out a quarter second at a time, as the Stats thread does.
        for (c in 0 until 24) { a.run(1 + c * 250, 1 + (c + 1) * 250) { 0f }; a.process() }
        val moving = a.data.inputs[StatKey.HAND_MOVING]!!.presses
        val aiming = a.data.inputs[StatKey.HAND_AIMING]!!.presses
        // Moments every 0.7 s from ~0 to ~5.5 s, minus the first (no history) and those near 2 s and the 3 s aim press.
        assertTrue("moving $moving", moving in 1..3)
        assertTrue("aiming $aiming", aiming in 2..4)
    }

    /**
     * 60 s of a hand that wobbles (random turn, same everywhere) with a press every ~1.6 s; each press
     * adds [kick] (signed, per press) as 40 ms out sideways and 40 ms back.
     */
    private fun pressesWithKick(kick: (Int) -> Float): AimStatsAnalyzer {
        val a = AimStatsAnalyzer()
        val rnd = java.util.Random(7)
        val key = StatKey.of(StatKey.KEY, AndroidKey.BUTTON_Y)
        val presses = (0 until 36).map { 2000 + it * 1600 + rnd.nextInt(200) }
        for (p in presses) { a.event(p * ms, key, true); a.event((p + 60) * ms, key, false) }
        var wobble = 0f
        var cursor = 0.0
        var next = 0
        for (c in 0 until 250) {
            for (t in 1 + c * 250 until 1 + (c + 1) * 250) {
                wobble = 0.9f * wobble + rnd.nextGaussian().toFloat() * 8f
                while (next < presses.size && presses[next] + 100 < t) next++
                val p = presses.getOrNull(next)
                val k = if (p == null) 0f else when (t - p) { in 1..40 -> kick(next); in 41..80 -> -kick(next); else -> 0f }
                a.sample(t * ms, wobble + k, wobble * 0.5f, true)
                // The cursor: 25 px per degree sideways, sent as whole pixels.
                cursor += (wobble + k) * 0.001 * 25
                val dx = cursor.toInt()
                if (dx != 0) { a.cursor(t * ms, dx, 0); cursor -= dx }
            }
            a.process()
        }
        return a
    }

    @Test
    fun sameKickEveryPressIsTheSignature() {
        val a = pressesWithKick { 25f }
        val c = a.data.inputs[StatKey.of(StatKey.KEY, AndroidKey.BUTTON_Y)]!!.curve
        val hand = a.data.inputs[StatKey.HAND_MOVING]!!.curve
        assertTrue("presses ${c.n}", c.n >= 30)
        assertTrue("hand moments ${hand.n}", hand.n >= 10)
        val nat = ShakeParts.natural(c, null, hand, PressCurve.SIDE)
        // The peak is 1° out at +40 ms (step 52), the same each press.
        val at = ShakeParts.of(c, PressCurve.SIDE, PressCurve.CENTER + 2, nat)
        assertTrue("kick ${at.kick}", at.kick in 0.5..1.1)
        assertTrue("jitter ${at.jitter} kick ${at.kick}", at.jitter < at.kick / 2)
        assertTrue("natural $nat", nat > 0.02)
        // Far from the press only the hand is left.
        val far = ShakeParts.of(c, PressCurve.SIDE, 5, nat)
        assertTrue("far press ${far.press} nat $nat", far.press < nat)
        val worst = ShakeParts.worst(c, PressCurve.SIDE, nat, PressCurve.UP, ShakeParts.natural(c, null, hand, PressCurve.UP))
        assertTrue("worst at ${PressCurve.ms(worst)} ms", PressCurve.ms(worst) in 0..80)
        // The cursor shows the same kick in pixels (25 px per degree).
        val px = ShakeParts.of(c, PressCurve.X, PressCurve.CENTER + 2, ShakeParts.natural(c, null, hand, PressCurve.X))
        assertTrue("px kick ${px.kick} vs ${at.kick * 25}", px.kick in at.kick * 20..at.kick * 30)
    }

    @Test
    fun kickThatDiffersEachPressIsJitter() {
        val a = pressesWithKick { i -> if (i % 2 == 0) 25f else -25f }
        val c = a.data.inputs[StatKey.of(StatKey.KEY, AndroidKey.BUTTON_Y)]!!.curve
        val hand = a.data.inputs[StatKey.HAND_MOVING]!!.curve
        val nat = ShakeParts.natural(c, null, hand, PressCurve.SIDE)
        val at = ShakeParts.of(c, PressCurve.SIDE, PressCurve.CENTER + 2, nat)
        assertTrue("jitter ${at.jitter} kick ${at.kick}", at.jitter > 0.5 && at.kick < at.jitter / 2)
        // Up/down untouched: no press part beyond the hand.
        val up = ShakeParts.of(c, PressCurve.UP, PressCurve.CENTER + 2, ShakeParts.natural(c, null, hand, PressCurve.UP))
        assertTrue("up ${up.press} total ${up.total}", up.press < up.total)
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
        r.mouse(1, 0, 0)
        r.axis(AndroidAxis.X, 1f) // not a trigger
        now = 1100 * ms
        r.axis(AndroidAxis.RTRIGGER, 0.1f)
        r.mouse(0, 0, 0)
        r.touch("fire_btn", true)
        for (t in 1 until 2000) r.gyro(t * ms, 0f, 0f, true)
        r.drain(a)
        a.process()
        val trig = StatKey.of(StatKey.TRIGGER, AndroidAxis.RTRIGGER)
        assertEquals(1, a.data.inputs[trig]?.presses)
        assertEquals(1, a.data.inputs[StatKey.LEFT_CLICK]?.presses)
        assertEquals(1, a.data.inputs[StatKey.touch("fire_btn")]?.presses)
        assertEquals(4, a.data.inputs.keys.count { StatKey.kind(it) != StatKey.BASELINE }) // + Hip fire
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
