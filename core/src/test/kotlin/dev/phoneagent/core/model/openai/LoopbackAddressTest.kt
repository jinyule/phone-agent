package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Test clients dial the address the test server is bound to, never `localhost`: OkHttp tries
 * `[::1]` first for it, and whatever listens on `[::1]` at the same port (an emulator's ephemeral
 * port, here a stand-in that drops every connection) would get the request instead.
 */
class LoopbackAddressTest {
    @StartStop
    private val server = Kit.server()

    /** A listener on `[::1]` at the server's port that accepts and drops connections; `null` when it cannot bind. */
    private fun squatter(accepted: AtomicInteger): ServerSocket? {
        val socket = ServerSocket()
        val bound =
            try {
                socket.bind(InetSocketAddress(InetAddress.getByName("::1"), server.port))
                true
            } catch (expectedUnavailable: IOException) {
                // No IPv6 loopback here, or someone else holds the port: there is nothing to dial by mistake.
                socket.close()
                false
            }
        if (!bound) return null
        thread(isDaemon = true, name = "squatter") {
            while (!socket.isClosed) {
                val connection =
                    try {
                        socket.accept()
                    } catch (expectedClosed: IOException) {
                        // The test closed the listener.
                        null
                    }
                connection?.use { accepted.incrementAndGet() }
            }
        }
        return socket
    }

    @Test
    fun `a request reaches the test server even with a listener on the IPv6 loopback at its port`() =
        runTest {
            assumeTrue(server.socketAddress.address !is Inet6Address, "the test server is bound to IPv4")
            val accepted = AtomicInteger()
            val squatter = squatter(accepted)
            assumeTrue(squatter != null, "[::1] at the server's port cannot be bound (no IPv6 loopback, or taken)")
            checkNotNull(squatter).use {
                server.enqueueSse(
                    Kit.sse("""{"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}"""),
                )
                val model =
                    Kit.adapters().unprobed(
                        Kit.profile(server.base()),
                        Kit.MODEL,
                        Kit.capabilities(),
                        Kit.blobs(),
                    )
                val reply = model.send(Kit.request())
                assertEquals(listOf(Part.Text("hi")), reply.parts)
                assertEquals(1, server.requestCount, "the test server got the request")
                assertEquals(0, accepted.get(), "nothing dialed the IPv6 listener")
            }
        }
}
