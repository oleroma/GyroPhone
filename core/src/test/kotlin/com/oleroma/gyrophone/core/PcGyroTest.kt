package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMath
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.net.OverlayFeed
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.PcGyroFeed
import com.oleroma.gyrophone.core.net.StateFrame
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.net.WireCodec
import com.oleroma.gyrophone.core.net.getI16
import com.oleroma.gyrophone.core.net.getI32
import com.oleroma.gyrophone.core.net.getI64
import com.oleroma.gyrophone.core.net.getU16
import com.oleroma.gyrophone.core.net.getU32
import com.oleroma.gyrophone.core.net.getU8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Use PC controller for gyro ratchet": the raw samples in STATE_GYRO's tail and the controller's gyro buttons. */
class PcGyroTest {
    private val tail = ByteArray(PcGyroFeed.TAIL_SIZE)

    private fun encode(feed: PcGyroFeed, offset: Long? = null): ByteArray {
        feed.encodeTail(tail, 0, offset)
        return tail
    }

    private fun slot(j: Int) = PcGyroFeed.SAMPLES_OFFSET + j * PcGyroFeed.SAMPLE_SIZE

    @Test
    fun ratesAreRoundedWithTheRemainderCarried() {
        val feed = PcGyroFeed()
        // 0.03 deg/s is 0.48/16: each alone rounds to 0, carried on they add up.
        var sum = 0
        repeat(8) {
            feed.add(0.03f, -0.03f, 1000f, 0.001f, 7, 1000L * it)
            val t = encode(feed)
            val o = slot(t.getU8(1) - 1)
            sum += t.getI16(o + 2)
            assertEquals(-t.getI16(o + 2), t.getI16(o + 4))
            assertEquals(16000, t.getI16(o + 6))
        }
        assertEquals(4, sum) // 8 x 0.48 = 3.84
    }

    @Test
    fun ratesClampToI16() {
        val feed = PcGyroFeed()
        feed.add(5000f, -5000f, Float.NaN, 0.001f, 7, 0L)
        val t = encode(feed)
        val o = slot(0)
        assertEquals(Short.MAX_VALUE.toInt(), t.getI16(o + 2))
        assertEquals(Short.MIN_VALUE.toInt(), t.getI16(o + 4))
        assertEquals(0, t.getI16(o + 6))
    }

    @Test
    fun seqCountsAndWraps() {
        val feed = PcGyroFeed()
        assertEquals(0, encode(feed).getU16(2))
        repeat(3) { feed.add(1f, 0f, 0f, 0.001f, 7, 0L) }
        assertEquals(3, encode(feed).getU16(2))
        repeat(0x10000 - 3) { feed.add(1f, 0f, 0f, 0.001f, 7, 0L) }
        assertEquals(0, encode(feed).getU16(2))
        feed.reset()
        val t = encode(feed)
        assertEquals(0, t.getU16(2))
        assertEquals(0, t.getU8(1))
    }

    @Test
    fun theRingHoldsTheLastEightOldestFirst() {
        val feed = PcGyroFeed()
        for (i in 1..3) feed.add(i.toFloat(), 0f, 0f, 0.002f, i, 100L * i)
        var t = encode(feed)
        assertEquals(3, t.getU8(1))
        assertEquals(300L, t.getU32(4))
        assertEquals(0, t.getU16(slot(0))) // the first sample after a stop has no time before it
        assertEquals(2000, t.getU16(slot(1)))
        for (j in 0 until 3) {
            assertEquals((j + 1) * 16, t.getI16(slot(j) + 2))
            assertEquals(j + 1, t.getU8(slot(j) + 8))
        }
        for (k in slot(3) until PcGyroFeed.TOTALS_OFFSET) assertEquals(0, t.getU8(k))
        for (i in 4..11) feed.add(i.toFloat(), 0f, 0f, 0.002f, i and 7, 100L * i)
        t = encode(feed)
        assertEquals(8, t.getU8(1))
        assertEquals(11, t.getU16(2))
        assertEquals(1100L, t.getU32(4))
        for (j in 0 until 8) assertEquals((j + 4) * 16, t.getI16(slot(j) + 2))
    }

    @Test
    fun totalsAddEverySampleSentAndStillStartsAgain() {
        val feed = PcGyroFeed()
        var expect = 0L
        repeat(20) {
            feed.add(10f, -2f, 0.5f, 0.0015f, 7, 0L)
            expect += if (it == 0) 0 else 160L * 1500
        }
        var t = encode(feed)
        assertEquals(PcGyroFeed.FLAG_MAY_MOVE, t.getU8(0))
        assertEquals(expect, t.getI64(PcGyroFeed.TOTALS_OFFSET))
        assertEquals(expect / -5, t.getI64(PcGyroFeed.TOTALS_OFFSET + 8))
        assertEquals(expect / 20, t.getI64(PcGyroFeed.TOTALS_OFFSET + 16))
        feed.still()
        t = encode(feed)
        assertEquals(0, t.getU8(0))
        assertEquals(20, t.getU16(2)) // still keeps seq, ring and totals
        feed.add(10f, 0f, 0f, 0.0015f, 7, 0L)
        t = encode(feed)
        assertEquals(0, t.getU16(slot(7))) // after a stop: no time before it
        assertEquals(expect, t.getI64(PcGyroFeed.TOTALS_OFFSET))
        feed.reset()
        t = encode(feed)
        assertEquals(0L, t.getI64(PcGyroFeed.TOTALS_OFFSET))
        assertEquals(0, t.getU8(0))
    }

    @Test
    fun noSampleForAWhileMeansStill() {
        var ns = 0L
        val feed = PcGyroFeed().apply { monoNs = { ns } }
        feed.add(1f, 0f, 0f, 0.001f, 7, 0L)
        ns += 1_000_000L
        feed.add(1f, 0f, 0f, 0.001f, 7, 1000L)
        assertEquals(1000, encode(feed).getU16(slot(1)))
        ns += 99_000_000L
        assertEquals(PcGyroFeed.FLAG_MAY_MOVE, encode(feed).getU8(0))
        ns += 1_000_000L
        assertEquals(0, encode(feed).getU8(0))
        feed.add(1f, 0f, 0f, 0.001f, 7, 2000L)
        val t = encode(feed)
        assertEquals(PcGyroFeed.FLAG_MAY_MOVE, t.getU8(0))
        assertEquals(0, t.getU16(slot(2))) // the sensor stopped: no time before it
    }

    @Test
    fun theClockOffsetMovesTheTimeIntoThePcsClock() {
        val feed = PcGyroFeed()
        feed.add(1f, 0f, 0f, 0.001f, 7, 0x1_0000_0010L)
        var t = encode(feed, -0x20L)
        assertEquals(PcGyroFeed.FLAG_MAY_MOVE or PcGyroFeed.FLAG_CLOCK, t.getU8(0))
        assertEquals(0xFFFF_FFF0L, t.getU32(4))
        t = encode(feed, null)
        assertEquals(PcGyroFeed.FLAG_MAY_MOVE, t.getU8(0))
        assertEquals(0x10L, t.getU32(4))
    }

    @Test
    fun theConfigBlockSaysWhatToDo() {
        val feed = PcGyroFeed()
        val routes = (0 until 14).map { GyroMath.Route(it % 3, it % 10, 0.5f + it) }
        val cfg = GyroMath.Config(
            floatArrayOf(16f, 8f, 4f), booleanArrayOf(true, false, true), floatArrayOf(100f, 200f, 300f),
            booleanArrayOf(false, true, true), routes,
        )
        feed.setConfig(cfg, 1.5f, 3f)
        val t = encode(feed)
        val c = PcGyroFeed.CONFIG_OFFSET
        fun f32(o: Int) = Float.fromBits(t.getI32(o))
        assertEquals(8f, f32(c + 4), 0f)
        assertEquals(300f, f32(c + 20), 0f)
        assertEquals(0b110_101, t.getU8(c + 24))
        assertEquals(PcGyroFeed.MAX_ROUTES, t.getU8(c + 25))
        assertEquals(1.5f, f32(c + 26), 0f)
        assertEquals(3f, f32(c + 30), 0f)
        val last = c + PcGyroFeed.ROUTES_OFFSET + 11 * PcGyroFeed.ROUTE_SIZE
        assertEquals(2, t.getU8(last))
        assertEquals(1, t.getU8(last + 1))
        assertEquals(11.5f, f32(last + 2), 0f)
        assertEquals(PcGyroFeed.CONFIG_OFFSET + PcGyroFeed.CURVES_OFFSET, last + PcGyroFeed.ROUTE_SIZE)
        assertEquals(PcGyroFeed.TAIL_SIZE, PcGyroFeed.CONFIG_OFFSET + PcGyroFeed.CURVES_OFFSET + 3 * PcGyroFeed.CURVE_SIZE)
    }

    @Test
    fun stateGyroCarriesTheTail() {
        assertEquals(285, PcGyroFeed.TAIL_SIZE)
        assertEquals(355, Wire.STATE_GYRO_PLAIN)
        val feed = PcGyroFeed()
        repeat(5) { feed.add(3f, 2f, 1f, 0.001f, 5, 1000L * it) }
        val f = StateFrame()
        f.hires = true
        f.gyroExt = true
        feed.encodeTail(f.ext, 0, null)
        assertEquals(PacketType.STATE_GYRO, WireCodec.stateType(f))
        val out = ByteArray(Wire.MAX_DATAGRAM)
        assertEquals(Wire.STATE_GYRO_PLAIN, WireCodec.encodeStatePlain(f, out, 0))
        assertTrue(Wire.sizeOf(PacketType.STATE_GYRO) <= Wire.MAX_DATAGRAM)
        assertEquals(5, out[Wire.STATE_HIRES_PLAIN + 1].toInt())
        val back = WireCodec.decodeSealedPlain(PacketType.STATE_GYRO, out, 0, Wire.STATE_GYRO_PLAIN) as StateFrame
        assertTrue(back.gyroExt)
        assertArrayEquals(f.ext, back.ext)
    }

    @Test
    fun controllerGyroButtonsByPlace() {
        val ws = Workspace(
            id = "w", name = "w",
            pads = listOf(AttachedPad(id = "p", mappings = listOf(
                Mapping(InputSource.Key(AndroidKey.BUTTON_L1), OutputTarget.GyroPause),
                Mapping(InputSource.Key(AndroidKey.BUTTON_Y), OutputTarget.GyroToggle),
                Mapping(InputSource.Axis(AndroidAxis.RTRIGGER), OutputTarget.GyroPauseAxis(MotionAxis.YAW)),
                Mapping(InputSource.Key(AndroidKey.BUTTON_A), OutputTarget.Pad(com.oleroma.gyrophone.core.model.GamepadButton.A)),
            ))),
        )
        val e = MappingEngine(single(ws)) { 0L }
        val got = e.pcGyroButtons.toSet()
        fun place(bit: Int) = Integer.numberOfTrailingZeros(bit) shl 8
        assertEquals(
            setOf(
                place(OverlayFeed.PAD_L1) or MappingEngine.ALL_GYRO,
                place(OverlayFeed.PAD_NORTH) or MappingEngine.PC_GYRO_TOGGLE,
                place(OverlayFeed.PAD_R2) or MappingEngine.gyroBit(MotionAxis.YAW),
            ),
            got,
        )
    }

    @Test
    fun padGyroLists() {
        val feed = PcGyroFeed()
        feed.enabled = true
        feed.epoch = 3
        feed.buttons = intArrayOf((4 shl 8) or 7)
        val out = ByteArray(Wire.PAD_GYRO_PLAIN)
        assertEquals(Wire.PAD_GYRO_PLAIN, feed.encodeButtons(out, 0))
        assertEquals(1, out[0].toInt())
        assertEquals(3, out[1].toInt())
        assertEquals(1, out[2].toInt())
        assertEquals(4, out[4].toInt())
        assertEquals(7, out[5].toInt())
        assertEquals(0xFF, out[6].toInt() and 0xFF)
    }
}
