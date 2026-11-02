package dev.phoneagent.core.model.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parts of [Redactor] that deal with cuts: [Redactor.reach] and [Redactor.openEnd] for text
 * read with a limit, and [Redactor.Window] for text that arrives in pieces. Neither leaves a piece
 * of a secret behind.
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

    @Test
    fun `a secret split across pieces is masked, and what was let through adds up`() {
        val window = redactor.window()
        val shown =
            listOf("before ", secret.take(4), secret.drop(4).take(9), secret.drop(13) + " after")
                .joinToString("") { window.push(it) } + window.finish()
        assertEquals("before *** after", shown)
        assertTrue(window.found)
    }

    @Test
    fun `only a tail that could still become a secret is held back`() {
        val window = redactor.window()
        assertEquals("text ", window.push("text plain-"), "the start of the secret waits")
        assertFalse(window.found)
        assertEquals("plain-sky", window.push("sky"), "released once it cannot be the secret")
        assertEquals("x y", window.push("x y") + window.push(""), "nothing held back")
        assertEquals(" ", window.push(" p"), "a single letter can start the secret")
        assertEquals("", window.push("lain"))
        assertEquals("plain", window.finish(), "the end releases the rest")
        assertFalse(window.found)
    }

    @Test
    fun `overlapping and repeated secrets become one mask each run`() {
        val window = Redactor(listOf("abab")).window()
        assertEquals("x***y", window.push("xababab") + window.push("y") + window.finish())
        val two = Redactor(listOf("abc", "cde")).window()
        assertEquals("1***2***3", two.push("1abcde2") + two.push("abc3") + two.finish())
    }

    @Test
    fun `a JSON window also masks the escaped forms of a secret`() {
        val quoted = Redactor(listOf("a\"b/c\\d"))
        val window = quoted.window(json = true)
        val json = """{"k":"a\"b/c\\d","l":"a\"b\/c\\d"}"""
        assertEquals(
            """{"k":"***","l":"***"}""",
            window.push(json.take(9)) + window.push(json.drop(9)) + window.finish(),
        )
        assertEquals("""{"k":"a\"b/c\\d"}""", quoted.window().run { push("""{"k":"a\"b/c\\d"}""") + finish() })
    }

    @Test
    fun `without secrets a window passes everything through at once`() {
        val window = Redactor.SHAPES_ONLY.window()
        assertEquals("sk-abcdefghijk", window.push("sk-abcdefghijk"))
        assertEquals("", window.finish())
        assertFalse(window.found)
    }

    @Test
    fun `echoes finds only the request's own secrets`() {
        assertTrue(redactor.echoes("x${secret}y"))
        assertFalse(redactor.echoes("x" + secret.dropLast(1)))
        assertFalse(Redactor.SHAPES_ONLY.echoes("sk-abcdefghijk"))
    }
}
