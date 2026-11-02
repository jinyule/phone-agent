package dev.phoneagent.core.model.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The parts of [Redactor] that deal with cuts: [Redactor.reach] and [Redactor.openEnd] for text
 * read with a limit. A cut never leaves a piece of a secret behind.
 */
class RedactorCutTest {
    private val secret = "plain-secret-not-real"
    private val redactor = Redactor(listOf(secret))

    @Test
    fun `the reach is the longest secret form in bytes, less one`() {
        assertEquals(secret.length - 1, redactor.reach)
        assertEquals(0, Redactor.SHAPES_ONLY.reach)
        assertEquals("a\\\"b".length - 1, Redactor(listOf("a\"b", "x")).reach, "the JSON-escaped form counts")
    }

    @Test
    fun `secrets are masked as sent and JSON-escaped`() {
        assertEquals("""{"m":"bad ***"}""", Redactor(listOf("a\"b")).text("""{"m":"bad a\"b"}"""))
        assertEquals("bad ***", Redactor(listOf("a\"b")).text("bad a\"b"))
    }

    @Test
    fun `the open end of a cut read is what may begin a secret or a shape`() {
        assertEquals(6, redactor.openEnd("text plain-"), "the start of the secret")
        assertEquals(0, redactor.openEnd("text plain-x"), "not the secret any more")
        assertEquals(0, redactor.openEnd("text ***"), "a masked secret is closed")
        assertEquals(5, Redactor.SHAPES_ONLY.openEnd("key sk-ab"), "a key cut before its shape")
        assertEquals(9, Redactor.SHAPES_ONLY.openEnd("x eyJhb.cDe"), "a JWT cut before its shape")
        assertEquals(0, Redactor.SHAPES_ONLY.openEnd("risk-ab"), "no word boundary, no key")
    }
}
