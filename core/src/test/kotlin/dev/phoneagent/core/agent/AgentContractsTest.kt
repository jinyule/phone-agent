package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentContractsTest {
    private val image = Part.Image(BlobRef("e".repeat(64)), "image/png", 1, 1)

    @Test
    fun `user and context inputs hold non-empty text and images`() {
        assertEquals(2, UserInput(listOf(Part.Text("hi"), image)).parts.size)
        assertThrows<IllegalArgumentException> { UserInput(emptyList()) }
        assertThrows<IllegalArgumentException> { UserInput(listOf(Part.ToolCall(ToolCallId("c"), "x", "{}"))) }
        assertEquals("timer", ContextInput("timer", listOf(Part.Text("fired"))).source)
        assertThrows<IllegalArgumentException> { ContextInput(" ", listOf(Part.Text("x"))) }
        assertThrows<IllegalArgumentException> { ContextInput("timer", emptyList()) }
        assertThrows<IllegalArgumentException> {
            ContextInput(
                "timer",
                listOf(Part.ToolCall(ToolCallId("c"), "x", "{}")),
            )
        }
    }

    @Test
    fun `phases and replies are plain values`() {
        val phases =
            listOf(
                Phase.Idle,
                Phase.Running(1, 2),
                Phase.Waiting(WaitReason.USER, PromptId("p")),
                Phase.Waiting(WaitReason.LOCK, null),
                Phase.Done,
                Phase.Stopped(StopCause.USER),
            )
        assertEquals(phases.size, phases.toSet().size, "every phase value is distinct")
        assertEquals(Phase.Running(1, 2), Phase.Running(1, 2))
        val replies =
            listOf(
                UserReply.Approve,
                UserReply.Reject,
                UserReply.Answer("同事"),
                UserReply.Decline,
                UserReply.HandoffDone,
            )
        assertEquals(5, replies.toSet().size)
        assertThrows<IllegalArgumentException> { UserReply.Answer(" ") }
    }

    @Test
    fun `interceptors run in order and may cut the chain`() =
        runTest {
            val calls = mutableListOf<String>()

            fun chainOf(
                interceptors: List<Interceptor<Int, String>>,
                terminal: suspend (Int) -> String,
            ): Chain<Int, String> =
                interceptors.foldRight(Chain(terminal)) { interceptor, next ->
                    Chain { input -> interceptor.intercept(input, next) }
                }

            val double =
                Interceptor<Int, String> { input, chain ->
                    calls += "double"
                    chain.proceed(input * 2)
                }
            val tag =
                Interceptor<Int, String> { input, chain ->
                    calls += "tag"
                    "<" + chain.proceed(input) + ">"
                }
            val cut =
                Interceptor<Int, String> { input, _ ->
                    calls += "cut"
                    "cut at $input"
                }
            assertEquals("<6>", chainOf(listOf(tag, double)) { it.toString() }.proceed(3))
            assertEquals(listOf("tag", "double"), calls)
            calls.clear()
            assertEquals("cut at 6", chainOf(listOf(double, cut, tag)) { error("unreachable") }.proceed(3))
            assertEquals(listOf("double", "cut"), calls)
        }
}
