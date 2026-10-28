package com.oleroma.gyrophone.wifi

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** The phone's USB tethering link: its own IPv4 address there and that subnet's broadcast address. */
data class UsbTetherInfo(val name: String, val address: InetAddress, val broadcast: InetAddress)

/**
 * Finds USB tethering (Settings → Hotspot & tethering → USB tethering, or the developer option
 * "Default USB configuration → USB tethering"). It is no Android network the app can request: the
 * phone is the router there, so the interface is found by name (rndis*, ncm*, usb*) with an IPv4
 * address. Traffic to its subnet goes out through it from an unbound socket.
 */
object UsbTether {
    private val NAMES = Regex("^(rndis|ncm|usb)\\d*$")

    /** The USB tether now, or null when there is none. Cheap enough for a check every second. */
    fun find(): UsbTetherInfo? {
        val all = try {
            NetworkInterface.getNetworkInterfaces() ?: return null
        } catch (e: SocketException) {
            return null
        }
        for (ni in all) {
            if (!NAMES.matches(ni.name)) continue
            val up = try {
                ni.isUp && !ni.isLoopback
            } catch (e: SocketException) {
                false
            }
            if (!up) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val bc = ia.broadcast ?: directed(a, ia.networkPrefixLength.toInt()) ?: continue
                return UsbTetherInfo(ni.name, a, bc)
            }
        }
        return null
    }

    private fun directed(a: Inet4Address, prefix: Int): InetAddress? {
        if (prefix !in 1..30) return null
        val b = a.address
        val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val bc = ip or (-1 ushr prefix)
        return InetAddress.getByAddress(
            byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()),
        )
    }
}
