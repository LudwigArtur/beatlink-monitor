package com.beatlinkmonitor.api

import kotlinx.serialization.Serializable

/**
 * Beat grid for a track (requirements specification: the Live View's detail waveform
 * shows the beat grid above the waveform, like a CDJ's phrase/beat markers)
 * - a list of beat timestamps (nominal track-time, unaffected by pitch, like
 * the waveform buffers) plus each beat's position within its bar
 * (1 = downbeat) so the frontend can render emphasized bar-start ticks.
 *
 * Fetched once per track load, exactly like `/detail/full`.
 */
@Serializable
data class BeatGridResponse(
    val deviceNumber: Int,
    val available: Boolean,
    val beatTimesMs: List<Long> = emptyList(),
    val beatsWithinBar: List<Int> = emptyList(),
    val note: String? = null,
)
