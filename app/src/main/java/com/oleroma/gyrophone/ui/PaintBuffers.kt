package com.oleroma.gyrophone.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.util.Log
import com.oleroma.gyrophone.core.engine.LaserPaint

/**
 * A pointer's paint map ([LaserPaint]) kept where the GPU reads it: two RGBA HardwareBuffers
 * (amount, fire, aim, glow; glow's fire and aim shares) and one for the way colours. The phone's
 * CPU and GPU share memory, so new paint is written straight into them, only the rows it changed:
 * no full copy and no upload each time, so the paint can follow the beam on every frame.
 * [create] gives null where the phone can't make such buffers (the old upload path is used then).
 */
internal class PaintBuffers private constructor(val width: Int, val height: Int) {
    private val main = buffer(width, height)
    private val glows = buffer(width, height)
    private var way: HardwareBuffer? = null

    /** Shaders sampling the buffers as they are now, made again after each write so the GPU reads the new paint. */
    var mainShader: BitmapShader? = null
        private set
    var glowShader: BitmapShader? = null
        private set
    var wayShader: BitmapShader? = null
        private set

    /** Writes rows [j0] .. [j1] of [lp]'s maps; false if the buffers could not be written. */
    fun write(lp: LaserPaint, j0: Int, j1: Int): Boolean {
        if (j1 >= j0 && !nativeWritePaint(main, glows, width, j0, j1, lp.amount, lp.fire, lp.aim, lp.glow, lp.glowFire, lp.glowAim)) return false
        mainShader = shaderOf(main) ?: return false
        glowShader = shaderOf(glows) ?: return false
        return true
    }

    /** Writes rows [j0] .. [j1] of the way colours ([LaserPaint.way], ARGB); false if it could not. */
    fun writeWay(ways: IntArray, j0: Int, j1: Int): Boolean {
        val w = way ?: buffer(width, height).also { way = it }
        if (j1 >= j0 && !nativeWriteWay(w, width, j0, j1, ways)) return false
        wayShader = shaderOf(w) ?: return false
        return true
    }

    companion object {
        private const val TAG = "PaintBuffers"
        private val loaded: Boolean = runCatching { System.loadLibrary("gyrobuffer") }.isSuccess
        private const val USAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
        private val SRGB = ColorSpace.get(ColorSpace.Named.SRGB)

        fun create(width: Int, height: Int): PaintBuffers? {
            if (!loaded) return null
            return try {
                if (!HardwareBuffer.isSupported(width, height, HardwareBuffer.RGBA_8888, 1, USAGE)) return null
                PaintBuffers(width, height)
            } catch (e: RuntimeException) {
                Log.w(TAG, "no paint buffers", e)
                null
            }
        }

        private fun buffer(width: Int, height: Int) = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, USAGE)

        // A fresh wrapper (no copy) each time, so drawing takes the buffer's new contents rather than a cached picture of it.
        private fun shaderOf(b: HardwareBuffer): BitmapShader? {
            val bm = Bitmap.wrapHardwareBuffer(b, SRGB) ?: return null
            return BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        }

        @JvmStatic private external fun nativeWritePaint(
            a: HardwareBuffer, b: HardwareBuffer, width: Int, j0: Int, j1: Int,
            amount: ByteArray, fire: ByteArray, aim: ByteArray, glow: ByteArray, glowFire: ByteArray, glowAim: ByteArray,
        ): Boolean
        @JvmStatic private external fun nativeWriteWay(w: HardwareBuffer, width: Int, j0: Int, j1: Int, way: IntArray): Boolean
    }
}
