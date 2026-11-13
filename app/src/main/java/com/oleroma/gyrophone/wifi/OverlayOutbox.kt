package com.oleroma.gyrophone.wifi

import android.os.Process
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.net.OverlayFeed
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.Wire
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.LockSupport

/**
 * Builds the PC phone overlay's packets on their own thread ("gp-overlay"), so the send thread
 * never does it: OVERLAY about 60 times a second, and the layout's OVERLAY_LAYOUT pieces when it
 * changes or every [LAYOUT_REPEAT_NS]. Reading the presses ([MappingEngine.touchesInto]) takes the
 * engine's lock and packing takes a little while; here neither can hold up a STATE. The finished
 * plaintexts wait in [take] for the pump, which only seals and sends them (it stays the one sealer
 * of the link, so the seq order needs no lock). Allocation-free: the packets come from a fixed pool.
 */
internal class OverlayOutbox(
    private val engine: MappingEngine,
    private val feed: () -> OverlayFeed?,
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

    /** A new session: its PC has no layout yet, so the next round sends it whole. */
    @Volatile private var layoutAgain = false

    // Builder-thread state.
    private val pressed = HashSet<String>()
    private val latched = HashSet<String>()
    private var layoutSentVersion = -1
    private var layoutSentNs = 0L
    private var layoutBlob: ByteArray? = null
    private var layoutVersion = 0
    /** The next layout piece to queue; -1 when no round is under way. */
    private var layoutPiece = -1

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "gp-overlay").apply { start() }
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

    /** The next packet to seal (pump thread), or null; hand it back with [recycle] once sent. */
    fun take(): Packet? = ready.poll()

    fun recycle(p: Packet) {
        free.offer(p)
    }

    /** Drops what waits (pump thread, on a session switch) and sends the layout again for the new session. */
    fun clear() {
        while (true) {
            val p = ready.poll() ?: break
            free.offer(p)
        }
        layoutAgain = true
    }

    private fun loop() {
        // Below the input threads: a late overlay frame costs nothing, a late STATE does.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var next = System.nanoTime()
        while (running) {
            val ov = feed()?.takeIf { it.wanted }
            if (ov == null || !sending) {
                layoutSentVersion = -1
                layoutPiece = -1
                LockSupport.parkNanos(IDLE_PARK_NS)
                next = System.nanoTime()
                continue
            }
            val now = System.nanoTime()
            if (now >= next) {
                next = if (now - next > OVERLAY_NS) now + OVERLAY_NS else next + OVERLAY_NS
                if (build(ov, now)) wakePump.run()
            }
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    /** Queues one OVERLAY and any layout pieces due; true if something was queued. */
    private fun build(ov: OverlayFeed, now: Long): Boolean {
        if (layoutAgain) {
            layoutAgain = false
            layoutSentVersion = -1
            layoutPiece = -1
        }
        var queued = false
        // A frame still waiting means the pump is behind: the newer one replaces nothing, so skip it
        // (the motion stays in the feed, and the totals carry it in the next frame).
        if (ready.isEmpty()) {
            val p = free.poll()
            if (p != null) {
                engine.touchesInto(pressed, latched)
                p.type = PacketType.OVERLAY
                p.len = ov.encodeFrame(p.plain, 0, WifiPump.phoneUs().toInt(), pressed, latched)
                ready.offer(p)
                queued = true
            }
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
            val p = free.poll() ?: return queued
            p.type = PacketType.OVERLAY_LAYOUT
            p.len = OverlayFeed.encodeLayoutPiece(p.plain, 0, layoutVersion, blob, layoutPiece)
            ready.offer(p)
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
        /** OVERLAY about 60 times a second. */
        const val OVERLAY_NS = 16_666_667L
        const val LAYOUT_REPEAT_NS = 2_000_000_000L
        /** How often it looks again while the PC's overlay is off or there is no session. */
        const val IDLE_PARK_NS = 50_000_000L
        const val STOP_JOIN_MS = 500L
    }
}
