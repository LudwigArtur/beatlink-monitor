package com.beatlinkmonitor.mixer

import java.net.InetAddress

/**
 * Frames with which an application announces itself on the Pro DJ Link network
 * as a third-party participant (category 5, device number 54-57) instead of a
 * CDJ. Mixers appear to send their rich live state (type 0x39) only to peers
 * that announce like this - that is an unverified hypothesis derived from
 * static analysis of another application; see `Docs/MIXER_CONTROL.md`.
 *
 * All frames start with the 10-byte magic header; system frames (sent as
 * broadcasts to the announcement port) carry `0x0b = 0`, the name at
 * `0x0c..0x1f`, `0x20 = 1`, `0x21 = 1` and the total frame length at `0x22..0x23`.
 */
object PeerIdentityProtocol {
    const val CATEGORY_THIRD_PARTY = 5
    const val FIRST_DEVICE_NUMBER = 54
    const val LAST_DEVICE_NUMBER = 57
    const val KEEPALIVE_INTERVAL_MS = 2000L
    const val CLAIM_ATTEMPTS = 3
    const val CLAIM_SPACING_MS = 300L

    const val TYPE_ID_REQUEST = 0x02
    const val TYPE_KEEPALIVE = 0x06
    const val TYPE_DISCONNECT = 0x08
    const val TYPE_VERSION_REQUEST = 0x30
    const val TYPE_VERSION_REPLY = 0x31

    private val MAGIC = "Qspt1WmJOL".toByteArray(Charsets.US_ASCII)
    private const val NAME_OFFSET = 0x0c
    private const val NAME_LENGTH = 20

    private fun systemFrame(type: Int, length: Int, name: String): ByteArray {
        val frame = ByteArray(length)
        MAGIC.copyInto(frame)
        frame[0x0a] = type.toByte()
        val bytes = name.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(frame, NAME_OFFSET, 0, minOf(bytes.size, NAME_LENGTH))
        frame[0x20] = 1
        frame[0x21] = 1
        frame[0x22] = (length shr 8).toByte()
        frame[0x23] = length.toByte()
        return frame
    }

    /** Claims [id] (stage-2 style request); sent three times, 300 ms apart, for each candidate id. */
    fun buildIdRequest(name: String, ip: InetAddress, mac: ByteArray, id: Int, attempt: Int): ByteArray {
        val frame = systemFrame(TYPE_ID_REQUEST, 0x32, name)
        ip.address.copyInto(frame, 0x24)
        mac.copyInto(frame, 0x28, 0, 6)
        frame[0x2f] = attempt.toByte()
        frame[0x30] = CATEGORY_THIRD_PARTY.toByte()
        frame[0x2e] = MixerProtocol.encodeOwnId(frame, id, mac, idOffset = 0x2e).toByte()
        return frame
    }

    /** Presence announcement, repeated every [KEEPALIVE_INTERVAL_MS]; [peerCount] is the number of devices we currently see. */
    fun buildKeepAlive(name: String, id: Int, mac: ByteArray, ip: InetAddress, peerCount: Int): ByteArray {
        val frame = systemFrame(TYPE_KEEPALIVE, 0x36, name)
        mac.copyInto(frame, 0x26, 0, 6)
        ip.address.copyInto(frame, 0x2c)
        frame[0x30] = peerCount.coerceIn(0, 80).toByte()
        frame[0x34] = CATEGORY_THIRD_PARTY.toByte()
        frame[0x35] = 0x20
        frame[0x24] = MixerProtocol.encodeOwnId(frame, id, mac, idOffset = 0x24).toByte()
        return frame
    }

    fun buildDisconnect(name: String, id: Int, mac: ByteArray, ip: InetAddress): ByteArray {
        val frame = systemFrame(TYPE_DISCONNECT, 0x29, name)
        ip.address.copyInto(frame, 0x25)
        frame[0x24] = MixerProtocol.encodeOwnId(frame, id, mac, idOffset = 0x24).toByte()
        return frame
    }

    /** Asks a mixer for its firmware version (answered with type 0x31); unlike the utility requests the id is not obfuscated. */
    fun buildVersionRequest(name: String, id: Int): ByteArray {
        val frame = ByteArray(0x24)
        MAGIC.copyInto(frame)
        frame[0x0a] = TYPE_VERSION_REQUEST.toByte()
        val bytes = name.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(frame, 0x0b, 0, minOf(bytes.size, NAME_LENGTH))
        frame[0x1f] = 1
        frame[0x20] = 3
        frame[0x21] = id.toByte()
        return frame
    }

    /** The four ASCII characters of a type-0x31 version reply, or null if [data] is not one. */
    fun parseVersionReply(data: ByteArray, length: Int): String? {
        if (length < 0x28 || length > data.size) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        if ((data[0x0a].toInt() and 0xff) != TYPE_VERSION_REPLY) return null
        val text = String(data, 0x24, 4, Charsets.US_ASCII)
        return text.takeIf { t -> t.all { it.code in 0x20..0x7e } }?.trim()
    }
}
