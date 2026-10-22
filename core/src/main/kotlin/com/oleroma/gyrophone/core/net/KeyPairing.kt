package com.oleroma.gyrophone.core.net

import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * A long-lived P-256 key whose private half may never leave its store (Android Keystore on the
 * phone). Only key agreement is needed.
 */
interface StaticKey {
    /** 65-byte SEC1 uncompressed public key. */
    val publicKey: ByteArray

    /** ECDH with [peer]: the 32-byte X coordinate. */
    fun agree(peer: ECPublicKey): ByteArray
}

/** A [StaticKey] held in memory, for tests and vectors. */
class SoftwareStaticKey(private val pair: KeyPair) : StaticKey {
    override val publicKey: ByteArray = P256.encodePublic(pair.public)

    override fun agree(peer: ECPublicKey): ByteArray = P256.ecdh(pair.private, peer)
}

/** One `.pub` file: `gyrophone-p256 <base64 SEC1 key> <name>`. */
class PubKeyFile(val publicKey: ByteArray, val name: String) {
    val keyId: ByteArray get() = KeyPairing.keyId(publicKey)
    val fingerprint: String get() = KeyPairing.fingerprint(publicKey)

    /** The file's single line, with a trailing newline. */
    fun format(): String = KeyPairing.TAG + " " + Base64.getEncoder().encodeToString(publicKey) + " " + name + "\n"

    companion object {
        /** Parses a key file (first non-blank line); null unless it holds a valid P-256 point. */
        fun parse(text: String): PubKeyFile? {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val parts = line.split(Regex("\\s+"), limit = 3)
            if (parts.size < 2 || parts[0] != KeyPairing.TAG) return null
            val key = try {
                Base64.getDecoder().decode(parts[1])
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (P256.decodePublic(key) == null) return null
            return PubKeyFile(key, parts.getOrNull(2)?.trim().orEmpty())
        }
    }
}

/**
 * Key file pairing: each side has a static P-256 key and a folder of the other side's public
 * keys. Nothing secret is copied, and no packet is added: the pairing key both sides derive from
 * their static keys feeds the usual HELLO/WELCOME, under a phone id derived from both keys.
 *
 * - `ss = ECDH(phone, host)`
 * - `pairKey = HKDF(ikm = ss, salt = SHA-256("GP-key-v1" || pkP || pkH), info = "GP-key-pair-v1", 32)`
 * - `keyPhoneId = SHA-256("GP-key-phone-id-v1" || pkP || pkH)[0..16]`
 *
 * DISCOVER names the PC key the phone trusts and the phone's own key ([keyId]); ANNOUNCE answers
 * with [AnnounceFlags.KEY_MATCH] and [AnnounceFlags.KNOWS_YOUR_KEY]. Must match KeyPairing.cs.
 */
object KeyPairing {
    const val TAG = "gyrophone-p256"

    /** First 8 bytes of SHA-256 of a public key: what DISCOVER carries. */
    fun keyId(publicKey: ByteArray): ByteArray = Crypto.sha256(publicKey).copyOf(Wire.KEY_ID_SIZE)

    /** Short display form of a key, e.g. `SHA256:3f9a…7c21`. */
    fun fingerprint(publicKey: ByteArray): String {
        val h = Crypto.sha256(publicKey).toHexString()
        return "SHA256:" + h.substring(0, 4) + "…" + h.substring(h.length - 4)
    }

    fun salt(pkP: ByteArray, pkH: ByteArray): ByteArray = Crypto.sha256(Crypto.ascii("GP-key-v1"), pkP, pkH)

    fun pairKey(ss: ByteArray, pkP: ByteArray, pkH: ByteArray): ByteArray =
        Hkdf.derive(ss, salt(pkP, pkH), Crypto.ascii("GP-key-pair-v1"), 32)

    /** The phone id a key-paired phone uses in HELLO with this PC. */
    fun keyPhoneId(pkP: ByteArray, pkH: ByteArray): ByteArray =
        Crypto.sha256(Crypto.ascii("GP-key-phone-id-v1"), pkP, pkH).copyOf(Wire.ID_SIZE)

    /** Phone side: (keyPhoneId, pairKey) for a trusted PC key, or null if the key is invalid. */
    fun phoneSecrets(phone: StaticKey, hostKey: ByteArray): Pair<ByteArray, ByteArray>? {
        val peer = P256.decodePublic(hostKey) ?: return null
        val ss = phone.agree(peer)
        return keyPhoneId(phone.publicKey, hostKey) to pairKey(ss, phone.publicKey, hostKey)
    }

    /** What one ANNOUNCE, answering a DISCOVER that named a trusted PC key, says. */
    fun status(announceFlags: Int): KeyStatus {
        val match = announceFlags and AnnounceFlags.KEY_MATCH != 0
        val knows = announceFlags and AnnounceFlags.KNOWS_YOUR_KEY != 0
        return when {
            match && knows -> KeyStatus.READY
            match -> KeyStatus.PC_LACKS_PHONE
            knows -> KeyStatus.PHONE_LACKS_PC
            else -> KeyStatus.NONE
        }
    }

    /** A file name for a key named [name]: letters, digits, space, dot, dash and underscore. */
    fun fileName(name: String): String {
        val clean = name.map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }.joinToString("").trim().trim('.')
        return (clean.ifEmpty { "key" }).take(48) + ".pub"
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Key pairing between this phone and one PC, as seen from ANNOUNCE. */
enum class KeyStatus {
    /** No key relation (or the PC predates key pairing). */
    NONE,
    /** Both sides know each other: connect without a prompt. */
    READY,
    /** The phone trusts the PC's key, but the PC does not know this phone. */
    PC_LACKS_PHONE,
    /** The PC knows this phone, but the phone does not trust the PC's key. */
    PHONE_LACKS_PC,
}
