package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.state.ApplicationState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/** Sends audio-level requests from the beat port (UDP 50001); the real one rides on beat-link's beat socket. */
interface AudioLevelTransport {
    val available: Boolean
    fun identity(): MixerIdentity?
    fun send(data: ByteArray, address: InetAddress)
}

/**
 * Levels 0..255 (high byte of 16-bit words). [bands] are the 15 words of the channel's first block, probably a
 * spectrum; [left] is their maximum, [right] the maximum of the second block (all zero on a DJM-V10).
 */
@Serializable
data class ChannelLevel(val channel: Int, val left: Int, val right: Int, val bands: List<Int>)

@Serializable
data class AudioLevels(
    val channels: List<ChannelLevel>,
    val masterLeft: Int,
    val masterRight: Int,
    /** The six clip flag bytes at 0x24; their meaning is inferred from key names only. */
    val clip: List<Int>,
    val timestampMs: Long,
)

@Serializable
data class AudioLevelStatus(
    val active: Boolean,
    val framesReceived: Long,
    val requestsSent: Long,
    val levels: AudioLevels?,
)

/**
 * Meter data of a mixer. Frame layout: type 0x58, six clip bytes at 0x24, then eight groups of 30 big-endian
 * words from 0x2c, stride 0x3c (block A = words 0..14, block B = words 15..29). Channels 1-4 are groups 0-3, the
 * master is groups 4 and 5 (block A), channels 5 and 6 are groups 6 and 7 (checked against a real DJM-V10, which
 * sends frames of 512 bytes about 30 times a second without being asked once it is announced as a peer).
 */
object AudioLevelProtocol {
    const val PORT = 50001
    const val TYPE_REQUEST = 0x57
    const val TYPE_LEVELS = 0x58
    const val MIN_LENGTH = 0x1ee
    private const val GROUP_START = 0x2c
    private const val GROUP_SIZE = 0x3c
    private val MAGIC = "Qspt1WmJOL".toByteArray(Charsets.US_ASCII)
    private val CHANNEL_GROUPS = intArrayOf(0, 1, 2, 3, 6, 7)

    fun buildRequest(senderName: String, deviceNumber: Int, mac: ByteArray, encodedId: Boolean): ByteArray {
        val frame = ByteArray(0x28)
        MAGIC.copyInto(frame)
        frame[0x0a] = TYPE_REQUEST.toByte()
        val name = senderName.toByteArray(Charsets.US_ASCII)
        name.copyInto(frame, 0x0b, 0, minOf(name.size, 20))
        frame[0x1f] = 1
        frame[0x20] = 0
        frame[0x23] = 4
        frame[0x24] = 1
        frame[0x21] = (if (encodedId) MixerProtocol.encodeOwnId(frame, deviceNumber, mac) else deviceNumber and 0xff).toByte()
        return frame
    }

    fun parse(data: ByteArray, length: Int, now: Long = System.currentTimeMillis()): AudioLevels? {
        if (length < MIN_LENGTH || length > data.size) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        if ((data[0x0a].toInt() and 0xff) != TYPE_LEVELS) return null
        fun word(at: Int): Int = if (at + 1 < length) ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff) else 0
        fun block(group: Int, second: Boolean): List<Int> {
            val first = GROUP_START + GROUP_SIZE * group + if (second) 0x1e else 0
            return (0 until 15).map { word(first + 2 * it) shr 8 }
        }
        val channels = CHANNEL_GROUPS.mapIndexed { i, g ->
            val a = block(g, second = false)
            ChannelLevel(i + 1, a.max(), block(g, second = true).max(), a)
        }
        return AudioLevels(
            channels = channels,
            masterLeft = block(4, false).max(),
            masterRight = block(5, false).max(),
            clip = (0 until 6).map { data[0x24 + it].toInt() and 0xff },
            timestampMs = now,
        )
    }
}

/**
 * Keeps the latest meter frame. A DJM-V10 streams it unasked to announced peers; as a fallback the documented request
 * is sent every 3 s while nothing arrives, alternating between an obfuscated and a plain device id.
 */
class AudioLevelService(
    private val state: ApplicationState,
    private val config: () -> ApplicationConfig,
    private val transport: AudioLevelTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val frames = AtomicLong()
    private val requests = AtomicLong()
    @Volatile private var latest: AudioLevels? = null
    @Volatile private var lastRequestAt = 0L
    @Volatile private var lastVariantEncoded: Boolean? = null

    companion object {
        const val REQUEST_INTERVAL_MS = 3000L
        const val STALE_AFTER_MS = 3000L
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick(System.currentTimeMillis()) }.onFailure { logger.warn(it) { "Audio level request failed" } }
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    internal fun tick(now: Long) {
        if (!config().mixerIntegrationEnabled || !transport.available) return
        val last = latest
        if (last != null && now - last.timestampMs < STALE_AFTER_MS) return
        if (now - lastRequestAt < REQUEST_INTERVAL_MS) return
        val address = state.players.value.values.firstOrNull { it.deviceType == DeviceType.MIXER && it.ipAddress != null }?.ipAddress
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?.takeIf { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress } ?: return
        val identity = transport.identity() ?: return
        val encoded = lastVariantEncoded?.not() ?: true
        lastVariantEncoded = encoded
        lastRequestAt = now
        requests.incrementAndGet()
        transport.send(AudioLevelProtocol.buildRequest(identity.name, identity.deviceNumber, identity.mac, encoded), address)
    }

    /** Entry point for every raw packet received on the Pro DJ Link beat port. */
    fun handlePacket(data: ByteArray, length: Int) {
        val levels = AudioLevelProtocol.parse(data, length) ?: return
        frames.incrementAndGet()
        latest = levels
    }

    fun status(): AudioLevelStatus {
        val current = latest?.takeIf { System.currentTimeMillis() - it.timestampMs < STALE_AFTER_MS }
        return AudioLevelStatus(config().mixerIntegrationEnabled, frames.get(), requests.get(), current)
    }
}
