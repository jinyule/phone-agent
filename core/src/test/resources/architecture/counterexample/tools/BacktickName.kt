package dev.phoneagent.core.tools

// Rejected: the assembly reached by fully qualified names with a segment quoted in backticks.
fun assembled(model: dev.phoneagent.core.model.ChatModel) =
    dev.phoneagent.core.`assembly`.assemble(
        model,
        dev.phoneagent.core.`assembly`
            .ProductConfig(setOf("a.b"), setOf("A")),
    )
