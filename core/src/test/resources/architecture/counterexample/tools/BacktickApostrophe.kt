package dev.phoneagent.core.tools

// Rejected: the apostrophe in a backticked name starts no char literal, so the code after it counts.
internal class ApostropheBox<`T'`>

internal val apostropheConfig: Any =
    dev.phoneagent.core.assembly
        .ProductConfig(setOf("a.b"), setOf("A"))
