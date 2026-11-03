package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Blocking OkHttp calls behind a suspend API. Blocking reads run on [io]; results come back to the
 * calling coroutine, so stream handlers (and `onDelta`) run there. Cancelling the caller cancels
 * the HTTP call, which aborts a read in progress. Transport failures become [ModelError.Network]
 * (their text masked by the request's [Redactor]); `CancellationException` always wins over the
 * `IOException` a cancelled call produces.
 */
internal class HttpTransport(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
) {
    /**
     * Sends [request] and passes the open response to [read]; the response is closed afterwards.
     * Ownership of the response cannot get lost: the I/O block stores it before returning, and
     * unless [read] finishes normally (cancellation, including one that lands after `execute()`
     * returned but before the caller resumed, or any failure) the call is cancelled and the stored
     * response closed, so its connection goes back to the pool.
     */
    suspend fun <T> send(
        request: Request,
        redactor: Redactor,
        read: suspend (HttpReply) -> T,
    ): T {
        val call = client.newCall(request)
        val executed = AtomicReference<Response?>()
        val finished = AtomicBoolean(false)
        return coroutineScope {
            // Interrupts a blocking execute() or read when the caller is cancelled meanwhile.
            val canceller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) call.cancel()
                    }
                }
            try {
                val response = blocking(io) { call.execute().also(executed::set) }
                response.use { read(HttpReply(it, io, redactor)) }.also { finished.set(true) }
            } finally {
                if (!finished.get()) {
                    call.cancel()
                    executed.get()?.close()
                }
                canceller.cancel()
            }
        }
    }
}

/** An open HTTP response. Reads suspend the caller and block a thread of the I/O dispatcher. */
internal class HttpReply(
    private val response: Response,
    private val io: CoroutineDispatcher,
    private val redactor: Redactor,
) {
    val status: Int get() = response.code
    val headers: Headers get() = RetryAfterStash.restore(response.headers)
    val successful: Boolean get() = response.isSuccessful

    /** Up to [limit] bytes of the body as UTF-8 text, as sent (a listing to parse). */
    suspend fun text(limit: Long): String =
        blocking(io) {
            val source = response.body.source()
            source.request(limit)
            source.buffer.readUtf8(minOf(limit, source.buffer.size))
        }

    /**
     * Up to [limit] characters of an error body (HTML pages can be large) as UTF-8 text masked with
     * the request's [Redactor], which masks before it cuts: [Redactor.reach] bytes past [limit] are
     * read as well, so a secret that straddles the limit is masked whole. When the body goes on
     * past what was read, the code point the read split and the [Redactor.openEnd] are dropped
     * before the cut. The cut never splits a surrogate pair.
     */
    suspend fun maskedText(limit: Int = ERROR_BODY_LIMIT): String =
        blocking(io) {
            val source = response.body.source()
            val wanted = limit.toLong() + redactor.reach
            val more = source.request(wanted + 1)
            val end = if (more) codePointStart(source.buffer, wanted) else source.buffer.size
            val masked = redactor.text(source.buffer.readUtf8(end))
            cut(if (more) masked.dropLast(redactor.openEnd(masked)) else masked, limit)
        }

    /** A line ended by CR whose LF (if any) has not been read yet. */
    private var lfPending = false

    /**
     * The next lines of the body without terminators: blocks for one, then takes every complete
     * line already buffered. LF, CRLF and a lone CR each end a line (WHATWG SSE), also when a
     * CRLF is split between two reads. A last line without terminator still counts. `null` at the
     * end of the body.
     */
    suspend fun lines(): List<String>? = blocking(io) { readLines(response.body.source()) }

    private fun readLines(source: BufferedSource): List<String>? {
        val lines = mutableListOf<String>()
        val buffer = source.buffer
        while (true) {
            takeBuffered(buffer, lines)
            if (lines.isNotEmpty()) return lines
            // Blocks for at least one more byte; false = the body ended.
            if (!source.request(buffer.size + 1)) return if (buffer.size > 0) listOf(buffer.readUtf8()) else null
        }
    }

    /** Moves every complete line in [buffer] to [lines]. */
    private fun takeBuffered(
        buffer: Buffer,
        lines: MutableList<String>,
    ) {
        while (true) {
            if (lfPending && buffer.size > 0) {
                if (buffer[0] == LF) buffer.skip(1)
                lfPending = false
            }
            val end = buffer.indexOfElement(TERMINATORS)
            if (end < 0) return
            lines += buffer.readUtf8(end)
            lfPending = buffer.readByte() == CR
        }
    }

    /**
     * Reads a successful body to the end, or until [onEvent] returns `true` (the protocol's
     * terminal event). A body whose first non-blank line starts with `{` is a whole JSON reply
     * (servers that ignore `stream:true`) and goes to [onJson] instead, whatever the content type.
     */
    suspend fun readEvents(
        onEvent: (SseEvent) -> Boolean,
        onJson: (String) -> Unit,
    ) {
        val parser = SseParser()
        var json: StringBuilder? = null
        var started = false
        while (true) {
            val lines = lines() ?: break
            for (line in lines) {
                if (!started && line.isBlank()) continue
                if (!started && line.trimStart().startsWith("{")) json = StringBuilder()
                started = true
                if (json != null) {
                    json.append(line).append('\n')
                } else {
                    val event = parser.line(line)
                    if (event != null && onEvent(event)) return
                }
            }
        }
        if (json != null) onJson(json.toString()) else parser.finish()?.let(onEvent)
    }

    private companion object {
        const val ERROR_BODY_LIMIT = 64 * 1024
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}

/** Bytes that end a line. */
private val TERMINATORS = "\r\n".encodeUtf8()

/**
 * Where a read of [buffer] that stops at byte [end] must stop so it splits no code point: [end],
 * or the start of the code point that byte [end] continues.
 */
private fun codePointStart(
    buffer: Buffer,
    end: Long,
): Long {
    var start = end
    while (start > 0 && end - start < MAX_CONTINUATIONS && continues(buffer[start])) start--
    return start
}

/** Whether [byte] continues a UTF-8 code point (`10xxxxxx`). */
private fun continues(byte: Byte): Boolean = (byte.toInt() and UTF8_TOP_BITS) == UTF8_CONTINUATION

/** A UTF-8 code point has at most three continuation bytes. */
private const val MAX_CONTINUATIONS = 3
private const val UTF8_TOP_BITS = 0xC0
private const val UTF8_CONTINUATION = 0x80

/** The first [limit] characters of [text], one fewer when the last of them would be half a surrogate pair. */
private fun cut(
    text: String,
    limit: Int,
): String =
    when {
        text.length <= limit -> text
        text[limit - 1].isHighSurrogate() -> text.take(limit - 1)
        else -> text.take(limit)
    }

/**
 * Keeps OkHttp from resending a 503 on its own: its follow-up logic retries a 503 whose
 * `Retry-After` is 0 whatever the client's retry settings say. As a network interceptor it moves
 * the header under [NAME] before that logic sees the response; [HttpReply.headers] moves it back,
 * so error mapping reads `Retry-After` as the server sent it. Values move unchecked: one HTTP does
 * not allow (non-ASCII) stays as it is, and error mapping finds no wait in it.
 */
internal object RetryAfterStash : Interceptor {
    const val NAME: String = "PhoneAgent-Retry-After"
    private const val RETRY_AFTER = "Retry-After"

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        return if (response.header(RETRY_AFTER) == null) {
            response
        } else {
            response.newBuilder().headers(moved(response.headers, RETRY_AFTER, NAME)).build()
        }
    }

    /** [headers] with the stashed value back under `Retry-After`. */
    fun restore(headers: Headers): Headers = if (headers[NAME] == null) headers else moved(headers, NAME, RETRY_AFTER)

    private fun moved(
        headers: Headers,
        from: String,
        to: String,
    ): Headers {
        val builder = headers.newBuilder().removeAll(from)
        // Not add(): its check of the value throws with the value quoted, and the value is the server's.
        headers.values(from).forEach { builder.addUnsafeNonAscii(to, it) }
        return builder.build()
    }
}

/**
 * Runs blocking I/O on [io]. An `IOException` becomes [ModelError.Network], unless the caller was
 * cancelled meanwhile (the cancelled call is what failed the read), in which case cancellation
 * propagates. Exception text can quote the server (status lines, header lines in hex), so the
 * error says what failed by its type ([Unquoted.io]) and its cause keeps class names and stack
 * traces only. An `IllegalArgumentException` (OkHttp rejecting a value the server sent) becomes
 * [ModelError.BadResponse] with fixed text and no cause; [HttpTransport.send] then cancels the call
 * and closes the response it holds.
 */
private suspend fun <T> blocking(
    io: CoroutineDispatcher,
    block: () -> T,
): T =
    try {
        withContext(io) { block() }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw ModelError.Network(Unquoted.io(e), Unquoted.bare(e))
    } catch (ignoredQuotingValidation: IllegalArgumentException) {
        // OkHttp's validation errors quote the rejected value whole, and the value is the server's.
        throw ModelError.BadResponse(INVALID_HTTP)
    }

/** What a response OkHttp rejected fails with. */
private const val INVALID_HTTP = "the server sent a response HTTP does not allow"
