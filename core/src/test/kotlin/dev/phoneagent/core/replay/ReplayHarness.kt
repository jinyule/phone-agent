@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.AgentSetup
import dev.phoneagent.core.agent.ReplayChecker
import dev.phoneagent.core.assembly.ProductAssembly
import dev.phoneagent.core.assembly.ProductConfig
import dev.phoneagent.core.assembly.assemble
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.testing.InMemoryStoragePort
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * The product loop of a session fixture (WP08 design §9), assembled by the product's own assembly
 * ([assemble], as the app assembles it; ADR 2026-11-13-product-assembly): [ProductConfig] with the
 * scenario's own packages, labels and tuning and every other config at its default, the main
 * model's vision. Over the product's request plan go the scenario's output cap and reasoning level
 * (`Assembly.maxOutputTokens`, `Assembly.reasoning`), which the product does not set yet. The rest
 * is the task's and the device's: the scenario's app catalog, no SurfaceManager yet
 * (design-review 7). It runs in [scope]'s virtual time on an in-memory session directory.
 *
 * Every request goes through a strict [ReplayChecker] (phase1 §3.9 rule 1) and then [tape], which
 * keeps it for `requests.expected.jsonl`; recording and replay wrap their models the same way.
 *
 * @param model the recording's model (record) or the fixture's (replay).
 * @param device the device, built from the product's analyzer and the call the log says is being
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

    /** Every request the model received, with the log position it was sent at. */
    val tape: RequestTape = RequestTape(model) { log().lastSeq }

    /** Rule 1 at every request; strict, so a difference fails the request. */
    internal val checker: ReplayChecker = ReplayChecker(files, strict = true)

    /** The product's agent around the checked and taped model. */
    val assembly: ProductAssembly = assemble(checker.wrap(tape), config(scenario.assembly, tuning))

    /** The device the loop drives. */
    val screen: ScreenPort = device(assembly.analyzer) { OpenCall.after(log().records) }

    /** Failures that escaped a runtime's coroutines: a replay failure thrown as an Error, or a bug. */
    val uncaught: MutableList<Throwable> = mutableListOf()

    val setup: AgentSetup =
        assembly
            .setup(
                TASK,
                files,
                screen,
                surfaces = null,
                apps = Catalog(scenario.device.apps),
                clock = Clock { scope.testScheduler.currentTime },
                timeSource = scope.testScheduler.timeSource,
            ).let { product ->
                val plan =
                    product.plan.copy(
                        maxOutputTokens = scenario.assembly.maxOutputTokens,
                        reasoning = scenario.assembly.reasoning,
                    )
                product.copy(plan = plan)
            }

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
        /** The product config of [assembly] and [tuning]: its packages and labels, other configs default. */
        fun config(
            assembly: Assembly,
            tuning: PolicyTuning,
        ): ProductConfig = ProductConfig(assembly.ownPackages, assembly.ownLabels, tuning)

        /** The task of every replayed session; normalized to `{{task:1}}`. */
        val TASK: TaskId = TaskId("task-1")

        /** The session of every replayed session; normalized to `{{session:1}}`. */
        val SESSION: SessionId = SessionId("session-1")
    }
}
