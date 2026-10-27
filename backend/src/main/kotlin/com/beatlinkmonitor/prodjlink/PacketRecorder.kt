package com.beatlinkmonitor.prodjlink

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Optional recording of the UDP traffic on the Pro DJ Link status socket
 * (packets we receive and packets we send), as a classic pcap file with raw
 * IPv4 frames (readable by Wireshark and similar tools). This is a
 * diagnostic aid for verifying reconstructed protocol layouts against real
 * hardware; it only copies traffic that the application already handles and
 * never sends anything itself. Files are written to a fixed directory.
 */
class PacketRecorder(private val directory: () -> File) {
    private val lock = Any()
    private var out: OutputStream? = null
    private var file: File? = null
    private var packets = 0L

    @kotlinx.serialization.Serializable
    data class Status(val recording: Boolean, val file: String?, val packets: Long)

    val active: Boolean get() = synchronized(lock) { out != null }

    fun status(): Status = synchronized(lock) { Status(out != null, file?.absolutePath, packets) }

    fun start(): File = synchronized(lock) {
        if (out != null) return file!!
        val dir = directory().also { it.mkdirs() }
        val target = File(dir, "status-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.pcap")
        val stream = BufferedOutputStream(target.outputStream())
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0).putInt(65535).putInt(101) // LINKTYPE_RAW
        stream.write(header.array())
        out = stream
        file = target
        packets = 0
        target
    }

    fun stop(): Status = synchronized(lock) {
        runCatching { out?.close() }
        out = null
        Status(false, file?.absolutePath, packets)
    }

    fun record(src: InetAddress?, srcPort: Int, dst: InetAddress?, dstPort: Int, data: ByteArray, offset: Int, length: Int) {
        if (out == null) return
        // A wildcard-bound socket (the beat socket) reports an IPv6 or empty address; record it as 0.0.0.0.
        val srcBytes = src?.address?.takeIf { it.size == 4 } ?: ByteArray(4)
        val dstBytes = dst?.address?.takeIf { it.size == 4 } ?: ByteArray(4)
        val total = 20 + 8 + length
        val frame = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        frame.put(0x45).put(0).putShort(total.toShort()).putShort(0).putShort(0).put(64).put(17).putShort(0)
        frame.put(srcBytes).put(dstBytes)
        frame.putShort(srcPort.toShort()).putShort(dstPort.toShort()).putShort((8 + length).toShort()).putShort(0)
        frame.put(data, offset, length)
        val now = System.currentTimeMillis()
        val record = ByteBuffer.allocate(16 + total).order(ByteOrder.LITTLE_ENDIAN)
        record.putInt((now / 1000).toInt()).putInt(((now % 1000) * 1000).toInt()).putInt(total).putInt(total)
        record.put(frame.array())
        synchronized(lock) {
            val stream = out ?: return
            runCatching { stream.write(record.array()); packets++ }.onFailure { out = null }
        }
    }
}
