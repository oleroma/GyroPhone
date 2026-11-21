package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.TRACKBALL_AS_CAMERA
import com.oleroma.gyrophone.core.model.TRACKBALL_LOCK
import com.oleroma.gyrophone.core.model.trackballLocks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraDegreesTest {
    @Test
    fun intoAScratchArrayMatchesANewOne() {
        val routes = Bindings.gyroMouseMappings()
        val gs = GyroSettings()
        val scratch = floatArrayOf(9f, 9f, 9f)
        val fresh = GyroDisplayFeed.cameraDegrees(120, -40, routes, gs)
        // The scratch is overwritten, not added to.
        assertArrayEquals(fresh, GyroDisplayFeed.cameraDegrees(120, -40, routes, gs, scratch), 0f)
        assertArrayEquals(fresh, scratch, 0f)
    }

    @Test
    fun trackballLocksAsBefore() {
        // Camera: roll locked (saved as a lock); an axis with no turn (None) is locked too.
        assertEquals(0b100, trackballLocks(TRACKBALL_AS_CAMERA))
        assertEquals(0b111, trackballLocks(0))
        assertEquals(0b010, trackballLocks(1 or (TRACKBALL_LOCK shl 8) or (3 shl 16)))
    }
}
