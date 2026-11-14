package com.oleroma.gyrophone.wifi

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.net.Bye
import com.oleroma.gyrophone.core.net.ByeReason
import com.oleroma.gyrophone.core.net.HostFlags
import com.oleroma.gyrophone.core.net.LinkInfo
import com.oleroma.gyrophone.core.net.LiveSession
import com.oleroma.gyrophone.core.net.MotionFeed
import com.oleroma.gyrophone.core.net.MouseTotals
import com.oleroma.gyrophone.core.net.OverlayFeed
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.PcGyroFeed
import com.oleroma.gyrophone.core.net.PhoneStats
import com.oleroma.gyrophone.core.net.PressStamp
import com.oleroma.gyrophone.core.net.PressTime
import com.oleroma.gyrophone.core.net.SendScheduler
import com.oleroma.gyrophone.core.net.SessionCipher
import com.oleroma.gyrophone.core.net.StateFrame
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.net.WireCodec
import com.oleroma.gyrophone.core.net.putU64
import com.oleroma.gyrophone.runtime.WorkHint
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.locks.LockSupport

/**
 * The Wi-Fi send loop on its own thread. Event-driven: the engine's onChange unparks it, and
 * [SendScheduler] decides between a normal STATE, the +2/+6 ms repeats after a discrete change
 * and the 20 ms keepalive, capped at [rateHz]. It is the only thread that seals P->H packets,
 * BYE included (sent on its way out). The loop allocates little besides `engine.snapshot()`.
 * The packets that aren't input (the PC phone overlay's, LINK_INFO, CAPTURE) are built on their own
 * thread ([SideOutbox]); this loop only seals and sends them, after its input.
 */
class WifiPump(private val engine: MappingEngine) {
    /** The session to send on; [byeReason] >= 0 says the previous one gets a goodbye first. */
    private class Target(val session: LiveSession?, val byeReason: Int)

    @Volatile private var target = Target(null, NO_BYE)
    /** The last [target] the loop has taken (its goodbye said). */
    @Volatile private var taken: Target? = null
    @Volatile private var running = false
    @Volatile private var exitByeReason = ByeReason.SHUTDOWN
    @Volatile private var socket: DatagramSocket? = null

    /** Sends a sealed packet straight down the USB cable when it goes there ([AccessoryBridge.sendDirect]). */
    @Volatile var direct: AccessoryBridge? = null
    @Volatile private var thread: Thread? = null

    @Volatile var rateHz: Int = Wire.DEFAULT_RATE_HZ

    /** Builds LINK_INFO (on the side thread, [SideOutbox]); null while there is no clock sample yet. */
    @Volatile var linkInfoSource: (() -> LinkInfo?)? = null

    /** The phone overlay's data: sent while [OverlayFeed.wanted] (the PC's overlay is on). */
    @Volatile var overlay: OverlayFeed? = null

    /** "Use PC controller for gyro ratchet": STATE_GYRO and PAD_GYRO for a PC that takes them. */
    @Volatile var pcGyro: PcGyroFeed? = null

    /** "Press a key" listens: CAPTURE on every second while it does, then off a few times ([SideOutbox]). */
    @Volatile var capturing = false
        set(v) {
            field = v
            side.wake()
        }

    /** The raw motion for the PC's DualShock 4: MOTION while [MotionFeed.wanted]. */
    @Volatile var motion: MotionFeed? = null

    /** Press and release input times for PRESS_TIME, sent while [pressTimes]. */
    val pressStamp = PressStamp()

    /** The PC's "Measure buttons" is on (TEST_MODE): a PRESS_TIME follows each STATE with a new press or release. */
    var pressTimes: Boolean
        get() = pressStamp.enabled
        set(v) { pressStamp.enabled = v }

    /** The clock offset (host us - phone us, [com.oleroma.gyrophone.core.net.ClockSync]) of this session, or null before a clock sample. */
    @Volatile var clockOffsetUs: Long? = null

    /** The PC runs a Link test (TEST_MODE): STATEs carry fake moving input at the full rate, marked LINK_TEST, instead of the engine's. */
    @Volatile var linkTest = false
    private var linkTestTick = 0

    /** STATE packets handed to / refused by the socket during the last full second. */
    @Volatile var sentPerSecond = 0
        private set
    @Volatile var failedPerSecond = 0
        private set
    private var sentCount = 0
    private var failedCount = 0
    private var statsWindowStart = 0L

    /** A Performance record runs on the PC (TEST_MODE PHONE_STATS): measure this side of the link and send PHONE_STATS each second. */
    @Volatile var phoneStats = false

    // PHONE_STATS, pump thread only, counted only while [phoneStats]: this second's sends, time in send, wake-ups and gaps.
    private var stPackets = 0
    private var stFailed = 0
    private var stSlow = 0
    private var stSendNs = 0L
    private var stSendMaxNs = 0L
    private var stWakeLateMaxNs = 0L
    private var stGapMaxNs = 0L
    private var lastStateNs = 0L
    private var lastTxPackets = -1L
    /** When the loop asked to wake (0 = it didn't park on a time). */
    private var parkedUntil = 0L

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
    /** The PC takes STATE_HIRES (1/120 notch wheel, horizontal wheel). */
    private var hires = false
    private var cipher: SessionCipher? = null
    private var seq = 0
    private var nextPingNs = 0L
    /** The packets that aren't input (overlay, LINK_INFO, CAPTURE), built on their own thread. */
    private val side = SideOutbox(engine, { overlay }, { linkInfoSource }, { capturing }, wake)
    private var gyroSnapshot = false
    private var motionSentVersion = -1
    private var nextMotionNs = 0L
    private var padGyroNs = 0L
    private var padGyroSent: IntArray? = null
    private var padGyroSentEpoch = -1
    private var padGyroSentOn = false
    private var padGyroHeldSent: List<Pair<Int, com.oleroma.gyrophone.core.engine.GyroMath.Config>>? = null

    fun start(socket: DatagramSocket) {
        if (running) return
        this.socket = socket
        running = true
        thread = Thread({ loop() }, "gp-wifi-pump").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        side.start()
    }

    /** Sends on [socket] from now on (the link moved between USB and Wi-Fi). */
    fun useSocket(socket: DatagramSocket) {
        if (running) this.socket = socket
    }

    /** Stops the loop; a live session gets one neutral STATE and BYE([byeReason]) x3 first. */
    fun stop(byeReason: Int = ByeReason.SHUTDOWN) {
        val t = thread ?: return
        exitByeReason = byeReason
        running = false
        side.stop()
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

    /**
     * Waits up to [timeoutMs] until the loop has taken the last [setSession], so its goodbye has
     * gone out on the socket in use before the caller changes or closes it.
     */
    fun awaitSession(timeoutMs: Long) {
        val t = target
        val until = System.nanoTime() + timeoutMs * 1_000_000L
        while (running && thread != null && taken !== t && System.nanoTime() < until) LockSupport.parkNanos(1_000_000L)
    }

    /** A PONG lowered the minimum round trip: send LINK_INFO with the better clock offset now. */
    fun requestLinkInfo() {
        side.requestLinkInfo()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val hint = WorkHint.forThisThread()
        while (running) {
            // Inside the rate limit nothing can go out: wait instead of taking a snapshot that
            // would use up a quick tap's edge. Motion keeps accumulating in the engine meanwhile.
            if (cur != null && target.session === cur) {
                val wait = scheduler.sendableAt() - System.nanoTime()
                if (wait > 0) {
                    if (phoneStats) parkedUntil = scheduler.sendableAt()
                    LockSupport.parkNanos(wait)
                    continue
                }
            }
            val passStart = System.nanoTime()
            if (parkedUntil != 0L) {
                // Woken early by input is fine; waking late means the thread wasn't run on time.
                val late = passStart - parkedUntil
                if (late > stWakeLateMaxNs) stWakeLateMaxNs = late
                parkedUntil = 0L
            }
            // Switch first: switchTo resets the gyro's samples, so the tail below starts from the new session's.
            val t = target
            if (t.session !== cur) {
                val old = cur
                if (old != null && t.byeReason >= 0) goodbye(old, t.byeReason)
                switchTo(t.session)
            }
            if (taken !== t) taken = t
            // always drain motion, even with no session; with the PC running the gyro, its samples go along
            val s = engine.snapshot()
            val g = pcGyro?.takeIf { it.active }
            if (g != null) g.encodeTail(frame.ext, 0, clockOffsetUs)
            gyroSnapshot = g != null
            totals.add(s.mouse)
            val now = System.nanoTime()
            updateStats(now)
            val c = cur
            val has = c != null
            if (side.sending != has) side.sending = has
            if (c == null) {
                LockSupport.parkNanos(IDLE_PARK_NS)
                continue
            }
            val hz = rateHz
            if (scheduler.rateHz != hz) scheduler.rateHz = hz

            if (linkTest) {
                // The engine's input is still drained above but not sent: the PC must never see it mid-test.
                frame.fillLinkTest(linkTestTick, hires)
            } else {
                frame.fill(s, totals, engine.gyroActive, hires)
                frame.gyroExt = hires && gyroSnapshot
            }
            val discrete = !frame.discreteEquals(lastSent)
            val dirty = discrete || !frame.totalsEqual(lastSent)
            val kind = scheduler.poll(now, dirty, discrete)
            if (kind != SendScheduler.NONE) {
                frame.flags = frame.flags or SendScheduler.flagsFor(kind)
                frame.phoneTimeUs = phoneUs().toInt()
                val stateLen = WireCodec.encodeStatePlain(frame, plain, 0)
                if (lastStateNs != 0L && now - lastStateNs > stGapMaxNs) stGapMaxNs = now - lastStateNs
                lastStateNs = now
                if (sendSealed(c, WireCodec.stateType(frame), stateLen)) sentCount++ else failedCount++
                if (discrete && pressStamp.enabled) sendPressTime(c, frame.phoneTimeUs, now)
                lastSent.copyFrom(frame)
                scheduler.markSent(now, kind, discrete)
                if (linkTest) linkTestTick++
            }
            if (now >= nextPingNs) {
                plain.putU64(0, phoneUs())
                sendSealed(c, PacketType.PING, Wire.PING_PLAIN)
                nextPingNs = now + SECOND_NS
            }
            // Each new gyro sample goes out at once; while still, every MOTION_KEEPALIVE_NS so a lost stop arrives.
            val mo = motion?.takeIf { it.wanted }
            if (mo != null && (mo.version != motionSentVersion || now >= nextMotionNs)) {
                motionSentVersion = mo.version
                nextMotionNs = now + MOTION_KEEPALIVE_NS
                sendSealed(c, PacketType.MOTION, mo.encode(plain, 0))
            }
            pcGyro?.let { if (it.hostTakes) sendPadGyro(c, it, now) }
            // The packets that aren't input, ready-made on their own thread: only sealed and sent here.
            while (true) {
                val p = side.take() ?: break
                sendSealed(c, p.type, p.plain, p.len)
                side.recycle(p)
            }
            var wakeAt = minOf(minOf(scheduler.nextDeadline(), nextPingNs), engine.touchpadDeadlineNanos())
            if (mo != null) wakeAt = minOf(wakeAt, nextMotionNs)
            val end = System.nanoTime()
            // The pass is done: how long it took goes to the performance hint.
            hint?.done(end - passStart, end)
            val wait = wakeAt - end
            if (wait > 0) {
                if (phoneStats) parkedUntil = wakeAt
                LockSupport.parkNanos(wait)
            }
        }
        hint?.close()
        cur?.let { goodbye(it, exitByeReason) }
        cur = null
        cipher = null
    }

    /** PRESS_TIME for the STATE sent at phone time [stateUs], when an input edge is waiting. */
    private fun sendPressTime(c: LiveSession, stateUs: Int, now: Long) {
        val at = pressStamp.take(now)
        if (at == 0L) return
        // The input's time is on the System.nanoTime base; STATE's on elapsed realtime.
        val eventUs = (at + SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1000
        val n = WireCodec.encodeSealedPlain(PressTime(stateUs, eventUs.toInt()), plain, 0)
        sendSealed(c, PacketType.PRESS_TIME, n)
    }

    /** PAD_GYRO when the switch, the buttons or the toggle epoch changed, else once a second (a lost one comes again). */
    private fun sendPadGyro(c: LiveSession, g: PcGyroFeed, now: Long) {
        g.buttons = engine.pcGyroButtons
        g.epoch = engine.toggleEpoch
        val b = g.buttons
        val changed = b !== padGyroSent || g.epoch != padGyroSentEpoch || g.enabled != padGyroSentOn || g.held !== padGyroHeldSent
        if (!changed && now - padGyroNs < SECOND_NS) return
        padGyroNs = now
        padGyroSent = b; padGyroSentEpoch = g.epoch; padGyroSentOn = g.enabled
        val n = g.encodeButtons(plain, 0)
        sendSealed(c, PacketType.PAD_GYRO, n)
        // Then the settings of each button that has its own while held (after PAD_GYRO, which says which have).
        val held = g.held
        for (i in held.indices) sendSealed(c, PacketType.PAD_GYRO_SET, g.encodeHeld(plain, 0, i))
        padGyroHeldSent = held
    }

    private fun switchTo(session: LiveSession?) {
        cur = session
        hires = session != null && session.hostFlags and HostFlags.HIRES_WHEEL != 0
        pcGyro?.let {
            it.hostTakes = hires && session!!.hostFlags and HostFlags.PC_GYRO != 0
            it.reset()
        }
        padGyroSent = null; padGyroSentEpoch = -1; padGyroHeldSent = null
        motion?.let {
            it.hostTakes = session != null && session.hostFlags and HostFlags.MOTION != 0
            it.reset()
        }
        motionSentVersion = -1
        cipher = session?.let { SessionCipher(it.kPH, Wire.DIR_P2H) }
        seq = 0
        lastStateNs = 0L
        totals.reset()
        lastSent.clear()
        scheduler.reset()
        val now = System.nanoTime()
        nextPingNs = now
        side.clear()
    }

    /** One neutral STATE (totals kept), then BYE x3 at 5 ms intervals. */
    private fun goodbye(c: LiveSession, reason: Int) {
        frame.clear()
        frame.hires = hires
        frame.mouseTotalX = totals.x
        frame.mouseTotalY = totals.y
        frame.wheelTotal = if (hires) totals.wheel else totals.wheelNotches
        frame.hwheelTotal = if (hires) totals.hwheel else 0
        frame.phoneTimeUs = phoneUs().toInt()
        val stateLen = WireCodec.encodeStatePlain(frame, plain, 0)
        sendSealed(c, WireCodec.stateType(frame), stateLen)
        val bye = Bye(reason)
        for (i in 0 until BYE_COUNT) {
            if (i > 0) LockSupport.parkNanos(BYE_SPACING_NS)
            val n = WireCodec.encodeSealedPlain(bye, plain, 0)
            sendSealed(c, PacketType.BYE, n)
        }
    }

    /** Seals the first [plainLen] bytes of [plain] as [type] with the next seq and sends it. */
    private fun sendSealed(c: LiveSession, type: Int, plainLen: Int): Boolean = sendSealed(c, type, plain, plainLen)

    /** Seals the first [plainLen] bytes of [src] as [type] with the next seq and sends it. */
    private fun sendSealed(c: LiveSession, type: Int, src: ByteArray, plainLen: Int): Boolean {
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
        val len = ci.seal(header, src, plainLen, out)
        if (direct?.sendDirect(c.hostAddr, out, 0, len) == true) return true
        packet.setData(out, 0, len)
        packet.socketAddress = c.hostAddr
        val measure = phoneStats
        val t0 = if (measure) System.nanoTime() else 0L
        return try {
            s.send(packet)
            if (measure) {
                val d = System.nanoTime() - t0
                stPackets++; stSendNs += d
                if (d > stSendMaxNs) stSendMaxNs = d
                if (d >= SLOW_SEND_NS) stSlow++
            }
            true
        } catch (e: IOException) {
            if (measure) stFailed++
            false // no route while Wi-Fi changes; the next packet carries the full state anyway
        }
    }

    private fun updateStats(now: Long) {
        if (now - statsWindowStart >= SECOND_NS) {
            sentPerSecond = sentCount; failedPerSecond = failedCount
            val c = cur
            if (phoneStats && c != null && statsWindowStart != 0L) sendPhoneStats(c, sentCount)
            else lastTxPackets = -1L
            sentCount = 0; failedCount = 0; statsWindowStart = now
            stPackets = 0; stFailed = 0; stSlow = 0; stSendNs = 0L; stSendMaxNs = 0L; stWakeLateMaxNs = 0L; stGapMaxNs = 0L
        }
    }

    /** PHONE_STATS for the second just ended (its numbers are cleared right after). */
    private fun sendPhoneStats(c: LiveSession, states: Int) {
        val tx = TrafficStats.getTotalTxPackets()
        val txDelta = if (lastTxPackets >= 0 && tx >= lastTxPackets) tx - lastTxPackets else 0L
        lastTxPackets = tx
        val p = PhoneStats(
            phoneUs().toInt(), sat16(states), sat16(stPackets), sat16(stFailed), sat16(stSlow),
            usOf(stSendNs), usOf(stSendMaxNs), usOf(stWakeLateMaxNs), usOf(stGapMaxNs), txDelta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        sendSealed(c, PacketType.PHONE_STATS, WireCodec.encodeSealedPlain(p, plain, 0))
    }

    private fun sat16(v: Int) = v.coerceIn(0, 0xFFFF)
    private fun usOf(ns: Long) = (ns / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val NO_BYE = -1
        private const val SECOND_NS = 1_000_000_000L
        private const val IDLE_PARK_NS = Wire.KEEPALIVE_MS * 1_000_000L
        private const val BYE_COUNT = 3
        private const val BYE_SPACING_NS = 5_000_000L
        private const val STOP_JOIN_MS = 500L
        private const val MOTION_KEEPALIVE_NS = MotionFeed.KEEPALIVE_MS * 1_000_000L
        /** A send that took this long or more counts as slow in PHONE_STATS. */
        private const val SLOW_SEND_NS = 2_000_000L

        /** Phone clock for STATE, PING and LINK_INFO: elapsed realtime in microseconds. */
        fun phoneUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
    }
}
