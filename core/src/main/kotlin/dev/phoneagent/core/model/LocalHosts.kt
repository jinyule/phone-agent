package dev.phoneagent.core.model

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Whether [host] (as in a URL; IPv6 literals in brackets) names a server on this device or the
 * local network: a loopback, private (RFC 1918), link-local or unique-local (fc00::/7) address
 * literal, `localhost`, or an mDNS `*.local` name. Decided from the text alone, without DNS.
 */
internal fun isLocalHost(host: String): Boolean {
    val name = host.lowercase()
    val literal = name.removeSurrounding("[", "]")
    val numeric = IPV4.matches(literal) || (literal != name && IPV6.matches(literal))
    val address = if (numeric) numericAddress(literal) else null
    return if (address != null) {
        LOCAL_NETWORKS.any { it.contains(address.address) }
    } else {
        name == "localhost" || name.endsWith(".local")
    }
}

/** [literal], already checked to be numeric, as an address; `null` when it is not a valid one. */
private fun numericAddress(literal: String): InetAddress? =
    try {
        // A numeric literal is parsed, never looked up.
        InetAddress.getByName(literal)
    } catch (ignored: UnknownHostException) {
        // Not a valid address, so not a local one either.
        null
    }

/** An address range in CIDR notation, such as `10.0.0.0/8`. */
private class Network(
    cidr: String,
) {
    private val base = checkNotNull(numericAddress(cidr.substringBefore('/'))) { "bad network $cidr" }.address
    private val prefixBits = cidr.substringAfter('/').toInt()

    fun contains(address: ByteArray): Boolean =
        address.size == base.size && (0 until prefixBits).all { bit(address, it) == bit(base, it) }

    private fun bit(
        bytes: ByteArray,
        index: Int,
    ): Int = (bytes[index / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - index % Byte.SIZE_BITS)) and 1
}

private val IPV4 = Regex("((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])")
private val IPV6 = Regex("[0-9a-f:.]*:[0-9a-f:.]*")
private val LOCAL_NETWORKS =
    listOf(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    ).map(::Network)
