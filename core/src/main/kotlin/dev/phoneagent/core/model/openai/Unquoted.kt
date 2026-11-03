package dev.phoneagent.core.model.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Words for what diagnostics do not quote (model-layer §2): reply content and the text of I/O
 * failures. An error message, its cause chain or a [ProbeResult] names the field, item, event or
 * step that was wrong and the kind and length of the content, never the content: an escape in raw
 * text no parser has read (a `u` escape, `\/`) hides a secret from masking.
 */
internal object Unquoted {
    /** `N characters`. */
    fun size(text: String): String = "${text.length} characters"

    /** What a JSON value is: `a string of N characters`, `a number`, `a boolean`, `an object`, `an array`, `null`. */
    fun shape(value: JsonElement): String =
        when (value) {
            JsonNull -> "null"
            is JsonPrimitive -> if (value.isString) "a string of ${size(value.content)}" else scalar(value)
            is JsonObject -> "an object"
            is JsonArray -> "an array"
        }

    private fun scalar(value: JsonPrimitive): String = if (value.booleanOrNull != null) "a boolean" else "a number"

    /** [value] itself when it is one of [known] (words the adapters expect), else its [shape]. */
    fun named(
        value: JsonElement,
        known: Set<String>,
    ): String = (value as? JsonPrimitive)?.takeIf { it.isString && it.content in known }?.content ?: shape(value)

    /** [value] itself when it is one of [known], else its length. */
    fun named(
        value: String,
        known: Set<String>,
    ): String = if (value in known) value else "a string of ${size(value)}"

    /**
     * What an I/O failure was, by its type and class name, never by its message: OkHttp's quote
     * the server (a status line it cannot read; a header line it cannot end, in hex).
     */
    fun io(error: IOException): String {
        val what =
            when (error) {
                is InterruptedIOException -> "timed out"
                is UnknownHostException -> "the host name did not resolve"
                is ConnectException, is NoRouteToHostException -> "could not connect"
                is SSLException -> "TLS failed"
                is ProtocolException -> "the server broke the HTTP protocol"
                is EOFException -> "the connection ended early"
                is SocketException -> "the connection failed"
                else -> "I/O failed"
            }
        return "$what (${error::class.simpleName})"
    }

    /** A stand-in for [error] and the first [MAX_DEPTH] of its causes: class names and stack traces, no messages. */
    fun bare(error: Throwable): Throwable = Bare(error, 0)

    private class Bare(
        original: Throwable,
        depth: Int,
    ) : Exception(
            original::class.java.name,
            original.cause?.takeIf { it !== original && depth + 1 < MAX_DEPTH }?.let { Bare(it, depth + 1) },
        ) {
        init {
            stackTrace = original.stackTrace
        }

        override fun toString(): String = message.orEmpty()
    }

    private const val MAX_DEPTH = 8
}
