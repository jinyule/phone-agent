package dev.phoneagent.core.model.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class RedactorTest {
    private val secret = "plain-secret-not-real"

    @Test
    fun `every use of a request secret is masked, longest first`() {
        val redactor = Redactor(listOf("placeholder", "placeholder-long", "", "placeholder"))
        assertEquals("x *** y *** z", redactor.text("x placeholder-long y placeholder z"))
        assertEquals("a *** b ***", Redactor(listOf(secret)).text("a $secret b $secret"))
    }

    @Test
    fun `secrets shorter than eight characters are placeholders, matched only by shape`() {
        val redactor = Redactor(listOf("1", "test", "ollama", "tinkey", "1234567"))
        val text = "model ollama/llama3 on tinkey, test 1234567 #1"
        assertEquals(text, redactor.text(text))
        assertEquals(0, redactor.reach, "nothing to read past a cut for")
        assertEquals("*** and ***", Redactor(listOf("12345678")).text("12345678 and 12345678"))
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        assertEquals(
            "*** is still masked by its shape",
            redactor.text("sk-" + "test-abcdefgh is still masked by its shape"),
        )
    }

    @Test
    fun `credential shapes are masked without knowing the secret`() {
        // Assembled at run time so secret scanners do not take the placeholders for real tokens.
        val jwt = listOf("eyJ" + "notarealheader", "notarealpayload", "notasignature").joinToString(".")
        val masked =
            Redactor.SHAPES_ONLY.text(
                "saw sk-proj-abcdefgh123, header Authorization: bearer abc.def-ghi, jwt $jwt end",
            )
        assertEquals("saw ***, header Authorization: bearer ***, jwt *** end", masked)
    }

    @Test
    fun `codes, parameters and ordinary words survive`() {
        val text = "code invalid_api_key; risk-assessment-12345 at param input[1].encrypted_content (sk-short)"
        assertEquals(text, Redactor.SHAPES_ONLY.text(text))
    }

    @Test
    fun `masking twice changes nothing`() {
        val once = Redactor(listOf(secret)).text("Bearer $secret and sk-abcdefghijk")
        assertEquals(once, Redactor(listOf(secret)).text(once))
        assertEquals("Bearer *** and ***", once)
    }

    @Test
    fun `with adds a secret and ignores empty ones`() {
        val base = Redactor(listOf("first-secret"))
        assertSame(base, base.with(null))
        assertSame(base, base.with(""))
        assertEquals("*** ***", base.with("second-secret").text("first-secret second-secret"))
    }

    @Test
    fun `secrets are masked in their JSON escaping too`() {
        val quoted = "placeholder\"with-quote-0001"
        val backslashed = "placeholder\\with-backslash-01"
        val redactor = Redactor(listOf(quoted, backslashed))
        assertEquals(
            """{"message":"bad key ***","param":"***"} and *** / ***""",
            redactor.text(
                """{"message":"bad key placeholder\"with-quote-0001","param":"placeholder\\with-backslash-01"}""" +
                    " and $quoted / $backslashed",
            ),
        )
    }

    @Test
    fun `JSON is masked in its standard escaping, whatever escapes it came with`() {
        val slashed = "placeholder/with-slash-00001"
        val redactor = Redactor(listOf(slashed))
        // `\/` and `\u0070` hide the secret from a scan of the text; keys are masked too.
        assertEquals(
            """{"token":"***","***":[1,null,"***"]}""",
            redactor.json(
                """{"token":"placeholder\/with-slash-00001",""" +
                    """"placeholder\/with-slash-00001":[1,null,"\u0070laceholder/with-slash-00001"]}""",
            ),
        )
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        assertEquals("""{"token":"***"}""", Redactor.SHAPES_ONLY.json("{\"token\":\"\\u0073k-" + "abcdefghijkl\"}"))
        assertEquals("not json *** {", redactor.json("not json $slashed {"))
    }

    @Test
    fun `clean causes are kept as they are`() {
        val error = IOException("connection reset", IllegalStateException("inner"))
        assertSame(error, Redactor(listOf(secret)).cause(error))
    }

    @Test
    fun `causes that quote a secret are replaced by redacted stand-ins`() {
        val inner = IllegalStateException("inner saw $secret")
        val outer = IOException("outer", inner)
        val scrubbed = Redactor(listOf(secret)).cause(outer)
        val trace = scrubbed.stackTraceToString()
        assertFalse(trace.contains(secret), "the secret is not in the trace")
        assertEquals("java.io.IOException: outer", scrubbed.toString())
        assertEquals("java.lang.IllegalStateException: inner saw ***", scrubbed.cause.toString())
        assertTrue(scrubbed.stackTrace.contentEquals(outer.stackTrace), "stack traces are kept")
    }

    @Test
    fun `cyclic and deep cause chains end`() {
        val looped = LoopedException("loop $secret")
        val chain =
            (1..20).fold<Int, Throwable>(
                IOException("root $secret"),
            ) { cause, n -> IOException("level $n", cause) }
        assertFalse(Redactor(listOf(secret)).cause(looped).stackTraceToString().contains(secret))
        assertFalse(Redactor(listOf(secret)).cause(chain).stackTraceToString().contains(secret))
    }

    @Test
    fun `suppressed exceptions that quote a secret are dropped`() {
        val error = IOException("all routes failed").apply { addSuppressed(IOException("route saw $secret")) }
        val scrubbed = Redactor(listOf(secret)).cause(error)
        assertFalse(scrubbed.stackTraceToString().contains(secret))
        assertEquals("java.io.IOException: all routes failed", scrubbed.toString())
    }

    /** An exception that is its own cause. */
    private class LoopedException(
        message: String,
    ) : Exception(message) {
        override val cause: Throwable get() = this
    }
}
