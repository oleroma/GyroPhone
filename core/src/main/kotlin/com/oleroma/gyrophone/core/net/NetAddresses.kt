package com.oleroma.gyrophone.core.net

import java.net.Inet4Address
import java.net.InetAddress

object NetAddresses {
    /**
     * The IPv4 addresses (with prefix lengths) of a network as one comparable key. IPv6 addresses
     * are left out: temporary addresses come and go while the IPv4 path the session uses stays.
     */
    fun ipv4Key(addresses: List<Pair<InetAddress, Int>>): String =
        addresses.filter { it.first is Inet4Address }
            .map { "${it.first.hostAddress}/${it.second}" }
            .sorted()
            .joinToString(",")
}
