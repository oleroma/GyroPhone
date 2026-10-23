package com.oleroma.gyrophone.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.util.Log
import com.oleroma.gyrophone.core.net.NetAddresses
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** The phone's Wi-Fi radio as last reported; null fields are unknown. */
data class WifiRadioInfo(val rssiDbm: Int? = null, val linkMbps: Int? = null, val freqMhz: Int? = null) {
    /** 2, 5 or 6 (GHz), 0 if unknown. */
    val band: Int get() = when (freqMhz) {
        null -> 0
        in 2400..2500 -> 2
        in 4900..5900 -> 5
        in 5925..7125 -> 6
        else -> 0
    }
}

/**
 * Tracks the Wi-Fi network and binds the link's socket to it, so LAN and broadcast traffic
 * leaves through Wi-Fi even when the default network is mobile data. [onChanged] runs (on a
 * ConnectivityManager thread) when the network or its IPv4 addresses change, so the link can
 * rebind and send a fresh HELLO.
 */
class WifiNetworkBinder(context: Context, private val onChanged: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var network: Network? = null
        private set
    @Volatile var info = WifiRadioInfo()
        private set
    /** The subnet-directed broadcast address of the Wi-Fi network (e.g. 192.168.1.255). */
    @Volatile var broadcast: InetAddress? = null
        private set

    @Volatile private var socket: DatagramSocket? = null
    private var registered = false
    /** The IPv4 addresses at the last callback ([NetAddresses.ipv4Key]); null until the first one. */
    private var lastAddresses: String? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = network != this@WifiNetworkBinder.network
            this@WifiNetworkBinder.network = network
            if (changed) lastAddresses = null
            bind()
            if (changed) onChanged()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != this@WifiNetworkBinder.network) return
            val wi = caps.transportInfo as? WifiInfo ?: return
            val tx = wi.txLinkSpeedMbps
            info = WifiRadioInfo(
                rssiDbm = wi.rssi.takeIf { it > -127 && it < 0 },
                linkMbps = (if (tx > 0) tx else wi.linkSpeed).takeIf { it > 0 },
                freqMhz = wi.frequency.takeIf { it > 0 },
            )
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (network != this@WifiNetworkBinder.network) return
            broadcast = directedBroadcast(lp)
            val addresses = NetAddresses.ipv4Key(lp.linkAddresses.map { it.address to it.prefixLength })
            val changed = lastAddresses != null && addresses != lastAddresses
            lastAddresses = addresses
            if (changed) {
                bind()
                onChanged()
            }
        }

        override fun onLost(network: Network) {
            if (network != this@WifiNetworkBinder.network) return
            this@WifiNetworkBinder.network = null
            info = WifiRadioInfo()
            broadcast = null
            lastAddresses = null
            onChanged()
        }
    }

    fun register(socket: DatagramSocket) {
        this.socket = socket
        if (registered || cm == null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // A LAN router without internet access is still a fine place for the PC.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "network callback refused", e)
        }
    }

    fun unregister() {
        if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
        socket = null
        network = null
        info = WifiRadioInfo()
        broadcast = null
        lastAddresses = null
    }

    private fun bind() {
        val n = network ?: return
        val s = socket ?: return
        try {
            n.bindSocket(s)
        } catch (e: IOException) {
            Log.w(TAG, "could not bind the socket to Wi-Fi", e)
        }
    }

    private fun directedBroadcast(lp: LinkProperties): InetAddress? {
        for (la in lp.linkAddresses) {
            val a = la.address as? Inet4Address ?: continue
            val prefix = la.prefixLength
            if (prefix !in 1..30) continue
            val b = a.address
            val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val host = -1 ushr prefix
            val bc = ip or host
            return InetAddress.getByAddress(
                byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()),
            )
        }
        return null
    }

    companion object {
        private const val TAG = "WifiNetworkBinder"
    }
}
