package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.Kit.send
import dev.phoneagent.core.model.openai.UnquotedReplyTest.Spelling
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * OkHttp quotes the server's bytes in the `IOException`s of a broken HTTP exchange, as text or in
 * hex (model-layer §2). WP04's raw socket repro found three ways a reply's content gets there; a
 * loopback server answers with those bytes, the next line carrying the secret in the call
 * arguments, spelled four ways ([Spelling]), in each protocol's stream. The [ModelError.Network]
 * of a call names the failure by its type only, and neither it nor its cause chain holds a piece
 * of the secret, as text or in hex; nor does the [ProbeResult] of a "test and save" whose trial
 * call breaks the same way. Assertions never print the secret.
 */
class RawSocketQuoteTest {
    /** A data line whose call arguments carry [spelling]. */
    private fun callLine(
        protocol: ModelProtocol,
        spelling: Spelling,
    ): String {
        val args = "{\\\"note\\\":\\\"${spelling.text}\\\"}"
        return if (protocol == ModelProtocol.OPENAI_RESPONSES) {
            "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc1\",\"output_index\":0," +
                "\"delta\":\"$args\"}"
        } else {
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":" +
                "{\"name\":\"get_weather\",\"arguments\":\"$args\"}}]}}]}"
        }
    }

    /** A 200 chunked stream whose first chunk is 2 bytes short of the first line, the call line next. */
    private fun shortChunk(
        protocol: ModelProtocol,
        spelling: Spelling,
    ): Answer {
        // OkHttp reads the rest of the line as the chunk's end, then takes the next line as the
        // next chunk size: "da" parses as hex and the rest is quoted as its extensions.
        val first =
            if (protocol == ModelProtocol.OPENAI_RESPONSES) {
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"m1\",\"output_index\":0,\"delta\":\"Hi\"}"
            } else {
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}"
            }
        return Answer(CHUNKED + "${(first.length - 2).toString(HEX)}\r\n$first\n${callLine(protocol, spelling)}\n\n")
    }

    /** A 200 chunked stream whose chunk stops just before the secret, the rest of its line unended. */
    private fun cutLine(
        protocol: ModelProtocol,
        spelling: Spelling,
    ): Answer {
        // OkHttp then looks for the end of the chunk's line and finds the end of the stream; its
        // EOFException writes what it read in hex.
        val line = callLine(protocol, spelling)
        return Answer(CHUNKED + "${line.indexOf(spelling.text).toString(HEX)}\r\n$line")
    }

    /** [head] with a body of [body], then the call line past its `Content-Length`; the connection stays open. */
    private fun overLength(
        head: String,
        body: String,
        line: String,
    ): Answer = Answer("$head\r\nContent-Length: ${body.length}\r\n\r\n$body$line\r\n", keepOpen = true)

    /** A whole reply answering "ok". */
    private fun reply(protocol: ModelProtocol): String =
        if (protocol == ModelProtocol.OPENAI_RESPONSES) {
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":" +
                "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n"
        } else {
            Kit.sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
        }

    private fun adapters(keepAlive: Duration): OpenAiAdapters =
        Kit.adapters(Keys(ECHOED), HttpTimeouts(readMs = READ_MS, idleConnectionKeepAlive = keepAlive).client())

    private fun model(
        protocol: ModelProtocol,
        base: String,
        keepAlive: Duration = Duration.ZERO,
    ): ChatModel {
        val profile = Kit.profile(base, protocol)
        return adapters(keepAlive)
            .model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), WireOptions.defaultsFor(profile))
    }

    /** Checks the next call of [model] fails as a [ModelError.Network] of [exception] that quotes nothing. */
    private suspend fun assertFails(
        protocol: ModelProtocol,
        model: ChatModel,
        exception: String,
    ) {
        // Any type: a failure message must not print an error before it is checked.
        val error = assertThrows<Throwable>("$protocol") { model.send(Kit.request()) }
        assertNoPiece("$protocol", "${error.message} $error ${error.stackTraceToString()}")
        assertTrue(error is ModelError.Network, "$protocol: a network failure")
        assertTrue(error.message.orEmpty().endsWith("($exception)"), "$protocol: named by its type")
    }

    /** Checks a probe that gets [listing] then [trial] fails at the trial call with [exception], quoting nothing. */
    private suspend fun assertProbeFails(
        protocol: ModelProtocol,
        exception: String,
        listing: Answer,
        trial: Answer?,
        keepAlive: Duration = Duration.ZERO,
    ) = RawServer(listOfNotNull(listing, trial)).use { server ->
        val result =
            adapters(keepAlive)
                .probe()
                .testAndSave(Kit.profile(server.base, protocol), Kit.MODEL, Kit.capabilities(), TOKEN)
        assertNoPiece("$protocol probe", result.toString())
        assertTrue(result is ProbeResult.Failed, "$protocol: the probe failed")
        val failed = result as ProbeResult.Failed
        assertEquals(ProbeStep.TOOL_CALL to ModelErrorKind.NETWORK, failed.step to failed.kind, "$protocol")
        assertTrue(failed.message.endsWith("($exception)"), "$protocol: named by its type")
    }

    @ParameterizedTest(name = "a chunk shorter than its line: {0}")
    @EnumSource(Spelling::class)
    fun `a chunk size that falls short of the line quotes none of the next line`(spelling: Spelling) =
        runTest {
            for (protocol in PROTOCOLS) {
                RawServer(listOf(shortChunk(protocol, spelling))).use { server ->
                    assertFails(protocol, model(protocol, server.base), "ProtocolException")
                }
                assertProbeFails(protocol, "ProtocolException", NO_LIST, shortChunk(protocol, spelling))
            }
        }

    @ParameterizedTest(name = "a Content-Length that falls short, then reuse: {0}")
    @EnumSource(Spelling::class)
    fun `bytes left after a short Content-Length are not quoted as the next status line`(spelling: Spelling) =
        runTest {
            for (protocol in PROTOCOLS) {
                val line = callLine(protocol, spelling)
                RawServer(listOf(overLength(SSE_HEAD, reply(protocol), line))).use { server ->
                    val model = model(protocol, server.base, KEEP_ALIVE)
                    assertEquals(Part.Text("ok"), model.send(Kit.request()).parts.single(), "$protocol")
                    assertFails(protocol, model, "ProtocolException")
                }
                // The trial call reuses the connection the model list left the line on.
                assertProbeFails(protocol, "ProtocolException", overLength(NOT_FOUND, "{}", line), null, KEEP_ALIVE)
            }
        }

    @ParameterizedTest(name = "a body cut in the middle of a line: {0}")
    @EnumSource(Spelling::class)
    fun `a body cut in the middle of a line shows none of it in hex`(spelling: Spelling) =
        runTest {
            for (protocol in PROTOCOLS) {
                RawServer(listOf(cutLine(protocol, spelling))).use { server ->
                    assertFails(protocol, model(protocol, server.base), "EOFException")
                }
                assertProbeFails(protocol, "EOFException", NO_LIST, cutLine(protocol, spelling))
            }
        }

    /** One scripted response: [bytes], then the connection closes unless [keepOpen]. */
    private class Answer(
        val bytes: String,
        val keepOpen: Boolean = false,
    )

    /**
     * A loopback server that answers requests with [answers] in order, whichever connection they
     * come on; once they run out, the next request's connection closes unanswered.
     */
    private class RawServer(
        answers: List<Answer>,
    ) : AutoCloseable {
        private val left = ArrayDeque(answers)
        private val socket = ServerSocket(0, 1, Kit.LOOPBACK)
        private val worker = thread(isDaemon = true) { acceptAll() }

        val base: String = "http://${Kit.LOOPBACK.hostAddress}:${socket.localPort}/v1"

        private fun acceptAll() {
            while (true) {
                val connection =
                    try {
                        socket.accept()
                    } catch (expectedClosed: SocketException) {
                        // close() ran: the test is over.
                        return
                    }
                try {
                    connection.use(::serve)
                } catch (expectedDropped: SocketException) {
                    // The client gave up on this connection (it refused what it read); serve the next one.
                }
            }
        }

        private fun serve(connection: Socket) {
            while (readRequest(connection.getInputStream())) {
                val answer = left.removeFirstOrNull() ?: return
                connection.getOutputStream().write(answer.bytes.toByteArray())
                connection.getOutputStream().flush()
                if (!answer.keepOpen) return
            }
        }

        override fun close() {
            socket.close()
            worker.join(JOIN_MS)
        }
    }

    private companion object {
        /** The same placeholder as [UnquotedReplyTest]: [Spelling] spells it. */
        const val ECHOED = "plain/quill\"vexm-r5-0001"
        const val TOKEN = "pa-raw"
        const val MIN_PIECE = 6
        const val HEX = 16
        const val READ_MS = 5_000L
        const val JOIN_MS = 5_000L
        const val SSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream"
        const val CHUNKED = "$SSE_HEAD\r\nTransfer-Encoding: chunked\r\n\r\n"
        const val NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json"
        val KEEP_ALIVE = 1.minutes
        val PROTOCOLS = listOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES)

        /** A model list the probe goes past: the route lists none. */
        val NO_LIST = Answer("$NOT_FOUND\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")

        /**
         * Reads one request: its head up to the blank line, then `Content-Length` bytes of body.
         * False when the connection ended before a whole head.
         */
        fun readRequest(input: InputStream): Boolean {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return false
                head.append(next.toChar())
            }
            val length =
                Regex("(?im)^content-length:\\s*(\\d+)")
                    .find(head)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            input.readNBytes(length)
            return true
        }

        /** Fails when [text] holds [MIN_PIECE] or more consecutive characters of [ECHOED], as text or in hex. */
        fun assertNoPiece(
            what: String,
            text: String,
        ) {
            val pieces = (0..ECHOED.length - MIN_PIECE).map { ECHOED.substring(it, it + MIN_PIECE) }
            assertFalse(pieces.any { text.contains(it) }, "$what: holds a piece of the secret (value not shown)")
            val hex = pieces.map { it.encodeUtf8().hex() }
            assertFalse(hex.any { text.contains(it) }, "$what: holds a piece of the secret in hex (value not shown)")
        }
    }
}
