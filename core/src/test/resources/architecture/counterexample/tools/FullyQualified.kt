package dev.phoneagent.core.tools

// Rejected: a fully qualified reference to another capability (policy.rules), without an import.
val rules =
    dev.phoneagent.core.policy.rules
        .Rules()

// Not counted: dev.phoneagent.core.screen.Formatter in a comment, or in "dev.phoneagent.core.screen.Formatter".
val text = "dev.phoneagent.core.screen.Formatter"
