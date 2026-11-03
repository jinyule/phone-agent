package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * An I/O failure is described by its type and the step it hit, never by its message: OkHttp's
 * messages quote what the server sent (a line it read as a chunk size or a status line, the bytes
 * of a line the stream cut, in hex), and a secret written there with a `u` escape or `\/` is not
 * one masking finds. The cause keeps class names and stack traces only. Each case runs with the
 * secret written as sent, JSON-escaped, with a `u` escape and with `\/`.
 */
class TransportQuoteTest {
    private var server: RawServer? = null

    @AfterEach
    fun stop() {
        server?.close()
    }

    private fun model(
        raw: RawServer,
        reuse: Boolean = false,
    ): ChatModel {
        server = raw
        val keepAlive = if (reuse) 1.minutes else Duration.ZERO
        val timeouts = AnthropicTimeouts(readMs = READ_MS, idleConnectionKeepAlive = keepAlive)
        return Kit
            .adapters(Keys(SECRET), timeouts.client())
            .model(Kit.profile(raw.base), Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
    }

    private fun assertUnquoted(
        error: ModelError.Network,
        expected: String,
        form: String,
    ) {
        assertEquals("network: $expected", error.message, form)
        val printed = error.stackTraceToString()
        (FORMS + HEX).forEach { leak -> assertFalse(leak in printed, "$form: the error shows the key: $printed") }
        generateSequence(error.cause) { it.cause }.forEach { cause ->
            assertTrue(CLASS_NAME.matches(cause.message.orEmpty()), "$form: a cause carries a message: $cause")
        }
    }

    @ParameterizedTest
    @MethodSource("forms")
    fun `a chunk size line that is a line of the reply is not quoted`(form: String) =
        runTest {
            // The declared size ends the chunk inside the first line; OkHttp reads the next line
            // ("data: …", whose "da" is hex) as a chunk size and quotes the rest of it.
            val reply = "event: x\ndata: {\"note\":\"$form\"}\n\n"
            val raw =
                RawServer { _, output ->
                    output.write(CHUNKED_HEAD + "8\r\n" + reply + "\r\n0\r\n\r\n")
                }
            val error = assertThrows<ModelError.Network> { model(raw).complete(Kit.request()) }
            assertUnquoted(
                error,
                "the server broke the HTTP protocol while reading the reply (ProtocolException)",
                form,
            )
        }

    @ParameterizedTest
    @MethodSource("forms")
    fun `bytes past a short Content-Length are not quoted as the next status line`(form: String) =
        runTest {
            val extra = "data: {\"note\":\"$form\"}\r\n"
            val raw =
                RawServer { input, output ->
                    val reply = Kit.TEXT_REPLY.toByteArray()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${reply.size}\r\n\r\n",
                    )
                    output.write(reply)
                    output.write(extra)
                    output.flush()
                    RawServer.request(input)
                }
            val model = model(raw, reuse = true)
            model.complete(Kit.request())
            val error = assertThrows<ModelError.Network> { model.complete(Kit.request()) }
            assertUnquoted(
                error,
                "the server broke the HTTP protocol while sending the request (ProtocolException)",
                form,
            )
            assertEquals(1, raw.connections, "the second request went out on the first connection")
        }

    @ParameterizedTest
    @MethodSource("forms")
    fun `a line the stream cuts is not quoted in hex`(form: String) =
        runTest {
            val raw =
                RawServer { _, output ->
                    output.write(CHUNKED_HEAD + "1a;note=$form")
                }
            val error = assertThrows<ModelError.Network> { model(raw).complete(Kit.request()) }
            assertUnquoted(error, "the connection ended early while reading the reply (EOFException)", form)
        }

    /**
     * HTTP/1.1 on 127.0.0.1: reads each request of the first connection and hands the rest to
     * [answer], then closes it; [connections] counts the connections accepted.
     */
    private class RawServer(
        private val answer: (InputStream, OutputStream) -> Unit,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${listener.localPort}"

        @Volatile
        var connections = 0
            private set

        private val acceptor = thread(name = "raw-server", isDaemon = true) { serve() }

        private fun serve() {
            while (true) {
                val connection =
                    try {
                        listener.accept()
                    } catch (expectedStop: SocketException) {
                        // close() ends a blocked accept(): the test is over.
                        return
                    }
                connections++
                connection.soTimeout = READ_MS.toInt()
                connection.use {
                    val input = BufferedInputStream(it.getInputStream())
                    request(input)
                    try {
                        answer(input, it.getOutputStream())
                        it.getOutputStream().flush()
                    } catch (expectedHangUp: SocketException) {
                        // The client gave up on the reply first.
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            acceptor.join(TimeUnit.SECONDS.toMillis(WAIT_S))
        }

        companion object {
            /** Reads one request (headers, then a body of its Content-Length); `false` at the end of the stream. */
            fun request(input: InputStream): Boolean {
                val headers = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
                if (headers.isEmpty()) return false
                val length =
                    headers.firstNotNullOfOrNull {
                        CONTENT_LENGTH
                            .matchEntire(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    }
                input.readNBytes(length ?: 0)
                return true
            }

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
        }
    }

    private companion object {
        const val WAIT_S = 5L
        const val READ_MS = 10_000L

        /** What a cause stand-in's message is: the class name of the exception it stands for. */
        val CLASS_NAME = Regex("[A-Za-z0-9_.$]+")
        const val BACKLOG = 4
        val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")
        const val CHUNKED_HEAD =
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"

        /** Backslash, built here: tool inputs decode escapes written out. */
        val BS = Char(BACKSLASH).toString()
        const val BACKSLASH = 92

        /** A key with a quote and a slash, so that each escaping writes it differently. */
        const val SECRET = "pa\"ss/word-0001"

        /** [SECRET] as sent, JSON-escaped, with a `u` escape for its first letter, and with `\/`. */
        val FORMS =
            listOf(
                SECRET,
                "pa$BS\"ss/word-0001",
                "${BS}u0070a$BS\"ss/word-0001",
                "pa$BS\"ss$BS/word-0001",
            )

        /** What OkHttp's hex dumps of a line would show of any form. */
        val HEX = FORMS.map { form -> form.toByteArray().joinToString("") { "%02x".format(it) } }

        @JvmStatic
        fun forms(): List<String> = FORMS

        fun OutputStream.write(text: String) = write(text.toByteArray())
    }
}
