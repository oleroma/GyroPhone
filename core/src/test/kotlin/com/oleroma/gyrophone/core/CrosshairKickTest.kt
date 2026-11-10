package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.CrosshairKick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrosshairKickTest {
    @Test fun curveRisesBouncesAndSettles() {
        assertEquals(0f, CrosshairKick.at(0f, 0f), 0f)
        assertEquals(1f, CrosshairKick.at(CrosshairKick.RISE_MS, 0f), 1e-3f)
        // It dips under rest a little on the way back, like a recoil spring.
        val low = (30..200).minOf { CrosshairKick.at(it.toFloat(), 0f) }
        assertTrue(low < -0.02f && low > -0.25f)
        assertEquals(0f, CrosshairKick.at(CrosshairKick.LENGTH_MS, 0f), 0f)
        assertEquals(0f, CrosshairKick.CURVE.last(), 0f)
    }

    @Test fun onceKicksOncePerPress() {
        val s = CrosshairKick.State()
        assertFalse(s.step(0, false, CrosshairKick.ONCE, 100))
        s.step(10, true, CrosshairKick.ONCE, 100)
        s.step(34, true, CrosshairKick.ONCE, 100)
        assertEquals(1f, s.value, 0.05f)
        // Held on: no second kick, it settles.
        assertFalse(s.step(400, true, CrosshairKick.ONCE, 100))
        assertEquals(0f, s.value, 0f)
        s.step(410, false, CrosshairKick.ONCE, 100)
        s.step(420, true, CrosshairKick.ONCE, 100)
        s.step(440, true, CrosshairKick.ONCE, 100)
        assertTrue(s.value > 0.3f)
    }

    @Test fun autoRifleKeepsKickingWhileHeld() {
        val s = CrosshairKick.State()
        s.step(0, true, CrosshairKick.AUTO, 100)
        var peaks = 0
        var t = 0L
        var was = 0f
        var rising = true
        while (t < 1000) {
            t += 8
            assertTrue(s.step(t, true, CrosshairKick.AUTO, 100))
            if (rising && s.value < was) { peaks++; rising = false }
            if (s.value > was) rising = true
            was = s.value
        }
        assertEquals(10f, peaks.toFloat(), 1f)
        assertTrue(s.step(t + 8, false, CrosshairKick.AUTO, 100))
        assertFalse(s.step(t + 400, false, CrosshairKick.AUTO, 100))
    }

    @Test fun offNeverKicks() {
        val s = CrosshairKick.State()
        assertFalse(s.step(0, true, CrosshairKick.OFF, 100))
        assertEquals(0f, s.value, 0f)
    }
}
