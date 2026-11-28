package dev.phoneagent.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `context/reasoning-dropped` removes reasoning blocks only; a compaction block holds history (model-layer §5). */
class ReasoningKindTest {
    @Test
    fun `every kind but the compaction block is reasoning`() {
        assertEquals(
            setOf(ReasoningKind.ANTHROPIC_COMPACTION),
            ReasoningKind.entries.filterNot { it.isReasoning }.toSet(),
        )
    }
}
