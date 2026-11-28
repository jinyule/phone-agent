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

/**
 * Rule samples of the model view (`SessionEvent` KDoc, "Model view"): given these records, the
 * messages `deriveMessages` must return. WP02's `deriveMessages` tests run every [cases] entry:
 * for each [View], derive from the records with `seq <= afterSeq` and compare with [View.messages].
 * The run-rule samples (stop, resume, calls that need a result) are [RunRuleSamples], included.
 * `DeriveRuleSamplesTest` keeps the samples themselves consistent with the rules.
 */
object DeriveRuleSamples : RuleSampleKit() {
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

    private val mismatch =
        AssistantAttempt(1, ModelErrorKind.PREFIX_BINDING_MISMATCH, "invalid thinking signature", 400)

    private val firstLook = reply(1, thinking("A"), tap("t1"), stop = StopReason.TOOL_USE)
    private val onlyThinking = reply(1, thinking("B"), stop = StopReason.MAX_TOKENS)
    private val goOnAfterCap = UserMessage(UserMessageKind.USER, listOf(Part.Text("继续")))
    private val secondLook = reply(1, thinking("C"), Part.Text("点发送"), tap("t2"), stop = StopReason.TOOL_USE)
    private val afterDrop = reply(2, thinking("D"), Part.Text("已发送"))

    private val dropBySeq =
        Case(
            name = "reasoning-dropped-by-seq",
            rule =
                "context/reasoning-dropped(upToSeq) removes the Part.Reasoning of replies with seq <= upToSeq only: " +
                    "a reply after upToSeq keeps it (also one before the drop record), a reply after the drop keeps " +
                    "it, and a reply left without parts is left out.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    firstLook.alone,
                    dispatch("t1") from listOf(6),
                    done("t1") from listOf(7),
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    StepStarted(1, 2).alone,
                    onlyThinking.alone,
                    StepEnded(1, 2, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.MAX_TOKENS).alone,
                    TurnStarted(2).alone,
                    goOnAfterCap.alone,
                    StepStarted(2, 1).alone,
                    secondLook.alone,
                    dispatch("t2") from listOf(17),
                    done("t2") from listOf(18),
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    StepStarted(2, 2).alone,
                    mismatch.alone,
                    ReasoningDropped(upToSeq = 11).alone,
                    RequestRetry(2, 0, ModelErrorKind.PREFIX_BINDING_MISMATCH).alone,
                    afterDrop.alone,
                    StepEnded(2, 2, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(
                        22,
                        listOf(
                            user(ask),
                            assistant(firstLook),
                            tool(done("t1")),
                            assistant(onlyThinking),
                            user(goOnAfterCap),
                            assistant(secondLook),
                            tool(done("t2")),
                        ),
                    ),
                    View(
                        24,
                        listOf(
                            user(ask),
                            withoutReasoning(firstLook),
                            tool(done("t1")),
                            user(goOnAfterCap),
                            assistant(secondLook),
                            tool(done("t2")),
                        ),
                    ),
                    View(
                        27,
                        listOf(
                            user(ask),
                            withoutReasoning(firstLook),
                            tool(done("t1")),
                            user(goOnAfterCap),
                            assistant(secondLook),
                            tool(done("t2")),
                            assistant(afterDrop),
                        ),
                    ),
                ),
        )

    private val turnOne = reply(1, thinking("E"), Part.Text("第一步"))
    private val askTwo = UserMessage(UserMessageKind.USER, listOf(Part.Text("第二步")))
    private val turnTwo = reply(2, thinking("F"), Part.Text("第二步好了"))
    private val askThree = UserMessage(UserMessageKind.USER, listOf(Part.Text("第三步")))
    private val turnThree = reply(2, thinking("G"), Part.Text("第三步好了"))

    private val dropsAccumulate =
        Case(
            name = "reasoning-drops-accumulate",
            rule = "Every context/reasoning-dropped applies from its record on; a later one adds to an earlier one.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    turnOne.alone,
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                    TurnStarted(2).alone,
                    askTwo.alone,
                    StepStarted(2, 1).alone,
                    mismatch.alone,
                    ReasoningDropped(upToSeq = 6).alone,
                    RequestRetry(2, 0, ModelErrorKind.PREFIX_BINDING_MISMATCH).alone,
                    turnTwo.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                    TurnStarted(3).alone,
                    askThree.alone,
                    StepStarted(3, 1).alone,
                    mismatch.alone,
                    ReasoningDropped(upToSeq = 15).alone,
                    RequestRetry(2, 0, ModelErrorKind.PREFIX_BINDING_MISMATCH).alone,
                    turnThree.alone,
                    StepEnded(3, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(3, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(12, listOf(user(ask), assistant(turnOne), user(askTwo))),
                    View(17, listOf(user(ask), withoutReasoning(turnOne), user(askTwo), assistant(turnTwo))),
                    View(
                        26,
                        listOf(
                            user(ask),
                            withoutReasoning(turnOne),
                            user(askTwo),
                            withoutReasoning(turnTwo),
                            user(askThree),
                            assistant(turnThree),
                        ),
                    ),
                ),
        )

    /** An interrupted reply cut by steer, then the steer input and the reopened step's reply. */
    private fun steered(
        name: String,
        rule: String,
        prefix: AssistantMessage,
        shown: List<Message>,
    ): Case {
        val input = UserMessage(UserMessageKind.USER, listOf(Part.Text("换个说法")))
        val next = reply(2, Part.Text("好的"))
        return Case(
            name = name,
            rule = rule,
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    prefix.alone,
                    InboxSpliced(InboxQueue.NEXT_STEP, 0, listOf(InboxEntry(InboxEntryKind.STEER, input.parts))).alone,
                    InboxSpliced(InboxQueue.NEXT_STEP, 1).alone,
                    input.alone,
                    next.alone,
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(6, listOf(user(ask))),
                    View(12, listOf(user(ask)) + shown + listOf(user(input), assistant(next))),
                ),
        )
    }

    private val cutThinkingOnly = reply(1, thinking("H"), stop = null)
    private val cutAfterThinking = reply(1, thinking("I"), Part.Text("我先打"), stop = null)

    private val interruptedThinkingOnly =
        steered(
            "interrupted-reasoning-only",
            "An interrupted prefix joins the model view without its Part.Reasoning; with nothing else it is left out.",
            cutThinkingOnly,
            emptyList(),
        )

    private val interruptedThinkingAndText =
        steered(
            "interrupted-reasoning-and-text",
            "A thinking block that was complete before the text was cut stays in the log for the UI, but only the " +
                "text of the prefix joins the model view.",
            cutAfterThinking,
            listOf(withoutReasoning(cutAfterThinking)),
        )

    private val erroredAfterThinking = reply(1, thinking("K"), Part.Text("我先"), stop = null)
    private val retriedAfterThinking = reply(2, thinking("L"), Part.Text("我先看看屏幕"))

    private val supersededWithThinking =
        Case(
            name = "interrupted-reasoning-superseded-by-retry",
            rule = "A prefix with reasoning that a retry of the same step supersedes stays out of the view entirely.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    erroredAfterThinking.alone,
                    RequestRetry(2, 500, ModelErrorKind.NETWORK).alone,
                    retriedAfterThinking.alone,
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views = listOf(View(10, listOf(user(ask), assistant(retriedAfterThinking)))),
        )

    private val compaction =
        Part.Reasoning(
            p.provider,
            p.model,
            ReasoningKind.ANTHROPIC_COMPACTION,
            buildJsonObject {
                put("type", "compaction")
                put("content", "earlier steps, compacted")
            },
        )
    private val compacted = reply(1, compaction, thinking("M"), Part.Text("点发送"), tap("k1"), stop = StopReason.TOOL_USE)
    private val afterCompactedDrop = reply(2, Part.Text("已发送"))

    private val compactionKept =
        Case(
            name = "compaction-kept-when-reasoning-dropped",
            rule =
                "context/reasoning-dropped removes reasoning blocks only: an ANTHROPIC_COMPACTION block holds the " +
                    "compacted history and stays where it was.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    compacted.alone,
                    dispatch("k1") from listOf(6),
                    done("k1") from listOf(7),
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    StepStarted(1, 2).alone,
                    mismatch.alone,
                    ReasoningDropped(upToSeq = 6).alone,
                    RequestRetry(2, 0, ModelErrorKind.PREFIX_BINDING_MISMATCH).alone,
                    afterCompactedDrop.alone,
                    StepEnded(1, 2, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(11, listOf(user(ask), assistant(compacted), tool(done("k1")))),
                    View(
                        12,
                        listOf(
                            user(ask),
                            Message(Role.ASSISTANT, listOf(compaction, Part.Text("点发送"), tap("k1"))),
                            tool(done("k1")),
                        ),
                    ),
                    View(
                        16,
                        listOf(
                            user(ask),
                            Message(Role.ASSISTANT, listOf(compaction, Part.Text("点发送"), tap("k1"))),
                            tool(done("k1")),
                            assistant(afterCompactedDrop),
                        ),
                    ),
                ),
        )

    private val cutAfterCompaction = reply(1, compaction, thinking("N"), Part.Text("我先打"), stop = null)

    private val interruptedCompaction =
        steered(
            "interrupted-compaction-left-out",
            "An interrupted prefix joins the model view without any Part.Reasoning, a compaction block included: " +
                "the reply is incomplete, so the server compacts again on the next request (model-layer §5).",
            cutAfterCompaction,
            listOf(Message(Role.ASSISTANT, listOf(Part.Text("我先打")))),
        )

    /** Every case. */
    val cases: List<Case> =
        listOf(
            supersededByRetry,
            keptBySteer,
            reasoningDropped,
            sideRequests,
            dropBySeq,
            dropsAccumulate,
            interruptedThinkingOnly,
            interruptedThinkingAndText,
            supersededWithThinking,
            compactionKept,
            interruptedCompaction,
        ) + RunRuleSamples.cases
}
