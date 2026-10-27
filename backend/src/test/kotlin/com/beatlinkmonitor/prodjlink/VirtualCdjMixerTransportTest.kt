package com.beatlinkmonitor.prodjlink

import com.beatlinkmonitor.mixer.MixerChannelState
import com.beatlinkmonitor.mixer.MixerLiveState
import com.beatlinkmonitor.mixer.MixerProtocol
import org.deepsymmetry.beatlink.VirtualCdj
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualCdjMixerTransportTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun `beat-link still has the socket field the integration hooks into`() {
        val field = VirtualCdj::class.java.getDeclaredField("socket")

        assertEquals(AtomicReference::class.java, field.type)
    }

    @Test
    fun `tapping socket consumes mixer frames and obfuscated frames and passes the rest to beat-link`() {
        val tapped = mutableListOf<ByteArray>()
        val real = DatagramSocket(0, loopback)
        val sender = DatagramSocket(0, loopback)
        val wrapper = VirtualCdjMixerTransport.TappingSocket(real) { data, length -> tapped += data.copyOf(length) }
        try {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            wrapper.soTimeout = 2000
            fun frame(type: Int, size: Int, idByte: Int) =
                ByteArray(size).also { "Qspt1WmJOL".toByteArray().copyInto(it); it[0x0a] = type.toByte(); it[0x21] = idByte.toByte() }
            fun sendAll(vararg frames: ByteArray) {
                for (d in frames) sender.send(DatagramPacket(d, d.size, loopback, real.localPort))
            }

            // The mixer's live frame is tapped and swallowed; the following regular status packet comes out.
            val live = MixerProtocol.buildLiveFrame("M", liveState())
            val status = frame(0x29, 0x38, 33)
            sendAll(live, status)
            packet.length = buffer.size
            wrapper.receive(packet)
            assertEquals(status.size, packet.length)
            assertEquals(0x29, buffer[0x0a].toInt())
            assertEquals(1, tapped.size)
            assertTrue(tapped[0].contentEquals(live))

            // Frames with an obfuscated device id (bit 7 set) never reach beat-link, only the plain one does.
            val obfuscated = frame(0x0a, 0x124, 0x8c)
            val plain = frame(0x0a, 0x200, 4)
            sendAll(obfuscated, plain)
            packet.length = buffer.size
            wrapper.receive(packet)
            assertEquals(plain.size, packet.length)
            assertEquals(4, buffer[0x21].toInt())
            assertEquals(1, tapped.size)

            // Sending through the wrapper uses the original socket (same source port).
            wrapper.send(DatagramPacket(live, live.size, loopback, sender.localPort))
            sender.soTimeout = 2000
            val echo = DatagramPacket(ByteArray(512), 512)
            sender.receive(echo)
            assertEquals(real.localPort, echo.port)
            assertEquals(real.localPort, wrapper.localPort)
        } finally {
            wrapper.close()
            sender.close()
        }
        assertTrue(real.isClosed)
    }

    @Test
    fun `beat socket tap consumes audio levels and drops the obfuscated copy of position frames`() {
        val tapped = mutableListOf<ByteArray>()
        val real = DatagramSocket(0, loopback)
        val sender = DatagramSocket(0, loopback)
        val wrapper = VirtualCdjMixerTransport.TappingSocket(real, null, setOf(0x58)) { data, length -> tapped += data.copyOf(length) }
        try {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            wrapper.soTimeout = 2000
            fun frame(type: Int, size: Int, idByte: Int) =
                ByteArray(size).also { "Qspt1WmJOL".toByteArray().copyInto(it); it[0x0a] = type.toByte(); it[0x21] = idByte.toByte() }
            val levels = frame(0x58, 512, 0x9b)
            val obfuscatedPosition = frame(0x0b, 60, 0x9c)
            val plainPosition = frame(0x0b, 60, 3)
            for (d in listOf(levels, obfuscatedPosition, plainPosition)) sender.send(DatagramPacket(d, d.size, loopback, real.localPort))

            packet.length = buffer.size
            wrapper.receive(packet)

            assertEquals(60, packet.length)
            assertEquals(3, buffer[0x21].toInt())
            assertEquals(1, tapped.size)
            assertEquals(0x58, tapped[0][0x0a].toInt())
        } finally {
            wrapper.close()
            sender.close()
        }
    }

    private fun liveState() = MixerLiveState(
        channels = (1..6).map { MixerChannelState(it, 0, 1, 2, 3, 4, 5, 6, 7, 8, false, 9, 10, 0) },
        crossfader = 1, masterLevel = 2, masterFilter = 3, masterCue = 4, isolatorOn = 5, isoHi = 6, isoMid = 7,
        isoLow = 8, booth = 9, boothHi = 10, boothLow = 11, fxFreqLow = 12, fxFreqMid = 13, fxFreqHi = 14,
        fxSelect = 15, fxChannel = 16, fxLevel = 17, fxOn = 18, hpMix = 19, hpLevel = 20,
    )
}
