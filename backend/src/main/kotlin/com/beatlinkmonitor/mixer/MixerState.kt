package com.beatlinkmonitor.mixer

import kotlinx.serialization.Serializable

/** The last reply received for one utility setting; [value] is null while the mixer reported it as unavailable. */
@Serializable
data class UtilityValue(
    val id: Int,
    val value: Int?,
    val timestampMs: Long,
)

/**
 * What the application currently knows about the mixer. Nothing here is ever
 * guessed: a utility value only appears after the mixer replied with it, and
 * [live] stays null until a live-state packet was actually received.
 */
@Serializable
data class MixerState(
    val live: MixerLiveState? = null,
    val liveTimestampMs: Long = 0,
    val utilities: List<UtilityValue> = emptyList(),
    /** Firmware version string from the mixer's answer to a version request, if it answered. */
    val version: String? = null,
)

@Serializable
data class MixerLinkStatus(
    val integrationEnabled: Boolean,
    val controlEnabled: Boolean,
    val transportAvailable: Boolean,
    val mixerAddress: String?,
    /** Device name the mixer announces (e.g. "DJM-V10"), so it is always clear which model the data belongs to. */
    val mixerModel: String?,
    val liveFramesReceived: Long,
    val utilityRepliesReceived: Long,
    val lastLiveFrameAgeMs: Long?,
    val lastError: String?,
    val announcer: AnnouncerStatus?,
    val utilityItems: List<UtilityItem>,
)

@Serializable
enum class UtilityWriteStatus {
    /** The mixer replied to the read-back with the requested value. */
    CONFIRMED,

    /** The mixer replied with a different value. */
    MISMATCH,

    /** The mixer replied that the item is unavailable/invalid. */
    REJECTED,

    /** The request was sent but no reply arrived in time, so the effect is unknown. */
    NO_REPLY,

    /** Mixer control is switched off in the settings; nothing was sent. */
    CONTROL_DISABLED,

    /** No mixer address or no usable network transport; nothing was sent. */
    NO_MIXER,

    /** Unknown utility id or value out of range; nothing was sent. */
    INVALID,
}

@Serializable
data class UtilityWriteResult(
    val status: UtilityWriteStatus,
    val id: Int,
    val requestedValue: Int,
    val reportedValue: Int? = null,
    val message: String? = null,
)

@Serializable
data class MixerSnapshot(
    val state: MixerState,
    val link: MixerLinkStatus,
)

@Serializable
data class UtilityWriteRequest(val value: Int)
