package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.testing.Screens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScreenAnalyzerTest {
    private val analyzer = ScreenAnalyzer()

    @Test
    fun `delegates to the fingerprint, quality and diff algorithms`() {
        val elements = Screens.CHAT_ELEMENTS
        assertEquals(ScreenFingerprint.of("p", "a", elements, null), analyzer.fingerprint("p", "a", elements, null))
        // Quality is about the tree alone: a secure page can still have a good tree.
        assertEquals(TreeQuality.GOOD, analyzer.quality(elements, Screens.AGENT, setOf(ScreenFlag.SECURE)))
        assertEquals(TreeQuality.EMPTY, analyzer.quality(emptyList(), Screens.AGENT, emptySet()))
        val after = Screens.CHAT.copy(elements = elements.filterNot { it.ref.value == "e6" })
        assertEquals(listOf(ElementRef("e6")), analyzer.diff(Screens.CHAT, after).removed)
    }
}
