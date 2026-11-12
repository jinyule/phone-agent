package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import kotlin.reflect.KClass

/**
 * The run rules of [SessionInvariants] (phase1 §3.7, stop and recovery): how a run of the task
 * ends and how the next one starts. A stop ends the current run only; the session goes on when
 * the user's own input opens a new turn, never on its own.
 *
 * - Turns and steps pair up ([TurnPairing]): one turn open at a time, numbered upward, and one
 *   step open at a time, in the open turn, numbered upward within it; each end closes the open
 *   one, a turn after its step. So no step crosses a turn, and a turn that ended is closed.
 * - The calls that need a result are the tool calls of every `assistant/message` (an interrupted
 *   one has none) and every dispatched call (`tool/call`). Each has its `tool/result` before
 *   anything closes the step, turn or run that asked for it: `step/end`, `turn/end`,
 *   `task/stopped`, `session/resumed`.
 * - `turn/end(STOPPED)` is followed by `task/stopped`; a log may end between the two (an
 *   unfinished stop, recovered with `task/stopped(ERROR)`). `task/stopped` and `session/resumed`
 *   come with no turn open.
 * - After every `task/stopped` the next record is `session/resumed`: the stopped runtime instance
 *   appends nothing more, and the instance that continues the task takes the log over first.
 * - From every `session/resumed` on (so after every stop), the task waits for the user: until a
 *   `user/message` of kind USER is recorded in a turn, nothing runs. A turn may start, the inbox,
 *   the security level and other user messages may be recorded, the run may stop or be resumed
 *   again, the runtime may record the rules of the new run (`policy/rules`) and read the screen on
 *   its own (`screen/snapshot` without a call), but no step, request, reply, call, prompt, side
 *   request or context change.
 *
 * An ignorable record of a type this build does not know changes none of this: it ends no wait
 * and stands neither between `turn/end(STOPPED)` and its `task/stopped` nor between a
 * `task/stopped` and its `session/resumed`. The store, which only
 * knows the records it reads back, and the reader therefore judge every log alike.
 */
internal class RunRules {
    private val needed = LinkedHashSet<ToolCallId>()
    private val answered = HashSet<ToolCallId>()
    private val turns = TurnPairing()

    /** The last record was `turn/end(STOPPED)`. */
    private var stopUnfinished = false

    /** The cause of the last stop while the log has not been resumed since. */
    private var resumeDue: StopCause? = null

    /** The log was resumed and the user's input has not been recorded in a turn since. */
    private var waiting = false

    /** Why [event] may not follow the records accepted so far, or `null` when it may. */
    fun violation(event: SessionEvent): String? =
        orderViolation(event) ?: turns.violation(event) ?: closeViolation(event) ?: waitViolation(event)

    /** Takes [event] into account for later checks. */
    fun accept(event: SessionEvent) {
        turns.accept(event)
        when {
            event is AssistantMessage -> needed += event.parts.filterIsInstance<Part.ToolCall>().map { it.id }
            event is ToolCallRecorded -> needed += event.call.id
            event is ToolResultRecorded -> answered += event.callId
            event is TurnEnded -> stopUnfinished = event.reason == TurnEndReason.STOPPED
            event is TaskStopped -> stop(event.cause)
            event is SessionResumed -> resume()
            event is UserMessage && event.kind == UserMessageKind.USER -> waiting = false
        }
    }

    private fun stop(cause: StopCause) {
        stopUnfinished = false
        resumeDue = cause
    }

    private fun resume() {
        resumeDue = null
        waiting = true
    }

    private fun orderViolation(event: SessionEvent): String? {
        val due = resumeDue
        return when {
            stopUnfinished && event !is TaskStopped -> "turn/end (STOPPED) is followed by task/stopped, not ${nameOf(
                event,
            )}"

            due != null && event !is SessionResumed -> "after task/stopped ($due) the log is resumed before ${nameOf(
                event,
            )}"

            else -> null
        }
    }

    private fun closeViolation(event: SessionEvent): String? {
        if (event::class !in CLOSING) return null
        val open = needed.filterNot { it in answered }
        return when {
            open.isNotEmpty() -> "${nameOf(event)} leaves calls without a result: ${open.joinToString { it.value }}"
            turns.inTurn && event::class in OUTSIDE_TURNS -> "${nameOf(event)} comes after the open turn ended"
            else -> null
        }
    }

    private fun waitViolation(event: SessionEvent): String? =
        when {
            !waiting || event::class in WHILE_WAITING -> null
            event is ScreenObserved && event.callId == null -> null
            event is UserMessage && turns.inTurn -> null
            event is UserMessage -> "the user's input after a stop or a resume comes in a turn (turn/start first)"
            else -> "after a stop or a resume nothing runs before the user's input: ${nameOf(event)}"
        }

    private fun nameOf(event: SessionEvent): String = SessionEventTypes.typeOf(event)
}

/** Records that close a step, a turn or a run: every call that needs a result has one. */
private val CLOSING: Set<KClass<out SessionEvent>> =
    setOf(StepEnded::class, TurnEnded::class, TaskStopped::class, SessionResumed::class)

/** Records that come with no turn open. */
private val OUTSIDE_TURNS: Set<KClass<out SessionEvent>> = setOf(TaskStopped::class, SessionResumed::class)

/** Records allowed while the task waits for the user, besides user messages in a turn. */
private val WHILE_WAITING: Set<KClass<out SessionEvent>> =
    setOf(
        TaskLevelChanged::class,
        PolicyRulesRecorded::class,
        InboxSpliced::class,
        TaskStopped::class,
        SessionResumed::class,
        TurnStarted::class,
        TurnEnded::class,
    )
