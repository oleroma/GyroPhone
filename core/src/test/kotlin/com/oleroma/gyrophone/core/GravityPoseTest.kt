package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GravityPose
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.ProfileJson
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The gravity pose behind the World and Player gyro spaces. */
class GravityPoseTest {
    @Test fun thePoseFollowsGravity() {
        val p = GravityPose()
        val a = Math.toRadians(30.0)
        p.setUp(0f, 1f, 0f, 1f, 0L)
        // Readings every 5 ms for 3 s, tilted 30°: the pose settles on them.
        for (i in 1..600) p.setUp(0f, cos(a).toFloat(), -sin(a).toFloat(), 1f, i * 5_000_000L)
        val u = FloatArray(3)
        p.up(u)
        assertEquals(cos(a).toFloat(), u[1], 0.01f)
        assertEquals(-sin(a).toFloat(), u[2], 0.01f)
    }

    @Test fun aJoltOffOneGIsSkipped() {
        val p = GravityPose()
        p.setUp(0f, 1f, 0f, 1f, 0L)
        p.setUp(0f, 0f, 3f, 1f, 5_000_000L)
        p.rotate(0f, 0f, 0f, 10f)
        val u = FloatArray(3)
        p.up(u)
        assertEquals(1f, u[1], 0.01f)
    }

    @Test fun aHardFlickIsCarriedByTheGyroAlone() {
        // 60° of pitch in 0.1 s while the accelerometer reads only jolts: the old reading must not pull the pose back.
        val p = GravityPose()
        p.setUp(0f, 1f, 0f, 1f, 0L)
        for (i in 1..100) {
            p.rotate(0f, 600f, 0f, 0.001f)
            if (i % 5 == 0) p.setUp(0f, 2f, 2f, 1f, i * 1_000_000L)
        }
        val u = FloatArray(3)
        p.up(u)
        val turned = Math.toDegrees(kotlin.math.atan2(-u[2].toDouble(), u[1].toDouble()))
        assertEquals(60.0, kotlin.math.abs(turned), 0.5)
    }

    @Test fun afterAResetTheNextReadingSetsThePose() {
        val p = GravityPose()
        p.setUp(0f, -1f, 0f, 1f, 0L) // the old frame's up, upside down in the new one
        p.reset()
        p.setUp(0f, 1f, 0f, 1f, 5_000_000L)
        p.rotate(0f, 0f, 0f, 0.01f)
        val u = FloatArray(3)
        p.up(u)
        assertEquals(1f, u[1], 0.01f)
    }

    @Test fun aSavedThreeDCameraDisplayReadsAsAPhone() {
        assertEquals(GyroDisplayKind.PHONE, ProfileJson.json.decodeFromString(GyroDisplayKind.serializer(), "\"CAMERA\""))
    }
}
