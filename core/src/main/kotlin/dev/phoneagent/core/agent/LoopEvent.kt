package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.tool.StepCard

/** What [TaskRuntime.events] reports. Observation only; the session log stays authoritative. */
public sealed interface LoopEvent {
    /** [record] was appended to the session log. */
    public data class Recorded(
        /** The record. */
        val record: SessionRecord,
    ) : LoopEvent

    /** A streaming fragment of the model reply in [turn], [step], [attempt]. */
    public data class Streamed(
        /** The turn. */
        val turn: Int,
        /** The step. */
        val step: Int,
        /** The attempt within the step. */
        val attempt: Int,
        /** The fragment. */
        val delta: Delta,
    ) : LoopEvent

    /** The trajectory card of [callId]: from `presentCall` when it starts, `presentResult` when it ends. */
    public data class Card(
        /** The call. */
        val callId: ToolCallId,
        /** The card. */
        val card: StepCard,
    ) : LoopEvent
}
