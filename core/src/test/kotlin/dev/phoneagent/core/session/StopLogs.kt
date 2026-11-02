package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows

/**
 * Small logs after `task/start` for the run rules of [SessionInvariants], checked on all three
 * paths: append, read, and append after reopening.
 */
class StopLogs {
    val storage = InMemoryStoragePort()
    val codec = SessionCodec()
    private var logs = 0

    /** Marks where [bytes] puts an ignorable record of a type this reader does not know. */
    object Unknown

    suspend fun newStore(): Pair<SessionFiles, SessionStore> {
        val files = SessionFiles(storage, StoragePath("sessions/s${logs++}"))
        val store = SessionStore.create(files, SessionSamples.session, SessionSamples.task, SessionSamples.start, clock)
        return files to store
    }

    /** A store holding [events], closed and reopened (the process ended after the last one). */
    suspend fun reopened(events: List<SessionEvent>): SessionStore.Opened {
        val (files, store) = newStore()
        events.forEach { store.append(it) }
        store.close()
        return SessionStore.open(files, clock)
    }

    /** The log of `task/start` and [events] ([Unknown] marks an unknown ignorable record). */
    fun bytes(events: List<Any>): ByteArray {
        val first = codec.encode(SessionRecord(1, 0, SessionSamples.start))
        val lines =
            listOf(codec.encodeHeader(SessionSamples.header), first) +
                events.mapIndexed { index, item ->
                    val seq = index + 2L
                    if (item is SessionEvent) codec.encode(SessionRecord(seq, 0, item)) else unknown(seq)
                }
        return lines.joinToString("") { "$it\n" }.encodeToByteArray()
    }

    /** The line reading [events] fails on, or `null` when it reads. */
    fun corruptLine(events: List<Any>): Int? =
        when (val error = runCatching { codec.read(bytes(events)) }.exceptionOrNull()) {
            null -> null
            is SessionCorruptedException -> error.line
            else -> throw error
        }

    private fun unknown(seq: Long) = """{"seq":$seq,"time":0,"type":"future/thing","ignorable":true,"data":{}}"""

    /**
     * [events] break a rule with their last one: the store refuses it and writes nothing, reading
     * names its line, and a store reopened before it refuses it too.
     */
    suspend fun assertRejectsLast(
        name: String,
        events: List<SessionEvent>,
    ) {
        val (_, store) = newStore()
        events.dropLast(1).forEach { store.append(it) }
        val before = store.lastSeq
        assertThrows<IllegalArgumentException>("$name: written") { store.append(events.last()) }
        assertEquals(before, store.lastSeq, "$name: nothing written")
        assertEquals(events.size + 2, corruptLine(events), "$name: line")
        val reopened = reopened(events.dropLast(1)).store
        assertThrows<IllegalArgumentException>("$name: written after reopening") { reopened.append(events.last()) }
    }

    /** [events] keep every rule: they append, read back, and append after reopening before the last one. */
    suspend fun assertAccepts(
        name: String,
        events: List<SessionEvent>,
    ) {
        val (files, store) = newStore()
        events.forEach { store.append(it) }
        store.close()
        assertEquals(
            events,
            SessionStore
                .open(files, clock)
                .log.records
                .drop(1)
                .map { it.event },
            name,
        )
        reopened(events.dropLast(1)).store.append(events.last())
    }

    companion object {
        val clock = Clock { 0 }
        val goOn = UserMessage(UserMessageKind.USER, listOf(Part.Text("继续")))
        val injected = UserMessage(UserMessageKind.INJECTED, listOf(Part.Text("timer fired")), source = "timer")
        val context = UserMessage(UserMessageKind.RUNTIME_CONTEXT, listOf(Part.Text("foreground: launcher")))

        /** A first turn the user's input opened and a stop with [cause] ended. */
        fun stoppedRun(cause: StopCause): List<SessionEvent> = stoppedTurn() + TaskStopped(cause)

        /** The first turn up to its `turn/end(STOPPED)`. */
        fun stoppedTurn(): List<SessionEvent> =
            listOf(
                TurnStarted(1),
                goOn,
                StepStarted(1, 1),
                StepEnded(1, 1, StepOutcome.CANCELLED),
                TurnEnded(1, TurnEndReason.STOPPED),
            )

        /** What a stop with [cause] needs before the session goes on: `session/resumed` after SHUTDOWN or ERROR. */
        fun resume(cause: StopCause): List<SessionEvent> =
            if (cause == StopCause.SHUTDOWN || cause == StopCause.ERROR) listOf(SessionResumed(0)) else emptyList()

        /** The user's input opening turn 2 and its first step. */
        val nextTurn: List<SessionEvent> = listOf(TurnStarted(2), goOn, StepStarted(2, 1))

        fun call(id: String) =
            ToolCall(
                ToolCallId(id),
                SessionSamples.task,
                SessionSamples.session,
                "phone_tap",
                JsonObject(emptyMap()),
                "r",
            )

        fun tap(id: String) = Part.ToolCall(ToolCallId(id), "phone_tap", """{"ref":"e2","reason":"r"}""")

        /** A complete reply asking for [ids]. */
        fun replyWith(vararg ids: String) =
            AssistantMessage(
                1,
                SessionSamples.provider,
                SessionSamples.model,
                listOf(Part.Text("点发送")) + ids.map(::tap),
                StopReason.TOOL_USE,
            )

        fun result(
            id: String,
            status: ToolStatus,
        ) = ToolResultRecorded(ToolCallId(id), "phone_tap", ToolResult(status, listOf(Part.Text("x"))))
    }
}
