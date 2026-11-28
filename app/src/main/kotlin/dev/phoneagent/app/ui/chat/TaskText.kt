package dev.phoneagent.app.ui.chat

import androidx.annotation.StringRes
import dev.phoneagent.app.R
import dev.phoneagent.app.task.TaskProblem
import dev.phoneagent.app.task.TaskStatus
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.session.StopCause

/** Why a confirmation is asked. */
@StringRes
internal fun reasonText(reason: ConfirmReason): Int =
    when (reason) {
        ConfirmReason.SPEND -> R.string.confirm_spend
        ConfirmReason.OUTWARD -> R.string.confirm_outward
        ConfirmReason.DELETE -> R.string.confirm_delete
        ConfirmReason.SETTINGS -> R.string.confirm_settings
        ConfirmReason.FIRST_IN_APP -> R.string.confirm_first_in_app
        ConfirmReason.REQUESTED_BY_MODEL -> R.string.confirm_requested
    }

/** What the user is asked to do in a handoff. */
@StringRes
internal fun kindText(kind: HandoffKind): Int =
    when (kind) {
        HandoffKind.LOGIN -> R.string.handoff_kind_login
        HandoffKind.PASSWORD -> R.string.handoff_kind_password
        HandoffKind.CAPTCHA -> R.string.handoff_kind_captcha
        HandoffKind.PAYMENT -> R.string.handoff_kind_payment
        HandoffKind.SECURE_PAGE -> R.string.handoff_kind_secure_page
        HandoffKind.CHOOSE -> R.string.handoff_kind_choose
        HandoffKind.OTHER -> R.string.handoff_kind_other
    }

/** Why a run stopped; each says that the next message continues. */
@StringRes
internal fun stopText(cause: StopCause): Int =
    when (cause) {
        StopCause.USER -> R.string.stopped_user
        StopCause.SHUTDOWN -> R.string.stopped_shutdown
        StopCause.LIMIT -> R.string.stopped_limit
        StopCause.ERROR -> R.string.stopped_error
    }

/** What kept a command from reaching a task. */
@StringRes
internal fun problemText(problem: TaskProblem): Int =
    when (problem) {
        TaskProblem.NO_MODEL -> R.string.task_problem_no_model
        TaskProblem.KEY_MISSING -> R.string.task_problem_key_missing
        TaskProblem.STORAGE -> R.string.task_problem_storage
        TaskProblem.UNEXPECTED -> R.string.task_problem_unexpected
    }

/**
 * What the chat's status row says while the task is [status], `null` for nothing. A wait is told
 * in the words of the task's notification (`TaskService.textOf`).
 */
@StringRes
internal fun taskStatusText(status: TaskStatus): Int? =
    when (status) {
        TaskStatus.IDLE -> null
        TaskStatus.STARTING -> R.string.chat_starting
        TaskStatus.WORKING -> R.string.chat_working
        TaskStatus.WAITING_ANSWER -> R.string.task_waiting_user
        TaskStatus.WAITING_HANDOFF -> R.string.task_waiting_handoff
        TaskStatus.WAITING_UNLOCK -> R.string.task_waiting_lock
    }

/** [money] as digits with two decimals (`Money.minor` is hundredths of the unit, whatever the currency). */
internal fun amountDigits(money: Money): String =
    "${money.minor / CENTS}.${(money.minor % CENTS).toString().padStart(2, '0')}"

private const val CENTS = 100
