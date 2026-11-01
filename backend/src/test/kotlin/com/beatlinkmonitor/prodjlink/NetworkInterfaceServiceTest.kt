package com.beatlinkmonitor.prodjlink

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterfaceServiceTest {
    private val v4 = listOf("169.254.144.185")

    @Test
    fun `ethernet adapters and vlans with an IPv4 address are usable`() {
        listOf("en13", "en0", "vlan0", "eth0", "wlan0", "bridge100", "apple0").forEach {
            assertTrue(NetworkInterfaceService.isUsable(it, v4), it)
        }
    }

    @Test
    fun `tunnels and other virtual macOS interfaces are not`() {
        listOf("utun7", "utun0", "awdl0", "llw0", "gif0", "stf0", "anpi0", "ap1").forEach {
            assertFalse(NetworkInterfaceService.isUsable(it, v4), it)
        }
    }

    @Test
    fun `an interface without an IPv4 address is not usable`() {
        assertFalse(NetworkInterfaceService.isUsable("en13", listOf("fe80:0:0:0:1:2:3:4%en13")))
        assertFalse(NetworkInterfaceService.isUsable("en13", emptyList()))
    }

    @Test
    fun `a name that merely starts with a virtual prefix is still usable`() {
        assertTrue(NetworkInterfaceService.isUsable("apple0", v4))
        assertTrue(NetworkInterfaceService.isUsable("gifted1", v4))
    }
}
