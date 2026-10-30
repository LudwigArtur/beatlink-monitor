package com.beatlinkmonitor.api

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalClientTest {
    private val own = setOf(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("fe80::1"))
    private fun check(peer: String?, host: String?, headers: List<String> = listOf("Host", "Accept")) =
        LocalClient.isLocal(peer, host, headers) { own }

    @Test
    fun `loopback and the machine's own addresses are local`() {
        assertTrue(check("127.0.0.1", "127.0.0.1:17812"))
        assertTrue(check("::1", "localhost:17812"))
        assertTrue(check("0:0:0:0:0:0:0:1", "[::1]:17812"))
        assertTrue(check("192.168.1.20", "192.168.1.20:17812"))
        assertTrue(check("fe80::1%en0", "[fe80::1]:17812"))
    }

    @Test
    fun `other devices on the network are not local`() {
        assertFalse(check("192.168.1.55", "192.168.1.20:17812"))
        assertFalse(check("10.0.0.2", "localhost:17812"))
        assertFalse(check(null, "localhost:17812"))
        assertFalse(check("not-an-ip", "localhost:17812"))
    }

    @Test
    fun `proxied requests and foreign host names are not local`() {
        assertFalse(check("127.0.0.1", "127.0.0.1:17812", listOf("Host", "x-forwarded-for")))
        assertFalse(check("127.0.0.1", "127.0.0.1:17812", listOf("Forwarded")))
        assertFalse(check("127.0.0.1", "evil.example.com:17812"))
        assertFalse(check("127.0.0.1", "localhost.evil.com"))
        assertFalse(check("127.0.0.1", null))
    }
}
