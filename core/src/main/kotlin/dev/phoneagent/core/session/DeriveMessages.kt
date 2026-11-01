package dev.phoneagent.core.session

import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
import java.util.TreeMap

/**
 * The model-visible conversation recorded in [records] (phase1 §3.9 rule 1). A request is exactly
 * `header.toRequest(deriveMessages(records))`; this function is pure, so replaying a log rebuilds
 * every request byte for byte.
 *
 * What is visible:
 * - `user/message` and `compaction/summary` → a [Role.USER] message with their parts;
 * - `assistant/message` → a [Role.ASSISTANT] message. An interrupted one (the prefix the user saw
 *   before a stream was cut) is visible only when a user message follows it, so a reply is never
 *   sent with a trailing assistant message (prefill) and a retried attempt never sees the prefix
 *   of the attempt it replaces;
 * - `tool/result` → a [Part.ToolResult]; consecutive results form one [Role.TOOL] message, so the
 *   results answering one reply travel together.
 *
 * Every other event type contributes nothing. Messages are neither merged nor reordered otherwise:
 * consecutive user messages stay separate.
 *
 * A record with [SurfaceOp.Replace] removes what the records in its range contribute (including
 * contributions of earlier replacements placed there) and puts its own content at the start of
 * the range. `compaction/prune` instead rewrites each contribution in its range in place: user
 * content and tool-result content become the prune's parts (role and call ids stay); an assistant
 * message becomes the prune's parts followed by its tool calls. Compaction must replace whole
 * exchanges: this function does not repair a tool result whose call was summarized away.
 */
public fun deriveMessages(records: List<SessionRecord>): List<Message> {
    val view = TreeMap<Long, Visible>()
    for (record in records) {
        val op = record.surfaceOp
        val event = record.event
        when {
            op is SurfaceOp.Replace && event is CompactionPrune -> {
                view.subMap(op.startSeq, true, op.endSeq, true).replaceAll { _, visible -> visible.pruned(event.parts) }
            }

            op is SurfaceOp.Replace -> {
                view.subMap(op.startSeq, true, op.endSeq, true).clear()
                visibleOf(event)?.let { view[op.startSeq] = it }
            }

            else -> {
                visibleOf(event)?.let { view[record.seq] = it }
            }
        }
    }
    return assemble(view.values.toList())
}

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
        -> null
    }

private fun assemble(view: List<Visible>): List<Message> {
    val messages = mutableListOf<Message>()
    val results = mutableListOf<Part>()

    fun flushResults() {
        if (results.isNotEmpty()) messages += Message(Role.TOOL, results.toList())
        results.clear()
    }
    view.forEachIndexed { index, visible ->
        when (visible) {
            is Visible.Result -> {
                results += visible.part
            }

            is Visible.User -> {
                flushResults()
                messages += Message(Role.USER, visible.parts)
            }

            is Visible.Assistant -> {
                flushResults()
                val shown = !visible.interrupted || view.getOrNull(index + 1) is Visible.User
                if (shown) messages += Message(Role.ASSISTANT, visible.parts)
            }
        }
    }
    flushResults()
    return messages.toList()
}
