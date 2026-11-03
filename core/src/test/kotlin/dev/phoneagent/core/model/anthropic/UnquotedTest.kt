package dev.phoneagent.core.model.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** [Unquoted] describes values and I/O failures by kind, never by their text. */
class UnquotedTest {
    @Test
    fun `a JSON value is described by its kind and a string by its length`() {
        assertEquals("null", Unquoted.shape(JsonNull))
        assertEquals("a string of 3 characters", Unquoted.shape(JsonPrimitive("abc")))
        assertEquals("a number", Unquoted.shape(JsonPrimitive(12)))
        assertEquals("a boolean", Unquoted.shape(JsonPrimitive(true)))
        assertEquals("an object", Unquoted.shape(JsonObject(mapOf("k" to JsonPrimitive("secret")))))
        assertEquals("an array", Unquoted.shape(JsonArray(listOf(JsonPrimitive("secret")))))
    }

    @Test
    fun `a known name is shown and any other by its length`() {
        assertEquals("text", Unquoted.named("text", setOf("text")))
        assertEquals("a string of 6 characters", Unquoted.named("secret", setOf("text")))
    }

    @Test
    fun `an I-O failure is described by its type, class and step, never by its message`() {
        val cases =
            listOf(
                SocketTimeoutException(SECRET) to "timed out",
                UnknownHostException(SECRET) to "the host name did not resolve",
                ConnectException(SECRET) to "could not connect",
                NoRouteToHostException(SECRET) to "could not connect",
                SSLHandshakeException(SECRET) to "TLS failed",
                ProtocolException(SECRET) to "the server broke the HTTP protocol",
                EOFException(SECRET) to "the connection ended early",
                SocketException(SECRET) to "the connection failed",
                IOException(SECRET) to "I/O failed",
            )
        cases.forEach { (error, what) ->
            assertEquals(
                "$what while reading the reply (${error::class.simpleName})",
                Unquoted.io(error, "reading the reply"),
            )
        }
    }

    @Test
    fun `a bare stand-in keeps class names and stack traces of the causes, no messages`() {
        val inner = IOException(SECRET)
        val outer = ProtocolException(SECRET).apply { initCause(inner) }
        val bare = Unquoted.bare(outer)
        assertEquals(ProtocolException::class.java.name, bare.message)
        assertEquals(ProtocolException::class.java.name, bare.toString())
        assertEquals(outer.stackTrace.toList(), bare.stackTrace.toList())
        assertEquals(IOException::class.java.name, bare.cause?.message)
        assertNull(bare.cause?.cause)
        var chain: Throwable = IOException(SECRET)
        repeat(LONG_CHAIN) { chain = IOException(SECRET, chain) }
        var depth = 0
        var at: Throwable? = Unquoted.bare(chain)
        while (at != null) {
            depth++
            at = at.cause
        }
        assertEquals(MAX_DEPTH, depth, "a long chain is cut")
    }

    private companion object {
        const val SECRET = "secret-in-the-message"
        const val LONG_CHAIN = 20
        const val MAX_DEPTH = 8
    }
}
