package com.beatlinkmonitor.outputs

import kotlinx.serialization.Serializable

/**
 * Common lifecycle every output protocol module implements (requirements specification
 * §14). The architecture must allow future modules (OSC, MIDI, Art-Net,
 * sACN, ...) to be added the same way, without touching existing ones
 * (coding guideline #15).
 */
interface OutputModule {
    val name: String
    fun start()
    fun stop()
    fun getStatus(): OutputStatus
}

@Serializable
data class OutputStatus(
    val name: String,
    val enabled: Boolean,
    val connected: Boolean,
    val lastError: String? = null,
    val lastSentAt: Long? = null,
    val lastSentPayload: String? = null,
    val detail: String? = null,
)
