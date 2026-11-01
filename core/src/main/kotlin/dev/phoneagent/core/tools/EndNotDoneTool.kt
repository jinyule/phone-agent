package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.tool.EndReason
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus

/**
 * `end_not_done(cause, summary)` (phase1 §5): ends the turn unfinished through
 * [ToolControl.EndTurn]; the loop reacts to the signal, not to this tool's name. The enum argument
 * is `cause`, not `reason`, because every tool already has a `reason` (contracts addendum §6).
 */
internal class EndNotDoneTool(
    mask: (String) -> String,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "end_not_done",
        description =
            "任务做不成时结束这一轮，不要硬做。cause：impossible 做不到（没有这个功能、找不到这个东西），" +
                "blocked 被拦住（登录墙、风控、限流、需要用户但用户不在），user_stopped 用户要求停止，other 其他。" +
                "summary 用用户的语言写清楚做了什么、卡在哪、用户可以怎么办。任务完成时不要用它，直接回复用户。",
        mask = mask,
        declare = {
            string("cause", "为什么做不成。", required = true, enum = CAUSES.keys.toList())
            string("summary", "给用户的说明：做了什么、卡在哪、用户可以怎么办。", required = true)
        },
    ) {
    override val group: ToolGroup = ToolGroup.GENERAL
    override val risk: RiskClass = RiskClass.READ
    override val concurrencySafe: Boolean = false

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        val cause = args.choice("cause", CAUSES, required = true)
        val summary = args.string("summary", required = true, max = MAX_SUMMARY)?.trim()
        val control = if (cause != null && summary != null) ToolControl.EndTurn(cause, summary) else null
        return when (val parsed = args.finish(USAGE, control)) {
            is Parsed.Invalid -> {
                ToolResults.invalid(parsed.message)
            }

            is Parsed.Ok -> {
                ToolResult(
                    status = ToolStatus.OK,
                    content = listOf(Part.Text("本轮已结束，任务没有完成。用户再发消息时会开始新的一轮。")),
                    control = parsed.value,
                )
            }
        }
    }

    private companion object {
        const val USAGE = "end_not_done(cause=impossible/blocked/user_stopped/other, summary)"
        const val MAX_SUMMARY = 1000
    }
}

private val CAUSES =
    linkedMapOf(
        "impossible" to EndReason.IMPOSSIBLE,
        "blocked" to EndReason.BLOCKED,
        "user_stopped" to EndReason.USER_STOPPED,
        "other" to EndReason.OTHER,
    )
