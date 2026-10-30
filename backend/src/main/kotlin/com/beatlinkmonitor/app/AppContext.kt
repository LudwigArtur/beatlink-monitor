package com.beatlinkmonitor.app

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.config.ConfigurationService
import com.beatlinkmonitor.diagnostics.DiagnosticsService
import com.beatlinkmonitor.cdj.CdjLinkService
import com.beatlinkmonitor.cdj.RemoteUnlockService
import com.beatlinkmonitor.mixer.AudioLevelService
import com.beatlinkmonitor.mixer.MixerLinkService
import com.beatlinkmonitor.mixer.PeerAnnouncer
import com.beatlinkmonitor.outputs.OutputManager
import com.beatlinkmonitor.prodjlink.PacketRecorder
import com.beatlinkmonitor.prodjlink.ProDjLinkAdapter
import com.beatlinkmonitor.prodjlink.VirtualCdjMixerTransport
import com.beatlinkmonitor.state.ApplicationState
import com.beatlinkmonitor.waveform.WaveformCache

const val APPLICATION_VERSION = "0.1.0"

/**
 * Wires together every service described in requirements specification §36 ("Internes
 * API-Modell"). Dependencies are passed through constructors rather than
 * looked up via global singletons (coding guideline #7, #8).
 */
class AppContext(
    val configurationService: ConfigurationService = ConfigurationService(),
) {
    var applicationConfig: ApplicationConfig = configurationService.loadApplicationConfig()

    val applicationState = ApplicationState()
    val waveformCache = WaveformCache()

    val proDjLinkAdapter = ProDjLinkAdapter(applicationState, waveformCache)

    val packetRecorder = PacketRecorder { java.io.File(configurationService.rootDir, "recordings") }
    val mixerTransport: VirtualCdjMixerTransport =
        VirtualCdjMixerTransport({ data, length ->
            mixerLink.handlePacket(data, length)
            cdjLink.handlePacket(data, length)
        }, packetRecorder, { data, length -> audioLevels.handlePacket(data, length) })
    val peerAnnouncer: PeerAnnouncer = PeerAnnouncer({ applicationConfig }, mixerTransport.announcementTransport)
    val mixerLink: MixerLinkService = MixerLinkService(
        applicationState,
        { applicationConfig },
        mixerTransport,
        { mixerTransport.ensureInstalled() },
        { peerAnnouncer.status() },
    ).also { mixerTransport.identityOverride = { peerAnnouncer.activeIdentity() } }
    val remoteUnlock = RemoteUnlockService(
        pinFile = { java.io.File(java.io.File(configurationService.rootDir, RemoteUnlockService.SECURITY_DIR_NAME), RemoteUnlockService.PIN_STORE_NAME) },
        legacyPinFile = { java.io.File(configurationService.rootDir, RemoteUnlockService.LEGACY_PIN_FILE_NAME) },
    )
    val audioLevels: AudioLevelService = AudioLevelService(applicationState, { applicationConfig }, mixerTransport.audioTransport)
    val cdjLink: CdjLinkService = CdjLinkService(applicationState, { applicationConfig }, mixerTransport)
    val outputManager = OutputManager(applicationState, configurationService)
    val diagnostics = DiagnosticsService(
        applicationState,
        proDjLinkAdapter,
        outputManager,
        APPLICATION_VERSION,
    )

    // Set once startApiServer() completes (see bootstrapBackend in Main.kt) so a native
    // desktop window (DesktopApp) can cleanly shut the embedded Netty engine down on quit -
    // without this, Netty's non-daemon worker threads would keep the JVM process alive in
    // the background even after the window itself had closed.
    var apiServer: io.ktor.server.engine.ApplicationEngine? = null
}
