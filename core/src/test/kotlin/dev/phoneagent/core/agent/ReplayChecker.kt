package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.deriveMessages

/**
 * Checks phase1 §3.9 rule 1 (model-visible ⟺ recorded) at every request of the models it wraps:
 * it reads the session log back from storage (so every record went through the codec), rebuilds
 * the request as `latest request/header`.toRequest(`deriveMessages`(log)) and keeps any difference
 * from the request actually sent in [mismatches].
 */
internal class ReplayChecker(
    private val files: SessionFiles,
    private val strict: Boolean = false,
) {
    /** Requests that the log does not rebuild exactly, described. */
    val mismatches = mutableListOf<String>()

    /** Requests checked so far. */
    var checked: Int = 0
        private set

    /** [model], with every request checked before it is sent on. */
    fun wrap(model: ChatModel): ChatModel =
        object : ChatModel by model {
            override suspend fun complete(
                request: ChatRequest,
                onDelta: (Delta) -> Unit,
            ): ChatResponse {
                check(request)
                return model.complete(request, onDelta)
            }
        }

    private suspend fun check(request: ChatRequest) {
        checked += 1
        val records = SessionStore.read(files).records
        val header = records.last { it.event is RequestHeader }.event as RequestHeader
        val rebuilt = header.toRequest(deriveMessages(records))
        if (rebuilt != request) {
            mismatches += "request #$checked:\n  rebuilt ${rebuilt.messages}\n  sent    ${request.messages}"
            if (strict) throw AssertionError(mismatches.last())
        }
    }
}
