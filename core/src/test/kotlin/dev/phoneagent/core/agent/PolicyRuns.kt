@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Samples of the policy tests: one OUTWARD `send` call to 小王. */
internal object PolicySamples {
    val c1 = ToolCallId("c1")
    val sendCard = ConfirmCard(c1, ConfirmReason.OUTWARD, "send", target = "小王", pkg = "com.example.chat")
    val handOff = Verdict.HandOff(HandoffKind.LOGIN, "log in")
    val refuse = Verdict.Refuse("rule-7", "Sending to strangers is not allowed.")
}

/** Answers with [verdicts] in order (then Allow) and remembers what it was asked. */
internal class ScriptedPolicy(
    vararg verdicts: Verdict,
) : PolicyEngine {
    private val queue = ArrayDeque(verdicts.toList())
    val contexts = mutableListOf<PolicyContext>()
    val intents = mutableListOf<ActionIntent>()

    override fun evaluate(
        call: dev.phoneagent.core.tool.ToolCall,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict {
        contexts += ctx
        intents += intent
        return queue.removeFirstOrNull() ?: Verdict.Allow
    }
}

/** A started `send` task. */
internal class PolicyRun(
    val harness: LoopHarness,
    val loop: AgentLoop,
    val tool: FakeTool,
) {
    suspend fun result(): ToolResult =
        harness
            .events()
            .only<ToolResultRecorded>()
            .first()
            .result
}

@Suppress("LongParameterList") // Every parameter has a default; tests name the few they change.
internal suspend fun TestScope.send(
    policy: PolicyEngine,
    config: LoopConfig = LoopConfig(),
    interceptors: Interceptors = Interceptors(requestError = emptyList()),
    screen: ScriptedScreen? = ScriptedScreen(ScriptedScreen.snapshotOf("s1")),
    surfaces: SurfaceManager? = null,
    settings: TaskStarted = LoopHarness.ATTENDED,
    slowScreen: ScreenPort? = null,
): PolicyRun {
    val tool = FakeTool("send", risk = RiskClass.OUTWARD)
    val model = FakeChatModel().calls(call("c1", "send")).answers("ok")
    val harness =
        LoopHarness(
            this,
            model,
            tools = listOf(tool),
            policy = policy,
            screen = slowScreen ?: screen,
            surfaces = surfaces,
            config = config,
            settings = settings,
            interceptors = interceptors,
        )
    val loop = harness.start()
    loop.followup(text("给小王发消息"))
    return PolicyRun(harness, loop, tool)
}

/** The first prompt other than [after]. */
internal suspend fun AgentLoop.awaitPrompt(after: PromptId? = null): Phase.Waiting =
    phase.first { it is Phase.Waiting && it.promptId != after } as Phase.Waiting

/** Approves the next confirmation after [after] and returns its prompt. */
internal suspend fun AgentLoop.approveNext(after: PromptId? = null): PromptId {
    val prompt = awaitPrompt(after).promptId!!
    assertTrue(answer(prompt, UserReply.Approve))
    return prompt
}

internal fun ToolResult.text(): String = (content.single() as Part.Text).text
