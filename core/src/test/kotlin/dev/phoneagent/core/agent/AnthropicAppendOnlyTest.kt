@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.anthropic.ImageData
import dev.phoneagent.core.model.anthropic.Kit
import dev.phoneagent.core.model.anthropic.MessagesWire
import dev.phoneagent.core.model.anthropic.WireRules
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.CompactionPrune
import dev.phoneagent.core.session.CompactionSummary
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Anthropic path is append-only on the wire (AGENTS §3 rule 7, model-layer §6): every request
 * the loop sends, mapped to its Messages body without `cache_control` (the API's prefix check
 * ignores it), keeps `system` and `tools` of the request before it, and that request's message
 * blocks are a prefix of its own. The one exception is the request after a
 * `context/reasoning-dropped`: the earlier request's blocks without its `thinking` and
 * `redacted_thinking` blocks are a prefix of it, so text, tool use, tool results and compaction
 * blocks, their roles and their order still stand. The log of the run holds what a projection
 * could break the prefix with: runtime context, a compaction block, a prune and a summary written
 * with Replace, a prefix cut by a steer and one superseded by a retry, and a reasoning drop. The
 * interrupted prefixes here hold text only; how one filters opaque and compaction blocks is the
 * rule sample `interrupted-compaction-left-out` (DeriveRuleSamples), not this test.
 */
class AnthropicAppendOnlyTest {
    private val wire = MessagesWire(FakeChatModel.INFO.provider, FakeChatModel.INFO.model)

    private fun block(
        kind: ReasoningKind,
        vararg fields: Pair<String, String>,
    ) = Part.Reasoning(
        FakeChatModel.INFO.provider,
        FakeChatModel.INFO.model,
        kind,
        buildJsonObject { fields.forEach { (name, value) -> put(name, value) } },
    )

    private fun thinking(text: String) =
        block(ReasoningKind.ANTHROPIC_THINKING, "type" to "thinking", "thinking" to text, "signature" to "sig-$text")

    private val compaction = block(ReasoningKind.ANTHROPIC_COMPACTION, "type" to "compaction", "content" to "早先的步骤")

    /** Runtime context per turn, and in turn 2 a prune of the first tool result and a summary of turn 1. */
    private val context =
        Interceptor<PreStepInput, PreStepResult> { input, chain ->
            input.log.appendRuntimeContext(listOf(Part.Text("前台：设置 · 第 ${input.turn} 轮")))
            if (input.turn == 2 && input.step == 1) {
                val records = input.log.records()
                val result = records.first { it.event is ToolResultRecorded }
                input.log.append(
                    CompactionPrune(listOf(Part.Text("[旧结果已省略]"))),
                    SurfaceOp.Replace(result.seq, result.seq),
                )
                val turnOne = records.filter { it.event is UserMessage || it.event is AssistantMessage }
                input.log.append(
                    CompactionSummary(listOf(Part.Text("用户要打开设置"))),
                    SurfaceOp.Replace(turnOne.first().seq, turnOne.last().seq),
                )
            }
            chain.proceed(input)
        }

    @Test
    fun `adjacent requests share system and tools and extend the message blocks, a drop removes only thinking`() =
        runTest {
            val model =
                FakeChatModel(wire = Kit.wire(reasoning = null))
                    .replies(
                        ChatResponse(
                            listOf(compaction, thinking("A"), Part.Text("看一下"), call("c1", "look")),
                            StopReason.TOOL_USE,
                        ),
                    ).hangs(delivered = "正在")
                    .replies(ChatResponse(listOf(thinking("B"), Part.Text("好的")), StopReason.END_TURN))
                    .fails(ModelError.Server(503, "busy"), delivered = "我先")
                    .replies(ChatResponse(listOf(thinking("C"), call("c2", "look")), StopReason.TOOL_USE))
                    .fails(ModelError.PrefixBindingMismatch("signature no longer valid"))
                    .answers("好了")
            lateinit var harness: LoopHarness
            // How many reasoning drops the log held when each request was sent (the log is flushed by then).
            val drops = mutableListOf<Int>()
            val counting = { sent: ChatModel ->
                object : ChatModel by sent {
                    override suspend fun complete(
                        request: ChatRequest,
                        onDelta: (Delta) -> Unit,
                    ): ChatResponse {
                        drops += harness.records().count { it.event is ReasoningDropped }
                        return sent.complete(request, onDelta)
                    }
                }
            }
            harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(FakeTool("look")),
                    interceptors = Interceptors(preStep = listOf(context)),
                    observe = counting,
                )
            val loop = harness.start()
            loop.followup(text("打开设置"))
            model.awaitStarted(2)
            runCurrent()
            loop.steer(text("换个说法"))
            advanceUntilIdle()
            loop.followup(text("再看看"))
            advanceUntilIdle()
            model.assertConsumed()

            val records = harness.records()
            assertScenario(records)
            val requests = model.requests
            assertEquals(7, requests.size)
            assertEquals(listOf(0, 0, 0, 0, 0, 0, 1), drops)
            for (n in 1 until requests.size) {
                assertExtends(requests[n - 1], requests[n], n + 1, dropped = drops[n] != drops[n - 1])
            }
        }

    /**
     * Request [n] keeps `system` and `tools` of the [earlier] one, and its message blocks start with
     * the earlier one's; after a drop ([dropped]), with the earlier one's without its thinking.
     */
    private fun assertExtends(
        earlier: ChatRequest,
        later: ChatRequest,
        n: Int,
        dropped: Boolean,
    ) {
        val (before, after) = body(earlier) to body(later)
        assertEquals(before["system"], after["system"], "request $n: system")
        assertEquals(before["tools"], after["tools"], "request $n: tools")
        val sent = blocks(before)
        val kept = if (dropped) sent.filterNot(::isThinking) else sent
        if (dropped) assertTrue(kept.size < sent.size, "request ${n - 1} held thinking for the drop to remove")
        assertEquals(kept, blocks(after).take(kept.size), "request $n rewrites what request ${n - 1} sent")
    }

    /** The log holds every record kind this test is about. */
    private fun assertScenario(records: List<SessionRecord>) {
        val events = records.map { it.event }
        assertTrue(events.count { it is UserMessage && it.kind == UserMessageKind.RUNTIME_CONTEXT } >= 2)
        assertTrue(events.any { it is AssistantMessage && it.parts.contains(compaction) })
        assertEquals(2, events.count { it is AssistantMessage && it.interrupted }, "a steered and a retried prefix")
        assertEquals(1, events.count { it is CompactionPrune })
        assertEquals(1, events.count { it is CompactionSummary })
        assertTrue(records.any { it.surfaceOp is SurfaceOp.Replace })
        assertEquals(1, events.count { it is ReasoningDropped })
    }

    private fun body(request: ChatRequest): JsonObject {
        val rules = WireRules.of(request, CredentialKind.API_KEY)
        return withoutCache(wire.body(request, rules, ImageData.NONE)).jsonObject
    }

    /** Whether a block of [blocks] is a `thinking` or `redacted_thinking` block: what a drop removes. */
    private fun isThinking(block: JsonElement): Boolean =
        block.jsonArray[1].jsonObject["type"] in setOf(JsonPrimitive("thinking"), JsonPrimitive("redacted_thinking"))

    /** Each message block with its role, in order: consecutive messages of one role merge on the wire. */
    private fun blocks(body: JsonObject): List<JsonElement> =
        body.getValue("messages").jsonArray.flatMap { message ->
            val role = message.jsonObject.getValue("role")
            message.jsonObject
                .getValue("content")
                .jsonArray
                .map { JsonArray(listOf(role, it)) }
        }

    private fun withoutCache(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element.filterKeys { it != "cache_control" }.mapValues { withoutCache(it.value) },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::withoutCache))
            }

            is JsonPrimitive -> {
                element
            }
        }
}
