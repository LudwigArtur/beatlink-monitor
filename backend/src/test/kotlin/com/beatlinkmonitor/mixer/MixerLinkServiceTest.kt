package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.state.ApplicationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixerLinkServiceTest {

    /** A pretend mixer on the network: records requests and optionally answers them. */
    private class FakeMixer(private val behavior: Behavior) : MixerTransport {
        enum class Behavior { ECHO, SILENT, IGNORE_WRITES, UNAVAILABLE_ITEM }

        lateinit var link: MixerLinkService
        val sent = CopyOnWriteArrayList<Pair<ByteArray, InetAddress>>()
        private val values = IntArray(15)

        override val available = true
        override fun identity() = MixerIdentity("TEST", 5, byteArrayOf(0, 1, 2, 3, 4, 5))

        override fun send(data: ByteArray, address: InetAddress, port: Int) {
            sent += data to address
            assertEquals(MixerProtocol.PORT, port)
            val (write, id, value) = MixerProtocol.parseUtilityRequest(data, data.size) ?: return
            when (behavior) {
                Behavior.SILENT -> return
                Behavior.IGNORE_WRITES -> if (write) return
                Behavior.UNAVAILABLE_ITEM -> {
                    link.handlePacket(MixerProtocol.buildUtilityReply("M", id, 0, valid = false), 40)
                    return
                }
                Behavior.ECHO -> if (write) values[id] = value
            }
            val reply = MixerProtocol.buildUtilityReply("M", id, values[id])
            link.handlePacket(reply, reply.size)
        }
    }

    private class Fixture(behavior: FakeMixer.Behavior, val config: ApplicationConfig) {
        val state = ApplicationState()
        val mixer = FakeMixer(behavior)
        val link = MixerLinkService(state, { config }, mixer).also { mixer.link = it }
    }

    private val enabled = ApplicationConfig(
        mixerIntegrationEnabled = true,
        mixerControlEnabled = true,
        mixerAddress = "169.254.10.20",
    )

    @Test
    fun `write is confirmed only after the mixer reports the value back`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled)

        val result = f.link.writeUtility(2, 3)

        assertEquals(UtilityWriteStatus.CONFIRMED, result.status)
        assertEquals(3, result.reportedValue)
        assertEquals(2, f.mixer.sent.size)
        assertEquals(1, f.mixer.sent[0].first[0x24].toInt()) // write
        assertEquals(0, f.mixer.sent[1].first[0x24].toInt()) // read-back
        assertEquals("169.254.10.20", f.mixer.sent[0].second.hostAddress)
        assertEquals(3, f.state.mixer.value.utilities.single { it.id == 2 }.value)
    }

    @Test
    fun `write without any reply is reported as unknown not as success`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.SILENT, enabled)

        val result = f.link.writeUtility(1, 1)

        assertEquals(UtilityWriteStatus.NO_REPLY, result.status)
        assertTrue(f.state.mixer.value.utilities.isEmpty())
    }

    @Test
    fun `a different value in the read-back is a mismatch`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.IGNORE_WRITES, enabled)

        val result = f.link.writeUtility(4, 5)

        assertEquals(UtilityWriteStatus.MISMATCH, result.status)
        assertEquals(0, result.reportedValue)
    }

    @Test
    fun `an item the mixer reports as unavailable is rejected and never stored as zero`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.UNAVAILABLE_ITEM, enabled)

        val result = f.link.writeUtility(4, 5)

        assertEquals(UtilityWriteStatus.REJECTED, result.status)
        assertNull(f.state.mixer.value.utilities.single { it.id == 4 }.value)
    }

    @Test
    fun `nothing is sent while mixer control is disabled`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled.copy(mixerControlEnabled = false))

        val result = f.link.writeUtility(1, 1)

        assertEquals(UtilityWriteStatus.CONTROL_DISABLED, result.status)
        assertTrue(f.mixer.sent.isEmpty())
    }

    @Test
    fun `nothing is sent while mixer integration is disabled`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled.copy(mixerIntegrationEnabled = false))

        assertEquals(UtilityWriteStatus.NO_MIXER, f.link.writeUtility(1, 1).status)
        assertEquals(false, f.link.requestRead(1))
        assertTrue(f.mixer.sent.isEmpty())
    }

    @Test
    fun `out of range values and unknown ids are refused before sending`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled)

        assertEquals(UtilityWriteStatus.INVALID, f.link.writeUtility(2, 5).status)
        assertEquals(UtilityWriteStatus.INVALID, f.link.writeUtility(0, 128).status)
        assertEquals(UtilityWriteStatus.INVALID, f.link.writeUtility(15, 0).status)
        assertTrue(f.mixer.sent.isEmpty())
    }

    @Test
    fun `addresses outside the local network are never used`() = runBlocking {
        for (address in listOf("8.8.8.8", "example.com", "300.1.1.1")) {
            val f = Fixture(FakeMixer.Behavior.ECHO, enabled.copy(mixerAddress = address))
            assertEquals(UtilityWriteStatus.NO_MIXER, f.link.writeUtility(1, 1).status, address)
            assertTrue(f.mixer.sent.isEmpty(), address)
        }
    }

    @Test
    fun `the mixer discovered by Pro DJ Link is used when no address is configured`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled.copy(mixerAddress = null))
        f.state.upsertPlayer(33) {
            it.copy(deviceType = com.beatlinkmonitor.model.DeviceType.MIXER, ipAddress = "192.168.1.50")
        }

        assertEquals(UtilityWriteStatus.CONFIRMED, f.link.writeUtility(3, 1).status)
        assertEquals("192.168.1.50", f.mixer.sent[0].second.hostAddress)
    }

    @Test
    fun `live packets update the shared state`() {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled)
        val live = MixerLiveState(
            channels = (1..6).map {
                MixerChannelState(it, 0, 1, 2, 3, 4, 5, 6, 7, 8, it == 1, 9, 200, 1)
            },
            crossfader = 1, masterLevel = 2, masterFilter = 3, masterCue = 4, isolatorOn = 5, isoHi = 6,
            isoMid = 7, isoLow = 8, booth = 9, boothHi = 10, boothLow = 11, fxFreqLow = 12, fxFreqMid = 13,
            fxFreqHi = 14, fxSelect = 15, fxChannel = 16, fxLevel = 17, fxOn = 18, hpMix = 19, hpLevel = 20,
        )
        val frame = MixerProtocol.buildLiveFrame("M", live)

        f.link.handlePacket(frame, frame.size)

        assertEquals(live, f.state.mixer.value.live)
        assertEquals(1L, f.link.status().liveFramesReceived)
    }

    @Test
    fun `live state of a mixer that stopped sending is dropped`() {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled)
        val live = MixerProtocol.parseLiveState(MixerProtocol.buildLiveFrame("M", liveSample()), 0xf8)!!
        f.state.updateMixerLive(live)
        val timestamp = f.state.mixer.value.liveTimestampMs

        f.link.expireStaleLiveState(timestamp + MixerLinkService.LIVE_STALE_AFTER_MS)
        assertNotNull(f.state.mixer.value.live)

        f.link.expireStaleLiveState(timestamp + MixerLinkService.LIVE_STALE_AFTER_MS + 1)
        assertNull(f.state.mixer.value.live)
    }

    private fun liveSample() = MixerLiveState(
        channels = (1..6).map { MixerChannelState(it, 0, 1, 2, 3, 4, 5, 6, 7, 8, false, 9, 10, 0) },
        crossfader = 1, masterLevel = 2, masterFilter = 3, masterCue = 4, isolatorOn = 5, isoHi = 6,
        isoMid = 7, isoLow = 8, booth = 9, boothHi = 10, boothLow = 11, fxFreqLow = 12, fxFreqMid = 13,
        fxFreqHi = 14, fxSelect = 15, fxChannel = 16, fxLevel = 17, fxOn = 18, hpMix = 19, hpLevel = 20,
    )

    @Test
    fun `a signed balance value is written and read back with its sign`() = runBlocking {
        val f = Fixture(FakeMixer.Behavior.ECHO, enabled)

        val result = f.link.writeUtility(0, -20)

        assertEquals(UtilityWriteStatus.CONFIRMED, result.status)
        assertEquals(-20, f.state.mixer.value.utilities.single { it.id == 0 }.value)
    }

    @Test
    fun `mixer defaults read everything and allow explicit writes`() {
        val config = ApplicationConfig()

        assertTrue(config.mixerIntegrationEnabled)
        assertTrue(config.mixerControlEnabled)
        assertTrue(config.mixerAnnounceIdentity)
    }
}
