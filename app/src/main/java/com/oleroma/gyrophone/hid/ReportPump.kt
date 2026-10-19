package com.oleroma.gyrophone.hid

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.GamepadState
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.hid.KeyboardState
import java.util.concurrent.locks.LockSupport

/**
 * Dedicated thread that snapshots the mapping engine at a fixed interval and sends only what
 * changed. Mouse motion accumulates between ticks, so a fast gyro never floods the Bluetooth
 * queue; game pad and keyboard reports go out only on change.
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
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val start = System.nanoTime()
            if (hid.isConnected) tick() else engine.snapshot() // drain motion while idle
            val sleep = intervalNanos - (System.nanoTime() - start)
            if (sleep > 0) LockSupport.parkNanos(sleep)
        }
    }

    private fun tick() {
        val s = engine.snapshot()
        if (s.gamepad != lastGamepad) {
            val bytes = s.gamepad.encode()
            if (hid.send(HidDescriptor.REPORT_ID_GAMEPAD, bytes)) {
                lastGamepad = s.gamepad
                lastGamepadReport = bytes
            }
        }
        if (s.keyboard != lastKeyboard) {
            val bytes = s.keyboard.encode()
            if (hid.send(HidDescriptor.REPORT_ID_KEYBOARD, bytes)) {
                lastKeyboard = s.keyboard
                lastKeyboardReport = bytes
            }
        }
        if (s.mouse.hasMotion || s.mouse.buttons != lastMouseButtons) {
            if (hid.send(HidDescriptor.REPORT_ID_MOUSE, s.mouse.encode())) lastMouseButtons = s.mouse.buttons
        }
    }

    companion object {
        /** 2 ms (500 Hz). Bluetooth Classic HID rarely delivers more; tune on device. */
        const val DEFAULT_INTERVAL_NANOS = 2_000_000L
    }
}
