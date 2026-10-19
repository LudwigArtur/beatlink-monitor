package com.beatlinkmonitor.api

import kotlinx.serialization.Serializable

@Serializable
data class WaveformResponse(
    val deviceNumber: Int,
    val type: String,
    val available: Boolean,
    val heights: List<Int> = emptyList(),
    val colors: List<String>? = null,
    // Populated instead of `heights`/`colors` when `type == "THREE_BAND"`:
    // independent per-frequency-band heights (0-31) for a CDJ-3000's native
    // waveform style, directly from beat-link's own `segmentHeight(segment,
    // scale, ThreeBandLayer)` accessors (coding guideline #13). The
    // frontend renders these as three overlapping colored layers using
    // beat-link's own documented `ThreeBandLayer` colors (dark blue/amber/
    // white for low/mid/high).
    val lowHeights: List<Int>? = null,
    val midHeights: List<Int>? = null,
    val highHeights: List<Int>? = null,
    val note: String? = null,
    // How many milliseconds of track-time a single entry in `heights`/
    // `colors` represents - the authoritative mapping from buffer index to
    // track-time, computed from the *actual* resolution this specific
    // response was rendered at (always exact, including after any
    // min/max column-count clamping for simulated tracks). Deliberately
    // NOT left for the client to infer from the player's separately
    // reported `trackDurationMs`: that duration comes from Pro DJ Link
    // metadata, which Pioneer only reports rounded to whole seconds, while
    // a real detail waveform's true, sub-second-precise implied duration is
    // `frameCount * (100.0/15.0)` ms (150 half-frames/sec, confirmed from
    // `beat-link`'s own frame<->time conversion). The small but real
    // mismatch between those two independently-sourced durations, if the
    // client naively spread the buffer evenly across the rounded duration
    // instead, previously showed up as the whole waveform image being
    // subtly mis-scaled in time relative to the (separately, correctly
    // per-beat-timestamped) beat grid - visible as the waveform imagery
    // appearing a beat or two early/late while the beat grid ticks stayed
    // perfectly aligned with real playback.
    val columnIntervalMs: Double = 0.0,
)
