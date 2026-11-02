package dev.phoneagent.core.tools

// Rejected: a fully qualified call with type arguments, nested ones included.
public val rules: Map<String, List<Int>> =
    dev.phoneagent.core.policy.rules
        .emptyRules<Map<String, List<Int>>>()
