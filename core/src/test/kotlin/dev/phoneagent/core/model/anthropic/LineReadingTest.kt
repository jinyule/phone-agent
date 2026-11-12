package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

/**
 * How [HttpReply] cuts a streamed body into lines (fix-list r11): each byte is looked at once
 * however the body arrives (an LF right after a CR not at all); the lines, and how they are
 * handed out, are the ones the reader gave before on every cut of random bodies; and a 64 KiB
 * line sent a byte per chunk over HTTP is read through.
 */
class LineReadingTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    /** A body that arrives in exactly these pieces, one per read. */
    private class Pieces(
        pieces: List<ByteString>,
    ) : Source {
        private val pending = ArrayDeque(pieces)

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val next = pending.removeFirstOrNull() ?: return -1
            sink.write(next)
            return next.size.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    @Suppress("InjectDispatcher") // Pieces never block; the reply needs some I/O dispatcher.
    private fun reply(pieces: List<ByteString>): HttpReply {
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

    /** What each call of [HttpReply.lines] gave, up to the end of the body. */
    private suspend fun HttpReply.calls(): List<List<String>> = buildList { while (true) add(lines() ?: break) }

    /** Long lines and many lines, each sent a byte per read. */
    enum class Bodies(
        val text: String,
    ) {
        LONG_COMMENT(": " + "x".repeat(LIMIT) + "\n" + "data: {}\n\n"),
        NO_LINE_END("x".repeat(LIMIT)),
        LONG_LINE_CRLF("x".repeat(LIMIT) + "\r\n" + "y"),
        SHORT_LINES("data: x\r\n".repeat(LIMIT / 9)),
        LONE_CRS("\r".repeat(LIMIT)),
        MULTIBYTE("用".repeat(LIMIT / 3) + "\n"),
    }

    @ParameterizedTest
    @EnumSource(Bodies::class)
    fun `each byte is looked at once, a byte per read`(body: Bodies) =
        runTest {
            val bytes = body.text.encodeUtf8()
            val reply = reply(List(bytes.size) { bytes.substring(it, it + 1) })
            reply.calls()
            assertEquals(bytes.size.toLong() - crlfs(body.text), reply.bytesScanned, "$body: bytes looked at")
        }

    @Test
    fun `line ends cut between reads give the lines they gave before`() =
        runTest {
            val cells =
                listOf(
                    listOf("a\r", "\nb\n") to listOf(listOf("a"), listOf("b")),
                    listOf("a\r", "\n") to listOf(listOf("a")),
                    listOf("\n\n", "a\n") to listOf(listOf("", ""), listOf("a")),
                    listOf("a\rb\r", "c") to listOf(listOf("a", "b"), listOf("c")),
                    listOf("a\r") to listOf(listOf("a")),
                    listOf("\r", "\r\n", "\n") to listOf(listOf(""), listOf(""), listOf("")),
                    listOf("ab", "c", "d\ne") to listOf(listOf("abcd"), listOf("e")),
                    listOf("ab", "c\nd\ne") to listOf(listOf("abc", "d"), listOf("e")),
                )
            assertAll(
                cells.flatMap { (pieces, calls) ->
                    val bytes = pieces.map { it.encodeUtf8() }
                    val now = reply(bytes).calls()
                    val before = OldReader(bytes).calls()
                    listOf(
                        { assertEquals(calls, now, "$pieces") },
                        { assertEquals(calls, before, "$pieces, as before") },
                    )
                },
            )
        }

    @Test
    fun `random bodies cut at random give the lines they gave before`() =
        runTest {
            val random = Random(SEED)
            repeat(CASES) { _ ->
                val text = buildString { repeat(random.nextInt(MAX_PIECES)) { append(PIECES.random(random)) } }
                val bytes = text.encodeUtf8()
                val cuts = mutableListOf<ByteString>()
                var at = 0
                while (at < bytes.size) {
                    val end = minOf(bytes.size, at + 1 + random.nextInt(MAX_CHUNK))
                    cuts += bytes.substring(at, end)
                    at = end
                }
                val reply = reply(cuts)
                assertEquals(OldReader(cuts).calls(), reply.calls(), "[$text] in $cuts")
                assertEquals(bytes.size.toLong() - crlfs(text), reply.bytesScanned, "[$text] in $cuts: bytes looked at")
            }
        }

    @Test
    fun `a 64 KiB comment sent a byte per chunk is skipped and the reply is read`() =
        runTest {
            server.enqueueSse(": " + "x".repeat(LIMIT) + "\n" + Kit.TEXT_REPLY, chunkSize = 1)
            assertEquals(listOf(Part.Text("Hi")), Kit.model(server).complete(Kit.request()).parts)
        }

    /** The line reader before fix-list r11, which looked through the whole buffer on every read. */
    private class OldReader(
        pieces: List<ByteString>,
    ) {
        private val source: BufferedSource = Pieces(pieces).buffer()
        private var lfPending = false

        fun lines(): List<String>? {
            val lines = mutableListOf<String>()
            val buffer = source.buffer
            while (true) {
                takeBuffered(buffer, lines)
                if (lines.isNotEmpty()) return lines
                if (!source.request(buffer.size + 1)) return if (buffer.size > 0) listOf(buffer.readUtf8()) else null
            }
        }

        private fun takeBuffered(
            buffer: Buffer,
            lines: MutableList<String>,
        ) {
            while (true) {
                if (lfPending && buffer.size > 0) {
                    if (buffer[0] == '\n'.code.toByte()) buffer.skip(1)
                    lfPending = false
                }
                val end = buffer.indexOfElement("\r\n".encodeUtf8())
                if (end < 0) return
                lines += buffer.readUtf8(end)
                lfPending = buffer.readByte() == '\r'.code.toByte()
            }
        }

        fun calls(): List<List<String>> = buildList { while (true) add(lines() ?: break) }
    }

    private companion object {
        /** About what HttpTransport reads of an error body; a successful line has no limit. */
        const val LIMIT = 64 * 1024

        /** Line ends, the text between them, characters of more than one byte. */
        val PIECES =
            listOf("a", "data: x", ": c", "{}", " ", "用", "é") +
                listOf("\r", "\r", "\n", "\n", "\r\n", "\n\n", "\r\r")

        /** LFs right after a CR: the reader skips them without looking at them. */
        fun crlfs(text: String) = Regex("\r\n").findAll(text).count()

        const val SEED = 11
        const val CASES = 20_000
        const val MAX_PIECES = 12
        const val MAX_CHUNK = 5
    }
}
