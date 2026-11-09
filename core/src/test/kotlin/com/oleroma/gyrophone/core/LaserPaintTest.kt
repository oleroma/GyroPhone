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

    private fun byte(p: LaserPaint, d: FloatArray) = p.data[p.texelOf(d[0], d[1], d[2])].toInt() and 0xFF
    private fun reach(p: LaserPaint, d: FloatArray) = byte(p, d) shr 1
    private fun fired(p: LaserPaint, d: FloatArray) = byte(p, d) and 1

    private fun line(p: LaserPaint, from: Float, to: Float, halfDeg: Float = 1f, fired: Boolean = false, way: Int? = null) {
        val a = at(from)
        val b = at(to)
        p.stamp(a[0], a[1], a[2], b[0], b[1], b[2], halfDeg, fired, way)
    }

    @Test fun aLineFallsOffFromItsMiddle() {
        val p = LaserPaint()
        line(p, 0f, 20f, fired = true)
        assertTrue(reach(p, at(10f)) > 110) // on the line
        assertEquals(1, fired(p, at(10f)))
        // One half width off it: the edge. Three: nothing.
        val edge = reach(p, at(10f, 1f)) / LaserPaint.REACH_MAX.toFloat()
        assertEquals(LaserPaint.EDGE, edge, 0.1f)
        assertEquals(0, reach(p, at(10f, 3.3f)))
        assertEquals(0, reach(p, at(25f)))
    }

    @Test fun newerPaintWinsWhereItCrosses() {
        val p = LaserPaint()
        line(p, 0f, 20f, fired = true)
        val a = at(10f, -10f)
        val b = at(10f, 10f)
        p.stamp(a[0], a[1], a[2], b[0], b[1], b[2], 1f, false)
        assertEquals(0, fired(p, at(10f)))
        assertEquals(1, fired(p, at(5f))) // the old line elsewhere stays as it was
    }

    @Test fun keepsWayColours() {
        val p = LaserPaint()
        line(p, 0f, 20f, way = 0x123456)
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

    @Test fun snakeKeepsOnlyItsLength() {
        val t = LaserTrace()
        // 1° steps along the equator, Snake 10° long, no Draw: nothing painted, the line cut.
        for (i in 0..30) {
            val d = at(i.toFloat())
            t.add(d[0], d[1], d[2], i.toLong(), draw = false, held = false, snakeDeg = 10f)
        }
        assertEquals(null, t.paint)
        assertEquals(10f, t.strokes.behindNewest(0), 0.6f)
        // Snake off and no Draw: the line goes.
        t.add(0f, 0f, -1f, 40, draw = false, held = false)
        assertEquals(0, t.strokes.count)
    }
}
