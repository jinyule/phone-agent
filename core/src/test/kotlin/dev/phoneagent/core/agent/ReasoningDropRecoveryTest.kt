package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.testing.FakeChatModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [ReasoningDropRecovery]: one retry without the stale reasoning per mismatch, decided from the log. */
class ReasoningDropRecoveryTest {
    private val mismatch = ModelError.PrefixBindingMismatch("signature no longer valid")

    private fun reasoning(kind: ReasoningKind) =
        Part.Reasoning(FakeChatModel.INFO.provider, FakeChatModel.INFO.model, kind, JsonNull)

    private val thinking = reasoning(ReasoningKind.ANTHROPIC_THINKING)
    private val compaction = reasoning(ReasoningKind.ANTHROPIC_COMPACTION)

    private fun reply(vararg parts: Part) =
        AssistantMessage(1, FakeChatModel.INFO.provider, FakeChatModel.INFO.model, parts.toList(), StopReason.END_TURN)

    private fun interrupted(vararg parts: Part) =
        AssistantMessage(
            1,
            FakeChatModel.INFO.provider,
            FakeChatModel.INFO.model,
            parts.toList(),
            null,
            interrupted = true,
        )

    /** A log of [events] at seq 1, 2, …, and what the interceptor appends to it. */
    private class Log(
        events: List<SessionEvent>,
    ) : StepLog {
        val records = events.mapIndexed { index, event -> SessionRecord(index + 1L, 0, event) }.toMutableList()
        val appended = mutableListOf<SessionEvent>()

        override val taskId = TaskId("t")

        override suspend fun records() = records.toList()

        override suspend fun appendRuntimeContext(parts: List<Part>) = error("unused")

        override suspend fun append(
            event: SessionEvent,
            surfaceOp: SurfaceOp,
            sources: List<Long>,
        ): SessionRecord {
            appended += event
            return SessionRecord(records.size + 1L, 0, event).also { records += it }
        }
    }

    private val passed = mutableListOf<ModelError>()
    private val end = Chain<RequestFailure, Recovery> { input -> Recovery.Fail.also { passed += input.error } }

    private suspend fun recover(
        log: Log,
        error: ModelError = mismatch,
    ): Recovery = ReasoningDropRecovery().intercept(RequestFailure(error, 2, 0, log), end)

    @Test
    fun `a mismatch drops the reasoning up to the latest complete reply and retries at once`() =
        runTest {
            // The latest reply has no reasoning of its own; the drop still reaches it.
            val log =
                Log(listOf(TurnStarted(1), reply(thinking, Part.Text("看")), TurnStarted(2), reply(Part.Text("好"))))
            assertEquals(Recovery.Retry(0), recover(log))
            assertEquals(listOf<SessionEvent>(ReasoningDropped(4)), log.appended)
            assertEquals(emptyList<ModelError>(), passed)
        }

    @Test
    fun `an interrupted reply is not the one the drop reaches, nor a reason to drop`() =
        runTest {
            val log = Log(listOf(TurnStarted(1), reply(thinking), interrupted(Part.Text("正在"))))
            assertEquals(Recovery.Retry(0), recover(log))
            assertEquals(listOf<SessionEvent>(ReasoningDropped(2)), log.appended)
            val onlyInterrupted = Log(listOf(TurnStarted(1), interrupted(thinking, Part.Text("正在"))))
            assertEquals(Recovery.Fail, recover(onlyInterrupted))
            assertEquals(emptyList<SessionEvent>(), onlyInterrupted.appended)
        }

    @Test
    fun `with nothing left to drop the error goes down the chain`() =
        runTest {
            val cases =
                listOf(
                    listOf(TurnStarted(1)),
                    listOf(TurnStarted(1), reply(Part.Text("好"))),
                    listOf(TurnStarted(1), reply(compaction, Part.Text("好"))),
                    // A mismatch again right after the drop: the retry would send the same request.
                    listOf(TurnStarted(1), reply(thinking), ReasoningDropped(2)),
                )
            for (events in cases) {
                val log = Log(events)
                assertEquals(Recovery.Fail, recover(log), "$events")
                assertEquals(emptyList<SessionEvent>(), log.appended, "$events")
            }
            assertEquals(List(cases.size) { mismatch }, passed)
        }

    @Test
    fun `reasoning of a reply after the latest drop is dropped by a new one`() =
        runTest {
            val events =
                listOf(
                    TurnStarted(1),
                    reply(thinking),
                    reply(thinking),
                    ReasoningDropped(2),
                    ReasoningDropped(3),
                    reply(Part.Text("好")),
                    reply(thinking, Part.Text("看")),
                )
            val log = Log(events)
            assertEquals(Recovery.Retry(0), recover(log))
            assertEquals(listOf<SessionEvent>(ReasoningDropped(7)), log.appended)
            val covered = Log(events.dropLast(1) + ReasoningDropped(6))
            assertEquals(
                Recovery.Fail,
                recover(covered),
                "the latest drop covers every reply with reasoning, not only the first one",
            )
        }

    @Test
    fun `other errors go down the chain untouched`() =
        runTest {
            val log = Log(listOf(TurnStarted(1), reply(thinking)))
            val overflow = ModelError.ContextOverflow("too long")
            assertEquals(Recovery.Fail, recover(log, overflow))
            assertEquals(listOf<ModelError>(overflow), passed)
            assertEquals(emptyList<SessionEvent>(), log.appended)
        }
}
