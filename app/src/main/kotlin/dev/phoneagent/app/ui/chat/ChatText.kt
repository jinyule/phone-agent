package dev.phoneagent.app.ui.chat

import androidx.annotation.StringRes
import dev.phoneagent.app.R
import dev.phoneagent.app.task.AppPlace
import dev.phoneagent.app.task.ChatEntry
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.tool.StepDetail
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import java.util.Locale

/** The label of tool [name], or `null` for a tool this app does not know (shown by its name). */
@StringRes
internal fun toolText(name: String): Int? = TOOL_TEXTS[name]

/**
 * What a step's state says. UNKNOWN is never a failure: the call may have taken effect
 * (`StepState.UNKNOWN`); FAILED says only that it did not succeed, not that nothing happened.
 */
@StringRes
internal fun stateText(state: StepState): Int =
    when (state) {
        StepState.RUNNING -> R.string.step_running
        StepState.SUCCEEDED -> R.string.step_succeeded
        StepState.FAILED -> R.string.step_failed
        StepState.REFUSED -> R.string.step_refused
        StepState.UNKNOWN -> R.string.step_unknown
    }

/**
 * What a step without a card says, from its result status alone. Whether a call could change the
 * phone is not known here, so every status that leaves that open reads as UNKNOWN.
 */
@StringRes
internal fun statusText(status: ToolStatus?): Int =
    when (status) {
        null -> R.string.step_running

        ToolStatus.OK -> R.string.step_succeeded

        ToolStatus.REFUSED -> R.string.step_refused

        ToolStatus.ERROR,
        ToolStatus.INVALID_ARGS,
        ToolStatus.TIMEOUT,
        ToolStatus.CANCELLED,
        ToolStatus.SKIPPED,
        ToolStatus.NOT_STARTED,
        ToolStatus.OUTCOME_UNKNOWN,
        -> R.string.step_unknown
    }

/** The state a step shows: its card's, else the one its status allows. */
@StringRes
internal fun stepStateText(step: ChatEntry.Step): Int = step.card?.state?.let(::stateText) ?: statusText(step.status)

/**
 * The card details the chat shows, each with its label: everything but [HIDDEN_DETAILS]. A key
 * without a label is shown by its name, so a new one is visible before it is translated.
 */
internal fun shownDetails(details: List<StepDetail>): List<StepDetail> = details.filterNot { it.key in HIDDEN_DETAILS }

/** The label of card detail [key], or `null` when it has none. */
@StringRes
internal fun detailText(key: String): Int? = DETAIL_TEXTS[key]

/** What the value of a card detail says: the outcome of a handoff has its own words. */
@StringRes
internal fun detailValueText(detail: StepDetail): Int? =
    if (detail.key ==
        HANDOFF_DETAIL
    ) {
        handoffValueText(detail.value)
    } else {
        null
    }

/** The words for hand_to_user's `handoff` detail [value] (a `HandoffResult`, lowercase). */
@StringRes
internal fun handoffValueText(value: String): Int? =
    HandoffResult.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == value }?.let(::handoffText)

/** The words for how a handoff ended. */
@StringRes
internal fun handoffText(result: HandoffResult): Int =
    when (result) {
        HandoffResult.DONE -> R.string.handoff_done
        HandoffResult.DECLINED -> R.string.handoff_declined
        HandoffResult.EXPIRED -> R.string.handoff_expired
        HandoffResult.UNAVAILABLE -> R.string.handoff_unavailable
        HandoffResult.FAILED -> R.string.handoff_failed
        HandoffResult.NOT_TAKEN_BACK -> R.string.handoff_not_taken_back
    }

/** Where a handed-off app is. */
@StringRes
internal fun placeText(place: AppPlace): Int =
    when (place) {
        AppPlace.WITH_USER -> R.string.place_with_user
        AppPlace.LEFT_WITH_USER -> R.string.place_left_with_user
        AppPlace.MAYBE_WITH_USER -> R.string.place_maybe_with_user
    }

/** The detail of a hand_to_user result naming how its handoff ended (core's `details.handoff`). */
internal const val HANDOFF_DETAIL = "handoff"

/**
 * Card details the chat does not show: the screen ids (`ToolResult.SCREEN`, also the argument naming
 * the screen a call was planned on) and the technical result fields; the card's state already
 * says how the call went.
 */
internal val HIDDEN_DETAILS: Set<String> =
    setOf(ToolResult.SCREEN, "outcome", "effect", "staleReason", "quality", "imageAttached", "met")

private val TOOL_TEXTS: Map<String, Int> =
    mapOf(
        "phone_observe" to R.string.tool_phone_observe,
        "phone_tap" to R.string.tool_phone_tap,
        "phone_type" to R.string.tool_phone_type,
        "phone_scroll" to R.string.tool_phone_scroll,
        "phone_swipe" to R.string.tool_phone_swipe,
        "phone_key" to R.string.tool_phone_key,
        "phone_launch" to R.string.tool_phone_launch,
        "phone_apps" to R.string.tool_phone_apps,
        "phone_intent" to R.string.tool_phone_intent,
        "phone_wait" to R.string.tool_phone_wait,
        "ask_user" to R.string.tool_ask_user,
        "hand_to_user" to R.string.tool_hand_to_user,
        "request_confirmation" to R.string.tool_request_confirmation,
        "end_not_done" to R.string.tool_end_not_done,
    )

private val DETAIL_TEXTS: Map<String, Int> =
    mapOf(
        "detail" to R.string.detail_detail,
        "image" to R.string.detail_image,
        "ref" to R.string.detail_ref,
        "x" to R.string.detail_x,
        "y" to R.string.detail_y,
        "long" to R.string.detail_long,
        "text" to R.string.detail_text,
        "append" to R.string.detail_append,
        "submit" to R.string.detail_submit,
        "direction" to R.string.detail_direction,
        "x1" to R.string.detail_x1,
        "y1" to R.string.detail_y1,
        "x2" to R.string.detail_x2,
        "y2" to R.string.detail_y2,
        "durationMs" to R.string.detail_duration,
        "key" to R.string.detail_key,
        "app" to R.string.detail_app,
        "link" to R.string.detail_link,
        "cold" to R.string.detail_cold,
        "query" to R.string.detail_query,
        "kind" to R.string.detail_kind,
        "time" to R.string.detail_time,
        "label" to R.string.detail_label,
        "seconds" to R.string.detail_seconds,
        "title" to R.string.detail_title,
        "start" to R.string.detail_start,
        "end" to R.string.detail_end,
        "location" to R.string.detail_location,
        "number" to R.string.detail_number,
        "place" to R.string.detail_place,
        "page" to R.string.detail_page,
        "package" to R.string.detail_package,
        "condition" to R.string.detail_condition,
        "timeoutMs" to R.string.detail_timeout,
        "question" to R.string.detail_question,
        "options" to R.string.detail_options,
        "answer" to R.string.detail_answer,
        "cause" to R.string.detail_cause,
        "summary" to R.string.detail_summary,
        "action" to R.string.detail_action,
        "target" to R.string.detail_target,
        "content" to R.string.detail_content,
        "amount" to R.string.detail_amount,
        HANDOFF_DETAIL to R.string.detail_handoff,
    )
