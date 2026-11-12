package dev.phoneagent.core.replay

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class CredentialsTest {
    /** A key with characters JSON escapes, so its three forms differ. */
    private val key = "k\"ey\\for-tests"
    private val once = JsonPrimitive(key).toString().removeSurrounding("\"")
    private val twice = JsonPrimitive(once).toString().removeSurrounding("\"")

    @Test
    fun `a key as written becomes its placeholder`() {
        assertEquals(Redacted("a {{credential:1}} b", mapOf(1 to 1)), Credentials.redact("a $key b", listOf(key)))
    }

    @Test
    fun `a key escaped once becomes its placeholder`() {
        assertEquals(
            Redacted("""{"t":"{{credential:1}}"}""", mapOf(1 to 1)),
            Credentials.redact("""{"t":"$once"}""", listOf(key)),
        )
    }

    @Test
    fun `a key escaped twice becomes its placeholder`() {
        assertEquals(
            Redacted("""{"args":"{{credential:1}}"}""", mapOf(1 to 1)),
            Credentials.redact("""{"args":"$twice"}""", listOf(key)),
        )
    }

    @Test
    fun `keys are numbered by their position and every occurrence counts`() {
        val redacted = Credentials.redact("x-key-one y-key-two x-key-one", listOf("x-key-one", "y-key-two"))
        assertEquals("{{credential:1}} {{credential:2}} {{credential:1}}", redacted.text)
        assertEquals(mapOf(1 to 2, 2 to 1), redacted.hits)
    }

    @Test
    fun `a key that is not there is no hit`() {
        assertEquals(Redacted("clean", emptyMap()), Credentials.redact("clean", listOf("x-key-one")))
    }

    @Test
    fun `a blank key is refused`() {
        assertThrows<IllegalArgumentException> { Credentials.redact("text", listOf(" ")) }
    }

    @Test
    fun `text shaped like an sk- key fails and names the file and line, not the text`() {
        val secretLike = "sk-" + "test0not0real0"
        val failure =
            assertThrows<IllegalArgumentException> {
                Credentials.checkShapes(
                    "f/session.jsonl",
                    "ok\nkey $secretLike\n",
                )
            }
        val message = failure.message.orEmpty()
        assertTrue("f/session.jsonl line 2" in message && "sk- key" in message, message)
        assertFalse(secretLike in message, message)
    }

    @Test
    fun `an sk- key right after a quote fails`() {
        val secretLike = "sk-" + "test0not0real0"
        assertThrows<IllegalArgumentException> { Credentials.checkShapes("f", "{\"key\":\"$secretLike\"}") }
    }

    @Test
    fun `an sk- key right after an equals sign fails`() {
        val secretLike = "sk-" + "test0not0real0"
        assertThrows<IllegalArgumentException> { Credentials.checkShapes("f", "api_key=$secretLike") }
    }

    @Test
    fun `words that end in sk before a dash are no key`() {
        assertDoesNotThrow { Credentials.checkShapes("f", "risk-assessment mask-sensitive-fields desk-booking\n") }
    }

    @Test
    fun `text shaped like a JWT fails`() {
        val jwtLike = listOf("eyJabcd", "efgh", "ijkl").joinToString(".")
        assertThrows<IllegalArgumentException> { Credentials.checkShapes("f", jwtLike) }
    }

    @Test
    fun `a bearer token fails`() {
        val bearer = "Authorization: bear" + "er " + "a".repeat(12)
        assertThrows<IllegalArgumentException> { Credentials.checkShapes("f", bearer) }
    }

    @Test
    fun `placeholders, short dashes and ordinary prose pass`() {
        assertDoesNotThrow { Credentials.checkShapes("f", "{{credential:1}} task-1 sk-abc a bearer of news\n") }
    }
}
