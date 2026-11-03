package dev.phoneagent.core.model.anthropic

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
 * Words for what diagnostics do not quote (model-layer §2): the text of I/O failures. OkHttp's
 * messages quote what the server sent (a line it read as a chunk size or a status line; the bytes
 * of a line the stream cut, in hex), and a secret written there with a `u` escape or `\/` is not
 * one masking finds; so an error says what failed by its type and step, and its cause keeps class
 * names and stack traces only.
 *
 * A copy of the OpenAI adapters' `Unquoted` (WP03; capabilities may not share code), which names
 * the step as well; keep the two in step.
 */
internal object Unquoted {
    /** What [error] was, in [step] (`reading the reply`), by its type and class name, never by its message. */
    fun io(
        error: IOException,
        step: String,
    ): String {
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
        return "$what while $step (${error::class.simpleName})"
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
