package com.oleroma.gyrophone.hid

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.hid.KeyboardState
import com.oleroma.gyrophone.core.hid.MouseState
import java.util.concurrent.locks.LockSupport

/**
 * Dedicated thread that snapshots the mapping engine at a fixed interval and sends only what
 * changed. Mouse motion accumulates between ticks, so a fast gyro never floods the Bluetooth
 * queue; game pad and keyboard reports go out only on change.
 *
 * The rate matters: sendReport() only enqueues, and Android gives no "sent" callback. If we
 * enqueue faster than the Bluetooth link drains, reports pile up in the stack and every new
 * movement waits behind the backlog, which feels like heavy, growing lag.
 *
 * On the OnePlus 15 the stack keeps the HID link in sniff mode (it re-enters sniff after every
 * report and cannot be told not to), which allows roughly one packet per sniff interval. So
 * each tick sends at most ONE report: keyboard changes first, then mouse button changes, then
 * game pad and mouse take turns. Mouse motion that is not sent yet keeps accumulating, so
 * nothing is lost or queued.
 */
class ReportPump(
    private val engine: MappingEngine,
    private val hid: HidDeviceManager,
    @Volatile var intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile private var lastGamepad: GamepadState? = null
    @Volatile private var lastKeyboard: KeyboardState? = null
    private var lastMouseButtons = 0
    private var pendingDx = 0
    private var pendingDy = 0
    /** Wheel and pan not yet reported, in 1/120 notch. */
    private var pendingWheel = 0
    private var pendingPan = 0
    private var mouseButtons = 0
    private var mouseTurn = false
    /** Mouse buttons pressed in a snapshot but not yet reported to the host. */
    private var latchedDown = 0
    /** Mouse buttons released in a snapshot but not yet reported to the host. */
    private var latchedUp = 0

    /** Reports handed to the stack / refused by it during the last full second. */
    @Volatile var sentPerSecond = 0
        private set
    @Volatile var failedPerSecond = 0
        private set
    private var sentCount = 0
    private var failedCount = 0
    private var statsWindowStart = 0L

    /** Latest encoded reports, for GET_REPORT requests from the host. */
    @Volatile var lastGamepadReport: ByteArray = GamepadState().encode()
        private set
    @Volatile var lastKeyboardReport: ByteArray = KeyboardState().encode()
        private set

    /**
     * The mouse feature report the host last set: which resolution multipliers are on. Until the
     * host enables one, that axis is reported in whole notches (the rest stays pending), so a
     * host that ignores the multiplier never scrolls 120 times too far.
     */
    @Volatile var mouseFeature = 0

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "hid-report-pump").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Forces a full re-send on the next tick (after a host connects). */
    fun invalidate() {
        lastGamepad = null
        lastKeyboard = null
        pendingDx = 0; pendingDy = 0; pendingWheel = 0; pendingPan = 0
        latchedDown = 0; latchedUp = 0
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val start = System.nanoTime()
            if (hid.isConnected) tick() else engine.snapshot() // drain motion while idle
            if (start - statsWindowStart >= 1_000_000_000L) {
                sentPerSecond = sentCount; failedPerSecond = failedCount
                sentCount = 0; failedCount = 0; statsWindowStart = start
            }
            val sleep = intervalNanos - (System.nanoTime() - start)
            if (sleep > 0) LockSupport.parkNanos(sleep)
        }
    }

    private fun send(reportId: Int, bytes: ByteArray): Boolean {
        val ok = hid.send(reportId, bytes)
        if (ok) sentCount++ else failedCount++
        return ok
    }

    private fun tick() {
        val s = engine.snapshot()
        pendingDx += s.mouse.dx
        pendingDy += s.mouse.dy
        pendingWheel += s.mouse.wheel
        pendingPan += s.mouse.hwheel
        // Edges that have not reached the host yet stay latched, so a skipped turn or failed send
        // cannot lose a click: a release goes out before a press that followed it (double click).
        val b = s.mouse.buttons
        latchedUp = latchedUp or (lastMouseButtons and b.inv())
        latchedDown = latchedDown or (b and (lastMouseButtons.inv() or latchedUp))
        mouseButtons = if (latchedUp != 0) lastMouseButtons and latchedUp.inv() else b or latchedDown

        val padDirty = s.gamepad != lastGamepad
        val feature = mouseFeature
        val mouseDirty = pendingDx != 0 || pendingDy != 0 || mouseButtons != lastMouseButtons ||
            wheelOut(pendingWheel, HidDescriptor.wheelHiRes(feature)) != 0 ||
            wheelOut(pendingPan, HidDescriptor.panHiRes(feature)) != 0
        when {
            s.keyboard != lastKeyboard -> {
                val bytes = s.keyboard.encode()
                if (send(HidDescriptor.REPORT_ID_KEYBOARD, bytes)) {
                    lastKeyboard = s.keyboard
                    lastKeyboardReport = bytes
                }
            }
            mouseButtons != lastMouseButtons -> sendMouse() // click edges go out on the next tick
            padDirty && !(mouseDirty && mouseTurn) -> {
                val bytes = s.gamepad.encode()
                if (send(HidDescriptor.REPORT_ID_GAMEPAD, bytes)) {
                    lastGamepad = s.gamepad
                    lastGamepadReport = bytes
                }
                mouseTurn = true
            }
            mouseDirty -> sendMouse()
        }
    }

    /** Pending 1/120-notch travel as the host reads it now: as is, or in whole notches (rest stays pending). */
    private fun wheelOut(pending: Int, hiRes: Boolean): Int =
        if (hiRes) pending.coerceIn(-MOUSE_MAX, MOUSE_MAX)
        else (pending / MouseState.WHEEL_UNIT).coerceIn(-MAX_NOTCHES, MAX_NOTCHES)

    private fun sendMouse() {
        val dx = pendingDx.coerceIn(-MOUSE_MAX, MOUSE_MAX)
        val dy = pendingDy.coerceIn(-MOUSE_MAX, MOUSE_MAX)
        val feature = mouseFeature
        val wheelHiRes = HidDescriptor.wheelHiRes(feature)
        val panHiRes = HidDescriptor.panHiRes(feature)
        val wheel = wheelOut(pendingWheel, wheelHiRes)
        val pan = wheelOut(pendingPan, panHiRes)
        val report = MouseState.report(mouseButtons, dx, dy, wheel, pan)
        if (send(HidDescriptor.REPORT_ID_MOUSE, report)) {
            pendingDx -= dx; pendingDy -= dy
            pendingWheel -= if (wheelHiRes) wheel else wheel * MouseState.WHEEL_UNIT
            pendingPan -= if (panHiRes) pan else pan * MouseState.WHEEL_UNIT
            lastMouseButtons = mouseButtons
            if (latchedUp != 0) latchedUp = 0 else latchedDown = 0
        }
        mouseTurn = false
    }

    companion object {
        /**
         * Selectable report rates. In sniff mode (AOSP HID default ~11.25 ms) the link carries
         * roughly 89 packets/s at best, so higher rates only build a queue. 60 Hz leaves headroom.
         */
        val RATES_HZ = intArrayOf(30, 45, 60, 75, 90)
        const val DEFAULT_RATE_HZ = 60
        private const val MOUSE_MAX = 32767
        private const val MAX_NOTCHES = 127
        const val DEFAULT_INTERVAL_NANOS = 1_000_000_000L / DEFAULT_RATE_HZ
    }
}
