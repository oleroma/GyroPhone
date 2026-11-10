package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.CrosshairKick
import com.oleroma.gyrophone.core.engine.Guns
import com.oleroma.gyrophone.core.engine.LaserTrace
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simulate spread, Bloom, Draw each shot and guns scaled to fit, as the PC's SpreadTests. */
class SpreadTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test fun shotsLandWithinTheSpread() {
        val k = CrosshairKick.State()
        val beam = FloatArray(3).also { LaserTrace.beam(identity, it) }
        val spread = Math.toRadians(2.0).toFloat()
        val hit = FloatArray(3)
        for (i in 0 until 50) {
            k.step(i * 100L, true, CrosshairKick.ONCE, 100, pellets = 8)
            k.step(i * 100L + 50, false, CrosshairKick.ONCE, 100, pellets = 8)
            assertEquals(8, k.pellets)
            for (p in 0 until k.pellets) {
                LaserTrace.shotPoint(identity, beam, k.offsets[p * 2] * spread, k.offsets[p * 2 + 1] * spread, hit)
                val off = acos((hit[0] * beam[0] + hit[1] * beam[1] + hit[2] * beam[2]).coerceIn(-1f, 1f))
                assertTrue(off <= spread * 1.001f)
            }
        }
        assertEquals(50L, k.shots)
    }

    @Test fun bloomGrowsInARowAndSettlesAfterLettingGo() {
        val k = CrosshairKick.State()
        for (t in 0L..1500L step 10) k.step(t, true, CrosshairKick.AUTO, 100)
        assertEquals(2f, k.bloom, 0.001f)
        k.step(1600, false, CrosshairKick.AUTO, 100)
        k.step(2200, true, CrosshairKick.AUTO, 100)
        assertEquals(1f, k.bloom, 0.001f)
    }

    @Test fun turnTowardMovesTheBeamOntoTheShot() {
        val beam = FloatArray(3).also { LaserTrace.beam(identity, it) }
        val to = FloatArray(3)
        LaserTrace.shotPoint(identity, beam, 0.05f, -0.03f, to)
        val m = identity.copyOf()
        LaserTrace.turnToward(m, beam, to, 1f)
        val b = FloatArray(3).also { LaserTrace.beam(m, it) }
        for (i in 0..2) {
            assertEquals(to[i], b[i], 0.0005f)
            assertEquals(to[i], beam[i], 0.0005f)
        }
    }

    @Test fun hitsStayUntilCleared() {
        val t = LaserTrace()
        t.addHit(0f, 0f, -2f, 0.01f)
        t.addHit(0.1f, 0f, -1f, 0.01f)
        assertEquals(2, t.hitCount)
        assertEquals(-1f, t.hits[2], 0.0001f)
        t.clear()
        assertEquals(0, t.hitCount)
    }

    @Test fun gunsAreScaledToTheRiflesLength() {
        assertEquals(1f, Guns.AR.fit, 0.0001f)
        assertTrue(Guns.PISTOL.fit > 1.3f)
        val out = FloatArray(3)
        for (g in Guns.ALL) {
            LaserTrace.muzzle(0f, out, gun = g)
            assertTrue(out[2] > -1.1f && out[2] < -0.3f, g.name)
        }
    }
}
