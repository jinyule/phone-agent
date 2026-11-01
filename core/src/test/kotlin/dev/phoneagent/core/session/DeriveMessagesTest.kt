package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The model view of a log (phase1 §3.9 rule 1). */
class DeriveMessagesTest {
    private val c1 = ToolCallId("c1")
    private val c2 = ToolCallId("c2")
    private val tap1 = Part.ToolCall(c1, "tap", "{}")
    private val tap2 = Part.ToolCall(c2, "tap", "{}")

    private fun user(text: String): UserMessage = UserMessage(UserMessageKind.USER, listOf(Part.Text(text)))

    private fun assistant(
        vararg parts: Part,
        interrupted: Boolean = false,
    ): AssistantMessage =
        AssistantMessage(
            attempt = 1,
            provider = SessionSamples.provider,
            model = SessionSamples.model,
            parts = parts.toList(),
            stopReason = if (interrupted) null else StopReason.END_TURN,
            interrupted = interrupted,
        )

    private fun result(
        id: ToolCallId,
        text: String,
        status: ToolStatus = ToolStatus.OK,
    ): ToolResultRecorded = ToolResultRecorded(id, "tap", ToolResult(status, listOf(Part.Text(text))))

    /** Records numbered from 1, all appended unless [ops] says otherwise. */
    private fun log(
        vararg events: SessionEvent,
        ops: Map<Int, SurfaceOp> = emptyMap(),
    ): List<SessionRecord> =
        events.mapIndexed { index, event ->
            val seq = index + 1L
            SessionRecord(seq, time = seq, event = event, surfaceOp = ops[index + 1] ?: SurfaceOp.Append)
        }

    private fun text(
        role: Role,
        value: String,
    ): Message = Message(role, listOf(Part.Text(value)))

    private fun results(vararg parts: Part.ToolResult): Message = Message(Role.TOOL, parts.toList())

    @Test
    fun `messages, calls and consecutive results form the conversation`() {
        val messages =
            deriveMessages(
                log(
                    user("hi"),
                    TurnStarted(1),
                    assistant(Part.Text("two taps"), tap1, tap2),
                    result(c1, "tapped"),
                    result(c2, "no", ToolStatus.ERROR),
                    user("more"),
                    user("and more"),
                ),
            )
        assertEquals(
            listOf(
                text(Role.USER, "hi"),
                Message(Role.ASSISTANT, listOf(Part.Text("two taps"), tap1, tap2)),
                results(
                    Part.ToolResult(c1, listOf(Part.Text("tapped")), isError = false),
                    Part.ToolResult(c2, listOf(Part.Text("no")), isError = true),
                ),
                text(Role.USER, "more"),
                text(Role.USER, "and more"),
            ),
            messages,
            "consecutive user messages stay separate",
        )
    }

    @Test
    fun `results before an assistant message are closed off`() {
        val messages = deriveMessages(log(assistant(tap1), result(c1, "ok"), assistant(Part.Text("done"))))
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), messages.map { it.role })
        assertEquals(results(Part.ToolResult(c1, listOf(Part.Text("ok")), isError = false)), messages[1])
    }

    @Test
    fun `an interrupted reply is shown only when the user spoke after it`() {
        val prefix = assistant(Part.Text("I will"), interrupted = true)
        assertEquals(
            listOf(text(Role.USER, "go"), text(Role.ASSISTANT, "I will"), text(Role.USER, "stop")),
            deriveMessages(log(user("go"), prefix, user("stop"))),
        )
        assertEquals(listOf(text(Role.USER, "go")), deriveMessages(log(user("go"), prefix)), "no prefill")
        assertEquals(
            listOf(text(Role.USER, "go"), text(Role.ASSISTANT, "done")),
            deriveMessages(log(user("go"), prefix, assistant(Part.Text("done")))),
            "a retried attempt never sees the prefix it replaces",
        )
    }

    @Test
    fun `no other event type is visible`() {
        val visible =
            setOf(UserMessage::class, CompactionSummary::class, AssistantMessage::class, ToolResultRecorded::class)
        val others = SessionSamples.events.filter { it::class !in visible }
        assertTrue(
            listOf(ScreenFrame::class, ScreenLaunched::class, ScreenActed::class).all { type ->
                others.any(type::isInstance)
            },
        )
        assertEquals(emptyList<Message>(), deriveMessages(log(*others.toTypedArray())))
    }

    @Test
    fun `a summary replaces its range and stands at its start`() {
        val summary = CompactionSummary(listOf(Part.Text("summary")))
        val messages =
            deriveMessages(
                log(
                    user("one"),
                    assistant(tap1),
                    result(c1, "ok"),
                    user("two"),
                    summary,
                    ops = mapOf(5 to SurfaceOp.Replace(startSeq = 1, endSeq = 3)),
                ),
            )
        assertEquals(listOf(text(Role.USER, "summary"), text(Role.USER, "two")), messages)
        assertEquals(
            listOf(text(Role.USER, "one"), text(Role.USER, "summary")),
            deriveMessages(log(user("one"), summary)),
            "an appended summary is a user message",
        )
    }

    @Test
    fun `a replacement without content only removes and a later one replaces the earlier`() {
        val summary = CompactionSummary(listOf(Part.Text("first")))
        val second = CompactionSummary(listOf(Part.Text("second")))
        val messages =
            deriveMessages(
                log(
                    user("one"),
                    user("two"),
                    summary,
                    user("three"),
                    second,
                    StepEnded(1, 1, StepOutcome.COMPLETED),
                    ops =
                        mapOf(
                            3 to SurfaceOp.Replace(1, 2),
                            5 to SurfaceOp.Replace(1, 4),
                            6 to SurfaceOp.Replace(1, 5),
                        ),
                ),
            )
        assertEquals(emptyList<Message>(), messages)
        assertEquals(
            listOf(text(Role.USER, "second")),
            deriveMessages(
                log(
                    user("one"),
                    user("two"),
                    summary,
                    user("three"),
                    second,
                    ops = mapOf(3 to SurfaceOp.Replace(1, 2), 5 to SurfaceOp.Replace(1, 4)),
                ),
            ),
            "the earlier summary sat in the later range",
        )
    }

    @Test
    fun `a prune rewrites content in place and keeps roles and call ids`() {
        val stub = listOf<Part>(Part.Text("[pruned]"))
        val messages =
            deriveMessages(
                log(
                    user("big"),
                    assistant(Part.Text("thinking aloud"), tap1),
                    result(c1, "screen dump", ToolStatus.ERROR),
                    user("kept"),
                    CompactionPrune(stub),
                    ops = mapOf(5 to SurfaceOp.Replace(1, 3)),
                ),
            )
        assertEquals(
            listOf(
                Message(Role.USER, stub),
                Message(Role.ASSISTANT, stub + tap1),
                results(Part.ToolResult(c1, stub, isError = true)),
                text(Role.USER, "kept"),
            ),
            messages,
        )
        assertEquals(
            listOf(text(Role.USER, "x")),
            deriveMessages(log(user("x"), CompactionPrune(stub))),
            "an appended prune contributes nothing",
        )
    }
}
