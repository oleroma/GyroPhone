package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.model.CurvePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** A curve frame's graph in the frames blob; the same hex is decoded by the companion's OverlayTests. */
class OverlayCurvesTest {
    @Test
    fun aCurveFrameFollowsTheMarks() {
        val c = OverlayCurve(
            id = "f", points = listOf(CurvePoint(30f, 1f), CurvePoint(270f, 2f)), smooth = true, axisSpeed = false,
            axis = 3, maxSpeed = 300f, maxGain = 3f, showValues = true, oneX = 10f, rws = true, inEffect = true,
            unlocked = false, drag = -1,
        )
        val b = OverlayFeed.encodeFrames(OverlayFrames.NONE, 7, mapOf("a" to 0, "b" to 1, "f" to 2), null, listOf(c, c.copy(id = "gone")))
        assertEquals(CURVES_BLOB, b.joinToString("") { "%02x".format(it) })
    }

    companion object {
        const val CURVES_BLOB = "0107000000640000000000004301022d032c012c0100002041ff022c01e8038c0ad007"
    }
}
