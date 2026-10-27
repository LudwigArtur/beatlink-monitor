package com.beatlinkmonitor.mixer

import kotlinx.serialization.Serializable

/** One of the 15 mixer "utility" settings that can be read and written over Pro DJ Link port 50002. */
@Serializable
data class UtilityItem(
    val id: Int,
    val key: String,
    val label: String,
    val min: Int,
    val max: Int,
    val signed: Boolean = false,
    val note: String? = null,
)

/** The most recent live knob/fader state reported by the mixer (all values are raw, unscaled bytes). */
@Serializable
data class MixerChannelState(
    val channel: Int,
    val input: Int,
    val trim: Int,
    val comp: Int,
    val eqHi: Int,
    val eqHiMid: Int,
    val eqLoMid: Int,
    val eqLow: Int,
    val filter: Int,
    val send: Int,
    val cue: Boolean,
    val cue2: Int,
    val fader: Int,
    val crossfaderAssign: Int,
)

@Serializable
data class MixerLiveState(
    val channels: List<MixerChannelState>,
    val crossfader: Int,
    val masterLevel: Int,
    val masterFilter: Int,
    val masterCue: Int,
    val isolatorOn: Int,
    val isoHi: Int,
    val isoMid: Int,
    val isoLow: Int,
    val booth: Int,
    val boothHi: Int,
    val boothLow: Int,
    val fxFreqLow: Int,
    val fxFreqMid: Int,
    val fxFreqHi: Int,
    val fxSelect: Int,
    val fxChannel: Int,
    val fxLevel: Int,
    val fxOn: Int,
    val hpMix: Int,
    val hpLevel: Int,
)

/** A decoded utility reply. [valid] is false when the mixer reported an error/unavailable status for the item. */
data class UtilityReply(val id: Int, val value: Int, val valid: Boolean)

/**
 * Pro DJ Link mixer packets exchanged on UDP port 50002 that go beyond what
 * `beat-link` decodes (which only covers tempo/pitch/master/sync/beat of a
 * mixer): the live knob/fader state (type 0x39) and the utility-settings
 * read/write request (0x3a) / reply (0x3b).
 *
 * The layouts were reconstructed by static analysis only and have **not**
 * been verified against real mixer hardware yet - see
 * `Docs/MIXER_CONTROL.md` for the evidence and the open questions.
 */
object MixerProtocol {
    /** The Pro DJ Link status port. */
    const val PORT = 50002

    const val TYPE_LIVE_STATE = 0x39
    const val TYPE_UTILITY_REQUEST = 0x3a
    const val TYPE_UTILITY_REPLY = 0x3b

    private const val TYPE_OFFSET = 0x0a
    private const val NAME_OFFSET = 0x0b
    private const val NAME_LENGTH = 20
    private const val ID_OFFSET = 0x21
    private const val PAYLOAD_OFFSET = 0x24
    private const val ID_SUM_LIMIT = 63

    const val UTILITY_PACKET_LENGTH = 0x28
    private const val LIVE_PACKET_MIN_LENGTH = 0xf8
    private const val LIVE_PAYLOAD_MIN_LENGTH = 0xc5
    private const val LIVE_PAYLOAD_LENGTH = LIVE_PACKET_MIN_LENGTH - PAYLOAD_OFFSET

    private val MAGIC = "Qspt1WmJOL".toByteArray(Charsets.US_ASCII)

    val UTILITY_ITEMS: List<UtilityItem> = listOf(
        UtilityItem(0, "balance", "Balance", -128, 127, signed = true),
        UtilityItem(1, "masterLimiter", "Master Peak Limiter", 0, 1),
        UtilityItem(2, "masterAtt", "Master Out Attenuation", 0, 4, note = "0..4 = 0 to -12 dB"),
        UtilityItem(3, "masterMono", "Master Mono", 0, 1),
        UtilityItem(4, "digitalLevel", "Digital Master Level", 0, 8),
        UtilityItem(5, "digitalRate", "Digital Sampling Rate", 0, 3),
        UtilityItem(6, "digitalLimiter", "Digital Peak Limiter", 0, 1),
        UtilityItem(7, "digitalMono", "Digital Mono", 0, 1),
        UtilityItem(8, "boothAtt", "Booth Out Attenuation", 0, 4),
        UtilityItem(9, "boothMono", "Booth Mono", 0, 1),
        UtilityItem(10, "micLimiter", "Mic Limiter Threshold", 0, 6),
        UtilityItem(11, "micToBooth", "Mic To Booth Level", 0, 7),
        UtilityItem(12, "micToBoothLimiter", "Mic To Booth Limiter", 0, 6),
        UtilityItem(13, "boothMuteOnMic", "Booth Mute When Mic On", 0, 1),
        UtilityItem(14, "micToRecExclude", "Mic To Rec Exclude Mic", 0, 1),
    )

    fun utilityItem(id: Int): UtilityItem? = UTILITY_ITEMS.getOrNull(id)

    /**
     * Obfuscation of our own device number inside a utility request: the
     * number is XORed with the sum of all packet bytes (except the ID byte
     * itself, limited to the first 64 bytes) plus the last three MAC bytes,
     * truncated to 7 bits, with the top bit set. This is not cryptography.
     */
    fun encodeOwnId(packet: ByteArray, ownDeviceNumber: Int, mac: ByteArray, idOffset: Int = ID_OFFSET): Int {
        require(mac.size >= 6) { "MAC address must have 6 bytes" }
        var sum = 0
        for (i in 0 until minOf(packet.size, ID_SUM_LIMIT + 1)) {
            if (i != idOffset) sum += packet[i].toInt() and 0xff
        }
        sum += (mac[3].toInt() and 0xff) + (mac[4].toInt() and 0xff) + (mac[5].toInt() and 0xff)
        return ((ownDeviceNumber xor sum) and 0x7f) or 0x80
    }

    fun buildUtilityRequest(
        senderName: String,
        ownDeviceNumber: Int,
        mac: ByteArray,
        write: Boolean,
        id: Int,
        value: Int = 0,
    ): ByteArray {
        require(id in UTILITY_ITEMS.indices) { "Unknown utility id $id" }
        val packet = ByteArray(UTILITY_PACKET_LENGTH)
        MAGIC.copyInto(packet)
        packet[TYPE_OFFSET] = TYPE_UTILITY_REQUEST.toByte()
        val name = senderName.toByteArray(Charsets.US_ASCII)
        name.copyInto(packet, NAME_OFFSET, 0, minOf(name.size, NAME_LENGTH))
        packet[0x1f] = 2
        packet[0x20] = 0
        packet[0x22] = 0
        packet[0x23] = 4
        packet[PAYLOAD_OFFSET] = if (write) 1 else 0
        packet[PAYLOAD_OFFSET + 1] = id.toByte()
        packet[PAYLOAD_OFFSET + 2] = if (write) (value and 0xff).toByte() else 0
        packet[ID_OFFSET] = encodeOwnId(packet, ownDeviceNumber, mac).toByte()
        return packet
    }

    private fun hasHeader(data: ByteArray, length: Int, type: Int): Boolean {
        if (length <= TYPE_OFFSET || length > data.size) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return (data[TYPE_OFFSET].toInt() and 0xff) == type
    }

    /** Decodes a type-0x3b reply; returns null for anything that is not a well-formed utility reply. */
    fun parseUtilityReply(data: ByteArray, length: Int): UtilityReply? {
        if (!hasHeader(data, length, TYPE_UTILITY_REPLY) || length < UTILITY_PACKET_LENGTH) return null
        val id = data[PAYLOAD_OFFSET + 1].toInt() and 0xff
        val item = utilityItem(id) ?: return null
        val ok = data[PAYLOAD_OFFSET].toInt() == 0
        val raw = data[PAYLOAD_OFFSET + 2].toInt()
        val value = if (item.signed) raw else raw and 0xff
        return UtilityReply(id, value, ok)
    }

    private const val CHANNELS = 6
    private const val CHANNEL_STRIDE = 0x18

    /** Decodes a type-0x39 live-state packet; returns null if it is not one or too short. */
    fun parseLiveState(data: ByteArray, length: Int): MixerLiveState? {
        if (!hasHeader(data, length, TYPE_LIVE_STATE) || length < LIVE_PACKET_MIN_LENGTH) return null
        if (length - PAYLOAD_OFFSET < LIVE_PAYLOAD_MIN_LENGTH) return null
        fun u(offset: Int): Int = data[PAYLOAD_OFFSET + offset].toInt() and 0xff
        val channels = (0 until CHANNELS).map { index ->
            val b = index * CHANNEL_STRIDE
            MixerChannelState(
                channel = index + 1,
                input = u(b),
                trim = u(b + 1),
                comp = u(b + 2),
                eqHi = u(b + 3),
                eqHiMid = u(b + 4),
                eqLoMid = u(b + 5),
                eqLow = u(b + 6),
                filter = u(b + 7),
                send = u(b + 8),
                cue = u(b + 9) == 1,
                cue2 = u(b + 0xa),
                fader = u(b + 0xb),
                crossfaderAssign = u(b + 0xc),
            )
        }
        return MixerLiveState(
            channels = channels,
            crossfader = u(0x90),
            masterLevel = u(0x93),
            masterFilter = u(0x94),
            masterCue = u(0x95),
            isolatorOn = u(0x97),
            isoHi = u(0x98),
            isoMid = u(0x99),
            isoLow = u(0x9a),
            booth = u(0x9b),
            boothHi = u(0x9c),
            boothLow = u(0x9d),
            fxFreqLow = u(0xa2),
            fxFreqMid = u(0xa3),
            fxFreqHi = u(0xa4),
            fxSelect = u(0xa5),
            fxChannel = u(0xa6),
            fxLevel = u(0xa7),
            fxOn = u(0xa8),
            hpMix = u(0xbf),
            hpLevel = u(0xc0),
        )
    }

    /** Builds a type-0x39 frame from [state]. Used by tests only, never sent to real hardware. */
    fun buildLiveFrame(senderName: String, state: MixerLiveState): ByteArray {
        val packet = ByteArray(LIVE_PACKET_MIN_LENGTH)
        MAGIC.copyInto(packet)
        packet[TYPE_OFFSET] = TYPE_LIVE_STATE.toByte()
        val name = senderName.toByteArray(Charsets.US_ASCII)
        name.copyInto(packet, NAME_OFFSET, 0, minOf(name.size, NAME_LENGTH))
        packet[0x22] = (LIVE_PAYLOAD_LENGTH shr 8).toByte()
        packet[0x23] = LIVE_PAYLOAD_LENGTH.toByte()
        fun put(offset: Int, value: Int) { packet[PAYLOAD_OFFSET + offset] = value.toByte() }
        state.channels.take(CHANNELS).forEachIndexed { index, c ->
            val b = index * CHANNEL_STRIDE
            put(b, c.input); put(b + 1, c.trim); put(b + 2, c.comp)
            put(b + 3, c.eqHi); put(b + 4, c.eqHiMid); put(b + 5, c.eqLoMid); put(b + 6, c.eqLow)
            put(b + 7, c.filter); put(b + 8, c.send); put(b + 9, if (c.cue) 1 else 0)
            put(b + 0xa, c.cue2); put(b + 0xb, c.fader); put(b + 0xc, c.crossfaderAssign)
        }
        put(0x90, state.crossfader); put(0x93, state.masterLevel); put(0x94, state.masterFilter)
        put(0x95, state.masterCue); put(0x97, state.isolatorOn); put(0x98, state.isoHi)
        put(0x99, state.isoMid); put(0x9a, state.isoLow); put(0x9b, state.booth)
        put(0x9c, state.boothHi); put(0x9d, state.boothLow); put(0xa2, state.fxFreqLow)
        put(0xa3, state.fxFreqMid); put(0xa4, state.fxFreqHi); put(0xa5, state.fxSelect)
        put(0xa6, state.fxChannel); put(0xa7, state.fxLevel); put(0xa8, state.fxOn)
        put(0xbf, state.hpMix)
        put(0xc0, state.hpLevel)
        return packet
    }

    /** Builds a type-0x3b reply. Used by tests only. */
    fun buildUtilityReply(senderName: String, id: Int, value: Int, valid: Boolean = true): ByteArray {
        val packet = ByteArray(UTILITY_PACKET_LENGTH)
        MAGIC.copyInto(packet)
        packet[TYPE_OFFSET] = TYPE_UTILITY_REPLY.toByte()
        val name = senderName.toByteArray(Charsets.US_ASCII)
        name.copyInto(packet, NAME_OFFSET, 0, minOf(name.size, NAME_LENGTH))
        packet[0x23] = 4
        packet[PAYLOAD_OFFSET] = if (valid) 0 else 1
        packet[PAYLOAD_OFFSET + 1] = id.toByte()
        packet[PAYLOAD_OFFSET + 2] = (value and 0xff).toByte()
        return packet
    }

    /** Decodes a request built by [buildUtilityRequest] (test use): `(write, id, value)`, or null. */
    fun parseUtilityRequest(data: ByteArray, length: Int): Triple<Boolean, Int, Int>? {
        if (!hasHeader(data, length, TYPE_UTILITY_REQUEST) || length < UTILITY_PACKET_LENGTH) return null
        val id = data[PAYLOAD_OFFSET + 1].toInt() and 0xff
        val item = utilityItem(id) ?: return null
        val raw = data[PAYLOAD_OFFSET + 2].toInt()
        return Triple(data[PAYLOAD_OFFSET].toInt() == 1, id, if (item.signed) raw else raw and 0xff)
    }
}
