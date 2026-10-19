package com.beatlinkmonitor.outputs

import com.beatlinkmonitor.config.ConfigurationService
import com.beatlinkmonitor.outputs.ableton.AbletonLinkOutput
import com.beatlinkmonitor.outputs.grandma.GrandMaTelnetOutput
import com.beatlinkmonitor.state.ApplicationState
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Owns and coordinates all [OutputModule] instances. New output protocols
 * (OSC, MIDI, Art-Net, sACN, ...) are added here as independent modules
 * without touching existing ones (requirements specification §14, coding guideline #15).
 */
class OutputManager(
    private val applicationState: ApplicationState,
    private val configurationService: ConfigurationService,
) {
    private val outputsConfig = configurationService.loadOutputsConfig()

    val grandMa = GrandMaTelnetOutput(applicationState, outputsConfig.grandMa)
    val abletonLink = AbletonLinkOutput(applicationState, outputsConfig.abletonLink)

    private val modules: List<OutputModule> = listOf(grandMa, abletonLink)

    fun startAll() {
        modules.forEach { module ->
            try {
                module.start()
            } catch (e: Exception) {
                logger.error(e) { "Output module '${module.name}' failed to start - continuing without it" }
            }
        }
    }

    fun stopAll() {
        modules.forEach { module ->
            try {
                module.stop()
            } catch (e: Exception) {
                logger.warn(e) { "Error stopping output module '${module.name}'" }
            }
        }
    }

    fun statuses(): List<OutputStatus> = modules.map { it.getStatus() }

    fun reloadFromConfig(outputsConfig: com.beatlinkmonitor.config.OutputsConfig) {
        grandMa.updateConfig(outputsConfig.grandMa)
        abletonLink.updateConfig(outputsConfig.abletonLink)
        configurationService.saveOutputsConfig(outputsConfig)
    }
}
