@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.AgentSetup
import dev.phoneagent.core.agent.Interceptors
import dev.phoneagent.core.agent.ReplayChecker
import dev.phoneagent.core.agent.RequestPlan
import dev.phoneagent.core.agent.RetryInterceptor
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.policy.rules.PolicyRules
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolRegistry
import dev.phoneagent.core.tools.PhaseOneTools
import dev.phoneagent.core.tools.SystemPrompt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/**
 * The product loop of a session fixture (WP08 design §9), assembled as the app assembles it: the
 * policy capability ([PolicyRules] with the scenario's own packages, labels and tuning), the screen
 * components built on its lexicon, the phase-1 tools, the product system prompt, the default loop
 * config and retries (seeded jitter, which N3 zeroes anyway), and the scenario's app catalog. No
 * SurfaceManager yet (design-review 7). It runs in [scope]'s virtual time on an in-memory session
 * directory.
 *
 * Every request goes through a strict [ReplayChecker] (phase1 §3.9 rule 1) and then [tape], which
 * keeps it for `requests.expected.jsonl`; recording and replay wrap their models the same way.
 *
 * Until WP09 makes the app's assembly a function of core, this is a second copy of it.
 *
 * @param model the recording's model (record) or the fixture's (replay).
 * @param device the device, built from the current analyzer and the call the log says is being
 *   executed ([OpenCall] over the replayed log).
 */
class ReplayHarness(
    private val scope: TestScope,
    val scenario: Scenario,
    model: ChatModel,
    device: (analyzer: SnapshotAnalyzer, currentCall: suspend () -> ToolCallId?) -> ScreenPort,
) {
    val storage: InMemoryStoragePort = InMemoryStoragePort()
    val files: SessionFiles = SessionFiles(storage, StoragePath("sessions/replay"))

    /** The tuning the policy runs with: the defaults with the scenario's fields set. */
    val tuning: PolicyTuning = PolicyPins.tuning(scenario.assembly.tuning)

    private val rules = PolicyRules(scenario.assembly.ownPackages, scenario.assembly.ownLabels, tuning)
    private val components = ScreenComponents(lexicon = rules.lexicon, vision = scenario.assembly.vision)
    private val tools = PhaseOneTools.create(components.presenter)

    /** Every request the model received, with the log position it was sent at. */
    val tape: RequestTape = RequestTape(model) { log().lastSeq }

    /** Rule 1 at every request; strict, so a difference fails the request. */
    internal val checker: ReplayChecker = ReplayChecker(files, strict = true)

    /** The device the loop drives. */
    val screen: ScreenPort = device(components.analyzer) { OpenCall.after(log().records) }

    /** Failures that escaped a runtime's coroutines: a replay failure thrown as an Error, or a bug. */
    val uncaught: MutableList<Throwable> = mutableListOf()

    val setup: AgentSetup =
        AgentSetup(
            taskId = TASK,
            files = files,
            plan = RequestPlan(checker.wrap(tape), SystemPrompt.BLOCKS, tools.map { it.def }),
            registry = ToolRegistry(tools, ToolGroup.entries),
            policy = rules.engine,
            intents = rules.resolver,
            screen = screen,
            apps = Catalog(scenario.device.apps),
            clock = Clock { scope.testScheduler.currentTime },
            interceptors = Interceptors(requestError = listOf(RetryInterceptor(random = Random(0)))),
            timeSource = scope.testScheduler.timeSource,
        )

    /**
     * A scope for one runtime: a child of the test's background work on a plain test dispatcher,
     * whose escaping failures land in [uncaught]. Cancelling it shuts that runtime down.
     */
    fun runtimeScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob(scope.backgroundScope.coroutineContext.job) +
                StandardTestDispatcher(scope.testScheduler) +
                CoroutineExceptionHandler { _, failure -> uncaught += failure },
        )

    /** The log as persisted so far. */
    suspend fun log(): SessionLog = SessionStore.read(files)

    private class Catalog(
        private val apps: List<AppEntry>,
    ) : AppCatalog {
        override suspend fun apps(): List<AppEntry> = apps
    }

    companion object {
        /** The task of every replayed session; normalized to `{{task:1}}`. */
        val TASK: TaskId = TaskId("task-1")

        /** The session of every replayed session; normalized to `{{session:1}}`. */
        val SESSION: SessionId = SessionId("session-1")
    }
}
