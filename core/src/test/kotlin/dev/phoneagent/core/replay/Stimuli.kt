package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.tool.ToolStatus

/**
 * One thing the user or the app did to a running session (WP08 design §5), given by
 * [SessionDriver] in order. Each one that is not an answer is given when the log has reached
 * [after]: the seq of the last record before its effect in the recording (read from a fixture,
 * [UserScript]), or in an authored script the seq to give it at (a steer while a reply streams);
 * `null` in an authored script gives it once the loop no longer runs on its own.
 */
sealed interface Stimulus {
    /** The log position it is given at, or `null` (authored: see [SessionDriver]). */
    val after: Long?

    /** `followup`, `steer` or `inject`, as [entry] says. */
    data class Input(
        val entry: InboxEntry,
        override val after: Long? = null,
    ) : Stimulus

    /** `changeLevel`. */
    data class Level(
        val level: SecurityLevel,
        override val after: Long? = null,
    ) : Stimulus

    /** `stop` for [cause], [StopCause.USER] or [StopCause.LIMIT] (the ones the app gives). */
    data class Stop(
        val cause: StopCause,
        override val after: Long? = null,
    ) : Stimulus

    /** The runtime's scope is cancelled: the app shuts down. */
    data class Shutdown(
        override val after: Long? = null,
    ) : Stimulus

    /** A new runtime takes the stopped session over (`AgentLoop.resume`). */
    data class Resume(
        override val after: Long? = null,
    ) : Stimulus

    /** [reply] to the [prompt]-th prompt of the session (from 1), once it waits for it. */
    data class Answer(
        val prompt: Int,
        val reply: UserReply,
    ) : Stimulus {
        override val after: Long? get() = null
    }
}

/**
 * The user's and the app's side of a recording, read from its log: what [SessionDriver] gives a
 * replay, in the log's order. Prompts that ended without the user (expired, unavailable,
 * withdrawn) give nothing: the runtime ends them again.
 *
 * What a log cannot say fails the load with [IllegalArgumentException]: a hand-off whose move
 * failed (`FAILED`, `NOT_TAKEN_BACK`: they need a SurfaceManager, which replay does not have yet,
 * design-review 7), and a resume that follows no `task/stopped` (a crash, which replay cannot
 * cause).
 */
object UserScript {
    /** The stimuli of [records]. */
    fun of(records: List<SessionRecord>): List<Stimulus> {
        val prompts = mutableListOf<Prompt>()
        return records.mapIndexedNotNull { index, record ->
            val event = record.event
            asked(event)?.let { prompts.add(Prompt(it, prompts.size + 1)) }
            when {
                event is InboxSpliced -> {
                    require(
                        event.added.size <= 1,
                    ) { "seq ${record.seq}: one input per inbox/spliced, got ${event.added}" }
                    event.added.singleOrNull()?.let { Stimulus.Input(it, record.seq - 1) }
                }

                event is TaskLevelChanged -> {
                    Stimulus.Level(event.level, record.seq - 1)
                }

                event is TaskStopped -> {
                    stopped(event.cause, stopAnchor(records, index))
                }

                event is SessionResumed -> {
                    val before = records.getOrNull(index - 1)
                    require(before?.event is TaskStopped) {
                        "seq ${record.seq}: session/resumed after ${before?.event?.let(::typeOf) ?: "nothing"}: " +
                            "a resume after a crash, which replay cannot cause"
                    }
                    Stimulus.Resume(record.seq - 1)
                }

                else -> {
                    decided(record, prompts)
                }
            }
        }
    }

    private class Prompt(
        val id: String,
        val number: Int,
    )

    private fun asked(event: SessionEvent): String? =
        when {
            event is ApprovalAsked -> event.promptId.value
            event is QuestionAsked -> event.promptId.value
            event is HandoffStarted -> event.promptId.value
            else -> null
        }

    private fun decided(
        record: SessionRecord,
        prompts: List<Prompt>,
    ): Stimulus? {
        val event = record.event
        val (id, reply) =
            when {
                event is ApprovalDecided -> event.promptId.value to reply(event.result)
                event is QuestionAnswered -> event.promptId.value to reply(event.result)
                event is HandoffEnded -> event.promptId.value to reply(record.seq, event.result)
                else -> return null
            }
        val prompt = requireNotNull(prompts.firstOrNull { it.id == id }) { "seq ${record.seq}: $id was never asked" }
        return reply?.let { Stimulus.Answer(prompt.number, it) }
    }

    private fun reply(result: ConfirmResult): UserReply? =
        when (result) {
            ConfirmResult.APPROVED -> UserReply.Approve
            ConfirmResult.REJECTED -> UserReply.Reject
            ConfirmResult.EXPIRED, ConfirmResult.UNAVAILABLE -> null
        }

    private fun reply(result: AskResult): UserReply? =
        when (result) {
            is AskResult.Answered -> UserReply.Answer(result.text)
            AskResult.Declined -> UserReply.Decline
            AskResult.Expired, AskResult.Unavailable -> null
        }

    private fun reply(
        seq: Long,
        result: HandoffResult,
    ): UserReply? =
        when (result) {
            HandoffResult.DONE -> {
                UserReply.HandoffDone
            }

            HandoffResult.DECLINED -> {
                UserReply.Decline
            }

            HandoffResult.EXPIRED, HandoffResult.UNAVAILABLE -> {
                null
            }

            HandoffResult.FAILED, HandoffResult.NOT_TAKEN_BACK -> {
                throw IllegalArgumentException(
                    "seq $seq: a hand-off ended $result, a failed move of a SurfaceManager; replay has none yet",
                )
            }
        }

    private fun stopped(
        cause: StopCause,
        after: Long,
    ): Stimulus? =
        when (cause) {
            StopCause.USER, StopCause.LIMIT -> Stimulus.Stop(cause, after)

            StopCause.SHUTDOWN -> Stimulus.Shutdown(after)

            // The runtime stops itself on a failure; replay sees whether it does again.
            StopCause.ERROR -> null
        }

    /**
     * Where the stop at [index] was given: the record before the ones the stop itself writes
     * (`AgentLoop` shutdown: the inbox cleared, an interrupted reply, the prompt withdrawn, an
     * action cut off, the open calls CANCELLED, the step and the turn closed, `task/stopped`).
     */
    private fun stopAnchor(
        records: List<SessionRecord>,
        index: Int,
    ): Long {
        var at = index - 1
        while (at >= 0 && writtenByStop(records[at].event)) at--
        return records.getOrNull(at)?.seq ?: 0
    }

    private fun writtenByStop(event: SessionEvent): Boolean =
        when {
            event is TurnEnded -> event.reason == TurnEndReason.STOPPED
            event is StepEnded -> event.outcome == StepOutcome.CANCELLED
            event is ToolResultRecorded -> event.result.status == ToolStatus.CANCELLED
            event is PromptWithdrawn -> true
            event is AssistantMessage -> event.interrupted
            event is InboxSpliced -> event.added.isEmpty() && event.removed > 0
            event is ScreenActed -> cutOff(event.outcome)
            event is ScreenLaunched -> cutOff(event.outcome)
            else -> false
        }

    private fun cutOff(outcome: ActionOutcome): Boolean =
        (outcome.status as? ActionOutcome.Status.Failed)?.reason == DeviceScript.UNFINISHED

    private fun typeOf(event: SessionEvent): String = event::class.simpleName.orEmpty()
}
