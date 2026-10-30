package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.CountStream
import com.oleroma.gyrophone.core.model.GameSens
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class GameSensTest {
    @Test
    fun sourceGameAtSensOne() {
        val c = GameSens.countsPer360(0.022, 1.0)
        assertEquals(16363.6f, c, 0.1f)
        // The default 16 counts per degree turns the camera 0.352° per phone degree.
        assertEquals(0.352f, GameSens.rws(16f, c), 0.001f)
        assertEquals(1f, GameSens.rws(GameSens.sensOf(1f, c), c), 1e-5f)
    }

    @Test
    fun measuredYawRoundTrips() {
        val c = GameSens.countsPer360(0.07, 0.4)
        assertEquals(0.07, GameSens.yawOf(c, 0.4), 1e-6)
    }

    @Test
    fun rwsOnlyWithAKnownGame() {
        assertNull(GyroSettings().rwsOf(16f))
        assertEquals(1f, GyroSettings(gameCountsPer360 = 5760f).rwsOf(16f)!!, 1e-5f)
    }

    @Test
    fun layersDoNotSetTheGame() {
        assertFalse(WorkspaceDefaults.gyroKey("gameCountsPer360") in WorkspaceDefaults.layerKeys)
        assertTrue(WorkspaceDefaults.gyroKey("gameCountsPer360") in WorkspaceDefaults.keys)
    }

    @Test
    fun streamCountsExactly() {
        val s = CountStream()
        s.run(1000f)
        var total = 0
        repeat(1000) { total += s.tick(0.0037f) }
        assertEquals(total.toLong(), s.sent)
        assertTrue(total in 3699..3700)
        s.stop()
        assertEquals(0, s.tick(1f).toLong())
        s.nudge(-5)
        assertEquals(total - 5L, s.sent)
    }

    @Test
    fun streamStopsAtItsTarget() {
        val s = CountStream()
        s.runTo(1234, 5000f)
        var total = 0
        repeat(1000) { total += s.tick(0.004f) }
        assertEquals(1234, total)
        assertFalse(s.running)
    }
}
