package com.oleroma.gyrophone.core.net

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM for sealed session packets: `header(12) || ciphertext || tag(16)`, the header is
 * the AAD and the nonce is `u32le(dir) || u32le(sessionId) || u32le(seq)` taken from the header.
 * Not thread-safe: use one instance per thread. Replay (seq) checks belong to the caller.
 */
class SessionCipher(key: ByteArray, private val dir: Int) {
    private val key = SecretKeySpec(key.copyOf(), "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12)

    /**
     * Seals [plainLen] bytes of [plain] under [header] (12 bytes, already carrying type, sid and
     * seq) into [out], which must hold `12 + plainLen + 16` bytes. Returns the packet length.
     */
    fun seal(header: ByteArray, plain: ByteArray, plainLen: Int, out: ByteArray): Int {
        header.copyInto(out, 0, 0, Wire.HEADER)
        setNonce(header)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header, 0, Wire.HEADER)
        val n = cipher.doFinal(plain, 0, plainLen, out, Wire.HEADER)
        return Wire.HEADER + n
    }

    /**
     * Opens a sealed packet of [len] bytes into [outPlain] (`len - 28` bytes). Returns false
     * on a short packet or a failed tag; [outPlain] content is then undefined.
     */
    fun open(buf: ByteArray, len: Int, outPlain: ByteArray): Boolean {
        if (len < Wire.HEADER + Wire.GCM_TAG || len > buf.size) return false
        if (outPlain.size < len - Wire.HEADER - Wire.GCM_TAG) return false
        setNonce(buf)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(buf, 0, Wire.HEADER)
            cipher.doFinal(buf, Wire.HEADER, len - Wire.HEADER, outPlain, 0)
            true
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    private fun setNonce(header: ByteArray) {
        nonce.putU32(0, dir)
        header.copyInto(nonce, 4, 4, Wire.HEADER) // sessionId and seq, already little-endian
    }

    companion object {
        private const val TAG_BITS = Wire.GCM_TAG * 8
    }
}
