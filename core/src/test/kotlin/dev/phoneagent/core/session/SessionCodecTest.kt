package dev.phoneagent.core.session

import dev.phoneagent.core.model.ModelErrorKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class SessionCodecTest {
    private val codec = SessionCodec()
    private val headerLine = codec.encodeHeader(SessionSamples.header)

    private fun log(vararg lines: String): ByteArray = lines.joinToString("") { "$it\n" }.encodeToByteArray()

    /** Record [seq] of a valid log: `task/start` first, then `turn/start` records. */
    private fun line(
        seq: Long,
        event: SessionEvent = if (seq == 1L) SessionSamples.start else TurnStarted(seq.toInt() - 1),
    ): String = codec.encode(SessionRecord(seq, 0, event))

    @Test
    fun `defaults are omitted from the envelope but every payload field is written`() {
        assertEquals(
            """{"seq":1,"time":0,"type":"turn/end","data":{"turn":1,"reason":"COMPLETED","detail":null}}""",
            line(1, TurnEnded(1, TurnEndReason.COMPLETED)),
        )
        assertEquals(
            """{"seq":3,"time":7,"type":"compaction/prune",""" +
                """"surfaceOp":{"type":"replace","startSeq":2,"endSeq":2},"sources":[1],"data":{"parts":[]}}""",
            codec.encode(SessionRecord(3, 7, CompactionPrune(emptyList()), SurfaceOp.Replace(2, 2), listOf(1))),
        )
        assertEquals(
            """{"seq":1,"time":0,"type":"request/retry","ignorable":true,""" +
                """"data":{"attempt":2,"delayMs":500,"error":"NETWORK"}}""",
            line(1, RequestRetry(2, 500, ModelErrorKind.NETWORK)),
        )
        assertEquals(
            """{"seq":1,"time":0,"type":"session/resumed","data":{"droppedTailBytes":0}}""",
            line(1, SessionResumed(0)),
        )
        assertEquals(
            """{"format":"phoneagent.session","version":1,""" +
                """"sessionId":"session-1","taskId":"task-1","createdAt":1000}""",
            headerLine,
        )
    }

    @Test
    fun `the committed v1 log decodes to the samples and the samples encode to it byte for byte`() {
        val fixture = File("src/test/resources/session-format/v1/${FormatFixture.FILE}")
        val expected = FormatFixture.text(codec)
        val read = codec.read(fixture.readBytes())
        assertEquals(SessionSamples.header, read.header)
        assertEquals(SessionSamples.records, read.records)
        assertEquals(
            expected,
            fixture.readText(),
            "v1 fixtures are never rewritten; a format change needs a new version",
        )
    }

    @Test
    fun `an unknown ignorable record is skipped and reported`() {
        val unknown = """{"seq":2,"time":0,"type":"future/thing","ignorable":true,"data":{"x":1}}"""
        val read = codec.read(log(headerLine, line(1), unknown, line(3)))
        assertEquals(listOf(1L, 3L), read.records.map { it.seq })
        assertEquals(listOf(SkippedRecord(2, "future/thing")), read.skipped)
        assertEquals(3, read.lastSeq)
    }

    @Test
    fun `an unknown record that is not ignorable refuses the log and names seq and type`() {
        val unknown = """{"seq":2,"time":0,"type":"future/thing","data":{}}"""
        val error = assertThrows<UnknownEventTypeException> { codec.read(log(headerLine, line(1), unknown)) }
        assertEquals(2, error.seq)
        assertEquals("future/thing", error.type)
        assertTrue("seq 2" in error.message.orEmpty() && "future/thing" in error.message.orEmpty())
    }

    @Test
    fun `a corrupted middle line refuses the log with its line number`() {
        val error =
            assertThrows<SessionCorruptedException> { codec.read(log(headerLine, line(1), "{not json", line(2))) }
        assertEquals(3, error.line)
    }

    @Test
    fun `unknown keys, bad payloads and broken invariants are corruption`() {
        val cases =
            listOf(
                """{"seq":2,"time":0,"type":"turn/start","data":{"turn":1,"extra":true}}""",
                """{"seq":2,"time":0,"type":"turn/start","data":{"turn":"one"}}""",
                """{"seq":2,"time":0,"type":"turn/start","sources":[2],"data":{"turn":1}}""",
                """{"seq":2,"time":0,"type":"Turn_Start","data":{}}""",
                """{"seq":2,"time":0,"type":"user/message","data":{"kind":"USER","parts":[],"source":null}}""",
                """{"seq":2,"time":0,"type":"turn/start","data":{"turn":1},"extra":0}""",
                """[1,2]""",
            )
        cases.forEach { bad ->
            val error = assertThrows<SessionCorruptedException>(bad) { codec.read(log(headerLine, line(1), bad)) }
            assertEquals(3, error.line, bad)
        }
        assertEquals(2, codec.read(log(headerLine, line(1), line(2))).records.size, "the valid prefix itself reads")
    }

    @Test
    fun `payload errors name the record`() {
        val bad = """{"seq":2,"time":0,"type":"turn/start","data":{"turn":"one"}}"""
        val error = assertThrows<SessionCorruptedException> { codec.read(log(headerLine, line(1), bad)) }
        assertTrue("seq 2 (turn/start)" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `seqs must start at 1 and be contiguous`() {
        assertEquals(3, assertThrows<SessionCorruptedException> { codec.read(log(headerLine, line(1), line(3))) }.line)
        assertEquals(2, assertThrows<SessionCorruptedException> { codec.read(log(headerLine, line(2))) }.line)
    }

    @Test
    fun `a torn last line is reported, not decoded`() {
        val bytes = log(headerLine, line(1)) + line(2).encodeToByteArray().copyOf(10)
        val read = codec.read(bytes)
        assertEquals(1, read.records.size)
        assertEquals(TruncatedTail(line = 3, bytes = 10), read.truncatedTail)
        assertNull(codec.read(log(headerLine, line(1))).truncatedTail)
    }

    @Test
    fun `an empty line or invalid UTF-8 in the middle is corruption`() {
        assertEquals(
            3,
            assertThrows<SessionCorruptedException> { codec.read(log(headerLine, line(1), "", line(2))) }.line,
        )
        val invalidUtf8 = log(headerLine).plus(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()))
        assertEquals(2, assertThrows<SessionCorruptedException> { codec.read(invalidUtf8) }.line)
    }

    @Test
    fun `the header is required and must be a session header of a readable version`() {
        assertEquals(1, assertThrows<SessionCorruptedException> { codec.read(ByteArray(0)) }.line)
        assertEquals(1, assertThrows<SessionCorruptedException> { codec.read(log("""{"seq":1}""")) }.line)
        assertEquals(
            1,
            assertThrows<SessionCorruptedException> {
                codec.read(log("""{"format":"other","version":1,"sessionId":"s","taskId":"t","createdAt":0}"""))
            }.line,
        )
        val newer = headerLine.replace("\"version\":1", "\"version\":2")
        val error = assertThrows<UnsupportedSessionVersionException> { codec.read(log(newer)) }
        assertEquals(2, error.version)
        assertEquals(1..1, error.supported)
    }

    /** Pretends v1 called the turn number `n` and v2 renamed it; shows how a version step is added. */
    private object RenameTurnField : SessionMigration {
        override val fromVersion = 1

        override fun migrate(envelope: JsonObject): JsonObject {
            if (envelope["type"]?.jsonPrimitive?.content != "turn/start") return envelope
            val data = envelope.getValue("data").jsonObject
            val renamed = JsonObject(data - "n" + ("turn" to data.getValue("n")))
            return JsonObject(envelope + ("data" to renamed))
        }
    }

    @Test
    fun `older versions are read through the migration chain`() {
        val migrating = SessionCodec(listOf(RenameTurnField), currentVersion = 2)
        assertEquals(1, migrating.oldestReadableVersion)
        val oldLine = """{"seq":2,"time":5,"type":"turn/start","data":{"n":4}}"""
        val read = migrating.read(log(headerLine, line(1), oldLine, line(3, TurnEnded(4, TurnEndReason.STOPPED))))
        assertEquals(1, read.header.version)
        assertEquals(
            listOf(
                SessionRecord(1, 0, SessionSamples.start),
                SessionRecord(2, 5, TurnStarted(4)),
                SessionRecord(3, 0, TurnEnded(4, TurnEndReason.STOPPED)),
            ),
            read.records,
        )
        // Without the migration the same file is refused, and a v2 file is too new for a v1 codec.
        assertThrows<SessionCorruptedException> { codec.read(log(headerLine, line(1), oldLine)) }
        val v2Header = migrating.encodeHeader(SessionSamples.header.copy(version = 2))
        assertThrows<UnsupportedSessionVersionException> { codec.read(log(v2Header)) }
    }

    @Test
    fun `the migration chain must be contiguous and end before the current version`() {
        assertThrows<IllegalArgumentException> { SessionCodec(listOf(RenameTurnField), currentVersion = 3) }
        assertThrows<IllegalArgumentException> { SessionCodec(listOf(RenameTurnField), currentVersion = 1) }
        assertThrows<IllegalArgumentException> {
            SessionCodec(
                listOf(RenameTurnField, RenameTurnField),
                currentVersion = 2,
            )
        }
        assertThrows<IllegalArgumentException> { SessionCodec(currentVersion = 0) }
        assertEquals(1, SessionCodec().oldestReadableVersion)
    }

    @Test
    fun `envelope and surface op invariants`() {
        val data = JsonPrimitive(1)
        assertThrows<IllegalArgumentException> { Envelope(0, 0, "turn/start", data = data) }
        assertThrows<IllegalArgumentException> { Envelope(2, 0, "turn/start", sources = listOf(2), data = data) }
        assertThrows<IllegalArgumentException> {
            Envelope(2, 0, "turn/start", surfaceOp = SurfaceOp.Replace(1, 2), data = data)
        }
        assertThrows<IllegalArgumentException> { SurfaceOp.Replace(3, 2) }
        assertThrows<IllegalArgumentException> { SurfaceOp.Replace(0, 2) }
        assertEquals(
            1,
            Envelope(
                2,
                0,
                "turn/start",
                surfaceOp = SurfaceOp.Replace(1, 1),
                sources = listOf(1),
                data = data,
            ).sources.size,
        )
    }

    @Test
    fun `event invariants reject inconsistent payloads`() {
        val p = SessionSamples
        assertThrows<IllegalArgumentException> {
            AssistantMessage(
                1,
                p.provider,
                p.model,
                emptyList(),
                stopReason = null,
            )
        }
        assertThrows<IllegalArgumentException> {
            AssistantMessage(
                1,
                p.provider,
                p.model,
                emptyList(),
                stopReason = dev.phoneagent.core.model.StopReason.END_TURN,
                interrupted = true,
            )
        }
        assertThrows<IllegalArgumentException> {
            AssistantMessage(1, p.provider, p.model, listOf(p.toolCallPart), null, interrupted = true)
        }
        assertThrows<IllegalArgumentException> {
            AssistantMessage(1, p.provider, p.model, listOf(p.image), dev.phoneagent.core.model.StopReason.END_TURN)
        }
        assertThrows<IllegalArgumentException> { UserMessage(UserMessageKind.USER, listOf(p.toolCallPart)) }
        assertThrows<IllegalArgumentException> {
            HandoffEnded(p.prompt, dev.phoneagent.core.ports.HandoffResult.DONE, detail = "x")
        }
        assertThrows<IllegalArgumentException> { InboxSpliced(InboxQueue.NEXT_STEP, removed = -1) }
        assertThrows<IllegalArgumentException> { InboxEntry(InboxEntryKind.STEER, listOf(p.reasoning)) }
        assertThrows<IllegalArgumentException> { CompactionSummary(listOf(p.toolCallPart)) }
        assertThrows<IllegalArgumentException> { CompactionPrune(listOf(p.reasoning)) }
        assertThrows<IllegalArgumentException> { ScreenFrame(p.blob, "image/gif", 1, 1) }
        assertThrows<IllegalArgumentException> { ScreenFrame(p.blob, "image/png", 0, 1) }
        assertThrows<IllegalArgumentException> {
            SessionHeader(version = 0, sessionId = p.session, taskId = p.task, createdAt = 0)
        }
    }

    @Test
    fun `optional payload fields have defaults`() {
        val p = SessionSamples
        assertEquals(null, HandoffStarted(p.prompt, dev.phoneagent.core.ports.HandoffKind.OTHER, "x").callId)
        assertEquals(null, HandoffEnded(p.prompt, dev.phoneagent.core.ports.HandoffResult.DONE).detail)
        assertEquals(null, SessionFlushException("x").cause)
        assertEquals(null, SessionWriteException("x").cause)
    }

    @Test
    fun `a request header rebuilds the request`() {
        val header = SessionSamples.events.filterIsInstance<RequestHeader>().first()
        val request = header.toRequest(emptyList())
        assertEquals(header.system, request.system)
        assertEquals(header.tools, request.tools)
        assertEquals(header.cache, request.cache)
        assertEquals(header.contextEdits, request.contextEdits)
        assertEquals(header.maxOutputTokens, request.maxOutputTokens)
        assertEquals(header.reasoning, request.reasoning)
        assertEquals(header.wire, request.wire)
    }
}
