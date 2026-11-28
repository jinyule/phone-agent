package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.CompactionSummary
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The normalizer (engineering-baseline §3.2, WP08 design §4): rules N1–N5 on a log holding every
 * event type, the invariances they buy, and lock (b) — each change of model-visible content
 * survives normalization (one test per kind of change).
 */
class NormalizerTest {
    private val codec = SessionCodec()

    private fun text(records: List<SessionRecord> = SessionSamples.records): String =
        (listOf(codec.encodeHeader(SessionSamples.header)) + records.map(codec::encode)).joinToString("") { "$it\n" }

    private fun normalized(records: List<SessionRecord> = SessionSamples.records): String =
        Normalizer().session(text(records))

    private fun lines(normalized: String): List<JsonObject> =
        normalized
            .lines()
            .filter {
                it.isNotEmpty()
            }.map { Json.parseToJsonElement(it).jsonObject }

    private fun data(
        normalized: String,
        type: String,
    ): List<JsonObject> =
        lines(normalized)
            .drop(1)
            .filter {
                it.string("type") == type
            }.map { it.getValue("data").jsonObject }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun events(transform: (SessionEvent) -> SessionEvent): List<SessionRecord> =
        SessionSamples.records.map { it.copy(event = transform(it.event)) }

    /** [mutated] differs from the samples before normalization and still after it. */
    private fun assertSurvives(mutated: List<SessionRecord>) {
        assertNotEquals(text(), text(mutated), "the mutation changed nothing")
        assertNotEquals(normalized(), normalized(mutated))
    }

    // N1–N4

    @Test
    fun `the header's time becomes 0 and its ids become tokens`() {
        val header = lines(normalized()).first()
        assertEquals(0, header.getValue("createdAt").jsonPrimitive.long)
        assertEquals("{{session:1}}", header.string("sessionId"))
        assertEquals("{{task:1}}", header.string("taskId"))
    }

    @Test
    fun `every record's time becomes 0`() {
        assertEquals(setOf(0L), lines(normalized()).drop(1).map { it.getValue("time").jsonPrimitive.long }.toSet())
    }

    @Test
    fun `a deadline keeps its distance from its record's time`() {
        val record = SessionSamples.records.first { it.event is ApprovalAsked }
        val expected = (record.event as ApprovalAsked).expiresAt - record.time
        assertEquals(
            expected,
            data(normalized(), "approval/asked")
                .single()
                .getValue("expiresAt")
                .jsonPrimitive.long,
        )
    }

    @Test
    fun `retry delays become 0`() {
        assertEquals(
            listOf(0L, 0L),
            data(normalized(), "request/retry").map { it.getValue("delayMs").jsonPrimitive.long },
        )
    }

    @Test
    fun `call ids are numbered by their first occurrence in the log`() {
        val results = data(normalized(), "tool/result").map { it.string("callId") }
        // call-1 first appears in the reply, then call-3, then call-2.
        assertEquals(listOf("{{call:1}}", "{{call:2}}", "{{call:3}}"), results)
    }

    @Test
    fun `a tool call carries the log's task and session tokens`() {
        val call = data(normalized(), "tool/call").single().getValue("call").jsonObject
        assertEquals(
            listOf("{{call:1}}", "{{task:1}}", "{{session:1}}"),
            listOf("id", "taskId", "sessionId").map {
                call.string(it)
            },
        )
    }

    @Test
    fun `prompt ids become tokens`() {
        assertEquals("{{prompt:1}}", data(normalized(), "approval/asked").single().string("promptId"))
        assertEquals("{{prompt:1}}", data(normalized(), "prompt/withdrawn").single().string("promptId"))
    }

    @Test
    fun `snapshot, element, blob, provider and model ids stay as written`() {
        val snapshot = data(normalized(), "screen/snapshot").first().getValue("snapshot").jsonObject
        assertEquals("s1", snapshot.string("id"))
        assertEquals(
            "e2",
            snapshot
                .getValue("elements")
                .jsonArray
                .first()
                .jsonObject
                .string("ref"),
        )
        val frame = data(normalized(), "screen/frame").single()
        assertEquals(SessionSamples.blob.value, frame.string("blob"))
        val reply = data(normalized(), "assistant/message").last()
        assertEquals(listOf("anthropic", "claude-opus-5-5"), listOf(reply.string("provider"), reply.string("model")))
    }

    @Test
    fun `seqs and their references stay as written`() {
        val raw = lines(text()).drop(1)
        val out = lines(normalized()).drop(1)
        assertEquals(raw.map { it["seq"] }, out.map { it["seq"] })
        assertEquals(raw.map { it["sources"] }, out.map { it["sources"] })
        assertEquals(raw.map { it["surfaceOp"] }, out.map { it["surfaceOp"] })
        assertEquals(
            data(text(), "tool/started").single()["verdictSeq"],
            data(normalized(), "tool/started").single()["verdictSeq"],
        )
        assertEquals(data(text(), "context/reasoning-dropped"), data(normalized(), "context/reasoning-dropped"))
    }

    @Test
    fun `text, JSON arguments, opaque blocks and metadata are not touched`() {
        val reply = data(normalized(), "assistant/message").last()
        assertEquals(data(text(), "assistant/message").last()["providerMetadata"], reply["providerMetadata"])
        val parts = reply.getValue("parts").jsonArray.map { it.jsonObject }
        assertEquals(SessionSamples.reasoning.opaque, parts[0]["opaque"])
        assertEquals("点发送", parts[1].string("text"))
        assertEquals(SessionSamples.toolCallPart.argsJson, parts[2].string("argsJson"))
    }

    // Invariances

    @Test
    fun `logs that differ only in times normalize the same`() {
        val shifted =
            SessionSamples.records.map { record ->
                val event =
                    when (val e = record.event) {
                        is ApprovalAsked -> e.copy(expiresAt = e.expiresAt + SHIFT)
                        is RequestRetry -> e.copy(delayMs = e.delayMs * 3 + 7)
                        else -> e
                    }
                record.copy(time = record.time + SHIFT, event = event)
            }
        val header = codec.encodeHeader(SessionSamples.header.copy(createdAt = SessionSamples.header.createdAt + SHIFT))
        val text = (listOf(header) + shifted.map(codec::encode)).joinToString("") { "$it\n" }
        assertNotEquals(text(), text)
        assertEquals(normalized(), Normalizer().session(text))
    }

    @Test
    fun `logs that differ only in how ids are spelled normalize the same`() {
        val respelled =
            text()
                .replace("\"call-1\"", "\"toolu_01Ab\"")
                .replace("\"call-2\"", "\"call_x\"")
                .replace("\"task-1\"", "\"t-9\"")
                .replace("\"session-1\"", "\"2026-11-03.a\"")
                .replace("\"prompt-1\"", "\"p-41\"")
        assertNotEquals(text(), respelled)
        assertEquals(normalized(), Normalizer().session(respelled))
    }

    @Test
    fun `normalizing twice changes nothing`() {
        assertEquals(normalized(), Normalizer().session(normalized()))
    }

    @Test
    fun `a materialized fixture decodes and normalizes back to itself`() {
        val materialized = Tokens.materializeSession(normalized())
        val log = codec.read(materialized.toByteArray())
        assertEquals("session-1", log.header.sessionId.value)
        assertEquals(SessionSamples.records.size, log.records.size)
        assertEquals(normalized(), Normalizer().session(materialized))
    }

    @Test
    fun `materializing a log whose ids are not tokens fails`() {
        val failure = assertThrows<IllegalArgumentException> { Tokens.materializeSession(text()) }
        assertTrue("not normalized" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `materializing a token of another kind in an id position fails`() {
        val wrong = normalized().replaceFirst("\"{{call:1}}\"", "\"{{task:1}}\"")
        val failure = assertThrows<IllegalArgumentException> { Tokens.materializeSession(wrong) }
        assertTrue("{{call:n}}" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a token stands for the id kind-n`() {
        assertEquals("call-3", Tokens.concrete("{{call:3}}"))
        assertEquals("{{prompt:12}}", Tokens.of("prompt", 12))
    }

    @Test
    fun `a token numbered 0 is no token`() {
        assertThrows<IllegalArgumentException> { Tokens.concrete("{{call:0}}") }
    }

    @Test
    fun `a concrete id is no token`() {
        assertThrows<IllegalArgumentException> { Tokens.concrete("call-3") }
    }

    @Test
    fun `a text that does not end with a newline is refused`() {
        assertThrows<IllegalArgumentException> { Normalizer().session(text().dropLast(1)) }
    }

    @Test
    fun `a record line that is not an object is refused`() {
        val lines = text().lines()
        assertThrows<IllegalArgumentException> { Normalizer().session("${lines[0]}\n[1]\n") }
    }

    // N5

    @Test
    fun `a key in a message becomes its placeholder and is counted`() {
        val mutated =
            events { e ->
                if (e is UserMessage &&
                    e.source == "timer"
                ) {
                    e.copy(parts = listOf(Part.Text("key $KEY and again $KEY")))
                } else {
                    e
                }
            }
        val normalizer = Normalizer(listOf("unused-key-for-tests", KEY))
        val out = normalizer.session(text(mutated))
        assertFalse(KEY in out)
        assertTrue("key {{credential:2}} and again {{credential:2}}" in out)
        assertEquals(mapOf(2 to 2), normalizer.credentialHits)
    }

    @Test
    fun `a key inside tool arguments is found escaped twice`() {
        val key = "test\"key\\with-escapes"
        val args = buildJsonObject { put("password", key) }.toString()
        val mutated =
            events { e ->
                if (e is AssistantMessage && e.stopReason != null) {
                    e.copy(parts = e.parts.map { if (it is Part.ToolCall) it.copy(argsJson = args) else it })
                } else {
                    e
                }
            }
        val normalizer = Normalizer(listOf(key))
        val reply = data(normalizer.session(text(mutated)), "assistant/message").last()
        val argsJson =
            reply
                .getValue("parts")
                .jsonArray[2]
                .jsonObject
                .string("argsJson")
        assertEquals("{{credential:1}}", Json.parseToJsonElement(argsJson).jsonObject.string("password"))
        assertEquals(mapOf(1 to 1), normalizer.credentialHits)
    }

    // The requests file

    private val request =
        ChatRequest(
            system = listOf(PromptBlock("identity", "You operate a phone.")),
            tools = emptyList(),
            messages =
                listOf(
                    Message(Role.ASSISTANT, listOf(SessionSamples.toolCallPart)),
                    Message(
                        Role.TOOL,
                        listOf(Part.ToolResult(ToolCallId("call-3"), listOf(Part.Text("ended")), isError = false)),
                    ),
                ),
            wire = WireProfile(mappingVersion = 1, credential = CredentialKind.NONE),
            cache = CacheLayout.NONE,
        )
    private val model = ModelRef(ProviderId("anthropic"), ModelId("claude-opus-5-5"), ModelProtocol.ANTHROPIC_MESSAGES)

    @Test
    fun `the requests file takes the log's tokens`() {
        val normalizer = Normalizer()
        normalizer.session(text())
        val line =
            Json.parseToJsonElement(
                normalizer.requests(RecordedRequests.encode(listOf(RecordedRequest(1, 42, model, request)))),
            )
        val append = line.jsonObject.getValue("append").jsonArray
        assertEquals(
            "{{call:1}}",
            append[0]
                .jsonObject
                .getValue("parts")
                .jsonArray[0]
                .jsonObject
                .string("id"),
        )
        assertEquals(
            "{{call:2}}",
            append[1]
                .jsonObject
                .getValue("parts")
                .jsonArray[0]
                .jsonObject
                .string("id"),
        )
        assertEquals(
            42,
            line.jsonObject
                .getValue("logSeq")
                .jsonPrimitive.long,
        )
    }

    @Test
    fun `a call id in the requests file that the log does not hold fails`() {
        val normalizer = Normalizer()
        normalizer.session(text())
        val stray =
            request.copy(
                messages =
                    listOf(
                        Message(Role.ASSISTANT, listOf(SessionSamples.toolCallPart.copy(id = ToolCallId("call-9")))),
                    ),
            )
        val failure =
            assertThrows<IllegalArgumentException> {
                normalizer.requests(RecordedRequests.encode(listOf(RecordedRequest(1, 42, model, stray))))
            }
        assertTrue("not in the session log" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a materialized requests file normalizes back to itself`() {
        val normalizer = Normalizer()
        val session = normalizer.session(text())
        val requests = normalizer.requests(RecordedRequests.encode(listOf(RecordedRequest(1, 42, model, request))))
        val again = Normalizer()
        again.session(Tokens.materializeSession(session))
        assertEquals(requests, again.requests(Tokens.materializeRequests(requests)))
        assertEquals(
            "call-2",
            RecordedRequests.decode(Tokens.materializeRequests(requests)).single().request.messages[1].parts.let {
                (it.single() as Part.ToolResult).id.value
            },
        )
    }

    // Canonical form

    @Test
    fun `canonical writes a log in the codec's own layout`() {
        val reordered =
            text().lines().filter { it.isNotEmpty() }.joinToString("") { line ->
                val element = Json.parseToJsonElement(line).jsonObject
                JsonObject(element.entries.reversed().associate { it.key to it.value }).toString() + "\n"
            }
        assertNotEquals(text(), reordered)
        assertEquals(text(), Normalizer.canonical(reordered.toByteArray()))
    }

    // Lock (b): model-visible changes survive normalization

    @Test
    fun `a changed user message survives`() {
        assertSurvives(
            changing<UserMessage> {
                if (it.kind ==
                    UserMessageKind.USER
                ) {
                    it.copy(parts = listOf(Part.Text("给李四发消息")))
                } else {
                    null
                }
            },
        )
    }

    @Test
    fun `a changed assistant text survives`() {
        assertSurvives(replyParts { if (it == Part.Text("点发送")) Part.Text("点取消") else it })
    }

    @Test
    fun `changed tool arguments survive`() {
        assertSurvives(
            replyParts { if (it is Part.ToolCall) it.copy(argsJson = it.argsJson.replace("e2", "e3")) else it },
        )
    }

    @Test
    fun `a changed tool name survives`() {
        assertSurvives(replyParts { if (it is Part.ToolCall) it.copy(name = "phone_long_press") else it })
    }

    @Test
    fun `a changed tool result text survives`() {
        assertSurvives(
            changing<ToolResultRecorded> {
                if (it.callId ==
                    SessionSamples.call
                ) {
                    it.copy(result = it.result.copy(content = listOf(Part.Text("failed"))))
                } else {
                    null
                }
            },
        )
    }

    @Test
    fun `results swapped between two calls survive`() {
        val first = SessionSamples.call
        val other = ToolCallId("call-3")
        assertSurvives(
            changing<ToolResultRecorded> {
                when (it.callId) {
                    first -> it.copy(callId = other)
                    other -> it.copy(callId = first)
                    else -> null
                }
            },
        )
    }

    @Test
    fun `two distinct ids that become one survive`() {
        assertSurvives(
            changing<ToolResultRecorded> {
                if (it.callId.value ==
                    "call-2"
                ) {
                    it.copy(callId = ToolCallId("call-3"))
                } else {
                    null
                }
            },
        )
    }

    @Test
    fun `a changed system prompt survives`() {
        assertSurvives(
            changing<RequestHeader> { it.copy(system = listOf(PromptBlock("identity", "You operate a tablet."))) },
        )
    }

    @Test
    fun `a changed wire snapshot survives`() {
        assertSurvives(changing<RequestHeader> { it.copy(wire = it.wire.copy(echoReasoning = false)) })
    }

    @Test
    fun `a changed compaction range survives`() {
        assertSurvives(
            SessionSamples.records.map { record ->
                val op = record.surfaceOp
                if (record.event is CompactionSummary && op is SurfaceOp.Replace) {
                    record.copy(surfaceOp = SurfaceOp.Replace(op.startSeq + 1, op.endSeq))
                } else {
                    record
                }
            },
        )
    }

    @Test
    fun `a changed reasoning cut survives`() {
        assertSurvives(changing<ReasoningDropped> { ReasoningDropped(it.upToSeq - 1) })
    }

    @Test
    fun `a changed opaque reasoning block survives`() {
        val opaque =
            buildJsonObject {
                put("type", "thinking")
                put("signature", "other")
            }
        assertSurvives(replyParts { if (it is Part.Reasoning) it.copy(opaque = opaque) else it })
    }

    @Test
    fun `a changed image survives`() {
        val other = BlobRef("b".repeat(64))
        assertSurvives(
            changing<UserMessage> { message ->
                message.copy(parts = message.parts.map { if (it is Part.Image) it.copy(blob = other) else it })
            },
        )
    }

    @Test
    fun `a changed snapshot id survives`() {
        assertSurvives(
            changing<ScreenObserved> {
                if (it.callId !=
                    null
                ) {
                    it.copy(snapshot = it.snapshot.copy(id = SnapshotId("s9")))
                } else {
                    null
                }
            },
        )
    }

    @Test
    fun `changed numbers in a call's arguments survive`() {
        val args =
            buildJsonObject {
                put("x", 481)
                put("y", 955)
            }
        assertSurvives(changing<ToolCallRecorded> { it.copy(call = it.call.copy(args = args)) })
    }

    @Test
    fun `changed action coordinates survive`() {
        assertSurvives(
            changing<ScreenActed> { acted ->
                val action = acted.action
                if (action is UiAction.Swipe) acted.copy(action = action.copy(to = Target.Point(500, 201))) else null
            },
        )
    }

    @Test
    fun `a deadline moved against its record survives`() {
        assertSurvives(changing<ApprovalAsked> { it.copy(expiresAt = it.expiresAt + SHIFT) })
    }

    @Test
    fun `changed sources survive`() {
        assertSurvives(
            SessionSamples.records.map { record ->
                if (record.event is ToolCallRecorded) {
                    record.copy(
                        sources = listOf(record.sources.single() - 1),
                    )
                } else {
                    record
                }
            },
        )
    }

    /** The samples with each [E] replaced by [change] of it (`null`: kept). */
    private inline fun <reified E : SessionEvent> changing(
        crossinline change: (E) -> SessionEvent?,
    ): List<SessionRecord> = events { if (it is E) change(it) ?: it else it }

    /** The samples with each part of each assistant reply replaced by [change] of it. */
    private fun replyParts(change: (Part) -> Part): List<SessionRecord> =
        changing<AssistantMessage> { it.copy(parts = it.parts.map(change)) }

    private companion object {
        const val SHIFT = 5_000L
        const val KEY = "sk-test-not-a-real-key"
    }
}
