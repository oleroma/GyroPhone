package com.oleroma.gyrophone.wifi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.oleroma.gyrophone.core.net.Crypto
import com.oleroma.gyrophone.core.net.PairedHost
import com.oleroma.gyrophone.core.net.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A paired PC as persisted. The pairing key never touches storage in the clear: it is wrapped
 * with an Android Keystore AES-256-GCM key. [lastAddr] / [lastPort] are where the PC last
 * answered from, for the first HELLO of a reconnect.
 */
@Serializable
data class StoredHost(
    val hostIdHex: String,
    val name: String,
    val wrappedKeyB64: String,
    val wrapIvB64: String,
    val lastAddr: String? = null,
    val lastPort: Int = Wire.PORT,
    val lastUsedMs: Long = 0,
)

/** This phone's Wi-Fi identity and its paired PCs (SharedPreferences `gyrophone_wifi`). */
class WifiPairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("gyrophone_wifi", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(StoredHost.serializer())

    /** 16 random bytes created once; the PC knows this phone by it. */
    val phoneId: ByteArray = loadOrCreatePhoneId()

    private val _hosts = MutableStateFlow(load())
    /** Paired PCs, most recently used first. */
    val hosts: StateFlow<List<StoredHost>> = _hosts

    fun find(hostIdHex: String): StoredHost? = _hosts.value.firstOrNull { it.hostIdHex == hostIdHex }

    /** The PC to reconnect to on start: the most recently used one. */
    fun mostRecent(): StoredHost? = _hosts.value.firstOrNull()

    /** Stores a freshly paired PC (replacing an older pairing with the same PC). */
    @Synchronized fun save(host: PairedHost, nowMs: Long = System.currentTimeMillis()): Boolean {
        val (wrapped, iv) = try {
            wrap(host.pairKey)
        } catch (e: Exception) {
            Log.e(TAG, "could not wrap the pairing key", e)
            return false
        }
        val id = host.hostId.toHex()
        val entry = StoredHost(
            hostIdHex = id,
            name = host.name,
            wrappedKeyB64 = b64(wrapped),
            wrapIvB64 = b64(iv),
            lastAddr = host.addr?.address?.hostAddress,
            lastPort = host.addr?.port ?: Wire.PORT,
            lastUsedMs = nowMs,
        )
        persist(listOf(entry) + _hosts.value.filter { it.hostIdHex != id })
        return true
    }

    @Synchronized fun updateAddress(hostIdHex: String, addr: InetSocketAddress) {
        val ip = addr.address?.hostAddress ?: return
        val list = _hosts.value
        val old = list.firstOrNull { it.hostIdHex == hostIdHex } ?: return
        if (old.lastAddr == ip && old.lastPort == addr.port) return
        persist(list.map { if (it.hostIdHex == hostIdHex) it.copy(lastAddr = ip, lastPort = addr.port) else it })
    }

    /** Marks a PC as just used, which moves it to the front. */
    @Synchronized fun touch(hostIdHex: String, nowMs: Long = System.currentTimeMillis()) {
        val list = _hosts.value
        val old = list.firstOrNull { it.hostIdHex == hostIdHex } ?: return
        persist(listOf(old.copy(lastUsedMs = nowMs)) + list.filter { it.hostIdHex != hostIdHex })
    }

    @Synchronized fun forget(hostIdHex: String) {
        persist(_hosts.value.filter { it.hostIdHex != hostIdHex })
    }

    /** The stored PC with its pairing key unwrapped, or null if the Keystore refuses (key lost). */
    fun unwrap(stored: StoredHost, addrOverride: InetSocketAddress? = null): PairedHost? {
        val key = try {
            unwrapKey(Base64.getDecoder().decode(stored.wrappedKeyB64), Base64.getDecoder().decode(stored.wrapIvB64))
        } catch (e: Exception) {
            Log.e(TAG, "could not unwrap the pairing key of ${stored.name}", e)
            return null
        }
        val addr = addrOverride ?: stored.lastAddr?.let { ip ->
            // A numeric literal: getByName does not touch DNS.
            runCatching { InetSocketAddress(InetAddress.getByName(ip), stored.lastPort) }.getOrNull()
        }
        return PairedHost(hexToBytes(stored.hostIdHex), stored.name, key, addr)
    }

    private fun load(): List<StoredHost> {
        val text = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
            .sortedByDescending { it.lastUsedMs }
    }

    private fun persist(list: List<StoredHost>) {
        prefs.edit().putString(KEY_HOSTS, json.encodeToString(listSerializer, list)).apply()
        _hosts.value = list
    }

    private fun loadOrCreatePhoneId(): ByteArray {
        prefs.getString(KEY_PHONE_ID, null)?.let { hex ->
            runCatching { hexToBytes(hex) }.getOrNull()?.takeIf { it.size == Wire.ID_SIZE }?.let { return it }
        }
        val id = Crypto.randomBytes(Wire.ID_SIZE)
        prefs.edit().putString(KEY_PHONE_ID, id.toHex()).commit()
        return id
    }

    // ---- Keystore wrapping ----

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    /** Returns (ciphertext with tag, iv); the Keystore picks the IV. */
    private fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.doFinal(plain) to c.iv
    }

    private fun unwrapKey(wrapped: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
        return c.doFinal(wrapped)
    }

    companion object {
        private const val TAG = "WifiPairingStore"
        private const val KEY_PHONE_ID = "phoneId"
        private const val KEY_HOSTS = "hosts"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_ALIAS = "gyrophone.wifi.wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex length" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
    }
}

private const val HEX = "0123456789abcdef"
