package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Camera displays turn by the mouse counts sent ([GyroDisplayFeed.cameraCounts], [GyroDisplayFeed.degPerCount]). */
class DisplayCountsTest {
    private val routes = listOf(
        Mapping(InputSource.Gyro(MotionAxis.ROLL), OutputTarget.MouseAxis(MouseMotion.MOVE_X, 0.5f)),
        Mapping(InputSource.Gyro(MotionAxis.PITCH), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
    )

    @Test fun gameTurnKnownGivesGameDegreesWhateverTheSensitivity() {
        val gs = GyroSettings(sensitivity = 100f, pitchSensitivity = 40f, gameCountsPer360 = 7200f)
        val g = GyroDisplayFeed.degPerCount(gs)
        assertEquals(0.05f, g, 1e-6f)
        val deg = GyroDisplayFeed.cameraCounts(200, -100, routes, gs, g, FloatArray(3))
        // Sideways counts turn about roll, up counts (dy -) about pitch, each by game degrees.
        assertEquals(10f, deg[2], 1e-4f)
        assertEquals(5f, deg[1], 1e-4f)
        assertEquals(0f, deg[0], 0f)
    }

    @Test fun unknownGameTurnKeepsEachAxisSensitivity() {
        val gs = GyroSettings(sensitivity = 20f, pitchSensitivity = 40f)
        val g = GyroDisplayFeed.degPerCount(gs)
        // One phone degree of pitch sends 40 counts: shown as 2 degrees, twice the shared sensitivity's.
        val deg = GyroDisplayFeed.cameraCounts(0, -40, routes, gs, g, FloatArray(3))
        assertEquals(2f, deg[1], 1e-4f)
    }

    @Test fun invertedAxisTurnsTheWayThePhoneTurned() {
        val gs = GyroSettings(sensitivity = 20f, yawSensitivity = -20f, gameCountsPer360 = 3600f)
        val r = listOf(Mapping(InputSource.Gyro(MotionAxis.YAW), OutputTarget.MouseAxis(MouseMotion.MOVE_X)))
        val deg = GyroDisplayFeed.cameraCounts(-10, 0, r, gs, GyroDisplayFeed.degPerCount(gs), FloatArray(3))
        assertEquals(1f, deg[0], 1e-4f)
    }

    @Test fun cameraDisplayTurnsByCountsAndSlowsWhileAiming() {
        val d = com.oleroma.gyrophone.core.model.GyroDisplay(
            com.oleroma.gyrophone.core.model.GyroDisplayKind.BALL, turnsWith = com.oleroma.gyrophone.core.model.TurnsWith.CAMERA, aimSens = 0.5f,
        )
        val gs = GyroSettings(gameCountsPer360 = 3600f)
        val m = FloatArray(9)
        val f = GyroDisplayFeed("c", d)
        // The live gyro's rates do nothing: its counts come through camera().
        repeat(50) { f.feed(60f, 0f, 60f, 0.01f, 7, pressed = false, held = false, gyro = gs) }
        f.pose.matrix(m); assertEquals(0f, m[6], 1e-4f)
        f.camera(300, 0, routes, gs) // 30° right in the game
        f.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
        val a = GyroDisplayFeed("a", d)
        a.aiming = true
        a.camera(600, 0, routes, gs) // aiming at half the sensitivity: still 30°
        a.pose.matrix(m); assertEquals(0.5f, m[6], 1e-3f)
    }
}
