package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.openai.ChatCompletionsAdapter

// Rejected: the agent names a concrete capability instead of the ChatModel contract.
class KnowsAdapter(
    val model: ChatModel = ChatCompletionsAdapter(),
)
