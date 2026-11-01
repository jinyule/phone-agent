package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.tool.ToolCall

/**
 * The phase1 §6 matrix, in the order of policy-rules.md §6:
 * 1. calls the policy cannot check are refused; `request_confirmation` always asks; back and home always run;
 * 2. a ref from another snapshot than [PolicyContext.screen] is refused (skipped on the re-check);
 * 3. hard refusals, then hand-offs ([HardRules]);
 * 4. confirmations by kind and level (SPEND at every level, refused when unattended);
 * 5. everything else runs.
 *
 * A confirmation equal to [PolicyContext.approved] is the approved one and runs.
 */
internal class RulesEngine(
    private val hard: HardRules,
) : PolicyEngine {
    override fun evaluate(
        call: ToolCall,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict {
        val step = Steps.parse(call)
        return gate(call, step, intent, ctx)
            ?: hard.refusal(step, intent, ctx.screen)
            ?: hard.handOff(step, intent, ctx.screen)
            ?: confirmation(call, step, intent, ctx)
            ?: Verdict.Allow
    }

    private fun gate(
        call: ToolCall,
        step: Step,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict? =
        when {
            step == Step.Malformed -> Refusal.UNRESOLVED.verdict()
            step == Step.Unknown && intent.kind != IntentKind.READ -> Refusal.UNKNOWN_TOOL.verdict()
            step is Step.Confirmation -> ask(Cards.requested(call, step, intent), ctx)
            step is Step.Key && step.key != SystemKey.ENTER -> Verdict.Allow
            stale(step, ctx) -> Refusal.STALE_SCREEN.verdict()
            else -> null
        }

    /** The step names a ref, and [PolicyContext.screen] is not the snapshot it came from. */
    private fun stale(
        step: Step,
        ctx: PolicyContext,
    ): Boolean {
        val ref = (step as? ScreenStep)?.ref
        val screen = ctx.screen
        return ref != null && ctx.approved == null && (screen == null || ref.snapshot != screen.id.value)
    }

    private fun confirmation(
        call: ToolCall,
        step: Step,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict? {
        val reason = reason(intent, ctx)
        return when {
            reason == null -> null
            reason == ConfirmReason.SPEND && ctx.unattended -> Refusal.SPEND_UNATTENDED.verdict()
            else -> ask(Cards.forCall(call, step, intent, reason, ctx.screen), ctx)
        }
    }

    /** Why the user must confirm, or `null` (the level-dependent rows of the matrix). */
    private fun reason(
        intent: ActionIntent,
        ctx: PolicyContext,
    ): ConfirmReason? {
        val guarded = ctx.level != SecurityLevel.AUTONOMOUS
        return when {
            intent.kind == IntentKind.SPEND -> ConfirmReason.SPEND
            intent.kind == IntentKind.OUTWARD && outwardAsks(intent, ctx) -> ConfirmReason.OUTWARD
            intent.kind == IntentKind.DELETE && guarded -> ConfirmReason.DELETE
            intent.kind == IntentKind.SETTINGS && guarded -> ConfirmReason.SETTINGS
            firstInApp(intent, ctx) -> ConfirmReason.FIRST_IN_APP
            else -> null
        }
    }

    /** 谨慎: every outward action; 标准: the first one per app in this task; 自主: none. */
    private fun outwardAsks(
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Boolean =
        ctx.level == SecurityLevel.CAUTIOUS ||
            (ctx.level == SecurityLevel.STANDARD && intent.pkg !in ctx.outwardConfirmedPackages)

    /** 谨慎 only: the first non-read action in an app during this task. */
    private fun firstInApp(
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Boolean =
        ctx.level == SecurityLevel.CAUTIOUS &&
            intent.kind != IntentKind.READ &&
            intent.pkg != null &&
            intent.pkg !in ctx.operatedPackages

    /** Asks for [card], unless it is exactly the card the user just approved. */
    private fun ask(
        card: ConfirmCard,
        ctx: PolicyContext,
    ): Verdict = if (ctx.approved == card) Verdict.Allow else Verdict.Confirm(card)
}
