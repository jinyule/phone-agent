package dev.phoneagent.core.model.anthropic

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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Line splitting of streamed bodies: LF, CRLF and CR all end a line (WHATWG SSE). */
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
    private fun reply(pieces: List<String>): HttpReply {
        val response =
            Response
                .Builder()
                .request(Request.Builder().url("http://localhost/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(Pieces(pieces).buffer().asResponseBody())
                .build()
        return HttpReply(response, Dispatchers.IO, Redactor.SHAPES_ONLY)
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

    companion object {
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
