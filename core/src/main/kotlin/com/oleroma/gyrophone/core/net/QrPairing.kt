package com.oleroma.gyrophone.core.net

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64

/** What a PC's QR code carries: its key, a one-time token, its name and where to reach it. */
class QrInvite(
    val hostKey: ByteArray,
    val token: ByteArray,
    val name: String,
    /** IPv4 literals, best first. */
    val addresses: List<String>,
    val port: Int = Wire.PORT,
)

/**
 * QR code pairing. The PC shows `gyrophone://pair?v=1&n=<name>&k=<pkH>&t=<token>&a=<ip,ip>&p=<port>`
 * (k and t base64url without padding, n percent-encoded UTF-8). Scanning it gives the phone the
 * PC's static key, so the phone trusts it as if host.pub had been copied. The phone then sends
 * KEY_ENROLL with its own static key, MACed with the token; the PC adds that key to
 * authorized_phones and answers KEY_ENROLL_ACK, MACed with the token over the phone's key. From
 * there both sides know each other and key pairing connects as usual.
 *
 * - `tokenId = SHA-256("GP-qr-id-v1" || token)[0..8]`
 * - `enrollMac = HMAC-SHA256(token, "GP-qr-enroll-v1" || KEY_ENROLL[0..121])[0..16]`
 * - `ackMac = HMAC-SHA256(token, "GP-qr-ack-v1" || KEY_ENROLL_ACK[0..25] || pkP)[0..16]`
 *
 * Only someone who saw the QR code knows the token. Must match QrPairing.cs.
 */
object QrPairing {
    const val PREFIX = "gyrophone://pair?"
    const val MAC_SIZE = 16
    private const val NAME_MAX = 64

    fun tokenId(token: ByteArray): ByteArray = Crypto.sha256(Crypto.ascii("GP-qr-id-v1"), token).copyOf(Wire.KEY_ID_SIZE)

    /** The mac of a KEY_ENROLL whose bytes 0..121 are already in [packet]. */
    fun enrollMac(token: ByteArray, packet: ByteArray): ByteArray =
        Crypto.hmac(token, Crypto.ascii("GP-qr-enroll-v1"), packet.copyOf(121)).copyOf(MAC_SIZE)

    /** The mac of a KEY_ENROLL_ACK whose bytes 0..25 are already in [packet], for the phone key [pkP]. */
    fun ackMac(token: ByteArray, packet: ByteArray, pkP: ByteArray): ByteArray =
        Crypto.hmac(token, Crypto.ascii("GP-qr-ack-v1"), packet.copyOf(25), pkP).copyOf(MAC_SIZE)

    fun format(inv: QrInvite): String = PREFIX + "v=1" +
        "&n=" + percentEncode(inv.name) +
        "&k=" + b64(inv.hostKey) +
        "&t=" + b64(inv.token) +
        "&a=" + inv.addresses.joinToString(",") +
        "&p=" + inv.port

    /** Parses a scanned QR code; null unless it is a valid GyroPhone pairing code. */
    fun parse(text: String): QrInvite? {
        val t = text.trim()
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        for (part in t.substring(PREFIX.length).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            fields.putIfAbsent(part.substring(0, eq), part.substring(eq + 1))
        }
        if (fields["v"] != "1") return null
        val key = unb64(fields["k"]) ?: return null
        if (key.size != Wire.PUBLIC_KEY_SIZE || P256.decodePublic(key) == null) return null
        val token = unb64(fields["t"]) ?: return null
        if (token.size != Wire.QR_TOKEN_SIZE) return null
        val name = percentDecode(fields["n"] ?: "")?.take(NAME_MAX) ?: return null
        val addresses = (fields["a"] ?: "").split(',').map { it.trim() }.filter { IPV4.matches(it) }
        val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Wire.PORT
        return QrInvite(key, token, name, addresses, port)
    }

    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    private fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun unb64(s: String?): ByteArray? = try {
        s?.let { Base64.getUrlDecoder().decode(it) }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** RFC 3986: unreserved characters stay, every other UTF-8 byte becomes %XX. */
    private fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun percentDecode(s: String): String? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 3 > s.length) return null
                    out.write(s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null)
                    i += 3
                    continue
                }
                c == '+' -> out.write(' '.code)
                else -> out.write(c.toString().toByteArray(Charsets.UTF_8))
            }
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** How a QR enrolment ended. */
enum class EnrollResult {
    /** The PC took this phone's key: both sides know each other now. */
    OK,
    /** The PC no longer shows that QR code. */
    EXPIRED,
    /** Another phone already used that QR code. */
    USED,
    /** The PC has no key of its own (key pairing is off there). */
    NO_KEY,
    /** No answer within [Wire.QR_GIVE_UP_MS]. */
    NO_ANSWER,
}

/**
 * The phone side of QR code pairing: KEY_ENROLL every [Wire.QR_RETX_MS] to the QR's addresses and
 * the broadcast targets until a matching KEY_ENROLL_ACK arrives or [Wire.QR_GIVE_UP_MS] passes.
 * EXPIRED and NO_KEY carry no MAC, and every PC on the LAN answers the broadcast, so they end the
 * flow at once only from one of the QR's addresses; from anywhere else they are reported at
 * [Wire.QR_GIVE_UP_MS] unless the PC in the QR code answers first.
 * Pure and single-threaded, like [PhonePairingFlow].
 */
class QrEnrollFlow(
    val invite: QrInvite,
    private val phoneKey: ByteArray,
    private val phoneName: String,
    private val broadcastTargets: () -> List<InetSocketAddress>,
    private val reqId: Int = Crypto.randomBytes(4).getI32(0),
) {
    sealed interface Out {
        class Send(val bytes: ByteArray, val addr: InetSocketAddress) : Out
        data class Done(val result: EnrollResult) : Out
    }

    private val tokenId = QrPairing.tokenId(invite.token)
    private val direct = invite.addresses.mapNotNull {
        runCatching { InetSocketAddress(InetAddress.getByName(it), invite.port) }.getOrNull()
    }
    private val directHosts = direct.mapTo(HashSet()) { it.address }
    /** An EXPIRED or NO_KEY from an address not in the QR code (maybe another PC), held until give-up. */
    private var strayRefusal: EnrollResult? = null
    private var startMs = 0L
    private var nextSendMs = 0L
    var finished = false
        private set

    fun start(nowMs: Long): List<Out> {
        startMs = nowMs
        nextSendMs = nowMs
        return onTick(nowMs)
    }

    fun onTick(nowMs: Long): List<Out> {
        if (finished) return emptyList()
        if (nowMs - startMs >= Wire.QR_GIVE_UP_MS) return finish(strayRefusal ?: EnrollResult.NO_ANSWER)
        if (nowMs < nextSendMs) return emptyList()
        nextSendMs = nowMs + Wire.QR_RETX_MS
        val buf = ByteArray(Wire.KEY_ENROLL_SIZE)
        WireCodec.encodeKeyEnroll(reqId, invite.token, phoneKey, phoneName, buf)
        return (direct + broadcastTargets()).distinct().map { Out.Send(buf, it) }
    }

    /** A datagram from [from] that may be our KEY_ENROLL_ACK. */
    fun onDatagram(buf: ByteArray, len: Int, from: InetSocketAddress): List<Out> {
        if (finished) return emptyList()
        val a = WireCodec.decodePlain(buf, len) as? KeyEnrollAck ?: return emptyList()
        if (a.reqId != reqId || !a.tokenId.contentEquals(tokenId)) return emptyList()
        val macOk = Crypto.ctEquals(a.mac, QrPairing.ackMac(invite.token, buf, phoneKey))
        return when (a.status) {
            EnrollStatus.OK -> if (macOk) finish(EnrollResult.OK) else emptyList()
            EnrollStatus.USED -> if (macOk) finish(EnrollResult.USED) else emptyList()
            EnrollStatus.EXPIRED -> refused(EnrollResult.EXPIRED, from)
            EnrollStatus.NO_KEY -> refused(EnrollResult.NO_KEY, from)
            else -> emptyList()
        }
    }

    private fun refused(r: EnrollResult, from: InetSocketAddress): List<Out> {
        if (from.address in directHosts) return finish(r)
        // EXPIRED is the likelier answer of the scanned PC itself, so it wins over NO_KEY.
        if (strayRefusal != EnrollResult.EXPIRED) strayRefusal = r
        return emptyList()
    }

    fun cancel() {
        finished = true
    }

    private fun finish(r: EnrollResult): List<Out> {
        finished = true
        return listOf(Out.Done(r))
    }
}
