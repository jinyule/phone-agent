package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.RiskLexicon

/**
 * The policy capability (phase1 §3.6, §6; rules in docs/design/policy-rules.md). The three parts
 * are built from one vocabulary and one app list, so the ⚠ the screen format shows, the intent
 * the runtime records and the verdict always agree. All three are pure and never throw.
 *
 * Construction loads the hard-refusal app list and fails loudly ([IllegalArgumentException]) when
 * it or [ownPackages] is invalid.
 *
 * @param ownPackages this app's package names (all build variants); the agent never operates them.
 */
public class PolicyRules(
    ownPackages: Set<String>,
) {
    init {
        require(ownPackages.isNotEmpty()) { "PolicyRules needs the app's own package names" }
        val invalid = ownPackages.filterNot(Text.PACKAGE::matches)
        require(invalid.isEmpty()) { "Invalid own package names: $invalid" }
    }

    private val rules = RulesLexicon()
    private val blocked = BlockedApps.DEFAULT

    /** Which elements are risky to activate; the screen format marks them ⚠. */
    public val lexicon: RiskLexicon = rules

    /** What a call would do; the runtime calls it right before [engine]. */
    public val resolver: IntentResolver = RulesResolver(rules, blocked)

    /** The decision: the last guard of `toolPreExecute`. */
    public val engine: PolicyEngine = RulesEngine(HardRules(ownPackages, blocked))
}
