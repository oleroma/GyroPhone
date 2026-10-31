package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.MapOptions
import kotlin.math.max
import kotlin.math.min

/**
 * One mapping's output shaped by its [MapOptions], driven by its source's activations
 * ([update]) and the clock. Without toggle an activation turns the output on from its start
 * plus the start delay until its end, held at least the minimum hold, plus the end delay; with
 * toggle each activation turns it on (from the start delay) or off (after the minimum hold and
 * end delay). With turbo the output presses and lets go at the turbo rate while on. Not
 * thread-safe.
 */
internal class Shaper(var options: MapOptions) {
    private var raw = false
    private var startNs = Long.MIN_VALUE
    private var endNs = Long.MIN_VALUE
    private var latched = false

    /** Whether its event target ([MappingEngine]'s wheel, gyro toggle, layer) counted as engaged last time. */
    var eventOn = false

    private val startDelay get() = options.startDelayMs.coerceIn(0, MapOptions.MAX_DELAY_MS) * MS
    private val endDelay get() = options.endDelayMs.coerceIn(0, MapOptions.MAX_DELAY_MS) * MS
    private val minHold get() = options.minHoldMs.coerceIn(0, MapOptions.MAX_DELAY_MS) * MS
    private val halfPeriod get() = 500 * MS / options.rate

    /** The output is in its on window at [now] (before turbo). */
    private fun inWindow(now: Long) = now >= startNs && now < endNs

    /** The source is [on] at [now]: its edges start and end activations. */
    fun update(on: Boolean, now: Long) {
        if (on == raw) return
        raw = on
        if (options.toggle) {
            if (!on) return
            if (!latched) {
                latched = true; open(now)
            } else {
                latched = false; endNs = max(now, startNs + minHold) + endDelay
            }
        } else if (on) open(now) else endNs = max(now, startNs + minHold) + endDelay
    }

    /** An activation at [now]: on from its start delay; one still on carries on. */
    private fun open(now: Long) {
        if (!inWindow(now)) startNs = now + startDelay
        endNs = Long.MAX_VALUE
    }

    /** Whether the output is pressed at [now]. */
    fun on(now: Long): Boolean {
        if (!inWindow(now)) return false
        if (!options.turbo) return true
        return ((now - startNs) / halfPeriod) % 2 == 0L
    }

    /** Whether the output may still change without input (a window or turbo running). */
    val busy: Boolean get() = startNs != Long.MIN_VALUE && endNs > startNs

    /** When the output next changes without input after [now]; Long.MAX_VALUE = not by itself. */
    fun deadline(now: Long): Long {
        if (now < startNs) return startNs
        if (now >= endNs) return Long.MAX_VALUE
        var d = endNs
        if (options.turbo) d = min(d, startNs + ((now - startNs) / halfPeriod + 1) * halfPeriod)
        return d
    }

    fun reset() {
        raw = false; latched = false; eventOn = false; startNs = Long.MIN_VALUE; endNs = Long.MIN_VALUE
    }

    private companion object {
        const val MS = 1_000_000L
    }
}

/** Whose turn it is among the cycle mappings of one source ([MapOptions.cycle]): each activation moves on. */
internal class CycleTurn {
    private var raw = false
    var turn = -1
        private set

    /** The shared source is [on]: a new activation moves to the next of [size]. */
    fun update(on: Boolean, size: Int) {
        if (on == raw) return
        raw = on
        if (on && size > 0) turn = (turn + 1) % size
    }
}
