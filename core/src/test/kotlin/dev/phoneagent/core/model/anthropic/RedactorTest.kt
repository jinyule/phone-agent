package dev.phoneagent.core.model.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.IOException

class RedactorTest {
    private val secret = "plain-secret-not-real"

    @Test
    fun `every use of a request secret is masked, longest first`() {
        val redactor = Redactor(listOf("abc", "abcdef", "", "abc"))
        assertEquals("x *** y *** z", redactor.text("x abcdef y abc z"))
        assertEquals("a *** b ***", Redactor(listOf(secret)).text("a $secret b $secret"))
    }

    @Test
    fun `credential shapes are masked without knowing the secret`() {
        // Assembled at run time so secret scanners do not take the placeholders for real tokens.
        val jwt = listOf("eyJ" + "notarealheader", "notarealpayload", "notasignature").joinToString(".")
        val masked =
            Redactor.SHAPES_ONLY.text(
                "saw sk-proj-abcdefgh123, header Authorization: bearer abc.def-ghi, jwt $jwt end, " +
                    "x-api-key " + "sk-ant-" + "api03-notarealkey_123",
            )
        assertEquals("saw ***, header Authorization: bearer ***, jwt *** end, x-api-key ***", masked)
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

    /** The OpenAI adapters' cases (WP03), so the two copies of [Redactor.quote] are held to one rule. */
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
        assertEquals("not json *** {", redactor.quote("not json $slashed {"), "the secret as sent, no escape")
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        val shaped = "s${backslash}u006b-" + "abcdefghijkl"
        assertEquals(Redactor.WITHHELD, Redactor.SHAPES_ONLY.quote("""{"note":"$shaped"""), "a credential shape")
    }

    /**
     * The rule both adapters keep (fix-list r6 S2): text its lenient decoding leaves as it is is
     * masked in place; text with escapes is withheld only when its decoding shows a form of the
     * request's secrets or a credential shape. The Bearer rule masks the token after the word and
     * never withholds.
     */
    @Test
    fun `a Bearer word never withholds, and text without escapes is masked in place`() {
        val redactor = Redactor(listOf(secret))
        val backslash = Char(92)
        val missing = "No API key. Use Bearer auth (i.e. Authorization: Bearer YOUR_KEY), or log in."
        // Assembled at run time so secret scanners do not take the placeholders for real keys.
        val upstream = "s" + "k-proj-" + "abcdefghijklmnop1234"
        val split = "s" + "k-abcd" + "${backslash}u0065" + "fghijkl"
        val late = "s" + "k-proj-abcdefgh" + "${backslash}u0069" + "jklmnop"
        val rewritten = """{"detail":"send $backslash"Bearer YOUR_KEY$backslash""}"""
        val hidden = "${backslash}u0070" + secret.drop(1)
        assertAll(
            { assertEquals("Invalid bearer ***", redactor.field("Invalid bearer token")) },
            {
                val expected = "No API key. Use Bearer *** (i.e. Authorization: Bearer ***, or log in."
                assertEquals(expected, redactor.field(missing))
            },
            { assertEquals("upstream key *** rejected", redactor.field("upstream key $upstream rejected")) },
            {
                val expected = """{"detail":"send $backslash"Bearer ***""}"""
                assertEquals(expected, redactor.quote(rewritten), "escapes and a Bearer word, nothing else")
            },
            { assertEquals(Redactor.WITHHELD, redactor.field("Invalid bearer $hidden"), "the key behind an escape") },
            { assertEquals(Redactor.WITHHELD, Redactor.SHAPES_ONLY.field("Invalid API key: $split"), "a split shape") },
            { assertEquals(Redactor.WITHHELD, Redactor.SHAPES_ONLY.field("Invalid API key: $late"), "split late") },
        )
    }

    @Test
    fun `exact masks the request's secrets and leaves credential shapes for after decoding`() {
        val redactor = Redactor(listOf(secret))
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        val shaped = "s" + "k-abcdefghijklmnop"
        assertEquals("*** and $shaped and Bearer x", redactor.exact("$secret and $shaped and Bearer x"))
        assertEquals("*** and *** and Bearer ***", redactor.text("$secret and $shaped and Bearer x"))
    }

    @Test
    fun `a decoded field is masked, or withheld when its own escapes hide a credential`() {
        val slashed = "placeholder/with-slash-00001"
        val redactor = Redactor(listOf(slashed))
        val backslash = Char(92)
        assertEquals("invalid x-api-key", redactor.field("invalid x-api-key"), "a field that holds nothing is kept")
        assertEquals("invalid x-api-key: ***", redactor.field("invalid x-api-key: $slashed"), "a plain key is masked")
        for (escape in listOf("$backslash/", "${backslash}u002F")) {
            val layered = "invalid x-api-key: placeholder${escape}with-slash-00001"
            assertEquals(Redactor.WITHHELD, redactor.field(layered), "a key behind the field's own escapes")
        }
        // Assembled at run time so secret scanners do not take the placeholder for a real key.
        val shaped = "s${backslash}u006b-" + "abcdefghijkl"
        assertEquals(Redactor.WITHHELD, Redactor.SHAPES_ONLY.field("Invalid API key: $shaped"), "a credential shape")
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
