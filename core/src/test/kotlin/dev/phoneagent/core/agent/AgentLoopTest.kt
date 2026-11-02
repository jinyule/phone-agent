@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.session.BlobStore
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.EndReason
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Turns, steps and their normal endings (phase1 §3.7, §7.2). */
class AgentLoopTest {
    private val probe = FakeTool("probe")

    @Test
    fun `a followup runs one turn to the final answer and the task is done`() =
        runTest {
            val model = FakeChatModel().answers("你好")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            assertEquals(Phase.Idle, loop.phase.value)

            loop.followup(text("hi"))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            assertEquals(
                listOf(
                    TaskStarted::class,
                    InboxSpliced::class,
                    TurnStarted::class,
                    InboxSpliced::class,
                    UserMessage::class,
                    StepStarted::class,
                    RequestHeader::class,
                    dev.phoneagent.core.session.AssistantMessage::class,
                    StepEnded::class,
                    TurnEnded::class,
                ),
                events.map { it::class },
            )
            assertEquals(TurnEnded(1, TurnEndReason.COMPLETED), events.last())
            assertEquals(listOf(Message(Role.USER, listOf(Part.Text("hi")))), model.requests.single().messages)
            model.assertConsumed()
        }

    @Test
    fun `inject waits for a followup and goes before it in the same step`() =
        runTest {
            val model = FakeChatModel().answers("ok")
            val harness = LoopHarness(this, model)
            val loop = harness.start()

            loop.inject(ContextInput("timer", listOf(Part.Text("timer fired"))))
            advanceUntilIdle()
            assertEquals(0, model.started.value, "inject alone does not start a turn")
            assertEquals(listOf(InboxQueue.NEXT_STEP), harness.events().only<InboxSpliced>().map { it.queue })

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            val inputs = harness.events().only<UserMessage>()
            assertEquals(listOf(UserMessageKind.INJECTED, UserMessageKind.USER), inputs.map { it.kind })
            assertEquals("timer", inputs.first().source)
        }

    @Test
    fun `followups queue one per turn and done tasks accept the next one`() =
        runTest {
            val model = FakeChatModel().answers("one").answers("two")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            val phases = mutableListOf<Phase>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { loop.phase.collect { phases += it } }

            loop.followup(text("a"))
            loop.followup(text("b"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(listOf(1, 2), events.only<TurnEnded>().map { it.turn })
            assertEquals(listOf("a", "b"), events.only<UserMessage>().map { (it.parts.single() as Part.Text).text })
            assertEquals(2, model.requests[1].messages.count { it.role == Role.USER })
            assertEquals(Phase.Done, loop.phase.value)
            assertEquals(1, phases.count { it == Phase.Done }, "no Done between turns while a followup is queued")
        }

    @Test
    fun `a tool call runs and the next step sees its result`() =
        runTest {
            val model = FakeChatModel().calls(call("c1", "probe"), text = "looking").answers("found it")
            val harness = LoopHarness(this, model, tools = listOf(probe))
            val loop = harness.start()

            loop.followup(text("check"))
            loop.awaitPhase(Phase.Done)

            assertEquals(1, probe.calls.size)
            assertEquals("测试", probe.calls.single().reason)
            assertEquals(JsonObject(emptyMap()), probe.calls.single().args, "reason is taken out of the arguments")
            val records = harness.records()
            val dispatched = records.single { it.event is ToolCallRecorded }
            val answered = records.single { it.event is ToolResultRecorded }
            assertEquals(listOf(dispatched.seq), answered.sources)
            val second = model.requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), second.map { it.role })
            assertEquals(
                Part.ToolResult(call("c1", "probe").id, listOf(Part.Text("done")), isError = false),
                second[2].parts.single(),
            )
            assertEquals(listOf(1, 2), harness.events().only<StepEnded>().map { it.step })
        }

    @Test
    fun `only the first call of a reply runs and the others are skipped together with it`() =
        runTest {
            val model =
                FakeChatModel()
                    .calls(call("c1", "probe"), call("c2", "probe"), call("c3", "nope"))
                    .answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(probe))
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            assertEquals(1, probe.calls.size)
            val results = harness.events().only<ToolResultRecorded>()
            assertEquals(listOf("c1", "c2", "c3"), results.map { it.callId.value })
            assertEquals(
                listOf(ToolStatus.OK, ToolStatus.SKIPPED, ToolStatus.SKIPPED),
                results.map { it.result.status },
            )
            assertEquals(LoopTexts().skipped, (results[1].result.content.single() as Part.Text).text)
            val tool = model.requests[1].messages.last()
            assertEquals(Role.TOOL, tool.role)
            assertEquals(3, tool.parts.size, "all results of one reply travel in one message")
        }

    @Test
    fun `a reply cut off at the output limit runs none of its calls and ends the turn`() =
        runTest {
            val model = FakeChatModel().calls(call("c1", "probe"), stopReason = StopReason.MAX_TOKENS)
            val harness = LoopHarness(this, model, tools = listOf(probe))
            val loop = harness.start()

            loop.followup(text("go"))
            advanceUntilIdle()

            assertTrue(probe.calls.isEmpty())
            val events = harness.events()
            assertEquals(
                ToolStatus.SKIPPED,
                events
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
            assertTrue(events.only<ToolCallRecorded>().isEmpty())
            assertEquals(TurnEnded(1, TurnEndReason.MAX_TOKENS), events.last())
            assertEquals(Phase.Idle, loop.phase.value)
        }

    @Test
    fun `a cut-off reply without calls also ends the turn idle`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel().answers("trunc", StopReason.MAX_TOKENS))
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()
            assertEquals(TurnEnded(1, TurnEndReason.MAX_TOKENS), harness.events().last())
            assertEquals(Phase.Idle, loop.phase.value)
        }

    @Test
    fun `the step limit ends the turn and counts per turn`() =
        runTest {
            val model =
                FakeChatModel()
                    .calls(call("c1", "probe"))
                    .calls(call("c2", "probe"))
                    .calls(call("c3", "probe"))
                    .answers("done")
            val harness = LoopHarness(this, model, tools = listOf(probe), config = LoopConfig(maxStepsPerTurn = 2))
            val loop = harness.start()

            loop.followup(text("go"))
            advanceUntilIdle()
            assertEquals(TurnEnded(1, TurnEndReason.LIMIT, "step limit 2 reached"), harness.events().last())
            assertEquals(Phase.Idle, loop.phase.value)
            assertEquals(2, model.started.value, "the step over the limit sends nothing")

            loop.followup(text("more"))
            loop.awaitPhase(Phase.Done)
            assertEquals(TurnEnded(2, TurnEndReason.COMPLETED), harness.events().last())
        }

    @Test
    fun `turnStopping continues a turn up to the continuation limit`() =
        runTest {
            val continued = mutableListOf<Int>()
            val stopping =
                Interceptor<TurnStoppingInput, TurnStopDecision> { input, _ ->
                    continued += input.continuations
                    TurnStopDecision.Continue(ContextInput("check", listOf(Part.Text("verify first"))))
                }
            val model = FakeChatModel().answers("1").answers("2").answers("3")
            val harness =
                LoopHarness(
                    this,
                    model,
                    config = LoopConfig(maxContinuationsPerTurn = 2),
                    interceptors = Interceptors(requestError = emptyList(), turnStopping = listOf(stopping)),
                )
            val loop = harness.start()

            loop.followup(text("go"))
            advanceUntilIdle()

            assertEquals(listOf(0, 1, 2), continued)
            val events = harness.events()
            assertEquals(TurnEnded(1, TurnEndReason.LIMIT, "continuation limit 2 reached"), events.last())
            assertEquals(
                2,
                events.only<UserMessage>().count {
                    it.kind == UserMessageKind.INJECTED &&
                        it.source == "check"
                },
            )
            assertEquals(
                Role.USER,
                model.requests[1]
                    .messages
                    .last()
                    .role,
                "the continuation is visible to the model",
            )
        }

    @Test
    fun `turnStopping may let the turn end`() =
        runTest {
            val seen = mutableListOf<String>()
            val stopping =
                Interceptor<TurnStoppingInput, TurnStopDecision> { input, chain ->
                    seen += (input.reply.parts.single() as Part.Text).text
                    chain.proceed(input)
                }
            val harness =
                LoopHarness(
                    this,
                    FakeChatModel().answers("final"),
                    interceptors = Interceptors(requestError = emptyList(), turnStopping = listOf(stopping)),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(listOf("final"), seen)
        }

    @Test
    fun `preStep may refuse a step after its inputs are recorded`() =
        runTest {
            val refuse = Interceptor<PreStepInput, PreStepResult> { _, _ -> PreStepResult.Refuse("battery low") }
            val model = FakeChatModel()
            val harness =
                LoopHarness(
                    this,
                    model,
                    interceptors = Interceptors(requestError = emptyList(), preStep = listOf(refuse)),
                )
            val loop = harness.start()

            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(TurnEnded(1, TurnEndReason.PRE_STEP_REFUSED, "battery low"), events.last())
            assertTrue(events.only<StepStarted>().isEmpty())
            assertEquals(1, events.only<UserMessage>().size)
            assertEquals(0, model.started.value)
            assertEquals(Phase.Idle, loop.phase.value)
        }

    @Test
    fun `end_not_done ends the turn with its summary and the task is done`() =
        runTest {
            val end =
                FakeTool("end_not_done") { _, _ ->
                    ToolResult(
                        ToolStatus.OK,
                        listOf(Part.Text("ended")),
                        control = ToolControl.EndTurn(EndReason.BLOCKED, "登录失效"),
                    )
                }
            val model = FakeChatModel().calls(call("c1", "end_not_done"), call("c2", "end_not_done"))
            val harness = LoopHarness(this, model, tools = listOf(end))
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            assertEquals(TurnEnded(1, TurnEndReason.END_NOT_DONE, "登录失效"), events.last())
            assertEquals(StepEnded(1, 1, StepOutcome.COMPLETED), events[events.size - 2])
            assertEquals(
                ToolStatus.SKIPPED,
                events
                    .only<ToolResultRecorded>()
                    .last()
                    .result.status,
            )
            assertEquals(1, model.started.value)
        }

    @Test
    fun `the request header is written only when it changes`() =
        runTest {
            var step = 0
            val think =
                Interceptor<RequestPlan, RequestPlan> { plan, chain ->
                    step += 1
                    chain.proceed(if (step >= 3) plan.copy(reasoning = ReasoningLevel.HIGH) else plan)
                }
            val model = FakeChatModel().calls(call("c1", "probe")).calls(call("c2", "probe")).answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(probe),
                    interceptors = Interceptors(requestError = emptyList(), request = listOf(think)),
                )
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val headers = harness.events().only<RequestHeader>()
            assertEquals(listOf(null, ReasoningLevel.HIGH), headers.map { it.reasoning })
            assertEquals(ReasoningLevel.HIGH, model.requests[2].reasoning)
            assertEquals(
                model.requests[0].messages,
                model.requests[1].messages.take(1),
                "sent messages are never edited",
            )
        }

    @Test
    fun `runtime context is appended only when it changes`() =
        runTest {
            val appended = mutableListOf<Boolean>()
            var battery = 50
            val context =
                Interceptor<PreStepInput, PreStepResult> { input, chain ->
                    appended += input.log.appendRuntimeContext(listOf(Part.Text("battery $battery%")))
                    if (input.step == 2) battery = 40
                    chain.proceed(input)
                }
            val model = FakeChatModel().calls(call("c1", "probe")).calls(call("c2", "probe")).answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(probe),
                    interceptors = Interceptors(requestError = emptyList(), preStep = listOf(context)),
                )
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(true, false, true), appended)
            val contexts = harness.events().only<UserMessage>().filter { it.kind == UserMessageKind.RUNTIME_CONTEXT }
            assertEquals(listOf("battery 50%", "battery 40%"), contexts.map { (it.parts.single() as Part.Text).text })
        }

    @Test
    fun `preStep sees the task and the log and may append context events`() =
        runTest {
            var seen = 0
            val context =
                Interceptor<PreStepInput, PreStepResult> { input, chain ->
                    assertEquals(LoopHarness.TASK, input.log.taskId)
                    seen = input.log.records().size
                    input.log.append(UserMessage(UserMessageKind.RUNTIME_CONTEXT, listOf(Part.Text("note"))))
                    chain.proceed(input)
                }
            val model = FakeChatModel().answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    interceptors = Interceptors(requestError = emptyList(), preStep = listOf(context)),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertTrue(seen > 0)
            assertEquals(
                2,
                model.requests
                    .single()
                    .messages.size,
            )
        }

    @Test
    fun `observers see records, streamed text and trajectory cards`() =
        runTest {
            val model = FakeChatModel().calls(call("c1", "probe"), text = "看看").answers("好")
            val harness = LoopHarness(this, model, tools = listOf(probe))
            val loop = harness.start()
            val events = mutableListOf<LoopEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { loop.events.toList(events) }
            runCurrent()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val streamed = events.filterIsInstance<LoopEvent.Streamed>()
            assertEquals(listOf(1, 2), streamed.map { it.step })
            val cards = events.filterIsInstance<LoopEvent.Card>()
            assertEquals(2, cards.size)
            assertEquals("c1", cards.first().callId.value)
            assertEquals(
                harness.records().drop(1),
                events.filterIsInstance<LoopEvent.Recorded>().map {
                    it.record
                },
                "task/start comes before the loop",
            )
        }

    @Test
    fun `a call that does not validate gets INVALID_ARGS and is never dispatched`() =
        runTest {
            val typed = FakeTool("typed", properties = mapOf("n" to FakeTool.INTEGER), required = listOf("n"))
            val model =
                FakeChatModel()
                    .calls(call("c1", "missing"))
                    .calls(call("c2", "typed", "{not json"))
                    .calls(call("c3", "typed", """{"reason":"测试","n":"x"}"""))
                    .calls(call("c4", "typed", """{"reason":"  ","n":1}"""))
                    .answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(typed))
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            assertTrue(typed.calls.isEmpty())
            val events = harness.events()
            assertTrue(events.only<ToolCallRecorded>().isEmpty())
            val texts = events.only<ToolResultRecorded>().map { (it.result.content.single() as Part.Text).text }
            assertTrue(events.only<ToolResultRecorded>().all { it.result.status == ToolStatus.INVALID_ARGS })
            val defaults = LoopTexts()
            assertEquals("${defaults.unknownTool} missing", texts[0])
            assertTrue(texts[1].startsWith(defaults.invalidJson))
            assertEquals("${defaults.invalidArgs} arguments.n must be integer, got \"x\"", texts[2])
            assertEquals(defaults.blankReason, texts[3])
        }

    @Test
    fun `a valid call keeps its arguments without the reason`() =
        runTest {
            val typed = FakeTool("typed", properties = mapOf("n" to FakeTool.INTEGER))
            val model = FakeChatModel().calls(call("c1", "typed", """{"reason":"数一下","n":3}""")).answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(typed))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(JsonObject(mapOf("n" to JsonPrimitive(3))), typed.calls.single().args)
            assertEquals(LoopHarness.TASK, typed.calls.single().taskId)
            assertFalse(
                harness
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result.content
                    .isEmpty(),
            )
        }

    @Test
    fun `a tool's context carries the task, session, clock, apps and blob store`() =
        runTest {
            val apps =
                object : AppCatalog {
                    override suspend fun apps() = listOf(AppEntry("com.example.chat", "Chat", launchable = true))
                }
            var seen: List<Any?> = emptyList()
            val inspect =
                FakeTool("inspect") { _, context ->
                    val blob = context.storeBlob(byteArrayOf(9))
                    seen =
                        listOf(
                            context.taskId,
                            context.sessionId,
                            context.clock.epochMillis(),
                            context.apps?.apps(),
                            blob,
                        )
                    ToolResult.ok("seen")
                }
            val model = FakeChatModel().calls(call("c1", "inspect")).answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(inspect), apps = apps)
            val loop = harness.start()
            loop.followup(text("look"))
            loop.awaitPhase(Phase.Done)

            val blob = BlobStore.refOf(byteArrayOf(9))
            assertEquals(
                listOf(LoopHarness.TASK, LoopHarness.SESSION, currentTime, apps.apps(), blob),
                seen,
            )
            assertArrayEquals(byteArrayOf(9), harness.files.blobs.get(blob))
        }

    @Test
    fun `toolPostExecute may rewrite the result and queue context for the next step`() =
        runTest {
            val post =
                Interceptor<PostExecuteInput, PostExecuteResult> { input, chain ->
                    val result = chain.proceed(input)
                    PostExecuteResult(
                        ToolResult.ok("rewritten"),
                        context = listOf(ContextInput("monitor", listOf(Part.Text("battery low")))),
                    ).also { assertEquals(ToolResult.ok("done"), result.result) }
                }
            val model = FakeChatModel().calls(call("c1", "probe")).answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(probe),
                    interceptors = Interceptors(requestError = emptyList(), toolPostExecute = listOf(post)),
                )
            val loop = harness.start()
            loop.followup(text("check"))
            loop.awaitPhase(Phase.Done)

            val second = model.requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), second.map { it.role })
            assertEquals(
                Part.ToolResult(ToolCallId("c1"), listOf(Part.Text("rewritten")), isError = false),
                second[2].parts.single(),
            )
            assertEquals(listOf(Part.Text("battery low")), second[3].parts)
            val injected = harness.events().only<UserMessage>().single { it.kind == UserMessageKind.INJECTED }
            assertEquals("monitor", injected.source)
        }
}
