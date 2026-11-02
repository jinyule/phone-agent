package dev.phoneagent.core.policy

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Money
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

    /** Ask the user first; consent is one-off and bound to the whole card. */
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
 * Everything besides the call that the matrix depends on. Not serializable on purpose: it is
 * never persisted (the log records the resulting `policy/verdict` with level and intent), but it
 * must be derivable from the session log alone, so replay reproduces every verdict. The runtime
 * computes it; the policy documents which fields its rules read. Samples of the two package sets
 * on small logs are in the core test sources (`PolicyContextSamples`).
 *
 * Re-check after approval: once the user approves a card and before the call runs, the runtime
 * resolves and evaluates the call again on a fresh screen with [approved] set. The engine returns
 * `Allow` only when the card it computes now equals [approved] as a whole, every [ConfirmCard]
 * field included (`callId`, `reason`, `action`, `target`, `content`, `amount`, `pkg`). Otherwise it
 * returns a new `Confirm`, or `HandOff` / `Refuse` when the fresh screen calls for that. So "one
 * approval is bound to exactly this card" is also decided only here.
 *
 * @property taskId the task.
 * @property level user-selected security level.
 * @property unattended no user is watching (scheduled tasks): spending is always refused.
 * @property screen the screen the call is judged on, derived from the log: the latest screen
 *   recorded under a call that already has its `tool/result` (a `screen/snapshot`, the re-check
 *   screen S' included, or the `after` screen of a `screen/action` or `screen/launch`), provided
 *   no boundary came after it. Boundaries: `handoff/start`, `handoff/end`, `session/resumed`, the
 *   `tool/started` of a non-READ call (by the intent of the verdict it started on), and every
 *   `screen/action` and `screen/launch`, whose own `after` screen counts as recorded after them.
 *   It may be newer than what the model saw. During the re-check after approval it is S',
 *   recorded under this call. `null` when there is no such screen (none read, a read failed, or
 *   a boundary came after the last one), screen tools included: the policy decides.
 * @property operatedPackages the `intent.pkg` of every non-READ call that started in this task
 *   (`tool/started`), taking the intent of the verdict it started on (`verdictSeq`); this includes
 *   the target package of `phone_launch`. A refused or handed-off call, one waiting for or denied
 *   an approval, and one an interceptor answered never started and count nothing.
 * @property outwardConfirmedPackages for every call that started and was executed with
 *   `ToolStatus.OK` (`tool/executed` of the same call id), the package the approval it ran on
 *   names: the last card `ConfirmResult.APPROVED` for that call before the Allow its `tool/started`
 *   points at (`verdictSeq`), and only when that card has reason `ConfirmReason.OUTWARD` and a pkg
 *   equal to the `intent.pkg` of that Allow. A card a later `Confirm` replaced grants nothing, nor
 *   does an approval of another reason (a payment), nor a card for another package than the one
 *   the call ran in. A `request_confirmation` card has no pkg and grants nothing.
 * @property approved the card the user just approved for this call, during the re-check; `null` otherwise.
 */
public data class PolicyContext(
    val taskId: TaskId,
    val level: SecurityLevel,
    val unattended: Boolean,
    val screen: ScreenSnapshot?,
    val operatedPackages: Set<String> = emptySet(),
    val outwardConfirmedPackages: Set<String> = emptySet(),
    val approved: ConfirmCard? = null,
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
