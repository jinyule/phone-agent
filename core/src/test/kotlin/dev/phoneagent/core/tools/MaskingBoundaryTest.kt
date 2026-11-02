package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
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
import org.junit.jupiter.api.assertAll
import java.util.Locale

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

    @Test
    fun `numbers grouped by space runs, U+0085, zero-width marks or minus signs are masked at every exit`() =
        runTest {
            assertAll(GROUPED.flatMap { raw -> exits("卡号 $raw") }.map(::assertCardMasked))
        }

    @Test
    fun `a number in the gap before a card does not shift the mask off the card at any exit`() =
        runTest {
            assertAll(NEIGHBOURED.flatMap { raw -> exits(raw) }.map(::assertCardMasked))
        }

    /** [deviceText] through the five exits: failure, refusal, exception, step-card summary, FULL screen. */
    private suspend fun exits(deviceText: String): List<Pair<String, String>> {
        val shown = deviceText.map { if (it.isLetterOrDigit()) "$it" else "\\u%04X".format(Locale.ROOT, it.code) }
        val label = shown.joinToString("")
        val failed =
            FakeScreen().apply {
                onAct = { _, _ -> ActionOutcome(Status.Failed(deviceText), Effect.UNKNOWN, after) }
            }
        val refused =
            FakeScreen().apply {
                onAct = {
                    _,
                    _,
                    ->
                    ActionOutcome(Status.Refused(deviceText), Effect.UNKNOWN)
                }
            }
        val lost = FakeScreen().apply { unavailable = ScreenUnavailableException("lost $deviceText", null) }
        val typed = call("phone_type", """{"screen":"s12","ref":"e9","text":"x"}""", reason = "输入 $deviceText")
        val card = Screens.snapshot("s1", listOf(Screens.el("e1", ElementRole.TEXT, deviceText)))
        return listOf(
            "failure $label" to tap(failed).text(),
            "refusal $label" to tap(refused).text(),
            "exception $label" to tap(lost).text(),
            "summary $label" to tools.getValue("phone_type").presentCall(typed).summary,
            "screen $label" to formatter.page(card, SnapshotDetail.FULL),
        )
    }

    private fun assertCardMasked(exit: Pair<String, String>): () -> Unit =
        {
            val (name, text) = exit
            assertFalse(listOf("6222", "0212", "3456").any(text::contains), "$name: $text")
            assertTrue(text.contains("「银行卡 尾号7894」"), "$name: $text")
        }

    private companion object {
        const val CARD = "6222021234567894"

        /** Groupings the device may send verbatim (no line folding happens before masking). */
        val GROUPED =
            listOf(
                "6222   0212   3456   7894",
                "6222\u00850212\u00853456\u00857894",
                "6222\r\n  0212\r\n  3456\r\n  7894",
                "6222\u200B0212\u200B3456\u200B7894",
                "6222\u22120212\u22123456\u22127894",
                "6222\uFE630212\uFE633456\uFE637894",
            )

        /** A number in the run before the card that makes a valid card with its first groups. */
        val NEIGHBOURED =
            listOf(
                "联系人 张三\n138 7586 0589\n    6222 0212 3456 7894",
                "105   6222 0212 3456 7894",
            )
    }
}
