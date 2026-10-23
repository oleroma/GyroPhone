package com.oleroma.gyrophone.core.hid

/**
 * Picks the ONE Bluetooth HID report each pump tick sends (the link allows about one packet per
 * sniff interval). Keyboard changes go first, then mouse button edges, then game pad button / hat
 * edges, then analog game pad changes and mouse motion take turns. Mouse motion that is not sent
 * yet keeps accumulating, so nothing is lost or queued, and mouse and game pad button edges stay
 * latched until a report carries them. When nothing changed for [REFRESH_NANOS]
 * the current game pad (or held keyboard) state is sent again, which repairs a report the stack
 * dropped after accepting it and keeps the link out of its idle sniff profile.
 *
 * Owned by one pump thread; only the two `last*Report` fields may be read from other threads.
 */
class ReportScheduler(private val sink: Sink) {
    fun interface Sink {
        /** Hands one input report to the stack; false if it was not accepted. */
        fun send(reportId: Int, report: ByteArray): Boolean
    }

    private var lastGamepad: GamepadState? = null
    private var lastKeyboard: KeyboardState? = null
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
    /** Game pad buttons pressed / released in a snapshot but not yet reported, latched like [latchedDown]. */
    private var padDown = 0
    private var padUp = 0
    /** The keyboard report changed and has not been refreshed since, so a dropped release heals once. */
    private var keyboardRefreshDue = false
    private var refreshKeyboardNext = true

    /** When a report was last handed to the sink (accepted or not); [NEVER] before the first. */
    var lastSendNanos = NEVER
        private set

    /** Latest encoded reports, for GET_REPORT requests from the host. */
    @Volatile var lastGamepadReport: ByteArray = GamepadState().encode()
        private set
    @Volatile var lastKeyboardReport: ByteArray = KeyboardState().encode()
        private set

    /** Forgets what the host has: the next ticks re-send full state (a host connected). */
    fun reset() {
        lastGamepad = null
        lastKeyboard = null
        lastMouseButtons = 0
        pendingDx = 0; pendingDy = 0; pendingWheel = 0; pendingPan = 0
        latchedDown = 0; latchedUp = 0
        padDown = 0; padUp = 0
        mouseTurn = false
    }

    /**
     * Folds snapshot [s] in and sends at most one report. [mouseFeature] is the host's resolution
     * multiplier feature report. Returns whether a report was handed to the sink.
     */
    fun tick(s: HidState, nowNanos: Long, mouseFeature: Int): Boolean {
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

        // Pad buttons latch the same way, so a tap shown only in a tick that went to the keyboard
        // or a click still reaches the host.
        val pad = lastGamepad
        val sentButtons = pad?.buttons ?: 0
        val raw = s.gamepad.buttons
        padUp = padUp or (sentButtons and raw.inv())
        padDown = padDown or (raw and (sentButtons.inv() or padUp))
        val buttons = if (padUp != 0) sentButtons and padUp.inv() else raw or padDown
        val g = if (buttons == raw) s.gamepad else s.gamepad.copy(buttons = buttons)
        val padDirty = g != pad
        // Button and hat edges do not wait for the pad's turn: a quick tap could end before it.
        val padEdge = pad == null || g.buttons != pad.buttons || g.hat != pad.hat
        val mouseDirty = pendingDx != 0 || pendingDy != 0 || mouseButtons != lastMouseButtons ||
            wheelOut(pendingWheel, HidDescriptor.wheelHiRes(mouseFeature)) != 0 ||
            wheelOut(pendingPan, HidDescriptor.panHiRes(mouseFeature)) != 0
        when {
            s.keyboard != lastKeyboard -> {
                if (sendKeyboard(s.keyboard, nowNanos)) {
                    keyboardRefreshDue = lastKeyboard != null // not the first report to this host
                    lastKeyboard = s.keyboard
                }
            }
            mouseButtons != lastMouseButtons -> sendMouse(mouseFeature, nowNanos) // click edges go out on the next tick
            padDirty && (padEdge || !(mouseDirty && mouseTurn)) -> {
                sendGamepad(g, nowNanos)
                mouseTurn = true
            }
            mouseDirty -> sendMouse(mouseFeature, nowNanos)
            nowNanos - lastSendNanos >= REFRESH_NANOS -> refresh(s, g, nowNanos)
            else -> return false
        }
        return true
    }

    /**
     * Re-sends absolute state, never a mouse report (it is relative): the keyboard while a key is
     * held (and once after its last change, so a dropped release heals), taking turns with the pad.
     */
    private fun refresh(s: HidState, g: GamepadState, nowNanos: Long) {
        val k = s.keyboard
        val keyboardWants = keyboardRefreshDue || k.modifiers != 0 || k.keys.isNotEmpty()
        if (keyboardWants && refreshKeyboardNext) {
            if (sendKeyboard(k, nowNanos)) keyboardRefreshDue = false
            refreshKeyboardNext = false
        } else {
            sendGamepad(g, nowNanos)
            refreshKeyboardNext = true
        }
    }

    private fun sendKeyboard(k: KeyboardState, nowNanos: Long): Boolean {
        val bytes = k.encode()
        lastSendNanos = nowNanos
        if (!sink.send(HidDescriptor.REPORT_ID_KEYBOARD, bytes)) return false
        lastKeyboardReport = bytes
        return true
    }

    private fun sendGamepad(g: GamepadState, nowNanos: Long) {
        val bytes = g.encode()
        lastSendNanos = nowNanos
        if (sink.send(HidDescriptor.REPORT_ID_GAMEPAD, bytes)) {
            lastGamepad = g
            lastGamepadReport = bytes
            if (padUp != 0) padUp = 0 else padDown = 0
        }
    }

    /** Pending 1/120-notch travel as the host reads it now: as is, or in whole notches (rest stays pending). */
    private fun wheelOut(pending: Int, hiRes: Boolean): Int =
        if (hiRes) pending.coerceIn(-MOUSE_MAX, MOUSE_MAX)
        else (pending / MouseState.WHEEL_UNIT).coerceIn(-MAX_NOTCHES, MAX_NOTCHES)

    private fun sendMouse(feature: Int, nowNanos: Long) {
        val dx = pendingDx.coerceIn(-MOUSE_MAX, MOUSE_MAX)
        val dy = pendingDy.coerceIn(-MOUSE_MAX, MOUSE_MAX)
        val wheelHiRes = HidDescriptor.wheelHiRes(feature)
        val panHiRes = HidDescriptor.panHiRes(feature)
        val wheel = wheelOut(pendingWheel, wheelHiRes)
        val pan = wheelOut(pendingPan, panHiRes)
        val report = MouseState.report(mouseButtons, dx, dy, wheel, pan)
        lastSendNanos = nowNanos
        if (sink.send(HidDescriptor.REPORT_ID_MOUSE, report)) {
            pendingDx -= dx; pendingDy -= dy
            pendingWheel -= if (wheelHiRes) wheel else wheel * MouseState.WHEEL_UNIT
            pendingPan -= if (panHiRes) pan else pan * MouseState.WHEEL_UNIT
            lastMouseButtons = mouseButtons
            if (latchedUp != 0) latchedUp = 0 else latchedDown = 0
        }
        mouseTurn = false
    }

    companion object {
        /** Idle time after which the current state is sent again; well below the stack's 5 s idle timer. */
        const val REFRESH_NANOS = 1_000_000_000L
        /** [lastSendNanos] before any report; far enough from overflow for interval arithmetic. */
        const val NEVER = Long.MIN_VALUE / 4
        private const val MOUSE_MAX = 32767
        private const val MAX_NOTCHES = 127

        /**
         * When a pump that sent nothing on its last tick may tick again once input arrives: at
         * once if the last report is at least one interval old, else when it is, and never later
         * than its periodic tick at [tickAt]. Two reports are never closer than [intervalNanos].
         */
        fun inputTickAt(tickAt: Long, lastSendNanos: Long, intervalNanos: Long): Long =
            minOf(tickAt, lastSendNanos + intervalNanos)
    }
}
