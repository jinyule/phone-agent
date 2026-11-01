package dev.phoneagent.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
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
}
