package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.model.AndroidKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OVERLAY / OVERLAY_LAYOUT bytes; the same hex is decoded by the companion's OverlayTests. */
class OverlayFeedTest {
    private val layout = OverlayLayout(
        widthPx = 2400, heightPx = 1080, workspace = "Main", layer = "",
        controls = listOf(OverlayControl("a", OverlayControl.BUTTON, true, 0.25f, 0.5f, 0.1f, 0.2f, "A", 0.5f)),
    )

    @Test fun sizes() {
        assertEquals(180, Wire.sizeOf(PacketType.OVERLAY))
        assertEquals(228, Wire.sizeOf(PacketType.OVERLAY_LAYOUT))
        assertEquals(true, PacketType.isPhoneToHost(PacketType.OVERLAY_LAYOUT))
    }

    @Test fun layoutPiece() {
        val blob = OverlayFeed.encodeLayout(layout)
        assertEquals(LAYOUT_BLOB, blob.hex())
        val out = ByteArray(Wire.OVERLAY_LAYOUT_PLAIN)
        assertEquals(Wire.OVERLAY_LAYOUT_PLAIN, OverlayFeed.encodeLayoutPiece(out, 0, 7, blob, 0))
        assertEquals("0700000119$LAYOUT_BLOB" + "00".repeat(200 - 5 - 25), out.hex())
    }

    @Test fun bigLayoutSplitsIntoPieces() {
        val many = layout.copy(controls = List(40) { layout.controls[0].copy(id = "c$it", label = "Label $it") })
        val blob = OverlayFeed.encodeLayout(many)
        val n = OverlayFeed.pieceCount(blob)
        assertEquals((blob.size + OverlayFeed.PIECE_DATA - 1) / OverlayFeed.PIECE_DATA, n)
        val out = ByteArray(Wire.OVERLAY_LAYOUT_PLAIN)
        OverlayFeed.encodeLayoutPiece(out, 0, 1, blob, n - 1)
        assertEquals(n - 1, out.getU8(2))
        assertEquals(n, out.getU8(3))
        assertEquals(blob.size - (n - 1) * OverlayFeed.PIECE_DATA, out.getU8(4))
    }

    @Test fun frame() {
        val f = OverlayFeed()
        assertNull(f.layoutSnapshot())
        assertEquals(-1, f.layoutVersion())
        f.setLayout(layout)
        f.setLayout(layout.copy()) // the same layout keeps its version
        assertEquals(1, f.layoutSnapshot()!!.first)
        assertEquals(1, f.layoutVersion())
        f.padShown = true
        f.gyro(100f, -50f, 25f, 0.01f, 3, true)
        f.up(0f, 9.8f, 0f, 9.8f)
        f.north(0f, 0f, -2f)
        f.camera(0.5f, 0f, 0f)
        f.camera(0.5f, 0f, -2f)
        f.rws(2f, 0f, 0f)
        f.setFingers(floatArrayOf(0.5f, 0.25f), 1)
        f.setStick("a", 1f, -0.5f)
        f.setStick("gone", 0.3f, 0.3f) // not in the layout: left out
        val out = ByteArray(Wire.OVERLAY_PLAIN)
        assertEquals(Wire.OVERLAY_PLAIN, f.encodeFrame(out, 0, 0x01020304, setOf("a", "unknown"), emptySet()))
        assertEquals(FRAME, out.hex())
        // The turn is sent once; the next frame starts from 0.
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getI32(4))
        assertEquals(0, out.getU16(16))
        assertEquals(1, out.getU8(31)) // still one press
        assertEquals(0, out.getI32(112)) // the camera turn too
        assertEquals(java.lang.Float.floatToRawIntBits(2f), out.getI32(124)) // the gains stay
    }

    @Test fun controller() {
        val f = OverlayFeed()
        val out = ByteArray(Wire.OVERLAY_PLAIN)
        f.padKey(AndroidKey.BUTTON_A, true) // nothing attached yet: not sent
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getU8(136))
        f.padAttached = true
        f.padKey(AndroidKey.BUTTON_A, true)
        f.padKey(AndroidKey.BUTTON_R1, true)
        f.padKey(AndroidKey.BUTTON_R1, false)
        f.padKey(AndroidKey.BUTTON_R2, true) // digital trigger: full
        f.padAxes(1f, -1f, 0.5f, 0f, 0.5f, 0f, -1f, 1f)
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(OverlayFeed.PAD_ATTACHED, out.getU8(136))
        assertEquals(128, out.getU8(137))
        assertEquals(255, out.getU8(138))
        assertEquals(
            OverlayFeed.PAD_SOUTH or OverlayFeed.PAD_L2 or OverlayFeed.PAD_R2 or OverlayFeed.PAD_LEFT or OverlayFeed.PAD_DOWN,
            out.getI32(140),
        )
        assertEquals(127, out[144].toInt())
        assertEquals(-127, out[145].toInt())
        assertEquals(64, out[146].toInt())
        f.padAttached = false // a controller left: everything let go
        f.padAttached = true
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getI32(140))
    }

    @Test fun levelDisplaysCountsPressesWithOrWithoutAController() {
        val f = OverlayFeed()
        val out = ByteArray(Wire.OVERLAY_PLAIN)
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getU8(139))
        f.levelDisplays()
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(1, out.getU8(139))
        f.padAttached = true
        repeat(255) { f.levelDisplays() }
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getU8(139)) // wraps at 256
        assertEquals(OverlayFeed.PAD_ATTACHED, out.getU8(136))
    }

    @Test fun overlayOffLetsGoOfControllerAxes() {
        val f = OverlayFeed()
        val out = ByteArray(Wire.OVERLAY_PLAIN)
        f.padAttached = true
        f.wanted = true
        f.padKey(AndroidKey.BUTTON_A, true)
        f.padAxes(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        f.wanted = false // axes stop being fed now
        f.wanted = true
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getU8(137))
        assertEquals(0, out[144].toInt())
        assertEquals(OverlayFeed.PAD_SOUTH, out.getI32(140)) // keys are fed all the time: kept
    }

    @Test fun totalsOnlyWhenThePcTakesThem() {
        val f = OverlayFeed()
        val out = ByteArray(Wire.OVERLAY_PLAIN)
        fun yaw() = java.lang.Float.intBitsToFloat(out.getI32(4))
        fun cam() = java.lang.Float.intBitsToFloat(out.getI32(112))
        f.wanted = true
        f.gyro(100f, 0f, 0f, 0.01f, 1, false)
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(0, out.getU8(34) and OverlayFeed.FLAG_TOTALS) // an older PC: per frame
        assertEquals(1f, yaw(), 1e-4f)
        f.totalsWanted = true
        for (i in 0 until 3) {
            f.gyro(100f, 0f, 0f, 0.01f, 1, false)
            f.camera(2f, 0f, 0f)
            f.encodeFrame(out, 0, i, emptySet(), emptySet())
        }
        // The first frame's turn counts too: the totals start when the feed was cleared.
        assertEquals(OverlayFeed.FLAG_TOTALS, out.getU8(34) and OverlayFeed.FLAG_TOTALS)
        assertEquals(4f, yaw(), 1e-4f)
        assertEquals(4000, out.getU16(16))
        assertEquals(6f, cam(), 1e-4f)
        // Kept modulo 360 in -180..180, the time modulo 65536 units.
        f.gyro(100f, 0f, 0f, 1.8f, 1, false)
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(-176f, yaw(), 1e-3f)
        assertEquals((184_000 - 65536 * 2), out.getU16(16))
        // Clearing the feed restarts them from 0.
        f.wanted = false
        f.wanted = true
        f.gyro(100f, 0f, 0f, 0.01f, 1, false)
        f.encodeFrame(out, 0, 0, emptySet(), emptySet())
        assertEquals(1f, yaw(), 1e-4f)
        assertEquals(1000, out.getU16(16))
        assertEquals(0f, cam(), 0f)
    }

    @Test fun wantedAndCompassTellTheRuntime() {
        val f = OverlayFeed()
        var changes = 0
        f.onWantedChange = Runnable { changes++ }
        f.wanted = true
        f.wanted = true
        f.compassWanted = true
        f.wanted = false // the compass goes off with it
        assertEquals(false, f.compassWanted)
        assertEquals(4, changes)
    }

    companion object {
        const val LAYOUT_BLOB = "0160093804044d61696e000100010008001033036606800141"
        const val FRAME = "040302010000803f000000bf0000803ee803000000200000000000000180030101003b010080004000000000000000000000000000000000" +
            "0100000000000000000000000000000000000000000000000000000000000000007fc1ff0000ff0000ff0000ff0000ff0000ff0000ff0000" +
            "0000803f00000000000000c0" + "000000400000000000000000" + "00000000000000000000000000000000"
    }
}
