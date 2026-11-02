package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.base
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import dev.phoneagent.core.model.anthropic.Kit.next
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The tests' mock server is reached by a literal IPv4 loopback address, never by `localhost`:
 * which address `localhost` resolves to first depends on the machine, and another process
 * listening on the other one would get the request.
 */
class LoopbackTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    @Test
    fun `requests go to the literal loopback address`() =
        runTest {
            server.enqueueSse(Kit.TEXT_REPLY)
            Kit.model(server).complete(Kit.request())
            assertEquals("127.0.0.1:${server.port}", server.next().headers["Host"])
        }

    @Test
    fun `a listener on the IPv6 loopback at the same port gets nothing`() =
        runTest {
            val bound =
                try {
                    ServerSocket(server.port, 1, InetAddress.getByName("::1"))
                } catch (ignoredUnavailable: IOException) {
                    // No IPv6 loopback here, or another process holds [::1] at this port.
                    null
                }
            assumeTrue(
                bound != null,
                "[::1]:${server.port} cannot be bound here (no IPv6 loopback, or the port is taken)",
            )
            val other = checkNotNull(bound)
            val reached = AtomicInteger()
            val acceptor =
                thread(name = "ipv6-listener", isDaemon = true) {
                    while (true) {
                        try {
                            other.accept().close()
                        } catch (expectedStop: SocketException) {
                            // close() ends a blocked accept(): the test is over.
                            break
                        }
                        reached.incrementAndGet()
                    }
                }
            other.use {
                server.enqueueSse(Kit.TEXT_REPLY)
                assertEquals(listOf(Part.Text("Hi")), Kit.model(server).complete(Kit.request()).parts)
            }
            acceptor.join(TimeUnit.SECONDS.toMillis(WAIT_S))
            assertEquals(0, reached.get(), "nothing went to the other listener")
        }

    private companion object {
        const val WAIT_S = 5L
    }
}
