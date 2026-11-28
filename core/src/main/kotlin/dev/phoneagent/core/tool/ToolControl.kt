package dev.phoneagent.core.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A control signal a tool result carries to the loop ([ToolResult.control]). The loop reacts to
 * these signals only, never to tool names. Allowed on [ToolStatus.OK] results only.
 */
@Serializable
public sealed interface ToolControl {
    /**
     * End the turn without the task being done (`end_not_done`). The loop records the result,
     * starts no further step in this turn, and ends it with `TurnEndReason.END_NOT_DONE`
     * (detail = [summary]).
     *
     * @property reason why the task cannot be finished.
     * @property summary what was done and what is left, in the user's language; non-blank.
     */
    @Serializable
    @SerialName("end_turn")
    public data class EndTurn(
        val reason: EndReason,
        val summary: String,
    ) : ToolControl {
        init {
            require(summary.isNotBlank()) { "EndTurn needs a summary" }
        }
    }
}

/** Why a task ends unfinished (`end_not_done.reason`, phase1 §5). */
@Serializable
public enum class EndReason {
    /** It cannot be done (the app has no such feature, the item does not exist). */
    IMPOSSIBLE,

    /** Something blocks it that the agent may not handle (login wall, risk control, captcha). */
    BLOCKED,

    /** The user asked to stop. */
    USER_STOPPED,

    /** Anything else; the summary explains. */
    OTHER,
}
