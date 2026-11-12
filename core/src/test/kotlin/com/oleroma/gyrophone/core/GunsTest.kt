package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.Guns
import com.oleroma.gyrophone.core.engine.LaserTrace
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GunsTest {
    private val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test fun eachGunsMuzzleIsAheadOfItsModel() {
        for (g in Guns.ALL) {
            val front = g.boxes.indices.step(6).minOf { g.boxes[it + 2] - g.boxes[it + 5] }
            assertTrue(g.name, g.muzzleZ <= front + 1e-4f)
            assertTrue(g.name, g.muzzleZ >= -1.05f)
        }
    }

    @Test fun recoilRisesToFullAndSettles() {
        for (g in Guns.ALL) {
            val r = g.recoil
            assertEquals(g.name, 1f, r.at(r.riseMs, 0f), 1e-3f)
            assertEquals(g.name, 0f, r.at(r.lengthMs, 0f), 0f)
        }
        // A sniper kicks harder and longer than an SMG.
        assertTrue(Guns.SNIPER.recoil.back > Guns.SMG.recoil.back)
        assertTrue(Guns.SNIPER.recoil.lengthMs > Guns.SMG.recoil.lengthMs)
    }

    @Test fun flashLengthFollowsCalibre() {
        assertTrue(Guns.PISTOL.flash < Guns.SMG.flash)
        assertTrue(Guns.SMG.flash < Guns.AR.flash)
        assertTrue(Guns.AR.flash < Guns.SNIPER.flash)
        assertTrue(Guns.ALL.maxOf { it.bore } == Guns.SHOTGUN.bore)
    }

    @Test fun eachGunKeepsItsOwnFireRate() {
        val d = GyroDisplay(GyroDisplayKind.LASER)
        assertEquals(600, d.rpmOf(Guns.AR))
        assertEquals(800, d.rpmOf(Guns.SMG))
        val e = d.copy(gunRpm = mapOf("smg" to 900))
        assertEquals(900, e.rpmOf(Guns.SMG))
        assertEquals(600, e.rpmOf(Guns.AR))
    }

    @Test fun roundScopeGrowsInWhileAimingButNotOnThePistol() {
        val bare = LaserTrace.rifle(id, axon = true, turned = null, gun = Guns.AR).size
        val scoped = LaserTrace.rifle(id, axon = true, turned = null, aim = 1f, gun = Guns.AR).size
        assertTrue(scoped > bare + 6)
        val off = LaserTrace.rifle(id, axon = true, turned = null, aim = 1f, gun = Guns.AR, scope = false).size
        assertEquals(bare, off)
        val pistol = LaserTrace.rifle(id, axon = true, turned = null, gun = Guns.PISTOL).size
        assertEquals(pistol, LaserTrace.rifle(id, axon = true, turned = null, aim = 1f, gun = Guns.PISTOL).size)
    }

    @Test fun loweredPitchesTheMuzzleDown() {
        val up = FloatArray(3).also { LaserTrace.muzzle(0f, it, gun = Guns.AR) }
        val down = FloatArray(3).also { LaserTrace.muzzle(0f, it, gun = Guns.AR, lower = 1f) }
        assertTrue(down[1] < up[1] - 0.2f)
    }
}
