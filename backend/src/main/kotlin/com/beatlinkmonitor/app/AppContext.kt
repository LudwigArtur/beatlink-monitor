package com.beatlinkmonitor.app

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.config.ConfigurationService
import com.beatlinkmonitor.diagnostics.DiagnosticsService
import com.beatlinkmonitor.outputs.OutputManager
import com.beatlinkmonitor.prodjlink.ProDjLinkAdapter
import com.beatlinkmonitor.simulator.SimulationService
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
    val simulationService = SimulationService(applicationState)
    val outputManager = OutputManager(applicationState, configurationService)
    val diagnostics = DiagnosticsService(
        applicationState,
        proDjLinkAdapter,
        simulationService,
        outputManager,
        APPLICATION_VERSION,
    )

    // Set once startApiServer() completes (see bootstrapBackend in Main.kt) so a native
    // desktop window (DesktopApp) can cleanly shut the embedded Netty engine down on quit -
    // without this, Netty's non-daemon worker threads would keep the JVM process alive in
    // the background even after the window itself had closed.
    var apiServer: io.ktor.server.engine.ApplicationEngine? = null
}
