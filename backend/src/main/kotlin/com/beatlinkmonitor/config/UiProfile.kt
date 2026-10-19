package com.beatlinkmonitor.config

import kotlinx.serialization.Serializable

@Serializable
data class WidgetConfig(
    val id: String,
    val type: String,
    val visible: Boolean = true,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 200.0,
    val height: Double = 100.0,
    val order: Int = 0,
    val fontSize: Double = 14.0,
    val color: String? = null,
)

@Serializable
data class PlayerSlotConfig(
    val slot: Int,
    val sourceType: String = "PLAYER", // PLAYER | AUTO | CURRENT_MASTER | ON_AIR_1 | ON_AIR_2 | LAST_MASTER | NEXT_ACTIVE
    val playerNumber: Int? = null,
    val visible: Boolean = true,
    val waveformMode: String = "THREE_BAND",
    val showOverview: Boolean = true,
    val showDetail: Boolean = true,
    val order: Int = slot,
    val width: Double = 320.0,
    val height: Double = 220.0,
    val fontSize: Double = 14.0,
)

/** The default set of global widgets (requirements specification §10.3), all visible by default. */
val DEFAULT_GLOBAL_WIDGETS: List<WidgetConfig> = listOf(
    "masterBpm", "masterPlayer", "networkStatus", "prodjlinkStatus", "linkStatus", "grandmaStatus", "simulatorStatus",
).mapIndexed { index, id -> WidgetConfig(id = id, type = id, order = index) }

/**
 * A full UI profile as described in requirements specification §11: layout, visible widgets,
 * positions/sizes, player mappings, dynamic sources, colors, font sizes,
 * scaling, waveform settings and fullscreen state - all in one importable/
 * exportable JSON document.
 */
@Serializable
data class UiProfile(
    val configVersion: Int = CURRENT_CONFIG_VERSION,
    val name: String = "default",
    val widgets: List<WidgetConfig> = DEFAULT_GLOBAL_WIDGETS,
    val playerSlots: List<PlayerSlotConfig> = (1..6).map {
        PlayerSlotConfig(slot = it, playerNumber = it, visible = it <= 4, order = it)
    },
    val globalScale: Double = 1.0,
    val fullscreen: Boolean = false,
    val waveformHeight: Double = 80.0,
    val waveformMode: String = "THREE_BAND",
    val backgroundColor: String = "#101014",
    val widgetGap: Double = 12.0,
    // Brightness (0-100%) applied to Live View decks that are not currently
    // On-Air while the "Inaktive Decks abdunkeln" toggle is active - user-
    // configurable (Settings -> Anzeige) in 5% steps from 5 to 95.
    val inactiveDeckBrightnessPercent: Int = 50,
    // How aggressively the Live View's detail waveform playhead corrects
    // small drift between the extrapolated and actually-reported playback
    // position (see `LiveDetailWaveform.svelte`'s `SOFT_CORRECTION_FACTOR`
    // doc comment for the full rationale): each update nudges the
    // extrapolation anchor by this fraction of the current disagreement,
    // every time. Lower = smoother motion but slower to catch up to real
    // drift; higher = snappier/more precise but a correction becomes more
    // perceptible as a small, visible nudge. User-configurable (Settings ->
    // Display) since "how smooth vs. how precise" is a matter of taste that
    // depends on the specific hardware/network's jitter characteristics.
    val waveformSmoothingFactor: Double = 0.08,
)
