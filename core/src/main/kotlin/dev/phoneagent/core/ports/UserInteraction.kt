package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.ToolCallId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How core waits for the user (phase1 §3.2, §6, §7.3). Implemented by the task runtime, one
 * instance per tool call (prompts are bound to that call), and held by the runtime alone: it
 * calls [confirm] for a `Verdict.Confirm` and [handOff] for a `Verdict.HandOff`. Tools reach the
 * user only through the [ToolInteraction] view [forTools] gives (`ask_user`, `hand_to_user`), so
 * no tool can show a confirmation card: `request_confirmation` gets its card from the policy like
 * any other call.
 *
 * Every method suspends until the prompt is resolved and returns how it ended:
 * - the user replied: the reply (`Answered` / `Declined`, `APPROVED` / `REJECTED`, `DONE` / `DECLINED`);
 * - the runtime's wait limit passed (for cards, `approval/asked.expiresAt`): `EXPIRED`;
 * - nobody can answer (unattended task, prompts unavailable): `UNAVAILABLE`, returned at once
 *   without showing anything;
 * - the task was stopped while waiting: the call is cancelled and throws `CancellationException`,
 *   never a result. Tools must let it propagate; the runtime records `prompt/withdrawn`.
 *
 * The prompt and its result are in the session log before a method returns, and the time spent
 * waiting does not count against `Tool.timeoutMs`. At most one prompt per task is pending; a
 * second call waits for the first. A failure to record the prompt throws the session log's
 * `IOException`. When the task cannot be taken back (the call cut its own handoff off or it
 * failed, and the take-back ended any way but returning; or the hand-over did not settle),
 * [handOff] throws [ScreenUnavailableException] (the move only) in place of the cancellation or
 * failure; tools must let it propagate and not go on acting. An Error propagates as it is.
 */
public interface UserInteraction : ToolInteraction {
    /**
     * Shows [card] and waits for a decision. Called only by the runtime for a `Verdict.Confirm`;
     * tools have no way to reach it. Only [ConfirmResult.APPROVED] lets the action run, and it
     * covers exactly this card.
     */
    public suspend fun confirm(card: ConfirmCard): ConfirmResult
}

/**
 * What a tool may do to reach the user, through `ToolContext.interaction`: ask and hand off, with
 * the behaviour [UserInteraction] describes. The runtime passes the view [forTools] makes, never
 * its [UserInteraction] itself, so not even a cast reaches `confirm`.
 */
public interface ToolInteraction {
    /** Asks [question] (`ask_user`). */
    public suspend fun ask(question: Question): AskResult

    /**
     * Moves the task to the main display for the user to do a [kind] step, explained by [reason]
     * (non-blank, user's language), and moves it back. The screen has changed afterwards whatever
     * the result: observe before acting.
     *
     * A handoff that ends without its `handoff/end` (the call cuts it off: its budget, a
     * cancellation inside the tool; or an unexpected failure) is taken back at once. When that
     * cannot be done (the take-back ends any way but returning: an exception, a cancellation of
     * the port's own, its own timeout; or it takes too long, or was itself cut off), this throws
     * [ScreenUnavailableException] naming the move only, in place of the cancellation or failure:
     * the task may still be with the user, so tools must let it propagate and not go on acting.
     * So does a hand-over that did not settle, cut off or not: it settles only when
     * `SurfaceManager.handToUser` returns a ticket or throws [ScreenUnavailableException] (nothing
     * moved: the handoff then ends FAILED and this returns it). When the port throws an
     * IOException (not cut off), `handoff/end` FAILED is recorded; any other unsettled end
     * (another exception, the port's own timeout or cancellation, an Error) is closed with
     * `prompt/withdrawn`, as a cut is. Either way where the app is is unknown and the call ends
     * as ERROR with the screen note. An Error propagates as it is, with the failed take-back
     * suppressed on it. A stopping task leaves the app with the user and throws the cancellation.
     */
    public suspend fun handOff(
        kind: HandoffKind,
        reason: String,
    ): HandoffResult
}

/**
 * The view of this interaction a tool gets: [ToolInteraction.ask] and [ToolInteraction.handOff]
 * pass through; the view is no [UserInteraction].
 */
public fun UserInteraction.forTools(): ToolInteraction = ToolView(this)

private class ToolView(
    private val interaction: UserInteraction,
) : ToolInteraction {
    override suspend fun ask(question: Question): AskResult = interaction.ask(question)

    override suspend fun handOff(
        kind: HandoffKind,
        reason: String,
    ): HandoffResult = interaction.handOff(kind, reason)
}

/**
 * A question for the user.
 *
 * @property text the question, in the user's language; non-blank.
 * @property options suggested answers, distinct and non-blank; empty for free text. The user may
 *   still answer in free text.
 */
@Serializable
public data class Question(
    val text: String,
    val options: List<String> = emptyList(),
) {
    init {
        require(text.isNotBlank()) { "Question text must not be blank" }
        require(options.none { it.isBlank() }) { "Question options must not be blank" }
        require(options.distinct().size == options.size) { "Question options must be distinct: $options" }
    }
}

/** How [UserInteraction.ask] ended. Recorded in `question/answered`. */
@Serializable
public sealed interface AskResult {
    /** The user answered with [text] (non-blank): an option or free text. */
    @Serializable
    @SerialName("answered")
    public data class Answered(
        /** The answer. */
        val text: String,
    ) : AskResult {
        init {
            require(text.isNotBlank()) { "An answer must not be blank" }
        }
    }

    /** The user dismissed the question without answering. */
    @Serializable
    @SerialName("declined")
    public data object Declined : AskResult

    /** Nobody answered within the wait limit. */
    @Serializable
    @SerialName("expired")
    public data object Expired : AskResult

    /** Nobody can answer (unattended task); nothing was shown. */
    @Serializable
    @SerialName("unavailable")
    public data object Unavailable : AskResult
}

/** How [UserInteraction.confirm] ended. Recorded in `approval/decided`. */
@Serializable
public enum class ConfirmResult {
    /** Approved, for this one call only. */
    APPROVED,

    /** Rejected by the user. */
    REJECTED,

    /** Nobody decided within the wait limit; treated as rejected. */
    EXPIRED,

    /** Nobody can decide (unattended task); nothing was shown; treated as rejected. */
    UNAVAILABLE,
}

/** How [UserInteraction.handOff] ended. Recorded in `handoff/end`. */
@Serializable
public enum class HandoffResult {
    /** The user says they finished the step. */
    DONE,

    /** The user gave the task back without doing the step. */
    DECLINED,

    /** Nobody acted within the wait limit. */
    EXPIRED,

    /** Nobody can take over (unattended task); nothing was moved. */
    UNAVAILABLE,

    /** Moving the task between displays failed; details are in `handoff/end`. */
    FAILED,
}

/**
 * Content of a confirmation card (phase1 §6). The approval applies to exactly this card: a card
 * that differs in any field, [callId] included, needs a new approval.
 *
 * @property callId the call being approved.
 * @property reason why the user is asked.
 * @property action what will happen, non-blank, in the user's language, e.g. "发送消息".
 * @property target who or what it affects, e.g. the contact name, or `null`.
 * @property content what will be sent or typed, masked when sensitive, or `null`.
 * @property amount money involved, or `null`.
 * @property pkg the app, or `null`.
 */
@Serializable
public data class ConfirmCard(
    val callId: ToolCallId,
    val reason: ConfirmReason,
    val action: String,
    val target: String? = null,
    val content: String? = null,
    val amount: Money? = null,
    val pkg: String? = null,
) {
    init {
        require(action.isNotBlank()) { "Confirm card for $callId: action must not be blank" }
    }
}

/** Why a confirmation is asked (phase1 §6 matrix rows, plus `request_confirmation`). */
@Serializable
public enum class ConfirmReason {
    /** Spending money; asked at every level. */
    SPEND,

    /** Reaching other people: send, post, greet, submit. */
    OUTWARD,

    /** Deleting something. */
    DELETE,

    /** Changing system settings. */
    SETTINGS,

    /** First action in an app during this task (谨慎 level). */
    FIRST_IN_APP,

    /** The model asked for it with `request_confirmation`; it can only add confirmations. */
    REQUESTED_BY_MODEL,
}

/**
 * An amount of money in hundredths of the unit (two decimals regardless of currency).
 *
 * @property minor amount × 100, >= 0.
 * @property currency ISO 4217 code (`CNY`), or `null` when the screen shows no identifiable currency.
 */
@Serializable
public data class Money(
    val minor: Long,
    val currency: String? = null,
) {
    init {
        require(minor >= 0) { "Money must be >= 0, got $minor" }
        require(currency == null || CURRENCY.matches(currency)) { "Currency must be ISO 4217, got '$currency'" }
    }
}

private val CURRENCY = Regex("[A-Z]{3}")
