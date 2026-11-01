package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenCoordinates
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult

/**
 * A tool that sends one [UiAction] to the task's screen (`phone_tap`, `phone_type`,
 * `phone_scroll`, `phone_swipe`, `phone_key`). It writes the screen, so it is never concurrency
 * safe and starts from [RiskClass.WRITE]; the policy refines each call.
 *
 * Execution: arguments are checked first ([ToolStatus.INVALID_ARGS][dev.phoneagent.core.tool.ToolStatus]
 * with what to fix); no screen or an unusable screen is an error result; otherwise the
 * [ActionOutcome][dev.phoneagent.core.ports.ActionOutcome] becomes the result (see [ToolResults.outcome]).
 */
internal abstract class ScreenActionTool(
    name: String,
    description: String,
    private val presenter: ScreenPresenter,
    final override val timeoutMs: Long,
    declare: ToolSchema.() -> Unit,
) : PhaseOneTool(name, description, presenter::mask, declare) {
    final override val group: ToolGroup = ToolGroup.PHONE
    final override val risk: RiskClass = RiskClass.WRITE
    final override val concurrencySafe: Boolean = false

    /** Usage shown when arguments are wrong, e.g. `phone_tap(screen, ref | x+y, long?)`. */
    protected abstract val usage: String

    /** The action described by [args], or `null` after recording problems in [args]. */
    protected abstract fun plan(args: ArgReader): ScreenPlan?

    final override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        return when (val parsed = args.finish(usage, plan(args))) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> act(parsed.value, ctx)
        }
    }

    private suspend fun act(
        plan: ScreenPlan,
        ctx: ToolContext,
    ): ToolResult {
        val screen = ctx.screen ?: return ToolResults.NO_SCREEN
        return when (val acted = screenCall { screen.act(plan.action, plan.on) }) {
            is ScreenCall.Done -> ToolResults.outcome(plan.done, acted.value, plan.on, presenter)
            is ScreenCall.Unavailable -> ToolResults.unavailable(acted.error)
        }
    }
}

/**
 * A validated action.
 *
 * @property action what to send.
 * @property on the snapshot it was planned on, or `null` for actions that name none.
 * @property done the status line's subject, e.g. "已点击 @e10".
 */
internal data class ScreenPlan(
    val action: UiAction,
    val on: SnapshotId?,
    val done: String,
)

/** How a target reads in status lines: `@e10` or `(500,300)`. */
internal fun Target.shown(): String =
    when (this) {
        is Target.Ref -> "@$ref"
        is Target.Point -> "($x,$y)"
    }

/**
 * Reads `ref`, or `x` and `y`, into a target: exactly one of the two forms. Returns `null` after
 * recording a problem (invalid values are recorded by the getters).
 */
internal fun ArgReader.target(): Target? {
    val ref: ElementRef? = ref("ref")
    val x = integer("x", COORDINATES)
    val y = integer("y", COORDINATES)
    targetProblem(has("ref"), has("x"), has("y"))?.let { problem(it) }
    return when {
        ref != null -> Target.Ref(ref)
        x != null && y != null -> Target.Point(x.toInt(), y.toInt())
        else -> null
    }
}

/** What is wrong with the combination of target arguments given, or `null`. */
private fun targetProblem(
    ref: Boolean,
    x: Boolean,
    y: Boolean,
): String? =
    when {
        ref && (x || y) -> "`ref` 和 `x`/`y` 只能给一种"
        x != y -> "坐标要同时给 `x` 和 `y`"
        !ref && !x -> "缺少目标：给 `ref`（最近一屏里的编号），或者同时给 `x` 和 `y`"
        else -> null
    }

/** Valid normalized coordinates (phase1 §4). */
internal val COORDINATES: LongRange = 0L..ScreenCoordinates.MAX.toLong()
