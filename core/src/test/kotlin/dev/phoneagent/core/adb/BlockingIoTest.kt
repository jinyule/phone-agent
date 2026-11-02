package dev.phoneagent.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture

class BlockingIoTest {
    @Test
    fun `returns the block's value and leaves the resource open`() =
        runTest {
            val resource = Tracked()
            assertEquals(42, blockingIo(testIo, resource) { 42 })
            assertFalse(resource.closed)
        }

    @Test
    fun `an IO failure without cancellation propagates and leaves the resource alone`() =
        runTest {
            val resource = Tracked()
            val failure =
                assertThrows<IOException> { blockingIo(testIo, resource) { throw IOException("boom") } }
            assertEquals("boom", failure.message)
            assertFalse(resource.closed)
        }

    @Test
    fun `cancellation closes the socket a blocked read waits on and is reported as cancellation`() =
        realTime {
            LoopbackPair().use { pair ->
                val reading = CompletableFuture<Unit>()
                val read =
                    async {
                        blockingIo(testIo, pair.client) {
                            reading.complete(Unit)
                            pair.client.getInputStream().read()
                        }
                    }
                reading.awaitBarrier()
                read.cancel()
                assertThrows<CancellationException> { read.await() }
                assertTrue(pair.client.isClosed)
            }
        }

    @Test
    fun `a timeout closes the socket and ends as null in virtual time`() =
        runTest {
            LoopbackPair().use { pair ->
                val reading = CompletableFuture<Unit>()
                val result =
                    async {
                        withTimeoutOrNull(5_000) {
                            blockingIo(testIo, pair.client) {
                                reading.complete(Unit)
                                pair.client.getInputStream().read()
                            }
                        }
                    }
                holdTimeUntil(reading)
                assertNull(result.await())
                assertEquals(5_000, virtualTime)
                assertTrue(pair.client.isClosed)
            }
        }

    @Test
    fun `closing quietly ignores a failing close`() {
        val failing = Closeable { throw IOException("already broken") }
        failing.closeQuietly()
        val tracked = Tracked()
        tracked.closeQuietly()
        assertTrue(tracked.closed)
    }

    private class Tracked : Closeable {
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }

    /** A connected loopback socket pair; the server side never writes. */
    private class LoopbackPair : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        private val accepted = server.accept()

        override fun close() {
            client.close()
            accepted.close()
            server.close()
        }
    }
}
