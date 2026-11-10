package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.CrosshairKick
import com.oleroma.gyrophone.core.engine.LaserTrace
import com.oleroma.gyrophone.core.engine.MuzzleFlash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuzzleFlashTest {
    private val hot = 0xFFFFE04A.toInt()
    private val end = 0xFFFF7A1A.toInt()
    private val base = 0xFF2080FF.toInt()

    @Test fun burstsWhiteThenYellowThenOrangeThenBack() {
        assertEquals(0xFFFFFFFF.toInt(), MuzzleFlash.color(0f, hot, end, base))
        assertEquals(hot, MuzzleFlash.color(MuzzleFlash.HOT_MS, hot, end, base))
        assertEquals(end, MuzzleFlash.color(MuzzleFlash.END_MS, hot, end, base))
        assertEquals(base, MuzzleFlash.color(MuzzleFlash.LENGTH_MS, hot, end, base))
        assertEquals(base, MuzzleFlash.color(-1f, hot, end, base))
        assertEquals(1f, MuzzleFlash.glow(0f), 0f)
        assertTrue(MuzzleFlash.glow(100f) in 0.01f..0.5f)
        assertEquals(0f, MuzzleFlash.glow(MuzzleFlash.LENGTH_MS), 0f)
    }

    @Test fun kickOffStillCountsShotsForTheFlash() {
        val s = CrosshairKick.State()
        assertTrue(s.step(10, true, CrosshairKick.OFF, 100, flash = true))
        assertEquals(10L, s.shotAt)
        s.step(30, true, CrosshairKick.OFF, 100, flash = true)
        assertEquals(0f, s.value, 0f)
    }

    @Test fun autoRifleFlashesEachShot() {
        val s = CrosshairKick.State()
        s.step(0, true, CrosshairKick.AUTO, 100, flash = true)
        s.step(104, true, CrosshairKick.AUTO, 100, flash = true)
        assertEquals(100L, s.shotAt)
    }

    @Test fun rifleKicksBackWithItsMuzzleUp() {
        val rest = FloatArray(3).also { LaserTrace.muzzle(0f, it) }
        val kicked = FloatArray(3).also { LaserTrace.muzzle(0f, it, 1f) }
        assertTrue(kicked[1] > rest[1] + 0.1f) // muzzle up
        assertTrue(kicked[2] > rest[2]) // back toward the player
        assertEquals(rest[2], LaserTrace.MUZZLE[2], 1e-6f)
    }
}
