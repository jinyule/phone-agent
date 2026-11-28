package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.anthropic.wire.Messages
import dev.phoneagent.core.ports.Clock

// Allowed: contracts, and the capability's own subpackages.
abstract class Allowed(
    val id: ModelId,
    val clock: Clock,
    val messages: Messages,
) : ChatModel
