package com.beatlinkmonitor.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixerProtocolTest {
    private val mac = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)

    @Test
    fun `utility read request has the reconstructed 40 byte layout`() {
        val packet = MixerProtocol.buildUtilityRequest("TEST", 5, mac, write = false, id = 7)

        assertEquals(40, packet.size)
        assertEquals("Qspt1WmJOL", String(packet, 0, 10, Charsets.US_ASCII))
        assertEquals(0x3a, packet[0x0a].toInt())
        assertEquals("TEST", String(packet, 0x0b, 4, Charsets.US_ASCII))
        assertEquals(2, packet[0x1f].toInt())
        assertEquals(0, packet[0x20].toInt())
        assertEquals(4, ((packet[0x22].toInt() and 0xff) shl 8) or (packet[0x23].toInt() and 0xff))
        assertEquals(0, packet[0x24].toInt())
        assertEquals(7, packet[0x25].toInt())
        assertEquals(0, packet[0x26].toInt())
        assertEquals(0, packet[0x27].toInt())
    }

    @Test
    fun `utility write request carries operation and value`() {
        val packet = MixerProtocol.buildUtilityRequest("TEST", 5, mac, write = true, id = 2, value = 3)

        assertEquals(1, packet[0x24].toInt())
        assertEquals(2, packet[0x25].toInt())
        assertEquals(3, packet[0x26].toInt())
    }

    @Test
    fun `own id is obfuscated with packet sum and the last three mac bytes`() {
        val packet = MixerProtocol.buildUtilityRequest("TEST", 5, mac, write = false, id = 7)

        var sum = 0
        for (i in 0 until 40) if (i != 0x21) sum += packet[i].toInt() and 0xff
        sum += 0x33 + 0x44 + 0x55
        val expected = ((5 xor sum) and 0x7f) or 0x80

        assertEquals(expected, packet[0x21].toInt() and 0xff)
        assertTrue((packet[0x21].toInt() and 0x80) != 0)
    }

    @Test
    fun `encoded id changes when payload or mac changes`() {
        val a = MixerProtocol.buildUtilityRequest("TEST", 5, mac, write = false, id = 1)[0x21]
        val b = MixerProtocol.buildUtilityRequest("TEST", 5, mac, write = false, id = 2)[0x21]
        val c = MixerProtocol.buildUtilityRequest("TEST", 5, byteArrayOf(0, 0, 0, 0, 0, 0), write = false, id = 1)[0x21]

        assertTrue(a != b)
        assertTrue(a != c)
    }

    @Test
    fun `utility reply with status zero is valid and signed for balance`() {
        val ok = MixerProtocol.parseUtilityReply(MixerProtocol.buildUtilityReply("M", 2, 3), 40)
        assertEquals(UtilityReply(2, 3, true), ok)

        val balance = MixerProtocol.parseUtilityReply(MixerProtocol.buildUtilityReply("M", 0, -20), 40)
        assertEquals(UtilityReply(0, -20, true), balance)
    }

    @Test
    fun `nonzero reply status marks the item invalid`() {
        val reply = MixerProtocol.parseUtilityReply(MixerProtocol.buildUtilityReply("M", 4, 0, valid = false), 40)

        assertNotNull(reply)
        assertEquals(false, reply.valid)
    }

    @Test
    fun `malformed utility replies are rejected`() {
        val good = MixerProtocol.buildUtilityReply("M", 4, 1)

        assertNull(MixerProtocol.parseUtilityReply(good, 39))
        assertNull(MixerProtocol.parseUtilityReply(MixerProtocol.buildUtilityReply("M", 15, 1), 40))
        assertNull(MixerProtocol.parseUtilityReply(good.copyOf().also { it[0] = 'X'.code.toByte() }, 40))
        assertNull(MixerProtocol.parseUtilityReply(good.copyOf().also { it[0x0a] = 0x39 }, 40))
    }

        private fun sampleLive(): MixerLiveState =
        MixerLiveState(
            channels = (1..6).map { ch ->
                MixerChannelState(
                    channel = ch, input = ch, trim = 10 + ch, comp = 20 + ch, eqHi = 30 + ch, eqHiMid = 40 + ch,
                    eqLoMid = 50 + ch, eqLow = 60 + ch, filter = 70 + ch, send = 80 + ch, cue = ch % 2 == 0,
                    cue2 = 90 + ch, fader = 255 - ch, crossfaderAssign = ch % 3,
                )
            },
            crossfader = 1, masterLevel = 2, masterFilter = 3, masterCue = 4, isolatorOn = 5, isoHi = 6,
            isoMid = 7, isoLow = 8, booth = 9, boothHi = 10, boothLow = 11, fxFreqLow = 12, fxFreqMid = 13,
            fxFreqHi = 14, fxSelect = 15, fxChannel = 16, fxLevel = 17, fxOn = 18, hpMix = 19, hpLevel = 20,
        )

    @Test
    fun `live state round trips and uses the documented offsets`() {
        val live = sampleLive()
        val frame = MixerProtocol.buildLiveFrame("MIX", live)

        assertEquals(0xf8, frame.size)
        assertEquals(0x39, frame[0x0a].toInt())
        // Payload starts at packet offset 0x24: channel 1 fader is payload 0x0b, channel 2 fader payload 0x23.
        assertEquals(254, frame[0x24 + 0x0b].toInt() and 0xff)
        assertEquals(253, frame[0x24 + 0x23].toInt() and 0xff)
        assertEquals(1, frame[0x24 + 0x90].toInt())
        assertEquals(19, frame[0x24 + 0xbf].toInt())

        assertEquals(live, MixerProtocol.parseLiveState(frame, frame.size))
    }

    @Test
    fun `short or foreign live packets are rejected`() {
        val frame = MixerProtocol.buildLiveFrame("MIX", sampleLive())

        assertNull(MixerProtocol.parseLiveState(frame, 0xf7))
        assertNull(MixerProtocol.parseLiveState(frame.copyOf().also { it[0x0a] = 0x29 }, frame.size))
    }

    @Test
    fun `the 15 utility items are numbered consecutively`() {
        assertEquals((0..14).toList(), MixerProtocol.UTILITY_ITEMS.map { it.id })
        assertEquals("masterAtt", MixerProtocol.utilityItem(2)?.key)
        assertEquals("micToRecExclude", MixerProtocol.utilityItem(14)?.key)
    }
}
