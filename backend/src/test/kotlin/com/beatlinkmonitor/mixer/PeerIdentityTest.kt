package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.config.ApplicationConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.deepsymmetry.beatlink.DeviceAnnouncement
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerIdentityTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))
    private val mac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)

    @Test
    fun `keep-alive is a 54 byte frame that beat-link parses as a device announcement`() {
        val frame = PeerIdentityProtocol.buildKeepAlive("BEATLINK-MON", 54, mac, ip, 3)
        val packet = DatagramPacket(frame, frame.size, ip, 50000)

        val parsed = DeviceAnnouncement(packet)

        assertEquals(0x36, frame.size)
        assertEquals("BEATLINK-MON", parsed.deviceName)
        assertEquals(0x06, frame[0x0a].toInt())
        assertEquals(0x36, ((frame[0x22].toInt() and 0xff) shl 8) or (frame[0x23].toInt() and 0xff))
        assertEquals(5, frame[0x34].toInt())
        assertEquals(0x20, frame[0x35].toInt())
        assertEquals(3, frame[0x30].toInt())
        assertTrue(frame.copyOfRange(0x26, 0x2c).contentEquals(mac))
        assertTrue(frame.copyOfRange(0x2c, 0x30).contentEquals(ip.address))
    }

    @Test
    fun `the device number is obfuscated and recoverable with the mac`() {
        val frame = PeerIdentityProtocol.buildKeepAlive("X", 55, mac, ip, 0)

        var sum = 0
        for (i in 0 until 0x36) if (i != 0x24) sum += frame[i].toInt() and 0xff
        sum += 0x33 + 0x44 + 0x55
        val wire = frame[0x24].toInt() and 0xff

        assertTrue(wire and 0x80 != 0)
        assertEquals(55, (wire xor sum) and 0x7f)
    }

    @Test
    fun `id request carries ip mac attempt and category`() {
        val frame = PeerIdentityProtocol.buildIdRequest("BEATLINK-MON", ip, mac, 54, 2)

        assertEquals(0x32, frame.size)
        assertEquals(0x02, frame[0x0a].toInt())
        assertTrue(frame.copyOfRange(0x24, 0x28).contentEquals(ip.address))
        assertTrue(frame.copyOfRange(0x28, 0x2e).contentEquals(mac))
        assertEquals(2, frame[0x2f].toInt())
        assertEquals(5, frame[0x30].toInt())
        var sum = 0
        for (i in 0 until 0x32) if (i != 0x2e) sum += frame[i].toInt() and 0xff
        sum += 0x33 + 0x44 + 0x55
        assertEquals(54, ((frame[0x2e].toInt() and 0xff) xor sum) and 0x7f)
    }

    @Test
    fun `disconnect and version request layouts`() {
        val bye = PeerIdentityProtocol.buildDisconnect("X", 54, mac, ip)
        assertEquals(0x29, bye.size)
        assertEquals(0x08, bye[0x0a].toInt())
        assertTrue(bye.copyOfRange(0x25, 0x29).contentEquals(ip.address))

        val probe = PeerIdentityProtocol.buildVersionRequest("X", 54)
        assertEquals(0x24, probe.size)
        assertEquals(0x30, probe[0x0a].toInt())
        assertEquals(1, probe[0x1f].toInt())
        assertEquals(3, probe[0x20].toInt())
        assertEquals(54, probe[0x21].toInt())
    }

    @Test
    fun `version reply is read from four ascii characters`() {
        val reply = ByteArray(0x30)
        "Qspt1WmJOL".toByteArray().copyInto(reply)
        reply[0x0a] = 0x31
        "1.30".toByteArray().copyInto(reply, 0x24)

        assertEquals("1.30", PeerIdentityProtocol.parseVersionReply(reply, reply.size))
        assertNull(PeerIdentityProtocol.parseVersionReply(reply, 0x27))
        reply[0x24] = 0x01
        assertNull(PeerIdentityProtocol.parseVersionReply(reply, reply.size))
    }

    private class FakeNetwork(var inUse: Set<Int> = emptySet()) : AnnouncementTransport {
        val frames = CopyOnWriteArrayList<ByteArray>()
        @Volatile override var available = true
        override fun networkIdentity() = NetworkIdentity(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7)), byteArrayOf(2, 1, 2, 3, 4, 5))
        override fun broadcast(data: ByteArray) { frames += data.copyOf() }
        override fun peerCount() = 2
        override fun deviceNumbersInUse() = inUse
        fun types() = frames.map { it[0x0a].toInt() }
    }

    private val on = ApplicationConfig(mixerIntegrationEnabled = true, mixerAnnounceIdentity = true)

    private fun announcer(config: ApplicationConfig, net: FakeNetwork) =
        PeerAnnouncer({ config }, net, keepAliveIntervalMs = 30, claimSpacingMs = 5, backoffMs = { 1 }, networkFullRetryMs = 30)

    @Test
    fun `nothing is sent unless integration and identity announcing are both enabled`() = runBlocking {
        for (config in listOf(
            ApplicationConfig(mixerIntegrationEnabled = false, mixerAnnounceIdentity = false),
            ApplicationConfig(mixerIntegrationEnabled = true, mixerAnnounceIdentity = false),
            ApplicationConfig(mixerIntegrationEnabled = false, mixerAnnounceIdentity = true),
        )) {
            val net = FakeNetwork()
            val a = announcer(config, net)
            a.start()
            delay(150)
            a.stop()
            assertTrue(net.frames.isEmpty())
            assertEquals(AnnouncerState.OFF, a.status().state)
        }
    }

    @Test
    fun `claims 54 with three requests then keeps announcing and says goodbye`() = runBlocking {
        val net = FakeNetwork()
        val a = announcer(on, net)
        a.start()
        delay(250)

        assertEquals(AnnouncerState.ONLINE, a.status().state)
        assertEquals(54, a.status().deviceNumber)
        assertEquals("BEATLINK-MON" to 54, a.activeIdentity())
        val types = net.types()
        assertEquals(listOf(2, 2, 2), types.take(3))
        assertTrue(types.drop(3).all { it == 6 })
        assertTrue(types.count { it == 6 } >= 2)

        a.stop()
        assertEquals(0x08, net.types().last())
        assertNull(a.activeIdentity())
    }

    @Test
    fun `numbers already in use are skipped and a full network is reported`() = runBlocking {
        val net = FakeNetwork(inUse = setOf(54, 55))
        val a = announcer(on, net)
        a.start()
        delay(250)
        assertEquals(56, a.status().deviceNumber)
        a.stop()

        val full = FakeNetwork(inUse = setOf(54, 55, 56, 57))
        val b = announcer(on, full)
        b.start()
        var seenFull = false
        repeat(100) {
            if (b.status().state == AnnouncerState.NETWORK_FULL) seenFull = true else delay(5)
        }
        assertTrue(seenFull)
        assertTrue(full.frames.isEmpty())
        b.stop()
    }

    @Test
    fun `switching the identity off sends a disconnect`() = runBlocking {
        val net = FakeNetwork()
        var config = on
        val a = PeerAnnouncer({ config }, net, keepAliveIntervalMs = 20, claimSpacingMs = 5, backoffMs = { 1 })
        a.start()
        delay(200)
        assertNotNull(a.activeIdentity())

        config = on.copy(mixerAnnounceIdentity = false)
        delay(1300)

        assertNull(a.activeIdentity())
        assertTrue(net.types().contains(0x08))
        a.stop()
    }
}
