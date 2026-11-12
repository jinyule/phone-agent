package dev.phoneagent.core.tools

// Rejected: a capability reaching the assembly, here fully qualified and without an import.
fun configured() =
    dev.phoneagent.core.assembly
        .ProductConfig(setOf("a.b"), setOf("A"))
