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
 * classified by [Risks], the same reading the screen format marks ⚠ with. The tool's [RiskClass]
 * is a floor: a tool declared SPEND always resolves to SPEND. The amount is read once the kind is
 * final, so every way of spending carries it ([Reading.price]).
 */
internal class RulesResolver(
    private val reading: Reading,
) : IntentResolver {
    private val screenIntents = ScreenIntents(reading)

    override fun resolve(
        call: ToolCall,
        risk: RiskClass,
        screen: ScreenSnapshot?,
    ): ActionIntent {
        val step = Steps.parse(call)
        val intent = floor(base(step, risk, screen), risk)
        return intent.copy(amount = reading.price(step, intent, screen).money)
    }

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

            // Only a package name launches an app; a link opens no app the policy can name.
            is Step.Launch -> {
                ActionIntent(IntentKind.NAVIGATE, step.target.takeIf { !step.link && Text.isPackage(it) })
            }

            is Step.Intent -> {
                ActionIntent(IntentKind.NAVIGATE, Pages.SETTINGS.takeIf { step.kind == SystemIntentKind.SETTINGS })
            }

            is Step.Confirmation -> {
                confirmation(step)
            }

            is ScreenStep -> {
                when {
                    screen == null -> blind(step)
                    !reading.readable(screen) -> blind(step).copy(pkg = screen.pkg)
                    else -> screenIntents.resolve(step, screen)
                }
            }
        }

    /**
     * Without a screen, or on one beyond the limits ([Reading.readable]), nothing can be looked
     * up: typing that submits and Enter count as reaching out.
     */
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
        val named = reading.lexicon.classify(step.action)
        val kind =
            when {
                step.amount != null -> IntentKind.SPEND
                named != null -> named.kind
                else -> IntentKind.OUTWARD
            }
        return ActionIntent(kind)
    }

    private fun floor(
        intent: ActionIntent,
        risk: RiskClass,
    ): ActionIntent {
        val minimum = FLOORS[risk] ?: return intent
        val raise = intent.fieldClass !in Fields.HAND_OFF && severity(intent.kind) < severity(minimum)
        return if (raise) intent.copy(kind = minimum, fieldClass = null) else intent
    }
}

private val FLOORS =
    mapOf(
        RiskClass.SPEND to IntentKind.SPEND,
        RiskClass.OUTWARD to IntentKind.OUTWARD,
        RiskClass.SETUP to IntentKind.SETTINGS,
    )
