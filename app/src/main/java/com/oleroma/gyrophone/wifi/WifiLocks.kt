package com.oleroma.gyrophone.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Holds a WIFI_MODE_FULL_LOW_LATENCY lock while a session is live: it keeps the radio out of
 * power save, which otherwise batches our packets behind the AP's beacon interval. Android only
 * honours it while the screen is on and GyroPhone is in front, which the Wi-Fi panel says.
 */
class WifiLocks(context: Context) {
    private val lock: WifiManager.WifiLock? = runCatching {
        context.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GyroPhone:low-latency")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    val held: Boolean get() = lock?.isHeld == true

    @Synchronized fun acquire() {
        val l = lock ?: return
        if (l.isHeld) return
        try {
            l.acquire()
        } catch (e: RuntimeException) {
            Log.w(TAG, "low-latency Wi-Fi lock refused", e)
        }
    }

    @Synchronized fun release() {
        val l = lock ?: return
        if (l.isHeld) l.release()
    }

    companion object {
        private const val TAG = "WifiLocks"
    }
}
