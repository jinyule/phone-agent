package dev.phoneagent.core.policy

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The single place where safety is decided (phase1 §3.6, §6, D8). Runs as the last guard of
 * `toolPreExecute`; nothing reorders or bypasses it.
 *
 * [evaluate] is a pure function of its arguments (no I/O, no clock, no randomness), so replay
 * reproduces every verdict. It never throws for any input; unknown situations are refused.
 */
public fun interface PolicyEngine {
    /** Decides what happens to [call], whose effect was resolved to [intent], in [ctx]. */
    public fun evaluate(
        call: ToolCall,
        intent: ActionIntent,
        ctx: PolicyContext,
    ): Verdict
}

/**
 * What a call would do, resolved before execution from the action, its target element and the
 * current screen.
 *
 * @property kind the effect class.
 * @property pkg package the action happens in, or `null` when not tied to an app.
 * @property target the element acted on, or `null` (point targets, non-screen tools).
 * @property amount money shown near the action, or `null` when none was recognized.
 * @property fieldClass sensitivity of the input field for [IntentKind.INPUT], else `null`.
 */
@Serializable
public data class ActionIntent(
    val kind: IntentKind,
    val pkg: String? = null,
    val target: UiElement? = null,
    val amount: Money? = null,
    val fieldClass: FieldClass? = null,
)

/** Effect classes the security matrix (phase1 §6) is written in. */
@Serializable
public enum class IntentKind {
    /** Observes only. */
    READ,

    /** Moves around (open app, scroll, back) without changing data. */
    NAVIGATE,

    /** Types into a field. */
    INPUT,

    /** Reaches other people: send, post, greet, submit. */
    OUTWARD,

    /** Spends money. */
    SPEND,

    /** Deletes data. */
    DELETE,

    /** Changes system settings. */
    SETTINGS,
}

/** What the policy decided. */
@Serializable
public sealed interface Verdict {
    /** Go ahead. */
    @Serializable
    @SerialName("allow")
    public data object Allow : Verdict

    /** Ask the user first; consent is one-off and bound to the card's call, target and amount. */
    @Serializable
    @SerialName("confirm")
    public data class Confirm(
        /** What the user is asked to approve. */
        val card: ConfirmCard,
    ) : Verdict

    /** The user must do this step themselves. */
    @Serializable
    @SerialName("hand_off")
    public data class HandOff(
        /** Why. */
        val kind: HandoffKind,
        /** Short instruction for the user, in English (the app localizes by [kind]). */
        val hint: String,
    ) : Verdict

    /** Not allowed. */
    @Serializable
    @SerialName("refuse")
    public data class Refuse(
        /** Rule that refused, for logs and the trajectory. */
        val reason: String,
        /** What the model is told instead of a result. */
        val hintForModel: String,
    ) : Verdict
}

/**
 * Everything besides the call that the matrix depends on. Built by the runtime from the session
 * log, so it is reproducible. Not serializable on purpose: it is rebuilt, never persisted (the log
 * records the resulting `policy/verdict` with level and intent).
 *
 * @property taskId the task.
 * @property level user-selected security level.
 * @property unattended no user is watching (scheduled tasks): spending is always refused.
 * @property screen the snapshot the call was planned on, or `null` for non-screen tools.
 * @property operatedPackages apps this task has already acted in.
 * @property outwardConfirmedPackages apps where an outward action was already confirmed in this task.
 */
public data class PolicyContext(
    val taskId: TaskId,
    val level: SecurityLevel,
    val unattended: Boolean,
    val screen: ScreenSnapshot?,
    val operatedPackages: Set<String> = emptySet(),
    val outwardConfirmedPackages: Set<String> = emptySet(),
)

/** Security levels (phase1 §6). Spending needs confirmation at every level. */
@Serializable
public enum class SecurityLevel {
    /** 谨慎: confirm every outward action and the first action in each app. */
    CAUTIOUS,

    /** 标准 (default): confirm the first outward action per app per task. */
    STANDARD,

    /** 自主: outward actions, deletes and settings run without asking. */
    AUTONOMOUS,
}

/**
 * Content of a confirmation card. The approval applies to exactly [callId]; a different call,
 * target or amount needs a new card.
 *
 * @property callId the call being approved.
 * @property kind effect class.
 * @property action what will happen, e.g. "send message" (model- or rule-provided text).
 * @property target who or what it affects, e.g. the contact name, or `null`.
 * @property content what will be sent or typed, masked when sensitive, or `null`.
 * @property amount money involved, or `null`.
 * @property pkg the app, or `null`.
 */
@Serializable
public data class ConfirmCard(
    val callId: ToolCallId,
    val kind: IntentKind,
    val action: String,
    val target: String? = null,
    val content: String? = null,
    val amount: Money? = null,
    val pkg: String? = null,
)

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

/** Sensitivity of an input field. */
@Serializable
public enum class FieldClass {
    /** Ordinary text. */
    PLAIN,

    /** Login password: hand off. */
    PASSWORD,

    /** SMS or app verification code: hand off. */
    VERIFICATION_CODE,

    /** Payment password or PIN: hand off. */
    PAYMENT_PASSWORD,

    /** Bank card number: masked in logs and the screen format. */
    BANK_CARD,

    /** National id number: masked. */
    ID_NUMBER,
}
