@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.assembly

import dev.phoneagent.core.agent.AgentLoop
import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.LoopHarness
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.ReplayChecker
import dev.phoneagent.core.agent.awaitPhase
import dev.phoneagent.core.agent.text
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.ToolResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle

/** The app's names as the product passes them; any valid ones would do. */
internal val PRODUCT = ProductConfig(setOf("dev.phoneagent", "dev.phoneagent.debug"), setOf("PhoneAgent"))

/**
 * One task of the agent as [assemble] builds it, inside a `runTest` scope: the real policy, tools,
 * system prompt and interceptors around a scripted [model] and [screen], with virtual time and an
 * in-memory session directory. Every request is checked against the log (phase1 §3.9 rule 1), and
 * every trajectory card is kept in [cards].
 */
internal class AssembledLoop(
    private val scope: TestScope,
    val model: FakeChatModel,
    val screen: ScreenPort?,
    config: ProductConfig = PRODUCT,
    private val settings: TaskStarted = LoopHarness.ATTENDED,
    val storage: InMemoryStoragePort = InMemoryStoragePort(),
) {
    val files = SessionFiles(storage, StoragePath("sessions/s1"))
    val assembly = assemble(ReplayChecker(files, strict = true).wrap(model), config)
    val setup =
        assembly.setup(
            LoopHarness.TASK,
            files,
            screen,
            surfaces = null,
            apps = null,
            clock = Clock { scope.testScheduler.currentTime },
            timeSource = scope.testScheduler.timeSource,
        )

    /** Failures that escaped the loop's coroutines. */
    val uncaught = mutableListOf<Throwable>()

    /** Every trajectory card in the order the loop showed them. */
    val cards = mutableListOf<StepCard>()

    private val loopScope =
        CoroutineScope(
            SupervisorJob(scope.backgroundScope.coroutineContext.job) +
                StandardTestDispatcher(scope.testScheduler) +
                CoroutineExceptionHandler { _, failure ->
                    uncaught += failure
                    if (failure is AssertionError) scope.launch { throw failure }
                },
        )

    suspend fun start(): AgentLoop {
        val loop = AgentLoop.start(setup, LoopHarness.SESSION, settings, loopScope)
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            loop.events.collect { event -> if (event is LoopEvent.Card) cards += event.card }
        }
        return loop
    }

    suspend fun resume(): AgentLoop = AgentLoop.resume(setup, loopScope)

    /** Starts the task, gives it [command] and runs it until it is done. */
    suspend fun ran(command: String = "去办"): AssembledLoop {
        val loop = start()
        loop.followup(text(command))
        scope.advanceUntilIdle()
        loop.awaitPhase(Phase.Done)
        return this
    }

    suspend fun events(): List<SessionEvent> = SessionStore.read(files).records.map { it.event }

    suspend fun resultOf(id: String): ToolResult =
        events().filterIsInstance<ToolResultRecorded>().single { it.callId == ToolCallId(id) }.result

    suspend fun verdictsOf(id: String): List<PolicyVerdictRecorded> =
        events().filterIsInstance<PolicyVerdictRecorded>().filter { it.callId == ToolCallId(id) }

    suspend fun lastVerdictOf(id: String): Verdict = verdictsOf(id).last().verdict
}
