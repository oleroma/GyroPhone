package com.oleroma.gyrophone.core.net

// Allocation-free little-endian accessors. Unsigned 32-bit values are written from the low 32
// bits of an Int (the bit pattern) and read back as a non-negative Long.

fun ByteArray.putU8(off: Int, v: Int) {
    this[off] = v.toByte()
}

fun ByteArray.putU16(off: Int, v: Int) {
    this[off] = v.toByte()
    this[off + 1] = (v ushr 8).toByte()
}

fun ByteArray.putI16(off: Int, v: Int) = putU16(off, v)

fun ByteArray.putU32(off: Int, v: Int) {
    this[off] = v.toByte()
    this[off + 1] = (v ushr 8).toByte()
    this[off + 2] = (v ushr 16).toByte()
    this[off + 3] = (v ushr 24).toByte()
}

fun ByteArray.putU32(off: Int, v: Long) = putU32(off, v.toInt())

fun ByteArray.putI32(off: Int, v: Int) = putU32(off, v)

fun ByteArray.putU64(off: Int, v: Long) {
    putU32(off, v.toInt())
    putU32(off + 4, (v ushr 32).toInt())
}

fun ByteArray.putI64(off: Int, v: Long) = putU64(off, v)

fun ByteArray.getU8(off: Int): Int = this[off].toInt() and 0xFF

fun ByteArray.getI8(off: Int): Int = this[off].toInt()

fun ByteArray.getU16(off: Int): Int = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

fun ByteArray.getI16(off: Int): Int = getU16(off).toShort().toInt()

fun ByteArray.getI32(off: Int): Int =
    (this[off].toInt() and 0xFF) or
        ((this[off + 1].toInt() and 0xFF) shl 8) or
        ((this[off + 2].toInt() and 0xFF) shl 16) or
        ((this[off + 3].toInt() and 0xFF) shl 24)

fun ByteArray.getU32(off: Int): Long = getI32(off).toLong() and 0xFFFF_FFFFL

fun ByteArray.getU64(off: Int): Long = getU32(off) or (getU32(off + 4) shl 32)

fun ByteArray.getI64(off: Int): Long = getU64(off)
