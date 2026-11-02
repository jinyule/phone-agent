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
    /**
     * The card for [call] asked for [reason]. The action is the model's `reason` (user's language);
     * the target is the conversation title for outward actions, else the label of the control; the
     * content is what the field will hold after typing (with `append`, its current text followed
     * by the typed text), or the draft an outward tap sends, masked.
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
        val title = if (screen != null && intent.kind == IntentKind.OUTWARD) reading.pages.title(screen) else null
        val content = if (step is Step.Type) typed(step, intent) else draft(step, intent, screen)
        return ConfirmCard(
            callId = call.id,
            reason = reason,
            action = call.reason,
            target = (title ?: label).takeIf(String::isNotBlank),
            content = content?.let { Masking.mask(it, intent.fieldClass) },
            amount = intent.amount,
            pkg = intent.pkg,
        )
    }

    private fun typed(
        step: Step.Type,
        intent: ActionIntent,
    ): String = if (step.append) intent.target?.value.orEmpty() + step.text else step.text

    /** What an outward tap or Enter sends: the text in the focused (or only) field. */
    private fun draft(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): String? =
        if (screen != null && intent.kind == IntentKind.OUTWARD && step is ScreenStep) {
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
