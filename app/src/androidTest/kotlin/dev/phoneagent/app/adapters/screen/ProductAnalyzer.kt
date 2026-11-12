package dev.phoneagent.app.adapters.screen

import android.content.Context
import dev.phoneagent.app.task.productConfig
import dev.phoneagent.core.assembly.assemble
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.ports.SnapshotAnalyzer

/** The analyzer the app's tasks build their ScreenPort with: the product assembly's, for a model that sees images. */
internal fun productAnalyzer(context: Context): SnapshotAnalyzer =
    assemble(CapabilitiesOnly, productConfig(context)).analyzer

/** A main model that only states what it can do; nothing here sends requests. */
private object CapabilitiesOnly : ChatModel {
    override val info: ModelInfo =
        ModelInfo(
            ProviderId("device-test"),
            ModelId("device-test"),
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelCapabilities(tools = true, vision = true, contextTokens = CONTEXT_TOKENS),
        )

    override fun prepare(reasoning: ReasoningLevel?): RequestSettings = error("the device test sends no requests")

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse = error("the device test sends no requests")

    private const val CONTEXT_TOKENS = 128_000
}
