package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Taps
import com.oleroma.gyrophone.core.model.When
import kotlin.math.min

/**
 * The triggers ([When] × [Taps]) of one Press action (a touch button, a key, a surface's finger
 * count), driven by its press edges ([down] / [up] / [cancel]) and the clock ([resolve]).
 *
 * A press is a tap when it lifts within [tapNs] and stayed clean (in the tap slop: [dirty] says
 * otherwise), a long press once it has lasted [tapNs], never both. With [doubleNs] > 0 and
 * [hasDouble] (the action has a Double mapping), a press within [doubleNs] after a tap's lift is
 * the second press of a double. A Single Tap waits out that window when [hasDouble]. A silent
 * [cancel] (the finger count changed) ends the press with none of its Tap, Release or Long press.
 * Each trigger stays on until a snapshot has shown it for the minimum pulse ([markSeen]).
 * Not thread-safe.
 */
internal class ButtonGestures(
    var tapNs: Long,
    var doubleNs: Long,
    var hasDouble: Boolean,
) {
    /** One trigger: on from [startNs], held while [holding], then on for the minimum pulse once seen. */
    class Gesture {
        var on = false
        var holding = false
        var startNs = 0L
        var seen = false

        fun start(now: Long, hold: Boolean) {
            on = true; holding = hold; startNs = now; seen = false
        }

        fun done(now: Long, minPulse: Long) = on && !holding && seen && now - startNs >= minPulse
        fun reset() {
            on = false; holding = false
        }
    }

    private var pressing = false
    private var downNs = 0L
    private var upNs = 0L

    /** The current press is the second of a double. */
    private var second = false

    /** The current press has turned into a long press. */
    private var longThisPress = false

    /** The current press left the tap slop: it is neither a tap nor a long press. */
    private var dirtyPress = false

    /** A tap lifted at [upNs]: a press within the window is the second of a double. */
    private var windowOpen = false

    /** That tap's Single Tap fires when the window closes. */
    private var tapPending = false

    /** By [When] ordinal × 3 + [Taps] ordinal. */
    private val gestures = Array(When.entries.size * 3) { Gesture() }

    private fun g(w: When, t: Taps) = gestures[w.ordinal * 3 + t.ordinal]

    fun on(w: When, t: Taps): Boolean = g(w, t).on

    /** Some trigger is on (so its device still counts as active). */
    val anyOn: Boolean get() = gestures.any { it.on }

    /** It is pressed now. */
    val pressed: Boolean get() = pressing

    private val doubles: Boolean get() = hasDouble && doubleNs > 0

    fun reset() {
        pressing = false; second = false; longThisPress = false; dirtyPress = false
        windowOpen = false; tapPending = false
        for (x in gestures) x.reset()
    }

    /** Starts the press's triggers of [w] that hold: Any, and Single or Double by [second]. */
    private fun hold(w: When, now: Long) {
        g(w, Taps.ANY).start(now, hold = true)
        g(w, if (second) Taps.DOUBLE else Taps.SINGLE).start(now, hold = true)
    }

    private fun letGo(w: When) {
        for (t in Taps.entries) g(w, t).holding = false
    }

    private fun pulse(w: When, t: Taps, now: Long) = g(w, t).start(now, hold = false)

    fun down(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (pressing) return
        pressing = true
        downNs = now
        longThisPress = false
        dirtyPress = false
        second = doubles && windowOpen && now - upNs <= doubleNs
        windowOpen = false
        // The waiting tap was the first of a double: never a Single Tap.
        if (second) tapPending = false
        hold(When.PRESS, now)
    }

    /** The press left the tap slop: it can no longer be a tap or turn into a long press. */
    fun dirty() {
        if (pressing) dirtyPress = true
    }

    fun up(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (!pressing) return
        pressing = false
        upNs = now
        letGo(When.PRESS)
        letGo(When.LONG_PRESS)
        pulse(When.RELEASE, Taps.ANY, now)
        pulse(When.RELEASE, if (second) Taps.DOUBLE else Taps.SINGLE, now)
        val tap = !longThisPress && !dirtyPress && now - downNs < tapNs
        if (tap) {
            pulse(When.TAP, Taps.ANY, now)
            when {
                second -> pulse(When.TAP, Taps.DOUBLE, now)
                doubles -> {
                    windowOpen = true
                    tapPending = true
                }

                else -> pulse(When.TAP, Taps.SINGLE, now)
            }
        }
        second = false
        // A lift stretched to the minimum pulse may come late: end what has shown long enough.
        endShown(now, minPulse)
    }

    /** Ends the press silently: its holds let go at once, and none of its Tap, Release or Long press fire. */
    fun cancel(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (!pressing) return
        pressing = false
        for (t in Taps.entries) {
            g(When.PRESS, t).reset(); g(When.LONG_PRESS, t).reset()
        }
        second = false
        windowOpen = false
        endShown(now, minPulse)
    }

    /** Due long press, waiting tap and trigger pulses. */
    fun resolve(now: Long, minPulse: Long) {
        if (pressing && !longThisPress && !dirtyPress && now - downNs >= tapNs) {
            longThisPress = true
            hold(When.LONG_PRESS, now)
        }
        if (windowOpen && now - upNs > doubleNs) {
            windowOpen = false
            if (tapPending) {
                tapPending = false
                pulse(When.TAP, Taps.SINGLE, now)
            }
        }
        endShown(now, minPulse)
    }

    /** When this next changes without input: a press turns long, a waiting tap fires, a pulse ends. */
    fun deadline(minPulse: Long): Long {
        var d = Long.MAX_VALUE
        if (pressing && !longThisPress && !dirtyPress) d = downNs + tapNs
        if (windowOpen) d = min(d, upNs + doubleNs + 1)
        for (x in gestures) if (x.on && !x.holding && x.seen) d = min(d, x.startNs + minPulse)
        return d
    }

    /** A snapshot showed the triggers that are on. */
    fun markSeen() {
        for (x in gestures) if (x.on) x.seen = true
    }

    private fun endShown(now: Long, minPulse: Long) {
        for (x in gestures) if (x.done(now, minPulse)) x.on = false
    }
}
