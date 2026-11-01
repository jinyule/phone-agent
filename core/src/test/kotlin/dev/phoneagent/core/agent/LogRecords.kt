package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/** Hand-built logs for the pure functions of the loop. */
internal object LogRecords {
    /** [events] as records numbered from 1. */
    fun log(vararg events: SessionEvent): List<SessionRecord> =
        events.mapIndexed { index, event -> SessionRecord(seq = index + 1L, time = 0, event = event) }

    /** A reply calling [ids] (tool `t`). */
    fun reply(vararg ids: String): AssistantMessage =
        AssistantMessage(
            attempt = 1,
            provider = SessionSamples.provider,
            model = SessionSamples.model,
            parts = ids.map { Part.ToolCall(ToolCallId(it), "t", "{}") },
            stopReason = StopReason.TOOL_USE,
        )

    fun dispatched(id: String): ToolCallRecorded =
        ToolCallRecorded(
            ToolCall(
                ToolCallId(id),
                SessionSamples.task,
                SessionSamples.session,
                "t",
                JsonObject(emptyMap()),
                "r",
            ),
        )

    fun answered(
        id: String,
        result: ToolResult = ToolResult.ok("done"),
    ): ToolResultRecorded = ToolResultRecorded(ToolCallId(id), "t", result)
}
