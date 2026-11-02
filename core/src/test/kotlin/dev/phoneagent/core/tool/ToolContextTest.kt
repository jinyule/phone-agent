package dev.phoneagent.core.tool

import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ToolInteraction
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.ports.forTools
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** B4 (r2): what tools get to reach the user cannot show a confirmation card. */
class ToolContextTest {
    @Test
    fun `the interaction a tool gets can ask and hand off, but not confirm`() {
        val type = ToolContext::class.java.getMethod("getInteraction").returnType
        val methods =
            type.methods
                .map { it.name }
                .filterNot { it in OBJECT_METHODS }
                .toSet()
        assertEquals(setOf("ask", "handOff"), methods, "methods reachable through ToolContext.interaction")
    }

    @Test
    fun `the view for tools passes asks and handoffs through and cannot be cast back to confirm`() =
        runTest {
            val full = Recording()
            val view: ToolInteraction = full.forTools()
            assertFalse(view is UserInteraction, "a cast must not reach confirm")
            assertEquals(AskResult.Answered("同事"), view.ask(Question("哪个张三？")))
            assertEquals(HandoffResult.DONE, view.handOff(HandoffKind.LOGIN, "请登录"))
            assertEquals(listOf("ask 哪个张三？", "handOff LOGIN 请登录"), full.calls)
        }

    /** Records what reaches the runtime's own interaction. */
    private class Recording : UserInteraction {
        val calls = mutableListOf<String>()

        override suspend fun ask(question: Question): AskResult {
            calls += "ask ${question.text}"
            return AskResult.Answered("同事")
        }

        override suspend fun confirm(card: ConfirmCard): ConfirmResult {
            calls += "confirm ${card.callId}"
            return ConfirmResult.APPROVED
        }

        override suspend fun handOff(
            kind: HandoffKind,
            reason: String,
        ): HandoffResult {
            calls += "handOff $kind $reason"
            return HandoffResult.DONE
        }
    }

    private companion object {
        val OBJECT_METHODS =
            Any::class.java.methods
                .map { it.name }
                .toSet()
    }
}
