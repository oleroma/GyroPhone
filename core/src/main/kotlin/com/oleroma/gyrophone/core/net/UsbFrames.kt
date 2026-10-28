package com.oleroma.gyrophone.core.net

/**
 * Datagrams over the direct USB cable (Android Open Accessory), which is a byte stream: each one
 * goes as `0x47, length (u16 LE), bytes`. A frame whose size is a multiple of 64 gets one 0x00
 * after it, so no USB transfer ends on a full packet (the other side would wait for more). The
 * reader skips anything that is not a frame start, which also finds its feet again mid-stream.
 * Same format as the companion's UsbFraming.
 */
object UsbFrames {
    const val MAGIC = 0x47
    const val HEADER = 3

    /** Room [encode] needs for a datagram of [len] bytes. */
    fun maxSize(len: Int) = HEADER + len + 1

    /** Writes the frame for `src[off, off + len)` to [dst] at 0 and returns its size. */
    fun encode(src: ByteArray, off: Int, len: Int, dst: ByteArray): Int {
        require(len in 1..Wire.MAX_DATAGRAM)
        dst[0] = MAGIC.toByte()
        dst.putU16(1, len)
        System.arraycopy(src, off, dst, HEADER, len)
        var n = HEADER + len
        if (n % 64 == 0) dst[n++] = 0
        return n
    }
}

/** Splits the USB byte stream back into datagrams. Not thread safe: one reader. */
class UsbDeframer {
    private val buf = ByteArray(UsbFrames.HEADER + Wire.MAX_DATAGRAM)
    private var have = 0
    private var need = 0

    /** Feeds `data[0, len)`; [onFrame] gets each datagram (its buffer is reused afterwards). */
    fun feed(data: ByteArray, len: Int, onFrame: (ByteArray, Int, Int) -> Unit) {
        var i = 0
        while (i < len) {
            val b = data[i++].toInt() and 0xFF
            if (have == 0) {
                if (b == UsbFrames.MAGIC) buf[have++] = b.toByte()
                continue
            }
            buf[have++] = b.toByte()
            if (have == UsbFrames.HEADER) {
                need = buf.getU16(1)
                if (need !in 1..Wire.MAX_DATAGRAM) {
                    have = 0 // not a frame: look for the next start
                    continue
                }
            }
            if (have > UsbFrames.HEADER && have == UsbFrames.HEADER + need) {
                onFrame(buf, UsbFrames.HEADER, need)
                have = 0
            }
        }
    }
}
