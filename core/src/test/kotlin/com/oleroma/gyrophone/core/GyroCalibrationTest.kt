package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroCalibration
import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.model.GyroSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroCalibrationTest {
    @Test fun stillPhoneGivesItsAverageAsBias() {
        val cal = GyroCalibration()
        var r: GyroCalibration.Result? = null
        var t = 0L
        var i = 0
        while (r == null) {
            val noise = if (i++ % 2 == 0) 0.002f else -0.002f
            r = cal.add(0.01f + noise, -0.005f, 0f, t)
            t += 5_000_000L
        }
        val done = r as GyroCalibration.Result.Done
        assertEquals(0.01f, done.x, 0.0005f)
        assertEquals(-0.005f, done.y, 0.0005f)
        assertEquals(0f, done.z, 0.0005f)
    }

    @Test fun movingPhoneFails() {
        val cal = GyroCalibration()
        assertNull(cal.add(0f, 0f, 0f, 0L))
        assertNull(cal.add(0.3f, 0f, 0f, 1_000_000_000L))
        assertTrue(cal.add(0f, 0f, 0f, 2_000_000_000L) is GyroCalibration.Result.Moved)
    }

    @Test fun slowSteadyTurnIsNotTakenForDrift() {
        val cal = GyroCalibration()
        assertNull(cal.add(0.1f, 0f, 0f, 0L))
        assertTrue(cal.add(0.1f, 0f, 0f, 2_000_000_000L) is GyroCalibration.Result.Moved)
    }

    @Test fun defaultsAreRawGyro() {
        val s = GyroSettings()
        assertEquals(0f, s.tighteningThreshold)
        assertEquals(0f, s.smoothingThreshold)
        // A slow 0.5 deg/s turn reaches the cursor in full: 0.5 * 16 counts over 1 s.
        val g = GyroMouse(s)
        val rad = (0.5 * Math.PI / 180.0).toFloat()
        var total = 0
        repeat(1000) { total += g.update(-rad, 0f, 0f, 0.001f).dx }
        assertEquals(8, total)
    }
}
