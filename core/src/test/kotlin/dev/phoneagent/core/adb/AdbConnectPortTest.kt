package dev.phoneagent.core.adb

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

class AdbConnectPortTest {
    private val handshake =
        Handshake(Socket(), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), "device::", 1, 1)
    private val session = AdbSession(handshake, AdbConfig(), testIo)
    private val tried = mutableListOf<Int>()
    private val cache = MemoryCache()

    /** adbd listens on [live]; [answers] overrides what other ports say (default: nothing listens). */
    private fun connector(
        live: Int?,
        answers: Map<Int, ConnectFailure> = emptyMap(),
    ): suspend (AdbEndpoint) -> ConnectResult =
        { endpoint ->
            tried += endpoint.port
            when (endpoint.port) {
                live -> {
                    ConnectResult.Connected(session)
                }

                else -> {
                    ConnectResult.Failed(
                        answers[endpoint.port] ?: ConnectFailure.UNREACHABLE,
                        "port ${endpoint.port}",
                    )
                }
            }
        }

    @Test
    fun `a cached port that works is used first`() =
        runTest {
            cache.port = 40_001
            val finder = AdbServiceFinder { _, _ -> error("mDNS is not needed") }
            val result = AdbConnectPort(connector(live = 40_001), cache, finder, null, PortSearchConfig()).connect()
            result as PortSearchResult.Connected
            assertSame(session, result.session)
            assertEquals(AdbEndpoint("127.0.0.1", 40_001), result.endpoint)
            assertEquals(PortSource.CACHE, result.source)
            assertEquals(listOf(40_001), tried)
        }

    @Test
    fun `then every mDNS port once, then the scan without the ports already tried`() =
        runTest {
            cache.port = 40_005
            var asked: Pair<AdbService, Long>? = null
            val finder =
                AdbServiceFinder { service, timeout ->
                    asked = service to timeout
                    listOf(40_002, 40_005, 0, 70_000, 40_003)
                }
            val probe =
                PortProbe { _, port ->
                    if (port == 40_007 ||
                        port == 40_002
                    ) {
                        PortState.ADB
                    } else {
                        PortState.CLOSED
                    }
                }
            val probed = mutableSetOf<Int>()
            val scan =
                LocalPortScan({ listOf("127.0.0.1") }, 40_000..40_010, PortScanConfig(), RecordingProbe(probe, probed))
            val config = PortSearchConfig(mdnsTimeoutMs = 1_234)
            val result = AdbConnectPort(connector(live = 40_007), cache, finder, scan, config).connect()
            result as PortSearchResult.Connected
            assertEquals(PortSource.SCAN, result.source)
            assertEquals(AdbService.CONNECT to 1_234L, asked)
            assertEquals(listOf(40_005, 40_002, 40_003, 40_007), tried)
            assertEquals(40_007, cache.port, "the working port is cached")
            assertEquals(setOf(40_002, 40_003, 40_005), (40_000..40_010).toSet() - probed, "skipped by the scan")
        }

    @Test
    fun `an unauthorized key ends the search`() =
        runTest {
            val finder = AdbServiceFinder { _, _ -> listOf(40_001, 40_002) }
            val unauthorized = mapOf(40_001 to ConnectFailure.UNAUTHORIZED)
            val probe = PortProbe { _, _ -> PortState.CLOSED }
            val scan = LocalPortScan({ error("no scan after a refusal") }, 1..2, PortScanConfig(), probe)
            val result =
                AdbConnectPort(
                    connector(null, unauthorized),
                    cache,
                    finder,
                    scan,
                    PortSearchConfig(),
                ).connect()
            result as PortSearchResult.Failed
            assertEquals(ConnectFailure.UNAUTHORIZED, result.lastFailure?.reason)
            assertEquals(listOf(AdbEndpoint("127.0.0.1", 40_001)), result.tried)
            assertNull(result.scan)
            assertNull(cache.port)
        }

    @Test
    fun `nothing found anywhere reports what was tried and scanned`() =
        runTest {
            cache.port = 40_001
            val finder = AdbServiceFinder { _, _ -> listOf(40_001) }
            val scan =
                LocalPortScan(
                    { listOf("127.0.0.1") },
                    40_000..40_002,
                    PortScanConfig(),
                    PortProbe {
                        _,
                        _,
                        ->
                        PortState.OPEN
                    },
                )
            val connector = connector(null, mapOf(40_001 to ConnectFailure.TLS))
            val result = AdbConnectPort(connector, cache, finder, scan, PortSearchConfig()).connect()
            assertEquals(
                PortSearchResult.Failed(
                    ConnectResult.Failed(ConnectFailure.TLS, "port 40001"),
                    listOf(AdbEndpoint("127.0.0.1", 40_001)),
                    ScanResult(emptyList(), 2),
                ),
                result,
            )
        }

    @Test
    fun `no candidates at all is a failure without a last failure`() =
        runTest {
            val finder = AdbServiceFinder { _, _ -> emptyList() }
            val client = AdbClient(TestTls.client, TestTls.exporter)
            val result = AdbConnectPort(client, cache, finder, null).connect()
            assertEquals(PortSearchResult.Failed(null, emptyList(), null), result)
        }

    private class MemoryCache : PortCache {
        var port: Int? = null

        override fun load(): Int? = port

        override fun save(port: Int) {
            this.port = port
        }
    }

    /** Records the ports a scan probed. */
    private class RecordingProbe(
        private val inner: PortProbe,
        private val probed: MutableSet<Int>,
    ) : PortProbe {
        override suspend fun probe(
            host: String,
            port: Int,
        ): PortState {
            synchronized(probed) { probed += port }
            return inner.probe(host, port)
        }
    }
}
