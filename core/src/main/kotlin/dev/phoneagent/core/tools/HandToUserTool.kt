package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * `hand_to_user(kind, reason)` (phase1 §5, §7.3): the user does this step themselves. The call's
 * `reason` is what the user is asked to do. Whatever the result, the screen has changed, so the
 * model is told to observe before acting.
 *
 * Every result it reports itself names the handoff's outcome in `details.handoff` (`done`,
 * `declined`, `expired`, `unavailable`, `failed`): the hand-off settled, and its card says how. An
 * ERROR without it is the runtime's, not its own: handOff threw instead of returning (a failed
 * take-back, or a hand-over that did not settle, so the app may be with the user:
 * `ToolInteraction.handOff`), or the call failed around it. Whether the hand-off happened is then
 * unknown, so its card shows UNKNOWN, not FAILED.
 */
internal class HandToUserTool(
    mask: (String) -> String,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "hand_to_user",
        description =
            "请用户自己完成这一步：把 App 交到用户眼前，等用户处理完再拿回来。" +
                "用于登录、验证码、支付、禁止截屏的页面，以及必须由用户亲手选择的情况。reason 写清楚要用户做什么。",
        mask = mask,
        declare = {
            string("kind", "为什么要用户来。", required = true, enum = KINDS.keys.toList())
        },
    ) {
    override val group: ToolGroup = ToolGroup.GENERAL
    override val risk: RiskClass = RiskClass.READ
    override val concurrencySafe: Boolean = false

    // It moves the app to the user's screen, and a stopped hand-off is not taken back.
    override val mayChangePhone: Boolean = true

    override fun uncertain(result: ToolResult): Boolean = result.details?.get(OUTCOME) == null

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        return when (val parsed = args.finish(USAGE, args.choice("kind", KINDS, required = true))) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> reported(ctx.interaction.handOff(parsed.value, call.reason))
        }
    }

    /** [result] as the model reads it, with the outcome in its details. */
    private fun reported(result: HandoffResult): ToolResult =
        report(result).copy(details = buildJsonObject { put(OUTCOME, result.name.lowercase(Locale.ROOT)) })

    private fun report(result: HandoffResult): ToolResult =
        when (result) {
            HandoffResult.DONE -> {
                ToolResult.ok("用户说已经处理好了。画面已经变了：先 phone_observe 核实结果，再继续。")
            }

            HandoffResult.DECLINED -> {
                ToolResult.failure(
                    ToolStatus.ERROR,
                    "用户没有处理就把任务交回来了。不要绕过这一步：问用户怎么办（ask_user），或者 end_not_done(cause=blocked)。",
                )
            }

            HandoffResult.EXPIRED -> {
                ToolResult.failure(ToolStatus.ERROR, "用户一直没有处理（已超时）。先 phone_observe 看看现在的画面，再决定。")
            }

            HandoffResult.UNAVAILABLE -> {
                ToolResult.failure(ToolStatus.ERROR, "现在没有人能接手（无人值守的任务）。这一步只能用户来做：end_not_done(cause=blocked)。")
            }

            // Either the app could not be moved to the user, or it could not be taken back and stays
            // on the main screen, whatever the user answered (SurfaceManager.takeBack).
            HandoffResult.FAILED, HandoffResult.NOT_TAKEN_BACK -> {
                ToolResult.failure(
                    ToolStatus.ERROR,
                    "切换屏幕失败：可能没能把 App 交到用户手上，也可能用户处理完以后没能把 App 收回来，它还留在用户的屏幕上。" +
                        "先 phone_observe 看看现在的画面，再决定。",
                )
            }
        }

    private companion object {
        const val USAGE = "hand_to_user(kind=login/captcha/payment/secure_page/choose/other)"

        /** The details key naming the outcome of a handoff the tool reported itself. */
        const val OUTCOME = "handoff"
    }
}

private val KINDS =
    linkedMapOf(
        "login" to HandoffKind.LOGIN,
        "captcha" to HandoffKind.CAPTCHA,
        "payment" to HandoffKind.PAYMENT,
        "secure_page" to HandoffKind.SECURE_PAGE,
        "choose" to HandoffKind.CHOOSE,
        "other" to HandoffKind.OTHER,
    )
