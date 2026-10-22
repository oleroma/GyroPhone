package com.oleroma.gyrophone.wifi

import android.content.Context
import android.os.FileObserver
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.oleroma.gyrophone.core.net.KeyPairing
import com.oleroma.gyrophone.core.net.P256
import com.oleroma.gyrophone.core.net.PubKeyFile
import com.oleroma.gyrophone.core.net.StaticKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/** A PC key file in `keys/trusted_hosts/`. */
class TrustedHost(val file: File, val key: PubKeyFile) {
    val name: String get() = key.name.ifBlank { file.nameWithoutExtension }
    val keyIdHex: String = key.keyId.toHex()
}

/**
 * This phone's side of key file pairing, in the app's external files folder
 * (`/sdcard/Android/data/com.oleroma.gyrophone/files/keys/`, reachable over adb):
 * `phone.pub` is this phone's public key (the private key lives in Android Keystore and never
 * leaves it), and every `.pub` in `trusted_hosts/` is a PC this phone trusts.
 */
class PhoneKeys(context: Context, private val phoneName: () -> String) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "keys")
    val trustedDir = File(root, "trusted_hosts")
    val publicFile = File(root, "phone.pub")

    /** The Keystore key, created on first use; null if the Keystore refuses. */
    val key: StaticKey? by lazy {
        try {
            KeystoreStaticKey()
        } catch (e: Exception) {
            Log.e(TAG, "no Keystore key for key pairing", e)
            null
        }
    }

    private val _fingerprint = MutableStateFlow<String?>(null)
    /** This phone's key fingerprint, once the key exists. */
    val fingerprint: StateFlow<String?> = _fingerprint

    private val _trusted = MutableStateFlow<List<TrustedHost>>(emptyList())
    /** Trusted PCs, by file name. */
    val trusted: StateFlow<List<TrustedHost>> = _trusted

    private var signature = ""
    private var observer: FileObserver? = null

    /** Creates the key if needed and (re)writes phone.pub when it is missing or out of date. Slow: not on the UI thread. */
    @Synchronized fun publish() {
        val k = key ?: return
        _fingerprint.value = KeyPairing.fingerprint(k.publicKey)
        try {
            trustedDir.mkdirs()
            val line = PubKeyFile(k.publicKey, phoneName()).format()
            if (!publicFile.exists() || publicFile.readText() != line) writeAtomic(publicFile, line)
        } catch (e: IOException) {
            Log.w(TAG, "could not write phone.pub", e)
        }
        rescan()
    }

    /** One line to share: what phone.pub holds. */
    fun publicLine(): String? = key?.let { PubKeyFile(it.publicKey, phoneName()).format() }

    /** Reads trusted_hosts again; true if the list changed. Cheap when nothing changed. */
    @Synchronized fun rescan(): Boolean {
        val files = trustedDir.listFiles { f -> f.isFile && f.name.endsWith(".pub") }?.sortedBy { it.name } ?: emptyList()
        val sig = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (sig == signature) return false
        signature = sig
        val list = files.mapNotNull { f ->
            val text = try {
                f.readText()
            } catch (e: IOException) {
                return@mapNotNull null
            }
            PubKeyFile.parse(text)?.let { TrustedHost(f, it) }
        }.distinctBy { it.keyIdHex }
        _trusted.value = list
        return true
    }

    /** Adds a PC key from a host.pub's text; returns an error message, or null when it was added. */
    fun import(text: String): String? {
        val k = PubKeyFile.parse(text) ?: return "That is not a GyroPhone key file (host.pub)"
        val id = k.keyId.toHex()
        if (_trusted.value.any { it.keyIdHex == id }) return null
        return try {
            trustedDir.mkdirs()
            var f = File(trustedDir, KeyPairing.fileName(k.name.ifBlank { "PC" }))
            var n = 2
            while (f.exists()) f = File(trustedDir, KeyPairing.fileName("${k.name.ifBlank { "PC" }} $n").also { n++ })
            writeAtomic(f, k.format())
            rescan()
            null
        } catch (e: IOException) {
            Log.w(TAG, "import failed", e)
            "Could not save the key"
        }
    }

    fun remove(host: TrustedHost) {
        if (!host.file.delete()) Log.w(TAG, "could not delete ${host.file.name}")
        rescan()
    }

    /** Calls [onChange] (on the observer's thread) after files in trusted_hosts change. */
    @Synchronized fun startWatching(onChange: () -> Unit) {
        if (observer != null) return
        trustedDir.mkdirs()
        observer = object : FileObserver(trustedDir, CREATE or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) = onChange()
        }.also { it.startWatching() }
    }

    @Synchronized fun stopWatching() {
        observer?.stopWatching()
        observer = null
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("rename to ${f.name} failed")
        }
    }

    private companion object {
        const val TAG = "PhoneKeys"
    }
}

/** P-256 key agreement key in Android Keystore (non-exportable). */
private class KeystoreStaticKey : StaticKey {
    private val privateKey: PrivateKey
    override val publicKey: ByteArray

    init {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            gen.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            gen.generateKeyPair()
        }
        privateKey = ks.getKey(ALIAS, null) as PrivateKey
        publicKey = P256.encodePublic(ks.getCertificate(ALIAS).publicKey)
    }

    override fun agree(peer: ECPublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE)
        ka.init(privateKey)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "gyrophone.wifi.identity"
    }
}
