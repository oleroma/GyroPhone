package com.oleroma.gyrophone.core.net

/**
 * Encoders and decoders for wire v1. Encoders write into a caller buffer (at least
 * [Wire.MAX_DATAGRAM] bytes, or the plaintext size for sealed bodies) and return the length.
 * Decoders return null on any rule violation: wrong magic, version, type or exact size, or a
 * name length that overruns its field.
 */
object WireCodec {
    /** [classify] result for a datagram whose version is not 1 (the host answers ERROR VERSION). */
    const val BAD_VERSION = -2
    /** [classify] result for anything else that must be dropped. */
    const val INVALID = -1

    // ---- header ----

    fun writeHeader(out: ByteArray, type: Int, sessionId: Int = 0, seq: Int = 0) {
        out[0] = Wire.MAGIC0
        out[1] = Wire.MAGIC1
        out[2] = Wire.VERSION.toByte()
        out[3] = type.toByte()
        out.putU32(4, sessionId)
        out.putU32(8, seq)
    }

    /**
     * Validates the header and exact size (receiver steps 1-4) and returns the packet type,
     * [BAD_VERSION] or [INVALID].
     */
    fun classify(buf: ByteArray, len: Int): Int {
        if (len < Wire.HEADER || len > buf.size) return INVALID
        if (buf[0] != Wire.MAGIC0 || buf[1] != Wire.MAGIC1) return INVALID
        if (buf.getU8(2) != Wire.VERSION) return BAD_VERSION
        val type = buf.getU8(3)
        val size = Wire.sizeOf(type)
        return if (size >= 0 && len == size) type else INVALID
    }

    fun headerType(buf: ByteArray): Int = buf.getU8(3)
    fun headerSessionId(buf: ByteArray): Int = buf.getI32(4)
    fun headerSeq(buf: ByteArray): Int = buf.getI32(8)

    // ---- unsealed packets ----

    fun encodeDiscover(p: Discover, out: ByteArray): Int = fixed(out, PacketType.DISCOVER, Wire.DISCOVER_SIZE) {
        out.putU32(12, p.reqId)
        putBytes(out, 16, p.phoneId, Wire.ID_SIZE)
        out.putU16(32, p.flags)
        putBytes(out, 34, p.hostKeyId, Wire.KEY_ID_SIZE)
        putBytes(out, 42, p.phoneKeyId, Wire.KEY_ID_SIZE)
    }

    fun encodeAnnounce(p: Announce, out: ByteArray): Int = fixed(out, PacketType.ANNOUNCE, Wire.ANNOUNCE_SIZE) {
        out.putU32(12, p.reqId)
        putBytes(out, 16, p.hostId, Wire.ID_SIZE)
        out.putU16(32, p.flags)
        putName(out, 34, p.name, Wire.ANNOUNCE_NAME_MAX)
    }

    fun encodePairStart(p: PairStart, out: ByteArray): Int = fixed(out, PacketType.PAIR_START, Wire.PAIR_START_SIZE) {
        putBytes(out, 12, p.phoneId, Wire.ID_SIZE)
        putBytes(out, 28, p.pkP, Wire.PUBLIC_KEY_SIZE)
        putBytes(out, 93, p.commit, Wire.HASH_SIZE)
        putName(out, 125, p.name, Wire.PAIR_NAME_MAX)
    }

    fun encodePairHost(p: PairHost, out: ByteArray): Int = fixed(out, PacketType.PAIR_HOST, Wire.PAIR_HOST_SIZE) {
        out.putU32(12, p.attemptId)
        putBytes(out, 16, p.hostId, Wire.ID_SIZE)
        putBytes(out, 32, p.pkH, Wire.PUBLIC_KEY_SIZE)
        putBytes(out, 97, p.nH, Wire.NONCE_SIZE)
        putName(out, 113, p.name, Wire.PAIR_NAME_MAX)
    }

    fun encodePairReveal(p: PairReveal, out: ByteArray): Int = fixed(out, PacketType.PAIR_REVEAL, Wire.PAIR_REVEAL_SIZE) {
        out.putU32(12, p.attemptId)
        putBytes(out, 16, p.phoneId, Wire.ID_SIZE)
        putBytes(out, 32, p.nP, Wire.NONCE_SIZE)
    }

    fun encodePairAck(p: PairAck, out: ByteArray): Int = fixed(out, PacketType.PAIR_ACK, Wire.PAIR_ACK_SIZE) {
        out.putU32(12, p.attemptId)
        out.putU8(16, p.status)
    }

    fun encodePairConfirm(p: PairConfirm, out: ByteArray): Int = fixed(out, PacketType.PAIR_CONFIRM, Wire.PAIR_CONFIRM_SIZE) {
        out.putU32(12, p.attemptId)
        putBytes(out, 16, p.phoneId, Wire.ID_SIZE)
        putBytes(out, 32, p.confirmP, Wire.HASH_SIZE)
    }

    fun encodePairDone(p: PairDone, out: ByteArray): Int = fixed(out, PacketType.PAIR_DONE, Wire.PAIR_DONE_SIZE) {
        out.putU32(12, p.attemptId)
        out.putU8(16, p.status)
        if (p.status == DoneStatus.ACCEPTED) putBytes(out, 17, p.confirmH, Wire.HASH_SIZE)
    }

    /** HELLO with its mac computed under [pairKey]. */
    fun encodeHello(p: Hello, pairKey: ByteArray, out: ByteArray): Int = fixed(out, PacketType.HELLO, Wire.HELLO_SIZE) {
        putBytes(out, 12, p.phoneId, Wire.ID_SIZE)
        putBytes(out, 28, p.nP, Wire.NONCE_SIZE)
        out.putU32(44, p.caps)
        out.putU16(48, p.maxRateHz)
        out.putU16(50, 0)
        Crypto.writeTrailingMac(pairKey, out, Wire.HELLO_SIZE)
    }

    /** WELCOME with its mac computed under [pairKey]. */
    fun encodeWelcome(p: Welcome, pairKey: ByteArray, out: ByteArray): Int =
        fixed(out, PacketType.WELCOME, Wire.WELCOME_SIZE, p.sessionId) {
            putBytes(out, 12, p.hostId, Wire.ID_SIZE)
            putBytes(out, 28, p.nP, Wire.NONCE_SIZE)
            putBytes(out, 44, p.nH, Wire.NONCE_SIZE)
            out.putU8(60, p.outputMode)
            out.putU8(61, p.hostFlags)
            out.putU16(62, p.keepaliveMs)
            Crypto.writeTrailingMac(pairKey, out, Wire.WELCOME_SIZE)
        }

    /** KEY_ENROLL with its mac computed under the QR code's [token]. */
    fun encodeKeyEnroll(reqId: Int, token: ByteArray, pkP: ByteArray, name: String, out: ByteArray): Int =
        fixed(out, PacketType.KEY_ENROLL, Wire.KEY_ENROLL_SIZE) {
            out.putU32(12, reqId)
            putBytes(out, 16, QrPairing.tokenId(token), Wire.KEY_ID_SIZE)
            putBytes(out, 24, pkP, Wire.PUBLIC_KEY_SIZE)
            putName(out, 89, name, Wire.PAIR_NAME_MAX)
            putBytes(out, 121, QrPairing.enrollMac(token, out), QrPairing.MAC_SIZE)
        }

    /** KEY_ENROLL_ACK; [token] null leaves the mac zero (the PC does not know the token). */
    fun encodeKeyEnrollAck(reqId: Int, tokenId: ByteArray, status: Int, token: ByteArray?, pkP: ByteArray?, out: ByteArray): Int =
        fixed(out, PacketType.KEY_ENROLL_ACK, Wire.KEY_ENROLL_ACK_SIZE) {
            out.putU32(12, reqId)
            putBytes(out, 16, tokenId, Wire.KEY_ID_SIZE)
            out.putU8(24, status)
            if (token != null && pkP != null) putBytes(out, 25, QrPairing.ackMac(token, out, pkP), QrPairing.MAC_SIZE)
        }

    fun encodeError(p: ErrorPacket, out: ByteArray): Int = fixed(out, PacketType.ERROR, Wire.ERROR_SIZE) {
        out.putU16(12, p.code)
        out.putU8(14, p.maxVersion)
    }

    /**
     * Decodes an unsealed packet. HELLO and WELCOME macs are not checked here; use
     * [Crypto.verifyTrailingMac] on the same buffer.
     */
    fun decodePlain(buf: ByteArray, len: Int): WirePacket? {
        val type = classify(buf, len)
        if (type < 0 || PacketType.isSealed(type)) return null
        return when (type) {
            PacketType.DISCOVER -> Discover(
                buf.getI32(12), slice(buf, 16, Wire.ID_SIZE), buf.getU16(32),
                slice(buf, 34, Wire.KEY_ID_SIZE), slice(buf, 42, Wire.KEY_ID_SIZE),
            )
            PacketType.ANNOUNCE -> Announce(
                buf.getI32(12), slice(buf, 16, Wire.ID_SIZE), buf.getU16(32),
                getName(buf, 34, Wire.ANNOUNCE_NAME_MAX) ?: return null,
            )
            PacketType.PAIR_START -> PairStart(
                slice(buf, 12, Wire.ID_SIZE), slice(buf, 28, Wire.PUBLIC_KEY_SIZE), slice(buf, 93, Wire.HASH_SIZE),
                getName(buf, 125, Wire.PAIR_NAME_MAX) ?: return null,
            )
            PacketType.PAIR_HOST -> PairHost(
                buf.getI32(12), slice(buf, 16, Wire.ID_SIZE), slice(buf, 32, Wire.PUBLIC_KEY_SIZE),
                slice(buf, 97, Wire.NONCE_SIZE), getName(buf, 113, Wire.PAIR_NAME_MAX) ?: return null,
            )
            PacketType.PAIR_REVEAL -> PairReveal(buf.getI32(12), slice(buf, 16, Wire.ID_SIZE), slice(buf, 32, Wire.NONCE_SIZE))
            PacketType.PAIR_ACK -> PairAck(buf.getI32(12), buf.getU8(16))
            PacketType.PAIR_CONFIRM -> PairConfirm(buf.getI32(12), slice(buf, 16, Wire.ID_SIZE), slice(buf, 32, Wire.HASH_SIZE))
            PacketType.PAIR_DONE -> PairDone(buf.getI32(12), buf.getU8(16), slice(buf, 17, Wire.HASH_SIZE))
            PacketType.HELLO -> Hello(slice(buf, 12, Wire.ID_SIZE), slice(buf, 28, Wire.NONCE_SIZE), buf.getI32(44), buf.getU16(48))
            PacketType.WELCOME -> Welcome(
                headerSessionId(buf), slice(buf, 12, Wire.ID_SIZE), slice(buf, 28, Wire.NONCE_SIZE),
                slice(buf, 44, Wire.NONCE_SIZE), buf.getU8(60), buf.getU8(61), buf.getU16(62),
            )
            PacketType.ERROR -> ErrorPacket(buf.getU16(12), buf.getU8(14))
            PacketType.KEY_ENROLL -> KeyEnroll(
                buf.getI32(12), slice(buf, 16, Wire.KEY_ID_SIZE), slice(buf, 24, Wire.PUBLIC_KEY_SIZE),
                getName(buf, 89, Wire.PAIR_NAME_MAX) ?: return null, slice(buf, 121, QrPairing.MAC_SIZE),
            )
            PacketType.KEY_ENROLL_ACK -> KeyEnrollAck(
                buf.getI32(12), slice(buf, 16, Wire.KEY_ID_SIZE), buf.getU8(24), slice(buf, 25, QrPairing.MAC_SIZE),
            )
            else -> null
        }
    }

    // ---- sealed plaintexts ----

    fun encodeStatePlain(f: StateFrame, out: ByteArray, off: Int = 0): Int {
        out.putU32(off, f.phoneTimeUs)
        out.putU16(off + 4, f.flags)
        out.putU16(off + 6, f.buttons)
        out.putU8(off + 8, f.hat)
        out.putU8(off + 9, f.mouseButtons)
        out.putI16(off + 10, f.lx)
        out.putI16(off + 12, f.ly)
        out.putI16(off + 14, f.rx)
        out.putI16(off + 16, f.ry)
        out.putU16(off + 18, f.lt)
        out.putU16(off + 20, f.rt)
        f.keyBitmap.copyInto(out, off + 22)
        out.putI32(off + 54, f.mouseTotalX)
        out.putI32(off + 58, f.mouseTotalY)
        out.putI32(off + 62, f.wheelTotal)
        if (!f.hires) return Wire.STATE_PLAIN
        out.putI32(off + 66, f.hwheelTotal)
        return Wire.STATE_HIRES_PLAIN
    }

    /** Packet type a frame is sent as: STATE, or STATE_HIRES when [StateFrame.hires]. */
    fun stateType(f: StateFrame): Int = if (f.hires) PacketType.STATE_HIRES else PacketType.STATE

    /**
     * Decodes a STATE (or, with [hires], STATE_HIRES) plaintext into [into] (allocation-free) as
     * sent, without receiver clamping.
     */
    fun decodeStatePlain(buf: ByteArray, off: Int = 0, into: StateFrame = StateFrame(), hires: Boolean = false): StateFrame {
        into.phoneTimeUs = buf.getI32(off)
        into.flags = buf.getU16(off + 4)
        into.buttons = buf.getU16(off + 6)
        into.hat = buf.getU8(off + 8)
        into.mouseButtons = buf.getU8(off + 9)
        into.lx = buf.getI16(off + 10)
        into.ly = buf.getI16(off + 12)
        into.rx = buf.getI16(off + 14)
        into.ry = buf.getI16(off + 16)
        into.lt = buf.getU16(off + 18)
        into.rt = buf.getU16(off + 20)
        buf.copyInto(into.keyBitmap, 0, off + 22, off + 22 + StateFrame.KEY_BITMAP_SIZE)
        into.mouseTotalX = buf.getI32(off + 54)
        into.mouseTotalY = buf.getI32(off + 58)
        into.wheelTotal = buf.getI32(off + 62)
        into.hires = hires
        into.hwheelTotal = if (hires) buf.getI32(off + 66) else 0
        return into
    }

    /** Packet type of a sealed plaintext, or -1 for an unsealed packet. */
    fun sealedTypeOf(p: WirePacket): Int = when (p) {
        is StateFrame -> stateType(p)
        is Ping -> PacketType.PING
        is LinkInfo -> PacketType.LINK_INFO
        is Bye -> PacketType.BYE
        is Pong -> PacketType.PONG
        is HostStatus -> PacketType.HOST_STATUS
        is Rumble -> PacketType.RUMBLE
        is Foreground -> PacketType.FOREGROUND
        is HostBye -> PacketType.HOST_BYE
        else -> -1
    }

    /** Encodes the plaintext of a sealed packet at [off] and returns its length. */
    fun encodeSealedPlain(p: WirePacket, out: ByteArray, off: Int = 0): Int {
        val size = Wire.plainSizeOf(sealedTypeOf(p))
        require(size > 0) { "not a sealed packet: $p" }
        out.fill(0, off, off + size)
        when (p) {
            is StateFrame -> encodeStatePlain(p, out, off)
            is Ping -> out.putU64(off, p.t1)
            is Pong -> { out.putU64(off, p.t1); out.putU64(off + 8, p.t2); out.putU64(off + 16, p.t3) }
            is LinkInfo -> {
                out.putI64(off, p.clockOffsetUs)
                out.putU32(off + 8, p.rttMinUs)
                out.putU16(off + 12, p.sendRateHz)
                out.putU8(off + 14, p.rssiDbm)
                out.putU8(off + 15, p.band)
                out.putU16(off + 16, p.linkMbps)
                out.putU16(off + 18, p.freqMhz)
                out.putU8(off + 20, p.flags)
            }
            is Bye -> out.putU8(off, p.reason)
            is HostBye -> out.putU8(off, p.reason)
            is HostStatus -> {
                out.putU32(off, p.lastSeq)
                out.putU16(off + 4, p.rxPerSec)
                out.putU16(off + 6, p.lossPermille)
                out.putU32(off + 8, p.latP50Us)
                out.putU32(off + 12, p.latP99Us)
                out.putU32(off + 16, p.jitterUs)
                out.putU8(off + 20, p.outputMode)
                out.putU8(off + 21, p.flags)
            }
            is Rumble -> { out.putU8(off, p.large); out.putU8(off + 1, p.small) }
            is Foreground -> {
                // Layout: nameLen u8, reserved u8, exe[64].
                val bytes = utf8Truncated(p.exe, Wire.FOREGROUND_EXE_MAX)
                out.putU8(off, bytes.size)
                bytes.copyInto(out, off + 2)
            }
            else -> Unit
        }
        return size
    }

    /** Decodes the plaintext of a sealed [type] of [len] bytes, or null if it does not fit. */
    fun decodeSealedPlain(type: Int, buf: ByteArray, off: Int, len: Int): WirePacket? {
        if (len != Wire.plainSizeOf(type)) return null
        return when (type) {
            PacketType.STATE -> decodeStatePlain(buf, off)
            PacketType.STATE_HIRES -> decodeStatePlain(buf, off, hires = true)
            PacketType.PING -> Ping(buf.getU64(off))
            PacketType.PONG -> Pong(buf.getU64(off), buf.getU64(off + 8), buf.getU64(off + 16))
            PacketType.LINK_INFO -> LinkInfo(
                buf.getI64(off), buf.getI32(off + 8), buf.getU16(off + 12), buf.getI8(off + 14), buf.getU8(off + 15),
                buf.getU16(off + 16), buf.getU16(off + 18), buf.getU8(off + 20),
            )
            PacketType.BYE -> Bye(buf.getU8(off))
            PacketType.HOST_BYE -> HostBye(buf.getU8(off))
            PacketType.HOST_STATUS -> HostStatus(
                buf.getI32(off), buf.getU16(off + 4), buf.getU16(off + 6), buf.getI32(off + 8), buf.getI32(off + 12),
                buf.getI32(off + 16), buf.getU8(off + 20), buf.getU8(off + 21),
            )
            PacketType.RUMBLE -> Rumble(buf.getU8(off), buf.getU8(off + 1))
            PacketType.FOREGROUND -> {
                val n = buf.getU8(off)
                if (n > Wire.FOREGROUND_EXE_MAX) null else Foreground(String(buf, off + 2, n, Charsets.UTF_8))
            }
            else -> null
        }
    }

    // ---- helpers ----

    /** UTF-8 bytes of [s], cut on a code-point boundary to at most [max] bytes. */
    fun utf8Truncated(s: String, max: Int): ByteArray {
        val all = s.toByteArray(Charsets.UTF_8)
        if (all.size <= max) return all
        var n = max
        // Back up over continuation bytes (10xxxxxx) so the cut lands before a lead byte.
        while (n > 0 && (all[n].toInt() and 0xC0) == 0x80) n--
        return all.copyOf(n)
    }

    private inline fun fixed(out: ByteArray, type: Int, size: Int, sessionId: Int = 0, body: () -> Unit): Int {
        out.fill(0, 0, size)
        writeHeader(out, type, sessionId, 0)
        body()
        return size
    }

    private fun putBytes(out: ByteArray, off: Int, src: ByteArray, size: Int) {
        require(src.size == size) { "field needs $size bytes, got ${src.size}" }
        src.copyInto(out, off)
    }

    private fun putName(out: ByteArray, off: Int, name: String, max: Int) {
        val bytes = utf8Truncated(name, max)
        out.putU8(off, bytes.size)
        bytes.copyInto(out, off + 1)
    }

    private fun getName(buf: ByteArray, off: Int, max: Int): String? {
        val n = buf.getU8(off)
        if (n > max) return null
        return String(buf, off + 1, n, Charsets.UTF_8)
    }

    private fun slice(buf: ByteArray, off: Int, size: Int): ByteArray = buf.copyOfRange(off, off + size)
}
