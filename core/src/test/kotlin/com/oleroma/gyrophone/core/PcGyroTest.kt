package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** "Use PC controller for gyro ratchet": each gyro axis's share, the controller's gyro buttons and STATE_GYRO. */
class PcGyroTest {
    private val routes = Bindings.gyroMouseMappings()
    private val yaw = MotionAxis.YAW.ordinal
    private val pitch = MotionAxis.PITCH.ordinal
    private val roll = MotionAxis.ROLL.ordinal

    @Test
    fun sharesAddUpToTheMotion() {
        val m = GyroMouse(GyroSettings())
        val p = GyroMouse.Parts()
        var dx = 0; var dy = 0
        var sx = 0f; var sy = 0f
        repeat(200) {
            val r = m.route(0f, -0.3f, -1f, 0.005f, routes, MappingEngine.ALL_GYRO, parts = p)
            dx += r.dx; dy += r.dy
            sx += p.onX[roll]; sy += p.onY[pitch]
            assertEquals(0f, p.onX[yaw])
        }
        assertTrue("dx $dx shares $sx", dx > 0 && abs(dx - sx) <= 1f)
        assertTrue("dy $dy shares $sy", dy != 0 && abs(dy - sy) <= 1f)
    }

    @Test
    fun anAxisOffStillSaysWhatItWouldMove() {
        val m = GyroMouse(GyroSettings())
        val p = GyroMouse.Parts()
        var dx = 0; var share = 0f
        repeat(100) {
            dx += m.route(0f, 0f, -1f, 0.005f, routes, 0, parts = p).dx
            share += p.onX[roll]
        }
        assertEquals(0, dx)
        // About 57 °/s · 0.5 s · 16 counts/° ≈ 460.
        assertTrue("share $share", share in 400f..520f)
    }

    @Test
    fun aGlideIsItsOwnShare() {
        val m = GyroMouse(GyroSettings(inertia = true))
        val p = GyroMouse.Parts()
        repeat(20) { m.route(0f, 0f, -1f, 0.005f, routes, MappingEngine.ALL_GYRO, parts = p) }
        var dx = 0; var glide = 0f
        repeat(400) {
            dx += m.route(0f, 0f, 0f, 0.005f, routes, 0, parts = p).dx
            glide += p.glX[roll]
            assertEquals(0f, p.onX[roll])
        }
        assertTrue("dx $dx glide $glide", dx > 100 && abs(dx - glide) <= 1f)
    }

    @Test
    fun stickSharesComeWithTheAxisOn() {
        val m = GyroMouse(GyroSettings())
        val p = GyroMouse.Parts()
        val stick = listOf(Mapping(InputSource.Gyro(MotionAxis.ROLL), OutputTarget.PadAxis(GamepadAxis.RX)))
        val r = m.route(0f, 0f, -0.5f, 0.005f, stick, 0, parts = p)
        assertEquals(0f, r.pad[GamepadAxis.RX.ordinal])
        assertTrue(p.pad[roll][GamepadAxis.RX.ordinal] > 0.2f)
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
    fun stateGyroCarriesTheShares() {
        val feed = PcGyroFeed()
        val m = GyroMouse(GyroSettings())
        val p = GyroMouse.Parts()
        var dx = 0
        repeat(50) {
            val r = m.route(0f, 0f, -1f, 0.005f, routes, MappingEngine.ALL_GYRO, parts = p)
            dx += r.dx
            feed.add(r, p, MappingEngine.ALL_GYRO)
        }
        val f = StateFrame()
        f.hires = true
        f.gyroExt = true
        feed.encodeExt(f.ext, 0, intArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(PacketType.STATE_GYRO, WireCodec.stateType(f))
        val out = ByteArray(Wire.MAX_DATAGRAM)
        assertEquals(Wire.STATE_GYRO_PLAIN, WireCodec.encodeStatePlain(f, out, 0))
        assertTrue(Wire.sizeOf(PacketType.STATE_GYRO) <= Wire.MAX_DATAGRAM)
        val e = Wire.STATE_HIRES_PLAIN
        assertEquals(PcGyroFeed.FLAG_MAY_MOVE, out[e].toInt())
        assertEquals(MappingEngine.ALL_GYRO, out[e + 1].toInt())
        assertEquals(dx, out.getI16(e + 2))
        // Roll's "on" x total equals the gyro's own with only roll moving the cursor.
        val rollOn = e + 10 + roll * PcGyroFeed.AXIS_SIZE
        assertTrue(abs(out.getI16(rollOn) - dx) <= 1)
        val nonGyro = IntArray(6) { out.getI16(e + 10 + 3 * PcGyroFeed.AXIS_SIZE + it * 2) }
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), nonGyro)
        val back = WireCodec.decodeSealedPlain(PacketType.STATE_GYRO, out, 0, Wire.STATE_GYRO_PLAIN) as StateFrame
        assertTrue(back.gyroExt)
        assertArrayEquals(f.ext, back.ext)
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
