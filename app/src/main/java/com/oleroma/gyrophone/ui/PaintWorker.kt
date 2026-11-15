package com.oleroma.gyrophone.ui

import android.os.Process
import com.oleroma.gyrophone.core.engine.LaserPaint
import com.oleroma.gyrophone.core.engine.LaserTrace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Paints the pointer's paint ([LaserTrace.paintPending]) off the displays' drawing thread, on a
 * thread of its own ("gp-paint"), sharing each big stamp's rows out over [ROW_THREADS] more threads
 * ([LaserPaint.RowRunner]): a wide brush on a fast turn is a lot of texels, and the phone has eight
 * cores. The drawing thread waits for it only up to a budget each frame ([await]); past that the
 * frame shows the paint as far as it got, and the rest shows on a later frame, so a burst of paint
 * never stalls the displays. All at display priority, under the input threads.
 */
internal object PaintWorker {
    private val painter: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); r.run() }, "gp-paint").apply { isDaemon = true }
    }
    private val rowPool: ExecutorService = Executors.newFixedThreadPool(ROW_THREADS) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); r.run() }, "gp-paint-rows").apply { isDaemon = true }
    }

    /** Splits a stamp's rows into [ROW_THREADS] + 1 bands: the others on [rowPool], one on the calling thread. */
    private val rows = LaserPaint.RowRunner { j0, j1, body ->
        val parts = ROW_THREADS + 1
        val n = j1 - j0 + 1
        val done = CountDownLatch(parts - 1)
        for (k in 1 until parts) {
            val a = j0 + n * k / parts
            val b = j0 + n * (k + 1) / parts - 1
            rowPool.execute {
                try {
                    if (b >= a) body(a, b)
                } finally {
                    done.countDown()
                }
            }
        }
        val b0 = j0 + n / parts - 1
        if (b0 >= j0) body(j0, b0)
        done.await()
    }

    private val running = java.util.WeakHashMap<LaserTrace, Future<*>>()

    /** Makes [t] paint on this worker (from now on, its new maps too). */
    fun adopt(t: LaserTrace) {
        LaserPaint.defaultRows = rows
        t.paint?.rows = rows
        t.deferPaint = true
    }

    /** Starts painting [t]'s waiting stamps, unless that is already going. Drawing thread. */
    fun start(t: LaserTrace) {
        val f = running[t]
        if (f != null && !f.isDone) return
        if (!t.paintWaiting) return
        running[t] = painter.submit { t.paintPending() }
    }

    /** Waits up to [budgetNs] for [t]'s painting to finish; true when nothing is left to paint. Drawing thread. */
    fun await(t: LaserTrace, budgetNs: Long): Boolean {
        val f = running[t] ?: return true
        return try {
            f.get(budgetNs, TimeUnit.NANOSECONDS)
            true
        } catch (e: TimeoutException) {
            false
        } catch (e: Exception) {
            true
        }
    }

    /** Threads beside gp-paint for a big stamp's rows. */
    private const val ROW_THREADS = 3
}
