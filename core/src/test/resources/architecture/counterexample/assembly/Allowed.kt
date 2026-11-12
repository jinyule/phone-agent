package dev.phoneagent.core.assembly

import dev.phoneagent.core.agent.AgentSetup
import dev.phoneagent.core.policy.rules.PolicyRules
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.tools.PhaseOneTools

// Allowed: the assembly wires contracts and capabilities together.
class Allowed(
    val rules: PolicyRules,
    val screens: ScreenComponents,
    val tools: PhaseOneTools,
    val files: SessionFiles,
    val setup: AgentSetup,
)
