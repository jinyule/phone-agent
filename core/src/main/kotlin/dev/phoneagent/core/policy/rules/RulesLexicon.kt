package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * The shared risk vocabulary (phase1 §4, policy-rules.md §3): the risk of a tap by ref on an
 * element, as [Risks.activation] decides it for the resolver too. The screen format marks these
 * answers ⚠, so the mark and the verdict agree.
 */
internal class RulesLexicon(
    private val risks: Risks,
) : RiskLexicon {
    override fun activationRisk(
        element: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk? = risks.activation(element, screen).risk
}
