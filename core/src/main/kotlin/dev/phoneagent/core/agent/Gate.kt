package dev.phoneagent.core.agent

import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `toolPreExecute` with the PolicyEngine as its last, fixed guard (phase1 §3.8, AGENTS §3.3).
 * The interceptor chain runs first; the policy then resolves and judges the recorded call on the
 * screen the model last saw ([latestSnapshot]) whatever the chain returned, and the stricter
 * verdict wins (Refuse > HandOff > Confirm > Allow; the policy's on a tie), so no interceptor can
 * bypass it. Every policy verdict is logged.
 *
 * An approved card is not enough to run (contracts addendum §8, §14): the runtime always tries to
 * observe a fresh screen S' ([freshScreen]) and records it (`screen/snapshot` without a call: a
 * runtime observation the model has not seen); the policy then resolves and judges the call again
 * with the card as `PolicyContext.approved`, on S', or on no screen when there is no screen port or
 * the read failed or took longer than [LoopConfig.recheckScreenTimeoutMs]. Whether a call may run
 * without a screen is the policy's decision, never the runtime's. Allow runs it; Refuse and
 * HandOff end as such; a new Confirm (the screen changed under the approval) is shown again, at
 * most [LoopConfig.maxReconfirmations] times per call, after which the call is refused and the
 * model told to observe and try again.
 */
internal class Gate(
    private val loop: LoopContext,
) {
    private val chain =
        loop.setup.interceptors.toolPreExecute
            .chain { Verdict.Allow }

    /** The result standing in for running [call], or `null` when it may run now. */
    suspend fun check(
        call: ToolCall,
        tool: Tool,
        interaction: UserInteraction,
    ): ToolResult? {
        val requested = chain.proceed(PreExecuteInput(call, tool))
        val verdict = judge(call, tool, latestSnapshot(loop.log.records()), approved = null, requested)
        return decided(call, tool, verdict, interaction, reconfirmed = 0)
    }

    /** Carries out [verdict]; [reconfirmed] counts the confirmations asked again for this call. */
    private suspend fun decided(
        call: ToolCall,
        tool: Tool,
        verdict: Verdict,
        interaction: UserInteraction,
        reconfirmed: Int,
    ): ToolResult? =
        when (verdict) {
            Verdict.Allow -> {
                null
            }

            is Verdict.Refuse -> {
                refused(verdict.hintForModel)
            }

            is Verdict.HandOff -> {
                handedOff(interaction.handOff(verdict.kind, verdict.hint))
            }

            is Verdict.Confirm -> {
                when (interaction.confirm(verdict.card)) {
                    ConfirmResult.APPROVED -> recheck(call, tool, verdict.card, interaction, reconfirmed)
                    ConfirmResult.REJECTED -> refused(loop.texts.rejected)
                    ConfirmResult.EXPIRED -> refused(loop.texts.confirmExpired)
                    ConfirmResult.UNAVAILABLE -> refused(loop.texts.confirmUnavailable)
                }
            }
        }

    private suspend fun recheck(
        call: ToolCall,
        tool: Tool,
        card: ConfirmCard,
        interaction: UserInteraction,
        reconfirmed: Int,
    ): ToolResult? {
        val screen = freshScreen(loop)
        val verdict = judge(call, tool, screen, card, Verdict.Allow)
        val exhausted = verdict is Verdict.Confirm && reconfirmed >= loop.config.maxReconfirmations
        return if (exhausted) {
            refused(loop.texts.changedSinceApproval)
        } else {
            decided(
                call,
                tool,
                verdict,
                interaction,
                reconfirmed + 1,
            )
        }
    }

    private suspend fun judge(
        call: ToolCall,
        tool: Tool,
        screen: ScreenSnapshot?,
        approved: ConfirmCard?,
        requested: Verdict,
    ): Verdict {
        val setup = loop.setup
        val intent = setup.intents.resolve(call, tool.risk, screen)
        val context = policyContextOf(loop.log.records(), setup.taskId, screen, approved)
        val verdict = setup.policy.evaluate(call, intent, context)
        loop.log.append(PolicyVerdictRecorded(call.id, context.level, intent, verdict))
        return if (strictness(requested) > strictness(verdict)) requested else verdict
    }

    private fun handedOff(result: HandoffResult): ToolResult =
        when (result) {
            HandoffResult.DONE -> ToolResult.ok(loop.texts.handoffDone)
            HandoffResult.DECLINED -> refused(loop.texts.handoffDeclined)
            HandoffResult.EXPIRED -> refused(loop.texts.handoffExpired)
            HandoffResult.UNAVAILABLE -> refused(loop.texts.handoffUnavailable)
            HandoffResult.FAILED -> refused(loop.texts.handoffFailed)
        }

    private fun refused(text: String): ToolResult = ToolResult.failure(ToolStatus.REFUSED, text)

    private fun strictness(verdict: Verdict): Strictness =
        when (verdict) {
            Verdict.Allow -> Strictness.ALLOW
            is Verdict.Confirm -> Strictness.CONFIRM
            is Verdict.HandOff -> Strictness.HAND_OFF
            is Verdict.Refuse -> Strictness.REFUSE
        }

    /** Verdicts from least to most strict. */
    private enum class Strictness { ALLOW, CONFIRM, HAND_OFF, REFUSE }
}

/**
 * A screen the runtime reads for itself (not for a call), recorded as `screen/snapshot` without a
 * call id; `null` when there is no screen port, the surface is unusable, or the read takes longer
 * than [LoopConfig.recheckScreenTimeoutMs].
 */
internal suspend fun freshScreen(loop: LoopContext): ScreenSnapshot? {
    val port = loop.setup.screen ?: return null
    val screen =
        try {
            withTimeoutOrNull(loop.config.recheckScreenTimeoutMs) { port.snapshot() }
        } catch (ignored: ScreenUnavailableException) {
            // An unusable surface is no screen; the policy decides what may run without one.
            null
        }
    screen?.let { loop.log.append(ScreenObserved(callId = null, snapshot = it)) }
    return screen
}
