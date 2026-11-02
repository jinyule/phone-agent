package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.RiskLexicon

/**
 * The policy capability (phase1 §3.6, §6; rules in docs/design/policy-rules.md). The three parts
 * are built from one reading of screens and one app list, so the ⚠ the screen format shows, the
 * intent the runtime records and the verdict always agree. All three are pure and never throw.
 *
 * Construction loads the hard-refusal app list and fails loudly ([IllegalArgumentException]) when
 * it, [ownPackages] or [ownLabels] is invalid. Both sets are copied: changing them afterwards has
 * no effect.
 *
 * @param ownPackages this app's package names (all build variants); the agent never operates them.
 * @param ownLabels the names this app is shown under (launcher label, all languages), to know its
 *   icon on a home screen and its page in the system settings.
 * @param tuning the heuristic thresholds; the defaults are the reviewed ones.
 */
public class PolicyRules(
    ownPackages: Set<String>,
    ownLabels: Set<String>,
    tuning: PolicyTuning = PolicyTuning(),
) {
    init {
        require(ownPackages.isNotEmpty()) { "PolicyRules needs the app's own package names" }
        val invalid = ownPackages.filterNot(Text::isPackage)
        require(invalid.isEmpty()) { "Invalid own package names: $invalid" }
        require(ownLabels.isNotEmpty()) { "PolicyRules needs the app's own labels" }
        require(ownLabels.none(String::isBlank)) { "Own labels must not be blank: $ownLabels" }
    }

    private val reading = Reading(tuning)
    private val apps = RefusedApps(ownPackages, ownLabels, BlockedApps.DEFAULT)

    /** Which elements are risky to activate; the screen format marks them ⚠. */
    public val lexicon: RiskLexicon = RulesLexicon(reading.risks)

    /** What a call would do; the runtime calls it right before [engine]. */
    public val resolver: IntentResolver = RulesResolver(reading)

    /** The decision: the last guard of `toolPreExecute`. */
    public val engine: PolicyEngine = RulesEngine(HardRules(apps, reading), Cards(reading), reading)
}
