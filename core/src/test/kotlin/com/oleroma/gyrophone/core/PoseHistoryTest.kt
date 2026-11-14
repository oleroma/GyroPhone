package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.PoseHistory
import org.junit.Test
import org.junit.Assert.assertEquals

class PoseHistoryTest {
    private fun q(v: Float) = floatArrayOf(v, 0f, 0f, 0f)

    @Test
    fun givesEachPoseAfterTheLastSeenInOrder() {
        val h = PoseHistory()
        for (i in 1..5) h.add(q(i.toFloat()), i * 1000L)
        val got = ArrayList<Float>()
        val out = FloatArray(4)
        val next = h.since(2, out) { p, _ -> got.add(p[0]) }
        assertEquals(listOf(3f, 4f, 5f), got)
        assertEquals(5L, next)
        got.clear()
        h.since(next, out) { p, _ -> got.add(p[0]) }
        assertEquals(emptyList<Float>(), got)
    }

    @Test
    fun keepsOnlyTheNewestAfterFallingBehind() {
        val h = PoseHistory()
        val total = PoseHistory.SIZE * 3
        for (i in 1..total) h.add(q(i.toFloat()), i.toLong())
        val got = ArrayList<Float>()
        val times = ArrayList<Long>()
        h.since(0, FloatArray(4)) { p, t -> got.add(p[0]); times.add(t) }
        assertEquals(PoseHistory.SIZE - 8, got.size)
        assertEquals(total.toFloat(), got.last())
        assertEquals(total.toLong(), times.last())
        assertEquals((total - got.size + 1).toFloat(), got.first())
    }
}
