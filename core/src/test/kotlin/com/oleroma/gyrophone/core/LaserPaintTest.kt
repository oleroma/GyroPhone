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

    private fun k(p: LaserPaint, d: FloatArray) = p.texelOf(d[0], d[1], d[2])
    private fun amount(p: LaserPaint, d: FloatArray) = (p.amount[k(p, d)].toInt() and 0xFF) / 255f
    private fun fire(p: LaserPaint, d: FloatArray) = (p.fire[k(p, d)].toInt() and 0xFF) / 255f

    // A line along the equator in 0.5° steps, as the trace stamps it.
    private fun line(p: LaserPaint, from: Float, to: Float, up: Float = 0f, soft: Float = 0.5f, fired: Boolean = false, way: Int? = null, kind: Int = if (fired) LaserPaint.FIRE else LaserPaint.PAINT) {
        p.newLine()
        var x = from
        var along = 0.0
        while (x < to) {
            val a = at(x, up)
            val b = at(x + 0.5f, up)
            along += 0.5
            p.stamp(a[0], a[1], a[2], b[0], b[1], b[2], along, 1f, kind, soft, way)
            x += 0.5f
        }
    }

    @Test fun aLineIsEvenAlongItsLength() {
        val p = LaserPaint()
        line(p, 0f, 20f, soft = 0.5f)
        assertEquals(1f, amount(p, at(10f)), 0.02f) // on the line, however many stamps overlapped
        assertEquals(amount(p, at(8f, 0.9f)), amount(p, at(12f, 0.9f)), 0.03f) // its soft edge the same everywhere
        assertTrue(amount(p, at(10f, 0.9f)) < 0.9f)
        assertTrue(amount(p, at(10f, 2.5f)) < 0.01f)
        assertTrue((p.glow[k(p, at(10f, 2f))].toInt() and 0xFF) > 0) // the glow reaches further
    }

    @Test fun crossingLinesBuildUp() {
        val p = LaserPaint()
        line(p, 0f, 20f, up = 0.9f)
        val before = amount(p, at(10f, 0f))
        line(p, 0f, 20f, up = -0.9f) // a second line whose soft edge overlaps the first's
        val after = amount(p, at(10f, 0f))
        val alone = LaserPaint().also { line(it, 0f, 20f, up = -0.9f) }.let { amount(it, at(10f, 0f)) }
        assertTrue(after > before + 0.05f)
        assertEquals(before + alone * (1 - before), after, 0.03f) // one over the other
    }

    @Test fun firePaintBlendsUnderNewPaint() {
        val p = LaserPaint()
        line(p, 0f, 20f, fired = true)
        assertEquals(1f, fire(p, at(10f)), 0.02f)
        line(p, 0f, 20f) // painted over in full
        assertEquals(0f, fire(p, at(10f)), 0.02f)
    }

    @Test fun aimPaintAndGlowAreKeptApartFromFire() {
        val p = LaserPaint()
        line(p, 0f, 20f, kind = LaserPaint.AIM)
        val t = k(p, at(10f))
        assertEquals(1f, (p.aim[t].toInt() and 0xFF) / 255f, 0.02f)
        assertEquals(0f, fire(p, at(10f)), 0.02f)
        val g = k(p, at(10f, 2f))
        assertEquals(p.glow[g].toInt() and 0xFF, p.glowAim[g].toInt() and 0xFF) // all of the glow there is aiming's
        assertEquals(0, p.glowFire[g].toInt())
        line(p, 0f, 20f, fired = true) // a shot over it takes its place
        assertEquals(0f, (p.aim[t].toInt() and 0xFF) / 255f, 0.02f)
        assertEquals(1f, fire(p, at(10f)), 0.02f)
        assertTrue((p.glowFire[g].toInt() and 0xFF) > (p.glowAim[g].toInt() and 0xFF))
    }

    @Test fun keepsWayColours() {
        val p = LaserPaint()
        line(p, 0f, 20f, way = 0x804020)
        val w = p.way
        assertNotNull(w)
        assertEquals(0xFF804020.toInt(), w!![k(p, at(10f))])
    }

    @Test fun traceStampsItsStrokes() {
        val t = LaserTrace()
        t.add(0f, 0f, -1f, 0, draw = true, held = false, brushDeg = 4f)
        val e = at(10f)
        t.add(e[0], e[1], e[2], 10, draw = true, held = false, brushDeg = 4f)
        val p = t.paint!!
        assertTrue(amount(p, at(5f)) > 0.9f)
        t.clear()
        assertEquals(0f, amount(p, at(5f)), 0f)
    }

    @Test fun snakeKeepsOnlyItsLength() {
        val t = LaserTrace()
        for (i in 0..30) {
            val d = at(i.toFloat())
            t.add(d[0], d[1], d[2], i.toLong(), draw = false, held = false, snakeDeg = 10f)
        }
        assertEquals(null, t.paint)
        assertEquals(10f, t.strokes.behindNewest(0), 0.6f)
        t.add(0f, 0f, -1f, 40, draw = false, held = false)
        assertEquals(0, t.strokes.count)
    }

    @Test fun keepsTheRowsChangedSinceTheyWereTaken() {
        val p = LaserPaint()
        // A new map: all of it is new to the UI.
        assertEquals(0, p.changedFrom)
        assertEquals(p.height - 1, p.changedTo)
        p.takeChanged()
        assertTrue(p.changedFrom > p.changedTo)
        line(p, 0f, 10f, up = 30f)
        val row = k(p, at(5f, 30f)) / p.width
        assertTrue(p.changedFrom <= row && row <= p.changedTo)
        assertTrue(p.changedTo - p.changedFrom < p.height / 8) // only near the line
        // Every changed texel is within the rows.
        p.takeChanged()
        val before = p.amount.copyOf()
        line(p, 20f, 30f, up = -10f)
        for (i in before.indices) if (before[i] != p.amount[i]) assertTrue(i / p.width in p.changedFrom..p.changedTo)
        p.takeChanged()
        p.clear()
        assertEquals(0, p.changedFrom)
        assertEquals(p.height - 1, p.changedTo)
    }
}
