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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

class MixerIdentity(val name: String, val deviceNumber: Int, val mac: ByteArray)

/** Sends mixer packets; the real one rides on beat-link's Pro DJ Link socket (tests use a fake). */
interface MixerTransport {
    val available: Boolean
    fun identity(): MixerIdentity?
    fun send(data: ByteArray, address: InetAddress, port: Int)
}

/**
 * Receives the mixer's live-state and utility packets and sends utility
 * read/write requests. All state goes through [ApplicationState] like every
 * other data source. Writing is refused unless explicitly enabled in the
 * configuration, and a write is only reported as confirmed after the mixer
 * itself answered a read-back with the requested value.
 */
class MixerLinkService(
    private val state: ApplicationState,
    private val config: () -> ApplicationConfig,
    private val realTransport: MixerTransport,
    private val ensureRealTransportInstalled: () -> Unit = {},
    private val announcerStatus: () -> AnnouncerStatus? = { null },
) {
    private class Reply(val sequence: Long, val value: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val replySequence = AtomicLong()
    private val replies = ConcurrentHashMap<Int, Reply>()
    private val liveFrames = AtomicLong()
    private val utilityReplies = AtomicLong()

    @Volatile private var lastLiveFrameAt = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var lastAutoReadAt = 0L
    private var supervisorJob: Job? = null
    private var readAllJob: Job? = null

    companion object {
        /** Spacing between consecutive utility reads; same value the reference implementation uses for its polling lock. */
        const val READ_SPACING_MS = 400L
        const val WRITE_READBACK_DELAY_MS = 150L
        const val REPLY_TIMEOUT_MS = 1500L
        private const val SUPERVISOR_INTERVAL_MS = 1000L
        private const val INSTALL_CHECK_TICKS = 1L
        private const val VERSION_PROBE_TICKS = 5L
        const val LIVE_STALE_AFTER_MS = 5000L
        private const val UTILITY_REFRESH_MS = 60_000L
    }

    fun start() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            var ticks = 0L
            while (isActive) {
                if (ticks % INSTALL_CHECK_TICKS == 0L && (config().mixerIntegrationEnabled || config().cdjIntegrationEnabled)) {
                    runCatching { ensureRealTransportInstalled() }
                        .onFailure { lastError = "Could not hook the Pro DJ Link socket: ${it.message}" }
                }
                expireStaleLiveState()
                if (ticks % VERSION_PROBE_TICKS == 0L) probeVersion()
                refreshUtilitiesIfDue()
                ticks++
                delay(SUPERVISOR_INTERVAL_MS)
            }
        }
    }

    /** Reads all utility settings as soon as the mixer streams, then every [UTILITY_REFRESH_MS] so changes made on the mixer show up. Reads never modify the mixer. */
    private fun refreshUtilitiesIfDue(now: Long = System.currentTimeMillis()) {
        if (state.mixer.value.live == null || now - lastAutoReadAt < UTILITY_REFRESH_MS) return
        if (requestReadAll()) lastAutoReadAt = now
    }

    /** Asks the mixer for its firmware version until it answers; the request is unobfuscated and harmless. */
    internal fun probeVersion() {
        if (state.mixer.value.version != null) return
        val target = resolveTarget() ?: return
        val identity = target.transport.identity() ?: return
        runCatching {
            target.transport.send(PeerIdentityProtocol.buildVersionRequest(identity.name, identity.deviceNumber), target.address, MixerProtocol.PORT)
        }.onFailure { lastError = "Version request failed: ${it.message}" }
    }

    /** Forgets the live state of a real mixer that stopped sending, so old knob positions never linger. */
    internal fun expireStaleLiveState(now: Long = System.currentTimeMillis()) {
        val current = state.mixer.value
        if (current.live != null && now - current.liveTimestampMs > LIVE_STALE_AFTER_MS) {
            state.clearMixer()
            replies.clear()
            lastAutoReadAt = 0L
        }
    }

    fun stop() {
        supervisorJob?.cancel()
        readAllJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    /** Entry point for every raw packet received on the Pro DJ Link status port */
    fun handlePacket(data: ByteArray, length: Int) {
        MixerProtocol.parseLiveState(data, length)?.let { live ->
            liveFrames.incrementAndGet()
            lastLiveFrameAt = System.currentTimeMillis()
            state.updateMixerLive(live)
            return
        }
        PeerIdentityProtocol.parseVersionReply(data, length)?.let { version ->
            state.updateMixerVersion(version)
            return
        }
        MixerProtocol.parseUtilityReply(data, length)?.let { reply ->
            utilityReplies.incrementAndGet()
            val value = reply.value.takeIf { reply.valid }
            replies[reply.id] = Reply(replySequence.incrementAndGet(), value)
            state.updateMixerUtility(reply.id, value)
        }
    }

    private class Target(val transport: MixerTransport, val address: InetAddress)

    private fun resolveTarget(): Target? {
        val cfg = config()
        if (!cfg.mixerIntegrationEnabled || !realTransport.available) return null
        val text = cfg.mixerAddress?.trim().takeUnless { it.isNullOrEmpty() }
            ?: state.players.value.values.firstOrNull { it.deviceType == DeviceType.MIXER && it.ipAddress != null }?.ipAddress
            ?: return null
        val address = parseLocalAddress(text) ?: return null
        return Target(realTransport, address)
    }

    /** The address requests would currently be sent to, or null. Informational only. */
    fun currentMixerAddress(): String? = resolveTarget()?.address?.hostAddress

    private fun parseLocalAddress(text: String): InetAddress? {
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(text)) return null
        val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null
        // Requests are meant for a mixer on the local Pro DJ Link network only.
        val local = address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
        return address.takeIf { local }
    }

    fun status(): MixerLinkStatus {
        val cfg = config()
        val last = lastLiveFrameAt
        return MixerLinkStatus(
            integrationEnabled = cfg.mixerIntegrationEnabled,
            controlEnabled = cfg.mixerControlEnabled,
            transportAvailable = realTransport.available,
            mixerAddress = currentMixerAddress(),
            mixerModel = state.players.value.values.firstOrNull { it.deviceType == DeviceType.MIXER }?.deviceName?.takeIf { it.isNotBlank() },
            liveFramesReceived = liveFrames.get(),
            utilityRepliesReceived = utilityReplies.get(),
            lastLiveFrameAgeMs = if (last == 0L) null else System.currentTimeMillis() - last,
            lastError = lastError,
            announcer = announcerStatus(),
            utilityItems = MixerProtocol.UTILITY_ITEMS,
        )
    }

    private fun sendUtility(target: Target, write: Boolean, id: Int, value: Int): Boolean {
        val identity = target.transport.identity()
        if (identity == null) {
            lastError = "No local Pro DJ Link identity yet"
            return false
        }
        return try {
            val packet = MixerProtocol.buildUtilityRequest(identity.name, identity.deviceNumber, identity.mac, write, id, value)
            target.transport.send(packet, target.address, MixerProtocol.PORT)
            true
        } catch (e: Exception) {
            lastError = "Sending to the mixer failed: ${e.message}"
            logger.warn(e) { "Mixer utility request failed" }
            false
        }
    }

    /** Sends one read request; the answer arrives asynchronously via [handlePacket]. Returns false if nothing could be sent. */
    fun requestRead(id: Int): Boolean {
        if (MixerProtocol.utilityItem(id) == null) return false
        val target = resolveTarget() ?: return false
        return sendUtility(target, write = false, id = id, value = 0)
    }

    /** Reads all utility settings one by one, spaced by [READ_SPACING_MS]. Returns false if a read-all is already running or no mixer is reachable. */
    fun requestReadAll(): Boolean {
        if (readAllJob?.isActive == true || resolveTarget() == null) return false
        readAllJob = scope.launch {
            for (item in MixerProtocol.UTILITY_ITEMS) {
                if (!requestRead(item.id)) break
                delay(READ_SPACING_MS)
            }
        }
        return true
    }

    suspend fun writeUtility(id: Int, value: Int): UtilityWriteResult {
        val item = MixerProtocol.utilityItem(id)
            ?: return UtilityWriteResult(UtilityWriteStatus.INVALID, id, value, message = "Unknown utility id")
        if (value !in item.min..item.max) {
            return UtilityWriteResult(UtilityWriteStatus.INVALID, id, value, message = "Value must be within ${item.min}..${item.max}")
        }
        val target = resolveTarget()
        if (target == null) {
            val message = if (config().mixerIntegrationEnabled) "No mixer address known or Pro DJ Link not running" else "Mixer integration is disabled"
            return UtilityWriteResult(UtilityWriteStatus.NO_MIXER, id, value, message = message)
        }
        if (!config().mixerControlEnabled) {
            return UtilityWriteResult(UtilityWriteStatus.CONTROL_DISABLED, id, value, message = "Mixer control is disabled in the settings")
        }
        return writeLock.withLock {
            if (!sendUtility(target, write = true, id = id, value = value)) {
                return@withLock UtilityWriteResult(UtilityWriteStatus.NO_MIXER, id, value, message = lastError)
            }
            delay(WRITE_READBACK_DELAY_MS)
            val sequenceAtReadback = replySequence.get()
            if (!sendUtility(target, write = false, id = id, value = 0)) {
                return@withLock UtilityWriteResult(UtilityWriteStatus.NO_REPLY, id, value, message = lastError)
            }
            val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
            var reply: Reply? = null
            while (System.currentTimeMillis() < deadline) {
                reply = replies[id]?.takeIf { it.sequence > sequenceAtReadback }
                if (reply != null) break
                delay(25)
            }
            when {
                reply == null -> UtilityWriteResult(UtilityWriteStatus.NO_REPLY, id, value, message = "No reply from the mixer; the effect is unknown")
                reply.value == null -> UtilityWriteResult(UtilityWriteStatus.REJECTED, id, value, message = "The mixer reported this item as unavailable")
                reply.value == value -> UtilityWriteResult(UtilityWriteStatus.CONFIRMED, id, value, reportedValue = reply.value)
                else -> UtilityWriteResult(UtilityWriteStatus.MISMATCH, id, value, reportedValue = reply.value, message = "The mixer reports a different value")
            }
        }
    }
}
