package com.beatlinkmonitor.prodjlink

import com.beatlinkmonitor.cdj.CdjProtocol
import com.beatlinkmonitor.mixer.AnnouncementTransport
import com.beatlinkmonitor.mixer.AudioLevelProtocol
import com.beatlinkmonitor.mixer.AudioLevelTransport
import com.beatlinkmonitor.mixer.MixerIdentity
import com.beatlinkmonitor.mixer.MixerProtocol
import com.beatlinkmonitor.mixer.MixerTransport
import com.beatlinkmonitor.mixer.NetworkIdentity
import com.beatlinkmonitor.mixer.PeerIdentityProtocol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.deepsymmetry.beatlink.BeatFinder
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.VirtualCdj
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Reaches the mixer through beat-link's own Pro DJ Link status socket (UDP
 * 50002). That socket is private to `VirtualCdj` and anything it does not
 * recognize (the mixer's live-state and utility-reply packets) is dropped, so
 * this transport swaps the socket for a thin wrapper that hands a copy of
 * every received packet to [onPacket] and otherwise delegates everything to
 * the original socket. Requests are sent through the same socket so that
 * they originate from port 50002, like regular Pro DJ Link status traffic.
 *
 * This reaches into beat-link by reflection and is only installed when the
 * mixer integration is enabled in the configuration.
 */
class VirtualCdjMixerTransport(
    private val onPacket: (ByteArray, Int) -> Unit,
    private val recorder: PacketRecorder? = null,
    private val onBeatPacket: ((ByteArray, Int) -> Unit)? = null,
) : MixerTransport {
    private val virtualCdj = VirtualCdj.getInstance()

    companion object {
        val STATUS_CONSUMED_TYPES = setOf(
            MixerProtocol.TYPE_LIVE_STATE,
            MixerProtocol.TYPE_UTILITY_REPLY,
            PeerIdentityProtocol.TYPE_VERSION_REPLY,
            CdjProtocol.TYPE_STATUS_REPLY,
            CdjProtocol.TYPE_SETTINGS_REPLY,
        )
    }

    internal class TappingSocket(
        val delegate: DatagramSocket,
        private val recorder: PacketRecorder? = null,
        private val consumedTypes: Set<Int> = STATUS_CONSUMED_TYPES,
        private val dropObfuscated: Boolean = true,
        private val tap: (ByteArray, Int) -> Unit,
    ) : DatagramSocket(null as SocketAddress?) {
        /**
         * Hands beat-link only packets it can interpret. Frames addressed to our third-party identity carry an
         * obfuscated device id (bit 7 set at 0x21), which beat-link would read as a bogus device number and
         * turn into phantom players; the mixer's own frame types (0x39/0x3b/0x31) are consumed by [tap].
         * Both are removed here after they were recorded and tapped.
         */
        override fun receive(p: DatagramPacket) {
            while (true) {
                delegate.receive(p)
                recorder?.takeIf { it.active }?.record(p.address, p.port, delegate.localAddress, delegate.localPort, p.data, p.offset, p.length)
                val hasHeader = p.length > 0x21 && regionMatches(p.data, p.offset)
                val type = if (hasHeader) p.data[p.offset + 0x0a].toInt() and 0xff else -1
                val consumed = type in consumedTypes
                if (consumed) runCatching { tap(p.data.copyOfRange(p.offset, p.offset + p.length), p.length) }
                val obfuscated = dropObfuscated && hasHeader && (p.data[p.offset + 0x21].toInt() and 0x80) != 0
                if (!consumed && !obfuscated) return
            }
        }

        private fun regionMatches(data: ByteArray, offset: Int): Boolean {
            val magic = "Qspt1WmJOL".toByteArray(Charsets.US_ASCII)
            for (i in magic.indices) if (data[offset + i] != magic[i]) return false
            return true
        }

        override fun send(p: DatagramPacket) {
            delegate.send(p)
            recorder?.takeIf { it.active }?.record(delegate.localAddress, delegate.localPort, p.address, p.port, p.data, p.offset, p.length)
        }
        override fun close() {
            delegate.close()
            super.close()
        }

        override fun isClosed(): Boolean = delegate.isClosed
        override fun isBound(): Boolean = delegate.isBound
        override fun isConnected(): Boolean = delegate.isConnected
        override fun getLocalAddress(): InetAddress? = delegate.localAddress
        override fun getLocalPort(): Int = delegate.localPort
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getPort(): Int = delegate.port
        override fun getBroadcast(): Boolean = delegate.broadcast
        override fun setBroadcast(on: Boolean) = delegate.setBroadcast(on)
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
    }

    @Suppress("UNCHECKED_CAST")
    private fun socketReference(): AtomicReference<DatagramSocket> {
        val field = VirtualCdj::class.java.getDeclaredField("socket")
        field.isAccessible = true
        return field.get(virtualCdj) as AtomicReference<DatagramSocket>
    }

    /** Installs the taps if beat-link's sockets are open and not wrapped yet. Safe to call repeatedly. */
    fun ensureInstalled() {
        runCatching { ensureBeatSocketInstalled() }
        if (!virtualCdj.isRunning) return
        val reference = socketReference()
        val current = reference.get() ?: return
        if (current is TappingSocket) return
        val wrapper = TappingSocket(current, recorder) { data, length -> onPacket(data, length) }
        if (reference.compareAndSet(current, wrapper)) {
            logger.info { "Mixer integration: hooked the Pro DJ Link status socket" }
        }
    }

    private val beatFinder = BeatFinder.getInstance()

    @Suppress("UNCHECKED_CAST")
    private fun beatSocketReference(): AtomicReference<DatagramSocket> {
        val field = BeatFinder::class.java.getDeclaredField("socket")
        field.isAccessible = true
        return field.get(beatFinder) as AtomicReference<DatagramSocket>
    }

    /** Same tap on beat-link's beat socket (UDP 50001), where the mixer's audio-level frames arrive. */
    fun ensureBeatSocketInstalled() {
        if (onBeatPacket == null || !beatFinder.isRunning) return
        val reference = beatSocketReference()
        val current = reference.get() ?: return
        if (current is TappingSocket) return
        val wrapper = TappingSocket(current, recorder, setOf(AudioLevelProtocol.TYPE_LEVELS), dropObfuscated = false) { data, length -> onBeatPacket.invoke(data, length) }
        if (reference.compareAndSet(current, wrapper)) {
            logger.info { "Mixer integration: hooked the Pro DJ Link beat socket" }
        }
    }

    private fun beatTappingSocket(): TappingSocket? =
        if (beatFinder.isRunning) runCatching { beatSocketReference().get() as? TappingSocket }.getOrNull() else null

    /** Audio-level requests are sent from the beat socket's own port 50001, like the reference application does. */
    val audioTransport: AudioLevelTransport = object : AudioLevelTransport {
        override val available: Boolean get() = beatTappingSocket()?.isClosed == false
        override fun identity(): MixerIdentity? = this@VirtualCdjMixerTransport.identity()
        override fun send(data: ByteArray, address: InetAddress) {
            val socket = beatTappingSocket() ?: throw IllegalStateException("Pro DJ Link beat socket is not hooked")
            socket.send(DatagramPacket(data, data.size, address, AudioLevelProtocol.PORT))
        }
    }

    private fun tappingSocket(): TappingSocket? =
        if (virtualCdj.isRunning) runCatching { socketReference().get() as? TappingSocket }.getOrNull() else null

    override val available: Boolean get() = tappingSocket()?.isClosed == false

    /** While a [PeerAnnouncer] is online, requests are signed with its name and number instead of beat-link's virtual CDJ. */
    @Volatile var identityOverride: (() -> Pair<String, Int>?)? = null

    private fun interfaceMac(): ByteArray? = runCatching {
        NetworkInterface.getByInetAddress(virtualCdj.localAddress)?.hardwareAddress
    }.getOrNull()?.takeIf { it.size >= 6 }

    override fun identity(): MixerIdentity? {
        if (!virtualCdj.isRunning) return null
        val mac = interfaceMac() ?: return null
        val override = identityOverride?.invoke()
        return if (override != null) MixerIdentity(override.first, override.second, mac)
        else MixerIdentity(VirtualCdj.getDeviceName(), virtualCdj.deviceNumber.toInt(), mac)
    }

    private val announcementSocket by lazy { DatagramSocket() }

    val announcementTransport: AnnouncementTransport = object : AnnouncementTransport {
        override val available: Boolean get() = virtualCdj.isRunning

        override fun networkIdentity(): NetworkIdentity? {
            if (!virtualCdj.isRunning) return null
            val mac = interfaceMac() ?: return null
            return NetworkIdentity(virtualCdj.localAddress, mac)
        }

        override fun broadcast(data: ByteArray) {
            announcementSocket.send(DatagramPacket(data, data.size, virtualCdj.broadcastAddress, DeviceFinder.ANNOUNCEMENT_PORT))
        }

        override fun peerCount(): Int = DeviceFinder.getInstance().currentDevices.size

        override fun deviceNumbersInUse(): Set<Int> =
            runCatching { DeviceFinder.getInstance().currentDevices.map { it.deviceNumber }.toSet() }.getOrDefault(emptySet())
    }

    override fun send(data: ByteArray, address: InetAddress, port: Int) {
        val socket = tappingSocket() ?: throw IllegalStateException("Pro DJ Link socket is not hooked")
        socket.send(DatagramPacket(data, data.size, address, port))
    }
}
