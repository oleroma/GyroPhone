package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.ButtonGesture
import kotlin.math.min

/**
 * The [ButtonGesture]s of one button-like input (a touch button, a key of a device), driven by
 * its press edges ([down] / [up]) and the clock ([resolve]). A press shorter than [longNs] is a
 * tap, one held to [longNs] a long press. With [hasDouble], a second press within [doubleNs]
 * after a tap is a double tap (neither a tap nor a long press); the tap waits out that window
 * only when [tapWaits] (its double tap does something else). Each gesture stays on until a
 * snapshot has shown it for the minimum pulse ([markSeen]). Not thread-safe.
 */
internal class ButtonGestures(
    var longNs: Long,
    var doubleNs: Long,
    var hasDouble: Boolean,
    var tapWaits: Boolean,
) {
    /** One gesture: on from [startNs], held while [holding], then on for the minimum pulse once seen. */
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

    /** The current press is the second of a double tap. */
    private var second = false

    /** The current press has turned into a long press. */
    private var longThisPress = false

    /** A tap lifted at [upNs]: a press within the window is a double tap. */
    private var windowOpen = false

    /** That tap fires when the window closes ([tapWaits]). */
    private var tapPending = false

    val tap = Gesture()
    val double = Gesture()
    val long = Gesture()
    val release = Gesture()

    fun gesture(g: ButtonGesture): Gesture = when (g) {
        ButtonGesture.TAP -> tap
        ButtonGesture.DOUBLE_TAP -> double
        ButtonGesture.LONG_PRESS -> long
        ButtonGesture.RELEASE -> release
    }

    fun on(g: ButtonGesture): Boolean = gesture(g).on

    /** Some gesture is on (so its device still counts as active). */
    val anyOn: Boolean get() = tap.on || double.on || long.on || release.on

    fun reset() {
        pressing = false; second = false; longThisPress = false; windowOpen = false; tapPending = false
        tap.reset(); double.reset(); long.reset(); release.reset()
    }

    fun down(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (pressing) return
        pressing = true
        downNs = now
        longThisPress = false
        second = hasDouble && windowOpen && now - upNs <= doubleNs
        windowOpen = false
        if (second) {
            tapPending = false
            double.start(now, hold = true)
        }
    }

    fun up(now: Long, minPulse: Long) {
        resolve(now, minPulse)
        if (!pressing) return
        pressing = false
        upNs = now
        release.start(now, hold = false)
        when {
            second -> double.holding = false
            longThisPress -> long.holding = false
            else -> {
                windowOpen = hasDouble
                tapPending = hasDouble && tapWaits
                if (!tapPending) tap.start(now, hold = false)
            }
        }
        second = false
        // A lift stretched to the minimum pulse may come late: end what has shown long enough.
        endShown(now, minPulse)
    }

    /** Due long press, waiting tap and gesture pulses. */
    fun resolve(now: Long, minPulse: Long) {
        if (pressing && !second && !longThisPress && now - downNs >= longNs) {
            longThisPress = true
            long.start(now, hold = true)
        }
        if (windowOpen && now - upNs > doubleNs) {
            windowOpen = false
            if (tapPending) {
                tapPending = false
                tap.start(now, hold = false)
            }
        }
        endShown(now, minPulse)
    }

    /** When this next changes without input: a press turns long, a waiting tap fires, a pulse ends. */
    fun deadline(minPulse: Long): Long {
        var d = Long.MAX_VALUE
        if (pressing && !second && !longThisPress) d = downNs + longNs
        if (tapPending) d = min(d, upNs + doubleNs + 1)
        if (tap.on && !tap.holding && tap.seen) d = min(d, tap.startNs + minPulse)
        if (double.on && !double.holding && double.seen) d = min(d, double.startNs + minPulse)
        if (long.on && !long.holding && long.seen) d = min(d, long.startNs + minPulse)
        if (release.on && !release.holding && release.seen) d = min(d, release.startNs + minPulse)
        return d
    }

    /** A snapshot showed the gestures that are on. */
    fun markSeen() {
        if (tap.on) tap.seen = true
        if (double.on) double.seen = true
        if (long.on) long.seen = true
        if (release.on) release.seen = true
    }

    private fun endShown(now: Long, minPulse: Long) {
        if (tap.done(now, minPulse)) tap.on = false
        if (double.done(now, minPulse)) double.on = false
        if (long.done(now, minPulse)) long.on = false
        if (release.done(now, minPulse)) release.on = false
    }
}
