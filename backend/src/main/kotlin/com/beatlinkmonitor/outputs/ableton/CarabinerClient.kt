package com.beatlinkmonitor.outputs.ableton

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private val logger = KotlinLogging.logger {}

data class CarabinerStatus(
    val peers: Int,
    val bpm: Double,
    val startMicros: Long,
    val beat: Double,
    val playing: Boolean? = null,
)

/**
 * Minimal client for Deep Symmetry's Carabiner bridge, the same mechanism
 * `beat-link-trigger`/`beat-carabiner` use to connect a JVM application to
 * Ableton Link without a hand-rolled JNI binding. Carabiner must be running
 * locally (requirements specification §12); this client only speaks its plain-text TCP
 * protocol ("status", "bpm <value>", "force-beat-at-time ...").
 *
 * https://github.com/Deep-Symmetry/carabiner
 */
class CarabinerClient(private val host: String, private val port: Int, private val bindAddress: java.net.InetAddress? = null) {

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: BufferedReader? = null
    private val lock = Mutex()

    private val statusRegex = Regex(
        """status \{ :peers (\d+) :bpm ([0-9.]+) :start (-?\d+) :beat (-?[0-9.]+)(?: :playing (true|false))? \}""",
    )

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect() {
        lock.withLock {
            close()
            val s = Socket()
            // Bind to the configured outbound network adapter, if one was
            // explicitly selected - independent of whichever adapter Pro DJ
            // Link discovery uses (requirements specification: separate adapters per
            // network service).
            if (bindAddress != null) {
                s.bind(InetSocketAddress(bindAddress, 0))
            }
            s.connect(InetSocketAddress(host, port), 3_000)
            s.soTimeout = 1_000
            socket = s
            output = s.getOutputStream()
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
        }
    }

    suspend fun send(message: String) {
        lock.withLock {
            val out = output ?: throw IllegalStateException("Carabiner not connected")
            out.write((message + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    /** Reads and parses a single line if one is available; non-blocking beyond the socket read timeout. */
    fun tryReadStatus(): CarabinerStatus? {
        val r = reader ?: return null
        return try {
            if (!r.ready()) return null
            val line = r.readLine() ?: return null
            parseStatus(line)
        } catch (e: Exception) {
            null
        }
    }

    fun parseStatus(line: String): CarabinerStatus? {
        val match = statusRegex.find(line) ?: return null
        return CarabinerStatus(
            peers = match.groupValues[1].toInt(),
            bpm = match.groupValues[2].toDouble(),
            startMicros = match.groupValues[3].toLong(),
            beat = match.groupValues[4].toDouble(),
            playing = match.groupValues.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toBoolean(),
        )
    }

    fun close() {
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        reader = null
        output = null
        socket = null
    }
}
