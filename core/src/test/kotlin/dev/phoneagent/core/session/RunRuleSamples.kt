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
import dev.phoneagent.core.session.DeriveRuleSamples.Case
import dev.phoneagent.core.session.DeriveRuleSamples.View
import dev.phoneagent.core.testing.TEST_WIRE
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The model-view samples of the run rules: a stop, a resume, and the calls that need a result
 * (`SessionInvariants`). Part of [DeriveRuleSamples.cases].
 */
internal object RunRuleSamples : RuleSampleKit() {
    private val stopPrefix = reply(1, Part.Text("正在打开"), stop = null)
    private val resumed = reply(1, Part.Text("继续发消息"))

    private val keptAfterStop =
        Case(
            name = "stop-prefix-visible-with-next-input",
            rule =
                "A prefix cut by stop (or by a final failure) is not superseded: it joins the model view with the " +
                    "next user/message, which opens the next turn after task/stopped.",
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
                    TaskStopped(StopCause.USER).alone,
                    TurnStarted(2).alone,
                    goOn.alone,
                    StepStarted(2, 1).alone,
                    resumed.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(9, listOf(user(ask))),
                    View(11, listOf(user(ask), assistant(stopPrefix), user(goOn))),
                    View(15, listOf(user(ask), assistant(stopPrefix), user(goOn), assistant(resumed))),
                ),
        )

    private val stoppedAfterThinking = reply(1, thinking("J"), Part.Text("正在打开"), stop = null)
    private val resumeAfterStop = UserMessage(UserMessageKind.USER, listOf(Part.Text("接着做")))
    private val resumedReply = reply(1, Part.Text("继续打开"))

    private val stoppedThinkingAndText =
        Case(
            name = "stopped-after-complete-reasoning",
            rule =
                "A prefix cut by stop after a complete thinking block joins the next turn's view with its text " +
                    "only.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    stoppedAfterThinking.alone,
                    StepEnded(1, 1, StepOutcome.CANCELLED).alone,
                    TurnEnded(1, TurnEndReason.STOPPED).alone,
                    TaskStopped(StopCause.USER).alone,
                    TurnStarted(2).alone,
                    resumeAfterStop.alone,
                    StepStarted(2, 1).alone,
                    resumedReply.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(9, listOf(user(ask))),
                    View(
                        15,
                        listOf(
                            user(ask),
                            withoutReasoning(stoppedAfterThinking),
                            user(resumeAfterStop),
                            assistant(resumedReply),
                        ),
                    ),
                ),
        )

    private val shutdownPrefix = reply(1, Part.Text("正在打开微信"), stop = null)
    private val afterResume = reply(1, Part.Text("先看看屏幕"))

    private val resumedAfterShutdown =
        Case(
            name = "shutdown-resumed-then-next-input",
            rule =
                "After task/stopped(SHUTDOWN) the log is resumed first (a complete stop leaves nothing to repair), " +
                    "then the user's input opens the next turn; the cut prefix joins the view with that input.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    shutdownPrefix.alone,
                    StepEnded(1, 1, StepOutcome.CANCELLED).alone,
                    TurnEnded(1, TurnEndReason.STOPPED).alone,
                    TaskStopped(StopCause.SHUTDOWN).alone,
                    SessionResumed(droppedTailBytes = 0).alone,
                    TurnStarted(2).alone,
                    goOn.alone,
                    StepStarted(2, 1).alone,
                    afterResume.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(10, listOf(user(ask))),
                    View(12, listOf(user(ask), assistant(shutdownPrefix), user(goOn))),
                    View(16, listOf(user(ask), assistant(shutdownPrefix), user(goOn), assistant(afterResume))),
                ),
        )

    private val cappedWithCall = reply(1, Part.Text("我来发消息"), tap("m1"), stop = StopReason.MAX_TOKENS)
    private val cappedSkipped =
        ToolResultRecorded(
            ToolCallId("m1"),
            "phone_tap",
            ToolResult.failure(ToolStatus.SKIPPED, "没有执行：回复被截断，你看到的参数不完整，不是你原本要发的那一份。请重新发起这次调用。"),
        )
    private val afterCap = reply(1, Part.Text("好的，重新来"))

    private val cappedCallsSkipped =
        Case(
            name = "max-tokens-calls-answered-skipped",
            rule =
                "A reply cut at the output cap stays as recorded, tool calls included; each of its calls is answered " +
                    "by a SKIPPED tool/result (an error) before the next request, so the next user input finds " +
                    "every call paired.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    cappedWithCall.alone,
                    cappedSkipped from listOf(6),
                    StepEnded(1, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(1, TurnEndReason.MAX_TOKENS).alone,
                    TurnStarted(2).alone,
                    goOn.alone,
                    StepStarted(2, 1).alone,
                    afterCap.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(7, listOf(user(ask), assistant(cappedWithCall), tool(cappedSkipped))),
                    View(11, listOf(user(ask), assistant(cappedWithCall), tool(cappedSkipped), user(goOn))),
                    View(
                        15,
                        listOf(
                            user(ask),
                            assistant(cappedWithCall),
                            tool(cappedSkipped),
                            user(goOn),
                            assistant(afterCap),
                        ),
                    ),
                ),
        )

    private val twoTaps = reply(1, Part.Text("点发送"), tap("r1"), tap("r2"), stop = StopReason.TOOL_USE)
    private val mayHaveRun =
        ToolResultRecorded(
            ToolCallId("r1"),
            "phone_tap",
            ToolResult.failure(
                ToolStatus.CANCELLED,
                "已停止：这个操作可能已经生效，继续之前先 phone_observe 核实，再决定要不要重试",
            ),
        )
    private val neverRan =
        ToolResultRecorded(ToolCallId("r2"), "phone_tap", ToolResult.failure(ToolStatus.CANCELLED, "已停止：没有执行"))
    private val afterCancel = reply(1, Part.Text("先看看屏幕"))

    private val stopCancelsCalls =
        Case(
            name = "stop-cancels-started-and-pending-calls",
            rule =
                "A stop answers every call still without a result with CANCELLED (an error): the text says the " +
                    "call may have taken effect when it has a tool/started, and that it did not run otherwise; " +
                    "both results stay in the view.",
            records =
                records(
                    p.start.alone,
                    TurnStarted(1).alone,
                    ask.alone,
                    StepStarted(1, 1).alone,
                    header.alone,
                    twoTaps.alone,
                    dispatch("r1") from listOf(6),
                    PolicyVerdictRecorded(
                        ToolCallId("r1"),
                        SecurityLevel.STANDARD,
                        ActionIntent(IntentKind.OUTWARD, "com.example.chat"),
                        Verdict.Allow,
                    ).alone,
                    ToolStarted(ToolCallId("r1"), verdictSeq = 8).alone,
                    mayHaveRun from listOf(7),
                    neverRan from listOf(6),
                    StepEnded(1, 1, StepOutcome.CANCELLED).alone,
                    TurnEnded(1, TurnEndReason.STOPPED).alone,
                    TaskStopped(StopCause.USER).alone,
                    TurnStarted(2).alone,
                    goOn.alone,
                    StepStarted(2, 1).alone,
                    afterCancel.alone,
                    StepEnded(2, 1, StepOutcome.COMPLETED).alone,
                    TurnEnded(2, TurnEndReason.COMPLETED).alone,
                ),
            views =
                listOf(
                    View(14, listOf(user(ask), assistant(twoTaps), tool(mayHaveRun), tool(neverRan))),
                    View(
                        20,
                        listOf(
                            user(ask),
                            assistant(twoTaps),
                            tool(mayHaveRun),
                            tool(neverRan),
                            user(goOn),
                            assistant(afterCancel),
                        ),
                    ),
                ),
        )

    /** Every run-rule case. */
    val cases: List<Case> =
        listOf(keptAfterStop, stoppedThinkingAndText, resumedAfterShutdown, cappedCallsSkipped, stopCancelsCalls)
}
