// Allowed: the shared risk vocabulary RiskLexicon is a contract (core.policy).
// Rejected: its implementation ChineseRiskLexicon is another capability (policy.rules).
package dev.phoneagent.core.screen

import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.policy.rules.ChineseRiskLexicon

fun marker(lexicon: RiskLexicon = ChineseRiskLexicon()): RiskLexicon = lexicon
