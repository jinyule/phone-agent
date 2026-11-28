package dev.phoneagent.core.tools

// Rejected: a fully qualified call of a top-level function in another capability.
val verdicts =
    dev.phoneagent.core.policy.rules
        .evaluateAll()
