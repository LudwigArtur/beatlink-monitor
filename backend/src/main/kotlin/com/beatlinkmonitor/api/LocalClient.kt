package com.beatlinkmonitor.api

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Decides whether a request comes from the device the server runs on. Only such a device may create or change the
 * remote-button PIN and it does not have to enter it. A request counts as local only if
 *  - the TCP peer is a loopback address or one of this machine's own interface addresses (so a browser on the Mac
 *    that opens the LAN address still counts, while another device on the network does not),
 *  - it carries no proxy headers (behind a reverse proxy on the same machine every client would look local), and
 *  - the Host header names "localhost" or an IP address, which also rules out DNS rebinding.
 */
object LocalClient {
    private val proxyHeaders = listOf("X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP", "Forwarded")

    fun isLocal(
        peerAddress: String?,
        hostHeader: String?,
        headerNames: Collection<String>,
        ownAddresses: () -> Set<InetAddress> = ::machineAddresses,
    ): Boolean {
        if (peerAddress == null) return false
        if (headerNames.any { name -> proxyHeaders.any { it.equals(name, ignoreCase = true) } }) return false
        if (!isLocalHostName(hostHeader)) return false
        val peer = parseLiteral(peerAddress) ?: return false
        return peer.isLoopbackAddress || peer in ownAddresses()
    }

    internal fun isLocalHostName(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val host = if (hostHeader.startsWith("[")) hostHeader.substringAfter("[").substringBefore("]")
        else if (hostHeader.count { it == ':' } > 1) hostHeader
        else hostHeader.substringBefore(":")
        return host.equals("localhost", ignoreCase = true) || parseLiteral(host) != null
    }

    /** Only accepts IP literals, never resolves a name. */
    private fun parseLiteral(value: String): InetAddress? {
        val text = value.substringBefore('%')
        val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(text)
        if (!ipv4 && !(text.contains(':') && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' })) return null
        return runCatching { InetAddress.getByName(text) }.getOrNull()
    }

    private fun machineAddresses(): Set<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    }.getOrDefault(emptySet())
}
