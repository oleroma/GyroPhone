package com.oleroma.gyrophone.sensor

import android.hardware.HardwareBuffer

/** Reads a HardwareBuffer's bytes on the CPU, through the small native library gyrobuffer. */
class HardwareBufferReader(buffer: HardwareBuffer) : AutoCloseable {
    private var handle = nativeAcquire(buffer).also { require(it != 0L) { "no native buffer" } }

    /** Copies the first [size] bytes into [dst]; false when the buffer could not be locked. */
    fun read(dst: ByteArray, size: Int): Boolean = handle != 0L && nativeRead(handle, dst, size)

    override fun close() {
        nativeRelease(handle)
        handle = 0L
    }

    companion object {
        /** False when the library failed to load: the gralloc direct channel is then not offered. */
        val loaded: Boolean = runCatching { System.loadLibrary("gyrobuffer") }.isSuccess

        @JvmStatic private external fun nativeAcquire(buffer: HardwareBuffer): Long
        @JvmStatic private external fun nativeRead(handle: Long, dst: ByteArray, size: Int): Boolean
        @JvmStatic private external fun nativeRelease(handle: Long)
    }
}
