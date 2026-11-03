package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * WP01 A9: `context/reasoning-dropped` removes only the blocks whose kind `isReasoning`. The
 * blocks this adapter keeps are of the kinds that say so: thinking and redacted thinking are
 * reasoning, a compaction block holds history and is not, so after a drop it still goes back.
 */
class ReasoningDropTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    private suspend fun reasoningOf(fixture: String): List<Part.Reasoning> {
        server.enqueueSse(Kit.wireText("anthropic/$fixture.sse"))
        return Kit
            .model(server)
            .complete(Kit.request())
            .parts
            .filterIsInstance<Part.Reasoning>()
    }

    @Test
    fun `the blocks the adapter keeps are reasoning except a compaction block`() =
        runTest {
            val kinds =
                listOf("thinking-tool-fragments", "redacted-thinking", "compaction").flatMap { fixture ->
                    reasoningOf(fixture).map { it.kind }
                }
            assertEquals(
                listOf(
                    ReasoningKind.ANTHROPIC_THINKING,
                    ReasoningKind.ANTHROPIC_THINKING,
                    ReasoningKind.ANTHROPIC_REDACTED_THINKING,
                    ReasoningKind.ANTHROPIC_COMPACTION,
                ),
                kinds,
            )
            assertEquals(listOf(true, true, true, false), kinds.map { it.isReasoning })
        }

    @Test
    fun `after a reasoning drop the compaction block still goes back`() =
        runTest {
            val thinking = reasoningOf("thinking-tool-fragments").first()
            val compaction = reasoningOf("compaction").single()
            val history = listOf(thinking, compaction, Part.Text("继续。"))
            // What context/reasoning-dropped leaves of the reply (SessionEvent, model view).
            val dropped = history.filterNot { it is Part.Reasoning && it.kind.isReasoning }
            val request = Kit.request(Kit.user("巴黎天气怎么样？"), Message(Role.ASSISTANT, dropped), Kit.user("谢谢"))
            val rules = WireRules.of(request, CredentialKind.API_KEY)
            val body = MessagesWire(Kit.PROVIDER, Kit.MODEL).body(request, rules, ImageData.NONE)
            val assistant = (body["messages"] as JsonArray)[1] as JsonObject
            val types = (assistant["content"] as JsonArray).map { (it as JsonObject)["type"]?.jsonPrimitive?.content }
            assertEquals(listOf("compaction", "text"), types)
            assertEquals(compaction.opaque, (assistant["content"] as JsonArray)[0])
        }
}
