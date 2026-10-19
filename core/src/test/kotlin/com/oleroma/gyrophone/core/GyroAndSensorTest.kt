package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.sensor.DirectReportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GyroAndSensorTest {
    @Test fun fastTurnMovesMouseAndKeepsRemainder() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f, tighteningThreshold = 0f, smoothingThreshold = 0f))
        // 90 deg/s yaw around -X (phone turned right in landscape) for 1 s at 1 kHz -> 900 counts.
        val rad = (90.0 * Math.PI / 180.0).toFloat()
        var total = 0
        repeat(1000) { total += g.update(-rad, 0f, 0f, 0.001f).dx }
        assertEquals(900, total)
    }

    @Test fun tinyJitterIsTightened() {
        val g = GyroMouse(GyroSettings(sensitivity = 10f))
        var total = 0
        repeat(1000) { total += g.update(0.002f, 0f, 0f, 0.001f).dx } // ~0.11 deg/s noise
        assertTrue(total == 0)
    }

    @Test fun directChannelParserReturnsNewEventsInOrder() {
        val n = 4
        val buf = ByteBuffer.allocate(n * DirectReportParser.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fun put(slot: Int, counter: Int, x: Float) {
            val b = slot * DirectReportParser.EVENT_SIZE
            buf.putInt(b, DirectReportParser.EVENT_SIZE); buf.putInt(b + 4, 7); buf.putInt(b + 8, 4)
            buf.putInt(b + 12, counter); buf.putLong(b + 16, counter * 1000L); buf.putFloat(b + 24, x)
        }
        put(0, 5, 0.5f); put(1, 2, 0.2f); put(2, 3, 0.3f); put(3, 4, 0.4f)
        val p = DirectReportParser(n)
        assertEquals(listOf(2L, 3L, 4L, 5L), p.parse(buf.array(), 7).map { it.counter })
        put(1, 6, 0.6f)
        val next = p.parse(buf.array(), 7)
        assertEquals(1, next.size)
        assertEquals(0.6f, next[0].x)
    }
}
