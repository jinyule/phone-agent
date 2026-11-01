package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall

/**
 * Resolves calls of the phase1 §5 tools into [ActionIntent]s (policy-rules.md §2). Every tap is
 * classified by [lexicon], the same vocabulary the screen format marks ⚠ with. The tool's
 * [RiskClass] is a floor: a tool declared SPEND always resolves to SPEND.
 */
internal class RulesResolver(
    lexicon: RulesLexicon,
    private val blocked: BlockedApps,
) : IntentResolver {
    private val screenIntents = ScreenIntents(lexicon)

    override fun resolve(
        call: ToolCall,
        risk: RiskClass,
        screen: ScreenSnapshot?,
    ): ActionIntent = floor(base(Steps.parse(call), risk, screen), risk)

    private fun base(
        step: Step,
        risk: RiskClass,
        screen: ScreenSnapshot?,
    ): ActionIntent =
        when (step) {
            Step.Talk -> {
                ActionIntent(IntentKind.READ)
            }

            // A tool outside the vocabulary is as harmless as it declares only when it only reads.
            Step.Unknown -> {
                ActionIntent(if (risk == RiskClass.READ) IntentKind.READ else IntentKind.NAVIGATE)
            }

            Step.Observe -> {
                ActionIntent(IntentKind.READ, screen?.pkg)
            }

            Step.Malformed -> {
                ActionIntent(IntentKind.NAVIGATE, screen?.pkg)
            }

            is Step.Launch -> {
                ActionIntent(IntentKind.NAVIGATE, launched(step))
            }

            is Step.Intent -> {
                ActionIntent(
                    IntentKind.NAVIGATE,
                    Pages.SETTINGS.takeIf {
                        step.kind ==
                            SystemIntentKind.SETTINGS
                    },
                )
            }

            is Step.Confirmation -> {
                confirmation(step)
            }

            is ScreenStep -> {
                if (screen == null) blind(step) else screenIntents.resolve(step, screen)
            }
        }

    private fun launched(step: Step.Launch): String? =
        if (step.link) Launches.linkPackage(step.target) else Launches.packageOf(step.target, blocked)

    /** Without a screen nothing can be looked up: typing that submits and Enter count as reaching out. */
    private fun blind(step: ScreenStep): ActionIntent {
        val sends = (step is Step.Type && step.submit) || step == Step.Key(SystemKey.ENTER)
        return when {
            sends -> ActionIntent(IntentKind.OUTWARD)
            step is Step.Type -> ActionIntent(IntentKind.INPUT, fieldClass = FieldClass.PLAIN)
            else -> ActionIntent(IntentKind.NAVIGATE)
        }
    }

    /** `request_confirmation`: SPEND when it states an amount, else what its action text names, else OUTWARD. */
    private fun confirmation(step: Step.Confirmation): ActionIntent {
        val amount = step.amount?.let { Amounts.find(it, bare = true) }
        val named = Lexicon.classify(step.action)
        val kind =
            when {
                amount != null -> IntentKind.SPEND
                named != null -> named.kind
                else -> IntentKind.OUTWARD
            }
        return ActionIntent(kind, amount = amount)
    }

    private fun floor(
        intent: ActionIntent,
        risk: RiskClass,
    ): ActionIntent {
        val minimum = FLOORS[risk] ?: return intent
        val raise = intent.fieldClass !in Fields.HAND_OFF && SEVERITY.indexOf(intent.kind) < SEVERITY.indexOf(minimum)
        return if (raise) intent.copy(kind = minimum, fieldClass = null) else intent
    }
}

private val FLOORS =
    mapOf(
        RiskClass.SPEND to IntentKind.SPEND,
        RiskClass.OUTWARD to IntentKind.OUTWARD,
        RiskClass.SETUP to IntentKind.SETTINGS,
    )

/** Least to most severe, for `RulesResolver.floor`. */
private val SEVERITY =
    listOf(
        IntentKind.READ,
        IntentKind.NAVIGATE,
        IntentKind.INPUT,
        IntentKind.SETTINGS,
        IntentKind.DELETE,
        IntentKind.OUTWARD,
        IntentKind.SPEND,
    )
