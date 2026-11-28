package dev.phoneagent.core.agent

import dev.phoneagent.core.assembly.ProductConfig

// Rejected: the loop is assembled; it does not know the product assembly.
class KnowsAssembly(
    val config: ProductConfig,
)
