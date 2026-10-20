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
 * each tick sends at most ONE report: keyboard changes first, then game pad and mouse take
 * turns. Mouse motion that is not sent yet keeps accumulating, so nothing is lost or queued.
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
    private var pendingWheel = 0
    private var mouseButtons = 0
    private var mouseTurn = false

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
        pendingDx = 0; pendingDy = 0; pendingWheel = 0
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
        mouseButtons = s.mouse.buttons

        val padDirty = s.gamepad != lastGamepad
        val mouseDirty = pendingDx != 0 || pendingDy != 0 || pendingWheel != 0 || mouseButtons != lastMouseButtons
        when {
            s.keyboard != lastKeyboard -> {
                val bytes = s.keyboard.encode()
                if (send(HidDescriptor.REPORT_ID_KEYBOARD, bytes)) {
                    lastKeyboard = s.keyboard
                    lastKeyboardReport = bytes
                }
            }
            padDirty && !(mouseDirty && mouseTurn) -> {
                val bytes = s.gamepad.encode()
                if (send(HidDescriptor.REPORT_ID_GAMEPAD, bytes)) {
                    lastGamepad = s.gamepad
                    lastGamepadReport = bytes
                }
                mouseTurn = true
            }
            mouseDirty -> {
                val dx = pendingDx.coerceIn(-MOUSE_MAX, MOUSE_MAX)
                val dy = pendingDy.coerceIn(-MOUSE_MAX, MOUSE_MAX)
                val wheel = pendingWheel.coerceIn(-127, 127)
                val report = MouseState(mouseButtons, dx, dy, wheel).encode()
                if (send(HidDescriptor.REPORT_ID_MOUSE, report)) {
                    pendingDx -= dx; pendingDy -= dy; pendingWheel -= wheel
                    lastMouseButtons = mouseButtons
                }
                mouseTurn = false
            }
        }
    }

    companion object {
        /**
         * Selectable report rates. In sniff mode (AOSP HID default ~11.25 ms) the link carries
         * roughly 89 packets/s at best, so higher rates only build a queue. 60 Hz leaves headroom.
         */
        val RATES_HZ = intArrayOf(30, 45, 60, 75, 90)
        const val DEFAULT_RATE_HZ = 60
        private const val MOUSE_MAX = 32767
        const val DEFAULT_INTERVAL_NANOS = 1_000_000_000L / DEFAULT_RATE_HZ
    }
}
