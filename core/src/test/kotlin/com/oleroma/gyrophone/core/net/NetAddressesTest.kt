package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.InetAddress

class NetAddressesTest {
    private fun a(ip: String, prefix: Int) = InetAddress.getByName(ip) to prefix

    @Test fun ipv6ChurnKeepsTheKey() {
        val v4 = a("192.168.1.23", 24)
        val base = NetAddresses.ipv4Key(listOf(a("fe80::1", 64), v4, a("2001:db8::aa", 64)))
        assertEquals("192.168.1.23/24", base)
        // A new temporary address, the old one dropped, SLAAC adding a global one later.
        assertEquals(base, NetAddresses.ipv4Key(listOf(a("fe80::1", 64), v4, a("2001:db8::bb", 64))))
        assertEquals(base, NetAddresses.ipv4Key(listOf(v4)))
        assertEquals(base, NetAddresses.ipv4Key(listOf(a("2001:db8::cc", 64), v4)))
    }

    @Test fun ipv4ChangesAlterTheKey() {
        val base = NetAddresses.ipv4Key(listOf(a("192.168.1.23", 24)))
        assertNotEquals(base, NetAddresses.ipv4Key(listOf(a("192.168.1.24", 24))))
        assertNotEquals(base, NetAddresses.ipv4Key(listOf(a("192.168.1.23", 16))))
        assertNotEquals(base, NetAddresses.ipv4Key(listOf(a("fe80::1", 64))))
        assertEquals("", NetAddresses.ipv4Key(emptyList()))
        assertEquals(
            NetAddresses.ipv4Key(listOf(a("10.0.0.2", 8), a("192.168.1.23", 24))),
            NetAddresses.ipv4Key(listOf(a("192.168.1.23", 24), a("10.0.0.2", 8))),
        )
    }
}
