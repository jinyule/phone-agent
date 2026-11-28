package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.Usage
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayChatModelTest {
    private val records = ReplaySamples.records
    private val requests = ReplaySamples.requests

    private fun model(script: List<ScriptedOutcome> = ModelScript.of(records, requests)) =
        ReplayChatModel(ReplaySamples.info, requests, script)

    /** The samples with the first reply (seq 6) replaced by [events]. */
    private fun replacingFirstReply(vararg events: SessionEvent): List<SessionRecord> {
        val before = records.take(5)
        val inserted = events.mapIndexed { i, e -> SessionRecord(6L + i, 0, e) }
        val after = records.drop(6).map { it.copy(seq = it.seq + events.size - 1) }
        return before + inserted + after
    }

    private fun shifted(by: Int) = listOf(requests[0], requests[1].copy(logSeq = requests[1].logSeq + by))

    private val overloaded = AssistantAttempt(1, ModelErrorKind.OVERLOADED, "provider overloaded", retryAfterMs = 500)

    private fun interrupted(text: String) =
        AssistantMessage(
            1,
            ReplaySamples.provider,
            ReplaySamples.model,
            listOf(Part.Text(text)),
            null,
            interrupted = true,
        )

    // ModelScript

    @Test
    fun `each request gets the reply recorded after it`() {
        assertEquals(
            listOf(
                ScriptedOutcome.Reply(1, ChatResponse(ReplaySamples.firstReply, StopReason.TOOL_USE, Usage(100, 10))),
                ScriptedOutcome.Reply(2, ChatResponse(ReplaySamples.answer, StopReason.END_TURN, Usage(200, 8))),
            ),
            ModelScript.of(records, requests),
        )
    }

    @Test
    fun `an interrupted reply followed by its attempt is a failure after a prefix`() {
        val script = ModelScript.of(replacingFirstReply(interrupted("我先"), overloaded), shifted(1))
        assertEquals(ScriptedOutcome.Failure(1, "我先", overloaded), script.first())
    }

    @Test
    fun `an attempt alone is a failure without a prefix`() {
        assertEquals(
            ScriptedOutcome.Failure(1, null, overloaded),
            ModelScript.of(replacingFirstReply(overloaded), requests).first(),
        )
    }

    @Test
    fun `an interrupted reply without an attempt never finished`() {
        assertEquals(
            ScriptedOutcome.Unfinished(1, "我先"),
            ModelScript.of(replacingFirstReply(interrupted("我先")), requests).first(),
        )
    }

    @Test
    fun `a request with no outcome never finished`() {
        val script = ModelScript.of(records.take(5), listOf(requests[0]))
        assertEquals(listOf(ScriptedOutcome.Unfinished(1, null)), script)
    }

    @Test
    fun `an outcome before the first request fails`() {
        assertThrows<IllegalArgumentException> {
            ModelScript.of(
                records,
                listOf(requests[0].copy(logSeq = 6), requests[1]),
            )
        }
    }

    @Test
    fun `two replies to one request fail`() {
        val reply = records[5].event
        assertThrows<IllegalArgumentException> { ModelScript.of(replacingFirstReply(reply, reply), shifted(1)) }
    }

    @Test
    fun `a record after a failure fails`() {
        assertThrows<IllegalArgumentException> {
            ModelScript.of(
                replacingFirstReply(overloaded, overloaded),
                shifted(1),
            )
        }
    }

    @Test
    fun `an interrupted reply of more than its text fails`() {
        val message =
            AssistantMessage(
                1,
                ReplaySamples.provider,
                ReplaySamples.model,
                listOf(Part.Text("我"), Part.Text("先")),
                null,
                interrupted = true,
            )
        assertThrows<IllegalArgumentException> { ModelScript.of(replacingFirstReply(message), requests) }
    }

    @Test
    fun `a reply from another model than the request went to fails`() {
        val other = (records[5].event as AssistantMessage).copy(model = ModelId("claude-haiku-5-5"))
        assertThrows<IllegalArgumentException> { ModelScript.of(replacingFirstReply(other), requests) }
    }

    @Test
    fun `a failure whose message cannot be rebuilt fails when the script is read`() {
        assertThrows<IllegalArgumentException> {
            ModelScript.of(replacingFirstReply(overloaded.copy(message = "busy")), requests)
        }
    }

    @Test
    fun `a reply with a malformed template fails when the script is read`() {
        val call = Part.ToolCall(ReplaySamples.call, "phone_tap", """{"ref":"{{fromRequest:e\\d+}}"}""")
        val reply = (records[5].event as AssistantMessage).copy(parts = listOf(call))
        assertThrows<IllegalArgumentException> { ModelScript.of(replacingFirstReply(reply), requests) }
    }

    // ReplayChatModel

    @Test
    fun `a reply is streamed as its text and returned as recorded`() =
        runTest {
            val deltas = mutableListOf<Delta>()
            val reply = model().complete(requests[0].request) { deltas += it }
            assertEquals(ChatResponse(ReplaySamples.firstReply, StopReason.TOOL_USE, Usage(100, 10)), reply)
            assertEquals(listOf<Delta>(Delta.Text("我看一下")), deltas)
        }

    @Test
    fun `a templated reply is resolved against the live request and remembered`() =
        runTest {
            val template = """{"ref":"{{fromRequest:(e\\d+) 张三}}"}"""
            val call = Part.ToolCall(ReplaySamples.call, "phone_tap", template)
            val script =
                listOf(
                    ScriptedOutcome.Reply(1, ChatResponse(listOf(call), StopReason.TOOL_USE)),
                    ScriptedOutcome.Unfinished(2, null),
                )
            val model = model(script)
            val reply = model.complete(requests[1].request)
            assertEquals(listOf(call.copy(argsJson = """{"ref":"e2"}""")), reply.parts)
            assertEquals(
                mapOf(ReplaySamples.call to RequestTemplates.Resolved(template, """{"ref":"e2"}""")),
                model.templates,
            )
        }

    @Test
    fun `a failure streams its prefix and throws the rebuilt error`() =
        runTest {
            val deltas = mutableListOf<Delta>()
            val script = listOf(ScriptedOutcome.Failure(1, "我先", overloaded), ScriptedOutcome.Unfinished(2, null))
            val model = model(script)
            val error = assertThrows<ModelError.Overloaded> { model.complete(requests[0].request) { deltas += it } }
            assertEquals(500L, error.retryAfterMs)
            assertEquals(listOf<Delta>(Delta.Text("我先")), deltas)
        }

    @Test
    fun `an unfinished reply streams its prefix and waits to be cancelled`() =
        runTest {
            val deltas = mutableListOf<Delta>()
            val model = model(listOf(ScriptedOutcome.Unfinished(1, "我先"), ScriptedOutcome.Unfinished(2, null)))
            val call = async { model.complete(requests[0].request) { deltas += it } }
            advanceUntilIdle()
            assertTrue(call.isActive)
            assertEquals(listOf<Delta>(Delta.Text("我先")), deltas)
            call.cancel()
        }

    @Test
    fun `a request beyond the script fails the replay`() =
        runTest {
            val model = model()
            model.complete(requests[0].request)
            model.complete(requests[1].request)
            val failure = assertThrows<AssertionError> { model.complete(requests[1].request) }
            assertEquals("script exhausted at request #3", failure.message)
        }

    @Test
    fun `prepare gives the settings of the next recorded request`() {
        assertEquals(RequestSettings(ReasoningLevel.HIGH, ReplaySamples.wire), model().prepare(ReasoningLevel.HIGH))
    }

    @Test
    fun `prepare gives the context edits the next request was recorded with`() {
        val server =
            ContextEditPolicy.ServerSide(
                clearToolUsesAboveTokens = null,
                keepToolUses = 3,
                compactAboveTokens = 150_000,
            )
        val recorded = requests.map { it.copy(request = it.request.copy(contextEdits = server)) }
        assertEquals(
            server,
            ReplayChatModel(
                ReplaySamples.info,
                recorded,
                ModelScript.of(records, recorded),
            ).prepare(ReasoningLevel.HIGH).contextEdits,
        )
    }

    @Test
    fun `prepare clamps the level with the model's capabilities`() {
        assertEquals(ReasoningLevel.HIGH, model().prepare(ReasoningLevel.XHIGH).reasoning)
    }

    @Test
    fun `a level that clamps to another than the recorded one fails`() {
        assertThrows<AssertionError> { model().prepare(ReasoningLevel.MEDIUM) }
    }

    @Test
    fun `prepare after the last request fails`() =
        runTest {
            val model = model()
            model.complete(requests[0].request)
            model.complete(requests[1].request)
            assertThrows<AssertionError> { model.prepare(ReasoningLevel.HIGH) }
        }

    @Test
    fun `requests the replay did not make fail the consumption check`() =
        runTest {
            val model = model()
            model.complete(requests[0].request)
            assertEquals(
                "the recording made 2 requests, the replay 1",
                assertThrows<AssertionError> {
                    model.assertConsumed()
                }.message,
            )
        }

    @Test
    fun `a replay that made every request passes the consumption check`() =
        runTest {
            val model = model()
            model.complete(requests[0].request)
            model.complete(requests[1].request)
            model.assertConsumed()
            assertEquals(2, model.answered)
        }

    @Test
    fun `requests to another model are refused`() {
        val other = requests.map { it.copy(model = it.model.copy(model = ModelId("claude-haiku-5-5"))) }
        assertThrows<IllegalArgumentException> {
            ReplayChatModel(
                ReplaySamples.info,
                other,
                ModelScript.of(records, requests),
            )
        }
    }

    @Test
    fun `a script that does not match the requests is refused`() {
        assertThrows<IllegalArgumentException> {
            ReplayChatModel(ReplaySamples.info, requests, ModelScript.of(records, requests).take(1))
        }
    }

    // RequestTape

    @Test
    fun `the tape keeps each request with the log's last seq`() =
        runTest {
            var seq = 5L
            val tape = RequestTape(model()) { seq }
            tape.complete(requests[0].request)
            seq = 18
            tape.complete(requests[1].request)
            assertEquals(requests, tape.requests)
            assertEquals(ReplaySamples.info, tape.info)
        }
}
