package com.beatlinkmonitor.config

import kotlinx.serialization.Serializable

const val CURRENT_CONFIG_VERSION = 1

@Serializable
data class ApplicationConfig(
    val configVersion: Int = CURRENT_CONFIG_VERSION,
    val networkInterfaceMode: String = "AUTOMATIC", // AUTOMATIC | MANUAL
    val manualInterfaceName: String? = null,
    // The device number our own virtual CDJ claims on the Pro DJ Link network
    // (requirements specification: user-selectable at startup, with conflict prevention).
    // 0 = automatic self-assignment (beat-link picks the lowest free number,
    // preferring 1-4 - see ProDjLinkAdapter.start()); 1-15 = a specific
    // number the user explicitly chose. The UI only lets the user pick a
    // number that is not currently occupied by a real device, but the
    // Pro DJ Link claim protocol itself (via beat-link) still defends
    // against any conflict regardless (e.g. if a real device joins the
    // network only after this was saved).
    val preferredDeviceNumber: Int = 0,
    // Which waveform style we ask real CDJ-3000s to send us (requirements specification
    // §9.2/§9.3): "RGB" is the widely-compatible NXS2-style color waveform
    // (beat-link's own default); "THREE_BAND" requests the CDJ-3000's
    // native, richer per-frequency-band style instead (dark blue/amber/
    // white layers - see beat-link's `WaveformFinder.ThreeBandLayer`).
    // Hardware that cannot produce the requested style (anything other
    // than a CDJ-3000) transparently falls back to whatever it *can*
    // provide, per beat-link's own documented behavior - this setting is
    // only ever a *preference*, never a hard requirement. Requires a full
    // application restart to take effect, like the other Pro DJ Link
    // network-level settings above.
    val waveformStylePreference: String = "RGB", // RGB | THREE_BAND
    // Mixer integration (see Docs/MIXER_CONTROL.md), on by default so that all
    // data of the real hardware is read:
    // - mixerIntegrationEnabled: listen for the mixer's live knob/fader
    //   packets (EQ -> waveform brightness), read its utility settings and
    //   firmware version. Hooks the Pro DJ Link status socket at startup, so
    //   changing it needs a restart.
    // - mixerControlEnabled: allow *writing* mixer settings (only ever on an
    //   explicit request from the UI/API; a write counts as confirmed only
    //   after the mixer reports the new value back).
    // - mixerAddress: optional IPv4 override; empty = the mixer found by
    //   Pro DJ Link discovery.
    val mixerIntegrationEnabled: Boolean = true,
    val mixerControlEnabled: Boolean = true,
    val mixerAddress: String? = null,
    // Announce this app as an additional third-party participant (category
    // 5, device number 54-57, like ShowKontrol) next to beat-link's virtual
    // CDJ. Verified on a real DJM-V10 and CDJ-3000s: without it the mixer
    // sends no live state and the players no extended status. Adds one more
    // device to the Pro DJ Link network. Needs the mixer integration.
    val mixerAnnounceIdentity: Boolean = true,
    val mixerIdentityName: String = "BEATLINK-MON",
    // CDJ integration (see Docs/CDJ_CONTROL.md), on by default:
    // - cdjIntegrationEnabled: read every CDJ's settings and deck flags
    //   (requests are harmless reads). Needs the mixer integration's socket hook.
    // - cdjControlEnabled: allow *writing* CDJ settings (explicit request
    //   only; counts as confirmed after the CDJ reports the new value).
    // - cdjRemoteEnabled: allow pressing CDJ buttons remotely (play, cue,
    //   hot cues ...). Off by default because it acts on live playback.
    val cdjIntegrationEnabled: Boolean = true,
    val cdjControlEnabled: Boolean = true,
    val cdjRemoteEnabled: Boolean = false,
    val webUiPort: Int = 17812,
    val allowLanAccess: Boolean = false,
    val bpmDecimalPlaces: Int = 2,
    val logLevel: String = "INFO",
    val activeProfile: String = "default",
)

@Serializable
data class GrandMaConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 30000,
    val username: String = "",
    val password: String = "",
    val loginTemplate: String = "login \"{USERNAME}\" \"{PASSWORD}\"",
    val commandTemplate: String = "SpecialMaster 3.1 At {BPM}",
    val sendOnChange: Boolean = true,
    val minimumChange: Double = 0.01,
    val maximumRateHz: Double = 10.0,
    val reconnect: Boolean = true,
    val keepalive: Boolean = true,
    // Which local network adapter the outbound Telnet connection to the
    // grandMA2 console is made from - independent of the adapter used for
    // Pro DJ Link discovery (ApplicationConfig.networkInterfaceMode), since
    // a machine with multiple NICs may need the console reachable via a
    // different network than the one the CDJs are on.
    val networkInterfaceMode: String = "AUTOMATIC", // AUTOMATIC | MANUAL
    val manualInterfaceName: String? = null,
)

@Serializable
enum class LinkPositionSource { AUTO, PRECISE, SMOOTHED }

@Serializable
data class AbletonLinkConfig(
    val enabled: Boolean = false,
    val carabinerHost: String = "127.0.0.1",
    val carabinerPort: Int = 17000,
    val quantum: Double = 4.0,
    val positionSource: LinkPositionSource = LinkPositionSource.AUTO,
    // Which local network adapter the outbound connection to the Carabiner
    // process is made from - independent of the Pro DJ Link adapter, same
    // rationale as GrandMaConfig above.
    val networkInterfaceMode: String = "AUTOMATIC", // AUTOMATIC | MANUAL
    val manualInterfaceName: String? = null,
)

@Serializable
data class OutputsConfig(
    val configVersion: Int = CURRENT_CONFIG_VERSION,
    val grandMa: GrandMaConfig = GrandMaConfig(),
    val abletonLink: AbletonLinkConfig = AbletonLinkConfig(),
)
