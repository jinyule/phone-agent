package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.testing.TEST_WIRE
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders shared by the model-view rule samples ([DeriveRuleSamples], [RunRuleSamples]). */
open class RuleSampleKit {
    protected val p = SessionSamples

    protected val header =
        RequestHeader(
            provider = p.provider,
            model = p.model,
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            wire = TEST_WIRE,
            system = listOf(PromptBlock("identity", "You operate a phone.")),
            tools = listOf(ToolDef("phone_tap", "Tap an element.", p.schema)),
            cache = CacheLayout.NONE,
            contextEdits = ContextEditPolicy.ClientSide,
        )

    protected val ask = UserMessage(UserMessageKind.USER, listOf(Part.Text("给张三发消息")))

    protected fun reply(
        attempt: Int,
        vararg parts: Part,
        stop: StopReason? = StopReason.END_TURN,
    ) = AssistantMessage(attempt, p.provider, p.model, parts.toList(), stop, interrupted = stop == null)

    protected fun records(vararg events: Pair<SessionEvent, List<Long>>): List<SessionRecord> =
        events.mapIndexed { index, (event, sources) -> SessionRecord(index + 1L, 0, event, sources = sources) }

    protected infix fun SessionEvent.from(sources: List<Long>) = this to sources

    protected val SessionEvent.alone get() = this to emptyList<Long>()

    protected fun user(event: UserMessage) = Message(Role.USER, event.parts)

    protected fun assistant(event: AssistantMessage) = Message(Role.ASSISTANT, event.parts)

    protected val goOn = UserMessage(UserMessageKind.USER, listOf(Part.Text("继续")))

    protected fun thinking(signature: String) =
        p.reasoning.copy(
            opaque =
                buildJsonObject {
                    put("type", "thinking")
                    put("signature", signature)
                },
            summary = "thought $signature",
        )

    protected fun tap(id: String) =
        Part.ToolCall(ToolCallId(id), "phone_tap", """{"screen":"s1","ref":"e2","reason":"r"}""")

    protected fun dispatch(id: String) =
        ToolCallRecorded(
            ToolCall(ToolCallId(id), p.task, p.session, "phone_tap", buildJsonObject { put("ref", "e2") }, "r"),
        )

    protected fun done(id: String) =
        ToolResultRecorded(ToolCallId(id), "phone_tap", ToolResult(ToolStatus.OK, listOf(Part.Text("done $id"))))

    protected fun tool(result: ToolResultRecorded) = Message(Role.TOOL, listOf(result.result.toPart(result.callId)))

    protected fun withoutReasoning(event: AssistantMessage) =
        Message(Role.ASSISTANT, event.parts.filterNot { it is Part.Reasoning })
}
