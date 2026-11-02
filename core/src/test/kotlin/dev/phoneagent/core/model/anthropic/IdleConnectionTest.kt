package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.Part
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * By default the adapter keeps no idle connection (model-layer §3.1): a server or middlebox that
 * closes a connection between two requests cannot fail the next one, which OkHttp would otherwise
 * send on the dead connection (it checks an idle connection only after 10 s) and, with its own
 * retries off, not resend.
 */
class IdleConnectionTest {
    private val server = ClosingServer(Kit.TEXT_REPLY)

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a connection the server closed after its reply does not fail the next request`() =
        runTest {
            val model = Kit.adapters().model(Kit.profile(server.base), Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
            assertEquals(listOf(Part.Text("Hi")), model.complete(Kit.request()).parts)
            assertTrue(server.closed.tryAcquire(WAIT_S, TimeUnit.SECONDS), "the server closed the first connection")
            assertEquals(listOf(Part.Text("Hi")), model.complete(Kit.request()).parts)
            assertEquals(2, server.requests.get(), "each request reached the server once")
        }

    /**
     * HTTP/1.1 on 127.0.0.1 that answers the first request of each connection with [body] (SSE)
     * and then closes that connection, without saying so in the response; [closed] gets a permit
     * after each close.
     */
    private class ClosingServer(
        private val body: String,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        val closed = Semaphore(0)
        val requests = AtomicInteger()
        val base = "http://127.0.0.1:${listener.localPort}"
        private val acceptor = thread(name = "closing-server", isDaemon = true) { serve() }

        private fun serve() {
            while (true) {
                val connection =
                    try {
                        listener.accept()
                    } catch (expectedStop: SocketException) {
                        // close() ends a blocked accept(): the test is over.
                        return
                    }
                connection.use(::answer)
                closed.release()
            }
        }

        private fun answer(connection: Socket) {
            val input = BufferedInputStream(connection.getInputStream())
            val headers = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
            val length =
                headers.firstNotNullOfOrNull {
                    CONTENT_LENGTH
                        .matchEntire(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }
            input.readNBytes(length ?: 0)
            requests.incrementAndGet()
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\n\r\n"
            connection.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
        }

        /** One header line without its CRLF; the headers end with an empty one. */
        private fun line(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                when (val next = input.read()) {
                    -1 -> return line.toString().ifEmpty { null }
                    '\n'.code -> return line.toString().removeSuffix("\r")
                    else -> line.append(next.toChar())
                }
            }
        }

        override fun close() {
            listener.close()
            acceptor.join(TimeUnit.SECONDS.toMillis(WAIT_S))
        }
    }

    private companion object {
        const val WAIT_S = 5L
        const val BACKLOG = 4
        val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")
    }
}
