package com.oleroma.gyrophone.wifi

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.net.Announce
import com.oleroma.gyrophone.core.net.AnnounceFlags
import com.oleroma.gyrophone.core.net.ByeReason
import com.oleroma.gyrophone.core.net.Caps
import com.oleroma.gyrophone.core.net.ClockSync
import com.oleroma.gyrophone.core.net.Crypto
import com.oleroma.gyrophone.core.net.Discover
import com.oleroma.gyrophone.core.net.EnrollResult
import com.oleroma.gyrophone.core.net.HostFlags
import com.oleroma.gyrophone.core.net.HostStatus
import com.oleroma.gyrophone.core.net.HostStatusFlags
import com.oleroma.gyrophone.core.net.KeyPairing
import com.oleroma.gyrophone.core.net.KeyStatus
import com.oleroma.gyrophone.core.net.KeyTakeover
import com.oleroma.gyrophone.core.net.LinkInfo
import com.oleroma.gyrophone.core.net.LinkInfoFlags
import com.oleroma.gyrophone.core.net.LiveSession
import com.oleroma.gyrophone.core.net.PacketType
import com.oleroma.gyrophone.core.net.PairFailure
import com.oleroma.gyrophone.core.net.PairState
import com.oleroma.gyrophone.core.net.PairedHost
import com.oleroma.gyrophone.core.net.PairingMath
import com.oleroma.gyrophone.core.net.PhonePairingFlow
import com.oleroma.gyrophone.core.net.PhoneSessionFlow
import com.oleroma.gyrophone.core.net.Pong
import com.oleroma.gyrophone.core.net.PubKeyFile
import com.oleroma.gyrophone.core.net.QrEnrollFlow
import com.oleroma.gyrophone.core.net.QrInvite
import com.oleroma.gyrophone.core.net.QrPairing
import com.oleroma.gyrophone.core.net.SessionStatus
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.core.net.WireCodec
import com.oleroma.gyrophone.core.net.getI32
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.link.LinkStats
import com.oleroma.gyrophone.link.OutputLink
import com.oleroma.gyrophone.link.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/** A PC that answered DISCOVER within the last [Wire.HOST_EXPIRY_MS]. */
data class DiscoveredHost(
    val hostIdHex: String,
    val name: String,
    val address: String,
    val port: Int,
    val flags: Int,
    /** This phone holds a pairing key for it. */
    val paired: Boolean,
    /** Key file pairing with it, from this phone's key DISCOVERs. */
    val keyStatus: KeyStatus = KeyStatus.NONE,
) {
    val pairingOpen: Boolean get() = flags and AnnounceFlags.PAIRING_OPEN != 0
    val vigemOk: Boolean get() = flags and AnnounceFlags.VIGEM_OK != 0
    val busy: Boolean get() = flags and AnnounceFlags.BUSY != 0
}

/** QR code pairing with [hostName]: what to show, and whether it is over (and worked). */
data class QrPairView(val hostName: String, val message: String, val finished: Boolean = false, val ok: Boolean = false)

/** What the pairing dialog shows: the PC's name and where the attempt is. */
data class PairingView(val hostName: String, val state: PairState)

/**
 * Wi-Fi transport to the GyroPhone companion on a Windows PC (UDP, wire protocol v1).
 *
 * One unconnected socket carries everything. The `gp-wifi-rx` thread owns all protocol state:
 * it receives with a 50 ms timeout, drives [PhonePairingFlow] and [PhoneSessionFlow], runs
 * discovery and executes UI commands from a queue, so no flow is touched from two threads.
 * [WifiPump] sends input on the live session.
 */
class WifiLink(context: Context, private val engine: MappingEngine) : OutputLink {
    override val kind = TransportKind.WIFI

    private val appContext = context.applicationContext
    val store = WifiPairingStore(appContext)
    /** Key file pairing: this phone's key and the PC keys it trusts. */
    val keys = PhoneKeys(appContext, ::phoneName)
    private val locks = WifiLocks(appContext)
    private val binder = WifiNetworkBinder(appContext) { post { onNetworkChanged() } }
    private val pump = WifiPump(engine)

    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    override val state: StateFlow<LinkState> = _state

    private val _found = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    /** PCs on this network, refreshed by DISCOVER every second while not connected. */
    val found: StateFlow<List<DiscoveredHost>> = _found

    private val _nothingFound = MutableStateFlow(false)
    /** True after 3 s of discovery without a single ANNOUNCE. */
    val nothingFound: StateFlow<Boolean> = _nothingFound

    private val _pairing = MutableStateFlow<PairingView?>(null)
    /** The pairing attempt for the dialog; null when there is none. Cleared by [dismissPair]. */
    val pairing: StateFlow<PairingView?> = _pairing

    private val _notice = MutableStateFlow<String?>(null)
    /** A one-off problem with the last UI action (bad address, unreadable key), or null. */
    val notice: StateFlow<String?> = _notice

    private val _currentHostId = MutableStateFlow<String?>(null)
    /** The paired PC the session runs for (connecting or connected), or null. */
    val currentHostId: StateFlow<String?> = _currentHostId

    /** Paired PCs, most recently used first. */
    val paired: StateFlow<List<StoredHost>> get() = store.hosts

    private val prefs = appContext.getSharedPreferences("gyrophone_wifi", Context.MODE_PRIVATE)
    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    /** Connect by key without a tap while GyroPhone is on screen (never from the background). */
    val autoConnect: StateFlow<Boolean> = _autoConnect

    fun setAutoConnect(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, on).apply()
        _autoConnect.value = on
    }

    private val _qrPairing = MutableStateFlow<QrPairView?>(null)
    /** The last QR code pairing, or null. Cleared by [dismissQr]. */
    val qrPairing: StateFlow<QrPairView?> = _qrPairing

    private val _pairedByKey = MutableStateFlow(false)
    /** The current session uses key file pairing. */
    val pairedByKey: StateFlow<Boolean> = _pairedByKey

    /** Max STATE rate (250, 500 or 1000 Hz). */
    var rateHz: Int = Wire.DEFAULT_RATE_HZ
        set(value) {
            field = value
            pump.rateHz = value
            post { session?.maxRateHz = value }
        }

    @Volatile private var running = false
    /** The socket in use: [wifiSocket], or [usbSocket] while the PC is reached over USB. */
    @Volatile private var socket: DatagramSocket? = null
    private var wifiSocket: DatagramSocket? = null

    // USB tethering (rx thread): the tether found, a non-blocking probe that looks for the PC on
    // it while the session runs over Wi-Fi, the PC's address there, and the socket once it is in use.
    private var usb: UsbTetherInfo? = null
    private var usbProbe: DatagramChannel? = null
    private val usbProbeBuf: ByteBuffer = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
    private var usbHostId: String? = null
    private var usbHostAddr: InetSocketAddress? = null
    private var usbSocket: DatagramSocket? = null
    @Volatile private var usbActive = false
    private var nextUsbCheckMs = 0L
    private var nextUsbProbeMs = 0L
    private var usbSilentSinceMs = 0L
    /** USB failed (the PC went silent there): Wi-Fi until then. */
    private var usbBackoffUntilMs = 0L
    private var rxThread: Thread? = null
    private val commands = ConcurrentLinkedQueue<Runnable>()

    // Rx-thread state.
    private var session: PhoneSessionFlow? = null
    private var sessionHostId: String? = null
    private var sessionHostName = ""
    private var live: LiveSession? = null
    private var pairFlow: PhonePairingFlow? = null
    private val clock = ClockSync()
    private val foundSeen = HashMap<String, Long>()
    private val foundMap = LinkedHashMap<String, DiscoveredHost>()
    private val manualTargets = LinkedHashSet<InetSocketAddress>()
    private var nextScanMs = 0L
    private var scanStartMs = 0L
    private var lastAnnounceMs = 0L
    private var nextRefreshMs = 0L
    private val txBuf = ByteArray(Wire.MAX_DATAGRAM)
    /** DISCOVER reqId -> hex key id of the trusted PC key it named ("" for none), this round and the last. */
    private var keyReqs = HashMap<Int, String>()
    private var lastKeyReqs = HashMap<Int, String>()
    /** Host id -> (trusted key id or "" -> status) from the latest key ANNOUNCEs. */
    private val keyAnswers = HashMap<String, HashMap<String, KeyStatus>>()
    /** Host ids the user disconnected from while this link runs: no key auto-connect to them. */
    private val keyUserStopped = HashSet<String>()
    private var sessionKeyId: String? = null
    /** How long the current session flow has been down, so a stuck one can give way to a key session. */
    private val takeover = KeyTakeover()
    private var qrFlow: QrEnrollFlow? = null
    /** The PC key a QR code just added: connect to that PC as soon as it answers, auto-connect or not. */
    private var qrConnectKeyId: String? = null
    private var qrConnectName = ""
    private var qrConnectUntilMs = 0L
    private var nextKeyScanMs = 0L

    // Read by the pump (LINK_INFO) and the UI (stats).
    @Volatile private var clockOffsetUs = 0L
    @Volatile private var rttMinUs = -1L
    @Volatile private var hostStatus: HostStatus? = null
    @Volatile private var welcomeHostFlags: Int? = null

    init {
        pump.rateHz = rateHz
        pump.linkInfoSource = ::buildLinkInfo
        // The Keystore key and phone.pub exist from the first start, so update.cmd can fetch it.
        Thread({ keys.publish() }, "gp-keys").start()
    }

    override fun start() {
        if (running) return
        val s = try {
            DatagramSocket(0).apply {
                broadcast = true
                trafficClass = Wire.DSCP_TRAFFIC_CLASS
                sendBufferSize = SEND_BUFFER
                soTimeout = RX_TIMEOUT_MS
            }
        } catch (e: SocketException) {
            Log.e(TAG, "no UDP socket", e)
            _state.value = LinkState.Failed("Could not open a network socket")
            return
        }
        socket = s
        wifiSocket = s
        running = true
        commands.clear()
        binder.register(s)
        pump.start(s)
        engine.onChange = pump.wake
        keyUserStopped.clear()
        keys.startWatching { post { onTrustedChanged(keys.rescan()) } }
        _state.value = LinkState.Searching
        post { autoConnect() }
        rxThread = Thread({ rxLoop() }, "gp-wifi-rx").apply { start() }
    }

    override fun stop() = stop(ByeReason.SHUTDOWN)

    /** Stops the link; a live session first gets a neutral STATE and BYE([byeReason]). */
    fun stop(byeReason: Int) {
        if (!running) return
        running = false
        engine.onChange = null
        keys.stopWatching()
        rxThread?.let { t ->
            try {
                t.join(RX_JOIN_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        rxThread = null
        // The rx thread is gone: its state is ours now. The pump says goodbye on the open socket.
        pump.stop(byeReason)
        session?.disconnect()
        session = null
        sessionHostId = null
        sessionKeyId = null
        _pairedByKey.value = false
        _currentHostId.value = null
        live = null
        pairFlow?.cancel()
        pairFlow = null
        _pairing.value = null
        qrFlow?.cancel()
        qrFlow = null
        qrConnectKeyId = null
        _qrPairing.value = null
        commands.clear()
        binder.unregister()
        closeUsb()
        usb = null
        wifiSocket?.close()
        wifiSocket = null
        socket = null
        locks.release()
        foundMap.clear()
        foundSeen.clear()
        keyAnswers.clear()
        _found.value = emptyList()
        _nothingFound.value = false
        _notice.value = null
        hostStatus = null
        welcomeHostFlags = null
        _state.value = LinkState.Off
    }

    override fun stats(): LinkStats {
        val info = if (usbActive) WifiRadioInfo() else binder.info
        val hs = hostStatus
        val rtt = rttMinUs
        return LinkStats(
            sentPerSecond = pump.sentPerSecond,
            failedPerSecond = pump.failedPerSecond,
            hostRxPerSecond = hs?.rxPerSec,
            lossPercent = hs?.let { it.lossPermille / 10f },
            latP50Ms = hs?.latP50Us?.takeIf { it != UNKNOWN_LATENCY }?.let { (it.toLong() and 0xFFFF_FFFFL) / 1000f },
            latP99Ms = hs?.latP99Us?.takeIf { it != UNKNOWN_LATENCY }?.let { (it.toLong() and 0xFFFF_FFFFL) / 1000f },
            rttMs = rtt.takeIf { it >= 0 }?.let { it / 1000f },
            band = info.band,
            rssi = info.rssiDbm,
            linkMbps = info.linkMbps,
            lowLatencyLock = locks.held,
            usb = usbActive,
            vigemOk = hs?.let { it.flags and HostStatusFlags.VIGEM_OK != 0 }
                ?: welcomeHostFlags?.let { it and HostFlags.VIGEM_OK != 0 },
        )
    }

    // ---- UI commands (run on the rx thread) ----

    /** Connects to a paired PC (HELLO to its last address, DISCOVER fallback). */
    fun connect(hostIdHex: String) = post { connectTo(hostIdHex) }

    /** Ends the current session (neutral STATE + BYE) and goes back to discovery. */
    fun disconnect() = post {
        sessionHostId?.let { keyUserStopped.add(it) }
        endSession(ByeReason.USER)
    }

    /** Connects to a PC by key file pairing (both sides know each other's key). */
    fun connectByKey(hostIdHex: String) = post {
        keyUserStopped.remove(hostIdHex)
        connectKeyTo(hostIdHex)
    }

    /** Adds a PC key (host.pub text) to trusted_hosts; returns an error message or null. */
    fun importHostKey(text: String): String? = keys.import(text).also { if (it == null) post { onTrustedChanged(true) } }

    /** Removes a trusted PC key; a session on it ends. */
    fun removeHostKey(host: TrustedHost) {
        keys.remove(host)
        post { onTrustedChanged(true) }
    }

    /**
     * A scanned QR code: trusts the PC in it and sends it this phone's key, then connects.
     * Returns an error message, or null when pairing started (see [qrPairing]).
     */
    fun scanQr(text: String): String? {
        val invite = QrPairing.parse(text) ?: return "That is not a GyroPhone pairing code. On the PC choose Pair new phone."
        if (!running) return "Turn the Wi-Fi link on first"
        post { startQr(invite) }
        return null
    }

    /** Closes the QR pairing message once it is over. */
    fun dismissQr() {
        if (_qrPairing.value?.finished == true) _qrPairing.value = null
    }

    /** Starts pairing with a PC whose pairing window is open. */
    fun pair(host: DiscoveredHost) = post { startPairing(host) }

    /** The user compared the codes and tapped Confirm. */
    fun confirmPair() = post { pairFlow?.let { handlePairOuts(it.userConfirmed(SystemClock.elapsedRealtime())) } }

    fun cancelPair() = post { pairFlow?.let { handlePairOuts(it.cancel()) } }

    /** Closes the pairing dialog after the attempt finished. */
    fun dismissPair() {
        val p = _pairing.value ?: return
        if (p.state.isFinished()) _pairing.value = null
    }

    /**
     * Looks for the PC at [ip] with unicast DISCOVER (for networks that drop broadcasts); the
     * address keeps being probed with every discovery round until the link stops.
     */
    fun connectByIp(ip: String) {
        val text = ip.trim()
        if (text.isEmpty() || !running) return
        Thread({
            val addr = runCatching { InetAddress.getByName(text) }.getOrNull()
            if (addr == null) {
                post { _notice.value = "Unknown address \"$text\"" }
            } else {
                post {
                    _notice.value = null
                    manualTargets.add(InetSocketAddress(addr, Wire.PORT))
                    nextScanMs = 0L // probe now
                }
            }
        }, "gp-wifi-resolve").start()
    }

    /** Forgets a paired PC here (the PC keeps its own entry until removed there). */
    fun forget(hostIdHex: String) = post {
        if (sessionHostId == hostIdHex) endSession(ByeReason.USER)
        store.forget(hostIdHex)
        foundMap[hostIdHex]?.let { foundMap[hostIdHex] = it.copy(paired = false) }
        publishFound()
    }

    private fun post(r: Runnable) {
        if (running) commands.add(r)
    }

    // ---- rx thread ----

    private fun rxLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            while (true) {
                val r = commands.poll() ?: break
                try {
                    r.run()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "command failed", e)
                }
            }
            val s = socket ?: break
            pollUsbProbe()
            try {
                packet.setLength(buf.size)
                s.receive(packet)
                val from = packet.socketAddress as? InetSocketAddress
                if (from != null) onDatagram(buf, packet.length, from, SystemClock.elapsedRealtime())
            } catch (e: SocketTimeoutException) {
                // tick below
            } catch (e: IOException) {
                if (!running) break
                Log.w(TAG, "receive failed", e)
                SystemClock.sleep(RX_TIMEOUT_MS.toLong())
            } catch (e: RuntimeException) {
                Log.e(TAG, "bad datagram", e) // one bad packet never kills the loop
            }
            try {
                tick(SystemClock.elapsedRealtime())
            } catch (e: RuntimeException) {
                Log.e(TAG, "tick failed", e)
            }
        }
    }

    private fun onDatagram(buf: ByteArray, len: Int, from: InetSocketAddress, nowMs: Long) {
        val type = WireCodec.classify(buf, len)
        if (type < 0) return
        when (type) {
            PacketType.ANNOUNCE -> (WireCodec.decodePlain(buf, len) as? Announce)?.let { onAnnounce(it, from, nowMs) }
            PacketType.KEY_ENROLL_ACK -> {
                qrFlow?.let { handleQrOuts(it, it.onDatagram(buf, len, from)) }
                return
            }
            PacketType.PAIR_HOST, PacketType.PAIR_ACK, PacketType.PAIR_DONE -> {
                val f = pairFlow ?: return
                val p = WireCodec.decodePlain(buf, len) ?: return
                handlePairOuts(f.onPacket(p, nowMs))
                return
            }
        }
        session?.let { handleSessionOuts(it.onDatagram(buf, len, from, nowMs)) }
    }

    private fun tick(nowMs: Long) {
        usbTick(nowMs)
        pairFlow?.let { handlePairOuts(it.onTick(nowMs)) }
        qrFlow?.let { handleQrOuts(it, it.onTick(nowMs)) }
        if (qrConnectKeyId != null && nowMs >= qrConnectUntilMs) {
            qrConnectKeyId = null // paired, but the PC did not answer a key DISCOVER in time
            _qrPairing.value = QrPairView(qrConnectName, "Paired with $qrConnectName. Tap Connect when it shows up below.", finished = true, ok = true)
        }
        session?.let { handleSessionOuts(it.onTick(nowMs)) }

        // Discovery: our own rounds while no session is up. A session flow still looking for its
        // PC runs its own (with its reqId), but only ours name the trusted keys, so they go out
        // too: otherwise a stuck session hides that a PC is ready by key. They also go out while
        // the PC is silent, so a PC watcher that knows this phone by key starts the companion
        // again. ANNOUNCEs feed [found].
        val f = session
        val idle = f == null || !f.isRunning || !KeyTakeover.isUp(f.status)
        val ownScan = idle || f?.status == SessionStatus.HOST_SILENT
        if (ownScan && nowMs >= nextScanMs) {
            sendDiscovers()
            nextScanMs = nowMs + Wire.DISCOVER_INTERVAL_MS
        }
        if (nowMs >= nextKeyScanMs) {
            nextKeyScanMs = nowMs + KEY_RESCAN_MS
            onTrustedChanged(keys.rescan()) // FileObserver can miss writes made over adb
        }
        val discovering = idle || f?.status == SessionStatus.DISCOVERING
        if (discovering) {
            if (scanStartMs == 0L) scanStartMs = nowMs
        } else {
            scanStartMs = 0L
        }
        _nothingFound.value = discovering && foundMap.isEmpty() &&
            nowMs - scanStartMs >= Wire.HOST_EXPIRY_MS && nowMs - lastAnnounceMs >= Wire.HOST_EXPIRY_MS

        var expired = false
        val iter = foundSeen.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (nowMs - e.value >= Wire.HOST_EXPIRY_MS) {
                iter.remove()
                foundMap.remove(e.key)
                expired = true
            }
        }
        if (expired) publishFound()

        if (nowMs >= nextRefreshMs) {
            nextRefreshMs = nowMs + REFRESH_MS
            publishState() // Wi-Fi radio details in the status line
        }
    }

    /**
     * One DISCOVER per trusted PC key (at most [MAX_KEY_DISCOVERS]), each naming that key and this
     * phone's key, or a single one naming only this phone's key. Old PCs ignore the key ids.
     */
    private fun sendDiscovers() {
        val phoneKeyId = keys.key?.let { KeyPairing.keyId(it.publicKey) } ?: ByteArray(Wire.KEY_ID_SIZE)
        val trusted = keys.trusted.value.take(MAX_KEY_DISCOVERS)
        lastKeyReqs = keyReqs
        keyReqs = HashMap()
        val targets = broadcastTargets()
        val named = if (trusted.isEmpty()) listOf<TrustedHost?>(null) else trusted
        for (t in named) {
            val reqId = Crypto.randomBytes(4).getI32(0)
            keyReqs[reqId] = t?.keyIdHex ?: ""
            val hostKeyId = t?.key?.keyId ?: ByteArray(Wire.KEY_ID_SIZE)
            val n = WireCodec.encodeDiscover(Discover(reqId, store.phoneId, 0, hostKeyId, phoneKeyId), txBuf)
            for (target in targets) send(txBuf, n, target)
        }
    }

    private fun onAnnounce(a: Announce, from: InetSocketAddress, nowMs: Long) {
        val id = a.hostId.toHex()
        lastAnnounceMs = nowMs
        foundSeen[id] = nowMs
        (keyReqs[a.reqId] ?: lastKeyReqs[a.reqId])?.let { keyId ->
            keyAnswers.getOrPut(id) { HashMap() }[keyId] = KeyPairing.status(a.flags)
        }
        val host = DiscoveredHost(
            hostIdHex = id,
            name = a.name.ifBlank { "PC" },
            address = from.address?.hostAddress ?: from.hostString,
            port = from.port,
            flags = a.flags,
            paired = store.find(id) != null,
            keyStatus = keyStatusOf(id),
        )
        if (foundMap[id] != host) {
            foundMap[id] = host
            publishFound()
        }
        val qrKey = qrConnectKeyId
        if (qrKey != null && host.keyStatus == KeyStatus.READY && pairFlow == null && matchedKey(id)?.keyIdHex == qrKey) {
            qrConnectKeyId = null
            keyUserStopped.remove(id)
            connectKeyTo(id) // the user just scanned this PC's QR code
            _qrPairing.value = QrPairView(qrConnectName, "Paired with $qrConnectName", finished = true, ok = true)
            return
        }
        if (host.keyStatus == KeyStatus.READY && pairFlow == null && id !in keyUserStopped && keySessionWanted(host, nowMs)) {
            connectKeyTo(id) // both sides know each other: no prompt
        }
    }

    /**
     * A key session may start by itself: nothing runs, or the current session has been down for
     * the time [KeyTakeover] allows. That covers a code session to a PC that no longer answers it
     * and a key session the PC refused (e.g. HELLO arrived while a restarting companion was still
     * loading its keys), which is retried at that pace. Another phone taking over is respected,
     * and so is a PC in use by another phone: only a tap on Connect takes it from that phone.
     */
    private fun keySessionWanted(host: DiscoveredHost, nowMs: Long): Boolean {
        if (!_autoConnect.value || !appOnScreen()) return false
        val f = session
        // A PC still holding this phone's own key session (older companions flag that BUSY too)
        // is fine to reconnect to: the running key flow would take it just the same.
        val ownKeySession = f != null && f.isRunning && sessionKeyId != null && sessionHostId == host.hostIdHex
        if (host.busy && !ownKeySession) return false
        if (f == null) return true
        return takeover.allows(f.status, f.isRunning, sessionHostId == host.hostIdHex, nowMs)
    }

    /** The best key answer [hostIdHex] gave for a key this phone still trusts. */
    private fun keyStatusOf(hostIdHex: String): KeyStatus {
        val answers = keyAnswers[hostIdHex] ?: return KeyStatus.NONE
        val current = keys.trusted.value.mapTo(HashSet()) { it.keyIdHex }.apply { add("") }
        val sts = answers.filterKeys { it in current }.values
        return KEY_STATUS_ORDER.firstOrNull { it in sts } ?: KeyStatus.NONE
    }

    /** The trusted PC key [hostIdHex] said is its own, if any. */
    private fun matchedKey(hostIdHex: String): TrustedHost? {
        val answers = keyAnswers[hostIdHex] ?: return null
        return keys.trusted.value.firstOrNull { answers[it.keyIdHex] == KeyStatus.READY }
    }

    /** trusted_hosts changed (or may have): refresh key statuses, end a session whose key is gone. */
    private fun onTrustedChanged(changed: Boolean) {
        if (!changed) return
        val ids = keys.trusted.value.mapTo(HashSet()) { it.keyIdHex }
        if (sessionKeyId != null && sessionKeyId !in ids) endSession(ByeReason.USER)
        var any = false
        for ((id, h) in foundMap.entries.toList()) {
            val st = keyStatusOf(id)
            if (h.keyStatus != st) {
                foundMap[id] = h.copy(keyStatus = st)
                any = true
            }
        }
        if (any) publishFound()
        nextScanMs = 0L // ask the PCs about the new keys now
    }

    private fun publishFound() {
        _found.value = foundMap.values.toList()
    }

    private fun broadcastTargets(): List<InetSocketAddress> {
        val list = ArrayList<InetSocketAddress>(4)
        val u = usb
        if (usbActive && u != null) {
            list.add(InetSocketAddress(u.broadcast, Wire.PORT))
            list.addAll(manualTargets)
            return list
        }
        list.add(InetSocketAddress(LIMITED_BROADCAST, Wire.PORT))
        binder.broadcast?.let { list.add(InetSocketAddress(it, Wire.PORT)) }
        list.addAll(manualTargets)
        return list
    }

    private fun send(bytes: ByteArray, len: Int, to: InetSocketAddress) {
        val s = socket ?: return
        try {
            s.send(DatagramPacket(bytes, len, to))
        } catch (e: IOException) {
            Log.d(TAG, "send to $to failed: ${e.message}")
        }
    }

    private fun autoConnect() {
        val last = store.mostRecent() ?: return
        connectTo(last.hostIdHex)
    }

    private fun connectTo(hostIdHex: String) {
        val stored = store.find(hostIdHex) ?: return
        // A fresh ANNOUNCE beats the stored address (DHCP may have moved the PC).
        val seen = foundMap[hostIdHex]?.let { runCatching { InetSocketAddress(InetAddress.getByName(it.address), it.port) }.getOrNull() }
        val host = store.unwrap(stored, seen)
        if (host == null) {
            _notice.value = "The key for ${stored.name} is unreadable. Forget it and pair again."
            return
        }
        _notice.value = null
        endSession(ByeReason.USER)
        store.touch(hostIdHex)
        val f = PhoneSessionFlow(store.phoneId, host, ::broadcastTargets, Caps.RUMBLE, rateHz)
        session = f
        sessionHostId = hostIdHex
        takeover.started(SystemClock.elapsedRealtime())
        _currentHostId.value = hostIdHex
        sessionHostName = stored.name
        handleSessionOuts(f.connect(SystemClock.elapsedRealtime()))
        publishState()
    }

    /** Stops the session flow; the pump says BYE([reason]) if the session was live. */
    private fun endSession(reason: Int) {
        val f = session ?: return
        f.disconnect()
        session = null
        if (usbActive) useWifi()
        sessionHostId = null
        sessionKeyId = null
        _pairedByKey.value = false
        _currentHostId.value = null
        if (live != null) {
            live = null
            pump.setSession(null, reason)
        }
        locks.release()
        hostStatus = null
        welcomeHostFlags = null
        publishState()
    }

    private fun connectKeyTo(hostIdHex: String) {
        val found = foundMap[hostIdHex] ?: return
        val trusted = matchedKey(hostIdHex) ?: return
        val k = keys.key ?: return
        val addr = runCatching { InetSocketAddress(InetAddress.getByName(found.address), found.port) }.getOrNull()
        val secrets = try {
            KeyPairing.phoneSecrets(k, trusted.key.publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "key agreement failed", e)
            null
        }
        if (secrets == null) {
            _notice.value = "Key pairing with ${found.name} failed"
            return
        }
        val (keyPhoneId, pairKey) = secrets
        _notice.value = null
        endSession(ByeReason.USER)
        val host = PairedHost(hexToBytes(hostIdHex), found.name, pairKey, addr)
        val f = PhoneSessionFlow(keyPhoneId, host, ::broadcastTargets, Caps.RUMBLE, rateHz)
        session = f
        sessionHostId = hostIdHex
        takeover.started(SystemClock.elapsedRealtime())
        sessionKeyId = trusted.keyIdHex
        _pairedByKey.value = true
        _currentHostId.value = hostIdHex
        sessionHostName = found.name
        handleSessionOuts(f.connect(SystemClock.elapsedRealtime()))
        publishState()
    }

    private fun startQr(invite: QrInvite) {
        val name = invite.name.ifBlank { "PC" }
        val k = keys.key
        if (k == null) {
            _qrPairing.value = QrPairView(name, "This phone has no pairing key (Android Keystore refused)", finished = true)
            return
        }
        qrFlow?.cancel()
        qrConnectKeyId = null
        for (a in invite.addresses) {
            runCatching { InetAddress.getByName(a) }.getOrNull()?.let { manualTargets.add(InetSocketAddress(it, invite.port)) }
        }
        val f = QrEnrollFlow(invite, k.publicKey, phoneName(), ::broadcastTargets)
        qrFlow = f
        _qrPairing.value = QrPairView(name, "Pairing with $name…")
        handleQrOuts(f, f.start(SystemClock.elapsedRealtime()))
    }

    private fun handleQrOuts(f: QrEnrollFlow, outs: List<QrEnrollFlow.Out>) {
        for (o in outs) when (o) {
            is QrEnrollFlow.Out.Send -> send(o.bytes, o.bytes.size, o.addr)
            is QrEnrollFlow.Out.Done -> onQrDone(f.invite, o.result)
        }
    }

    private fun onQrDone(invite: QrInvite, r: EnrollResult) {
        qrFlow = null
        val name = invite.name.ifBlank { "PC" }
        if (r != EnrollResult.OK) {
            val why = when (r) {
                EnrollResult.EXPIRED -> "The QR code expired. On the PC choose Pair new phone again."
                EnrollResult.USED -> "Another phone already used this QR code. On the PC choose Pair new phone again."
                EnrollResult.NO_KEY -> "$name has key pairing off (see its log). Pair with a code instead."
                else -> "No answer from $name. Check that the phone is on the same network as the PC."
            }
            _qrPairing.value = QrPairView(name, why, finished = true)
            return
        }
        // The PC knows this phone now; trust its key like an imported host.pub.
        val err = keys.import(PubKeyFile(invite.hostKey, name).format())
        if (err != null) {
            _qrPairing.value = QrPairView(name, err, finished = true)
            return
        }
        onTrustedChanged(true)
        if (session?.isRunning == true) {
            // Another session runs, so no DISCOVER goes out to find the PC: leave connecting to the user.
            _qrPairing.value = QrPairView(name, "Paired with $name", finished = true, ok = true)
            return
        }
        qrConnectKeyId = KeyPairing.keyId(invite.hostKey).toHex()
        qrConnectName = name
        qrConnectUntilMs = SystemClock.elapsedRealtime() + QR_CONNECT_WAIT_MS
        _qrPairing.value = QrPairView(name, "Paired with $name, connecting…")
    }

    private fun startPairing(host: DiscoveredHost) {
        if (pairFlow != null) return
        val addr = runCatching { InetSocketAddress(InetAddress.getByName(host.address), host.port) }.getOrNull() ?: return
        val f = PhonePairingFlow(store.phoneId, phoneName(), addr)
        pairFlow = f
        _pairing.value = PairingView(host.name, PairState.Idle)
        handlePairOuts(f.start(SystemClock.elapsedRealtime()))
    }

    private fun handlePairOuts(outs: List<PhonePairingFlow.Out>) {
        for (o in outs) when (o) {
            is PhonePairingFlow.Out.Send -> send(o.bytes, o.bytes.size, o.addr)
            is PhonePairingFlow.Out.State -> onPairState(o.state)
        }
    }

    private fun onPairState(s: PairState) {
        val name = _pairing.value?.hostName ?: ""
        _pairing.value = PairingView(name, s)
        when (s) {
            is PairState.Done -> {
                pairFlow = null
                if (store.save(s.host)) {
                    val id = s.host.hostId.toHex()
                    foundMap[id]?.let { foundMap[id] = it.copy(paired = true); publishFound() }
                    connectTo(id)
                } else {
                    _notice.value = "Could not store the pairing key"
                }
            }
            is PairState.Failed -> {
                pairFlow = null
                if (s.reason == PairFailure.CANCELLED) _pairing.value = null // the user closed it
            }
            else -> Unit
        }
        publishState()
    }

    private fun handleSessionOuts(outs: List<PhoneSessionFlow.Out>) {
        var changed = false
        for (o in outs) when (o) {
            is PhoneSessionFlow.Out.Send -> send(o.bytes, o.bytes.size, o.addr)
            is PhoneSessionFlow.Out.Live -> {
                val s = o.session
                live = s
                // A replacement (rekey, new HELLO after silence) needs no BYE; neither does a
                // session the PC ended with HOST_BYE.
                pump.setSession(s)
                clock.reset()
                clockOffsetUs = 0L
                rttMinUs = -1L
                hostStatus = null
                welcomeHostFlags = s?.hostFlags
                if (s != null) locks.acquire() else locks.release()
                changed = true
            }
            is PhoneSessionFlow.Out.Status -> {
                takeover.onStatus(o.status, SystemClock.elapsedRealtime())
                changed = true
            }
            is PhoneSessionFlow.Out.HostAddress -> sessionHostId?.let { store.updateAddress(it, o.addr) }
            is PhoneSessionFlow.Out.Received -> when (val p = o.packet) {
                is Pong -> onPong(p)
                is HostStatus -> hostStatus = p
                else -> Unit // RUMBLE is ignored in v1; ANNOUNCE was handled already
            }
        }
        if (changed) publishState()
    }

    private fun onPong(p: Pong) {
        val t4 = WifiPump.phoneUs()
        if (clock.add(p.t1, p.t2, p.t3, t4)) {
            clockOffsetUs = clock.offsetUs
            rttMinUs = clock.rttMinUs
            pump.requestLinkInfo()
        }
    }


    // ---- USB tethering ----

    /**
     * Once a second: notices USB tethering come and go. While the session runs over Wi-Fi, a probe
     * sends DISCOVER on the tether; when the session's PC answers there, the link moves to USB
     * ([useUsb]). Pulling the cable (the tether goes) or the PC going silent on USB moves it back.
     */
    private fun usbTick(nowMs: Long) {
        if (nowMs >= nextUsbCheckMs) {
            nextUsbCheckMs = nowMs + USB_CHECK_MS
            val t = UsbTether.find()
            if (t?.address != usb?.address) {
                if (usbActive) useWifi()
                closeUsb()
                usb = t
                if (t != null) openProbe(t)
                publishState()
            }
        }
        val f = session
        if (usbActive) {
            // Silent over USB for a while: the PC may not take it (firewall); Wi-Fi again for now.
            if (f?.status == SessionStatus.HOST_SILENT) {
                if (usbSilentSinceMs == 0L) usbSilentSinceMs = nowMs
                if (nowMs - usbSilentSinceMs >= USB_SILENT_MS) {
                    usbBackoffUntilMs = nowMs + USB_BACKOFF_MS
                    useWifi()
                }
            } else usbSilentSinceMs = 0L
            return
        }
        val probe = usbProbe ?: return
        val u = usb ?: return
        if (f == null || !f.isRunning || nowMs < usbBackoffUntilMs) return
        val host = sessionHostId
        val addr = usbHostAddr
        if (host != null && host == usbHostId && addr != null) {
            useUsb(addr)
            return
        }
        if (nowMs >= nextUsbProbeMs) {
            nextUsbProbeMs = nowMs + USB_PROBE_MS
            val n = WireCodec.encodeDiscover(Discover(Crypto.randomBytes(4).getI32(0), store.phoneId), txBuf)
            try {
                probe.send(ByteBuffer.wrap(txBuf, 0, n), InetSocketAddress(u.broadcast, Wire.PORT))
            } catch (e: IOException) {
                Log.d(TAG, "USB probe failed: ${e.message}")
            }
        }
    }

    private fun openProbe(t: UsbTetherInfo) {
        usbProbe = try {
            DatagramChannel.open().apply {
                configureBlocking(false)
                socket().broadcast = true
                bind(InetSocketAddress(t.address, 0))
            }
        } catch (e: IOException) {
            Log.w(TAG, "no USB probe socket", e)
            null
        }
        nextUsbProbeMs = 0L
    }

    /** Reads the probe's ANNOUNCEs without waiting: the PC that answered and its USB address. */
    private fun pollUsbProbe() {
        val probe = usbProbe ?: return
        while (true) {
            usbProbeBuf.clear()
            val from = try {
                probe.receive(usbProbeBuf) as? InetSocketAddress
            } catch (e: IOException) {
                null
            } ?: return
            val len = usbProbeBuf.position()
            val a = WireCodec.decodePlain(usbProbeBuf.array(), len) as? Announce ?: continue
            usbHostId = a.hostId.toHex()
            usbHostAddr = from
        }
    }

    /** The session goes over USB to the PC at [addr]: its own socket on the tether, a fresh HELLO there. */
    private fun useUsb(addr: InetSocketAddress) {
        val u = usb ?: return
        val s = try {
            DatagramSocket(InetSocketAddress(u.address, 0)).apply {
                broadcast = true
                trafficClass = Wire.DSCP_TRAFFIC_CLASS
                sendBufferSize = SEND_BUFFER
                soTimeout = RX_TIMEOUT_MS
            }
        } catch (e: SocketException) {
            Log.w(TAG, "no USB socket", e)
            usbBackoffUntilMs = SystemClock.elapsedRealtime() + USB_BACKOFF_MS
            return
        }
        usbProbe?.let { runCatching { it.close() } }
        usbProbe = null
        usbSocket = s
        usbActive = true
        usbSilentSinceMs = 0L
        socket = s
        pump.useSocket(s)
        session?.let { handleSessionOuts(it.moveTo(addr, SystemClock.elapsedRealtime())) }
        publishState()
    }

    /** Back to Wi-Fi: the Wi-Fi socket again, the PC found there afresh, the probe looking at USB again. */
    private fun useWifi() {
        val w = wifiSocket ?: return
        usbActive = false
        socket = w
        pump.useSocket(w)
        usbSocket?.close()
        usbSocket = null
        usbHostId = null
        usbHostAddr = null
        val addr = sessionHostId?.let { foundMap[it] }?.let {
            runCatching { InetSocketAddress(InetAddress.getByName(it.address), it.port) }.getOrNull()
        }
        session?.let { handleSessionOuts(it.moveTo(addr, SystemClock.elapsedRealtime())) }
        usb?.let { if (usbProbe == null) openProbe(it) }
        publishState()
    }

    private fun closeUsb() {
        usbProbe?.let { runCatching { it.close() } }
        usbProbe = null
        usbSocket?.close()
        usbSocket = null
        usbActive = false
        usbHostId = null
        usbHostAddr = null
    }

    private fun onNetworkChanged() {
        nextScanMs = 0L // look around the new network at once
        // Over USB a Wi-Fi change does not matter.
        if (!usbActive) session?.let { handleSessionOuts(it.reconnect(SystemClock.elapsedRealtime())) }
        publishState()
    }

    /** Pump thread: LINK_INFO for the PC's latency statistics, once a clock sample exists. */
    private fun buildLinkInfo(): LinkInfo? {
        val rtt = rttMinUs
        if (rtt < 0) return null
        val usbNow = usbActive
        val info = if (usbNow) WifiRadioInfo() else binder.info
        return LinkInfo(
            clockOffsetUs = clockOffsetUs,
            rttMinUs = rtt.coerceAtMost(0xFFFF_FFFFL).toInt(),
            sendRateHz = pump.rateHz,
            rssiDbm = (info.rssiDbm ?: 0).coerceIn(-128, 127),
            band = info.band,
            linkMbps = (info.linkMbps ?: 0).coerceIn(0, 0xFFFF),
            freqMhz = (info.freqMhz ?: 0).coerceIn(0, 0xFFFF),
            flags = (if (locks.held) LinkInfoFlags.LOW_LATENCY_LOCK_HELD else 0) or (if (usbNow) LinkInfoFlags.USB else 0),
        )
    }

    private fun publishState() {
        if (!running) return
        val pf = pairFlow
        val pv = _pairing.value
        val f = session
        _state.value = when {
            pf != null && pv != null -> LinkState.Pairing(sasOf(pv.state), pv.hostName)
            f != null -> when (f.status) {
                SessionStatus.LIVE -> LinkState.Connected(sessionHostName, radioDetail())
                SessionStatus.HOST_SILENT -> LinkState.HostSilent(sessionHostName)
                SessionStatus.NOT_PAIRED -> LinkState.Failed(
                    if (sessionKeyId != null) "$sessionHostName no longer has this phone's key. Run update.cmd or pair with a code"
                    else "$sessionHostName no longer knows this phone. Pair again?",
                )
                SessionStatus.VERSION_MISMATCH -> LinkState.Failed("Update GyroPhone / the PC app")
                SessionStatus.REPLACED -> LinkState.Failed("Another phone took over $sessionHostName")
                SessionStatus.FORGOTTEN -> LinkState.Failed("$sessionHostName forgot this phone. Pair again?")
                SessionStatus.IDLE, SessionStatus.CONNECTING, SessionStatus.DISCOVERING, SessionStatus.HOST_EXITED ->
                    LinkState.Connecting(sessionHostName)
            }
            binder.network == null && usb == null -> LinkState.Failed("Not connected to Wi-Fi or USB tethering")
            else -> LinkState.Searching
        }
    }

    /** An activity of ours is visible (a foreground service alone does not count). */
    private fun appOnScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** "5 GHz · 1201 Mbps · −51 dBm", leaving out what is unknown. */
    private fun radioDetail(): String {
        if (usbActive) return "USB"
        val info = binder.info
        val parts = ArrayList<String>(3)
        if (info.band != 0) parts.add("${info.band} GHz")
        info.linkMbps?.let { parts.add("$it Mbps") }
        info.rssiDbm?.let { parts.add("${it.toString().replace('-', '−')} dBm") }
        return parts.joinToString(" · ")
    }

    private fun phoneName(): String =
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    companion object {
        private const val TAG = "WifiLink"
        private const val RX_TIMEOUT_MS = 50
        private const val RX_JOIN_MS = 300L
        private const val SEND_BUFFER = 8192
        private const val REFRESH_MS = 1000L
        private const val UNKNOWN_LATENCY = -1 // 0xFFFFFFFF
        private const val MAX_KEY_DISCOVERS = 4
        private const val KEY_AUTO_CONNECT = "keyAutoConnect"
        private const val KEY_RESCAN_MS = 2000L
        /** How often USB tethering is looked for, and the PC on it. */
        private const val USB_CHECK_MS = 1000L
        private const val USB_PROBE_MS = 1000L
        /** Silent this long over USB: back to Wi-Fi, and USB is not tried again for [USB_BACKOFF_MS]. */
        private const val USB_SILENT_MS = 3000L
        private const val USB_BACKOFF_MS = 30_000L
        /** After a QR code pairing, how long to wait for the PC to answer a key DISCOVER. */
        private const val QR_CONNECT_WAIT_MS = 5000L
        /** When a PC answered several key DISCOVERs, the most useful answer wins. */
        private val KEY_STATUS_ORDER = listOf(KeyStatus.READY, KeyStatus.PC_LACKS_PHONE, KeyStatus.PHONE_LACKS_PC)
        private val LIMITED_BROADCAST: InetAddress =
            InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

        private fun sasOf(s: PairState): String? = when (s) {
            is PairState.GotHost -> PairingMath.formatSas(s.sas)
            is PairState.AwaitUser -> PairingMath.formatSas(s.sas)
            is PairState.Confirming -> PairingMath.formatSas(s.sas)
            else -> null
        }

        private fun PairState.isFinished(): Boolean = this is PairState.Done || this is PairState.Failed
    }
}
