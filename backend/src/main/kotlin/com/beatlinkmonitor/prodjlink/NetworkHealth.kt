package com.beatlinkmonitor.prodjlink

import kotlinx.serialization.Serializable
import java.net.NetworkInterface
import java.util.ArrayDeque

/** What the UI and the diagnostics view show about the health of the Pro DJ Link network connection. */
@Serializable
data class NetworkHealth(
    /** The interface Pro DJ Link was started on, null while it is not running. */
    val interfaceName: String? = null,
    /** The address Pro DJ Link is bound to; it keeps using this one until the application is restarted. */
    val boundAddress: String? = null,
    /** The addresses the interface has right now. */
    val currentAddresses: List<String> = emptyList(),
    /** The interface lost the address Pro DJ Link is bound to (or disappeared): it can no longer talk to the players. */
    val addressChanged: Boolean = false,
    /** How often a device vanished from the network in the last [NetworkWatch.WINDOW_MS] (a stable network has none). */
    val deviceDropouts: Int = 0,
    /** Players that report a loaded track but whose metadata and waveform could not be loaded for a while. */
    val metadataUnavailablePlayers: List<Int> = emptyList(),
    /** Of those, the players whose database port could not even be queried (the TCP connection to the player fails). */
    val unreachablePlayers: List<Int> = emptyList(),
)

/**
 * Notices the two network problems that make Pro DJ Link silently stop working while the application keeps running:
 * the interface it is bound to getting a different address (a link-local address is picked again whenever the link
 * comes back, a DHCP lease changes, an adapter is replugged), and devices dropping out repeatedly.
 */
class NetworkWatch(
    private val clock: () -> Long = System::currentTimeMillis,
    private val addressesOf: (String) -> List<String>? = ::interfaceAddresses,
) {
    companion object {
        const val WINDOW_MS = 10 * 60_000L

        /** The addresses of the named interface, or null if it does not exist (any more). */
        fun interfaceAddresses(name: String): List<String>? = runCatching {
            NetworkInterface.getByName(name)?.inetAddresses?.toList()?.mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrNull()
    }

    @Volatile private var interfaceName: String? = null
    @Volatile private var boundAddress: String? = null
    private val dropouts = ArrayDeque<Long>()

    /** Remembers the interface and address Pro DJ Link just started with. */
    fun bind(interfaceName: String?, address: String?) {
        this.interfaceName = interfaceName
        this.boundAddress = address?.substringBefore('%')
        synchronized(dropouts) { dropouts.clear() }
    }

    fun unbind() = bind(null, null)

    fun recordDropout() {
        synchronized(dropouts) { dropouts.addLast(clock()) }
    }

    private fun dropoutsInWindow(): Int = synchronized(dropouts) {
        val limit = clock() - WINDOW_MS
        while (dropouts.isNotEmpty() && dropouts.first() < limit) dropouts.removeFirst()
        dropouts.size
    }

    fun snapshot(metadataUnavailable: Collection<Int> = emptyList(), unreachable: Collection<Int> = emptyList()): NetworkHealth {
        val name = interfaceName
        val bound = boundAddress
        val current = name?.let(addressesOf)
        val changed = name != null && bound != null && (current == null || bound !in current)
        return NetworkHealth(
            interfaceName = name,
            boundAddress = bound,
            currentAddresses = current.orEmpty(),
            addressChanged = changed,
            deviceDropouts = dropoutsInWindow(),
            metadataUnavailablePlayers = metadataUnavailable.sorted(),
            unreachablePlayers = unreachable.sorted(),
        )
    }
}
