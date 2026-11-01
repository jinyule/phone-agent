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
                val response = blocking(io, redactor) { call.execute().also(executed::set) }
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

    /** Up to [limit] bytes of the body as UTF-8 text (error bodies; HTML pages can be large). */
    suspend fun text(limit: Long = ERROR_BODY_LIMIT): String =
        blocking(io, redactor) {
            val source = response.body.source()
            source.request(limit)
            source.buffer.readUtf8(minOf(limit, source.buffer.size))
        }

    /**
     * The next lines of the body without terminators: blocks for one, then takes every complete
     * line already buffered. `null` at the end of the body.
     */
    suspend fun lines(): List<String>? =
        blocking(io, redactor) {
            val source = response.body.source()
            val first = source.readUtf8Line()
            first?.let {
                buildList {
                    add(it)
                    while (source.buffer.indexOf(NEWLINE) >= 0) add(source.readUtf8LineStrict())
                }
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
        const val ERROR_BODY_LIMIT = 64L * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}

/**
 * Keeps OkHttp from resending a 503 on its own: its follow-up logic retries a 503 whose
 * `Retry-After` is 0 whatever the client's retry settings say. As a network interceptor it moves
 * the header under [NAME] before that logic sees the response; [HttpReply.headers] moves it back,
 * so error mapping reads `Retry-After` as the server sent it.
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
        headers.values(from).forEach { builder.add(to, it) }
        return builder.build()
    }
}

/**
 * Runs blocking I/O on [io]. An `IOException` becomes [ModelError.Network], unless the caller was
 * cancelled meanwhile (the cancelled call is what failed the read), in which case cancellation
 * propagates. Exception text can quote the server (status lines), so it is masked with [redactor].
 */
private suspend fun <T> blocking(
    io: CoroutineDispatcher,
    redactor: Redactor,
    block: () -> T,
): T =
    try {
        withContext(io) { block() }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw ModelError.Network(redactor.text("${e::class.simpleName}: ${e.message}"), redactor.cause(e))
    }
