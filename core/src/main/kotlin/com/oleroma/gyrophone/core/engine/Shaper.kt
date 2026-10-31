package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Trigger
import kotlin.math.max

/**
 * One mapping's output shaped by its [MapOptions], driven by its source's level ([update]) and
 * the clock. First [trigger] works out its activations from the source (HOLD: the source itself;
 * LONG_PRESS: once it has been on for [longNs]; RELEASE: [update]'s rise is the lift, an instant; DOUBLE_TAP:
 * a rise within [doubleNs] of the one before, while on). Then [MapOptions.mode]: HELD is on from
 * an activation's start plus the start delay until its end, held at least the minimum hold
 * (and [pulseNs]), plus the end delay; ONCE is on for that minimum only; TOGGLE turns on at one
 * activation (from the start delay) and off at the next (after the minimum hold and end delay).
 * Not thread-safe.
 */
internal class Shaper(var options: MapOptions, var trigger: Trigger = Trigger.HOLD) {
    /** The long-press time and double-tap window of its source, for [trigger]. */
    var longNs = 0L
    var doubleNs = 0L

    /** The shortest press it outputs, so a snapshot shows it. */
    var pulseNs = DEFAULT_PULSE_NS

    private var raw = false

    /** A swipe Release: the source's lift count it last saw ([Touchpad.liftSeq]); MIN = none yet. */
    var liftSeq = Long.MIN_VALUE
    private var rawStartNs = Long.MIN_VALUE
    private var lastRiseNs = Long.MIN_VALUE
    private var double = false

    /** The activation (after [trigger]) is on. */
    private var active = false
    private var startNs = Long.MIN_VALUE
    private var endNs = Long.MIN_VALUE
    private var latched = false

    /** Whether its event target ([MappingEngine]'s wheel, gyro toggle, layer) counted as engaged last time. */
    var eventOn = false

    private val startDelay get() = options.startDelayMs.coerceIn(0, MapOptions.MAX_DELAY_MS) * MS
    private val endDelay get() = options.endDelayMs.coerceIn(0, MapOptions.MAX_DELAY_MS) * MS
    private val minHold get() = max(options.minHoldMs.coerceIn(0, MapOptions.MAX_DELAY_MS) * MS, pulseNs)

    /** The output is in its on window at [now]. */
    private fun inWindow(now: Long) = now >= startNs && now < endNs

    /** The source is [on] at [now]. */
    fun update(on: Boolean, now: Long) {
        val rose = on && !raw
        raw = on
        if (rose) {
            rawStartNs = now
            double = lastRiseNs != Long.MIN_VALUE && now - lastRiseNs <= doubleNs
            // A double's second rise starts no new one.
            lastRiseNs = if (double) Long.MIN_VALUE else now
        }
        when (trigger) {
            // [on] is the lift itself (the engine works it out): an instant.
            Trigger.RELEASE -> if (rose) {
                activation(true, now); activation(false, now)
            }

            Trigger.LONG_PRESS -> level(on && now - rawStartNs >= longNs, now)
            Trigger.DOUBLE_TAP -> level(on && double, now)
            else -> level(on, now)
        }
    }

    private fun level(on: Boolean, now: Long) {
        if (on == active) return
        active = on
        activation(on, now)
    }

    /** The same button or surface touched last time ([touched]). */
    private var baseOn = false

    /** An activation starts ([on]) or ends at [now]. */
    private fun activation(on: Boolean, now: Long) {
        if (on && swallow) {
            swallow = false; return
        }
        when (options.mode) {
            How.HELD -> if (on) open(now) else endNs = max(now, startNs + minHold) + endDelay
            How.ONCE -> if (on) {
                startNs = now + startDelay; endNs = startNs + minHold + endDelay
            }

            How.TOGGLE -> if (on) {
                // Ended some other way meanwhile (a timer, Let go): this one starts it again.
                if (latched && now >= endNs) latched = false
                if (!latched) {
                    latched = true; nextUsed = false; open(now)
                    val timer = options.endsAfterMs.coerceIn(0, MapOptions.MAX_TOGGLE_MS)
                    if (timer > 0) endNs = startNs + timer * MS
                } else {
                    latched = false; endNs = max(now, startNs + minHold) + endDelay
                }
            }
        }
    }

    /** An activation at [now]: on from its start delay; one still on carries on. */
    private fun open(now: Long) {
        if (!inWindow(now)) startNs = now + startDelay
        endNs = Long.MAX_VALUE
    }

    /**
     * A toggle that is on ends now ("Also ends on": the same button touched again, a Let go, the
     * next input). Nothing when it is off.
     */
    fun letGo(now: Long) {
        if (!latched) return
        latched = false
        if (now < endNs) endNs = max(now, startNs + minHold) + endDelay
    }

    /** A toggle is on now ([MapOptions.mode] TOGGLE, turned on and not ended). */
    fun toggledOn(now: Long): Boolean = latched && now < endNs

    /** A touch ended the toggle ([touched]): that touch's own activation starts nothing. */
    private var swallow = false
    private var baseRiseNs = Long.MIN_VALUE

    /** With [MapOptions.endsOnNextInput]: another input was pressed while it was on (it ends as that is let go). */
    var nextUsed = false

    /**
     * The same button or surface is [on] (touched) at [now], seen before [update]: with
     * [MapOptions.endsOnTap], touching it again ends a toggle that is on, and the activation that
     * touch makes (its tap, long press, ...) does not turn it on again.
     */
    fun touched(on: Boolean, now: Long) {
        val rose = on && !baseOn
        baseOn = on
        if (!rose) return
        // A double tap's second touch belongs with the first.
        if (trigger != Trigger.DOUBLE_TAP || baseRiseNs == Long.MIN_VALUE || now - baseRiseNs > doubleNs) swallow = false
        baseRiseNs = now
        if (options.endsOnTap && options.mode == How.TOGGLE && toggledOn(now)) {
            letGo(now); swallow = true
        }
    }

    /** It is on at [now], latched, or turns on or off by itself later. */
    fun busy(now: Long): Boolean = latched || now < endNs

    /** Whether the output is pressed at [now]. */
    fun on(now: Long): Boolean = inWindow(now)

    /** When the output next changes without input after [now]; Long.MAX_VALUE = not by itself. */
    fun deadline(now: Long): Long {
        var d = Long.MAX_VALUE
        if (trigger == Trigger.LONG_PRESS && raw && !active) d = rawStartNs + longNs
        if (now < startNs) return minOf(d, startNs)
        if (now >= endNs) return d
        return minOf(d, endNs)
    }

    fun reset() {
        raw = false; active = false; double = false; latched = false; eventOn = false; baseOn = false
        swallow = false; nextUsed = false; baseRiseNs = Long.MIN_VALUE
        rawStartNs = Long.MIN_VALUE; lastRiseNs = Long.MIN_VALUE; startNs = Long.MIN_VALUE; endNs = Long.MIN_VALUE
    }

    companion object {
        private const val MS = 1_000_000L
        const val DEFAULT_PULSE_NS = 40_000_000L
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
