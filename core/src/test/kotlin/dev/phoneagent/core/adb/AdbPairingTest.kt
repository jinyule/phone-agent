package dev.phoneagent.core.adb

import dev.phoneagent.core.adb.FakePairingServer.Behavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

class AdbPairingTest {
    private val identity = TestTls.client
    private val code = PairingCode("482916")

    @Test
    fun `pairs and hands the device our key and certificate`() =
        realTime {
            FakePairingServer("482916", guid = "adb-R5CT1234-xYz9Ab").use { device ->
                val result = client().pair(device.endpoint, code)
                assertEquals(PairingResult.Paired(PairedDevice("adb-R5CT1234-xYz9Ab")), result)
                assertEquals(identity.adbPublicKey, device.receivedKey.get(10, TimeUnit.SECONDS))
                assertArrayEquals(identity.rsaPublicKey.encoded, device.clientCertificateKey.get(10, TimeUnit.SECONDS))
                device.clientClosed.awaitBarrier()
                device.close()
                assertNull(device.join())
            }
        }

    @Test
    fun `a wrong code is WRONG_CODE and the device does not get our key`() =
        realTime {
            FakePairingServer("482917").use { device ->
                val result = client().pair(device.endpoint, code)
                assertFailure(PairingFailure.WRONG_CODE, result)
                assertFalse((result as PairingResult.Failed).detail.contains("482916"), "the code never shows")
                assertFalse(device.receivedKey.isDone)
                device.close()
                assertNull(device.join())
            }
        }

    @ParameterizedTest
    @CsvSource(
        "NOT_TLS, TLS",
        "CLOSE_BEFORE_SPAKE2, REJECTED",
        "TRUNCATED_SPAKE2, REJECTED",
        "BAD_VERSION, PROTOCOL",
        "WRONG_TYPE, PROTOCOL",
        "EMPTY_PAYLOAD, PROTOCOL",
        "OVERSIZED_PAYLOAD, PROTOCOL",
        "NOT_A_POINT, PROTOCOL",
        "CLOSE_AFTER_PEER_INFO, WRONG_CODE",
        "SHORT_PEER_INFO, PROTOCOL",
        "WRONG_PEER_INFO_TYPE, PROTOCOL",
    )
    fun `each broken device step has its failure class`(
        behavior: Behavior,
        expected: PairingFailure,
    ) = realTime {
        FakePairingServer("482916", behavior).use { device ->
            assertFailure(expected, client().pair(device.endpoint, code))
            device.close()
            assertNull(device.join(), "the fake itself worked")
        }
    }

    @Test
    fun `a closed port is UNREACHABLE`() =
        realTime {
            assertFailure(PairingFailure.UNREACHABLE, client().pair(closedPort(), code))
        }

    @Test
    fun `an exporter failure is TLS`() =
        realTime {
            val exporters =
                listOf(
                    TlsKeyExporter { _, _, _ -> throw SSLException("no exporter") },
                    TlsKeyExporter { _, _, _ -> ByteArray(3) },
                )
            for (exporter in exporters) {
                FakePairingServer("482916").use { device ->
                    assertFailure(PairingFailure.TLS, AdbClient(identity, exporter).pair(device.endpoint, code))
                }
            }
        }

    @Test
    fun `a silent device times out in virtual time and the socket is closed`() =
        runTest {
            FakePairingServer("482916", Behavior.SILENT_AFTER_SPAKE2).use { device ->
                val config = AdbConfig(pairingTimeoutMs = 4_000)
                val result =
                    async { AdbClient(identity, TestTls.exporter, config, testIo).pair(device.endpoint, code) }
                holdTimeUntil(device.gotSpake2)
                assertFailure(PairingFailure.TIMEOUT, result.await())
                assertEquals(4_000, virtualTime)
                device.clientClosed.get(10, TimeUnit.SECONDS)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["executor", "limitedParallelism"])
    fun `with one IO thread cancelling a pairing closes its socket and ends`(kind: String) {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "one-io").apply { isDaemon = true } }
        try {
            val io = if (kind == "executor") executor.asCoroutineDispatcher() else testIo.limitedParallelism(1)
            FakePairingServer("482916", Behavior.SILENT_AFTER_SPAKE2).use { device ->
                realTime {
                    // Outside this scope, so that a stuck pairing fails the test instead of hanging it.
                    val client = AdbClient(identity, TestTls.exporter, AdbConfig(), io)
                    val result = CoroutineScope(Job()).async { client.pair(device.endpoint, code) }
                    device.gotSpake2.awaitBarrier()
                    result.cancel()
                    assertTrue(withTimeoutOrNull(10.seconds) { result.join() } != null, "the pairing ended")
                    assertTrue(result.isCancelled)
                    device.clientClosed.awaitBarrier()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a read after TLS that closing the socket did not end still ends at the socket timeout`() {
        FakePairingServer("482916", Behavior.SILENT_AFTER_SPAKE2).use { device ->
            val pairing = AdbPairing(AdbTls(identity, TestTls.exporter), identity)
            val failure =
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    assertThrows<PairingError> { Socket().use { pairing.pair(it, device.endpoint, code, 200) } }
                }
            assertEquals(PairingFailure.TIMEOUT, failure.reason)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["executor", "limitedParallelism"])
    fun `with one IO thread a silent device still times out`(kind: String) {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "one-io").apply { isDaemon = true } }
        try {
            val io = if (kind == "executor") executor.asCoroutineDispatcher() else testIo.limitedParallelism(1)
            FakePairingServer("482916", Behavior.SILENT_AFTER_SPAKE2).use { device ->
                assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                    runTest {
                        val config = AdbConfig(pairingTimeoutMs = 4_000)
                        val result =
                            async { AdbClient(identity, TestTls.exporter, config, io).pair(device.endpoint, code) }
                        holdTimeUntil(device.gotSpake2)
                        assertFailure(PairingFailure.TIMEOUT, result.await())
                        assertEquals(4_000, virtualTime)
                        device.clientClosed.get(10, TimeUnit.SECONDS)
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a read that closing the socket did not end still ends at the socket timeout`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val endpoint = AdbEndpoint("127.0.0.1", silent.localPort)
            val pairing = AdbPairing(AdbTls(identity, TestTls.exporter), identity)
            val failure =
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    assertThrows<PairingError> { Socket().use { pairing.pair(it, endpoint, code, 200) } }
                }
            assertEquals(PairingFailure.TIMEOUT, failure.reason)
        }
    }

    @Test
    fun `cancellation closes the socket and propagates`() =
        realTime {
            FakePairingServer("482916", Behavior.SILENT_AFTER_SPAKE2).use { device ->
                val result = async { client().pair(device.endpoint, code) }
                device.gotSpake2.awaitBarrier()
                result.cancel()
                assertThrows<CancellationException> { result.await() }
                device.clientClosed.awaitBarrier()
            }
        }

    @Test
    fun `pairing packets have a 6-byte header and checked bounds`() {
        val out = ByteArrayOutputStream()
        AdbPairing.writePacket(out, AdbPairing.TYPE_PEER_INFO, byteArrayOf(7, 8))
        assertEquals("010100000002" + "0708", out.toByteArray().toHex())
        assertArrayEquals(
            byteArrayOf(7, 8),
            AdbPairing.readPacket(ByteArrayInputStream(out.toByteArray()), AdbPairing.TYPE_PEER_INFO),
        )
        assertNull(AdbPairing.readPacket(ByteArrayInputStream(ByteArray(0)), AdbPairing.TYPE_SPAKE2))
        assertThrows<TruncatedFrameException> { read("0100000000") }
        assertThrows<TruncatedFrameException> { read("010000000002") }
        assertThrows<TruncatedFrameException> { read("010000000002" + "07") }
        for (header in listOf("020000000001", "010100000001", "010000000000", "010000004001", "0100ffffffff")) {
            val failure = assertThrows<AdbProtocolException> { read(header + "00") }
            assertEquals(AdbProtocolException::class.java, failure.javaClass, header)
        }
        assertEquals(16_384, read("010000004000" + "00".repeat(16_384))?.size)
    }

    @Test
    fun `codes are six ASCII digits and never printed`() {
        assertEquals("PairingCode(******)", code.toString())
        assertEquals("482916", code.bytes().toString(Charsets.US_ASCII))
        for (bad in listOf("", "12345", "1234567", "12345a", "١٢٣٤٥٦", "12 456")) {
            assertThrows<IllegalArgumentException> { PairingCode(bad) }
        }
    }

    @Test
    fun `failure classes are distinct and the export label carries its NUL`() {
        assertEquals(6, PairingFailure.entries.size)
        assertEquals(10, AdbPairing.EXPORT_LABEL.toByteArray(Charsets.UTF_8).size)
        assertTrue(describe(IOException("x")).startsWith("IOException"))
    }

    private fun read(hex: String): ByteArray? =
        AdbPairing.readPacket(ByteArrayInputStream(hexBytes(hex)), AdbPairing.TYPE_SPAKE2)

    private fun client() = AdbClient(identity, TestTls.exporter)

    private fun assertFailure(
        expected: PairingFailure,
        result: PairingResult,
    ) {
        assertTrue(result is PairingResult.Failed && result.reason == expected) { "expected $expected, got $result" }
    }

    companion object {
        /** An endpoint where nothing listens (a port that was just released). */
        fun closedPort(): AdbEndpoint {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            return AdbEndpoint("127.0.0.1", port)
        }
    }
}
