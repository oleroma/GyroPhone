package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.LaserPaint
import com.oleroma.gyrophone.core.engine.LaserTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserPaintTest {
    // The direction [deg] degrees right of straight ahead along the equator, [up] degrees up.
    private fun at(deg: Float, up: Float = 0f): FloatArray = FloatArray(3).also {
        LaserTrace.dir(Math.toRadians(up.toDouble()).toFloat(), Math.toRadians(deg.toDouble()).toFloat(), it)
    }

    private fun reach(p: LaserPaint, d: FloatArray) = p.data[p.texelOf(d[0], d[1], d[2])] shr 17 and LaserPaint.REACH_MAX
    private fun fired(p: LaserPaint, d: FloatArray) = p.data[p.texelOf(d[0], d[1], d[2])] shr 16 and 1
    private fun along(p: LaserPaint, d: FloatArray) = p.data[p.texelOf(d[0], d[1], d[2])] and 0xFFFF

    private fun line(p: LaserPaint, from: Float, to: Float, alongFrom: Double, halfDeg: Float = 1f, fired: Boolean = false, snake: Float = 0f, way: Int? = null) {
        val a = at(from)
        val b = at(to)
        p.stamp(a[0], a[1], a[2], alongFrom, b[0], b[1], b[2], alongFrom + (to - from), halfDeg, fired, snake, way)
    }

    @Test fun aLineFallsOffFromItsMiddle() {
        val p = LaserPaint()
        line(p, 0f, 20f, 0.0, fired = true)
        assertTrue(reach(p, at(10f)) > 110) // on the line
        assertEquals(1, fired(p, at(10f)))
        // One half width off it: the edge. Three: nothing.
        val edge = reach(p, at(10f, 1f)) / LaserPaint.REACH_MAX.toFloat()
        assertEquals(LaserPaint.EDGE, edge, 0.1f)
        assertEquals(0, reach(p, at(10f, 3.3f)))
        assertEquals(0, reach(p, at(25f)))
        // Its place along the painting.
        assertEquals(100f, along(p, at(10f)).toFloat(), 3f)
    }

    @Test fun newerPaintWinsWhereItCrosses() {
        val p = LaserPaint()
        line(p, 0f, 20f, 0.0)
        // Across it, 100° later along.
        val a = at(10f, -10f)
        val b = at(10f, 10f)
        p.stamp(a[0], a[1], a[2], 100.0, b[0], b[1], b[2], 120.0, 1f, false)
        assertEquals(1100f, along(p, at(10f)).toFloat(), 3f)
        assertEquals(50f, along(p, at(5f)).toFloat(), 3f) // the old line elsewhere stays as it was
    }

    @Test fun snakeShowsOnlyTheNewestLength() {
        val units = (180 / LaserPaint.ALONG_UNIT).toInt()
        assertEquals(1f, LaserPaint.snakeShows(1000, 1000, units), 0f)
        assertEquals(1f, LaserPaint.snakeShows(1000 - (units * 0.8f).toInt(), 1000, units), 0.01f)
        assertEquals(0.5f, LaserPaint.snakeShows(1000 - (units * 0.9f).toInt(), 1000, units), 0.01f)
        assertEquals(0f, LaserPaint.snakeShows(1000 - units, 1000, units), 0f)
        // Through the wrap.
        assertEquals(1f, LaserPaint.snakeShows(LaserPaint.ALONG_WRAP - 5, 5, units), 0f)
    }

    @Test fun oldPaintStaysHiddenThroughTheWrap() {
        val p = LaserPaint()
        line(p, 0f, 10f, 0.0)
        // Paint far away for a long time: more than the wrap's 6553.6°.
        var along = 10.0
        repeat(80) {
            line(p, 90f, 180f, along, snake = 180f)
            along += 90.0
        }
        val units = (180 / LaserPaint.ALONG_UNIT).toInt()
        assertEquals(0f, LaserPaint.snakeShows(along(p, at(5f)), p.newest, units), 0f)
    }

    @Test fun keepsWayColours() {
        val p = LaserPaint()
        line(p, 0f, 20f, 0.0, way = 0x123456)
        val w = p.way
        assertNotNull(w)
        val d = at(10f)
        assertEquals(0xFF123456.toInt(), w!![p.texelOf(d[0], d[1], d[2])])
    }

    @Test fun traceStampsItsStrokes() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = true, held = false, brushDeg = 4f)
        val e = at(10f)
        t.add(e[0], e[1], e[2], 10, draw = true, held = false, brushDeg = 4f)
        val p = t.paint!!
        assertTrue(reach(p, at(5f)) > 100)
        t.clear()
        assertEquals(0, reach(p, at(5f)))
    }
}
