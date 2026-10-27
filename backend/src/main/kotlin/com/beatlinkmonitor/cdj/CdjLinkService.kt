package com.beatlinkmonitor.cdj

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.mixer.MixerTransport
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
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

@Serializable
data class CdjDeviceState(
    val deviceNumber: Int,
    /** Setting values by frame offset as last reported by the CDJ. */
    val settings: Map<Int, Int> = emptyMap(),
    val settingsTimestampMs: Long = 0,
    val flags: CdjDeckFlags? = null,
    val flagsRaw: List<Int> = emptyList(),
    val flagsTimestampMs: Long = 0,
)

@Serializable
data class CdjLinkStatus(
    val integrationEnabled: Boolean,
    val controlEnabled: Boolean,
    val remoteEnabled: Boolean,
    val transportAvailable: Boolean,
    val settingsRepliesReceived: Long,
    val statusRepliesReceived: Long,
    val lastError: String?,
    val settingItems: List<CdjSettingItem>,
    val buttons: List<CdjRemoteButton>,
)

@Serializable
data class CdjSnapshot(val devices: List<CdjDeviceState>, val link: CdjLinkStatus)

@Serializable
enum class CdjWriteStatus { CONFIRMED, MISMATCH, NO_REPLY, INVALID, NO_DEVICE, CONTROL_DISABLED }

@Serializable
data class CdjWriteResult(
    val status: CdjWriteStatus,
    val deviceNumber: Int,
    val offset: Int,
    val requestedValue: Int,
    val reportedValue: Int? = null,
    val message: String? = null,
)

@Serializable
enum class CdjRemoteStatus { SENT, REMOTE_DISABLED, NO_DEVICE, INVALID, FAILED }

@Serializable
data class CdjRemoteResult(val status: CdjRemoteStatus, val message: String? = null)

/**
 * Reads the per-player settings and deck flags of every CDJ (harmless requests, repeated every few
 * seconds the way the reference application does) and optionally changes a setting or presses a button
 * remotely. A setting write counts as confirmed only after the CDJ itself reports the new value;
 * button presses have no acknowledgement and are only reported as sent.
 */
class CdjLinkService(
    private val state: ApplicationState,
    private val config: () -> ApplicationConfig,
    private val transport: MixerTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devices = ConcurrentHashMap<Int, CdjDeviceState>()
    private val settingsSequence = ConcurrentHashMap<Int, AtomicLong>()
    private val lastStatusRequest = ConcurrentHashMap<Int, Long>()
    private val lastSettingsRequest = ConcurrentHashMap<Int, Long>()
    private val remoteSessions = ConcurrentHashMap<Int, RemoteSession>()
    private val writeLock = Mutex()
    private val settingsReplies = AtomicLong()
    private val statusReplies = AtomicLong()
    @Volatile private var lastError: String? = null
    private var supervisorJob: Job? = null

    private class RemoteSession(@Volatile var lastConnectAt: Long, @Volatile var lastCommandAt: Long)

    companion object {
        const val STATUS_INTERVAL_MS = 1000L
        const val SETTINGS_INTERVAL_MS = 5000L
        const val WRITE_READBACK_DELAY_MS = 150L
        const val REPLY_TIMEOUT_MS = 2000L
        const val REMOTE_KEEPALIVE_MS = 5000L
        const val REMOTE_IDLE_MS = 60_000L
        private const val SUPERVISOR_INTERVAL_MS = 500L
    }

    fun start() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            while (isActive) {
                runCatching { tick(System.currentTimeMillis()) }.onFailure { lastError = "CDJ polling failed: ${it.message}" }
                delay(SUPERVISOR_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        runCatching { remoteSessions.keys.toList().forEach { endRemoteSession(it) } }
        supervisorJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private class Target(val deviceNumber: Int, val address: InetAddress)

    private fun cdjTargets(): List<Target> = state.players.value.values.mapNotNull { player ->
        if (player.deviceType != DeviceType.CDJ || player.deviceNumber !in 1..6) return@mapNotNull null
        val address = parseLocalAddress(player.ipAddress ?: return@mapNotNull null) ?: return@mapNotNull null
        Target(player.deviceNumber, address)
    }

    private fun target(deviceNumber: Int): Target? = cdjTargets().firstOrNull { it.deviceNumber == deviceNumber }

    private fun parseLocalAddress(text: String): InetAddress? {
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(text)) return null
        val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null
        return address.takeIf { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress }
    }

    internal fun tick(now: Long) {
        val present = cdjTargets()
        devices.keys.retainAll(present.map { it.deviceNumber }.toSet())
        if (!config().cdjIntegrationEnabled || !transport.available) return
        val identity = transport.identity() ?: return
        for (target in present) {
            if (now - (lastStatusRequest[target.deviceNumber] ?: 0L) >= STATUS_INTERVAL_MS) {
                lastStatusRequest[target.deviceNumber] = now
                send(CdjProtocol.buildStatusRequest(identity.name, identity.deviceNumber), target.address, CdjProtocol.STATUS_PORT)
            }
            if (now - (lastSettingsRequest[target.deviceNumber] ?: 0L) >= SETTINGS_INTERVAL_MS) {
                lastSettingsRequest[target.deviceNumber] = now
                send(CdjProtocol.buildSettingsRead(identity.name, identity.deviceNumber), target.address, CdjProtocol.STATUS_PORT)
            }
        }
        for ((number, session) in remoteSessions) {
            when {
                now - session.lastCommandAt > REMOTE_IDLE_MS || !config().cdjRemoteEnabled -> endRemoteSession(number)
                now - session.lastConnectAt >= REMOTE_KEEPALIVE_MS -> connectRemote(number, now)
            }
        }
    }

    private fun send(frame: ByteArray, address: InetAddress, port: Int): Boolean = try {
        transport.send(frame, address, port)
        true
    } catch (e: Exception) {
        lastError = "Sending to a CDJ failed: ${e.message}"
        logger.warn(e) { "CDJ request failed" }
        false
    }

    /** Entry point for every raw packet received on the Pro DJ Link status port. */
    fun handlePacket(data: ByteArray, length: Int) {
        CdjProtocol.parseSettingsReply(data, length)?.let { reply ->
            settingsReplies.incrementAndGet()
            devices.compute(reply.deviceNumber) { number, old ->
                (old ?: CdjDeviceState(number)).copy(settings = reply.values, settingsTimestampMs = System.currentTimeMillis())
            }
            settingsSequence.computeIfAbsent(reply.deviceNumber) { AtomicLong() }.incrementAndGet()
            return
        }
        CdjProtocol.parseStatusReply(data, length)?.let { reply ->
            statusReplies.incrementAndGet()
            devices.compute(reply.deviceNumber) { number, old ->
                (old ?: CdjDeviceState(number)).copy(flags = reply.flags, flagsRaw = reply.raw, flagsTimestampMs = System.currentTimeMillis())
            }
        }
    }

    fun snapshot(): CdjSnapshot = CdjSnapshot(devices.values.sortedBy { it.deviceNumber }, status())

    fun status(): CdjLinkStatus {
        val cfg = config()
        return CdjLinkStatus(
            integrationEnabled = cfg.cdjIntegrationEnabled,
            controlEnabled = cfg.cdjControlEnabled,
            remoteEnabled = cfg.cdjRemoteEnabled,
            transportAvailable = transport.available,
            settingsRepliesReceived = settingsReplies.get(),
            statusRepliesReceived = statusReplies.get(),
            lastError = lastError,
            settingItems = CdjProtocol.SETTINGS,
            buttons = CdjRemoteButtons.ALL,
        )
    }

    suspend fun writeSetting(deviceNumber: Int, offset: Int, value: Int): CdjWriteResult {
        fun result(status: CdjWriteStatus, reported: Int? = null, message: String? = null) =
            CdjWriteResult(status, deviceNumber, offset, value, reported, message)
        val item = CdjProtocol.settingItem(offset) ?: return result(CdjWriteStatus.INVALID, message = "Unknown setting offset")
        if (value !in item.min..item.max) return result(CdjWriteStatus.INVALID, message = "Value must be within ${item.min}..${item.max}")
        val cfg = config()
        if (!cfg.cdjIntegrationEnabled || !transport.available) return result(CdjWriteStatus.NO_DEVICE, message = "CDJ integration is not active")
        val target = target(deviceNumber) ?: return result(CdjWriteStatus.NO_DEVICE, message = "Player $deviceNumber is not a CDJ on the network")
        if (!cfg.cdjControlEnabled) return result(CdjWriteStatus.CONTROL_DISABLED, message = "CDJ control is disabled in the settings")
        val identity = transport.identity() ?: return result(CdjWriteStatus.NO_DEVICE, message = "No local Pro DJ Link identity yet")
        return writeLock.withLock {
            val sequence = settingsSequence.computeIfAbsent(deviceNumber) { AtomicLong() }
            if (!send(CdjProtocol.buildSettingsWrite(identity.name, identity.deviceNumber, offset, value), target.address, CdjProtocol.STATUS_PORT)) {
                return@withLock result(CdjWriteStatus.NO_DEVICE, message = lastError)
            }
            delay(WRITE_READBACK_DELAY_MS)
            val before = sequence.get()
            if (!send(CdjProtocol.buildSettingsRead(identity.name, identity.deviceNumber), target.address, CdjProtocol.STATUS_PORT)) {
                return@withLock result(CdjWriteStatus.NO_REPLY, message = lastError)
            }
            val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && sequence.get() <= before) delay(25)
            if (sequence.get() <= before) return@withLock result(CdjWriteStatus.NO_REPLY, message = "No reply from the CDJ; the effect is unknown")
            val reported = devices[deviceNumber]?.settings?.get(offset)
            if (reported == value) result(CdjWriteStatus.CONFIRMED, reported)
            else result(CdjWriteStatus.MISMATCH, reported, "The CDJ reports a different value")
        }
    }

    /** Asks every CDJ for its settings right away instead of waiting for the next periodic read. */
    fun requestRefresh() {
        lastSettingsRequest.clear()
        lastStatusRequest.clear()
    }

    // --- Remote buttons -----------------------------------------------------------------------------------

    private fun connectRemote(deviceNumber: Int, now: Long): Boolean {
        val target = target(deviceNumber) ?: return false
        val identity = transport.identity() ?: return false
        val frame = CdjProtocol.buildExternalControl(identity.deviceNumber, identity.mac, CdjProtocol.ButtonAction.CONNECT)
        remoteSessions[deviceNumber]?.lastConnectAt = now
        return send(frame, target.address, CdjProtocol.BEAT_PORT)
    }

    private fun endRemoteSession(deviceNumber: Int) {
        remoteSessions.remove(deviceNumber) ?: return
        val target = target(deviceNumber) ?: return
        val identity = transport.identity() ?: return
        send(CdjProtocol.buildExternalControl(identity.deviceNumber, identity.mac, CdjProtocol.ButtonAction.DISCONNECT), target.address, CdjProtocol.BEAT_PORT)
    }

    fun pressButton(deviceNumber: Int, code: Int, press: CdjProtocol.Press = CdjProtocol.Press.CLICK): CdjRemoteResult {
        if (CdjRemoteButtons.byCode(code) == null) return CdjRemoteResult(CdjRemoteStatus.INVALID, "Unknown button code")
        val cfg = config()
        if (!cfg.cdjRemoteEnabled) return CdjRemoteResult(CdjRemoteStatus.REMOTE_DISABLED, "Remote control of CDJs is disabled in the settings")
        if (!cfg.cdjIntegrationEnabled || !transport.available) return CdjRemoteResult(CdjRemoteStatus.NO_DEVICE, "CDJ integration is not active")
        val target = target(deviceNumber) ?: return CdjRemoteResult(CdjRemoteStatus.NO_DEVICE, "Player $deviceNumber is not a CDJ on the network")
        val identity = transport.identity() ?: return CdjRemoteResult(CdjRemoteStatus.NO_DEVICE, "No local Pro DJ Link identity yet")
        val now = System.currentTimeMillis()
        val session = remoteSessions.computeIfAbsent(deviceNumber) { RemoteSession(0L, now) }
        session.lastCommandAt = now
        if (now - session.lastConnectAt >= REMOTE_KEEPALIVE_MS) connectRemote(deviceNumber, now)
        val frame = CdjProtocol.buildExternalControl(identity.deviceNumber, identity.mac, CdjProtocol.ButtonAction.COMMAND, code, press)
        return if (send(frame, target.address, CdjProtocol.BEAT_PORT)) CdjRemoteResult(CdjRemoteStatus.SENT)
        else CdjRemoteResult(CdjRemoteStatus.FAILED, lastError)
    }
}
