package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model-facing results shared by the tools. Results carry state, not new tool descriptions
 * (phase1 §3.2): "cannot do that now" is explained here, with what to do next.
 *
 * Text that comes from the device (failure and refusal reasons, exception messages) goes through
 * [ScreenPresenter.mask] like screen text, since it may quote what is on screen.
 */
internal object ToolResults {
    /** The task has no surface yet ([dev.phoneagent.core.tool.ToolContext.screen] is `null`). */
    val NO_SCREEN: ToolResult =
        ToolResult.failure(
            ToolStatus.ERROR,
            "现在没有可操作的屏幕（后台屏还没准备好），这一步没有执行。稍后再试；一直这样就 end_not_done(cause=blocked)。",
        )

    /** Arguments failed the boundary check; [message] lists the problems and the usage. */
    fun invalid(message: String): ToolResult = ToolResult.failure(ToolStatus.INVALID_ARGS, message)

    /** The surface threw [error]: it cannot be used at all right now; its message is masked by [presenter]. */
    fun unavailable(
        error: ScreenUnavailableException,
        presenter: ScreenPresenter,
    ): ToolResult =
        ToolResult.failure(
            ToolStatus.ERROR,
            "屏幕暂时用不了（${presenter.mask(error.message.orEmpty())}），这一步可能没有执行。过一会儿先 phone_observe，再决定下一步。",
        )

    /**
     * The result of an action or launch: a status line ([done] says what was done, e.g.
     * "已点击 @e10"), then the screen after it from [presenter]. [plannedOn] is the snapshot the
     * action named, or `null`.
     */
    fun outcome(
        done: String,
        outcome: ActionOutcome,
        plannedOn: SnapshotId?,
        presenter: ScreenPresenter,
    ): ToolResult {
        val after = outcome.after
        val screen = presenter.afterAction(outcome, plannedOn)
        val (status, text) =
            when (val result = outcome.status) {
                ActionOutcome.Status.Ok -> {
                    ToolStatus.OK to "${effectLine(done, outcome.effect)}\n$screen"
                }

                is ActionOutcome.Status.Failed -> {
                    ToolStatus.ERROR to "没有做成：${presenter.mask(result.reason)}。看一下现在的画面再决定下一步。\n$screen"
                }

                is ActionOutcome.Status.Refused -> {
                    ToolStatus.REFUSED to "手机拒绝了这个动作：${presenter.mask(result.reason)}。不要重试同一个动作，换个办法，或者 end_not_done。"
                }

                ActionOutcome.Status.StaleRef -> {
                    ToolStatus.ERROR to
                        if (after == null) {
                            STALE + "先 phone_observe 读最新一屏，再用新的 screen 和编号。"
                        } else {
                            STALE + "下面是最新一屏，请用它的 screen 和编号：\n" + presenter.page(after, SnapshotDetail.BRIEF)
                        }
                }
            }
        return ToolResult(status, listOf(Part.Text(text)), details(outcome))
    }

    private fun effectLine(
        done: String,
        effect: ActionOutcome.Effect,
    ): String =
        when (effect) {
            ActionOutcome.Effect.CHANGED -> {
                "$done。"
            }

            ActionOutcome.Effect.NO_CHANGE -> {
                "$done，但画面没有变化：可能还在加载（可以 phone_wait），也可能这个动作在这里不起作用，换个目标或办法。"
            }

            ActionOutcome.Effect.NEW_APP -> {
                "$done，前台换成了另一个 App。"
            }

            ActionOutcome.Effect.SPILLED_BACK -> {
                "$done。App 曾跳到用户正在用的主屏，已经挪回后台屏。"
            }

            ActionOutcome.Effect.RATE_LIMITED -> {
                "$done，但 App 提示操作太频繁。先停一停，不要重复同样的动作；还不行就 end_not_done(cause=blocked)。"
            }

            ActionOutcome.Effect.SECURE_PAGE -> {
                "$done，新页面禁止截屏。需要用户在这一页操作时，用 hand_to_user(kind=secure_page)。"
            }

            ActionOutcome.Effect.UNKNOWN -> {
                "$done，但不确定有没有效果。"
            }
        }

    private fun details(outcome: ActionOutcome): JsonObject =
        buildJsonObject {
            put(
                "outcome",
                when (outcome.status) {
                    ActionOutcome.Status.Ok -> "ok"
                    is ActionOutcome.Status.Failed -> "failed"
                    is ActionOutcome.Status.Refused -> "refused"
                    ActionOutcome.Status.StaleRef -> "stale_ref"
                },
            )
            put("effect", outcome.effect.name)
            outcome.after?.let { put("screen", it.id.value) }
        }

    private const val STALE = "目标已经不在画面上了（画面变了，编号过期），这一步没有执行。"
}

/** Result of calling the screen port: its value, or the surface was unusable. */
internal sealed interface ScreenCall<out T> {
    /** The port returned [value]. */
    data class Done<T>(
        val value: T,
    ) : ScreenCall<T>

    /** The port threw [error]. */
    data class Unavailable(
        val error: ScreenUnavailableException,
    ) : ScreenCall<Nothing>
}

/** Runs one screen-port call; an unusable surface becomes [ScreenCall.Unavailable]. Cancellation propagates. */
internal suspend fun <T> screenCall(call: suspend () -> T): ScreenCall<T> =
    try {
        ScreenCall.Done(call())
    } catch (unavailable: ScreenUnavailableException) {
        // Expected: the display or accessibility service went away; the caller tells the model.
        ScreenCall.Unavailable(unavailable)
    }
