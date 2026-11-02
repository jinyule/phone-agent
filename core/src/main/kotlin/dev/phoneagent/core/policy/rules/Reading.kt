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
            screen != null && !readable(screen) -> Price.Unclear
            else -> amounts.forSpend(label(intent, screen), screen)
        }
    }

    /**
     * Whether [screen] is within the limits of [tuning] (policy-rules.md §8): no more elements
     * and OCR lines, characters of text and levels of tree than allowed, and no cycle. Reading a
     * screen within them looks its elements up in [ScreenIndex] rather than comparing them in pairs
     * or walking ancestors again, so the limits bound its cost; a screen beyond them is not read
     * at all, and what depends on it is treated as unknown.
     */
    fun readable(screen: ScreenSnapshot): Boolean {
        if (screen.elements.size + screen.ocr.orEmpty().size > tuning.maxElements) return false
        val index = ScreenIndex.of(screen)
        return index.textLength <= tuning.maxScreenText && index.depth <= tuning.maxDepth
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
