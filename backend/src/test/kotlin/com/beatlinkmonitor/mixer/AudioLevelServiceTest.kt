package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.state.ApplicationState
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioLevelServiceTest {
    private val mac = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun levelFrame(configure: (ByteArray) -> Unit): ByteArray {
        val frame = ByteArray(512)
        "Qspt1WmJOL".toByteArray().copyInto(frame)
        frame[0x0a] = AudioLevelProtocol.TYPE_LEVELS.toByte()
        configure(frame)
        return frame
    }

    @Test
    fun `request frame is 40 bytes and the encoded variant has bit 7 set`() {
        val plain = AudioLevelProtocol.buildRequest("TEST", 54, mac, encodedId = false)
        val encoded = AudioLevelProtocol.buildRequest("TEST", 54, mac, encodedId = true)

        assertEquals(0x28, plain.size)
        assertEquals(0x57, plain[0x0a].toInt())
        assertEquals(54, plain[0x21].toInt())
        assertEquals(1, plain[0x24].toInt())
        assertTrue(encoded[0x21].toInt() and 0x80 != 0)
    }

    @Test
    fun `peak of each block is the high byte of the largest word and channels map to groups`() {
        val frame = levelFrame { f ->
            f[0x2c + 2] = 0x40 // channel 1, block A, word 1
            f[0x2c + 0x1e + 4] = 0x22 // channel 1, block B (second block, stays unused by the assertions below)
            f[0x2c + 0x3c * 6] = 0x7f // group 6 = channel 5
            f[0x2c + 0x3c * 4] = 0x10 // master left
            f[0x2c + 0x3c * 5] = 0x20 // master right
            f[0x24 + 4] = 1 // master clip
        }

        val levels = assertNotNull(AudioLevelProtocol.parse(frame, frame.size))

        assertEquals(0x40, levels.channels[0].left)
        assertEquals(0x22, levels.channels[0].right)
        assertEquals(0x40, levels.channels[0].bands[1])
        assertEquals(0x7f, levels.channels[4].left)
        assertEquals(0x10, levels.masterLeft)
        assertEquals(0x20, levels.masterRight)
        assertEquals(1, levels.clip[4])
        assertNull(AudioLevelProtocol.parse(frame, 0x100))
    }

    @Test
    fun `requests alternate the id variant every three seconds until levels arrive`() {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val transport = object : AudioLevelTransport {
            override val available = true
            override fun identity() = MixerIdentity("TEST", 54, mac)
            override fun send(data: ByteArray, address: InetAddress) { sent += data }
        }
        val state = ApplicationState().also { s -> s.upsertPlayer(33) { it.copy(deviceType = DeviceType.MIXER, ipAddress = "169.254.1.33") } }
        val service = AudioLevelService(state, { ApplicationConfig() }, transport)

        service.tick(10_000)
        service.tick(11_000) // too early
        service.tick(13_100)
        assertEquals(2, sent.size)
        assertTrue(sent[0][0x21].toInt() and 0x80 != 0)
        assertEquals(54, sent[1][0x21].toInt())

        val frame = levelFrame { }
        service.handlePacket(frame, frame.size)
        assertNotNull(service.status().levels)
    }
}
