package com.beatlinkmonitor.app

import com.beatlinkmonitor.api.startApiServer
import io.github.oshai.kotlinlogging.KotlinLogging

// Deliberately lazy: this file's `main()` is the JVM entry point, so this
// class's static initializer (where a plain `= KotlinLogging.logger {}`
// would run) executes *before* `main()`'s own body - including before
// `LogSetup.applyDefault()` can set the log directory. `by lazy` defers the
// actual logger/logback initialization until the first time it is used
// (i.e. the first `logger.info { ... }` call below), which is safely after
// that call (see [main]).
private val logger by lazy { KotlinLogging.logger {} }

/**
 * Starts every backend subsystem (Pro DJ Link, simulator, outputs, Web UI
 * API/WebSocket) against the given [context]. Shared by both the headless
 * entry point ([main], for server-only/Raspberry-Pi-kiosk use per
 * requirements specification §18.3/§18.4) and the native desktop GUI entry point
 * ([com.beatlinkmonitor.app.DesktopApp]), so both "feel" like the exact same
 * application - just with a different window around the identical backend.
 *
 * Implements the "first coding task" from requirements specification §46:
 *
 * 1. Project starts.
 * 2. beat-link initializes (Pro DJ Link discovery + state tracking).
 * 3. The network interface can be chosen via configuration.
 * 4. Pro DJ Link devices are discovered.
 * 5. Players 1-6 are tracked as [com.beatlinkmonitor.model.PlayerState].
 * 6. Changes are logged.
 * 7. The simulator can alternatively feed the very same PlayerState objects.
 * 8. A WebSocket exposes the current state as JSON.
 */
fun bootstrapBackend(context: AppContext) {
    logger.info { "Beat Link Monitor v$APPLICATION_VERSION starting" }
    logger.info { "Operating system: ${System.getProperty("os.name")} ${System.getProperty("os.version")}" }
    logger.info { "Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})" }
    logger.info { "Configuration loaded from ${context.configurationService.rootDir}" }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info { "Shutting down..." }
            runCatching { context.outputManager.stopAll() }
            runCatching { context.simulationService.stop() }
            runCatching { context.proDjLinkAdapter.stop() }
            runCatching { context.apiServer?.stop(1_000, 2_000) }
        },
    )

    // Each subsystem is started independently; one failing subsystem must
    // never prevent the others from running (requirements specification §23, §43). The Pro
    // DJ Link stack is started on its own thread because `beat-link`'s
    // VirtualCdj blocks for up to ~30s retrying device discovery when no
    // network is present yet - the simulator and the web UI must remain
    // usable immediately regardless of that (requirements specification §16, §43).
    Thread({
        val manualInterface = context.applicationConfig
            .takeIf { it.networkInterfaceMode == "MANUAL" }
            ?.manualInterfaceName
        runCatching {
            context.proDjLinkAdapter.start(
                manualInterface,
                context.applicationConfig.preferredDeviceNumber,
                context.applicationConfig.waveformStylePreference,
            )
        }
            .onFailure { logger.error(it) { "Pro DJ Link adapter failed to start" } }
    }, "pro-dj-link-startup").apply { isDaemon = true }.start()

    if (context.applicationConfig.developmentMode || context.applicationConfig.simulatorAutoStart) {
        runCatching { context.simulationService.start() }
            .onFailure { logger.error(it) { "Simulator failed to start" } }
        context.diagnostics.simulatorRunning = true
        logger.info { "Development mode: simulator auto-started" }
    }

    runCatching { context.outputManager.startAll() }
        .onFailure { logger.error(it) { "Output manager failed to start" } }

    runCatching { context.apiServer = startApiServer(context) }
        .onFailure { logger.error(it) { "Web UI API server failed to start" } }

    logger.info { "Beat Link Monitor is running. Web UI: http://127.0.0.1:${context.applicationConfig.webUiPort}" }
}

/**
 * Headless entry point: runs the backend (including the embedded Web UI
 * server) without any native window, for server/Raspberry-Pi/Chromium-kiosk
 * deployments (requirements specification §18.3, §18.4). For the native control window
 * and browser launcher, see [com.beatlinkmonitor.app.DesktopApp] instead.
 */
fun main() {
    LogSetup.applyDefault() // must run before any logger (including AppContext's) is touched
    val context = AppContext()
    bootstrapBackend(context)

    // Keep the JVM alive; all real work happens on the coroutines/executors
    // started above (requirements specification §19).
    Thread.currentThread().join()
}
