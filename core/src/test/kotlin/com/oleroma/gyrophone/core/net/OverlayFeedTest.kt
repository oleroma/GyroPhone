package com.oleroma.gyrophone.core.net

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
        assertEquals(140, Wire.sizeOf(PacketType.OVERLAY))
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
        f.setLayout(layout)
        f.setLayout(layout.copy()) // the same layout keeps its version
        assertEquals(1, f.layoutSnapshot()!!.first)
        f.padShown = true
        f.gyro(100f, -50f, 25f, 0.01f, 3, true)
        f.up(0f, 9.8f, 0f, 9.8f)
        f.north(0f, 0f, -2f)
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
            "0100000000000000000000000000000000000000000000000000000000000000007fc1ff0000ff0000ff0000ff0000ff0000ff0000ff0000"
    }
}
