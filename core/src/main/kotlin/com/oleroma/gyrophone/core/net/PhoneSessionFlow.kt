package com.oleroma.gyrophone.core.net

import java.net.InetSocketAddress

/**
 * An established session. The pump owns the P->H direction (its own [SessionCipher] over [kPH]
 * and the seq counter); the rx thread opens H->P packets through [openFromHost].
 */
class LiveSession(
    val sessionId: Int,
    val hostId: ByteArray,
    val hostName: String,
    val kPH: ByteArray,
    kHP: ByteArray,
    hostAddr: InetSocketAddress,
    val outputMode: Int,
    val hostFlags: Int,
    val keepaliveMs: Int,
) {
    /** Where H->P traffic last authenticated from; P->H packets go here. */
    @Volatile var hostAddr: InetSocketAddress = hostAddr

    /** Set by the pump when its seq reaches [Wire.REKEY_SEQ]; the session flow then runs a new HELLO. */
    @Volatile var rekeyRequested = false

    private val rx = SessionCipher(kHP, Wire.DIR_H2P)
    private val plain = ByteArray(Wire.MAX_DATAGRAM)
    private var highestRxSeq = 0L

    /**
     * Validates (session id, `seq > highest`, tag) and decodes a host-to-phone sealed packet,
     * committing its seq only after the tag verifies. Returns null for anything to drop.
     */
    fun openFromHost(buf: ByteArray, len: Int): WirePacket? {
        val type = WireCodec.classify(buf, len)
        if (type < 0 || !PacketType.isSealed(type) || PacketType.isPhoneToHost(type)) return null
        if (WireCodec.headerSessionId(buf) != sessionId) return null
        val seq = buf.getU32(8)
        if (seq <= highestRxSeq) return null
        if (!rx.open(buf, len, plain)) return null
        highestRxSeq = seq
        return WireCodec.decodeSealedPlain(type, plain, 0, len - Wire.HEADER - Wire.GCM_TAG)
    }
}

enum class SessionStatus {
    IDLE,
    /** HELLO out to the last known address. */
    CONNECTING,
    /** No WELCOME yet; broadcasting DISCOVER to find the PC's address. */
    DISCOVERING,
    LIVE,
    /**
     * Live, but no PONG or HOST_STATUS for [Wire.HOST_SILENT_MS]; a new HELLO is running. After
     * [Wire.SESSION_TIMEOUT_MS] of silence the session ends and the flow goes back to CONNECTING.
     */
    HOST_SILENT,
    /** ERROR NOT_PAIRED: offer to pair again, never delete the key automatically. */
    NOT_PAIRED,
    /** ERROR VERSION: one side needs an update. */
    VERSION_MISMATCH,
    /** Another phone's session took over on the PC. */
    REPLACED,
    /** The PC app exited; reconnect attempts continue. */
    HOST_EXITED,
    /** The PC forgot this phone. */
    FORGOTTEN,
}

/**
 * Phone side of the HELLO/WELCOME handshake (wire v1 section 1.6) as a pure state machine:
 * HELLO retries at 0, 100, 200, 400, 800 ms then every second for one nP, DISCOVER broadcast
 * fallback after 500 ms without WELCOME, HOST_SILENT reconnects, advisory ERROR handling and
 * rekeying. The rx thread feeds it datagrams and ticks; it never touches sockets itself.
 */
class PhoneSessionFlow(
    private val phoneId: ByteArray,
    private val host: PairedHost,
    /** Broadcast destinations for DISCOVER (255.255.255.255 and the subnet-directed address). */
    private val broadcastTargets: () -> List<InetSocketAddress>,
    private val caps: Int = Caps.RUMBLE,
    var maxRateHz: Int = Wire.DEFAULT_RATE_HZ,
    private val random: (Int) -> ByteArray = Crypto::randomBytes,
) {
    sealed interface Out {
        class Send(val bytes: ByteArray, val addr: InetSocketAddress) : Out
        /** A new live session, or null when the session ended. */
        data class Live(val session: LiveSession?) : Out
        data class Status(val status: SessionStatus) : Out
        /** The PC's address changed, confirmed by authenticated traffic (persist it as the paired host's last address). */
        data class HostAddress(val addr: InetSocketAddress) : Out
        /** An authenticated PONG / HOST_STATUS / RUMBLE, or any ANNOUNCE, for the link to use. */
        data class Received(val packet: WirePacket, val from: InetSocketAddress) : Out
    }

    private class Attempt(val nP: ByteArray, val startMs: Long, val reqId: Int) {
        var hellosSent = 0
        var nextDiscoverMs = 0L
    }

    var live: LiveSession? = null
        private set
    var status: SessionStatus = SessionStatus.IDLE
        private set
    var hostAddr: InetSocketAddress? = host.addr
        private set

    private var running = false
    private var attempt: Attempt? = null
    private var lastHeardMs = 0L
    private var silent = false
    /** [hostAddr] came from an unauthenticated ANNOUNCE: a HELLO target, reported ([Out.HostAddress]) only once a WELCOME or sealed packet comes from it. */
    private var addrUnconfirmed = false
    /**
     * A [moveTo] attempt to a known address is running: the live session's packets, still coming the
     * old way, neither move [hostAddr] off the new link nor end the attempt. Cleared by its WELCOME.
     */
    private var moving = false
    /** When ERROR SESSION_UNKNOWN last started an attempt (-1: never). */
    private var lastUnknownMs = -1L
    private val buf = ByteArray(Wire.MAX_DATAGRAM)

    val isRunning: Boolean get() = running

    fun connect(nowMs: Long): List<Out> {
        val out = ArrayList<Out>()
        running = true
        startAttempt(nowMs, out)
        tickAttempt(nowMs, out)
        return out
    }

    /**
     * Starts a fresh HELLO attempt (new nP, retry schedule from the top) after the phone's
     * network changed, keeping any live session until the new WELCOME replaces it.
     */
    fun reconnect(nowMs: Long): List<Out> {
        if (!running) return emptyList()
        val out = ArrayList<Out>()
        startAttempt(nowMs, out)
        tickAttempt(nowMs, out)
        return out
    }

    /**
     * The phone moved to another link (USB tethering, back to Wi-Fi): a fresh HELLO attempt to
     * [addr], the PC's address on that link, or DISCOVER at once when it is null. Like [reconnect],
     * a live session stays until the new WELCOME replaces it. The address is not reported as the
     * host's to remember: the link may come and go.
     */
    fun moveTo(addr: InetSocketAddress?, nowMs: Long): List<Out> {
        if (!running) return emptyList()
        hostAddr = addr
        addrUnconfirmed = false
        val out = ArrayList<Out>()
        startAttempt(nowMs, out)
        moving = addr != null
        tickAttempt(nowMs, out)
        return out
    }

    /** Stops everything. The caller sends the neutral STATE and BYE itself after its pump stops. */
    fun disconnect(): List<Out> {
        val out = ArrayList<Out>()
        val wasLive = live != null
        running = false
        attempt = null
        moving = false
        live = null
        if (wasLive) out.add(Out.Live(null))
        setStatus(SessionStatus.IDLE, out)
        return out
    }

    fun onTick(nowMs: Long): List<Out> {
        if (!running) return emptyList()
        val out = ArrayList<Out>()
        val l = live
        if (l != null) {
            if (l.rekeyRequested && attempt == null) startAttempt(nowMs, out)
            if (!silent && nowMs - lastHeardMs >= Wire.HOST_SILENT_MS) {
                silent = true
                setStatus(SessionStatus.HOST_SILENT, out)
                if (attempt == null) startAttempt(nowMs, out)
            }
            if (silent && nowMs - lastHeardMs >= Wire.SESSION_TIMEOUT_MS) {
                // The PC is gone without a HOST_BYE (asleep, off the network): the session is down,
                // so the pump stops sending and another PC may take over. The HELLO attempt goes on.
                live = null
                silent = false
                out.add(Out.Live(null))
                setStatus(SessionStatus.CONNECTING, out)
                if (attempt == null) startAttempt(nowMs, out)
            }
        }
        tickAttempt(nowMs, out)
        return out
    }

    fun onDatagram(data: ByteArray, len: Int, from: InetSocketAddress, nowMs: Long): List<Out> {
        if (!running) return emptyList()
        val type = WireCodec.classify(data, len)
        // A newer host writes its own version in the ERROR VERSION it sends us.
        if (type < 0 && type != WireCodec.BAD_VERSION) return emptyList()
        val out = ArrayList<Out>()
        when {
            type == WireCodec.BAD_VERSION || type == PacketType.ERROR -> WireCodec.decodeError(data, len)?.let { onError(it, from, nowMs, out) }
            type == PacketType.ANNOUNCE -> (WireCodec.decodePlain(data, len) as? Announce)?.let { onAnnounce(it, from, nowMs, out) }
            type == PacketType.WELCOME -> onWelcome(data, len, from, nowMs, out)
            PacketType.isSealed(type) && !PacketType.isPhoneToHost(type) -> onSealed(data, len, from, nowMs, out)
        }
        return out
    }

    private fun onAnnounce(a: Announce, from: InetSocketAddress, nowMs: Long, out: MutableList<Out>) {
        out.add(Out.Received(a, from))
        if (!a.hostId.contentEquals(host.hostId)) return
        // Unauthenticated (the hostId is public): only a HELLO target for the attempt running now, remembered
        // once the PC answers from there. Without an attempt the live session's address follows sealed packets.
        val att = attempt ?: return
        if (moving) return
        if (from != hostAddr) {
            hostAddr = from
            addrUnconfirmed = true
        }
        sendHello(att, out) // the address may be new: try it at once, outside the retry schedule
    }

    private fun onWelcome(data: ByteArray, len: Int, from: InetSocketAddress, nowMs: Long, out: MutableList<Out>) {
        val att = attempt ?: return
        if (!Crypto.verifyTrailingMac(host.pairKey, data, len)) return
        val w = WireCodec.decodePlain(data, len) as? Welcome ?: return
        if (!Crypto.ctEquals(w.nP, att.nP) || !w.hostId.contentEquals(host.hostId) || w.sessionId == 0) return
        val keys = SessionKeys.derive(host.pairKey, w.nP, w.nH, w.sessionId)
        val session = LiveSession(
            w.sessionId, w.hostId, host.name, keys.kPH, keys.kHP, from, w.outputMode, w.hostFlags, w.keepaliveMs,
        )
        updateAddr(from, out)
        attempt = null
        moving = false
        live = session
        lastHeardMs = nowMs
        silent = false
        out.add(Out.Live(session))
        setStatus(SessionStatus.LIVE, out)
    }

    private fun onError(e: ErrorPacket, from: InetSocketAddress, nowMs: Long, out: MutableList<Out>) {
        // Unauthenticated, so advisory only: it never tears a live session down. Anyone on the network can
        // send one, so SESSION_UNKNOWN counts only from the live PC's address, while it has been quiet a
        // moment (a PC that knows the session sends PONG and HOST_STATUS every second), and at most once per
        // HOST_SILENT_MS; NOT_PAIRED and VERSION only from the address being tried, when it is known.
        val l = live
        when (e.code) {
            ErrorCode.SESSION_UNKNOWN -> if (
                l != null && attempt == null && from == l.hostAddr &&
                nowMs - lastHeardMs >= UNKNOWN_QUIET_MS &&
                (lastUnknownMs < 0 || nowMs - lastUnknownMs >= Wire.HOST_SILENT_MS)
            ) {
                lastUnknownMs = nowMs
                startAttempt(nowMs, out)
            }
            ErrorCode.NOT_PAIRED -> if (l == null && fromTried(from)) setStatus(SessionStatus.NOT_PAIRED, out)
            ErrorCode.VERSION -> if (l == null && fromTried(from)) setStatus(SessionStatus.VERSION_MISMATCH, out)
        }
        tickAttempt(nowMs, out)
    }

    private fun onSealed(data: ByteArray, len: Int, from: InetSocketAddress, nowMs: Long, out: MutableList<Out>) {
        val l = live ?: return
        val p = l.openFromHost(data, len) ?: return
        lastHeardMs = nowMs
        if (from != l.hostAddr) l.hostAddr = from
        // Moving to another link: the PC is tried there, whatever still comes the old way.
        if (!moving) updateAddr(from, out)
        if (silent) {
            silent = false
            if (attempt != null && !l.rekeyRequested && !moving) attempt = null
            setStatus(SessionStatus.LIVE, out)
        }
        if (p is HostBye) {
            live = null
            attempt = null
            moving = false
            out.add(Out.Live(null))
            when (p.reason) {
                HostByeReason.REPLACED -> { running = false; setStatus(SessionStatus.REPLACED, out) }
                HostByeReason.FORGOTTEN -> { running = false; setStatus(SessionStatus.FORGOTTEN, out) }
                else -> {
                    setStatus(SessionStatus.HOST_EXITED, out)
                    startAttempt(nowMs, out)
                }
            }
        } else {
            out.add(Out.Received(p, from))
        }
    }

    private fun startAttempt(nowMs: Long, out: MutableList<Out>) {
        moving = false
        val att = Attempt(random(Wire.NONCE_SIZE), nowMs, random(4).getI32(0))
        att.nextDiscoverMs = if (hostAddr == null) nowMs else nowMs + Wire.DISCOVERY_FALLBACK_MS
        attempt = att
        if (live == null && status != SessionStatus.HOST_EXITED) setStatus(SessionStatus.CONNECTING, out)
    }

    private fun tickAttempt(nowMs: Long, out: MutableList<Out>) {
        val att = attempt ?: return
        if (hostAddr != null) {
            if (nowMs >= att.startMs + helloOffsetMs(att.hellosSent)) {
                sendHello(att, out)
                att.hellosSent++
                // Several missed slots (a late tick) collapse into one send.
                while (nowMs >= att.startMs + helloOffsetMs(att.hellosSent)) att.hellosSent++
            }
        }
        if (nowMs >= att.nextDiscoverMs) {
            val n = WireCodec.encodeDiscover(Discover(att.reqId, phoneId), buf)
            for (target in broadcastTargets()) out.add(Out.Send(buf.copyOf(n), target))
            att.nextDiscoverMs = nowMs + Wire.DISCOVER_INTERVAL_MS
            // NOT_PAIRED, VERSION_MISMATCH and HOST_EXITED stay until a WELCOME or a new attempt.
            if (live == null && status == SessionStatus.CONNECTING) setStatus(SessionStatus.DISCOVERING, out)
        }
    }

    private fun sendHello(att: Attempt, out: MutableList<Out>) {
        val addr = hostAddr ?: return
        val n = WireCodec.encodeHello(Hello(phoneId, att.nP, caps, maxRateHz), host.pairKey, buf)
        out.add(Out.Send(buf.copyOf(n), addr))
    }

    private fun fromTried(from: InetSocketAddress): Boolean = hostAddr.let { it == null || it == from }

    /** [from] sent authenticated traffic: the PC's address, reported when new (or only announced so far). */
    private fun updateAddr(from: InetSocketAddress, out: MutableList<Out>) {
        if (from == hostAddr && !addrUnconfirmed) return
        hostAddr = from
        addrUnconfirmed = false
        out.add(Out.HostAddress(from))
    }

    private fun setStatus(s: SessionStatus, out: MutableList<Out>) {
        if (s == status) return
        status = s
        out.add(Out.Status(s))
    }

    companion object {
        /** ERROR SESSION_UNKNOWN is acted on only after this long without an authenticated packet from the PC. */
        const val UNKNOWN_QUIET_MS = 500L

        /** Offset from the attempt start of the [k]-th HELLO: 0, 100, 200, 400, 800, 1800, 2800... */
        fun helloOffsetMs(k: Int): Long {
            val r = Wire.HELLO_RETRY_MS
            return if (k < r.size) r[k].toLong() else r.last() + (k - r.size + 1).toLong() * Wire.HELLO_RETRY_LATER_MS
        }
    }
}
