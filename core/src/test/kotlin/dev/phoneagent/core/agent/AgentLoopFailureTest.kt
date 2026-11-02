@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.SessionFlushException
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Failures, log failures and resume (phase1 §3.7, §3.9; D14). */
class AgentLoopFailureTest {
    private val texts = LoopTexts()

    private fun ToolResult.text(): String = (content.single() as Part.Text).text

    private fun failingPreStep(onTurn: Int): Interceptor<PreStepInput, PreStepResult> =
        Interceptor { input, chain ->
            if (input.turn == onTurn) error("context provider crashed")
            chain.proceed(input)
        }

    @Test
    fun `an interceptor failure ends that turn only`() =
        runTest {
            val model = FakeChatModel().answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    interceptors = Interceptors(requestError = emptyList(), preStep = listOf(failingPreStep(1))),
                )
            val loop = harness.start()

            loop.followup(text("one"))
            advanceUntilIdle()
            val failed = harness.events().only<TurnEnded>().single()
            assertEquals(TurnEndReason.FAILED, failed.reason)
            assertEquals("failed with java.lang.IllegalStateException", failed.detail)
            assertEquals(Phase.Idle, loop.phase.value)

            loop.followup(text("two"))
            loop.awaitPhase(Phase.Done)
            assertEquals(TurnEnded(2, TurnEndReason.COMPLETED), harness.events().last())
        }

    @Test
    fun `a call whose step failed before it ran is NOT_STARTED, one that ran is OUTCOME_UNKNOWN`() =
        runTest {
            val crashBefore =
                Interceptor<PreExecuteInput, dev.phoneagent.core.policy.Verdict> {
                    _,
                    _,
                    ->
                    error("guard bug")
                }
            val tool = FakeTool("tap", risk = RiskClass.WRITE)
            val before =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "tap"), call("c2", "tap")),
                    tools = listOf(tool),
                    interceptors = Interceptors(requestError = emptyList(), toolPreExecute = listOf(crashBefore)),
                )
            val first = before.start()
            first.followup(text("go"))
            advanceUntilIdle()
            val notStarted = before.events().only<ToolResultRecorded>()
            assertEquals(listOf(ToolStatus.NOT_STARTED, ToolStatus.NOT_STARTED), notStarted.map { it.result.status })
            assertEquals(texts.notStarted, notStarted[0].result.text())
            assertEquals(StepEnded(1, 1, StepOutcome.FAILED), before.events().only<StepEnded>().single())
            assertTrue(tool.calls.isEmpty())

            val crashAfter = Interceptor<PostExecuteInput, PostExecuteResult> { _, _ -> error("post bug") }
            val after =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "tap")),
                    tools = listOf(tool),
                    interceptors = Interceptors(requestError = emptyList(), toolPostExecute = listOf(crashAfter)),
                )
            val second = after.start()
            second.followup(text("go"))
            advanceUntilIdle()
            val unknown = after.events().only<ToolResultRecorded>().single()
            assertEquals(ToolStatus.OUTCOME_UNKNOWN, unknown.result.status)
            assertEquals("${texts.outcomeUnknown}${texts.mayHaveTakenEffect}", unknown.result.text())
            assertEquals(1, tool.calls.size)
            val dispatched = after.records().single { it.event is ToolCallRecorded }.seq
            assertEquals(listOf(dispatched), after.records().single { it.event is ToolResultRecorded }.sources)
        }

    @Test
    fun `a model bug fails the turn and keeps what was delivered`() =
        runTest {
            val model =
                FakeChatModel().then { _, onDelta ->
                    onDelta(Delta.Text("半句"))
                    error("adapter bug")
                }
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()
            val events = harness.events()
            assertEquals(listOf(Part.Text("半句")), events.only<AssistantMessage>().single { it.interrupted }.parts)
            assertEquals(TurnEndReason.FAILED, events.only<TurnEnded>().single().reason)
            assertEquals(Phase.Idle, loop.phase.value)
        }

    @Test
    fun `a request error without retry fails the step and the turn`() =
        runTest {
            val model = FakeChatModel().fails(ModelError.Server(503, "unavailable"), delivered = "正在")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(listOf(Part.Text("正在")), events.only<AssistantMessage>().single().parts)
            assertEquals(
                AssistantAttempt(1, ModelErrorKind.SERVER, "server error 503: unavailable", httpStatus = 503),
                events.only<AssistantAttempt>().single(),
            )
            assertEquals(StepEnded(1, 1, StepOutcome.FAILED), events.only<StepEnded>().single())
            assertEquals(TurnEnded(1, TurnEndReason.FAILED, "server error 503: unavailable"), events.last())
            assertEquals(Phase.Idle, loop.phase.value)
        }

    @Test
    fun `the default retry honours retry-after and logs each retry`() =
        runTest {
            val model =
                FakeChatModel()
                    .fails(ModelError.RateLimited(retryAfterMs = 2_000))
                    .fails(ModelError.Overloaded(retryAfterMs = 3_000))
                    .answers("ok")
            val harness = LoopHarness(this, model, interceptors = Interceptors())
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            assertEquals(
                listOf(
                    RequestRetry(2, 2_000, ModelErrorKind.RATE_LIMITED),
                    RequestRetry(3, 3_000, ModelErrorKind.OVERLOADED),
                ),
                events.only<RequestRetry>(),
            )
            assertEquals(listOf(2_000L, 3_000L), events.only<AssistantAttempt>().map { it.retryAfterMs })
            assertEquals(3, events.only<AssistantMessage>().single().attempt)
            assertEquals(5_000, currentTime)
            assertEquals(1, events.only<StepStarted>().size, "retries stay in the step")
        }

    @Test
    fun `a model that cancels itself is a retryable network error`() =
        runTest {
            val model =
                FakeChatModel()
                    .then { _, _ -> throw CancellationException("adapter cancelled itself") }
                    .answers("ok")
            val harness = LoopHarness(this, model, interceptors = Interceptors())
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            val attempt = harness.events().only<AssistantAttempt>().single()
            assertEquals(ModelErrorKind.NETWORK, attempt.error)
            assertEquals("network: model call cancelled unexpectedly", attempt.message)
        }

    @Test
    fun `a failed flush before a request stops the task with the failure recorded`() =
        runTest {
            val model = FakeChatModel()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            harness.storage.syncFailures = 1

            loop.followup(text("go"))
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.awaitStopped())

            assertEquals(0, model.started.value, "nothing is sent unless the log is durable")
            val ended = harness.events().only<TurnEnded>().single()
            assertEquals(TurnEndReason.FAILED, ended.reason)
            assertTrue(ended.detail!!.startsWith("session log failure:"), ended.detail)
            assertEquals(StepEnded(1, 1, StepOutcome.FAILED), harness.events().only<StepEnded>().single())
        }

    @Test
    fun `a session log failure's turn end names the exception's class only`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel())
            val loop = harness.start()
            harness.storage.syncFailures = 1

            loop.followup(text("go"))
            loop.awaitStopped()

            val ended = harness.events().only<TurnEnded>().single()
            assertEquals("session log failure: ${SessionFlushException::class.java.name}", ended.detail)
            val log =
                harness.storage.files.values
                    .joinToString("\n") { it.decodeToString() }
            assertTrue("injected sync failure" !in log, "the storage's message stays out of the log")
        }

    @Test
    fun `every call is flushed before it runs, READ ones too, since it records tool-started first`() =
        runTest {
            lateinit var harness: LoopHarness
            var atReply = 0
            val flushes = mutableMapOf<String, Int>()
            val look =
                FakeTool("look") { call, _ ->
                    flushes[call.name] = harness.storage.syncCount - atReply
                    ToolResult.ok("seen")
                }
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { call, _ ->
                    flushes[call.name] = harness.storage.syncCount - atReply
                    ToolResult.ok("tapped")
                }
            val model = FakeChatModel()
            for (name in listOf("look", "tap")) {
                model.then { _, _ ->
                    atReply = harness.storage.syncCount
                    ChatResponse(listOf(call("c-$name", name)), StopReason.TOOL_USE)
                }
            }
            model.answers("ok")
            harness = LoopHarness(this, model, tools = listOf(look, tap))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(mapOf("look" to 1, "tap" to 1), flushes)
        }

    @Test
    fun `when the log cannot be reopened the task stops and the next open repairs it`() =
        runTest {
            lateinit var harness: LoopHarness
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { _, _ ->
                    // The result's append tears the last line, and repairing it on reopen fails too.
                    harness.storage.appendFailures = 1
                    harness.storage.failWrite = true
                    ToolResult.ok("tapped")
                }
            harness = LoopHarness(this, FakeChatModel().calls(call("c1", "tap")), tools = listOf(tap))
            val loop = harness.start()
            loop.followup(text("go"))
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.awaitStopped())

            harness.storage.failWrite = false
            harness.resume()
            val events = harness.events()
            assertEquals(
                ToolStatus.OUTCOME_UNKNOWN,
                events
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
            assertEquals(TurnEndReason.FAILED, events.only<TurnEnded>().single().reason)
            assertTrue((events.last() as SessionResumed).droppedTailBytes > 0)
        }

    @Test
    fun `a storage bug while closing still ends in Stopped`() =
        runTest {
            val model = FakeChatModel().hangs()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            harness.storage.beforeAppend = { throw IllegalStateException("storage bug") }
            loop.stop(StopCause.USER)
            assertEquals(Phase.Stopped(StopCause.USER), loop.awaitStopped())
            assertEquals("storage bug", harness.uncaught.single().message, "the bug is reported, not swallowed")
        }

    @Test
    fun `a failed append stops the task and the reopened log is repaired`() =
        runTest {
            lateinit var harness: LoopHarness
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { _, _ ->
                    harness.storage.appendFailures = 1
                    ToolResult.ok("tapped")
                }
            harness = LoopHarness(this, FakeChatModel().calls(call("c1", "tap")), tools = listOf(tap))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitStopped()

            val events = harness.events()
            assertEquals(
                ToolStatus.OUTCOME_UNKNOWN,
                events
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
                "it ran",
            )
            assertEquals(TurnEndReason.FAILED, events.only<TurnEnded>().single().reason)
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.phase.value)
        }

    @Test
    fun `when even the repair fails the next open repairs the log`() =
        runTest {
            lateinit var harness: LoopHarness
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { _, _ ->
                    harness.storage.appendFailures = 2
                    ToolResult.ok("tapped")
                }
            harness = LoopHarness(this, FakeChatModel().calls(call("c1", "tap")), tools = listOf(tap))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitStopped()
            assertTrue(harness.events().only<TurnEnded>().isEmpty())

            val resumed = harness.resume()
            val events = harness.events()
            assertEquals(
                ToolStatus.OUTCOME_UNKNOWN,
                events
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
            assertEquals(TurnEndReason.FAILED, events.only<TurnEnded>().single().reason)
            assertTrue(events.last() is SessionResumed)
            assertEquals(Phase.Idle, resumed.phase.value)
        }

    @Test
    fun `a failure while closing a failed turn stops the task`() =
        runTest {
            lateinit var harness: LoopHarness
            val crash =
                Interceptor<PreStepInput, PreStepResult> { _, _ ->
                    harness.storage.appendFailures = 1
                    error("crash")
                }
            harness =
                LoopHarness(
                    this,
                    FakeChatModel(),
                    interceptors = Interceptors(requestError = emptyList(), preStep = listOf(crash)),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.awaitStopped())
            assertEquals(
                TurnEndReason.FAILED,
                harness
                    .events()
                    .only<TurnEnded>()
                    .single()
                    .reason,
            )
        }

    @Test
    fun `input that cannot be recorded stops the task`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel())
            val loop = harness.start()
            harness.storage.appendFailures = 1
            loop.followup(text("go"))
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.awaitStopped())
            assertEquals(listOf(LoopHarness.ATTENDED), harness.events(), "only task/start")
        }

    @Test
    fun `stopping with a failing log still reaches Stopped`() =
        runTest {
            val model = FakeChatModel().hangs(delivered = "x")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            loop.followup(text("queued"))
            advanceUntilIdle()
            harness.storage.failAppend = true
            loop.stop(StopCause.USER)
            assertEquals(Phase.Stopped(StopCause.USER), loop.awaitStopped())
        }

    /** Writes a log as the process left it when it died mid-turn. */
    private suspend fun crashedSession(harness: LoopHarness) {
        val store =
            SessionStore.create(
                harness.files,
                LoopHarness.SESSION,
                LoopHarness.TASK,
                LoopHarness.ATTENDED,
                harness.setup.clock,
            )
        val queued = InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("later")))
        val dispatched =
            ToolCall(ToolCallId("c1"), LoopHarness.TASK, LoopHarness.SESSION, "ask", JsonObject(emptyMap()), "问一下")
        store.append(
            InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("go"))))),
        )
        store.append(TurnStarted(1))
        store.append(InboxSpliced(InboxQueue.NEXT_TURN, 1))
        store.append(UserMessage(UserMessageKind.USER, listOf(Part.Text("go"))))
        store.append(StepStarted(1, 1))
        store.append(
            AssistantMessage(
                1,
                FakeChatModel.INFO.provider,
                FakeChatModel.INFO.model,
                listOf(call("c1", "ask"), call("c2", "ask")),
                StopReason.TOOL_USE,
            ),
        )
        store.append(ToolCallRecorded(dispatched), sources = listOf(7))
        val allowed =
            store.append(
                PolicyVerdictRecorded(
                    ToolCallId("c1"),
                    SecurityLevel.STANDARD,
                    ActionIntent(IntentKind.READ),
                    Verdict.Allow,
                ),
            )
        // c1 ran (it asked from inside the tool); c2 was never dispatched.
        store.append(ToolStarted(ToolCallId("c1"), allowed.seq))
        store.append(QuestionAsked(PromptId("prompt-8"), ToolCallId("c1"), Question("谁？")))
        store.append(InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(queued)))
        store.flush()
        store.close()
    }

    @Test
    fun `resume repairs a turn the process died in and continues only on new input`() =
        runTest {
            val model = FakeChatModel().answers("first").answers("second")
            val harness = LoopHarness(this, model, tools = listOf(FakeTool("ask")))
            crashedSession(harness)
            val torn = "{\"seq\":10,".encodeToByteArray()
            harness.storage.files[harness.files.log.value] =
                harness.storage.files.getValue(harness.files.log.value) + torn

            val loop = harness.resume()
            advanceUntilIdle()

            val events = harness.events()
            val results = events.only<ToolResultRecorded>()
            assertEquals(listOf(ToolStatus.OUTCOME_UNKNOWN, ToolStatus.NOT_STARTED), results.map { it.result.status })
            assertEquals(PromptWithdrawn(PromptId("prompt-8")), events.only<PromptWithdrawn>().single())
            assertEquals(StepEnded(1, 1, StepOutcome.FAILED), events.only<StepEnded>().single())
            assertEquals(
                TurnEnded(1, TurnEndReason.FAILED, "the app stopped before this turn finished"),
                events.only<TurnEnded>().single(),
            )
            assertEquals(SessionResumed(torn.size), events.last())
            assertEquals(Phase.Idle, loop.phase.value)
            assertEquals(0, model.started.value, "nothing continues on its own")

            loop.followup(text("now"))
            loop.awaitPhase(Phase.Done)
            assertEquals(
                listOf(2, 3),
                harness
                    .events()
                    .only<TurnStarted>()
                    .drop(1)
                    .map { it.turn },
            )
            val inputs = harness.events().only<UserMessage>().map { (it.parts.single() as Part.Text).text }
            assertEquals(listOf("go", "later", "now"), inputs, "the queued followup runs first")
        }

    @Test
    fun `resume of a finished session keeps its phase and turn numbers`() =
        runTest {
            val model = FakeChatModel().answers("one").answers("two")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("a"))
            loop.awaitPhase(Phase.Done)
            loop.stop(StopCause.SHUTDOWN)
            loop.awaitStopped()

            val resumed = harness.resume()
            assertEquals(Phase.Done, resumed.phase.value)
            assertEquals(SessionResumed(0), harness.events().last())
            resumed.followup(text("b"))
            advanceUntilIdle()
            assertEquals(TurnEnded(2, TurnEndReason.COMPLETED), harness.events().last())
        }

    /** A session that was stopped cleanly, with its log closed. */
    private suspend fun TestScope.stoppedSession(harness: LoopHarness) {
        val loop = harness.start()
        loop.stop(StopCause.SHUTDOWN)
        loop.awaitStopped()
        assertEquals(0, harness.storage.openAppenders)
    }

    @Test
    fun `a resume that fails after opening the log closes it`() =
        runTest {
            val harness = LoopHarness(this)
            stoppedSession(harness)
            harness.storage.failSync = true
            val failure =
                try {
                    harness.resume()
                    null
                } catch (expected: SessionFlushException) {
                    expected
                }
            assertTrue(failure != null)
            assertEquals(0, harness.storage.openAppenders, "the store is not leaked")
        }

    @Test
    fun `a resume cancelled after opening the log closes it`() =
        runTest {
            val harness = LoopHarness(this)
            stoppedSession(harness)
            val attempt =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coroutineContext.job.cancel() // e.g. the service stops while the task is being resumed
                    harness.resume()
                }
            advanceUntilIdle()
            assertTrue(attempt.isCancelled)
            assertEquals(0, harness.storage.openAppenders, "the store is not leaked")
        }

    @Test
    fun `a session of another task is not resumed`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel())
            SessionStore
                .create(
                    harness.files,
                    LoopHarness.SESSION,
                    TaskId("someone-else"),
                    LoopHarness.ATTENDED,
                    harness.setup.clock,
                ).close()
            val failure =
                try {
                    harness.resume()
                    null
                } catch (expected: IllegalArgumentException) {
                    expected
                }
            assertTrue(failure?.message.orEmpty().contains("someone-else"), "$failure")
        }
}
