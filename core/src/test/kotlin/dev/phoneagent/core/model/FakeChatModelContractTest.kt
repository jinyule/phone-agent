package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId

/** Runs [ChatModelContract] on a minimal adapter, so the contract itself is known to pass and to fail. */
class FakeChatModelContractTest : ChatModelContract() {
    override fun subject(): Subject {
        val live = mutableMapOf("x-client" to "fake")
        val model = FakeChatModel(live)
        return Subject(model, { model.sent }) {
            live["x-client"] = "edited"
            live["x-extra"] = "added"
        }
    }

    /**
     * Maps reasoning to `reasoning_effort`, refuses foreign wire settings, answers "ok". Freezes the
     * live headers it is built from, as `ModelRouter` freezes a profile.
     */
    private class FakeChatModel(
        live: Map<String, String>,
    ) : ChatModel {
        var sent = 0

        override val info =
            ModelInfo(
                ProviderId("fake"),
                ModelId("fake-1"),
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                ModelCapabilities(
                    tools = true,
                    vision = false,
                    contextTokens = 1000,
                    reasoningLevels = listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
                ),
            )

        private val headers = live.toMap()

        override fun prepare(reasoning: ReasoningLevel?): RequestSettings {
            val effective = reasoning?.clampTo(info.capabilities.reasoningLevels)
            return RequestSettings(
                effective,
                WireProfile(
                    mappingVersion = MAPPING_VERSION,
                    credential = CredentialKind.API_KEY,
                    headers = headers,
                    reasoningEffort = effective?.let { WireSetting("reasoning_effort", it.name.lowercase()) },
                    maxTokensField = "max_tokens",
                ),
            )
        }

        override suspend fun complete(
            request: ChatRequest,
            onDelta: (Delta) -> Unit,
        ): ChatResponse {
            val wire = request.wire
            if (wire.mappingVersion != MAPPING_VERSION || wire.credential != CredentialKind.API_KEY) {
                throw ModelError.InvalidRequest(null, "wire settings of another adapter")
            }
            sent++
            return ChatResponse(listOf(Part.Text("ok")), StopReason.END_TURN)
        }
    }

    private companion object {
        const val MAPPING_VERSION = 2
    }
}
