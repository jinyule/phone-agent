package dev.phoneagent.core.session

import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** B3: the task settings PolicyEngine reads are in the log from the first record on. */
class TaskStartTest {
    private val storage = InMemoryStoragePort()
    private val files = SessionFiles(storage, StoragePath("sessions/s"))
    private val codec = SessionCodec()
    private val start = TaskStarted(SecurityLevel.CAUTIOUS, unattended = true)

    @Test
    fun `create writes the header and task start in one durable write`() =
        runTest {
            val store = SessionStore.create(files, SessionSamples.session, SessionSamples.task, start, Clock { 7 })
            assertEquals(listOf(SessionRecord(1, 7, start)), store.records())
            assertEquals(1, store.lastSeq)
            assertEquals(1, storage.writes.getValue("sessions/s/session.jsonl"), "header and task/start together")
            assertEquals(2, storage.text("sessions/s/session.jsonl").lines().count { it.isNotEmpty() })
            assertEquals(
                start,
                SessionStore
                    .read(files)
                    .records
                    .first()
                    .event,
            )
            assertEquals(false, SessionEventTypes.isIgnorable("task/start"))
            assertEquals(false, SessionEventTypes.isIgnorable("task/level-changed"))
        }

    @Test
    fun `the start is written once, the level may change later`() =
        runTest {
            val store = SessionStore.create(files, SessionSamples.session, SessionSamples.task, start, Clock { 0 })
            assertThrows<IllegalArgumentException> { store.append(start) }
            assertEquals(2, store.append(TaskLevelChanged(SecurityLevel.STANDARD)).seq)
        }

    private fun read(vararg records: SessionRecord) =
        codec.read(
            (listOf(codec.encodeHeader(SessionSamples.header)) + records.map(codec::encode))
                .joinToString("") { "$it\n" }
                .encodeToByteArray(),
        )

    @Test
    fun `a log without task start first is corrupted`() {
        val cases =
            mapOf(
                "starts with another record" to listOf(SessionRecord(1, 0, TurnStarted(1))),
                "starts twice" to listOf(SessionRecord(1, 0, start), SessionRecord(2, 0, start)),
                "no records at all" to emptyList(),
            )
        val accepted =
            cases.mapNotNull { (name, records) ->
                val error = runCatching { read(*records.toTypedArray()) }.exceptionOrNull()
                if (error is SessionCorruptedException) null else "$name: $error"
            }
        assertEquals(emptyList<String>(), accepted)
        // A skipped record cannot stand in for task/start, even when the records after it are fine.
        val unknownFirst = """{"seq":1,"time":0,"type":"future/x","ignorable":true,"data":{}}"""
        val error =
            assertThrows<SessionCorruptedException> {
                val rest = codec.encode(SessionRecord(2, 0, TurnStarted(1)))
                codec.read("${codec.encodeHeader(SessionSamples.header)}\n$unknownFirst\n$rest\n".encodeToByteArray())
            }
        assertEquals(2, error.line)
        assertEquals(start, read(SessionRecord(1, 0, start)).records.single().event)
    }
}
