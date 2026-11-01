package dev.phoneagent.core.testing

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * A [ChatModel] that plays a script, one [Script] per request, and records every request. Shared
 * by all core tests; the replay model of the regression harness builds on it.
 *
 * Builders cover the usual cases: a reply (its text parts are streamed as deltas first), an error
 * (after optional delivered text), a delay, and a hang that delivers text and then waits for a
 * [CompletableDeferred] (or forever) — the barrier for steer, stop and timeout tests. A request
 * beyond the script fails the test with an [AssertionError].
 */
class FakeChatModel(
    override val info: ModelInfo = INFO,
) : ChatModel {
    /** What one request does. */
    fun interface Script {
        suspend fun play(
            request: ChatRequest,
            onDelta: (Delta) -> Unit,
        ): ChatResponse
    }

    private val script = ArrayDeque<Script>()
    private val startedCount = MutableStateFlow(0)

    /** Every request, in order. */
    val requests = mutableListOf<ChatRequest>()

    /** Requests started so far. */
    val started: StateFlow<Int> get() = startedCount

    /** Scripts not played yet. */
    val remaining: Int get() = script.size

    fun then(next: Script): FakeChatModel = apply { script.addLast(next) }

    /** Streams [response]'s text parts as deltas, then returns it. */
    fun replies(response: ChatResponse): FakeChatModel =
        then { _, onDelta ->
            response.parts.filterIsInstance<Part.Text>().forEach { onDelta(Delta.Text(it.text)) }
            response
        }

    /** A final answer [text]. */
    fun answers(
        text: String,
        stopReason: StopReason = StopReason.END_TURN,
    ): FakeChatModel = replies(ChatResponse(listOf(Part.Text(text)), stopReason))

    /** A reply with [calls] (after [text], when given). */
    fun calls(
        vararg calls: Part.ToolCall,
        text: String? = null,
        stopReason: StopReason = StopReason.TOOL_USE,
    ): FakeChatModel = replies(ChatResponse(listOfNotNull(text?.let(Part::Text)) + calls, stopReason))

    /** Delivers [delivered] as one delta, then throws [error]. */
    fun fails(
        error: ModelError,
        delivered: String? = null,
    ): FakeChatModel =
        then { _, onDelta ->
            delivered?.let { onDelta(Delta.Text(it)) }
            throw error
        }

    /** Delivers [delivered], waits [delayMs] (virtual time in tests), then answers [text]. */
    fun answersAfter(
        delayMs: Long,
        text: String,
        delivered: String? = null,
    ): FakeChatModel =
        then { _, onDelta ->
            delivered?.let { onDelta(Delta.Text(it)) }
            delay(delayMs)
            ChatResponse(listOf(Part.Text(text)), StopReason.END_TURN)
        }

    /** Delivers [delivered], then waits for [release] (forever when `null`, until cancelled). */
    fun hangs(
        delivered: String? = null,
        release: CompletableDeferred<ChatResponse>? = null,
    ): FakeChatModel =
        then { _, onDelta ->
            delivered?.let { onDelta(Delta.Text(it)) }
            release?.await() ?: awaitCancellation()
        }

    /** Suspends until request number [n] (from 1) has started. */
    suspend fun awaitStarted(n: Int) {
        startedCount.first { it >= n }
    }

    /** Fails unless every script was played. */
    fun assertConsumed() {
        if (script.isNotEmpty()) throw AssertionError("${script.size} scripted replies were never requested")
    }

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        requests += request
        startedCount.value += 1
        val next = script.removeFirstOrNull() ?: throw AssertionError("unexpected request #${requests.size}")
        return next.play(request, onDelta)
    }

    companion object {
        val INFO =
            ModelInfo(
                ProviderId("fake"),
                ModelId("fake-model"),
                ModelProtocol.ANTHROPIC_MESSAGES,
                ModelCapabilities(tools = true, vision = true, contextTokens = 200_000),
            )

        /** A tool call part with raw [argsJson]. */
        fun call(
            id: String,
            name: String,
            argsJson: String = """{"reason":"测试"}""",
        ): Part.ToolCall = Part.ToolCall(ToolCallId(id), name, argsJson)
    }
}
