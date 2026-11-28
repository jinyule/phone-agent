package dev.phoneagent.core.assembly

import dev.phoneagent.core.agent.AgentSetup
import dev.phoneagent.core.agent.Interceptors
import dev.phoneagent.core.agent.LoopConfig
import dev.phoneagent.core.agent.RequestPlan
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.rules.PolicyRules
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.screen.DiffConfig
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.screen.ScreenFormatConfig
import dev.phoneagent.core.screen.TreeQualityConfig
import dev.phoneagent.core.session.PolicyRulesRecorded
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolRegistry
import dev.phoneagent.core.tools.PhaseOneTools
import dev.phoneagent.core.tools.SystemPrompt
import dev.phoneagent.core.tools.ToolsConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.TimeSource

/**
 * What the product's agent is built from besides its main model: the app's own names for the
 * policy, and every tunable of the policy, the screen format, the tools and the loop. Each value is
 * checked by its own type on construction; [assemble] checks the app's names (`PolicyRules`).
 *
 * @property ownPackages this app's package names, all build variants; the agent never operates them.
 * @property ownLabels the names this app is shown under (launcher label, all languages).
 * @property tuning the policy's thresholds; the defaults are the reviewed ones.
 * @property format the screen format: what the ScreenPort's analyzer compares and what the model reads.
 * @property quality when a tree is too poor to act on.
 * @property diff what counts as a change of the screen.
 * @property tools the phase-1 tools' timeouts and waits.
 * @property loop the loop's limits, timeouts and texts.
 */
public data class ProductConfig(
    val ownPackages: Set<String>,
    val ownLabels: Set<String>,
    val tuning: PolicyTuning = PolicyTuning(),
    val format: ScreenFormatConfig = ScreenFormatConfig(),
    val quality: TreeQualityConfig = TreeQualityConfig(),
    val diff: DiffConfig = DiffConfig(),
    val tools: ToolsConfig = ToolsConfig(),
    val loop: LoopConfig = LoopConfig(),
)

/**
 * The product's agent for one session, assembled the one way the app and session replay share
 * (phase1 §2.1 `assembly`): the policy capability, the screen capability built from the one
 * [ProductConfig.format] and the main model's vision, the phase-1 tools in the PHONE then GENERAL
 * groups, the system prompt, the default interceptors, and the rules every run records.
 *
 * Everything here is fixed for the session; [setup] adds what belongs to the task and the device.
 * The app builds its ScreenPort with [analyzer], so the port's fingerprints, quality and diffs
 * follow the lines the model reads.
 */
public class ProductAssembly internal constructor(
    model: ChatModel,
    config: ProductConfig,
) {
    private val rules = PolicyRules(config.ownPackages, config.ownLabels, config.tuning)
    private val screens =
        ScreenComponents(config.format, config.quality, config.diff, rules.lexicon, model.info.capabilities.vision)

    /** For the app's ScreenPort: fingerprint, tree quality and diff, by [ProductConfig.format]. */
    public val analyzer: SnapshotAnalyzer = screens.analyzer

    /** What the tools show the model; its `vision` is the main model's. */
    public val presenter: ScreenPresenter = screens.presenter

    /** The safety engine. */
    public val policy: PolicyEngine = rules.engine

    /** What a call would do, for [policy]. */
    public val intents: IntentResolver = rules.resolver

    /** The phase-1 tools, all visible. */
    public val registry: ToolRegistry = ToolRegistry(PhaseOneTools.create(presenter, config.tools), GROUPS)

    /** The main conversation's request plan: [model], the system prompt and [registry]'s manifest. */
    public val plan: RequestPlan = RequestPlan(model, SystemPrompt.BLOCKS, registry.manifest)

    /**
     * The loop's extensions: the defaults (retrying failed requests, recovering from a stale
     * thinking signature).
     */
    public val interceptors: Interceptors = Interceptors()

    /** The loop's configuration. */
    public val loop: LoopConfig = config.loop

    /** The rules [policy] judges with, as every run of the task records them (`policy/rules`). */
    public val policyRules: PolicyRulesRecorded =
        PolicyRulesRecorded(
            PolicyTuning.RULES_VERSION,
            JsonObject(config.tuning.byName().mapValues { (_, value) -> JsonPrimitive(value) }),
        )

    /**
     * The setup of task [taskId] in the session directory [files], whose blobs the main model reads:
     * driving [screen] (`null` when the task has none), handing off through [surfaces] (`null` in
     * main-display mode), with [apps] when known.
     */
    @Suppress("LongParameterList") // The task's and the device's parts, all named at the call site.
    public fun setup(
        taskId: TaskId,
        files: SessionFiles,
        screen: ScreenPort?,
        surfaces: SurfaceManager?,
        apps: AppCatalog?,
        clock: Clock = Clock.System,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    ): AgentSetup =
        AgentSetup(
            taskId = taskId,
            files = files,
            plan = plan,
            registry = registry,
            policy = policy,
            intents = intents,
            screen = screen,
            surfaces = surfaces,
            apps = apps,
            clock = clock,
            config = loop,
            interceptors = interceptors,
            timeSource = timeSource,
            rules = policyRules,
        )
}

/**
 * Assembles the product's agent for one session around its main [model] (built for that session,
 * see `AgentSetup.plan`), by [config]. Pure: no I/O, the same inputs give the same parts. Throws
 * [IllegalArgumentException] when the app's names are invalid (`PolicyRules`).
 */
public fun assemble(
    model: ChatModel,
    config: ProductConfig,
): ProductAssembly = ProductAssembly(model, config)

/** Tool groups the product shows, in manifest order. */
private val GROUPS = listOf(ToolGroup.PHONE, ToolGroup.GENERAL)
