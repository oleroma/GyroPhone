package com.oleroma.gyrophone.wifi

import android.os.Process
import android.os.SystemClock
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.net.Bye
import com.oleroma.gyrophone.core.net.ByeReason
import com.oleroma.gyrophone.core.net.LinkInfo
import com.oleroma.gyrophone.core.net.LiveSession
import com.oleroma.gyrophone.core.net.MouseTotals
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.SendScheduler
import com.oleroma.gyrophone.core.net.SessionCipher
import com.oleroma.gyrophone.core.net.StateFrame
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.net.WireCodec
import com.oleroma.gyrophone.core.net.putU64
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.locks.LockSupport

/**
 * The Wi-Fi send loop on its own thread. Event-driven: the engine's onChange unparks it, and
 * [SendScheduler] decides between a normal STATE, the +2/+6 ms repeats after a discrete change
 * and the 20 ms keepalive, capped at [rateHz]. It is the only thread that seals P->H packets,
 * BYE included (sent on its way out). The loop allocates nothing except `engine.snapshot()`
 * and the once-a-second PING / LINK_INFO.
 */
class WifiPump(private val engine: MappingEngine) {
    /** The session to send on; [byeReason] >= 0 says the previous one gets a goodbye first. */
    private class Target(val session: LiveSession?, val byeReason: Int)

    @Volatile private var target = Target(null, NO_BYE)
    @Volatile private var running = false
    @Volatile private var exitByeReason = ByeReason.SHUTDOWN
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null

    @Volatile var rateHz: Int = Wire.DEFAULT_RATE_HZ

    /** Builds LINK_INFO on the pump thread; null while there is no clock sample yet. */
    @Volatile var linkInfoSource: (() -> LinkInfo?)? = null
    @Volatile private var linkInfoDue = false

    /** STATE packets handed to / refused by the socket during the last full second. */
    @Volatile var sentPerSecond = 0
        private set
    @Volatile var failedPerSecond = 0
        private set
    private var sentCount = 0
    private var failedCount = 0
    private var statsWindowStart = 0L

    /** Set as `engine.onChange`: wakes the loop as soon as input changes. */
    val wake = Runnable { LockSupport.unpark(thread) }

    // Pump-thread state.
    private val scheduler = SendScheduler()
    private val totals = MouseTotals()
    private val frame = StateFrame()
    private val lastSent = StateFrame()
    private val header = ByteArray(Wire.HEADER)
    private val plain = ByteArray(Wire.MAX_DATAGRAM)
    private val out = ByteArray(Wire.MAX_DATAGRAM)
    private val packet = DatagramPacket(out, out.size)
    private var cur: LiveSession? = null
    private var cipher: SessionCipher? = null
    private var seq = 0
    private var nextPingNs = 0L
    private var lastLinkInfoNs = 0L

    fun start(socket: DatagramSocket) {
        if (running) return
        this.socket = socket
        running = true
        thread = Thread({ loop() }, "gp-wifi-pump").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stops the loop; a live session gets one neutral STATE and BYE([byeReason]) x3 first. */
    fun stop(byeReason: Int = ByeReason.SHUTDOWN) {
        val t = thread ?: return
        exitByeReason = byeReason
        running = false
        LockSupport.unpark(t)
        try {
            t.join(STOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        socket = null
        target = Target(null, NO_BYE)
        sentPerSecond = 0
        failedPerSecond = 0
    }

    /**
     * Switches the session the loop sends on (null = none). [byeReason] >= 0 makes the loop
     * release everything and say BYE on the previous session first (user disconnect); a rekey
     * or a session the PC already ended passes [NO_BYE].
     */
    fun setSession(session: LiveSession?, byeReason: Int = NO_BYE) {
        target = Target(session, byeReason)
        LockSupport.unpark(thread)
    }

    /** A PONG lowered the minimum round trip: send LINK_INFO with the better clock offset now. */
    fun requestLinkInfo() {
        linkInfoDue = true
        LockSupport.unpark(thread)
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val s = engine.snapshot() // always drain motion, even with no session
            totals.add(s.mouse)
            val t = target
            if (t.session !== cur) {
                val old = cur
                if (old != null && t.byeReason >= 0) goodbye(old, t.byeReason)
                switchTo(t.session)
            }
            val now = System.nanoTime()
            updateStats(now)
            val c = cur
            if (c == null) {
                LockSupport.parkNanos(IDLE_PARK_NS)
                continue
            }
            val hz = rateHz
            if (scheduler.rateHz != hz) scheduler.rateHz = hz

            frame.fill(s, totals, engine.gyroActive)
            val discrete = !frame.discreteEquals(lastSent)
            val dirty = discrete || !frame.totalsEqual(lastSent)
            val kind = scheduler.poll(now, dirty, discrete)
            if (kind != SendScheduler.NONE) {
                frame.flags = frame.flags or SendScheduler.flagsFor(kind)
                frame.phoneTimeUs = phoneUs().toInt()
                WireCodec.encodeStatePlain(frame, plain, 0)
                if (sendSealed(c, PacketType.STATE, Wire.STATE_PLAIN)) sentCount++ else failedCount++
                lastSent.copyFrom(frame)
                scheduler.markSent(now, kind, discrete)
            }
            if (now >= nextPingNs) {
                plain.putU64(0, phoneUs())
                sendSealed(c, PacketType.PING, Wire.PING_PLAIN)
                nextPingNs = now + SECOND_NS
            }
            if (linkInfoDue || now - lastLinkInfoNs >= SECOND_NS) {
                linkInfoDue = false
                lastLinkInfoNs = now
                linkInfoSource?.invoke()?.let { info ->
                    val n = WireCodec.encodeSealedPlain(info, plain, 0)
                    sendSealed(c, PacketType.LINK_INFO, n)
                }
            }
            val wakeAt = minOf(scheduler.nextDeadline(), nextPingNs, lastLinkInfoNs + SECOND_NS)
            val wait = wakeAt - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
        }
        cur?.let { goodbye(it, exitByeReason) }
        cur = null
        cipher = null
    }

    private fun switchTo(session: LiveSession?) {
        cur = session
        cipher = session?.let { SessionCipher(it.kPH, Wire.DIR_P2H) }
        seq = 0
        totals.reset()
        lastSent.clear()
        scheduler.reset()
        val now = System.nanoTime()
        nextPingNs = now
        lastLinkInfoNs = now
        linkInfoDue = false
    }

    /** One neutral STATE (totals kept), then BYE x3 at 5 ms intervals. */
    private fun goodbye(c: LiveSession, reason: Int) {
        frame.clear()
        frame.mouseTotalX = totals.x
        frame.mouseTotalY = totals.y
        frame.wheelTotal = totals.wheel
        frame.phoneTimeUs = phoneUs().toInt()
        WireCodec.encodeStatePlain(frame, plain, 0)
        sendSealed(c, PacketType.STATE, Wire.STATE_PLAIN)
        val bye = Bye(reason)
        for (i in 0 until BYE_COUNT) {
            if (i > 0) LockSupport.parkNanos(BYE_SPACING_NS)
            val n = WireCodec.encodeSealedPlain(bye, plain, 0)
            sendSealed(c, PacketType.BYE, n)
        }
    }

    /** Seals the first [plainLen] bytes of [plain] as [type] with the next seq and sends it. */
    private fun sendSealed(c: LiveSession, type: Int, plainLen: Int): Boolean {
        val ci = cipher ?: return false
        val s = socket ?: return false
        if ((seq.toLong() and 0xFFFF_FFFFL) == 0xFFFF_FFFFL) {
            // Seq space spent and the rekey never landed: stop sealing under this key rather than
            // wrap and reuse GCM nonces. Keep cur so the loop does not re-switch to it with seq 0;
            // the next session from the flow gets a fresh cipher.
            cipher = null
            return false
        }
        seq++
        if ((seq.toLong() and 0xFFFF_FFFFL) >= Wire.REKEY_SEQ) c.rekeyRequested = true
        WireCodec.writeHeader(header, type, c.sessionId, seq)
        val len = ci.seal(header, plain, plainLen, out)
        packet.setData(out, 0, len)
        packet.socketAddress = c.hostAddr
        return try {
            s.send(packet)
            true
        } catch (e: IOException) {
            false // no route while Wi-Fi changes; the next packet carries the full state anyway
        }
    }

    private fun updateStats(now: Long) {
        if (now - statsWindowStart >= SECOND_NS) {
            sentPerSecond = sentCount; failedPerSecond = failedCount
            sentCount = 0; failedCount = 0; statsWindowStart = now
        }
    }

    companion object {
        const val NO_BYE = -1
        private const val SECOND_NS = 1_000_000_000L
        private const val IDLE_PARK_NS = Wire.KEEPALIVE_MS * 1_000_000L
        private const val BYE_COUNT = 3
        private const val BYE_SPACING_NS = 5_000_000L
        private const val STOP_JOIN_MS = 500L

        /** Phone clock for STATE, PING and LINK_INFO: elapsed realtime in microseconds. */
        fun phoneUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
    }
}
