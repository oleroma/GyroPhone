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
        p.setUp(0f, cos(a).toFloat(), -sin(a).toFloat(), 1f)
        p.rotate(0f, 0f, 0f, 10f)
        val u = FloatArray(3)
        p.up(u)
        assertEquals(cos(a).toFloat(), u[1], 0.01f)
        assertEquals(-sin(a).toFloat(), u[2], 0.01f)
    }

    @Test fun aJoltOffOneGIsSkipped() {
        val p = GravityPose()
        p.setUp(0f, 1f, 0f, 1f)
        p.setUp(0f, 0f, 3f, 1f)
        p.rotate(0f, 0f, 0f, 10f)
        val u = FloatArray(3)
        p.up(u)
        assertEquals(1f, u[1], 0.01f)
    }

    @Test fun aSavedThreeDCameraDisplayReadsAsAPhone() {
        assertEquals(GyroDisplayKind.PHONE, ProfileJson.json.decodeFromString(GyroDisplayKind.serializer(), "\"CAMERA\""))
    }
}
