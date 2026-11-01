package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeInteraction
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where tool output is masked (phase1 §4): text that comes from the device (failure and refusal
 * reasons, exception messages) and step-card summaries. What the user typed into ask_user and the
 * model's own arguments echoed back are not masked.
 */
class MaskingBoundaryTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)
    private val tools = PhaseOneTools.create(formatter).associateBy { it.def.name }
    private val after = Screens.CHAT.copy(id = SnapshotId("s13"))

    private suspend fun tap(screen: FakeScreen): ToolResult =
        tools.getValue("phone_tap").execute(call("phone_tap", """{"screen":"s12","ref":"e10"}"""), FakeContext(screen))

    private fun assertMasked(text: String) {
        assertFalse(text.contains(CARD), text)
        assertTrue(text.contains("「银行卡 尾号7894」"), text)
    }

    @Test
    fun `a failure reason from the device is masked`() =
        runTest {
            val screen =
                FakeScreen().apply {
                    onAct =
                        { _, _ -> ActionOutcome(Status.Failed("card $CARD"), Effect.UNKNOWN, after) }
                }
            assertMasked(tap(screen).text())
        }

    @Test
    fun `a refusal reason from the device is masked`() =
        runTest {
            val screen =
                FakeScreen().apply {
                    onAct =
                        { _, _ -> ActionOutcome(Status.Refused("guard $CARD"), Effect.UNKNOWN) }
                }
            assertMasked(tap(screen).text())
        }

    @Test
    fun `an exception message from the device is masked`() =
        runTest {
            val screen = FakeScreen().apply { unavailable = ScreenUnavailableException("lost $CARD", null) }
            assertMasked(tap(screen).text())
            val observeScreen = FakeScreen().apply { frameError = ScreenUnavailableException("frame $CARD", null) }
            val observed =
                tools
                    .getValue("phone_observe")
                    .execute(call("phone_observe", """{"image":true}"""), FakeContext(observeScreen))
            assertMasked(observed.text())
        }

    @Test
    fun `step-card summaries are masked like their details`() {
        val tool = tools.getValue("phone_type")
        val typed = call("phone_type", """{"screen":"s12","ref":"e9","text":"$CARD"}""", reason = "输入卡号 $CARD")
        val card = tool.presentCall(typed)
        assertMasked(card.summary)
        assertMasked(card.details.single { it.key == "text" }.value)
        assertMasked(tool.presentResult(typed, ToolResult.ok("已输入。")).summary)
    }

    @Test
    fun `the user's own answer and the model's own words are not masked in the result`() =
        runTest {
            val interaction = FakeInteraction(askResult = AskResult.Answered("卡号 $CARD"))
            val asked =
                tools
                    .getValue("ask_user")
                    .execute(call("ask_user", """{"question":"用哪张卡？"}"""), FakeContext(interaction = interaction))
            assertEquals("用户回答：卡号 $CARD", asked.text())
            val confirmed =
                tools.getValue("request_confirmation").execute(
                    call("request_confirmation", """{"action":"绑定卡 $CARD","target":"钱包","content":"绑卡"}"""),
                    FakeContext(),
                )
            assertTrue(confirmed.text().contains("绑定卡 $CARD"), confirmed.text())
        }

    private companion object {
        const val CARD = "6222021234567894"
    }
}
