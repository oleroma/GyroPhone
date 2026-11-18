package com.oleroma.gyrophone.runtime

import java.util.concurrent.atomic.AtomicInteger

/**
 * The attached controller's sticks, for a stick's settings picture: fed by the controller readers
 * only while a picture [watch]es ([on]), so the input path otherwise pays one volatile read. The
 * four values are written without a lock; a picture that reads one event's X with the next's Y
 * draws a dot a frame off, nothing more.
 */
class StickWatch {
    private val watchers = AtomicInteger()

    /** Whether any picture watches: the readers feed [set] only then. */
    @Volatile
    var on = false
        private set

    @Volatile private var lx = 0f
    @Volatile private var ly = 0f
    @Volatile private var rx = 0f
    @Volatile private var ry = 0f

    /** A picture starts watching; [stop] when it goes. */
    fun watch() {
        if (watchers.incrementAndGet() == 1) { lx = 0f; ly = 0f; rx = 0f; ry = 0f; on = true }
    }

    fun stop() {
        if (watchers.decrementAndGet() <= 0) { watchers.set(0); on = false }
    }

    /** The sticks (-1..1, y down; NaN: not reported, kept). */
    fun set(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!lx.isNaN()) this.lx = lx
        if (!ly.isNaN()) this.ly = ly
        if (!rx.isNaN()) this.rx = rx
        if (!ry.isNaN()) this.ry = ry
    }

    /** Stick [right] (else the left one): its X, Y. */
    fun x(right: Boolean) = if (right) rx else lx
    fun y(right: Boolean) = if (right) ry else ly
}
