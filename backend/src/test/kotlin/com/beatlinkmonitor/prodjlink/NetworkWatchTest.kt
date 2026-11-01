package com.beatlinkmonitor.prodjlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkWatchTest {
    private var now = 1_000_000L
    private var addresses: List<String>? = listOf("169.254.144.185", "fe80::1")
    private val watch = NetworkWatch({ now }, { addresses })

    @Test
    fun `nothing is reported while Pro DJ Link is not running`() {
        val health = watch.snapshot()

        assertFalse(health.addressChanged)
        assertEquals(null, health.interfaceName)
    }

    @Test
    fun `the bound address still being on the interface is healthy`() {
        watch.bind("en13", "169.254.144.185")

        val health = watch.snapshot()

        assertFalse(health.addressChanged)
        assertEquals("en13", health.interfaceName)
        assertEquals("169.254.144.185", health.boundAddress)
    }

    @Test
    fun `a new address on the interface is reported as changed`() {
        watch.bind("en13", "169.254.144.185")
        addresses = listOf("169.254.7.9")

        val health = watch.snapshot()

        assertTrue(health.addressChanged)
        assertEquals(listOf("169.254.7.9"), health.currentAddresses)
    }

    @Test
    fun `a vanished interface or one without addresses is reported as changed`() {
        watch.bind("en13", "169.254.144.185")

        addresses = null
        assertTrue(watch.snapshot().addressChanged)

        addresses = emptyList()
        assertTrue(watch.snapshot().addressChanged)
    }

    @Test
    fun `the address coming back clears the report and scope ids are ignored`() {
        watch.bind("en13", "fe80::1%en13")
        addresses = listOf("169.254.7.9")
        assertTrue(watch.snapshot().addressChanged)

        addresses = listOf("fe80::1")
        assertFalse(watch.snapshot().addressChanged)
    }

    @Test
    fun `dropouts are counted for ten minutes only`() {
        watch.bind("en13", "169.254.144.185")
        repeat(3) { watch.recordDropout() }
        assertEquals(3, watch.snapshot().deviceDropouts)

        now += NetworkWatch.WINDOW_MS - 1_000
        watch.recordDropout()
        assertEquals(4, watch.snapshot().deviceDropouts)

        now += 2_000
        assertEquals(1, watch.snapshot().deviceDropouts)
    }

    @Test
    fun `binding again starts a fresh count and the player lists are sorted`() {
        watch.bind("en13", "169.254.144.185")
        watch.recordDropout()

        watch.bind("en13", "169.254.144.185")
        val health = watch.snapshot(metadataUnavailable = listOf(6, 4), unreachable = listOf(5, 4))

        assertEquals(0, health.deviceDropouts)
        assertEquals(listOf(4, 6), health.metadataUnavailablePlayers)
        assertEquals(listOf(4, 5), health.unreachablePlayers)
    }
}
