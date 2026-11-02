package dev.phoneagent.core.adb

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LocalPortScanTest {
    @Test
    fun `finds adbd on loopback with real sockets`() =
        realTime {
            FakeAdbd({ emptyList() }).use { adbd ->
                val port = adbd.endpoint.port
                val scan = LocalPortScan({ listOf("127.0.0.1") }, port..port, PortScanConfig(parallelism = 2))
                assertEquals(ScanResult(listOf(adbd.endpoint), 1), scan.scan(emptySet()))
                assertEquals(ScanResult(emptyList(), 0), scan.scan(setOf(port)))
            }
        }

    @Test
    fun `the socket probe tells adbd from other services and closed ports`() =
        realTime {
            val probe = SocketPortProbe(PortScanConfig(), testIo)
            FakeAdbd({ emptyList() }).use { adbd ->
                assertEquals(PortState.ADB, probe.probe("127.0.0.1", adbd.endpoint.port))
            }
            Answering(ByteArray(0)).use { assertEquals(PortState.OPEN, probe.probe("127.0.0.1", it.port)) }
            Answering("SSH-2.0-OpenSSH_9.6\r\n".repeat(2).toByteArray()).use {
                assertEquals(PortState.OPEN, probe.probe("127.0.0.1", it.port))
            }
            val cnxn = AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, 4096, "device::\u0000".toByteArray()).encode()
            Answering(
                cnxn,
            ).use { assertEquals(PortState.OPEN, probe.probe("127.0.0.1", it.port), "legacy adbd without TLS") }
            assertEquals(PortState.CLOSED, probe.probe("127.0.0.1", AdbPairingTest.closedPort().port))
        }

    @Test
    fun `a port that never answers counts as open after the probe timeout, in virtual time`() =
        runTest {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
                val probe = SocketPortProbe(PortScanConfig(probeTimeoutMs = 700), testIo)
                assertEquals(PortState.OPEN, probe.probe("127.0.0.1", silent.localPort))
                assertEquals(700, virtualTime)
            }
        }

    @Test
    fun `scans hosts in order and stops after the first host with adbd`() =
        runTest {
            val probed = Collections.synchronizedList(mutableListOf<String>())
            val probe =
                PortProbe { host, port ->
                    probed += "$host:$port"
                    when {
                        host == "10.0.0.2" && port == 40_002 -> PortState.ADB
                        host == "10.0.0.2" && port == 40_001 -> PortState.ADB
                        port == 40_003 -> PortState.OPEN
                        else -> PortState.CLOSED
                    }
                }
            val hosts = listOf("10.0.0.1", "10.0.0.2", "10.0.0.1", "10.0.0.3")
            val scan = LocalPortScan({ hosts }, 40_000..40_003, PortScanConfig(parallelism = 3), probe)
            val result = scan.scan(skip = setOf(40_000))
            assertEquals(listOf(AdbEndpoint("10.0.0.2", 40_001), AdbEndpoint("10.0.0.2", 40_002)), result.found)
            assertEquals(4, result.openPorts)
            assertEquals(6, probed.size, "two hosts of three ports each")
            assertEquals(setOf("10.0.0.1", "10.0.0.2"), probed.map { it.substringBefore(':') }.toSet())
        }

    @Test
    fun `at most parallelism ports are probed at once`() =
        runTest {
            val running = AtomicInteger()
            val peak = AtomicInteger()
            val probe =
                PortProbe { _, _ ->
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    delay(10)
                    running.decrementAndGet()
                    PortState.CLOSED
                }
            val result = LocalPortScan({ listOf("h") }, 1..100, PortScanConfig(parallelism = 7), probe).scan(emptySet())
            assertEquals(ScanResult(emptyList(), 0), result)
            assertEquals(7, peak.get())
            assertEquals(150, virtualTime, "100 ports, 7 at a time, 10 ms each")
        }

    @Test
    fun `the budget ends the scan with what was found so far`() =
        runTest {
            val probe =
                PortProbe { _, port ->
                    when (port) {
                        1 -> PortState.ADB
                        2 -> PortState.OPEN
                        else -> awaitCancellation()
                    }
                }
            val scan = LocalPortScan({ listOf("h", "other") }, 1..50, PortScanConfig(budgetMs = 1_500), probe)
            assertEquals(ScanResult(listOf(AdbEndpoint("h", 1)), 2), scan.scan(emptySet()))
            assertEquals(1_500, virtualTime)
        }

    @Test
    fun `ranges are checked and read from the kernel's format`() {
        assertThrows<IllegalArgumentException> { LocalPortScan({ emptyList() }, 0..10) }
        assertThrows<IllegalArgumentException> { LocalPortScan({ emptyList() }, 1..65_536) }
        assertThrows<IllegalArgumentException> { LocalPortScan({ emptyList() }, IntRange.EMPTY) }
        assertEquals(30_000..65_535, LocalPortScan.FALLBACK_RANGE)
        assertEquals(ScanResult(emptyList(), 0), inRealTime { LocalPortScan({ emptyList() }).scan(emptySet()) })
        assertEquals(32_768..60_999, LocalPortScan.parseRange("32768\t60999\n"))
        assertEquals(1024..65_535, LocalPortScan.parseRange(" 1024   65535 "))
        assertEquals(40_000..40_000, LocalPortScan.parseRange("40000 40000"))
        val fallbacks =
            listOf(
                null,
                "",
                "40000",
                "1 2 3",
                "abc 60000",
                "40000 x",
                "80 60000",
                "40000 30000",
                "1024 65536",
                "70000 70001",
            )
        for (text in fallbacks) assertEquals(LocalPortScan.FALLBACK_RANGE, LocalPortScan.parseRange(text), "for $text")
    }

    /** Accepts connections, sends [greeting] and hangs up (another service than adbd). */
    private class Answering(
        private val greeting: ByteArray,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        private val worker =
            thread(isDaemon = true, name = "answering") {
                try {
                    while (true) {
                        server.accept().use { it.getOutputStream().write(greeting) }
                    }
                } catch (expectedClosed: java.io.IOException) {
                    // close() ends the loop.
                }
            }

        override fun close() {
            server.close()
            worker.join(10_000)
        }
    }
}
