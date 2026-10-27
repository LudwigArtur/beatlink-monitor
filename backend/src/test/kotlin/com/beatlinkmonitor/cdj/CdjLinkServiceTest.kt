package com.beatlinkmonitor.cdj

import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.mixer.MixerIdentity
import com.beatlinkmonitor.mixer.MixerTransport
import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.state.ApplicationState
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CdjLinkServiceTest {
    private val mac = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

    private class Sent(val data: ByteArray, val address: InetAddress, val port: Int)

    /** A pretend CDJ #2: keeps its settings and answers status and settings requests. */
    private class FakeCdj(val mac: ByteArray, val answerWrites: Boolean = true) : MixerTransport {
        lateinit var link: CdjLinkService
        val sent = CopyOnWriteArrayList<Sent>()
        val settings = IntArray(0x74)

        override val available = true
        override fun identity() = MixerIdentity("TEST", 54, mac)

        override fun send(data: ByteArray, address: InetAddress, port: Int) {
            sent += Sent(data, address, port)
            when (data[0x0a].toInt() and 0xff) {
                CdjProtocol.TYPE_SETTINGS_REQUEST -> {
                    if (data[0x24].toInt() == 1) {
                        if (!answerWrites) return
                        for (offset in CdjProtocol.SETTINGS_FIRST_OFFSET..CdjProtocol.SETTINGS_LAST_OFFSET) {
                            if (data[offset].toInt() and 0x80 != 0) settings[offset] = data[offset].toInt() and 0x7f
                        }
                    }
                    link.handlePacket(reply(CdjProtocol.TYPE_SETTINGS_REPLY, CdjProtocol.SETTINGS_FRAME_LENGTH) { frame ->
                        for (offset in CdjProtocol.SETTINGS_FIRST_OFFSET..CdjProtocol.SETTINGS_LAST_OFFSET) frame[offset] = (0x80 or settings[offset]).toByte()
                    }, CdjProtocol.SETTINGS_FRAME_LENGTH)
                }
                CdjProtocol.TYPE_STATUS_REQUEST -> link.handlePacket(reply(CdjProtocol.TYPE_STATUS_REPLY, 0x38) {
                    it[0x32] = 1
                    it[0x28] = 0x31; it[0x29] = 0x38 // 12600 = 126.00 BPM
                    it[0x2a] = 0xff.toByte(); it[0x2b] = 0xb5.toByte() // -75 = -0.75 %
                    it[0x2e] = 1
                }, 0x38)
            }
        }

        private fun reply(type: Int, length: Int, fill: (ByteArray) -> Unit): ByteArray {
            val frame = ByteArray(length)
            "Qspt1WmJOL".toByteArray().copyInto(frame)
            frame[0x0a] = type.toByte()
            frame[0x21] = 2
            fill(frame)
            return frame
        }
    }

    private class Fixture(val cdj: FakeCdj, val config: ApplicationConfig) {
        val state = ApplicationState().also { s ->
            s.upsertPlayer(2) { it.copy(deviceType = DeviceType.CDJ, ipAddress = "169.254.1.2") }
            s.upsertPlayer(33) { it.copy(deviceType = DeviceType.MIXER, ipAddress = "169.254.1.33") }
        }
        val link = CdjLinkService(state, { config }, cdj).also { cdj.link = it }
    }

    @Test
    fun `settings write frame carries the value at the item offset with bit 7 set`() {
        val frame = CdjProtocol.buildSettingsWrite("TEST", 54, 0x34, 1)

        assertEquals(CdjProtocol.SETTINGS_FRAME_LENGTH, frame.size)
        assertEquals(0x6b, frame[0x0a].toInt())
        assertEquals(1, frame[0x24].toInt())
        assertEquals(0x81, frame[0x34].toInt() and 0xff)
        assertEquals(0x50, frame[0x23].toInt())
        assertEquals(0, frame.indices.filter { it >= 0x25 && it != 0x34 }.count { frame[it].toInt() != 0 })
    }

    @Test
    fun `external control frames have the documented sizes and an obfuscated id`() {
        val connect = CdjProtocol.buildExternalControl(54, mac, CdjProtocol.ButtonAction.CONNECT)
        val click = CdjProtocol.buildExternalControl(54, mac, CdjProtocol.ButtonAction.COMMAND, 20, CdjProtocol.Press.CLICK)
        val down = CdjProtocol.buildExternalControl(54, mac, CdjProtocol.ButtonAction.COMMAND, 21, CdjProtocol.Press.DOWN)

        assertEquals(0x2a, connect.size)
        assertEquals(0x36, click.size)
        assertEquals(0x30, down.size)
        assertEquals(1, connect[0x25].toInt())
        assertEquals(2, click[0x29].toInt())
        assertEquals(20, click[0x2b].toInt())
        assertEquals(1, click[0x2d].toInt())
        assertEquals(0, click[0x33].toInt())
        assertTrue(click[0x21].toInt() and 0x80 != 0)
        assertEquals("CDJ-2000NXS2", String(click, 0x0b, 12))
    }

    @Test
    fun `polling reads each CDJ and fills the state`() {
        val f = Fixture(FakeCdj(mac), ApplicationConfig())
        f.cdj.settings[0x34] = 1

        f.link.tick(10_000)

        val device = f.link.snapshot().devices.single()
        assertEquals(2, device.deviceNumber)
        assertEquals(1, device.settings[0x34])
        assertEquals(true, device.flags?.slip)
        assertEquals(126.0, device.flags?.trackBpm)
        assertEquals(-0.75, device.flags?.pitchPercent)
        assertEquals(true, device.flags?.sync)
        assertEquals(false, device.flags?.keySync)
        assertEquals(2, f.cdj.sent.size) // only the CDJ is addressed, not the mixer
        assertTrue(f.cdj.sent.all { it.address.hostAddress == "169.254.1.2" && it.port == 50002 })
    }

    @Test
    fun `write is confirmed after the read-back and refused when control is off`() = runBlocking {
        val f = Fixture(FakeCdj(mac), ApplicationConfig(cdjControlEnabled = true))

        val ok = f.link.writeSetting(2, 0x34, 1)
        assertEquals(CdjWriteStatus.CONFIRMED, ok.status)
        assertEquals(1, f.link.snapshot().devices.single().settings[0x34])

        val off = Fixture(FakeCdj(mac), ApplicationConfig(cdjControlEnabled = false))
        assertEquals(CdjWriteStatus.CONTROL_DISABLED, off.link.writeSetting(2, 0x34, 1).status)
        assertTrue(off.cdj.sent.isEmpty())
    }

    @Test
    fun `invalid values and unknown players are rejected before anything is sent`() = runBlocking {
        val f = Fixture(FakeCdj(mac), ApplicationConfig())

        assertEquals(CdjWriteStatus.INVALID, f.link.writeSetting(2, 0x34, 5).status)
        assertEquals(CdjWriteStatus.INVALID, f.link.writeSetting(2, 0x42, 1).status)
        assertEquals(CdjWriteStatus.NO_DEVICE, f.link.writeSetting(3, 0x34, 1).status)
        assertEquals(CdjWriteStatus.NO_DEVICE, f.link.writeSetting(33, 0x34, 1).status)
        assertTrue(f.cdj.sent.isEmpty())
    }

    @Test
    fun `a write the CDJ ignores is reported as mismatch`() = runBlocking {
        val f = Fixture(FakeCdj(mac, answerWrites = false), ApplicationConfig())

        val result = f.link.writeSetting(2, 0x34, 1)

        // The fake still answers the read-back with the old value, so the mismatch is detected.
        assertEquals(CdjWriteStatus.MISMATCH, result.status)
        assertEquals(0, result.reportedValue)
    }

    @Test
    fun `remote buttons go to the beat port and open a session first`() {
        val on = Fixture(FakeCdj(mac), ApplicationConfig())
        assertEquals(CdjRemoteStatus.SENT, on.link.pressButton(2, 20).status)
        assertEquals(CdjRemoteStatus.INVALID, on.link.pressButton(2, 999).status)
        assertEquals(CdjRemoteStatus.NO_DEVICE, on.link.pressButton(4, 20).status)
        assertEquals(CdjRemoteStatus.NO_DEVICE, on.link.pressButton(33, 20).status)
        assertEquals(listOf(0x2a, 0x36), on.cdj.sent.map { it.data.size }) // connect, then the click
        assertTrue(on.cdj.sent.all { it.port == 50001 })
        assertNotNull(on.link.snapshot())
    }
}
