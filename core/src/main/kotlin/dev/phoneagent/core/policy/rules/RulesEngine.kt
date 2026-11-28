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
 * 1. calls the policy cannot check are refused; `request_confirmation` always asks; back and home
 *    always run; screen actions without a screen, refs from another snapshot (except on the
 *    re-check), apps named other than by package and links of other schemes are refused;
 * 2. hard refusals, then hand-offs ([HardRules]);
 * 3. confirmations by kind and level (SPEND at every level, refused when unattended);
 * 4. everything else runs.
 *
 * The re-check ([PolicyContext.approved] set) is its own branch after step 2: whatever the level
 * and the history ask now, the card for the call on the fresh screen is built (from the kind when
 * the level would not ask) and runs only when it equals the approved card as a whole and its
 * amount is not unclear; otherwise the user is asked again.
 */
internal class RulesEngine(
    private val hard: HardRules,
    private val cards: Cards,
    private val reading: Reading,
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
            step is Step.Confirmation -> confirm(cards.requested(call, step, intent), step, intent, ctx)
            step is Step.Key && step.key != SystemKey.ENTER -> Verdict.Allow
            step is ScreenStep -> screenGate(step, ctx)?.verdict()
            step is Step.Launch -> launchGate(step)?.verdict()
            else -> null
        }

    /** A screen action needs a screen, and a ref from it (on the re-check the screen is the fresh one). */
    private fun screenGate(
        step: ScreenStep,
        ctx: PolicyContext,
    ): Refusal? {
        val screen = ctx.screen
        val snapshot = step.ref?.snapshot
        return when {
            screen == null -> Refusal.NO_SCREEN
            step.ref != null && ctx.approved == null && snapshot != screen.id.value -> Refusal.STALE_SCREEN
            else -> null
        }
    }

    /** `app` must be a package name (looked up with `phone_apps`); links only of `LaunchTarget.Link.SCHEMES`. */
    private fun launchGate(step: Step.Launch): Refusal? =
        when {
            step.link -> Refusal.LINK_SCHEME.takeUnless { Launches.linkAllowed(step.target) }
            else -> Refusal.LAUNCH_PACKAGE.takeUnless { Text.isPackage(step.target) }
        }

    private fun confirmation(
        call: ToolCall,
        step: Step,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict? {
        val approved = ctx.approved
        return if (approved ==
            null
        ) {
            ask(call, step, intent, reason(intent, ctx), ctx)
        } else {
            recheck(call, step, intent, ctx, approved)
        }
    }

    /**
     * The re-check: the reason the level gives now, else the one the kind gives, so a call the level
     * would no longer ask about is still compared. A call that now has no reason to ask at all is
     * not the call the user approved: asked again with the approved reason, never let through.
     */
    private fun recheck(
        call: ToolCall,
        step: Step,
        intent: ActionIntent,
        ctx: PolicyContext,
        approved: ConfirmCard,
    ): Verdict =
        ask(call, step, intent, reason(intent, ctx) ?: KIND_REASONS[intent.kind], ctx)
            ?: Verdict.Confirm(cards.forCall(call, step, intent, approved.reason, ctx.screen))

    private fun ask(
        call: ToolCall,
        step: Step,
        intent: ActionIntent,
        reason: ConfirmReason?,
        ctx: PolicyContext,
    ): Verdict? =
        when {
            reason == null -> null
            reason == ConfirmReason.SPEND && ctx.unattended -> Refusal.SPEND_UNATTENDED.verdict()
            else -> confirm(cards.forCall(call, step, intent, reason, ctx.screen), step, intent, ctx)
        }

    /** Asks for [card], unless it equals the card the user just approved and its amount is not unclear. */
    private fun confirm(
        card: ConfirmCard,
        step: Step,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict =
        if (ctx.approved == card && reading.price(step, intent, ctx.screen) != Price.Unclear) {
            Verdict.Allow
        } else {
            Verdict.Confirm(card)
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
            (ctx.level == SecurityLevel.STANDARD && !listed(intent.pkg, ctx.outwardConfirmedPackages))

    /** 谨慎 only: the first non-read action in an app during this task. */
    private fun firstInApp(
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Boolean =
        ctx.level == SecurityLevel.CAUTIOUS &&
            intent.kind != IntentKind.READ &&
            intent.pkg != null &&
            !listed(intent.pkg, ctx.operatedPackages)
}

/** The confirmation each risky kind asks for when the level does not ask. */
private val KIND_REASONS =
    mapOf(
        IntentKind.SPEND to ConfirmReason.SPEND,
        IntentKind.OUTWARD to ConfirmReason.OUTWARD,
        IntentKind.DELETE to ConfirmReason.DELETE,
        IntentKind.SETTINGS to ConfirmReason.SETTINGS,
    )

/** [pkg] is in [packages]; package names compare case-insensitively. */
private fun listed(
    pkg: String?,
    packages: Set<String>,
): Boolean = packages.any { it.equals(pkg, ignoreCase = true) }
