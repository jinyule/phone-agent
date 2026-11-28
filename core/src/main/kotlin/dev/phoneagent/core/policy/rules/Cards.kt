package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.ToolCall

/**
 * Confirmation cards (policy-rules.md §6.3). A card is a pure function of the call, its intent
 * and the screen, so evaluating the same call on an unchanged screen gives an equal card: only
 * that equality lets an approval through on the re-check (`PolicyContext.approved`).
 */
internal class Cards(
    private val reading: Reading,
) {
    private val intents = ScreenIntents(reading)

    /**
     * The card for [call] asked for [reason]. The action is the model's `reason` (user's language).
     * When the call may send something ([sends]), the target is the conversation title (the
     * recipient), followed by the label of the control in brackets when the call is asked about
     * for something else ("张三（发送并删除原图）"); else the label alone. The content is what
     * the field will hold after typing (with `append`, its current text followed by the typed
     * text), or the draft a tap or Enter that may send sends, masked.
     */
    fun forCall(
        call: ToolCall,
        step: Step,
        intent: ActionIntent,
        reason: ConfirmReason,
        screen: ScreenSnapshot?,
    ): ConfirmCard {
        val target = intent.target
        val label = if (screen == null || target == null) "" else Elements.label(target, screen)
        val sends = screen != null && sends(step, intent, screen)
        val title = if (sends) reading.pages.title(screen) else null
        val content = if (step is Step.Type) typed(step, intent) else draft(step, sends, screen)
        return ConfirmCard(
            callId = call.id,
            reason = reason,
            action = call.reason,
            target = target(title, label, intent).takeIf(String::isNotBlank),
            content = content?.let { Masking.mask(it, intent.fieldClass) },
            amount = intent.amount,
            pkg = intent.pkg,
        )
    }

    /**
     * [step] may send something on [screen]: it reaches out, or whatever its intent became (a
     * stricter reading, a declared risk class) it also sends ([ScreenIntents.sends]; on a screen
     * that cannot be read, typing that submits and Enter).
     */
    private fun sends(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot,
    ): Boolean =
        when {
            intent.kind == IntentKind.OUTWARD -> true
            step !is ScreenStep -> false
            reading.readable(screen) -> intents.sends(step, screen)
            else -> submits(step)
        }

    /** [title] and, for a call asked about for more than sending, what [label] says; [label] without a title. */
    private fun target(
        title: String?,
        label: String,
        intent: ActionIntent,
    ): String =
        when {
            title == null -> label
            intent.kind == IntentKind.OUTWARD || label.isBlank() || label == title -> title
            else -> "$title（$label）"
        }

    private fun typed(
        step: Step.Type,
        intent: ActionIntent,
    ): String = if (step.append) intent.target?.value.orEmpty() + step.text else step.text

    /** What a tap or Enter that [sends] sends: the text in the focused (or only) field. */
    private fun draft(
        step: Step,
        sends: Boolean,
        screen: ScreenSnapshot?,
    ): String? =
        if (screen != null && sends && step is ScreenStep) {
            reading.fields
                .typingTarget(screen)
                ?.value
                ?.takeIf(String::isNotBlank)
        } else {
            null
        }

    /**
     * The card `request_confirmation` asks for, from its own arguments. It has no pkg, so an
     * approval never counts as the per-app outward confirmation (it may only add confirmations).
     */
    fun requested(
        call: ToolCall,
        step: Step.Confirmation,
        intent: ActionIntent,
    ): ConfirmCard =
        ConfirmCard(
            callId = call.id,
            reason = ConfirmReason.REQUESTED_BY_MODEL,
            action = step.action,
            target = step.target?.takeIf(String::isNotBlank),
            content = step.content?.takeIf(String::isNotBlank)?.let { Masking.mask(it, null) },
            amount = intent.amount,
        )
}
