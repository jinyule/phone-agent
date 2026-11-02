package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ScreenSnapshot

/**
 * How the policy reads screens, built once from [tuning] and shared by the lexicon, the resolver
 * and the engine, so all three see the same words, pages, fields and risks.
 */
internal class Reading(
    val tuning: PolicyTuning,
) {
    val lexicon: Lexicon = Lexicon(tuning)
    val amounts: Amounts = Amounts(tuning)
    val pages: Pages = Pages(tuning, amounts)
    val settings: SettingsPages = SettingsPages(tuning, pages)
    val fields: Fields = Fields(tuning, lexicon, pages)
    val risks: Risks = Risks(Controls(lexicon, pages, settings, fields))

    /**
     * What a spend is about (policy-rules.md §4); the resolver puts its money on the intent and the
     * engine reads it again on the re-check, so an unclear amount is never taken as unchanged.
     * `request_confirmation` states its own amount when it gives one; otherwise the label of the
     * control acted on and the screen decide. Not a spend: [Price.Absent].
     */
    fun price(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Price {
        val stated = (step as? Step.Confirmation)?.amount
        return when {
            intent.kind != IntentKind.SPEND -> Price.Absent
            stated != null -> Price.Known(stated)
            else -> amounts.forSpend(label(intent, screen), screen)
        }
    }

    /** The label of the control [intent] acts on, or "" when there is none. */
    private fun label(
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): String {
        val target = intent.target
        return if (screen == null || target == null) "" else Elements.label(target, screen)
    }
}
