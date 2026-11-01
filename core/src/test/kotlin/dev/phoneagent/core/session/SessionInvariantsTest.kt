package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Rules that span several records, checked where records are written and where they are read. */
class SessionInvariantsTest {
    private val storage = InMemoryStoragePort()
    private val files = SessionFiles(storage, StoragePath("sessions/s"))
    private val codec = SessionCodec()

    private suspend fun newStore() =
        SessionStore.create(files, SessionSamples.session, SessionSamples.task, SessionSamples.start, Clock { 0 })

    private fun call(id: String) =
        ToolCall(ToolCallId(id), SessionSamples.task, SessionSamples.session, "phone_tap", JsonObject(emptyMap()), "r")

    private fun result(
        id: String,
        status: ToolStatus = ToolStatus.OK,
    ) = ToolResultRecorded(ToolCallId(id), "phone_tap", ToolResult(status, listOf(Part.Text("x"))))

    private val text = listOf(Part.Text("x"))

    @Test
    fun `surface ops must match the event type when written`() =
        runTest {
            val store = newStore()
            val first = store.append(UserMessage(UserMessageKind.USER, text)).seq
            val second = store.append(UserMessage(UserMessageKind.USER, text)).seq
            val rejected =
                listOf(
                    "user/message with Replace" to
                        suspend {
                            store.append(
                                UserMessage(UserMessageKind.USER, text),
                                SurfaceOp.Replace(first, first),
                            )
                        },
                    "summary with Append" to suspend { store.append(CompactionSummary(text)) },
                    "prune over two records" to
                        suspend { store.append(CompactionPrune(text), SurfaceOp.Replace(first, second)) },
                    "prune with Append" to suspend { store.append(CompactionPrune(text)) },
                )
            rejected.forEach { (name, append) -> assertThrows<IllegalArgumentException>(name) { append() } }
            assertEquals(second, store.lastSeq, "nothing written")
            store.append(CompactionSummary(text), SurfaceOp.Replace(first, second))
            store.append(CompactionPrune(text), SurfaceOp.Replace(second, second))
            assertEquals(second + 2, store.lastSeq, "the store stays writable")
        }

    @Test
    fun `a call is declared once and answered once, and results need no declaration`() =
        runTest {
            val store = newStore()
            val declared = store.append(ToolCallRecorded(call("c1"))).seq
            assertThrows<IllegalArgumentException>("second tool/call") { store.append(ToolCallRecorded(call("c1"))) }
            store.append(result("c1"), sources = listOf(declared))
            assertThrows<IllegalArgumentException>("second tool/result") {
                store.append(result("c1", ToolStatus.ERROR), sources = listOf(declared))
            }
            store.append(result("c-invalid", ToolStatus.INVALID_ARGS))
            store.append(result("c-skipped", ToolStatus.SKIPPED))
            assertThrows<IllegalArgumentException>("answered twice without a declaration") {
                store.append(result("c-skipped", ToolStatus.SKIPPED))
            }
            assertEquals(declared + 3, store.lastSeq)
        }

    @Test
    fun `reopening keeps track of declared and answered calls`() =
        runTest {
            val store = newStore()
            val declared = store.append(ToolCallRecorded(call("c1"))).seq
            store.append(result("c1"), sources = listOf(declared))
            store.close()
            val reopened = SessionStore.open(files, Clock { 0 }).store
            assertThrows<IllegalArgumentException> { reopened.append(ToolCallRecorded(call("c1"))) }
            assertThrows<IllegalArgumentException> { reopened.append(result("c1")) }
        }

    private val prefix: List<SessionRecord> = listOf(SessionRecord(1, 0, SessionSamples.start))

    private fun read(vararg records: SessionRecord): SessionLog {
        val lines = listOf(codec.encodeHeader(SessionSamples.header)) + (prefix + records).map(codec::encode)
        return codec.read(lines.joinToString("") { "$it\n" }.encodeToByteArray())
    }

    private fun record(
        offset: Int,
        event: SessionEvent,
        surfaceOp: SurfaceOp = SurfaceOp.Append,
    ) = SessionRecord(prefix.size + offset.toLong(), 0, event, surfaceOp)

    /** Line of the record at [offset] (1-based after the prefix): the header is line 1. */
    private fun lineOf(offset: Int) = prefix.size + offset + 1

    @Test
    fun `reading rejects records that break the rules, naming the line`() {
        val user = UserMessage(UserMessageKind.USER, text)
        val first = prefix.size + 1L
        val cases =
            listOf(
                "user/message with Replace" to
                    listOf(
                        record(1, user),
                        record(2, user, SurfaceOp.Replace(first, first)),
                    ),
                "summary with Append" to listOf(record(1, user), record(2, CompactionSummary(text))),
                "prune with Append" to listOf(record(1, user), record(2, CompactionPrune(text))),
                "prune over two records" to
                    listOf(
                        record(1, user),
                        record(2, user),
                        record(3, CompactionPrune(text), SurfaceOp.Replace(first, first + 1)),
                    ),
                "second tool/call" to
                    listOf(record(1, ToolCallRecorded(call("c1"))), record(2, ToolCallRecorded(call("c1")))),
                "second tool/result" to listOf(record(1, result("c1")), record(2, result("c1"))),
            )
        val notRejected =
            cases.mapNotNull { (name, records) ->
                val error = runCatching { read(*records.toTypedArray()) }.exceptionOrNull()
                if (error is SessionCorruptedException && error.line == lineOf(records.size)) null else "$name: $error"
            }
        assertEquals(emptyList<String>(), notRejected, "each case is corruption of its last line")
        val fine =
            read(
                record(1, user),
                record(2, CompactionSummary(text), SurfaceOp.Replace(first, first)),
                record(3, CompactionPrune(text), SurfaceOp.Replace(first, first)),
                record(4, ToolCallRecorded(call("c1"))),
                record(5, result("c1")),
                record(6, result("c2", ToolStatus.SKIPPED)),
            )
        assertTrue(fine.records.size >= 6)
    }
}
