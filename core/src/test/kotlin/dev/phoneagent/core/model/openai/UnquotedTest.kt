package dev.phoneagent.core.model.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** [Unquoted] describes content and failures by kind and length; no text of theirs comes through. */
class UnquotedTest {
    @Test
    fun `JSON values are described by their kind`() {
        assertEquals("a string of 3 characters", Unquoted.shape(JsonPrimitive("abc")))
        assertEquals("a number", Unquoted.shape(JsonPrimitive(7)))
        assertEquals("a boolean", Unquoted.shape(JsonPrimitive(true)))
        assertEquals("null", Unquoted.shape(JsonNull))
        assertEquals("an object", Unquoted.shape(JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertEquals("an array", Unquoted.shape(JsonArray(listOf(JsonPrimitive(1)))))
        assertEquals("4 characters", Unquoted.size("abcd"))
    }

    @Test
    fun `only known words are named`() {
        val known = setOf("failed")
        assertEquals("failed", Unquoted.named(JsonPrimitive("failed"), known))
        assertEquals("a string of 6 characters", Unquoted.named(JsonPrimitive("other1"), known))
        assertEquals("a number", Unquoted.named(JsonPrimitive(7), known))
        assertEquals("failed", Unquoted.named("failed", known))
        assertEquals("a string of 6 characters", Unquoted.named("other1", known))
    }

    @Test
    fun `an I-O failure is named by its type, not its message`() {
        val cases =
            listOf(
                SocketTimeoutException(QUOTE) to "timed out (SocketTimeoutException)",
                InterruptedIOException(QUOTE) to "timed out (InterruptedIOException)",
                UnknownHostException(QUOTE) to "the host name did not resolve (UnknownHostException)",
                ConnectException(QUOTE) to "could not connect (ConnectException)",
                NoRouteToHostException(QUOTE) to "could not connect (NoRouteToHostException)",
                SSLHandshakeException(QUOTE) to "TLS failed (SSLHandshakeException)",
                ProtocolException(QUOTE) to "the server broke the HTTP protocol (ProtocolException)",
                EOFException(QUOTE) to "the connection ended early (EOFException)",
                SocketException(QUOTE) to "the connection failed (SocketException)",
                IOException(QUOTE) to "I/O failed (IOException)",
            )
        for ((error, words) in cases) assertEquals(words, Unquoted.io(error))
    }

    @Test
    fun `a bare stand-in keeps class names and stack traces, no messages`() {
        val root = IllegalStateException(QUOTE)
        val error = IOException(QUOTE, root)
        val bare = Unquoted.bare(error)
        assertEquals("java.io.IOException", bare.message)
        assertEquals("java.io.IOException", bare.toString())
        assertArrayEquals(error.stackTrace, bare.stackTrace)
        assertEquals("java.lang.IllegalStateException", bare.cause?.message)
        assertArrayEquals(root.stackTrace, bare.cause?.stackTrace)
        assertNull(bare.cause?.cause)
    }

    @Test
    fun `cause chains that loop or run deep end`() {
        val looping = LoopingError()
        assertNull(Unquoted.bare(looping).cause, "a cause that is the error itself is not followed")
        var deep: Throwable = IOException(QUOTE)
        repeat(DEPTH) { deep = IOException(QUOTE, deep) }
        var links = 0
        var next: Throwable? = Unquoted.bare(deep)
        while (next != null) {
            links++
            next = next.cause
        }
        assertEquals(MAX_DEPTH, links, "the first causes only")
    }

    private class LoopingError : Exception(QUOTE) {
        override val cause: Throwable get() = this
    }

    private companion object {
        const val QUOTE = "Unexpected status line: what the server sent"
        const val DEPTH = 20
        const val MAX_DEPTH = 8
    }
}
