package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ChatModelContract
import dev.phoneagent.core.model.anthropic.Kit.base
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach

/**
 * [ChatModelContract] on the Anthropic adapter: a model on a mock server that answers every
 * request with a short text reply. Its capabilities are Claude Opus 5.5's, whose levels start at
 * `low`, so `minimal` clamps. The live settings are the profile's headers, which the hook edits.
 */
class AnthropicChatModelContractTest : ChatModelContract() {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    override fun subject(): Subject {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "text/event-stream")
                        .body(Kit.TEXT_REPLY)
                        .build()
            }
        val live = mutableMapOf("x-client" to "phone-agent")
        val profile = Kit.profile(server.base(), headers = live)
        val model = Kit.adapters().model(profile, Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
        return Subject(model, { server.requestCount }) {
            live["x-client"] = "edited"
            live["x-extra"] = "added"
        }
    }
}
