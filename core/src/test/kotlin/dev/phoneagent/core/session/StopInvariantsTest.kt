package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.session.StopLogs.Companion.context
import dev.phoneagent.core.session.StopLogs.Companion.goOn
import dev.phoneagent.core.session.StopLogs.Companion.injected
import dev.phoneagent.core.session.StopLogs.Companion.nextTurn
import dev.phoneagent.core.session.StopLogs.Companion.resumed
import dev.phoneagent.core.session.StopLogs.Companion.stoppedRun
import dev.phoneagent.core.session.StopLogs.Companion.stoppedTurn
import dev.phoneagent.core.session.StopLogs.Unknown
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A stop ends the current run only. Every `task/stopped` is followed by `session/resumed` (the
 * stopped runtime instance writes nothing more), and the session continues when the user's own
 * input opens a new turn, never on its own; a stop the process did not finish is recovered as an
 * error. The rules hold where records are written and where they are read, unknown ignorable
 * records included.
 */
class StopInvariantsTest {
    private val logs = StopLogs()

    private val header = SessionSamples.events.filterIsInstance<RequestHeader>().first()
    private val side = SessionSamples.events.filterIsInstance<SideRequest>().first()
    private val reply = SessionSamples.events.filterIsInstance<AssistantMessage>().first()

    @Test
    fun `after any stop the user's next input continues the session in a new turn`() =
        runTest {
            StopCause.entries.forEach { cause ->
                logs.assertAccepts("$cause", stoppedRun(cause) + resumed + nextTurn)
            }
        }

    @Test
    fun `after a stop nothing runs before the user's input opens a new turn`() =
        runTest {
            val afterStop =
                mapOf(
                    "a step right after the stop" to listOf(StepStarted(1, 2)),
                    "a model request right after the stop" to listOf(header),
                    "a side request right after the stop" to listOf(side),
                    "the user's input outside a turn" to listOf(goOn),
                    "a step before the user's input" to listOf(TurnStarted(2), StepStarted(2, 1)),
                    "a model request before the user's input" to listOf(TurnStarted(2), header),
                    "a reply before the user's input" to listOf(TurnStarted(2), reply),
                    "a step after injected context only" to listOf(TurnStarted(2), injected, StepStarted(2, 1)),
                    "a step after runtime context only" to listOf(TurnStarted(2), context, StepStarted(2, 1)),
                    "a call before the user's input" to listOf(TurnStarted(2), ToolCallRecorded(StopLogs.call("c9"))),
                    "the screen read for a call" to listOf(ScreenObserved(ToolCallId("c9"), SessionSamples.snapshot)),
                )
            StopCause.entries.forEach { cause ->
                afterStop.forEach { (name, events) ->
                    logs.assertRejectsLast("$cause, $name", stoppedRun(cause) + resumed + events)
                }
            }
        }

    @Test
    fun `only the user's own input ends the wait, not a resume or background context`() =
        runTest {
            val crashed =
                listOf(
                    TurnStarted(1),
                    goOn,
                    StepStarted(1, 1),
                    StepEnded(1, 1, StepOutcome.FAILED),
                    TurnEnded(1, TurnEndReason.FAILED),
                    SessionResumed(0),
                )
            val backgroundOnly = listOf(TurnStarted(2), injected, context, StepStarted(2, 1))
            logs.assertRejectsLast("a step after a crash and resume", crashed + StepStarted(1, 2))
            logs.assertRejectsLast("background input after a crash and resume", crashed + backgroundOnly)
            logs.assertRejectsLast(
                "a resume after a user stop",
                stoppedRun(StopCause.USER) + SessionResumed(0) + backgroundOnly,
            )
            logs.assertRejectsLast(
                "background input after a stop",
                stoppedRun(StopCause.SHUTDOWN) + SessionResumed(0) + backgroundOnly,
            )
            logs.assertAccepts("the user's input after a crash", crashed + nextTurn)
            logs.assertAccepts(
                "the screen read on its own while waiting",
                crashed + ScreenObserved(null, SessionSamples.snapshot) + nextTurn,
            )
            val unknownFirst = stoppedRun(StopCause.USER) + resumed + Unknown + backgroundOnly
            assertEquals(
                unknownFirst.size + 2,
                logs.corruptLine(unknownFirst),
                "an unknown record does not end the wait",
            )
        }

    @Test
    fun `background input queued before the user's input runs with it, but never without it`() =
        runTest {
            val queued =
                listOf(
                    InboxSpliced(
                        InboxQueue.NEXT_STEP,
                        0,
                        listOf(InboxEntry(InboxEntryKind.INJECT, injected.parts, "timer")),
                    ),
                    InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(InboxEntry(InboxEntryKind.FOLLOWUP, goOn.parts))),
                    TurnStarted(2),
                )
            val backgroundFirst =
                listOf(InboxSpliced(InboxQueue.NEXT_STEP, 1), injected, InboxSpliced(InboxQueue.NEXT_TURN, 1), goOn)
            val userFirst =
                listOf(InboxSpliced(InboxQueue.NEXT_TURN, 1), goOn, InboxSpliced(InboxQueue.NEXT_STEP, 1), injected)
            logs.assertAccepts(
                "the queued background message first",
                stoppedRun(StopCause.USER) + resumed + queued + backgroundFirst + StepStarted(2, 1),
            )
            logs.assertAccepts(
                "the user's input first",
                stoppedRun(StopCause.USER) + resumed + queued + userFirst + StepStarted(2, 1),
            )
            logs.assertRejectsLast(
                "the queued background message alone",
                stoppedRun(StopCause.USER) + resumed + queued + InboxSpliced(InboxQueue.NEXT_STEP, 1) + injected +
                    StepStarted(2, 1),
            )
        }

    @Test
    fun `every stop is resumed before anything else, so nothing of the stopped run comes after it`() =
        runTest {
            StopCause.entries.forEach { cause ->
                logs.assertRejectsLast("$cause, turn/start", stoppedRun(cause) + TurnStarted(2))
                logs.assertRejectsLast("$cause, the user's input", stoppedRun(cause) + goOn)
                logs.assertRejectsLast(
                    "$cause, level change",
                    stoppedRun(cause) + TaskLevelChanged(SecurityLevel.CAUTIOUS),
                )
                logs.assertRejectsLast(
                    "$cause, inbox",
                    stoppedRun(cause) +
                        InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(InboxEntry(InboxEntryKind.FOLLOWUP, goOn.parts))),
                )
            }
            logs.assertAccepts(
                "a level change while waiting",
                stoppedRun(StopCause.USER) + resumed + TaskLevelChanged(SecurityLevel.CAUTIOUS) + nextTurn,
            )
            logs.assertAccepts(
                "a stop while waiting, resumed again",
                stoppedRun(StopCause.USER) + resumed + TaskStopped(StopCause.SHUTDOWN) + resumed + nextTurn,
            )
            logs.assertRejectsLast(
                "a stop while waiting, then the next turn",
                stoppedRun(StopCause.USER) + resumed + TaskStopped(StopCause.SHUTDOWN) + TurnStarted(2),
            )
        }

    @Test
    fun `a stopped turn is followed by task stopped, and a stop or a resume leaves no turn open`() =
        runTest {
            logs.assertRejectsLast("turn/end(STOPPED), then turn/start", stoppedTurn() + TurnStarted(2))
            logs.assertRejectsLast("turn/end(STOPPED), then session/resumed", stoppedTurn() + SessionResumed(0))
            logs.assertRejectsLast(
                "task/stopped in an open turn",
                listOf(TurnStarted(1), goOn, TaskStopped(StopCause.USER)),
            )
            logs.assertRejectsLast("session/resumed in an open turn", listOf(TurnStarted(1), goOn, SessionResumed(0)))
            logs.assertAccepts(
                "a stop between turns",
                listOf(TurnStarted(1), goOn, TurnEnded(1, TurnEndReason.COMPLETED), TaskStopped(StopCause.LIMIT)),
            )
            logs.assertAccepts(
                "a stop before the turn's input",
                stoppedRun(StopCause.USER) + resumed + TurnStarted(2) + TurnEnded(2, TurnEndReason.STOPPED) +
                    TaskStopped(StopCause.USER),
            )
        }

    @Test
    fun `unknown ignorable records are transparent to the stop rules, alike when read and when reopened`() =
        runTest {
            val shutdown = stoppedRun(StopCause.SHUTDOWN) + Unknown
            assertEquals(
                shutdown.size + 3,
                logs.corruptLine(shutdown + TurnStarted(2)),
                "turn/start without session/resumed",
            )
            val userStop = stoppedRun(StopCause.USER) + Unknown
            assertEquals(
                userStop.size + 3,
                logs.corruptLine(userStop + TurnStarted(2)),
                "turn/start without session/resumed after a user stop",
            )
            assertNull(logs.corruptLine(shutdown + SessionResumed(0) + nextTurn), "the unknown record itself is fine")
            val stopped = stoppedTurn() + Unknown
            assertEquals(
                stopped.size + 3,
                logs.corruptLine(stopped + TurnStarted(2)),
                "turn/start without task/stopped",
            )
            assertNull(
                logs.corruptLine(stopped + TaskStopped(StopCause.USER) + resumed + nextTurn),
                "between turn/end and task/stopped",
            )
            assertNull(
                logs.corruptLine(stoppedRun(StopCause.USER) + resumed + Unknown + nextTurn),
                "while the stopped task waits",
            )
            listOf(
                shutdown to listOf<SessionEvent>(SessionResumed(0)),
                stopped to listOf<SessionEvent>(TaskStopped(StopCause.ERROR), SessionResumed(0)),
            ).forEach { (written, recovery) ->
                val (files, _) = logs.newStore()
                logs.storage.writeAtomically(files.log, logs.bytes(written))
                val store = SessionStore.open(files, StopLogs.clock).store
                assertThrows<IllegalArgumentException>("turn/start on reopening") { store.append(TurnStarted(2)) }
                (recovery + nextTurn).forEach { store.append(it) }
                store.close()
                val read = SessionStore.read(files)
                assertEquals(1, read.skipped.size)
                assertEquals(recovery + nextTurn, read.records.takeLast(recovery.size + nextTurn.size).map { it.event })
            }
        }

    @Test
    fun `a stop the process did not finish is recovered as an error`() =
        runTest {
            val unfinished = logs.reopened(stoppedTurn())
            assertNull(unfinished.log.truncatedTail)
            val store = unfinished.store
            listOf(SessionResumed(0), TurnStarted(2), StepStarted(1, 2)).forEach { event ->
                assertThrows<IllegalArgumentException>("$event before task/stopped") { store.append(event) }
            }
            (listOf(TaskStopped(StopCause.ERROR), SessionResumed(0)) + nextTurn).forEach { store.append(it) }

            val (files, _) = logs.newStore()
            val whole = logs.bytes(stoppedRun(StopCause.USER))
            val torn = 7
            logs.storage.writeAtomically(files.log, whole.copyOf(whole.size - torn))
            val halfWritten = SessionStore.open(files, StopLogs.clock)
            assertEquals(
                stoppedTurn(),
                halfWritten.log.records
                    .drop(1)
                    .map { it.event },
            )
            val dropped = halfWritten.log.truncatedTail?.bytes ?: 0
            assertEquals(whole.size - torn - logs.bytes(stoppedTurn()).size, dropped)
            assertThrows<IllegalArgumentException> { halfWritten.store.append(SessionResumed(dropped)) }
            (
                listOf(
                    TaskStopped(StopCause.ERROR),
                    SessionResumed(dropped),
                ) + nextTurn
            ).forEach { halfWritten.store.append(it) }
        }

    @Test
    fun `after a user stop and a restart the user's input continues after session resumed`() =
        runTest {
            val restarted = logs.reopened(stoppedRun(StopCause.USER)).store
            val followup = InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("继续")))
            listOf(
                SessionResumed(0),
                InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(followup)),
                TurnStarted(2),
                InboxSpliced(InboxQueue.NEXT_TURN, 1),
                goOn,
                StepStarted(2, 1),
            ).forEach { restarted.append(it) }
        }

    @Test
    fun `reopening keeps the stop state`() =
        runTest {
            val shutdown = logs.reopened(stoppedRun(StopCause.SHUTDOWN)).store
            assertThrows<IllegalArgumentException>(
                "turn/start before session/resumed",
            ) { shutdown.append(TurnStarted(2)) }
            (resumed + nextTurn).forEach { shutdown.append(it) }

            val user = logs.reopened(stoppedRun(StopCause.USER)).store
            assertThrows<IllegalArgumentException>("turn/start before session/resumed") { user.append(TurnStarted(2)) }
            user.append(SessionResumed(0))
            assertThrows<IllegalArgumentException>("a step before the user's input") { user.append(StepStarted(1, 2)) }
            user.append(TurnStarted(2))
            user.append(injected)
            assertThrows<IllegalArgumentException>(
                "a step after injected context only",
            ) { user.append(StepStarted(2, 1)) }
            user.append(goOn)
            user.append(StepStarted(2, 1))
        }
}
