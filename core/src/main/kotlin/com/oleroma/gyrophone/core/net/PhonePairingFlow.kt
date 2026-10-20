package com.oleroma.gyrophone.core.net

import java.net.InetSocketAddress
import java.security.KeyPair

/** A PC this phone has paired with. [addr] is the last address it answered from, if known. */
data class PairedHost(
    val hostId: ByteArray,
    val name: String,
    val pairKey: ByteArray,
    val addr: InetSocketAddress?,
)

enum class PairFailure {
    /** The PC's pairing window is closed: choose "Pair new phone" on the PC. */
    CLOSED,
    BUSY,
    COOLDOWN,
    BAD_COMMIT,
    UNKNOWN_ATTEMPT,
    DENIED,
    TIMEOUT,
    BAD_CONFIRM,
    /** The PC's confirmH did not verify: the code comparison was attacked or the keys differ. */
    HOST_CONFIRM_MISMATCH,
    CANCELLED,
    /** No outcome within [Wire.PAIRING_WINDOW_S]. */
    GAVE_UP,
}

sealed interface PairState {
    data object Idle : PairState
    /** PAIR_START sent, waiting for PAIR_HOST. */
    data object Started : PairState
    /** PAIR_REVEAL sent; the code is shown with Confirm and Cancel while the PC verifies the commit. */
    data class GotHost(val sas: String, val hostName: String) : PairState
    /** The PC shows the code too and waits for Allow; waiting for the user's Confirm here. */
    data class AwaitUser(val sas: String, val hostName: String) : PairState
    /** PAIR_CONFIRM sent; [hostPending] once the PC reported its prompt is still open. */
    data class Confirming(val sas: String, val hostName: String, val hostPending: Boolean) : PairState
    data class Done(val host: PairedHost) : PairState
    data class Failed(val reason: PairFailure) : PairState
}

/**
 * Phone side of commit/reveal pairing (wire v1 section 1.5) as a pure state machine:
 * `Idle -> Started -> GotHost(sas) -> AwaitUser -> Confirming -> Done | Failed`.
 * The caller feeds packets and ticks and sends what it is told; randomness and the ephemeral
 * key pair are injected for tests. The pairing is reported Done only after confirmH verifies.
 */
class PhonePairingFlow(
    private val phoneId: ByteArray,
    private val phoneName: String,
    private val hostAddr: InetSocketAddress,
    private val random: (Int) -> ByteArray = Crypto::randomBytes,
    private val keyPair: () -> KeyPair = P256::generate,
) {
    sealed interface Out {
        class Send(val bytes: ByteArray, val addr: InetSocketAddress) : Out
        data class State(val state: PairState) : Out
    }

    var state: PairState = PairState.Idle
        private set

    private var kp: KeyPair? = null
    private var pkP = ByteArray(0)
    private var nP = ByteArray(0)
    private var commit = ByteArray(0)
    private var attemptId = 0
    private var hostId = ByteArray(0)
    private var hostName = ""
    private var pairKey = ByteArray(0)
    private var th = ByteArray(0)
    private var userConfirmedEarly = false
    private var startedMs = 0L
    private var nextTxMs = 0L
    private val buf = ByteArray(Wire.MAX_DATAGRAM)

    fun start(nowMs: Long): List<Out> {
        check(state == PairState.Idle) { "a flow runs once" }
        val k = keyPair()
        kp = k
        pkP = P256.encodePublic(k.public)
        nP = random(Wire.NONCE_SIZE)
        commit = PairingMath.commit(pkP, nP)
        startedMs = nowMs
        val out = ArrayList<Out>()
        move(PairState.Started, out)
        sendCurrent(nowMs, out)
        return out
    }

    fun onTick(nowMs: Long): List<Out> {
        if (isFinished() || state == PairState.Idle) return emptyList()
        val out = ArrayList<Out>()
        if (nowMs - startedMs >= Wire.PAIRING_WINDOW_S * 1000L) {
            fail(PairFailure.GAVE_UP, out)
        } else if (nowMs >= nextTxMs) {
            sendCurrent(nowMs, out)
        }
        return out
    }

    fun onPacket(p: WirePacket, nowMs: Long): List<Out> {
        val out = ArrayList<Out>()
        when (val s = state) {
            PairState.Started -> when (p) {
                is PairHost -> onHost(p, nowMs, out)
                is PairAck -> refusal(p.status)?.let { fail(it, out) }
                else -> Unit
            }
            is PairState.GotHost -> if (p is PairAck && p.attemptId == attemptId) {
                if (p.status == AckStatus.OK) {
                    if (userConfirmedEarly) {
                        move(PairState.Confirming(s.sas, s.hostName, false), out)
                        sendCurrent(nowMs, out)
                    } else {
                        move(PairState.AwaitUser(s.sas, s.hostName), out)
                    }
                } else {
                    fail(ackFailure(p.status), out)
                }
            }
            is PairState.AwaitUser, is PairState.Confirming -> when {
                p is PairAck && (p.attemptId == attemptId || p.attemptId == 0) && p.status != AckStatus.OK ->
                    fail(ackFailure(p.status), out)
                p is PairDone && p.attemptId == attemptId && s is PairState.Confirming -> onDone(p, s, out)
            }
            else -> Unit
        }
        return out
    }

    /** The user tapped Confirm after comparing the codes. */
    fun userConfirmed(nowMs: Long): List<Out> {
        val out = ArrayList<Out>()
        when (val s = state) {
            is PairState.GotHost -> userConfirmedEarly = true
            is PairState.AwaitUser -> {
                move(PairState.Confirming(s.sas, s.hostName, false), out)
                sendCurrent(nowMs, out)
            }
            else -> Unit
        }
        return out
    }

    fun cancel(): List<Out> {
        if (isFinished()) return emptyList()
        val out = ArrayList<Out>()
        fail(PairFailure.CANCELLED, out)
        return out
    }

    fun isFinished(): Boolean = state is PairState.Done || state is PairState.Failed

    private fun onHost(p: PairHost, nowMs: Long, out: MutableList<Out>) {
        val k = kp ?: return
        val pub = P256.decodePublic(p.pkH) ?: return
        val z = P256.ecdh(k.private, pub)
        attemptId = p.attemptId
        hostId = p.hostId
        hostName = p.name
        th = PairingMath.th(phoneId, hostId, pkP, p.pkH, nP, p.nH, attemptId)
        pairKey = PairingMath.pairKey(z, th)
        z.fill(0)
        val sas = PairingMath.sas(th)
        move(PairState.GotHost(sas, hostName), out)
        sendCurrent(nowMs, out)
    }

    private fun onDone(p: PairDone, s: PairState.Confirming, out: MutableList<Out>) {
        when (p.status) {
            DoneStatus.PENDING -> if (!s.hostPending) move(s.copy(hostPending = true), out)
            DoneStatus.ACCEPTED -> {
                if (Crypto.ctEquals(p.confirmH, PairingMath.confirmH(pairKey, th))) {
                    val host = PairedHost(hostId, hostName, pairKey.copyOf(), hostAddr)
                    erase()
                    move(PairState.Done(host), out)
                } else {
                    fail(PairFailure.HOST_CONFIRM_MISMATCH, out)
                }
            }
            DoneStatus.DENIED -> fail(PairFailure.DENIED, out)
            DoneStatus.TIMEOUT -> fail(PairFailure.TIMEOUT, out)
            DoneStatus.BAD_CONFIRM -> fail(PairFailure.BAD_CONFIRM, out)
        }
    }

    private fun sendCurrent(nowMs: Long, out: MutableList<Out>) {
        val len = when (state) {
            PairState.Started -> WireCodec.encodePairStart(PairStart(phoneId, pkP, commit, phoneName), buf)
            is PairState.GotHost -> WireCodec.encodePairReveal(PairReveal(attemptId, phoneId, nP), buf)
            is PairState.Confirming -> WireCodec.encodePairConfirm(
                PairConfirm(attemptId, phoneId, PairingMath.confirmP(pairKey, th)), buf,
            )
            else -> return // AwaitUser: nothing to repeat until the user answers
        }
        out.add(Out.Send(buf.copyOf(len), hostAddr))
        nextTxMs = nowMs + if (state is PairState.Confirming) Wire.PAIR_CONFIRM_RETX_MS else Wire.PAIR_RETX_MS
    }

    private fun refusal(status: Int): PairFailure? = when (status) {
        AckStatus.CLOSED -> PairFailure.CLOSED
        AckStatus.BUSY -> PairFailure.BUSY
        AckStatus.COOLDOWN -> PairFailure.COOLDOWN
        else -> null
    }

    private fun ackFailure(status: Int): PairFailure = when (status) {
        AckStatus.BAD_COMMIT -> PairFailure.BAD_COMMIT
        AckStatus.UNKNOWN_ATTEMPT -> PairFailure.UNKNOWN_ATTEMPT
        else -> refusal(status) ?: PairFailure.UNKNOWN_ATTEMPT
    }

    private fun fail(reason: PairFailure, out: MutableList<Out>) {
        erase()
        move(PairState.Failed(reason), out)
    }

    /** Drops the ephemeral key and zeroes the secrets this flow holds. */
    private fun erase() {
        kp = null
        nP.fill(0)
        pairKey.fill(0)
        th.fill(0)
    }

    private fun move(s: PairState, out: MutableList<Out>) {
        state = s
        out.add(Out.State(s))
    }
}
