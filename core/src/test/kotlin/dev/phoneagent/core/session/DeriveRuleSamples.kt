package dev.phoneagent.core.session

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
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.testing.TEST_WIRE
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rule samples of the model view (`SessionEvent` KDoc, "Model view"): given these records, the
 * messages `deriveMessages` must return. WP02's `deriveMessages` tests run every [cases] entry:
 * for each [View], derive from the records with `seq <= afterSeq` and compare with [View.messages].
 * `DeriveRuleSamplesTest` keeps the samples themselves consistent with the rules.
 */
object DeriveRuleSamples {
    /** The expected model view once the log ends at [afterSeq]. */
    data class View(
        val afterSeq: Long,
        val messages: List<Message>,
    )

    /** One rule, shown on a small log. */
    data class Case(
        val name: String,
        val rule: String,
        val records: List<SessionRecord>,
        val views: List<View>,
    )

    private val p = SessionSamples

    private val header =
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

    private val ask = UserMessage(UserMessageKind.USER, listOf(Part.Text("给张三发消息")))

    private fun reply(
        attempt: Int,
        vararg parts: Part,
        stop: StopReason? = StopReason.END_TURN,
    ) = AssistantMessage(attempt, p.provider, p.model, parts.toList(), stop, interrupted = stop == null)

    private fun records(vararg events: Pair<SessionEvent, List<Long>>): List<SessionRecord> =
        events.mapIndexed { index, (event, sources) -> SessionRecord(index + 1L, 0, event, sources = sources) }

    private infix fun SessionEvent.from(sources: List<Long>) = this to sources

    private val SessionEvent.alone get() = this to emptyList<Long>()

    private fun user(event: UserMessage) = Message(Role.USER, event.parts)

    private fun assistant(event: AssistantMessage) = Message(Role.ASSISTANT, event.parts)

    private val errorPrefix = reply(1, Part.Text("我先"), stop = null)
    private val retried = reply(2, Part.Text("我先看看屏幕"))

    private val supersededByRetry =
        Case(
            name = "error-prefix-superseded-by-retry",
            rule =
                "An interrupted reply followed by another reply before any user/message is UI-only: the retry " +
                    "of the same step resends the request that produced it, without the prefix.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    errorPrefix.alone,
                    RequestRetry(2, 500, ModelErrorKind.NETWORK).alone,
                    retried.alone,
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(5, listOf(user(ask))),
                    View(7, listOf(user(ask))),
                    View(10, listOf(user(ask), assistant(retried))),
                ),
        )

    private val steerPrefix = reply(1, Part.Text("好的，我打开微信"), stop = null)
    private val steer = UserMessage(UserMessageKind.USER, listOf(Part.Text("用 QQ 发")))
    private val afterSteer = reply(2, Part.Text("改用 QQ"))

    private val keptBySteer =
        Case(
            name = "steer-prefix-kept",
            rule =
                "An interrupted reply followed by a user/message before any other reply is model-visible: a steer " +
                    "cuts the stream and reopens the step with its input after the prefix (phase1 §7.2).",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    steerPrefix.alone,
                    InboxSpliced(InboxQueue.NEXT_STEP, 0, listOf(InboxEntry(InboxEntryKind.STEER, steer.parts))).alone,
                    InboxSpliced(InboxQueue.NEXT_STEP, 1).alone,
                    steer.alone,
                    afterSteer.alone,
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(6, listOf(user(ask))),
                    View(9, listOf(user(ask), assistant(steerPrefix), user(steer))),
                    View(12, listOf(user(ask), assistant(steerPrefix), user(steer), assistant(afterSteer))),
                ),
        )

    private val stopPrefix = reply(1, Part.Text("正在打开"), stop = null)
    private val goOn = UserMessage(UserMessageKind.USER, listOf(Part.Text("继续")))
    private val resumed = reply(1, Part.Text("继续发消息"))

    private val keptAfterStop =
        Case(
            name = "stop-prefix-visible-with-next-input",
            rule =
                "A prefix cut by stop (or by a final failure) is not superseded: it joins the model view with the " +
                    "next user/message, in the next turn.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    stopPrefix.alone,
                    StepEnded(1, 1, StepOutcome.CANCELLED).alone,
                    TurnEnded(1, TurnEndReason.STOPPED).alone,
                    TurnStarted(2).alone,
                    goOn.alone,
                    StepStarted(2, 1).alone,
                    resumed.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(8, listOf(user(ask))),
                    View(10, listOf(user(ask), assistant(stopPrefix), user(goOn))),
                    View(14, listOf(user(ask), assistant(stopPrefix), user(goOn), assistant(resumed))),
                ),
        )

    private val tapPart = Part.ToolCall(p.call, "phone_tap", """{"screen":"s1","ref":"e2","reason":"发送"}""")
    private val thoughtful = reply(1, p.reasoning, Part.Text("点发送"), tapPart, stop = StopReason.TOOL_USE)
    private val tapCall = ToolCall(p.call, p.task, p.session, "phone_tap", buildJsonObject { put("ref", "e2") }, "发送")
    private val tapResult =
        ToolResultRecorded(p.call, "phone_tap", ToolResult(ToolStatus.OK, listOf(Part.Text("done"))))
    private val toolMessage = Message(Role.TOOL, listOf(tapResult.result.toPart(tapResult.callId)))
    private val withoutReasoning = Message(Role.ASSISTANT, listOf(Part.Text("点发送"), tapPart))
    private val sent = reply(2, Part.Text("已发送"))

    private val reasoningDropped =
        Case(
            name = "reasoning-dropped-before-retry",
            rule =
                "After context/reasoning-dropped(upToSeq), every Part.Reasoning of replies with seq <= upToSeq is " +
                    "left out; text, tool calls and tool results stay as they were.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    thoughtful.alone,
                    ToolCallRecorded(tapCall) from listOf(6),
                    tapResult from listOf(7),
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    StepStarted(1, 2).alone,
                    AssistantAttempt(
                        1,
                        ModelErrorKind.PREFIX_BINDING_MISMATCH,
                        "invalid thinking signature",
                        400,
                    ).alone,
                    ReasoningDropped(upToSeq = 6).alone,
                    RequestRetry(2, 0, ModelErrorKind.PREFIX_BINDING_MISMATCH).alone,
                    sent.alone,
                    StepEnded(1, 2, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(11, listOf(user(ask), assistant(thoughtful), toolMessage)),
                    View(13, listOf(user(ask), withoutReasoning, toolMessage)),
                    View(16, listOf(user(ask), withoutReasoning, toolMessage, assistant(sent))),
                ),
        )

    private val sideQuestion =
        SideRequest(
            role = ModelRole.LIGHT,
            provider = p.provider,
            model = p.model,
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            request =
                ChatRequest(
                    system = listOf(PromptBlock("light", "Answer yes or no.")),
                    tools = emptyList(),
                    messages = listOf(Message(Role.USER, listOf(Part.Text("这是登录页吗？")))),
                    wire = TEST_WIRE,
                    cache = CacheLayout.NONE,
                ),
        )
    private val sideAnswer =
        SideResponse(SideOutcome.Completed(ChatResponse(listOf(Part.Text("不是")), StopReason.END_TURN)))
    private val mainReply = reply(1, Part.Text("开始操作"))

    private val sideRequests =
        Case(
            name = "side-requests-outside-the-main-view",
            rule =
                "side/request and side/response never enter the main view, even when the side request uses the " +
                    "same provider and model as the main conversation.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    sideQuestion from listOf(4),
                    sideAnswer from listOf(6),
                    mainReply.alone,
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(7, listOf(user(ask))),
                    View(10, listOf(user(ask), assistant(mainReply))),
                ),
        )

    /** Every case. */
    val cases: List<Case> = listOf(supersededByRetry, keptBySteer, keptAfterStop, reasoningDropped, sideRequests)
}
