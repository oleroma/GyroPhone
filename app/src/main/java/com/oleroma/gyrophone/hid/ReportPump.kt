package com.oleroma.gyrophone.hid

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.ReportScheduler
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
 * On the OnePlus 15 the stack keeps the HID link in sniff mode (it re-enters sniff after every
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
    @Volatile private var inputArrived = false

    /** Set as `engine.onChange` while running; wakes the loop only while it waits idle. */
    private val wake = Runnable {
        if (idleWaiting) {
            inputArrived = true
            LockSupport.unpark(thread)
        }
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
    }

    private fun loop(s: ReportScheduler) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val me = Thread.currentThread()
        while (running && thread === me) {
            val start = System.nanoTime()
            val connected = hid.isConnected
            val sent = if (connected) tick(s, start) else {
                engine.snapshot() // drain motion while idle
                false
            }
            if (start - statsWindowStart >= 1_000_000_000L) {
                sentPerSecond = sentCount; failedPerSecond = failedCount
                sentCount = 0; failedCount = 0; statsWindowStart = start
            }
            waitUntil(s, start + intervalNanos, me, inputWakes = connected && !sent)
        }
    }

    /**
     * Parks until the periodic tick at [tickAt]. With [inputWakes] (the last tick sent nothing),
     * input shortens the wait: the loop then ticks as soon as the last report is one interval
     * old. Early returns from park (a stale unpark) never shorten it otherwise.
     */
    private fun waitUntil(s: ReportScheduler, tickAt: Long, me: Thread, inputWakes: Boolean) {
        var wakeAt = tickAt
        inputArrived = false
        idleWaiting = inputWakes
        while (running && thread === me) {
            if (inputWakes && inputArrived) {
                inputArrived = false
                wakeAt = ReportScheduler.inputTickAt(wakeAt, s.lastSendNanos, intervalNanos)
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
    }
}
