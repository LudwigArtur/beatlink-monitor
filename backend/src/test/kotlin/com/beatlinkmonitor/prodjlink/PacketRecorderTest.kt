package com.beatlinkmonitor.prodjlink

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketRecorderTest {
    private val a = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    private val b = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))

    @Test
    fun `records raw ip udp frames in a classic pcap`() {
        val dir = createTempDirectory().toFile()
        val recorder = PacketRecorder { dir }
        assertFalse(recorder.active)

        val file = recorder.start()
        val payload = "Qspt1WmJOL".toByteArray() + byteArrayOf(0x39, 1, 2, 3)
        recorder.record(a, 50002, b, 50002, payload, 0, payload.size)
        recorder.record(b, 40000, a, 50002, byteArrayOf(9, 8, 7), 0, 3)
        val status = recorder.stop()

        assertEquals(2, status.packets)
        assertFalse(recorder.active)
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xa1b2c3d4.toInt(), buf.getInt(0))
        assertEquals(101, buf.getInt(20))
        val first = 24
        val caplen = buf.getInt(first + 8)
        assertEquals(20 + 8 + payload.size, caplen)
        val ip = first + 16
        assertEquals(0x45, buf.get(ip).toInt())
        assertEquals(17, buf.get(ip + 9).toInt())
        assertEquals(10, buf.get(ip + 12).toInt())
        assertEquals(2, buf.get(ip + 19).toInt())
        buf.order(ByteOrder.BIG_ENDIAN)
        assertEquals(50002, buf.getShort(ip + 20).toInt() and 0xffff)
        assertEquals(50002, buf.getShort(ip + 22).toInt() and 0xffff)
        assertTrue(file.readBytes().takeLast(3) == listOf<Byte>(9, 8, 7))
    }

    @Test
    fun `nothing is written while not recording`() {
        val recorder = PacketRecorder { createTempDirectory().toFile() }
        recorder.record(a, 1, b, 2, byteArrayOf(1), 0, 1)
        assertEquals(0, recorder.status().packets)
    }
}
