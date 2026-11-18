package com.oleroma.gyrophone.hid

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.ReportScheduler
import com.oleroma.gyrophone.runtime.WorkHint
import java.util.concurrent.locks.LockSupport

/**
 * Dedicated thread that snapshots the mapping engine at a fixed interval and sends only what
 * changed. Mouse motion accumulates between ticks, so a fast gyro never floods the Bluetooth
 * queue; game pad and keyboard reports go out only on change (and once a second while idle).
 *
 * The rate matters: sendReport() only enqueues, and Android gives no "sent" callback. If we
 * enqueue faster than the Bluetooth link drains, reports pile up in the stack and every new
 * movement waits behind the backlog, which feels like heavy, growing lag.
 *
 * On the reference phone the stack keeps the HID link in sniff mode (it re-enters sniff after every
 * report and cannot be told not to), which allows roughly one packet per sniff interval. So
 * each tick sends at most ONE report, chosen by [ReportScheduler], and two reports are never
 * closer than one interval. After a tick that sent nothing, input (the engine's onChange) ends
 * the wait early instead of waiting for the next tick on the grid.
 */
class ReportPump(
    private val engine: MappingEngine,
    private val hid: HidDeviceManager,
    @Volatile var intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
) {
    @Volatile private var running = false
    /** The current loop's thread; a loop whose thread is no longer this one exits. */
    @Volatile private var thread: Thread? = null
    /** Report state of the current loop; a fresh one per start(). */
    @Volatile private var scheduler = ReportScheduler(::send)
    /** Set by [invalidate]; the pump thread resets [scheduler] before its next snapshot. */
    @Volatile private var invalidated = false
    /** The loop sent nothing on its last tick and is waiting: input may end the wait. */
    @Volatile private var idleWaiting = false
    /**
     * Bumped on every input. The loop reads it before its snapshot, so input that lands between
     * the snapshot and the start of the wait is still seen there (a lost bump from two threads at
     * once does not matter: the value still differs).
     */
    @Volatile private var inputSeq = 0

    /** Set as `engine.onChange` while running; wakes the loop only while it waits idle. */
    private val wake = Runnable {
        inputSeq++
        if (idleWaiting) LockSupport.unpark(thread)
    }

    /** Reports handed to the stack / refused by it during the last full second. */
    @Volatile var sentPerSecond = 0
        private set
    @Volatile var failedPerSecond = 0
        private set
    private var sentCount = 0
    private var failedCount = 0
    private var statsWindowStart = 0L

    /** Latest encoded reports, for GET_REPORT requests from the host. */
    val lastGamepadReport: ByteArray get() = scheduler.lastGamepadReport
    val lastKeyboardReport: ByteArray get() = scheduler.lastKeyboardReport
    val lastConsumerReport: ByteArray get() = scheduler.lastConsumerReport

    /**
     * The mouse feature report the host last set: which resolution multipliers are on. Until the
     * host enables one, that axis is reported in whole notches (the rest stays pending), so a
     * host that ignores the multiplier never scrolls 120 times too far.
     */
    @Volatile var mouseFeature = 0

    fun start() {
        if (running) return
        running = true
        invalidated = false
        val s = ReportScheduler(::send)
        scheduler = s
        val t = Thread({ loop(s) }, "hid-report-pump").apply { priority = Thread.MAX_PRIORITY }
        thread = t
        engine.onChange = wake
        t.start()
    }

    fun stop() {
        val t = thread ?: return
        running = false
        thread = null
        if (engine.onChange === wake) engine.onChange = null
        t.interrupt()
        try {
            t.join(STOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        sentPerSecond = 0
        failedPerSecond = 0
    }

    /** Forces a full re-send on the next tick (after a host connects); safe from any thread. */
    fun invalidate() {
        invalidated = true
        // The loop may be parked for a long while with no host: let it start at once.
        thread?.let { LockSupport.unpark(it) }
    }

    private fun loop(s: ReportScheduler) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val me = Thread.currentThread()
        val hint = WorkHint.forThisThread()
        var wasConnected = false
        while (running && thread === me) {
            val start = System.nanoTime()
            if (!hid.isConnected) {
                // No host: tick slowly (a host connecting unparks the loop via [invalidate]).
                wasConnected = false
                engine.snapshot() // drain motion while idle
                if (start - statsWindowStart >= 1_000_000_000L) {
                    sentPerSecond = 0; failedPerSecond = 0
                    sentCount = 0; failedCount = 0; statsWindowStart = start
                }
                waitDisconnected(start + NO_HOST_INTERVAL_NANOS, me)
                continue
            }
            if (!wasConnected) {
                // Just connected: drop motion gathered during the slow idle ticks.
                wasConnected = true
                engine.snapshot()
            }
            val seenInput = inputSeq
            val sent = tick(s, start)
            // The pass is done: how long it took goes to the performance hint.
            if (hint != null) {
                val end = System.nanoTime()
                hint.done(end - start, end)
            }
            if (start - statsWindowStart >= 1_000_000_000L) {
                sentPerSecond = sentCount; failedPerSecond = failedCount
                sentCount = 0; failedCount = 0; statsWindowStart = start
            }
            waitUntil(s, start + intervalNanos, me, inputWakes = !sent, seenInput)
        }
        hint?.close()
    }

    /** Parks until [until], or until a host connects. */
    private fun waitDisconnected(until: Long, me: Thread) {
        while (running && thread === me && !hid.isConnected) {
            val wait = until - System.nanoTime()
            if (wait <= 0) break
            LockSupport.parkNanos(wait)
        }
    }

    /**
     * Parks until the periodic tick at [tickAt]. With [inputWakes] (the last tick sent nothing),
     * input shortens the wait: the loop then ticks as soon as the last report is one interval
     * old. Input counts from [seenInput] ([inputSeq] before the tick's snapshot), so input that
     * came after the snapshot but before the wait began is not missed. Early returns from park (a
     * stale unpark) never shorten it otherwise.
     */
    private fun waitUntil(s: ReportScheduler, tickAt: Long, me: Thread, inputWakes: Boolean, seenInput: Int) {
        var wakeAt = tickAt
        var seen = seenInput
        // Set before reading [inputSeq] below: any input after this read unparks the loop.
        idleWaiting = inputWakes
        while (running && thread === me) {
            if (inputWakes) {
                val seq = inputSeq
                if (seq != seen) {
                    seen = seq
                    wakeAt = ReportScheduler.inputTickAt(wakeAt, s.lastSendNanos, intervalNanos)
                }
            }
            val wait = wakeAt - System.nanoTime()
            if (wait <= 0) break
            LockSupport.parkNanos(wait)
        }
        idleWaiting = false
    }

    private fun send(reportId: Int, bytes: ByteArray): Boolean {
        val ok = hid.send(reportId, bytes)
        if (ok) sentCount++ else failedCount++
        return ok
    }

    /** One snapshot, at most one report. Returns whether a report was handed to the stack. */
    private fun tick(s: ReportScheduler, now: Long): Boolean {
        if (invalidated) {
            invalidated = false
            s.reset()
        }
        return s.tick(engine.snapshot(), now, mouseFeature)
    }

    companion object {
        /**
         * Selectable report rates. In sniff mode (AOSP HID default ~11.25 ms) the link carries
         * roughly 89 packets/s at best, so higher rates only build a queue. 60 Hz leaves headroom.
         */
        val RATES_HZ = intArrayOf(30, 45, 60, 75, 90)
        const val DEFAULT_RATE_HZ = 60
        const val DEFAULT_INTERVAL_NANOS = 1_000_000_000L / DEFAULT_RATE_HZ
        private const val STOP_JOIN_MS = 500L
        /** How often the loop wakes with no host connected (only to drain the engine's motion). */
        private const val NO_HOST_INTERVAL_NANOS = 250_000_000L
    }
}
