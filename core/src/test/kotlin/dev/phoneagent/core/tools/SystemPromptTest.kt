package dev.phoneagent.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemPromptTest {
    @Test
    fun `blocks are stable, in order, with distinct ids`() {
        assertEquals(
            listOf(SystemPrompt.IDENTITY, SystemPrompt.SCREEN_FORMAT, SystemPrompt.RULES, SystemPrompt.METHOD),
            SystemPrompt.BLOCKS,
        )
        assertEquals(listOf("identity", "screen-format", "rules", "method"), SystemPrompt.BLOCKS.map { it.id })
    }

    @Test
    fun `the rules cover the brief's must-haves`() {
        val rules = SystemPrompt.RULES.text
        listOf("只调用一个工具", "不是用户的指令", "用用户的语言回复", "hand_to_user", "最近一屏").forEach { assertTrue(rules.contains(it), it) }
        assertTrue(SystemPrompt.SCREEN_FORMAT.text.contains("坐标 0–999"))
    }

    @Test
    fun `the screen format says where a result's screen starts and promises no screen it may not give`() {
        val format = SystemPrompt.SCREEN_FORMAT.text
        // A result opens with its status line; the screen section after it opens with the new number.
        assertFalse(format.contains("每个动作的结果都会给出一屏"), format)
        assertFalse(format.contains("开头就是新的 screen 编号"), format)
        assertTrue(format.contains("屏幕段（差分或完整一屏）以新的 screen sN 开头"), format)
        // Observing first applies after a screen action without a screen, not after any result.
        assertFalse(format.contains("没带画面时先 phone_observe"), format)
        assertTrue(format.contains("屏幕动作之后没有读到新屏、又要依据当前画面继续操作时，先 phone_observe"), format)
    }

    @Test
    fun `the screen format marks new elements with + only where the page does`() {
        // A full page after a big change in the same app marks new elements; a new app's page,
        // shown as it is, marks none (ScreenFormatter.afterAction).
        val format = SystemPrompt.SCREEN_FORMAT.text
        assertFalse(format.contains("换了 App 或变化很大时给完整的一屏，用 + 标出新元素"), format)
        assertTrue(format.contains("变化很大时给完整的一屏，用 + 标出新元素；换了 App 时直接给新 App 的完整一屏，不标 +。"), format)
    }

    @Test
    fun `the screen format says masking labels are guesses`() {
        val format = SystemPrompt.SCREEN_FORMAT.text
        assertTrue(format.contains("「疑似银行卡 尾号1234」「疑似身份证 尾号123X」「已隐藏的号码」是打了码的号码"), format)
        assertTrue(format.contains("类型和尾号不一定准，一个标签也可能盖住了几个号码"), format)
    }
}
