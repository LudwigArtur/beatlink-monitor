package com.beatlinkmonitor.prodjlink

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.NetworkInterface

private val logger = KotlinLogging.logger {}

@kotlinx.serialization.Serializable
data class NetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val addresses: List<String>,
)

/**
 * Lists usable network interfaces so the user can pick the one connected to
 * the Pro DJ Link network (requirements specification §28). The application must never
 * assume only a single adapter exists (e.g. Wi-Fi for internet + USB-Ethernet
 * for Pro DJ Link at the same time).
 */
object NetworkInterfaceService {

    fun listInterfaces(): List<NetworkInterfaceInfo> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .map { nif ->
                NetworkInterfaceInfo(
                    name = nif.name,
                    displayName = nif.displayName ?: nif.name,
                    addresses = nif.inetAddresses.toList().mapNotNull { it.hostAddress },
                )
            }
    } catch (e: Exception) {
        logger.error(e) { "Failed to enumerate network interfaces" }
        emptyList()
    }

    fun findByName(name: String): NetworkInterface? = try {
        NetworkInterface.getByName(name)
    } catch (e: Exception) {
        logger.error(e) { "Failed to resolve network interface '$name'" }
        null
    }

    /**
     * Resolves the local address an *outbound* connection (GrandMA2 Telnet,
     * Ableton Link/Carabiner) should be bound to, given that output's own
     * independent interface selection (requirements specification: separate adapters per
     * network service, not just one shared setting for everything). Returns
     * `null` for "AUTOMATIC" (let the OS pick the default route, today's
     * behavior) or if the configured interface can't be found/has no usable
     * address - callers should treat `null` as "don't bind, connect
     * normally" rather than failing outright.
     */
    fun resolveBindAddress(mode: String, manualInterfaceName: String?): java.net.InetAddress? {
        if (mode != "MANUAL" || manualInterfaceName.isNullOrBlank()) return null
        val nif = findByName(manualInterfaceName)
        if (nif == null) {
            logger.warn { "Configured network interface '$manualInterfaceName' not found - connecting without a specific bind address" }
            return null
        }
        // Prefer an IPv4 address (most Telnet/Carabiner endpoints are IPv4-only),
        // falling back to whatever address the interface has.
        val addresses = nif.inetAddresses.toList()
        return addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.firstOrNull()
    }
}

