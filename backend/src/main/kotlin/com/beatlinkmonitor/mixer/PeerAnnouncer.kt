package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.config.ApplicationConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

class NetworkIdentity(val ip: InetAddress, val mac: ByteArray)

/** What the announcer needs from the network side; the real one rides on beat-link's interface and device list. */
interface AnnouncementTransport {
    val available: Boolean
    fun networkIdentity(): NetworkIdentity?
    fun broadcast(data: ByteArray)
    fun peerCount(): Int
    fun deviceNumbersInUse(): Set<Int>
}

@kotlinx.serialization.Serializable
enum class AnnouncerState { OFF, WAITING, CLAIMING, ONLINE, NETWORK_FULL }

@kotlinx.serialization.Serializable
data class AnnouncerStatus(val state: AnnouncerState, val deviceNumber: Int?, val name: String)

/**
 * Optionally announces this application as an additional third-party
 * participant (category 5, device number 54-57), the way ShowKontrol does,
 * next to beat-link's virtual CDJ. Mixers appear to address their rich live
 * state only to such peers (unverified hypothesis, see `Docs/MIXER_CONTROL.md`).
 *
 * Sequence: random back-off, then three claim requests 300 ms apart for the
 * first free number in 54-57, then a keep-alive every 2 s; a disconnect frame
 * is sent when it is switched off. Nothing is sent unless both the mixer
 * integration and [ApplicationConfig.mixerAnnounceIdentity] are enabled.
 */
class PeerAnnouncer(
    private val config: () -> ApplicationConfig,
    private val transport: AnnouncementTransport,
    private val keepAliveIntervalMs: Long = PeerIdentityProtocol.KEEPALIVE_INTERVAL_MS,
    private val claimSpacingMs: Long = PeerIdentityProtocol.CLAIM_SPACING_MS,
    private val backoffMs: () -> Long = { Random.nextLong(300, 3000) },
    private val networkFullRetryMs: Long = 5000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var state = AnnouncerState.OFF
    @Volatile private var number: Int? = null

    fun status(): AnnouncerStatus = AnnouncerStatus(state, number.takeIf { state == AnnouncerState.ONLINE }, announceName())

    /** The identity requests should be signed with while online, or null. */
    fun activeIdentity(): Pair<String, Int>? = number?.takeIf { state == AnnouncerState.ONLINE }?.let { announceName() to it }

    private fun enabled(): Boolean = config().let { it.mixerIntegrationEnabled && it.mixerAnnounceIdentity }

    private fun announceName(): String =
        config().mixerIdentityName.filter { it.code in 0x20..0x7e }.take(20).ifBlank { "BEATLINK-MON" }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        sendDisconnect()
        scope.cancel()
        state = AnnouncerState.OFF
        number = null
    }

    private suspend fun run() {
        while (scope.isActive) {
            if (!enabled() || !transport.available) {
                if (state == AnnouncerState.ONLINE) sendDisconnect()
                state = AnnouncerState.OFF
                number = null
                delay(1000)
                continue
            }
            val identity = transport.networkIdentity()
            if (identity == null) {
                state = AnnouncerState.WAITING
                delay(1000)
                continue
            }
            state = AnnouncerState.CLAIMING
            delay(backoffMs())
            val claimed = claim(identity)
            if (claimed == null) {
                state = AnnouncerState.NETWORK_FULL
                logger.warn { "Mixer integration: all device numbers 54-57 are in use on the network, retrying" }
                delay(networkFullRetryMs)
                continue
            }
            number = claimed
            state = AnnouncerState.ONLINE
            logger.info { "Mixer integration: announcing as '${announceName()}' with device number $claimed (category 5)" }
            keepAlive(identity, claimed)
        }
    }

    private suspend fun claim(identity: NetworkIdentity): Int? {
        for (candidate in PeerIdentityProtocol.FIRST_DEVICE_NUMBER..PeerIdentityProtocol.LAST_DEVICE_NUMBER) {
            if (candidate in transport.deviceNumbersInUse()) continue
            var taken = false
            for (attempt in 1..PeerIdentityProtocol.CLAIM_ATTEMPTS) {
                transport.broadcast(PeerIdentityProtocol.buildIdRequest(announceName(), identity.ip, identity.mac, candidate, attempt))
                delay(claimSpacingMs)
                if (candidate in transport.deviceNumbersInUse()) {
                    taken = true
                    break
                }
            }
            if (!taken) return candidate
        }
        return null
    }

    private suspend fun keepAlive(identity: NetworkIdentity, claimed: Int) {
        while (scope.isActive && enabled() && transport.available) {
            if (claimed in transport.deviceNumbersInUse()) {
                logger.warn { "Mixer integration: another device uses number $claimed, claiming again" }
                return
            }
            transport.broadcast(
                PeerIdentityProtocol.buildKeepAlive(announceName(), claimed, identity.mac, identity.ip, transport.peerCount()),
            )
            delay(keepAliveIntervalMs)
        }
        if (state == AnnouncerState.ONLINE && (!enabled() || !scope.isActive)) sendDisconnect()
    }

    private fun sendDisconnect() {
        val claimed = number ?: return
        val identity = runCatching { transport.networkIdentity() }.getOrNull() ?: return
        if (state != AnnouncerState.ONLINE) return
        runCatching {
            transport.broadcast(PeerIdentityProtocol.buildDisconnect(announceName(), claimed, identity.mac, identity.ip))
        }
        number = null
        state = AnnouncerState.OFF
    }
}
