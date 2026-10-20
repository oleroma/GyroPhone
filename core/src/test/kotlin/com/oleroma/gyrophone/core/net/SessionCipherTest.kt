package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCipherTest {
    private val kPH = ByteArray(16) { it.toByte() }
    private val kHP = ByteArray(16) { (0x40 + it).toByte() }
    private val plain = ByteArray(Wire.STATE_PLAIN) { (it * 3).toByte() }

    private fun sealed(seq: Int = 1): ByteArray {
        val header = ByteArray(12).also { WireCodec.writeHeader(it, PacketType.STATE, 0x11223344, seq) }
        val out = ByteArray(Wire.MAX_DATAGRAM)
        val n = SessionCipher(kPH, Wire.DIR_P2H).seal(header, plain, plain.size, out)
        assertEquals(94, n)
        return out.copyOf(n)
    }

    private fun opens(pkt: ByteArray, key: ByteArray = kPH, dir: Int = Wire.DIR_P2H): Boolean =
        SessionCipher(key, dir).open(pkt, pkt.size, ByteArray(Wire.MAX_DATAGRAM))

    @Test fun roundTrip() {
        val pkt = sealed()
        val out = ByteArray(Wire.MAX_DATAGRAM)
        assertTrue(SessionCipher(kPH, Wire.DIR_P2H).open(pkt, pkt.size, out))
        assertArrayEquals(plain, out.copyOf(plain.size))
    }

    @Test fun anyFlippedBitFails() {
        val pkt = sealed()
        for (i in pkt.indices) {
            val t = pkt.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFalse("byte $i", opens(t))
        }
    }

    @Test fun wrongDirectionOrKeyFails() {
        val pkt = sealed()
        assertFalse(opens(pkt, kPH, Wire.DIR_H2P))
        assertFalse(opens(pkt, kHP, Wire.DIR_P2H))
    }

    @Test fun shortPacketFails() {
        val pkt = sealed()
        assertFalse(SessionCipher(kPH, Wire.DIR_P2H).open(pkt, 27, ByteArray(256)))
        assertFalse(SessionCipher(kPH, Wire.DIR_P2H).open(pkt, pkt.size - 1, ByteArray(256)))
    }

    @Test fun oneCipherSealsConsecutiveSeqs() {
        val c = SessionCipher(kPH, Wire.DIR_P2H)
        val header = ByteArray(12)
        val out = ByteArray(Wire.MAX_DATAGRAM)
        for (seq in 1..100) {
            WireCodec.writeHeader(header, PacketType.STATE, 1, seq)
            val n = c.seal(header, plain, plain.size, out)
            assertTrue(opens(out.copyOf(n)))
        }
    }
}
