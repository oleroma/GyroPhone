package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Duration
import com.oleroma.gyrophone.core.model.FiresOn
import com.oleroma.gyrophone.core.model.Taps
import kotlin.math.min

/**
 * The triggers ([FiresOn] × [Taps] × [Duration], plus Don't wait taps) of one Press action (a
 * touch button, a key, a surface's finger count, a Touch direction), driven by its press edges
 * ([down] / [up] / [cancel]) and the clock ([resolve]).
 *
 * A first press is Quick when it lifts within [tapNs] and stayed clean (in the tap slop: [dirty]
 * says otherwise), Long once it has lasted [tapNs], never both. With [doubleNs] > 0 and
 * [hasDouble] (the action has a Double mapping), a press within [doubleNs] after a Quick lift is
 * the second press of a double, split into Quick and Long by [releaseNs]; with [releaseNs] = 0 its
 * Double Quick fires as it presses (and it is never Long). A Single Quick (Tap) waits out the
 * double window when doubles are on; a Don't wait tap fires on every quick lift at once.
 * Hold (Press, Single, Any) is on for every press and Release (Lift, Single, Any) fires on every
 * lift. A silent [cancel] (the finger count changed) ends the press with none of its lift or long
 * triggers. Each trigger stays on until a snapshot has shown it for the minimum pulse ([markSeen]).
 * Not thread-safe.
 */
internal class ButtonGestures(
    var tapNs: Long,
    var doubleNs: Long,
    var hasDouble: Boolean,
    var releaseNs: Long = 0L,
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

    /** The current press has turned Long. */
    private var longThisPress = false

    /** The current press left the tap slop: it is neither Quick nor Long. */
    private var dirtyPress = false

    /** A Quick first press lifted at [upNs]: a press within the window is the second of a double. */
    private var windowOpen = false

    /** That press's Tap fires when the window closes. */
    private var tapPending = false

    private val gestures = Array(FiresOn.entries.size * 6 + 1) { Gesture() }

    private fun g(f: FiresOn, t: Taps, d: Duration) = gestures[f.ordinal * 6 + t.ordinal * 3 + d.ordinal]

    /** The Don't wait tap. */
    private val tapNow get() = gestures[gestures.size - 1]

    fun on(f: FiresOn, t: Taps, d: Duration, now: Boolean = false): Boolean =
        if (now && f == FiresOn.LIFT && t == Taps.SINGLE && d == Duration.QUICK) tapNow.on else g(f, t, d).on

    /** Some trigger is on (so its device still counts as active). */
    val anyOn: Boolean get() = gestures.any { it.on }

    /** It is pressed now. */
    val pressed: Boolean get() = pressing

    private val doubles: Boolean get() = hasDouble && doubleNs > 0

    private val taps: Taps get() = if (second) Taps.DOUBLE else Taps.SINGLE

    fun reset() {
        pressing = false; second = false; longThisPress = false; dirtyPress = false
        windowOpen = false; tapPending = false
        for (x in gestures) x.reset()
    }

    private fun pulse(f: FiresOn, t: Taps, d: Duration, now: Long) = g(f, t, d).start(now, hold = false)

    fun down(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (pressing) return
        pressing = true
        downNs = now
        longThisPress = false
        dirtyPress = false
        second = doubles && windowOpen && now - upNs <= doubleNs
        windowOpen = false
        // The waiting tap was the first of a double: never a Tap.
        if (second) tapPending = false
        g(FiresOn.PRESS, Taps.SINGLE, Duration.ANY).start(now, hold = true)
        if (second) {
            g(FiresOn.PRESS, Taps.DOUBLE, Duration.ANY).start(now, hold = true)
            if (releaseNs <= 0) pulse(FiresOn.LIFT, Taps.DOUBLE, Duration.QUICK, now)
        }
    }

    /** The press left the tap slop: it can no longer be Quick or turn Long. */
    fun dirty() {
        if (pressing) dirtyPress = true
    }

    /** How long the current press may last and still be Quick; 0 = it is never Long (and Quick already fired). */
    private val quickNs: Long get() = if (second) releaseNs else tapNs

    fun up(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (!pressing) return
        pressing = false
        upNs = now
        for (t in Taps.entries) for (d in Duration.entries) g(FiresOn.PRESS, t, d).holding = false
        pulse(FiresOn.LIFT, Taps.SINGLE, Duration.ANY, now)
        val t = taps
        if (second) pulse(FiresOn.LIFT, Taps.DOUBLE, Duration.ANY, now)
        val clean = !dirtyPress
        val quick = clean && !longThisPress && now - downNs < (if (second) releaseNs else tapNs)
        if (longThisPress) pulse(FiresOn.LIFT, t, Duration.LONG, now)
        // Don't wait taps go by the tap window on either press.
        if (clean && !longThisPress && now - downNs < tapNs) tapNow.start(now, hold = false)
        when {
            second -> if (quick) pulse(FiresOn.LIFT, Taps.DOUBLE, Duration.QUICK, now)
            quick && doubles -> {
                windowOpen = true
                tapPending = true
            }

            quick -> pulse(FiresOn.LIFT, Taps.SINGLE, Duration.QUICK, now)
        }
        second = false
        // A lift stretched to the minimum pulse may come late: end what has shown long enough.
        endShown(now, minPulse)
    }

    /** Ends the press silently: its holds let go at once, and none of its lift or long triggers fire. */
    fun cancel(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (!pressing) return
        pressing = false
        for (t in Taps.entries) for (d in Duration.entries) g(FiresOn.PRESS, t, d).reset()
        second = false
        windowOpen = false
        endShown(now, minPulse)
    }

    /** Due long press, waiting tap and trigger pulses. */
    fun resolve(now: Long, minPulse: Long) {
        val q = quickNs
        if (pressing && !longThisPress && !dirtyPress && q > 0 && now - downNs >= q) {
            longThisPress = true
            g(FiresOn.PRESS, taps, Duration.LONG).start(now, hold = true)
        }
        if (windowOpen && now - upNs > doubleNs) {
            windowOpen = false
            if (tapPending) {
                tapPending = false
                pulse(FiresOn.LIFT, Taps.SINGLE, Duration.QUICK, now)
            }
        }
        endShown(now, minPulse)
    }

    /** When this next changes without input: a press turns long, a waiting tap fires, a pulse ends. */
    fun deadline(minPulse: Long): Long {
        var d = Long.MAX_VALUE
        val q = quickNs
        if (pressing && !longThisPress && !dirtyPress && q > 0) d = downNs + q
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
