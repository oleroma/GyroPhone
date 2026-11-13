package com.oleroma.gyrophone.wifi

import android.os.Process
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.net.CaptureFlags
import com.oleroma.gyrophone.core.net.CaptureListen
import com.oleroma.gyrophone.core.net.LinkInfo
import com.oleroma.gyrophone.core.net.OverlayFeed
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.net.WireCodec
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.LockSupport

/**
 * Builds the phone-to-PC packets that aren't input on their own thread ("gp-side"), so the send
 * thread ([WifiPump]) never spends time on them: the PC phone overlay's OVERLAY about 60 times a
 * second and its layout's OVERLAY_LAYOUT pieces when it changes or every [LAYOUT_REPEAT_NS];
 * LINK_INFO once a second and when asked ([requestLinkInfo]); and "Press a key"'s CAPTURE. Reading
 * the presses for the overlay takes the engine's lock, LINK_INFO reads the Wi-Fi state and
 * allocates: here none of it can hold up a STATE. The finished plaintexts wait in [take] for the
 * pump, which only seals and sends them after its input. It stays the one sealer of the link, so
 * the packet numbers need no lock. The packets come from a fixed pool (no allocation for OVERLAY).
 *
 * Kept on the pump: PING (its time stamp is the clock sync's measurement, so it is taken as it goes
 * out), PRESS_TIME (right behind its STATE), MOTION and PAD_GYRO (input for the PC's pad and gyro).
 */
internal class SideOutbox(
    private val engine: MappingEngine,
    private val overlay: () -> OverlayFeed?,
    private val linkInfo: () -> (() -> LinkInfo?)?,
    private val capturing: () -> Boolean,
    private val wakePump: Runnable,
) {
    /** One plaintext waiting to be sealed as [type]. */
    class Packet {
        var type = 0
        var len = 0
        val plain = ByteArray(Wire.MAX_DATAGRAM)
    }

    private val free = ArrayBlockingQueue<Packet>(POOL)
    private val ready = ArrayBlockingQueue<Packet>(POOL)

    init {
        repeat(POOL) { free.add(Packet()) }
    }

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    /** The pump has a session to send on; nothing is built without one. */
    @Volatile var sending = false
        set(v) {
            if (field == v) return
            field = v
            if (v) wake()
        }

    /** A new session: everything starts again (the layout sent whole, LINK_INFO a second on, CAPTURE afresh). */
    @Volatile private var fresh = true
    @Volatile private var linkInfoDue = false
    /** An OVERLAY waits in [ready] (the pump hasn't sent it yet). */
    @Volatile private var overlayWaiting = false

    // Builder-thread state.
    private val pressed = HashSet<String>()
    private val latched = HashSet<String>()
    private var nextOverlayNs = 0L
    private var layoutSentVersion = -1
    private var layoutSentNs = 0L
    private var layoutBlob: ByteArray? = null
    private var layoutVersion = 0
    /** The next layout piece to queue; -1 when no round is under way. */
    private var layoutPiece = -1
    private var lastLinkInfoNs = 0L
    private var captureSentOn = false
    private var captureNs = 0L
    private var captureOffLeft = 0

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "gp-side").apply { start() }
    }

    fun stop() {
        val t = thread ?: return
        running = false
        LockSupport.unpark(t)
        try {
            t.join(STOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        clear()
    }

    /** Something changed (the overlay or "Press a key" turned on or off): look now. */
    fun wake() {
        LockSupport.unpark(thread)
    }

    /** A PONG lowered the minimum round trip: LINK_INFO with the better clock offset now. */
    fun requestLinkInfo() {
        linkInfoDue = true
        wake()
    }

    /** The next packet to seal (pump thread), or null; hand it back with [recycle] once sent. */
    fun take(): Packet? {
        val p = ready.poll() ?: return null
        if (p.type == PacketType.OVERLAY) overlayWaiting = false
        return p
    }

    fun recycle(p: Packet) {
        free.offer(p)
    }

    /** Drops what waits (pump thread, on a session switch): the new session starts afresh. */
    fun clear() {
        while (true) {
            val p = ready.poll() ?: break
            free.offer(p)
        }
        overlayWaiting = false
        fresh = true
        wake()
    }

    private fun loop() {
        // Below the input threads: a late side packet costs nothing, a late STATE does.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            if (!sending) {
                LockSupport.parkNanos(IDLE_PARK_NS)
                continue
            }
            val now = System.nanoTime()
            if (fresh) {
                fresh = false
                nextOverlayNs = now
                layoutSentVersion = -1
                layoutPiece = -1
                lastLinkInfoNs = now
                linkInfoDue = false
                captureSentOn = false
                captureOffLeft = 0
            }
            var queued = false
            if (linkInfoDue || now - lastLinkInfoNs >= SECOND_NS) {
                linkInfoDue = false
                lastLinkInfoNs = now
                val info = linkInfo()?.invoke()
                if (info != null && queue({ p -> WireCodec.encodeSealedPlain(info, p.plain, 0) }, PacketType.LINK_INFO)) queued = true
            }
            if (capture(now)) queued = true
            var wakeAt = minOf(lastLinkInfoNs + SECOND_NS, captureDeadline())
            val ov = overlay()?.takeIf { it.wanted }
            if (ov == null) {
                layoutSentVersion = -1
                layoutPiece = -1
            } else {
                if (now >= nextOverlayNs) {
                    nextOverlayNs = if (now - nextOverlayNs > OVERLAY_NS) now + OVERLAY_NS else nextOverlayNs + OVERLAY_NS
                    if (buildOverlay(ov, now)) queued = true
                }
                wakeAt = minOf(wakeAt, nextOverlayNs)
            }
            if (queued) wakePump.run()
            // With the overlay off it still looks every IDLE_PARK_NS, so turning it on starts within that.
            wakeAt = minOf(wakeAt, now + IDLE_PARK_NS)
            val wait = wakeAt - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    /** Queues one packet of [type] that [fill] writes (returning its length); false when the pool is spent. */
    private inline fun queue(fill: (Packet) -> Int, type: Int): Boolean {
        val p = free.poll() ?: return false
        p.type = type
        p.len = fill(p)
        ready.offer(p)
        return true
    }

    /** CAPTURE on at once and every second while capturing; once it stops, off three times, [CAPTURE_OFF_GAP_NS] apart. */
    private fun capture(now: Long): Boolean {
        val on = capturing()
        when {
            on -> {
                if (captureSentOn && now - captureNs < CAPTURE_RESEND_NS) return false
                captureSentOn = true
                captureOffLeft = 3
            }
            captureOffLeft > 0 -> {
                if (!captureSentOn && now - captureNs < CAPTURE_OFF_GAP_NS) return false
                captureSentOn = false
                captureOffLeft--
            }
            else -> return false
        }
        captureNs = now
        val plainOf = if (on) CAPTURE_ON else CAPTURE_OFF
        return queue({ p -> WireCodec.encodeSealedPlain(plainOf, p.plain, 0) }, PacketType.CAPTURE)
    }

    private fun captureDeadline(): Long = when {
        capturing() -> captureNs + CAPTURE_RESEND_NS
        captureOffLeft > 0 -> captureNs + CAPTURE_OFF_GAP_NS
        else -> Long.MAX_VALUE
    }

    /** Queues one OVERLAY and any layout pieces due; true if something was queued. */
    private fun buildOverlay(ov: OverlayFeed, now: Long): Boolean {
        var queued = false
        // A frame still waiting means the pump is behind (busy with input): skip this one. The motion
        // stays in the feed and the next frame's running totals carry it.
        if (!overlayWaiting) {
            queued = queue({ p ->
                // Set before it is queued: the pump may take it at once.
                overlayWaiting = true
                engine.touchesInto(pressed, latched)
                ov.encodeFrame(p.plain, 0, WifiPump.phoneUs().toInt(), pressed, latched)
            }, PacketType.OVERLAY)
        }
        if (layoutPiece < 0) {
            val v = ov.layoutVersion()
            if (v < 0 || v == layoutSentVersion && now - layoutSentNs < LAYOUT_REPEAT_NS) return queued
            val (version, blob) = ov.layoutSnapshot() ?: return queued
            layoutVersion = version
            layoutBlob = blob
            layoutPiece = 0
        }
        val blob = layoutBlob ?: return queued
        val count = OverlayFeed.pieceCount(blob)
        // A long layout goes over several rounds when the pool runs short; a lost piece comes again with the repeat.
        while (layoutPiece < count) {
            val piece = layoutPiece
            if (!queue({ p -> OverlayFeed.encodeLayoutPiece(p.plain, 0, layoutVersion, blob, piece) }, PacketType.OVERLAY_LAYOUT)) return queued
            queued = true
            layoutPiece++
        }
        layoutSentVersion = layoutVersion
        layoutSentNs = now
        layoutPiece = -1
        layoutBlob = null
        return queued
    }

    private companion object {
        const val POOL = 16
        const val SECOND_NS = 1_000_000_000L
        /** OVERLAY about 60 times a second. */
        const val OVERLAY_NS = 16_666_667L
        const val LAYOUT_REPEAT_NS = 2 * SECOND_NS
        const val CAPTURE_RESEND_NS = Wire.CAPTURE_RESEND_MS * 1_000_000L
        /** The gap between the CAPTUREs that say "off" (three, so one lost doesn't keep the PC listening for 3 s). */
        const val CAPTURE_OFF_GAP_NS = 100_000_000L
        /** How often it looks again with nothing due (no session, or the overlay off). */
        const val IDLE_PARK_NS = 50_000_000L
        const val STOP_JOIN_MS = 500L
        val CAPTURE_ON = CaptureListen(CaptureFlags.ON)
        val CAPTURE_OFF = CaptureListen(0)
    }
}
