package com.beatlinkmonitor.model

import kotlinx.serialization.Serializable

/**
 * Master clock state, derived *exclusively* from the current Pro DJ Link
 * tempo master (requirements specification §8). No heuristic master (on-air, play state,
 * volume, etc.) may ever populate this in Version 1.
 */
@Serializable
data class MasterClockState(
    val masterPlayerNumber: Int? = null,
    val trackBpm: Double? = null,
    val effectiveBpm: Double? = null,
    val beatNumber: Int? = null,
    val beatWithinBar: Int? = null,
    val phase: Double = 0.0,
    val lastUpdateTimestamp: Long = 0L,
    val valid: Boolean = false,
) {
    companion object {
        val INVALID = MasterClockState()
    }
}
