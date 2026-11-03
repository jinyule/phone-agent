package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModelContract
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.openai.Kit.base
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop

/**
 * [ChatModelContract] on the OpenAI adapters (WP01 r4 §7 item 4): each protocol, and Responses
 * with ChatGPT plan sign-in, whose snapshot differs (no output cap). The model declares two of the
 * levels, so the others clamp; the live settings the hook changes are the profile's header map and
 * the capabilities' level list, both handed in as mutable collections.
 */
abstract class OpenAiModelContractTest : ChatModelContract() {
    /** The adapter's protocol. */
    protected abstract val protocol: ModelProtocol

    /** The profile's credential. */
    protected abstract val credential: Credential

    /** The valid final reply the server gives every request. */
    protected abstract val reply: String

    @StartStop
    private val server: MockWebServer = Kit.server()

    override fun subject(): Subject {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "text/event-stream")
                        .body(reply)
                        .build()
            }
        val headers = mutableMapOf("X-Team" to "phone")
        val levels = mutableListOf(ReasoningLevel.LOW, ReasoningLevel.HIGH)
        val profile = Kit.profile(server.base(), protocol, credential, headers = headers)
        val model =
            Kit.adapters().model(
                profile,
                Kit.MODEL,
                Kit.capabilities(levels = levels),
                Kit.blobs(),
                WireOptions(),
            )
        return Subject(model, { server.requestCount }) {
            headers["X-Team"] = "changed"
            levels.clear()
        }
    }
}

class ChatCompletionsModelContractTest : OpenAiModelContractTest() {
    override val protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS
    override val credential: Credential = Kit.KEY
    override val reply: String =
        Kit.sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
}

class ResponsesModelContractTest : OpenAiModelContractTest() {
    override val protocol: ModelProtocol = ModelProtocol.OPENAI_RESPONSES
    override val credential: Credential = Kit.KEY
    override val reply: String = RESPONSES_REPLY
}

class ResponsesPlanModelContractTest : OpenAiModelContractTest() {
    override val protocol: ModelProtocol = ModelProtocol.OPENAI_RESPONSES
    override val credential: Credential = Kit.PLAN
    override val reply: String = RESPONSES_REPLY
}

private const val RESPONSES_REPLY =
    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":" +
        "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n"
