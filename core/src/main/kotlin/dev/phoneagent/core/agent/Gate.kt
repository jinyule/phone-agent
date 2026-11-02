package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
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
 * newest screen recorded by a call that already has its result ([latestSnapshot]) whatever the
 * chain returned, and the stricter verdict wins (Refuse > HandOff > Confirm > Allow; the policy's
 * on a tie), so no interceptor can bypass it. Every policy verdict is logged.
 *
 * An approved card is not enough to run (contracts addendum §8, §14): the runtime always tries to
 * observe a fresh screen S' ([freshScreen]) and records it under the call (`screen/snapshot`
 * with its callId, so once the call has its result, whatever it is, S' is the screen later calls
 * are judged on); the policy then resolves and judges the call again with the card as
 * `PolicyContext.approved`, on S', or on no screen when there is no screen port or the read failed
 * or took longer than [LoopConfig.recheckScreenTimeoutMs]. Whether a call may run
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

    /** Whether [call] may run now, and on which verdict, or the result standing in for running it. */
    suspend fun check(
        call: ToolCall,
        tool: Tool,
        interaction: UserInteraction,
    ): Ruling {
        val requested = chain.proceed(PreExecuteInput(call, tool))
        val judged = judge(call, tool, latestSnapshot(loop.log.records()), approved = null, requested)
        return decided(call, tool, judged, interaction, reconfirmed = 0)
    }

    /** Carries out [judged]; [reconfirmed] counts the confirmations asked again for this call. */
    private suspend fun decided(
        call: ToolCall,
        tool: Tool,
        judged: Judged,
        interaction: UserInteraction,
        reconfirmed: Int,
    ): Ruling =
        when (val verdict = judged.verdict) {
            Verdict.Allow -> {
                Ruling.Run(judged.seq)
            }

            is Verdict.Refuse -> {
                refused(verdict.hintForModel)
            }

            is Verdict.HandOff -> {
                Ruling.Answered(handedOff(interaction.handOff(verdict.kind, verdict.hint)))
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
    ): Ruling {
        val screen = freshScreen(loop, call.id)
        val judged = judge(call, tool, screen, card, Verdict.Allow)
        val exhausted = judged.verdict is Verdict.Confirm && reconfirmed >= loop.config.maxReconfirmations
        return if (exhausted) {
            refused(loop.texts.changedSinceApproval)
        } else {
            decided(
                call,
                tool,
                judged,
                interaction,
                reconfirmed + 1,
            )
        }
    }

    /**
     * Records the policy's verdict on [call] and returns the stricter of it and [requested] with
     * the record's seq. Allow comes out only when the policy allowed, so [Judged.seq] is then the
     * call's latest verdict, an Allow: the one `tool/started` names.
     */
    private suspend fun judge(
        call: ToolCall,
        tool: Tool,
        screen: ScreenSnapshot?,
        approved: ConfirmCard?,
        requested: Verdict,
    ): Judged {
        val setup = loop.setup
        val intent = setup.intents.resolve(call, tool.risk, screen)
        val context = policyContextOf(loop.log.records(), setup.taskId, screen, approved)
        val verdict = setup.policy.evaluate(call, intent, context)
        val record = loop.log.append(PolicyVerdictRecorded(call.id, context.level, intent, verdict))
        return Judged(if (strictness(requested) > strictness(verdict)) requested else verdict, record.seq)
    }

    private class Judged(
        val verdict: Verdict,
        val seq: Long,
    )

    private fun handedOff(result: HandoffResult): ToolResult =
        when (result) {
            HandoffResult.DONE -> ToolResult.ok(loop.texts.handoffDone)
            HandoffResult.DECLINED -> ToolResult.failure(ToolStatus.REFUSED, loop.texts.handoffDeclined)
            HandoffResult.EXPIRED -> ToolResult.failure(ToolStatus.REFUSED, loop.texts.handoffExpired)
            HandoffResult.UNAVAILABLE -> ToolResult.failure(ToolStatus.REFUSED, loop.texts.handoffUnavailable)
            HandoffResult.FAILED -> ToolResult.failure(ToolStatus.REFUSED, loop.texts.handoffFailed)
        }

    private fun refused(text: String): Ruling = Ruling.Answered(ToolResult.failure(ToolStatus.REFUSED, text))

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

/** What the [Gate] decided for a call. */
internal sealed interface Ruling {
    /** The call may run, on the Allow recorded at [verdictSeq]. */
    class Run(
        val verdictSeq: Long,
    ) : Ruling

    /** The call does not run; [result] stands in for it (refused, handed off, or not approved). */
    class Answered(
        val result: ToolResult,
    ) : Ruling
}

/**
 * A screen the runtime reads on its own for call [callId] (the re-check after its approval, or
 * the look after its handoff), recorded as `screen/snapshot` under [callId], so it is
 * `PolicyContext.screen` once that call has its result. Returns `null`, recording nothing, when
 * there is no screen port, the surface is unusable, or the read takes longer than
 * [LoopConfig.recheckScreenTimeoutMs].
 */
internal suspend fun freshScreen(
    loop: LoopContext,
    callId: ToolCallId,
): ScreenSnapshot? {
    val port = loop.setup.screen ?: return null
    val screen =
        try {
            withTimeoutOrNull(loop.config.recheckScreenTimeoutMs) { port.snapshot() }
        } catch (ignored: ScreenUnavailableException) {
            // An unusable surface is no screen; the policy decides what may run without one.
            null
        }
    screen?.let { loop.log.append(ScreenObserved(callId, it)) }
    return screen
}
