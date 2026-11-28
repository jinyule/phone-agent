package dev.phoneagent.core.policy

import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * The one risk vocabulary shared by the screen format and the policy (phase1 §4, §6): which
 * elements are risky to activate. The formatter marks them ⚠ and the engine classifies a tap on
 * them the same way, so the model is warned exactly where a confirmation rule applies. Whether a
 * card is actually shown still depends on the level and the task's history (§6).
 *
 * Implemented by the policy capability; the formatter and the engine both depend only on this
 * contract. Pure (same element and screen, same answer) and never throws.
 */
public fun interface RiskLexicon {
    /** The risk of tapping or otherwise activating [element] on [screen], or `null` when ordinary. */
    public fun activationRisk(
        element: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk?
}

/**
 * Why an element is risky.
 *
 * @property kind one of [RISKY_KINDS].
 * @property matched the lexicon entry or rule that matched (e.g. "发送"), non-blank; for the
 *   trajectory and logs, not shown to the model.
 */
public data class ElementRisk(
    val kind: IntentKind,
    val matched: String,
) {
    init {
        require(kind in RISKY_KINDS) { "Element risk must be one of $RISKY_KINDS, got $kind" }
        require(matched.isNotBlank()) { "Element risk needs the matched entry" }
    }

    /** Constants. */
    public companion object {
        /** Intent kinds that make an element risky: the ones the §6 matrix may confirm. */
        public val RISKY_KINDS: Set<IntentKind> =
            setOf(IntentKind.OUTWARD, IntentKind.SPEND, IntentKind.DELETE, IntentKind.SETTINGS)
    }
}
