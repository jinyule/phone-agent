package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory

/**
 * A server may close an idle keep-alive connection without saying so (no `Connection: close`).
 * By default the adapters keep no idle connection, so the next call opens a new one and succeeds
 * with one request, instead of failing on the closed one (OkHttp's own resend stays off).
 */
class IdleConnectionTest {
    private val sockets = ClosingSockets()

    @StartStop
    private val server = Kit.server(sockets)

    /** A reply after which the server closes the connection, with no `Connection: close` header. */
    private fun enqueueThenClose() {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(Kit.sse("""{"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}"""))
                .onResponseEnd(SocketEffect.CloseSocket())
                .build(),
        )
    }

    @Test
    fun `a call after the server closed the idle connection opens a new one`() =
        runTest {
            enqueueThenClose()
            enqueueThenClose()
            val model = Kit.adapters().unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())
            assertEquals(listOf(Part.Text("hi")), model.send(Kit.request()).parts)
            assertTrue(sockets.closed.await(WAIT_S, TimeUnit.SECONDS), "the server closed the first connection")
            assertEquals(listOf(Part.Text("hi")), model.send(Kit.request()).parts)
            assertEquals(2, server.requestCount, "one request per call")
        }

    /** Server sockets whose accepted connections count down [closed] when the server closes them. */
    private class ClosingSockets : ServerSocketFactory() {
        val closed = CountDownLatch(1)

        override fun createServerSocket(): ServerSocket =
            object : ServerSocket() {
                override fun accept(): Socket {
                    val socket =
                        object : Socket() {
                            override fun close() {
                                super.close()
                                closed.countDown()
                            }
                        }
                    implAccept(socket)
                    return socket
                }
            }

        override fun createServerSocket(port: Int): ServerSocket = unused()

        override fun createServerSocket(
            port: Int,
            backlog: Int,
        ): ServerSocket = unused()

        override fun createServerSocket(
            port: Int,
            backlog: Int,
            address: InetAddress?,
        ): ServerSocket = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("MockWebServer binds an unbound socket")
    }

    private companion object {
        const val WAIT_S = 5L
    }
}
