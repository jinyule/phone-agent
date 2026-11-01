package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ask_user(question, options?)` (phase1 §5): asks through [ToolContext.interaction] and returns
 * the answer. Anything but an answer is an error result telling the model not to guess.
 *
 * The answer reaches the model unmasked: the user gave it to the agent on purpose (a card number
 * to fill in must stay usable). The trajectory card still masks it, like every card detail.
 */
internal class AskUserTool(
    mask: (String) -> String,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "ask_user",
        description =
            "问用户一个问题，等用户回答。只在缺少必须由用户决定的信息时用（选哪个、填什么、要不要继续）。" +
                "options 给出建议的选项，用户也可以自由回答。",
        mask = mask,
        declare = {
            string("question", "要问的问题，用用户的语言。", required = true)
            strings("options", "建议的选项（最多 $MAX_OPTIONS 个），可不填。", MAX_OPTIONS)
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
        val text = args.string("question", required = true, max = MAX_QUESTION)?.trim()
        val options = args.strings("options", MAX_OPTIONS, MAX_OPTION).orEmpty().map(String::trim)
        val question = text?.let { Question(it, options.distinct()) }
        return when (val parsed = args.finish(USAGE, question)) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> answer(ctx.interaction.ask(parsed.value))
        }
    }

    private fun answer(result: AskResult): ToolResult =
        when (result) {
            is AskResult.Answered -> {
                ToolResult(
                    ToolStatus.OK,
                    ToolResult.ok("用户回答：${result.text}").content,
                    buildJsonObject { put("answer", result.text) },
                )
            }

            AskResult.Declined -> {
                ToolResult.failure(ToolStatus.ERROR, "用户没有回答，关掉了这个问题。不要替用户猜答案：按已经知道的继续，或者 end_not_done。")
            }

            AskResult.Expired -> {
                ToolResult.failure(ToolStatus.ERROR, "用户一直没有回答（已超时）。不要替用户猜答案：按已经知道的继续，或者 end_not_done(cause=blocked)。")
            }

            AskResult.Unavailable -> {
                ToolResult.failure(ToolStatus.ERROR, "现在没有人能回答（无人值守的任务）。按最稳妥的方式继续，做不了就 end_not_done(cause=blocked)。")
            }
        }

    private companion object {
        const val USAGE = "ask_user(question, options?)"
        const val MAX_QUESTION = 500
        const val MAX_OPTIONS = 6
        const val MAX_OPTION = 80
    }
}
