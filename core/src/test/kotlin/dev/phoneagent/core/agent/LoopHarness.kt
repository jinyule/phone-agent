@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Builds and runs one [AgentLoop] inside a `runTest` scope: virtual time for timeouts and the
 * clock, an in-memory session directory, a scripted model; `advanceUntilIdle()` runs the loop
 * until it waits (for input, the user, or a barrier). Defaults: every verdict Allow, intents
 * follow the tool's risk class on the current screen's package, no retries.
 */
@Suppress("LongParameterList") // Every parameter has a default; tests name the few they change.
internal class LoopHarness(
    scope: TestScope,
    val model: FakeChatModel = FakeChatModel(),
    val tools: List<Tool> = emptyList(),
    val policy: PolicyEngine = PolicyEngine { _, _, _ -> Verdict.Allow },
    val intents: IntentResolver = IntentResolver { _, risk, seen -> ActionIntent(kindOf(risk), seen?.pkg) },
    val screen: ScreenPort? = null,
    val surfaces: SurfaceManager? = null,
    val apps: AppCatalog? = null,
    val config: LoopConfig = LoopConfig(),
    val settings: TaskStarted = ATTENDED,
    val interceptors: Interceptors = Interceptors(requestError = emptyList()),
    val storage: InMemoryStoragePort = InMemoryStoragePort(),
    strictReplay: Boolean = true,
) {
    val files = SessionFiles(storage, StoragePath("sessions/s1"))

    /**
     * Checks every request of [model] against the log (phase1 §3.9 rule 1); with [strictReplay] a
     * difference fails the request (and so the test), else it is only kept in `replay.mismatches`.
     */
    val replay = ReplayChecker(files, strictReplay)
    val plan =
        RequestPlan(
            replay.wrap(model),
            listOf(PromptBlock("identity", "You drive a phone.")),
            tools.map { it.def },
        )
    val setup: AgentSetup =
        AgentSetup(
            taskId = TASK,
            files = files,
            plan = plan,
            registry = ToolRegistry(tools, ToolGroup.entries),
            policy = policy,
            intents = intents,
            screen = screen,
            surfaces = surfaces,
            apps = apps,
            clock = Clock { scope.testScheduler.currentTime },
            config = config,
            interceptors = interceptors,
            timeSource = scope.testScheduler.timeSource,
        )

    /**
     * Where the loop runs: a child of the test's background work (cancelling it, a shutdown,
     * leaves other harnesses of the test running), on a plain test dispatcher, so
     * `advanceUntilIdle()` runs the loop until it waits for input.
     */
    val loopScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob(scope.backgroundScope.coroutineContext.job) +
                StandardTestDispatcher(scope.testScheduler) +
                CoroutineExceptionHandler { _, failure ->
                    uncaught += failure
                    // A failed check (the strict replay's) fails the test now, with its message,
                    // instead of leaving it waiting for a phase the dead driver never reaches.
                    if (failure is AssertionError) scope.launch { throw failure }
                },
        )

    /** Failures that escaped the loop's coroutines (bugs); tests that provoke one assert on it. */
    val uncaught = mutableListOf<Throwable>()

    suspend fun start(): AgentLoop = AgentLoop.start(setup, SESSION, settings, loopScope)

    suspend fun resume(): AgentLoop = AgentLoop.resume(setup, loopScope)

    /** The log as persisted (decoded from storage). */
    suspend fun records(): List<SessionRecord> = SessionStore.read(files).records

    suspend fun events(): List<SessionEvent> = records().map { it.event }

    companion object {
        val TASK = TaskId("task-1")
        val SESSION = SessionId("session-1")
        val ATTENDED = TaskStarted(SecurityLevel.STANDARD, unattended = false)
        val UNATTENDED = TaskStarted(SecurityLevel.STANDARD, unattended = true)

        fun kindOf(risk: RiskClass): IntentKind =
            when (risk) {
                RiskClass.READ -> IntentKind.READ
                RiskClass.WRITE -> IntentKind.INPUT
                RiskClass.OUTWARD -> IntentKind.OUTWARD
                RiskClass.SPEND -> IntentKind.SPEND
                RiskClass.SETUP -> IntentKind.SETTINGS
            }
    }
}

internal fun text(value: String): UserInput = UserInput(listOf(Part.Text(value)))

internal suspend fun TaskRuntime.awaitPhase(expected: Phase): Phase = phase.first { it == expected }

internal suspend fun TaskRuntime.awaitStopped(): Phase = phase.first { it is Phase.Stopped }

internal inline fun <reified T : SessionEvent> List<SessionEvent>.only(): List<T> = filterIsInstance<T>()

/** The next record of type [T]; call it before suspending, so the record cannot be missed. */
internal suspend inline fun <reified T : SessionEvent> TaskRuntime.awaitRecorded(): T =
    events
        .mapNotNull { ((it as? LoopEvent.Recorded)?.record?.event as? T) }
        .first()
