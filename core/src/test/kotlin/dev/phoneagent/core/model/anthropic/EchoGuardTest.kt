package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** [EchoGuard] matches each request secret exactly, as sent and JSON-escaped, however it is split. */
class EchoGuardTest {
    private val secret = "abc\"def"
    private val guard = Redactor(listOf(secret)).guard()

    private fun rejected(block: () -> Unit) {
        val error = assertThrows<ModelError.BadResponse> { block() }
        assertTrue(CredentialEcho.discardsStreamed(error), "the streamed output is not kept")
    }

    @Test
    fun `a window rejects the fragment that completes a secret, wherever the cuts fall`() {
        for (form in listOf(secret, "abc\\\"def")) {
            val text = "x${form}y"
            for (first in 1 until text.length) {
                for (second in first + 1 until text.length) {
                    val window = guard.Window()
                    val pieces = listOf(text.take(first), text.substring(first, second), text.drop(second))
                    val passed = pieces.takeWhile { piece -> runCatching { window.next(piece) }.isSuccess }
                    val completing = pieces.runningReduce(String::plus).indexOfFirst { it.length > form.length }
                    assertEquals(completing, passed.size, "$pieces")
                }
            }
        }
    }

    @Test
    fun `a window lets text through that only begins a secret`() {
        val window = guard.Window()
        assertDoesNotThrow {
            window.next("abc")
            window.next("\"de")
            window.next("x abc")
        }
    }

    @Test
    fun `secrets of any length match, credential shapes do not`() {
        val short = Redactor(listOf("ab")).guard()
        rejected { short.check("xaby") }
        assertDoesNotThrow { short.check("a b") }
        assertDoesNotThrow { guard.check("sk-ant-api03-not-a-request-secret eyJhbGciOiJIUzI1NiJ9.e30.x") }
        assertDoesNotThrow { Redactor.SHAPES_ONLY.guard().check("anything") }
    }

    @Test
    fun `JSON is checked in every key and string, and arguments once more as parsed`() {
        rejected { guard.json(Json.parseToJsonElement("""{"k":["x","abc\"def"]}""")) }
        rejected { guard.json(Json.parseToJsonElement("""{"abc\"def":1}""")) }
        assertDoesNotThrow { guard.json(Json.parseToJsonElement("""{"k":[1,true,null,"abc"]}""")) }
        rejected { guard.arguments("""{"k":"abc\"def"}""") }
        assertDoesNotThrow { guard.arguments("""{"k":"abc""") }
    }

    @Test
    fun `only the fixed echo failure says its streamed output is not kept`() {
        assertEquals("bad response: ${CredentialEcho.DETAIL}", CredentialEcho.error().message)
        assertTrue(CredentialEcho.discardsStreamed(CredentialEcho.error()))
        assertFalse(CredentialEcho.discardsStreamed(ModelError.BadResponse("other")))
        assertFalse(CredentialEcho.discardsStreamed(ModelError.Network(CredentialEcho.DETAIL)))
    }
}
