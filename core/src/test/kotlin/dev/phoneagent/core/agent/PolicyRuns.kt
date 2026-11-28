@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SCREEN_JUDGMENT_TOOL
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

/**
 * Answers with [verdicts] in order (then Allow) and remembers what it was asked.
 *
 * The runtime's judgments of the screens a call's tool reads (policy-rules §6.4) come as more
 * [SCREEN_JUDGMENT_TOOL] evaluations of a call already evaluated. Unless [judgesScreens], they
 * take no verdict from the script: they are allowed and kept in [screens] only, so a script
 * written for the calls stays one.
 */
internal class ScriptedPolicy(
    vararg verdicts: Verdict,
    private val judgesScreens: Boolean = false,
) : PolicyEngine {
    private val queue = ArrayDeque(verdicts.toList())
    private val evaluated = HashSet<ToolCallId>()
    val contexts = mutableListOf<PolicyContext>()
    val intents = mutableListOf<ActionIntent>()

    /** The screens judged for tools and allowed without a scripted verdict. */
    val screens = mutableListOf<ScreenSnapshot?>()

    override fun evaluate(
        call: dev.phoneagent.core.tool.ToolCall,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict {
        val again = !evaluated.add(call.id)
        if (!judgesScreens && again && isScreenJudgment(call)) {
            screens += ctx.screen
            return Verdict.Allow
        }
        contexts += ctx
        intents += intent
        return queue.removeFirstOrNull() ?: Verdict.Allow
    }

    private fun isScreenJudgment(call: dev.phoneagent.core.tool.ToolCall): Boolean =
        call.name == SCREEN_JUDGMENT_TOOL && call.args.isEmpty()
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
