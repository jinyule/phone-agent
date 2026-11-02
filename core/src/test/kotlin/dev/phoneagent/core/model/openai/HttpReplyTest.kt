package dev.phoneagent.core.model.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Line splitting of streamed bodies: LF, CRLF and CR all end a line (WHATWG SSE). Error bodies
 * are masked before they are cut, and the cut splits no character.
 */
class HttpReplyTest {
    /** A body that arrives in exactly these pieces, one per read. */
    private class Pieces(
        pieces: List<String>,
    ) : Source {
        private val pending = ArrayDeque(pieces)

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val next = pending.removeFirstOrNull() ?: return -1
            sink.writeUtf8(next)
            return next.length.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    @Suppress("InjectDispatcher") // Pieces never block; the reply needs some I/O dispatcher.
    private fun reply(
        pieces: List<String>,
        redactor: Redactor = Redactor.SHAPES_ONLY,
    ): HttpReply {
        val response =
            Response
                .Builder()
                .request(Request.Builder().url("http://localhost/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(Pieces(pieces).buffer().asResponseBody())
                .build()
        return HttpReply(response, Dispatchers.IO, redactor)
    }

    private suspend fun events(pieces: List<String>): List<String> {
        val events = mutableListOf<String>()
        reply(pieces).readEvents({
            events += it.data
            false
        }, { events += "json:$it" })
        return events
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endings")
    fun `every line ending splits events the same way`(
        name: String,
        pieces: List<String>,
    ) = runTest {
        assertEquals(listOf("a\nb", "c"), events(pieces), name)
    }

    /** The error text of a reply with [body], read with a [limit] of 100 characters. */
    private suspend fun masked(
        body: String,
        vararg secrets: String,
    ): String = reply(listOf(body), Redactor(secrets.asList())).maskedText(limit = LIMIT)

    @Test
    fun `a secret that straddles the limit is masked whole`() =
        runTest {
            val text = masked(Straddle.text(LIMIT, ECHOED, '.', tail = 50), ECHOED)
            Straddle.assertNoPrefix("the error text", text, ECHOED)
            assertEquals((".".repeat(LIMIT - ECHOED.length / 2) + Redactor.MASK + ".".repeat(50)).take(LIMIT), text)
        }

    @Test
    fun `a masked secret before the limit does not pull a cut one under it`() =
        runTest {
            // Masking the first copy shortens the text: the second copy, which the read cut at its
            // end, would move under the limit.
            val body = ECHOED + ".".repeat(LIMIT + 5 - ECHOED.length) + ECHOED + ".".repeat(50)
            val text = masked(body, ECHOED)
            Straddle.assertNoPrefix("the error text", text, ECHOED)
            assertEquals(Redactor.MASK + ".".repeat(LIMIT + 5 - ECHOED.length), text)
        }

    @Test
    fun `credential shapes the read cut short are dropped`() =
        runTest {
            // Assembled at run time so secret scanners do not take the placeholders for real tokens.
            val key = "sk-" + "abcdefghijkl"
            val jwt = "eyJ" + "notarealheader.notarealpayload"
            for (shape in listOf(key, jwt)) {
                val text = masked(".".repeat(LIMIT - 5) + shape + ".".repeat(50))
                assertEquals(".".repeat(LIMIT - 5), text)
            }
        }

    @Test
    fun `a body that fits is masked and kept whole`() =
        runTest {
            assertEquals("bad key ***, sk-short", masked("bad key $ECHOED, sk-short", ECHOED))
        }

    @Test
    fun `the cut splits no character`() =
        runTest {
            // 100 bytes end inside the 34th three-byte character.
            assertEquals("中".repeat(LIMIT / 3), masked("中".repeat(LIMIT)))
            // A secret makes the read go past the limit; 100 characters end inside the emoji.
            assertEquals(".".repeat(LIMIT - 1), masked(".".repeat(LIMIT - 1) + "\uD83D\uDE00" + "...", ECHOED))
        }

    companion object {
        private const val LIMIT = 100

        /** A placeholder without a credential shape. */
        private const val ECHOED = "plain-placeholder-0001"

        @JvmStatic
        fun endings(): List<Array<Any>> =
            listOf(
                arrayOf("LF", listOf("data: a\ndata: b\n\ndata: c\n\n")),
                arrayOf("CRLF", listOf("data: a\r\ndata: b\r\n\r\ndata: c\r\n\r\n")),
                arrayOf("CR", listOf("data: a\rdata: b\r\rdata: c\r\r")),
                arrayOf("CR in pieces", listOf("data: a\r", "data: b\r", "\r", "data: c\r\r")),
                arrayOf(
                    "CRLF split between reads",
                    listOf("data: a\r", "\ndata: b\r", "\n\r", "\ndata: c\r", "\n\r", "\n"),
                ),
                arrayOf("mixed", listOf("data: a\rdata: b\n\r\ndata: c\r", "\r")),
                arrayOf("no final terminator", listOf("data: a\rdata: b\r\rdata: c")),
            )
    }
}
