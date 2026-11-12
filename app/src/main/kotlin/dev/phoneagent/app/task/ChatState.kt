package dev.phoneagent.app.task

import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolRegistry
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus

/**
 * The trajectory card of a call, made from its records the way the runtime makes it for its
 * `LoopEvent.Card` (`Tool.presentCall` while it runs, `Tool.presentResult` once it has a result;
 * both are pure), so a view rebuilt from the log shows the same cards.
 */
internal fun interface StepCards {
    /** The card of [call] with [result] (`null` while it runs), or `null` for a tool the task does not have. */
    fun card(
        call: ToolCall,
        result: ToolResult?,
    ): StepCard?

    companion object {
        /** No cards: steps show their tool and status only. */
        val NONE: StepCards = StepCards { _, _ -> null }

        /** The cards of [registry]'s tools. */
        fun of(registry: ToolRegistry): StepCards {
            val byName: Map<String, Tool> = registry.tools.associateBy { it.def.name }
            return StepCards { call, result ->
                byName[call.name]?.let { tool ->
                    if (result == null) tool.presentCall(call) else tool.presentResult(call, result)
                }
            }
        }
    }
}

/**
 * What the chat shows of one task, folded from its session records ([recorded]) and its runtime's
 * [phase]. A pure value: the same records give the same state, so a view that missed events
 * rebuilds it from the log ([ChatState.of]). Cards come from [cards], never from the events.
 *
 * @property entries the conversation in log order: the user's commands, the model's texts, one
 *   step per tool call (in the order of their `tool/call`), and the stops.
 * @property prompts every prompt asked and not yet closed (answered, expired, withdrawn).
 * @property phase the runtime's phase; [Phase.Stopped] once the run stopped.
 * @property lastSeq the seq of the last record folded in (or of a log read, [through]); a record at
 *   or before it changes nothing, so a record seen both in the log and as an event counts once.
 * @property commands how many commands of the user the task took (`user/message` USER): one per
 *   followup, when its turn starts.
 * @property openTurn whether the log has a `turn/start` not yet followed by its `turn/end`.
 * @property cards the cards of the task's tools.
 * @property handoffCalls the tool call each hand-off started for a call was asked by.
 */
internal data class ChatState(
    val entries: List<ChatEntry> = emptyList(),
    val prompts: Map<PromptId, PendingPrompt> = emptyMap(),
    val phase: Phase = Phase.Idle,
    val lastSeq: Long = 0,
    val commands: Int = 0,
    val openTurn: Boolean = false,
    val cards: StepCards = StepCards.NONE,
    val handoffCalls: Map<PromptId, ToolCallId> = emptyMap(),
) {
    /** The prompt the task waits for now, or `null`. */
    val waitingFor: PendingPrompt?
        get() = (phase as? Phase.Waiting)?.promptId?.let(prompts::get)

    /** Whether the runtime can still be stopped: it runs or waits. */
    val stoppable: Boolean
        get() = phase is Phase.Running || phase is Phase.Waiting

    /** Whether the task is at work: a turn is open in the log, or the runtime runs or waits. */
    val busy: Boolean
        get() = openTurn || stoppable

    /** Whether [record] comes with no record missed before it (its seq is at most one past [lastSeq]). */
    fun follows(record: SessionRecord): Boolean = record.seq <= lastSeq + 1

    /** This state with every record up to [seq] taken as seen (a log read that ends with skipped records). */
    fun through(seq: Long): ChatState = if (seq > lastSeq) copy(lastSeq = seq) else this

    /** This state after [record] was appended to the log; unchanged when it was already folded in. */
    fun recorded(record: SessionRecord): ChatState =
        if (record.seq <= lastSeq) this else folded(record).copy(lastSeq = record.seq)

    /** This state with the runtime in [phase]. */
    fun phase(phase: Phase): ChatState = copy(phase = phase)

    /** This state after [event]: its record; cards and streaming fragments are not needed. */
    fun on(event: LoopEvent): ChatState = if (event is LoopEvent.Recorded) recorded(event.record) else this

    companion object {
        /** The state of a task whose log holds [records] (in seq order), its runtime in [phase]. */
        fun of(
            records: Iterable<SessionRecord>,
            phase: Phase,
            cards: StepCards = StepCards.NONE,
        ): ChatState = records.fold(ChatState(cards = cards)) { state, record -> state.recorded(record) }.phase(phase)
    }
}

/** One line of the conversation. */
internal sealed interface ChatEntry {
    /** A command or message of the user. */
    data class UserText(
        val text: String,
    ) : ChatEntry

    /** What the model said, its final answer included. */
    data class AssistantText(
        val text: String,
    ) : ChatEntry

    /**
     * One tool call: the [call] as recorded, its [card], its result [status], the screens withheld
     * from the model while it ran ([withheld], policy-rules §6.4), and where its app is when that
     * is not on the agent's screen ([place]).
     */
    data class Step(
        val callId: ToolCallId,
        val tool: String,
        val call: ToolCall? = null,
        val card: StepCard? = null,
        val status: ToolStatus? = null,
        val withheld: Set<SnapshotId> = emptySet(),
        val place: AppPlace? = null,
    ) : ChatEntry {
        /**
         * Whether the screen the card shows was withheld: the one its result names (the last
         * `ToolResult.SCREEN` detail; an argument of that name, the screen the call was planned on,
         * comes before it), or any of the call's screens when it names none.
         */
        val showsWithheld: Boolean
            get() {
                val shown = card?.details?.lastOrNull { it.key == ToolResult.SCREEN }?.value
                return if (shown == null) withheld.isNotEmpty() else withheld.any { it.value == shown }
            }
    }

    /** The run stopped for [cause]; the next command continues the task. */
    data class Stopped(
        val cause: StopCause,
    ) : ChatEntry
}

/** A prompt the user can answer. */
internal sealed interface PendingPrompt {
    /** The prompt's id. */
    val id: PromptId

    /** Approve or reject [card]. */
    data class Confirm(
        override val id: PromptId,
        val card: ConfirmCard,
    ) : PendingPrompt

    /** Answer [question] (ask_user). */
    data class Ask(
        override val id: PromptId,
        val question: Question,
    ) : PendingPrompt

    /** Do a step of [kind] yourself, for [reason] (hand_to_user or a policy hand-off). */
    data class Handoff(
        override val id: PromptId,
        val kind: HandoffKind,
        val reason: String,
    ) : PendingPrompt
}

/** Where a handed-off app is, when the user should know (wp09-notes, WP02 r6/r8). */
internal enum class AppPlace {
    /** The take-back failed: the app is on the user's screen. */
    WITH_USER,

    /** The run stopped during the handoff: the app stays with the user. */
    LEFT_WITH_USER,

    /** The screen was lost during the handoff: the app may be on the user's screen. */
    MAYBE_WITH_USER,
}
