package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.FireAt
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.Output

/**
 * What one mapping's [Output] keeps between looks at its trigger: Fire once pulses, a Toggle's or
 * Sticky's state. [step] is fed the trigger (on or not) whenever the mapping is evaluated, and
 * reacts to its edges, so looking several times at the same state changes nothing. Mappings of
 * one group with the same target and trigger share one state ([OutputStates]), which is
 * triggered while any of them is: an input reported as two sources (a trigger as both axis and
 * key) toggles or fires once. A pulse
 * stays on until a snapshot has shown it for the minimum pulse ([markSeen]), and a second one
 * (Fire once at both ends of a quick press) waits until a snapshot has shown the gap. Not
 * thread-safe.
 */
internal class OutputState {
    /** The trigger as of the last [step]: some mapping in [holders]. */
    private var was = false
    private val holders = HashSet<Mapping>(2)

    /** Toggle and Sticky: on. */
    var on = false

    /** Sticky: another output was pressed while it was on; it lets go once that is let go. */
    var used = false

    // Fire once: a pulse showing since [pulseNs] ([seen] by a snapshot), another one waiting, and
    // a gap after a pulse that a snapshot must show before the next.
    private var pulsing = false
    private var pulseNs = 0L
    private var seen = false
    private var waiting = false
    private var gap = false

    /** Something is on or due: its device still counts as active. */
    val busy: Boolean get() = on || pulsing || waiting

    /** The output with [m]'s trigger [pressed] at [now], as [output] (not Held) and [fireAt] say. */
    fun step(m: Mapping, pressed: Boolean, output: Output, fireAt: FireAt, now: Long, minPulse: Long): Boolean {
        if (pressed) holders.add(m) else holders.remove(m)
        val trigger = holders.isNotEmpty()
        val rise = trigger && !was
        val fall = !trigger && was
        was = trigger
        when (output) {
            Output.HELD -> return trigger
            Output.FIRE_ONCE -> {
                endPulse(now, minPulse)
                if (rise && fireAt != FireAt.END) pulse(now)
                if (fall && fireAt != FireAt.START) pulse(now)
                return pulsing
            }

            Output.TOGGLE -> {
                if (rise) on = !on
                return on
            }

            Output.STICKY -> {
                if (rise) {
                    on = !on; used = false
                }
                return on
            }
        }
    }

    private fun pulse(now: Long) {
        if (pulsing || gap) waiting = true else start(now)
    }

    private fun start(now: Long) {
        pulsing = true; pulseNs = now; seen = false; waiting = false
    }

    private fun endPulse(now: Long, minPulse: Long) {
        if (pulsing && seen && now - pulseNs >= minPulse) {
            pulsing = false
            gap = true
        }
    }

    /** When this next changes without input (Long.MAX_VALUE = never). */
    fun deadline(minPulse: Long): Long = if (pulsing && seen) pulseNs + minPulse else Long.MAX_VALUE

    /** A snapshot showed the output: a pulse counts as seen, a gap as shown and the waiting pulse starts. */
    fun markSeen(now: Long) {
        if (pulsing) seen = true
        else if (gap) {
            gap = false
            if (waiting) start(now)
        }
    }
}

/** The output states of one group's mappings, one per target and trigger ([OutputState]). */
internal class OutputStates {
    private val byMapping = HashMap<Mapping, OutputState>()
    private val shared = HashMap<Mapping, OutputState>()

    /** Each state once. */
    val all = ArrayList<OutputState>()

    fun of(m: Mapping): OutputState = byMapping.getOrPut(m) {
        // Keyed by target and trigger alone.
        shared.getOrPut(Mapping(SHARED, m.target, 0, m.trigger)) { OutputState().also { all.add(it) } }
    }

    /** The states of the mappings [pick] picks. */
    inline fun forEach(pick: (Mapping) -> Boolean, f: (OutputState) -> Unit) {
        for ((m, st) in byMapping) if (pick(m)) f(st)
    }

    fun isNotEmpty() = all.isNotEmpty()
    fun clear() {
        byMapping.clear(); shared.clear(); all.clear()
    }

    private companion object {
        val SHARED = com.oleroma.gyrophone.core.model.InputSource.Touch("")
    }
}
