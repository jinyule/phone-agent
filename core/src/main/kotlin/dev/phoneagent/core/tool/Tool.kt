package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ToolInteraction
import kotlinx.serialization.Serializable

/**
 * A tool the model can call (phase1 §3.2). Its [def] is static for the life of the app, so the
 * cached prompt prefix never changes; "cannot do that now" is a [ToolResult], not a missing tool.
 *
 * Execution contract: [execute] receives arguments already validated against the schema and
 * returns a result for every expected failure. It throws only for bugs; the loop records those
 * as [ToolStatus.ERROR]. It must propagate `CancellationException`. The loop enforces
 * [timeoutMs] around it (time spent waiting for the user is not counted).
 */
public interface Tool {
    /** What the model sees. */
    public val def: ToolDef

    /** Group for folding and expanding. */
    public val group: ToolGroup

    /** Risk class the policy starts from. */
    public val risk: RiskClass

    /**
     * Whether calls may run in parallel with other calls; anything that writes the screen is `false`.
     * Abstract on purpose: every tool states it rather than inheriting a default.
     */
    public val concurrencySafe: Boolean

    /** Single source of truth for this tool's timeout, > 0. */
    public val timeoutMs: Long

    /** Runs [call]. See the class contract. */
    public suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult

    /** Trajectory card shown when the call starts. Pure: replay regenerates it. */
    public fun presentCall(call: ToolCall): StepCard

    /** Trajectory card for the finished call. Pure: replay regenerates it. */
    public fun presentResult(
        call: ToolCall,
        result: ToolResult,
    ): StepCard
}

/**
 * What a tool may use while it runs. Implemented by the task runtime, scoped to one call;
 * do not keep it after [Tool.execute] returns.
 */
public interface ToolContext {
    /** The task the call belongs to. */
    public val taskId: TaskId

    /** The session it is recorded in. */
    public val sessionId: SessionId

    /** The task's surface, or `null` when it has none yet (phone tools then return an error result). */
    public val screen: ScreenPort?

    /** Timestamp source. */
    public val clock: Clock

    /**
     * Asking and handing off to the user, bound to this call: the runtime's `UserInteraction`
     * seen through `forTools()`, so it has no `confirm`. Confirmations come from the policy and
     * are shown by the runtime.
     */
    public val interaction: ToolInteraction

    /**
     * Installed apps, or `null` when this capability is missing; `phone_apps` and launching by
     * name then return an error result, as phone tools do when [screen] is `null`.
     */
    public val apps: AppCatalog?

    /** Stores [bytes] in the session's blob store (deduplicated) and returns its reference. */
    public suspend fun storeBlob(bytes: ByteArray): BlobRef
}

/** Tool groups; collapsed groups are expanded by appending their tools (phase1 §3.2). */
@Serializable
public enum class ToolGroup {
    /** Reading and driving the phone. */
    PHONE,

    /** Conversation with the user and task control (`ask_user`, `end_not_done` …). */
    GENERAL,

    /** App knowledge and memory (M4). */
    KNOWLEDGE,
}

/** Risk class of a tool; the policy refines it per call into an `IntentKind`. */
@Serializable
public enum class RiskClass {
    /** Only reads. */
    READ,

    /** Changes local state (taps, typing). */
    WRITE,

    /** Can reach other people (send, post, greet). */
    OUTWARD,

    /** Can spend money. */
    SPEND,

    /** Changes device setup. */
    SETUP,
}
