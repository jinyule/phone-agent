package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId

/**
 * A minimal adapter for [ChatModelContract]: maps reasoning to `reasoning_effort`, refuses foreign
 * wire settings, answers "ok". Copies the live headers it is built from, as `ModelRouter` freezes
 * a profile, and supports [levels]. A [flaw] breaks one rule of the contract, so that the contract
 * itself is known to fail.
 */
internal class FakeChatModel(
    live: MutableMap<String, String>,
    levels: List<ReasoningLevel>? = listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
    private val flaw: Flaw? = null,
) : ChatModel {
    /** One way to break [ChatModelContract]. */
    enum class Flaw {
        /** Maps the level asked for instead of the clamped one. */
        MAPS_BEFORE_CLAMPING,

        /** Adds a header, in place, to its one header map, which all the settings it gave out share. */
        CHANGES_HEADERS_IN_PLACE,

        /** Gives out a copy of its headers each time, and adds a header in place to the copy it sends. */
        CHANGES_SENT_SETTINGS,

        /** Replaces its headers with a new map holding one more header on every request. */
        CHANGES_LATER_SETTINGS,

        /** Keeps the live headers instead of a copy, so an edit of the profile shows in its settings. */
        SHARES_LIVE_HEADERS,
    }

    /** Requests sent so far. */
    var sent = 0
        private set

    override val info =
        ModelInfo(
            ProviderId("fake"),
            ModelId("fake-1"),
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelCapabilities(tools = true, vision = false, contextTokens = 1000, reasoningLevels = levels),
        )

    private var headers: Map<String, String> =
        when {
            flaw == Flaw.SHARES_LIVE_HEADERS -> live
            flaw == Flaw.CHANGES_HEADERS_IN_PLACE -> live.toMutableMap()
            else -> live.toMap()
        }

    override fun prepare(reasoning: ReasoningLevel?): RequestSettings {
        val effective = reasoning?.clampTo(info.capabilities.reasoningLevels)
        val mapped = if (flaw == Flaw.MAPS_BEFORE_CLAMPING) reasoning else effective
        return RequestSettings(
            effective,
            WireProfile(
                mappingVersion = MAPPING_VERSION,
                credential = CredentialKind.API_KEY,
                headers = if (flaw == Flaw.CHANGES_SENT_SETTINGS) headers.toMutableMap() else headers,
                reasoningEffort = mapped?.let { WireSetting("reasoning_effort", it.name.lowercase()) },
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
        when {
            flaw == Flaw.CHANGES_HEADERS_IN_PLACE -> (headers as MutableMap<String, String>)["x-attempt"] = "$sent"
            flaw == Flaw.CHANGES_SENT_SETTINGS -> (wire.headers as MutableMap<String, String>)["x-attempt"] = "$sent"
            flaw == Flaw.CHANGES_LATER_SETTINGS -> headers = headers + ("x-attempt" to "$sent")
        }
        return ChatResponse(listOf(Part.Text("ok")), StopReason.END_TURN)
    }

    companion object {
        const val MAPPING_VERSION = 2

        /**
         * A [ChatModelContract.Subject] around the fake [build] makes from fresh live headers; the
         * subject's hook edits those headers as a user editing the profile would.
         */
        fun subject(
            build: (MutableMap<String, String>) -> FakeChatModel = { FakeChatModel(it) },
        ): ChatModelContract.Subject {
            val live = mutableMapOf("x-client" to "fake")
            val model = build(live)
            return ChatModelContract.Subject(model, { model.sent }) {
                live["x-client"] = "edited"
                live["x-extra"] = "added"
            }
        }
    }
}
