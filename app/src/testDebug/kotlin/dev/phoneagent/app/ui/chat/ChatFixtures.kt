package dev.phoneagent.app.ui.chat

import dev.phoneagent.app.task.ChatState
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A tap the model planned. */
internal val TAP =
    ToolCall(
        ToolCallId("call-1"),
        TaskId("t"),
        SessionId("s"),
        "phone_tap",
        JsonObject(mapOf("ref" to JsonPrimitive("e2"))),
        "tap send",
    )

/** The chat of [events] (seq 1, 2, …) in [phase], every step showing [card]. */
internal fun chatOf(
    phase: Phase,
    card: StepCard?,
    vararg events: SessionEvent,
): ChatState {
    val records = events.mapIndexed { index, event -> SessionRecord(index + 1L, 0, event) }
    return ChatState.of(records, phase) { _, _ -> card }
}

/** The chat's actions, recorded. */
internal class RecordingChatActions : ChatActions {
    val sent = mutableListOf<String>()
    val answers = mutableListOf<Pair<PromptId, UserReply>>()
    var stops = 0
    var newTasks = 0
    var models = 0

    override fun send(text: String) {
        sent += text
    }

    override fun answer(
        id: PromptId,
        reply: UserReply,
    ) {
        answers += id to reply
    }

    override fun stop() {
        stops++
    }

    override fun newTask() {
        newTasks++
    }

    /** The chat's way to the model settings. */
    fun openModels() {
        models++
    }
}
