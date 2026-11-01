package dev.phoneagent.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** S6: which hosts plain http may reach, decided from the text alone. */
class LocalHostsTest {
    @Test
    fun `local hosts are loopback, private, link-local and unique-local literals, localhost and mDNS names`() {
        val local =
            listOf(
                "127.0.0.1",
                "127.255.255.254",
                "10.0.2.2",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.0.1",
                "169.254.10.1",
                "[::1]",
                "[fd00::1]",
                "[fe80::1]",
                "[::ffff:127.0.0.1]",
                "localhost",
                "LOCALHOST",
                "nas.local",
            )
        val remote =
            listOf(
                "8.8.8.8",
                "172.15.255.255",
                "172.32.0.0",
                "192.169.0.1",
                "169.255.0.1",
                "128.0.0.1",
                "[2001:db8::1]",
                "[fec0::1]",
                "[::ffff:8.8.8.8]",
                "[:::]",
                "010.0.0.1",
                "127.0.0.256",
                "localhost.example.com",
                "local",
                "example.com",
                "fd00::1",
            )
        assertEquals(emptyList<String>(), local.filterNot(::isLocalHost), "local hosts refused")
        assertEquals(emptyList<String>(), remote.filter(::isLocalHost), "remote hosts accepted")
    }
}
