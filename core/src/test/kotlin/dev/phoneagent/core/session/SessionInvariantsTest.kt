package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
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

    private fun verdict(
        id: String,
        verdict: Verdict = Verdict.Allow,
    ) = PolicyVerdictRecorded(
        ToolCallId(id),
        SecurityLevel.STANDARD,
        ActionIntent(IntentKind.INPUT, "com.example"),
        verdict,
    )

    private fun confirm(id: String) = Verdict.Confirm(ConfirmCard(ToolCallId(id), ConfirmReason.OUTWARD, "发送"))

    private fun started(
        id: String,
        verdictSeq: Long,
    ) = ToolStarted(ToolCallId(id), verdictSeq)

    private fun executed(
        id: String,
        status: ToolStatus = ToolStatus.OK,
    ) = ToolExecuted(ToolCallId(id), status)

    @Test
    fun `a call starts only after its tool call and on its latest verdict, an Allow`() =
        runTest {
            val store = newStore()
            val before = store.lastSeq
            assertThrows<IllegalArgumentException>("no tool/call") { store.append(started("c1", 1)) }
            store.append(ToolCallRecorded(call("c1")))
            val refused = store.append(verdict("c1", Verdict.Refuse("rule", "no"))).seq
            assertThrows<IllegalArgumentException>("on a Refuse") { store.append(started("c1", refused)) }
            val allowed = store.append(verdict("c1")).seq
            val reconfirm = store.append(verdict("c1", confirm("c1"))).seq
            assertThrows<IllegalArgumentException>("on an Allow a later verdict replaced") {
                store.append(started("c1", allowed))
            }
            assertThrows<IllegalArgumentException>("on a Confirm") { store.append(started("c1", reconfirm)) }
            val recheck = store.append(verdict("c1")).seq
            store.append(ToolCallRecorded(call("c2")))
            val other = store.append(verdict("c2")).seq
            assertThrows<IllegalArgumentException>("on another call's verdict") { store.append(started("c1", other)) }
            assertThrows<IllegalArgumentException>("on a record that is no verdict") { store.append(started("c1", 1)) }
            assertThrows<IllegalArgumentException>("on a later seq") { store.append(started("c1", recheck + 100)) }
            assertEquals(before + 7, store.lastSeq, "nothing written for the rejected starts")
            store.append(started("c1", recheck))
            assertThrows<IllegalArgumentException>("started twice") { store.append(started("c1", recheck)) }
            store.append(result("c2"))
            assertThrows<IllegalArgumentException>("after its result") { store.append(started("c2", other)) }
        }

    @Test
    fun `an execution is recorded once, after the start and before the result`() =
        runTest {
            val store = newStore()
            store.append(ToolCallRecorded(call("c1")))
            val allowed = store.append(verdict("c1")).seq
            assertThrows<IllegalArgumentException>("before the start") { store.append(executed("c1")) }
            store.append(started("c1", allowed))
            store.append(executed("c1", ToolStatus.ERROR))
            assertThrows<IllegalArgumentException>("twice") { store.append(executed("c1")) }
            store.append(ToolCallRecorded(call("c2")))
            store.append(started("c2", store.append(verdict("c2")).seq))
            store.append(result("c2", ToolStatus.TIMEOUT))
            assertThrows<IllegalArgumentException>("after the result") { store.append(executed("c2")) }
            assertThrows<IllegalArgumentException>("a status only repair writes") {
                ToolExecuted(ToolCallId("c3"), ToolStatus.OUTCOME_UNKNOWN)
            }
            assertThrows<IllegalArgumentException> { ToolExecuted(ToolCallId("c3"), ToolStatus.NOT_STARTED) }
            assertThrows<IllegalArgumentException> { ToolStarted(ToolCallId("c3"), 0) }
        }

    @Test
    fun `reopening keeps track of started and executed calls`() =
        runTest {
            val store = newStore()
            store.append(ToolCallRecorded(call("c1")))
            val allowed = store.append(verdict("c1")).seq
            store.append(started("c1", allowed))
            store.append(executed("c1"))
            store.close()
            val reopened = SessionStore.open(files, Clock { 0 }).store
            assertThrows<IllegalArgumentException> { reopened.append(started("c1", allowed)) }
            assertThrows<IllegalArgumentException> { reopened.append(executed("c1")) }
            assertThrows<IllegalArgumentException> { reopened.append(result("c1", ToolStatus.NOT_STARTED)) }
            reopened.append(result("c1", ToolStatus.OUTCOME_UNKNOWN))
        }

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
    fun `a call that already has a result cannot be declared afterwards`() =
        runTest {
            val store = newStore()
            store.append(result("c-invalid", ToolStatus.INVALID_ARGS))
            store.append(result("c-skipped", ToolStatus.SKIPPED))
            store.append(result("c-not-started", ToolStatus.NOT_STARTED))
            val before = store.lastSeq
            listOf("c-invalid", "c-skipped", "c-not-started").forEach { id ->
                assertThrows<IllegalArgumentException>("tool/call after the result of $id") {
                    store.append(ToolCallRecorded(call(id)))
                }
            }
            assertEquals(before, store.lastSeq, "nothing written")
            assertEquals(
                3,
                storage.text(files.log.value).lines().count { "tool/result" in it },
                "no byte of a tool/call",
            )
            store.close()
            val reopened = SessionStore.open(files, Clock { 0 }).store
            assertThrows<IllegalArgumentException>("after reopening") {
                reopened.append(ToolCallRecorded(call("c-skipped")))
            }
            reopened.append(ToolCallRecorded(call("c-new")))
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

    private val user = UserMessage(UserMessageKind.USER, text)

    /** Seq of the first record after the prefix. */
    private val first = prefix.size + 1L

    /** Logs whose last record breaks a rule. */
    private val corruptions: List<Pair<String, List<SessionRecord>>> =
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
            "tool/call after its result" to
                listOf(record(1, result("c1", ToolStatus.SKIPPED)), record(2, ToolCallRecorded(call("c1")))),
            "tool/started without tool/call" to listOf(record(1, verdict("c1")), record(2, started("c1", first))),
            "tool/started on a Refuse" to
                listOf(
                    record(1, ToolCallRecorded(call("c1"))),
                    record(2, verdict("c1", Verdict.Refuse("rule", "no"))),
                    record(3, started("c1", first + 1)),
                ),
            "tool/started on a replaced Allow" to
                listOf(
                    record(1, ToolCallRecorded(call("c1"))),
                    record(2, verdict("c1")),
                    record(3, verdict("c1", confirm("c1"))),
                    record(4, started("c1", first + 1)),
                ),
            "second tool/started" to
                listOf(
                    record(1, ToolCallRecorded(call("c1"))),
                    record(2, verdict("c1")),
                    record(3, started("c1", first + 1)),
                    record(4, started("c1", first + 1)),
                ),
            "tool/executed without tool/started" to
                listOf(record(1, ToolCallRecorded(call("c1"))), record(2, executed("c1"))),
            "tool/executed after the result" to
                listOf(
                    record(1, ToolCallRecorded(call("c1"))),
                    record(2, verdict("c1")),
                    record(3, started("c1", first + 1)),
                    record(4, result("c1")),
                    record(5, executed("c1")),
                ),
        )

    @Test
    fun `reading rejects records that break the rules, naming the line`() {
        val notRejected =
            corruptions.mapNotNull { (name, records) ->
                val error = runCatching { read(*records.toTypedArray()) }.exceptionOrNull()
                if (error is SessionCorruptedException && error.line == lineOf(records.size)) null else "$name: $error"
            }
        assertEquals(emptyList<String>(), notRejected, "each case is corruption of its last line")
    }

    @Test
    fun `reading accepts a log that keeps every rule`() {
        val fine =
            read(
                record(1, user),
                record(2, CompactionSummary(text), SurfaceOp.Replace(first, first)),
                record(3, CompactionPrune(text), SurfaceOp.Replace(first, first)),
                record(4, ToolCallRecorded(call("c1"))),
                record(5, result("c1")),
                record(6, result("c2", ToolStatus.SKIPPED)),
                record(7, ToolCallRecorded(call("c3"))),
                record(8, verdict("c3")),
                record(9, started("c3", first + 7)),
                record(10, executed("c3")),
                record(11, result("c3")),
                record(12, TaskStopped(StopCause.ERROR)),
                record(13, SessionResumed(0)),
                record(14, TaskStopped(StopCause.LIMIT)),
            )
        assertTrue(fine.records.size >= 14)
    }
}
