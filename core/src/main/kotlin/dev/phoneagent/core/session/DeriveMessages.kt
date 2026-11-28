package dev.phoneagent.core.session

import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role

/**
 * The model-visible conversation recorded in [records] (phase1 §3.9 rule 1, [SessionEvent] "Model
 * view"). A main request is exactly `header.toRequest(deriveMessages(records))` with the latest
 * header; this function is pure, so replaying a log rebuilds every request byte for byte.
 *
 * What is visible:
 * - `user/message` and `compaction/summary` → a [Role.USER] message with their parts;
 * - `assistant/message` → a [Role.ASSISTANT] message. An interrupted one counts only once a user
 *   message follows it before any other reply: a prefix superseded by a retry of the same step is
 *   UI-only, a prefix cut by steer, stop or a final failure joins the view with the next input,
 *   without its [Part.Reasoning]s (an unfinished block cannot be sent back), and not at all when
 *   nothing else is left;
 * - `tool/result` → a [Role.TOOL] message holding its [Part.ToolResult], one message per result
 *   (an adapter whose wire wants them together joins consecutive ones);
 * - `context/reasoning-dropped(upToSeq)` leaves out the reasoning blocks (a [Part.Reasoning] whose
 *   kind `isReasoning`) of replies with seq <= upToSeq; their compaction blocks stay, and a reply
 *   left without parts is left out.
 *
 * Every other event type contributes nothing. Messages are neither merged nor reordered otherwise:
 * consecutive user messages stay separate.
 *
 * Compaction (records with [SurfaceOp.Replace]) acts on contributions by the record that made
 * them, not by where they are shown: a summary removes the contributions of the records in its
 * range (an earlier summary included, by its own seq) and is shown where the first of them was;
 * `compaction/prune` rewrites the contributions of its range in place (user content and tool
 * result content become the prune's parts, role and call ids stay; a reply becomes the prune's
 * parts followed by its tool calls). On the Anthropic path (latest `request/header` with
 * [ModelProtocol.ANTHROPIC_MESSAGES]) history is append-only (AGENTS §3.7, model-layer §5):
 * compaction records change nothing here, the server trims old content. Compaction must replace
 * whole exchanges: this function does not repair a tool result whose call was summarized away.
 */
public fun deriveMessages(records: List<SessionRecord>): List<Message> {
    val appendOnly =
        records.asReversed().firstNotNullOfOrNull { it.event as? RequestHeader }?.protocol ==
            ModelProtocol.ANTHROPIC_MESSAGES
    val view = mutableListOf<Contribution>()
    var droppedUpTo = 0L
    for (record in records) {
        val op = record.surfaceOp
        val event = record.event
        when {
            event is ReasoningDropped -> droppedUpTo = maxOf(droppedUpTo, event.upToSeq)
            op is SurfaceOp.Replace -> if (!appendOnly) compact(view, record, op)
            else -> visibleOf(event)?.let { view += Contribution(record.seq, record.seq, it) }
        }
    }
    return assemble(view.sortedWith(compareBy({ it.position }, { it.source })), droppedUpTo)
}

/** Applies the compaction [record] with range [op] to [view]. */
private fun compact(
    view: MutableList<Contribution>,
    record: SessionRecord,
    op: SurfaceOp.Replace,
) {
    val range = op.startSeq..op.endSeq
    val event = record.event
    if (event is CompactionPrune) {
        view.replaceAll { if (it.source in range) it.copy(visible = it.visible.pruned(event.parts)) else it }
        return
    }
    val removed = view.filter { it.source in range }
    view.removeAll(removed)
    val position = (removed.map { it.position } + op.startSeq).min()
    visibleOf(event)?.let { view += Contribution(position, record.seq, it) }
}

/** What record [source] contributes, shown at [position] (its own seq unless a summary moved it). */
private data class Contribution(
    val position: Long,
    val source: Long,
    val visible: Visible,
)

/** One record's contribution to the model view. */
private sealed interface Visible {
    fun pruned(parts: List<Part>): Visible

    data class User(
        val parts: List<Part>,
    ) : Visible {
        override fun pruned(parts: List<Part>): Visible = User(parts)
    }

    data class Assistant(
        val parts: List<Part>,
        val interrupted: Boolean,
    ) : Visible {
        override fun pruned(parts: List<Part>): Visible =
            Assistant(parts + this.parts.filterIsInstance<Part.ToolCall>(), interrupted)
    }

    data class Result(
        val part: Part.ToolResult,
    ) : Visible {
        override fun pruned(parts: List<Part>): Visible = Result(part.copy(parts = parts))
    }
}

private fun visibleOf(event: SessionEvent): Visible? =
    when (event) {
        is UserMessage -> Visible.User(event.parts)

        is CompactionSummary -> Visible.User(event.parts)

        is AssistantMessage -> Visible.Assistant(event.parts, event.interrupted)

        is ToolResultRecorded -> Visible.Result(event.result.toPart(event.callId))

        is CompactionPrune,
        is TaskStarted,
        is TaskLevelChanged,
        is ReasoningDropped,
        is SideRequest,
        is SideResponse,
        is TurnStarted,
        is TurnEnded,
        is StepStarted,
        is StepEnded,
        is InboxSpliced,
        is SessionResumed,
        is RequestHeader,
        is RequestRetry,
        is AssistantAttempt,
        is ToolCallRecorded,
        is PolicyVerdictRecorded,
        is ApprovalAsked,
        is ApprovalDecided,
        is QuestionAsked,
        is QuestionAnswered,
        is HandoffStarted,
        is HandoffEnded,
        is PromptWithdrawn,
        is CompactionStarted,
        is CompactionEnded,
        is ScreenObserved,
        is ScreenActed,
        is ScreenLaunched,
        is ScreenFrame,
        is ToolStarted,
        is ToolExecuted,
        is TaskStopped,
        -> null
    }

private fun assemble(
    view: List<Contribution>,
    droppedUpTo: Long,
): List<Message> {
    val messages = mutableListOf<Message>()
    view.forEachIndexed { index, contribution ->
        when (val visible = contribution.visible) {
            is Visible.Result -> {
                messages += Message(Role.TOOL, listOf(visible.part))
            }

            is Visible.User -> {
                messages += Message(Role.USER, visible.parts)
            }

            is Visible.Assistant -> {
                val parts =
                    when {
                        // An interrupted prefix carries no opaque block: an unfinished one cannot be sent back.
                        visible.interrupted -> visible.parts.filterNot { it is Part.Reasoning }

                        contribution.source <= droppedUpTo -> visible.parts.withoutReasoning()

                        else -> visible.parts
                    }
                val shown = !visible.interrupted || userFollows(view, index)
                if (shown && parts.isNotEmpty()) messages += Message(Role.ASSISTANT, parts)
            }
        }
    }
    return messages.toList()
}

/** Whether a user message comes after [index] before any other reply. */
private fun userFollows(
    view: List<Contribution>,
    index: Int,
): Boolean =
    view
        .drop(index + 1)
        .map { it.visible }
        .firstOrNull { it !is Visible.Result } is Visible.User

/** Without the reasoning blocks; compaction blocks hold the compacted history and stay. */
private fun List<Part>.withoutReasoning(): List<Part> = filterNot { it is Part.Reasoning && it.kind.isReasoning }
