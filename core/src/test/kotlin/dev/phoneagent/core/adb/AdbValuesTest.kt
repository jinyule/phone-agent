package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Configuration and value types: validated on construction, loud on mistakes. */
class AdbValuesTest {
    @Test
    fun `endpoints need a host and a port in range`() {
        assertEquals("127.0.0.1:37000", AdbEndpoint("127.0.0.1", 37_000).toString())
        AdbEndpoint("h", 1)
        AdbEndpoint("h", 65_535)
        for ((host, port) in listOf("" to 1, " " to 1, "h" to 0, "h" to 65_536, "h" to -1)) {
            assertThrows<IllegalArgumentException> { AdbEndpoint(host, port) }
        }
    }

    @Test
    fun `client limits are positive and output is bounded`() {
        val defaults = AdbConfig()
        assertEquals(AdbConfig.DEFAULT_PAIRING_TIMEOUT_MS, defaults.pairingTimeoutMs)
        assertEquals(AdbConfig.DEFAULT_CONNECT_TIMEOUT_MS, defaults.connectTimeoutMs)
        assertEquals(AdbConfig.DEFAULT_COMMAND_TIMEOUT_MS, defaults.commandTimeoutMs)
        assertEquals(AdbConfig.DEFAULT_MAX_OUTPUT_BYTES, defaults.maxOutputBytes)
        AdbConfig(1, 1, 1, 1)
        AdbConfig(maxOutputBytes = AdbConfig.MAX_OUTPUT_LIMIT)
        val bad =
            listOf(
                { AdbConfig(pairingTimeoutMs = 0) },
                { AdbConfig(connectTimeoutMs = 0) },
                { AdbConfig(commandTimeoutMs = -1) },
                { AdbConfig(maxOutputBytes = 0) },
                { AdbConfig(maxOutputBytes = AdbConfig.MAX_OUTPUT_LIMIT + 1) },
            )
        for (make in bad) assertThrows<IllegalArgumentException> { make() }
    }

    @Test
    fun `commands are non-blank, NUL-free, and quote arguments for sh`() {
        assertEquals("AdbCommand(id -u)", AdbCommand("id -u").toString())
        assertThrows<IllegalArgumentException> { AdbCommand(" \n") }
        assertThrows<IllegalArgumentException> { AdbCommand("id\u0000; reboot") }
        assertEquals("'plain'", AdbCommand.quote("plain"))
        assertEquals("''", AdbCommand.quote(""))
        assertEquals("'it'\\''s; \$(reboot) `x`'", AdbCommand.quote("it's; \$(reboot) `x`"))
        // What Java callers (and Kover) see of the value class: a boxed object with a getter.
        val boxed: Any = listOf(AdbCommand("id -u")).first()
        assertEquals("id -u", AdbCommand::class.java.getMethod("getText").invoke(boxed))
    }

    @Test
    fun `scan limits are validated`() {
        val defaults = PortScanConfig()
        assertEquals(PortScanConfig.DEFAULT_BUDGET_MS, defaults.budgetMs)
        assertEquals(PortScanConfig.DEFAULT_CONNECT_TIMEOUT_MS, defaults.connectTimeoutMs)
        assertEquals(PortScanConfig.DEFAULT_PROBE_TIMEOUT_MS, defaults.probeTimeoutMs)
        assertEquals(PortScanConfig.DEFAULT_PARALLELISM, defaults.parallelism)
        PortScanConfig(1, 1, 1, 1)
        PortScanConfig(connectTimeoutMs = 60_000, parallelism = 1024)
        val bad =
            listOf(
                { PortScanConfig(budgetMs = 0) },
                { PortScanConfig(connectTimeoutMs = 0) },
                { PortScanConfig(connectTimeoutMs = 60_001) },
                { PortScanConfig(probeTimeoutMs = 0) },
                { PortScanConfig(parallelism = 0) },
                { PortScanConfig(parallelism = 1025) },
            )
        for (make in bad) assertThrows<IllegalArgumentException> { make() }
    }

    @Test
    fun `port search settings are validated`() {
        assertEquals(PortSearchConfig.LOOPBACK, PortSearchConfig().host)
        assertEquals(PortSearchConfig.DEFAULT_MDNS_TIMEOUT_MS, PortSearchConfig().mdnsTimeoutMs)
        assertThrows<IllegalArgumentException> { PortSearchConfig(host = "") }
        assertThrows<IllegalArgumentException> { PortSearchConfig(mdnsTimeoutMs = 0) }
        assertEquals("_adb-tls-pairing._tcp", AdbService.PAIRING.type)
        assertEquals("_adb-tls-connect._tcp", AdbService.CONNECT.type)
    }

    @Test
    fun `failure results carry a class and a detail`() {
        val failed = CommandResult.Failed(CommandFailure.TIMEOUT, "slow")
        assertEquals(CommandFailure.TIMEOUT, failed.reason)
        assertEquals("slow", failed.detail)
        assertEquals("x", ConnectResult.Failed(ConnectFailure.TLS, "x").detail)
        assertEquals("y", PairingResult.Failed(PairingFailure.TLS, "y").detail)
        assertFalse(
            PairingResult
                .Paired(PairedDevice(""))
                .device.guid
                .isNotEmpty(),
        )
        assertEquals(6, CommandFailure.entries.size)
        assertEquals(5, ConnectFailure.entries.size)
    }
}
