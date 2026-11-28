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
    fun `short secrets are matched exactly too`() {
        val redactor = Redactor(listOf("test", "local"))
        assertEquals("key ***, *** run", redactor.text("key local, test run"))
        assertEquals("local".length - 1, redactor.reach)
        assertEquals("(***)", Redactor(listOf("1")).text("(1)"))
    }

    @Test
    fun `an identifier is kept whole or not at all`() {
        val redactor = Redactor(listOf("qwen"))
        assertEquals("llama3.2", redactor.verbatim("llama3.2"))
        assertEquals(null, redactor.verbatim("qwen3:8b"))
        assertEquals(null, redactor.verbatim("Bearer " + "abcdefghijkl"), "a shape is masked too")
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
    fun `provider text is quoted masked, or not at all when an escape hides a credential`() {
        val slashed = "placeholder/with-slash-00001"
        val redactor = Redactor(listOf(slashed))
        val backslash = Char(92)
        assertEquals("not json {", redactor.quote("not json {"), "text that holds nothing is kept")
        assertEquals("Bad Gateway", redactor.quote("Bad Gateway"))
        for (escape in listOf("$backslash/", "${backslash}u002F")) {
            val cut = """{"token":"pa-1","note":"placeholder${escape}with-slash-00001"""
            assertEquals(Redactor.WITHHELD, redactor.quote(cut), "a cut text")
            assertEquals(Redactor.WITHHELD, redactor.quote("$cut\"}}"), "a broken text")
            assertEquals(Redactor.WITHHELD, redactor.quote("$cut\"}"), "JSON written back")
        }
        assertEquals("not json *** {", redactor.quote("not json $slashed {"), "the secret as sent is masked in place")
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        val shaped = "s${backslash}u006b-" + "abcdefghijkl"
        assertEquals(Redactor.WITHHELD, Redactor.SHAPES_ONLY.quote("""{"note":"$shaped"""), "a credential shape")
    }

    @Test
    fun `exact masks only the secrets, and text is withheld only when decoding shows a credential`() {
        val slashed = "placeholder/with-slash-00001"
        val redactor = Redactor(listOf(slashed))
        val backslash = Char(92)
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        val shaped = "sk-" + "abcdefghijkl"
        assertEquals("key *** and $shaped", redactor.exact("key $slashed and $shaped"), "shapes are left to text")
        assertEquals("key *** and ***", redactor.text("key $slashed and $shaped"))
        assertEquals("bad key ***.", redactor.quote("bad key $slashed."), "the secret as sent is masked in place")
        val escaped = "placeholder${backslash}u002Fwith-slash-00001"
        val decodes = "bad key $slashed, see ${backslash}u2014"
        assertEquals("bad key ***, see ${backslash}u2014", redactor.quote(decodes), "masked before decoding")
        assertEquals("nothing here", redactor.quote("nothing here"))
        for (escape in listOf("$backslash/", "${backslash}u002F")) {
            val behind = "bad key placeholder${escape}with-slash-00001"
            assertEquals(Redactor.WITHHELD, redactor.quote(behind), "a secret behind an escape")
        }
        assertEquals(Redactor.WITHHELD, redactor.quote("Bearer $escaped"), "a bearer secret behind an escape")
        assertEquals("bad key ***", redactor.quote("bad key $shaped"), "a shape with nothing to decode")
        val hiddenShape = "bad key s${backslash}u006b-abcdefghijkl"
        assertEquals(Redactor.WITHHELD, redactor.quote(hiddenShape), "a shape behind an escape")
    }

    @Test
    fun `bearer masks only the token after it and never withholds the text`() {
        val redactor = Redactor(listOf("placeholder/with-slash-00001"))
        val backslash = Char(92)
        assertEquals("Invalid bearer ***", redactor.quote("Invalid bearer token"))
        assertEquals("Authorization: Bearer *** expected", redactor.quote("Authorization: Bearer YOUR_KEY expected"))
        val decodes = "Invalid bearer token ${backslash}u2014 check the header"
        assertEquals("Invalid bearer *** ${backslash}u2014 check the header", redactor.quote(decodes), "an escape")
        assertEquals(
            "upstream key *** rejected",
            Redactor.SHAPES_ONLY.quote("upstream key sk-" + "abcdefghijkl rejected"),
        )
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
