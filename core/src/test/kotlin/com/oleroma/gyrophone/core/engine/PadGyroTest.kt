package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadGyroTest {
    private fun map(g: GyroSettings, yaw: Float, pitch: Float, roll: Float) = FloatArray(3).also { PadGyro.map(g, yaw, pitch, roll, it) }

    @Test fun defaultIsThePhoneAsThePadsFace() {
        // Stored yaw (shown roll) = along the screen -> pad roll (z); stored roll (shown yaw) = through the screen -> pad yaw (y).
        assertArrayEquals(floatArrayOf(2f, -3f, 1f), map(GyroSettings(), yaw = 1f, pitch = 2f, roll = 3f), 0f)
    }

    @Test fun anyAxisCanFeedAnyPadAxisInvertedOrNot() {
        val g = GyroSettings(
            padYaw = MotionAxis.YAW, padYawInvert = true,
            padPitch = MotionAxis.NONE,
            padRoll = MotionAxis.ROLL,
        )
        assertArrayEquals(floatArrayOf(0f, -1f, -3f), map(g, yaw = 1f, pitch = 2f, roll = 3f), 0f)
    }

    @Test fun padAxesAreTheWorkspacesOnly() {
        for (k in WorkspaceDefaults.PAD_GYRO_KEYS) {
            assertTrue(k, k in WorkspaceDefaults.keys)
            assertFalse(k, k in WorkspaceDefaults.layerKeys)
        }
    }
}
