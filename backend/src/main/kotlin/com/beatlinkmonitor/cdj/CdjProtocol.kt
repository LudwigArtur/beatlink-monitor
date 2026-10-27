package com.beatlinkmonitor.cdj

import com.beatlinkmonitor.mixer.MixerProtocol
import kotlinx.serialization.Serializable

/** One per-player setting of a CDJ; [offset] is the absolute byte position in the settings frames (0x29..0x73). */
@Serializable
data class CdjSettingItem(
    val offset: Int,
    val key: String,
    val label: String,
    val min: Int,
    val max: Int,
    val note: String? = null,
)

/**
 * Deck state of a type-0x69 reply. Offsets and how each one was verified on CDJ-3000s (fw 3.22): tempoReset 0x27
 * (TEMPO RESET button), trackBpm 0x28 (u16, BPM x 100, matches the track BPM of all four decks), pitchPercent 0x2a
 * (i16, % x 100, matches the status packets), masterTempo 0x2c, keySync 0x2d, sync 0x2e (SYNC button via remote),
 * tempoMaster 0x2f (matches the master flag; only one master seen), vinylMode 0x30 (setting "Jog Mode Vinyl"),
 * slip 0x32 (SLIP via remote), autoCue 0x33 (setting "Auto Cue"), quantize 0x34 (QUANTIZE via remote).
 * [unknown31] at 0x31 never changed (jog touch, settings): its meaning is open.
 */
@Serializable
data class CdjDeckFlags(
    val tempoReset: Boolean,
    val trackBpm: Double,
    val pitchPercent: Double,
    val masterTempo: Boolean,
    val keySync: Boolean,
    val sync: Boolean,
    val tempoMaster: Boolean,
    val vinylMode: Boolean,
    val unknown31: Int,
    val slip: Boolean,
    val autoCue: Boolean,
    val quantize: Boolean,
)

/** Frames used to read and change the per-player settings and to press a CDJ's buttons remotely. */
object CdjProtocol {
    const val STATUS_PORT = 50002
    const val BEAT_PORT = 50001

    const val TYPE_EXTERNAL_CONTROL = 0x07
    const val TYPE_STATUS_REQUEST = 0x68
    const val TYPE_STATUS_REPLY = 0x69
    const val TYPE_SETTINGS_REQUEST = 0x6b
    const val TYPE_SETTINGS_REPLY = 0x6c

    const val SETTINGS_FIRST_OFFSET = 0x29
    const val SETTINGS_LAST_OFFSET = 0x73
    const val SETTINGS_FRAME_LENGTH = 0x74
    private const val STATUS_REPLY_MIN_LENGTH = 0x35
    const val EXTERNAL_CONTROL_NAME = "CDJ-2000NXS2"

    private val MAGIC = "Qspt1WmJOL".toByteArray(Charsets.US_ASCII)
    private const val NAME_OFFSET = 0x0b
    private const val NAME_LENGTH = 20

    val SETTINGS: List<CdjSettingItem> = listOf(
        CdjSettingItem(0x29, "screenSaver", "Screen Saver", 0, 1),
        CdjSettingItem(0x2a, "autoStandby", "Auto Standby", 0, 1),
        CdjSettingItem(0x2b, "outputAtt", "Output Attenuation", 0, 4, "0..4 = -12 to 0 dB"),
        CdjSettingItem(0x2c, "onAirFunction", "On Air Function", 0, 1),
        CdjSettingItem(0x2d, "lcdBrightness", "LCD Brightness", 1, 5),
        CdjSettingItem(0x2e, "quantize", "Quantize", 0, 1),
        CdjSettingItem(0x2f, "autoCueLevel", "Auto Cue Level", 0, 8, "0 = -36 dB ... 7 = -78 dB, 8 = Memory"),
        CdjSettingItem(0x30, "language", "Language", 1, 18),
        CdjSettingItem(0x31, "jacketDisplay", "Jacket Display", 0, 1),
        CdjSettingItem(0x32, "jogRingBrightness", "Jog Ring Brightness", 0, 2),
        CdjSettingItem(0x33, "jogRingFlashing", "Jog Ring Flashing", 0, 1),
        CdjSettingItem(0x34, "slipBlink", "Slip Blink", 0, 1),
        CdjSettingItem(0x35, "trackInfoType", "Track Info Type", 0, 1),
        CdjSettingItem(0x36, "playlistView", "Playlist View", 0, 1),
        CdjSettingItem(0x37, "hidSettingType", "HID Setting", 0, 1, "0 = Standard, 1 = Advanced"),
        CdjSettingItem(0x38, "discSlotBrightness", "Disc Slot Brightness", 0, 2),
        CdjSettingItem(0x39, "ejectLoadLock", "Eject/Load Lock", 0, 1),
        CdjSettingItem(0x3a, "sync", "Sync", 0, 1),
        CdjSettingItem(0x3b, "filePlayMode", "File Play Mode", 0, 1, "0 = Continue, 1 = Single"),
        CdjSettingItem(0x3c, "quantizeBeat", "Quantize Beat", 0, 3, "0 = 1, 1 = 1/2, 2 = 1/4, 3 = 1/8"),
        CdjSettingItem(0x3d, "hotCueAutoLoad", "Hot Cue Auto Load", 0, 2),
        CdjSettingItem(0x3e, "hotCueAutoColor", "Hot Cue Color", 0, 1),
        CdjSettingItem(0x3f, "screen2Brightness", "2nd Screen Brightness", 1, 5),
        CdjSettingItem(0x40, "logoIllumination", "Logo Illumination", 0, 1),
        CdjSettingItem(0x41, "needleLock", "Needle Lock", 0, 1),
        CdjSettingItem(0x44, "timeModeRemain", "Time Mode Remain", 0, 1),
        CdjSettingItem(0x45, "jogModeVinyl", "Jog Mode Vinyl", 0, 1),
        CdjSettingItem(0x46, "autoCue", "Auto Cue", 0, 1),
        CdjSettingItem(0x47, "masterTempo", "Master Tempo", 0, 1),
        CdjSettingItem(0x48, "tempoRange", "Tempo Range", 0, 3, "0 = 6 %, 1 = 10 %, 2 = 16 %, 3 = Wide"),
        CdjSettingItem(0x49, "phaseMeterStyle", "Phase Meter Style", 0, 1, "0 = Beat, 1 = Seamless"),
        CdjSettingItem(0x4c, "vinylSpeedAdjust", "Vinyl Speed Adjust", 0, 2),
        CdjSettingItem(0x4d, "jogDisplayMode", "Jog Display Mode", 0, 3),
        CdjSettingItem(0x4e, "padBrightness", "Pad Brightness", 1, 4),
        CdjSettingItem(0x4f, "jogLcdBrightness", "Jog LCD Brightness", 1, 5),
        CdjSettingItem(0x50, "waveformDivisions", "Waveform Divisions", 0, 1, "0 = Time, 1 = Phrase"),
        CdjSettingItem(0x51, "gateCue", "Gate Cue", 0, 1),
        CdjSettingItem(0x52, "smartCue", "Smart Cue", 0, 1),
        CdjSettingItem(0x53, "rotarySelectorBrightness", "Rotary Selector Brightness", 0, 2),
        CdjSettingItem(0x56, "waveformPhaseMeter", "Waveform / Phase Meter", 0, 1),
        CdjSettingItem(0x57, "jogDisplayArtwork", "Jog Display Artwork", 0, 2, "0 = Normal, 1 = None, 2 = Large"),
        CdjSettingItem(0x58, "beatJumpBeats", "Beat Jump Beats", 0, 7, "0..7 = 1/2 to 64 beats"),
    )

    fun settingItem(offset: Int): CdjSettingItem? = SETTINGS.firstOrNull { it.offset == offset }

    private fun frame(type: Int, senderName: String, deviceNumber: Int, payloadLength: Int): ByteArray {
        val frame = ByteArray(0x24 + payloadLength)
        MAGIC.copyInto(frame)
        frame[0x0a] = type.toByte()
        val name = senderName.toByteArray(Charsets.US_ASCII)
        name.copyInto(frame, NAME_OFFSET, 0, minOf(name.size, NAME_LENGTH))
        frame[0x1f] = 1
        frame[0x20] = 0
        frame[0x21] = deviceNumber.toByte()
        frame[0x22] = (payloadLength shr 8).toByte()
        frame[0x23] = payloadLength.toByte()
        return frame
    }

    fun buildStatusRequest(senderName: String, deviceNumber: Int): ByteArray = frame(TYPE_STATUS_REQUEST, senderName, deviceNumber, 0)

    fun buildSettingsRead(senderName: String, deviceNumber: Int): ByteArray =
        frame(TYPE_SETTINGS_REQUEST, senderName, deviceNumber, SETTINGS_FRAME_LENGTH - 0x24)

    /** Writes a single setting: byte 0x24 = 1 and the value at the item's own offset, sent as `0x80 | value`. */
    fun buildSettingsWrite(senderName: String, deviceNumber: Int, offset: Int, value: Int): ByteArray {
        require(offset in SETTINGS_FIRST_OFFSET..SETTINGS_LAST_OFFSET) { "Setting offset out of range" }
        val frame = frame(TYPE_SETTINGS_REQUEST, senderName, deviceNumber, SETTINGS_FRAME_LENGTH - 0x24)
        frame[0x24] = 1
        frame[offset] = ((value and 0x7f) or 0x80).toByte()
        return frame
    }

    private fun hasHeader(data: ByteArray, length: Int, type: Int): Boolean {
        if (length < 0x24 || length > data.size) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return (data[0x0a].toInt() and 0xff) == type
    }

    class SettingsReply(val deviceNumber: Int, val values: Map<Int, Int>)

    /** Reads every table item out of a type-0x6c reply; values are the low seven bits of the wire bytes. */
    fun parseSettingsReply(data: ByteArray, length: Int): SettingsReply? {
        if (!hasHeader(data, length, TYPE_SETTINGS_REPLY) || length < SETTINGS_FRAME_LENGTH) return null
        val device = data[0x21].toInt() and 0xff
        if (device !in 1..6) return null
        val values = SETTINGS.associate { it.offset to (data[it.offset].toInt() and 0x7f) }
        return SettingsReply(device, values)
    }

    class StatusReply(val deviceNumber: Int, val flags: CdjDeckFlags, val raw: List<Int>)

    fun parseStatusReply(data: ByteArray, length: Int): StatusReply? {
        if (!hasHeader(data, length, TYPE_STATUS_REPLY) || length < STATUS_REPLY_MIN_LENGTH) return null
        val device = data[0x21].toInt() and 0xff
        if (device !in 1..6) return null
        fun u(offset: Int) = data[offset].toInt() and 0xff
        fun on(offset: Int) = u(offset) == 1
        val bpm = (u(0x28) shl 8) or u(0x29)
        val pitch = ((u(0x2a) shl 8) or u(0x2b)).toShort().toInt()
        val flags = CdjDeckFlags(
            tempoReset = on(0x27),
            trackBpm = bpm / 100.0,
            pitchPercent = pitch / 100.0,
            masterTempo = on(0x2c),
            keySync = on(0x2d),
            sync = on(0x2e),
            tempoMaster = on(0x2f),
            vinylMode = on(0x30),
            unknown31 = u(0x31),
            slip = on(0x32),
            autoCue = on(0x33),
            quantize = on(0x34),
        )
        return StatusReply(device, flags, (0x24 until length.coerceAtMost(0x60)).map { u(it) })
    }

    // --- Remote buttons -----------------------------------------------------------------------------------

    enum class ButtonAction(val code: Int) { DISCONNECT(2), CONNECT(1), COMMAND(0) }

    /** What a button press looks like on the wire: a click is down+up in one frame, hold buttons (Cue) send down and up separately. */
    enum class Press { CLICK, DOWN, UP }

    /**
     * External-control frame to a player's beat port: connect (a = 1) every few seconds while remote control is
     * used, command (a = 0) with one or two 6-byte entries (button, state), disconnect (a = 2) at the end. The
     * sender id is obfuscated like in the mixer utility frames and the name is the one the players expect.
     */
    fun buildExternalControl(ownDeviceNumber: Int, mac: ByteArray, action: ButtonAction, button: Int = 0, press: Press = Press.CLICK): ByteArray {
        val states = if (action != ButtonAction.COMMAND) emptyList() else when (press) {
            Press.CLICK -> listOf(1, 0)
            Press.DOWN -> listOf(1)
            Press.UP -> listOf(0)
        }
        val frame = frame(TYPE_EXTERNAL_CONTROL, EXTERNAL_CONTROL_NAME, 0, 6 + 6 * states.size)
        frame[0x25] = action.code.toByte()
        frame[0x29] = states.size.toByte()
        states.forEachIndexed { k, state ->
            val base = 0x2a + 6 * k
            frame[base] = (button shr 8).toByte()
            frame[base + 1] = button.toByte()
            frame[base + 3] = state.toByte()
        }
        frame[0x21] = MixerProtocol.encodeOwnId(frame, ownDeviceNumber, mac).toByte()
        return frame
    }
}

@Serializable
data class CdjRemoteButton(val code: Int, val key: String, val label: String, val group: String)

object CdjRemoteButtons {
    val ALL: List<CdjRemoteButton> = buildList {
        add(CdjRemoteButton(20, "playPause", "Play / Pause", "Transport"))
        add(CdjRemoteButton(21, "cue", "Cue", "Transport"))
        add(CdjRemoteButton(13, "sync", "Sync", "Transport"))
        add(CdjRemoteButton(12, "master", "Master", "Transport"))
        add(CdjRemoteButton(14, "masterTempo", "Master Tempo", "Transport"))
        add(CdjRemoteButton(10, "quantize", "Quantize", "Transport"))
        add(CdjRemoteButton(11, "slip", "Slip", "Transport"))
        add(CdjRemoteButton(22, "reverse", "Reverse", "Transport"))
        add(CdjRemoteButton(23, "slipReverse", "Slip Reverse", "Transport"))
        for (i in 0 until 8) add(CdjRemoteButton(70 + i, "hotCue${'A' + i}", "Hot Cue ${'A' + i}", "Hot cues"))
        for (i in 0 until 8) add(CdjRemoteButton(80 + i, "deleteHotCue${'A' + i}", "Delete Hot Cue ${'A' + i}", "Hot cues"))
        add(CdjRemoteButton(30, "loopIn", "Loop In", "Loops"))
        add(CdjRemoteButton(31, "loopOut", "Loop Out", "Loops"))
        add(CdjRemoteButton(32, "reloop", "Reloop / Exit", "Loops"))
        add(CdjRemoteButton(36, "fourBeatLoop", "4-Beat Loop / Exit", "Loops"))
        add(CdjRemoteButton(34, "loopCutter", "Loop Cutter", "Loops"))
        add(CdjRemoteButton(35, "loopDouble", "Loop Double", "Loops"))
        val beatLoops = listOf(40 to "1/32", 41 to "1/16", 42 to "1/8", 43 to "1/4", 45 to "1/2", 47 to "1", 48 to "2", 49 to "4", 51 to "8", 53 to "16", 54 to "32", 55 to "64")
        beatLoops.forEach { (code, label) -> add(CdjRemoteButton(code, "beatLoop$code", "Beat Loop $label", "Loops")) }
        val jumps = listOf(1, 2, 4, 8, 16, 32, 64)
        jumps.forEachIndexed { i, beats ->
            add(CdjRemoteButton(90 + 2 * i, "jumpBack$beats", "Jump back $beats", "Beat jump"))
            add(CdjRemoteButton(91 + 2 * i, "jumpForward$beats", "Jump forward $beats", "Beat jump"))
        }
    }

    fun byCode(code: Int): CdjRemoteButton? = ALL.firstOrNull { it.code == code }
}
