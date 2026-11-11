package com.oleroma.gyrophone.wifi

import com.oleroma.gyrophone.core.net.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * A direct link to the PC that isn't the Wi-Fi network: the address the phone's sockets bind to there
 * and where DISCOVER goes. For USB tethering and the phone's own hotspot ([hotspot]) that is the phone's
 * IPv4 address on that interface and the subnet's broadcast; for the direct cable ([direct],
 * [AccessoryBridge]) loopback and the bridge's socket.
 */
data class UsbTetherInfo(
    val name: String,
    val address: InetAddress,
    val target: InetSocketAddress,
    val direct: Boolean = false,
    val hotspot: Boolean = false,
)

/**
 * Finds USB tethering (Settings → Hotspot & tethering → USB tethering, or the developer option
 * "Default USB configuration → USB tethering") and the phone's own Wi-Fi hotspot with the PC on it.
 * Neither is an Android network the app can request: the phone is the router there, so the interface
 * is found by name (rndis*, ncm*, usb* for USB; ap*, swlan*, softap*, wlan* for the hotspot) with an
 * IPv4 address. Traffic to its subnet goes out through it from an unbound socket.
 */
object UsbTether {
    private val NAMES = Regex("^(rndis|ncm|usb)\\d*$")
    private val HOTSPOT_NAMES = Regex("^(ap|swlan|softap|wlan)\\d*$")

    /**
     * USB tethering now, else the hotspot, or null when there is neither. [wifiInterface] is the
     * Wi-Fi network's own interface, never taken for the hotspot; with [hotspot] false (a Wi-Fi network
     * whose interface isn't known yet) only USB is looked for. Cheap enough for a check every second.
     */
    fun find(wifiInterface: String? = null, hotspot: Boolean = true): UsbTetherInfo? =
        find(NAMES, null, hotspot = false) ?: if (hotspot) find(HOTSPOT_NAMES, wifiInterface, hotspot = true) else null

    private fun find(names: Regex, skip: String?, hotspot: Boolean): UsbTetherInfo? {
        val all = try {
            NetworkInterface.getNetworkInterfaces() ?: return null
        } catch (e: SocketException) {
            return null
        }
        for (ni in all) {
            if (!names.matches(ni.name) || ni.name == skip) continue
            val up = try {
                ni.isUp && !ni.isLoopback
            } catch (e: SocketException) {
                false
            }
            if (!up) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val bc = ia.broadcast ?: directed(a, ia.networkPrefixLength.toInt()) ?: continue
                return UsbTetherInfo(ni.name, a, InetSocketAddress(bc, Wire.PORT), hotspot = hotspot)
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
